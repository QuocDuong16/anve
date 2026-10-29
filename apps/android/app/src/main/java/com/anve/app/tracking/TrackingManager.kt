package com.anve.app.tracking

import android.os.SystemClock
import com.anve.app.data.api.ApiException
import com.anve.app.data.api.UserDto
import com.anve.app.data.auth.DeviceRegistrationProvider
import com.anve.app.data.auth.ConsentMutationCoordinator
import com.anve.app.data.auth.PendingConsentOperation
import com.anve.app.data.auth.SessionStorage
import com.anve.app.data.auth.StoredSession
import com.anve.app.data.auth.hasConsentConflict
import com.anve.app.data.auth.newPendingConsentOperation
import com.anve.app.data.location.LocationQueueRepository
import com.anve.app.data.location.LocationSyncScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Outcome of a consent switch, separating local effect from server confirmation. */
data class ConsentResult(
    val enabled: Boolean,
    val serverConfirmed: Boolean,
    val message: String? = null,
    /** The account returned by a confirmed write, so the UI can apply its fresh state. */
    val user: UserDto? = null,
    /** A newer server consent state conflicts with this operation and needs a fresh user action. */
    val conflict: Boolean = false,
    /** True only when an unresolved operation is safely stored for later reconciliation. */
    val pendingOperationPersisted: Boolean = false,
)

private data class ConsentOffPreparation(
    val session: StoredSession?,
    val ownerUserId: String?,
    val operation: PendingConsentOperation?,
    val outboxPersisted: Boolean,
    val queuePurged: Boolean,
    val serviceStopFailure: Exception?,
    val storageFailure: Exception?,
    val cancellation: CancellationException?,
)

private enum class TrackingStartAdmission {
    ALREADY_STARTING,
    DENIED,
    SERVICE_REFUSED,
    ACCEPTED,
}

/**
 * The consent surface the account screen uses. Keeping it an interface
 * avoids pulling the whole tracking stack into that screen's tests.
 */
interface ConsentController {
    suspend fun setConsent(enabled: Boolean): ConsentResult
    suspend fun recordServerConsent(enabled: Boolean)
    suspend fun recordServerConsent(user: UserDto) = recordServerConsent(user.locationSharingEnabled)
}

/** The control surface the tracking screen drives. */
interface TrackingControl : ConsentController {
    val status: StateFlow<TrackingStatus>
    fun startTracking()
    fun stopTracking()
    fun retrySync()
    fun refreshStatus()
}

/**
 * The tracking coordinator. It owns the policy state machine, the provider
 * subscription, the foreground service lifecycle, the durable queue writes
 * and the *local* consent semantics. Server authorization remains the
 * authority: the manager never claims a server state it did not observe.
 *
 * State rules that the UI depends on:
 * - `consentEnabled` is what the user agreed to locally; it is not proof
 *   that the server accepted it (`consentSyncPending`).
 * - `activelyTracking` is true only when the service and provider are both
 *   actually running. Calling `startForegroundService` alone never flips it.
 * - A process restart sets `recoveryRequired`; the manager does not pretend
 *   Android restarted tracking.
 */
class TrackingManager(
    private val server: TrackingServerGateway,
    private val sessions: SessionStorage,
    private val deviceIdentity: DeviceRegistrationProvider,
    private val queue: LocationQueueRepository,
    private val store: TrackingStore,
    private val permissions: TrackingPermissions,
    private val provider: LocationProvider,
    private val connectivity: ConnectivityMonitor,
    private val service: TrackingServiceController,
    private val syncScheduler: LocationSyncScheduler,
    private val scope: CoroutineScope,
    private val metrics: TrackingMetricsStore = NoOpTrackingMetricsStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsedNow: () -> Long = { SystemClock.elapsedRealtime() },
    private val batterySaver: () -> Boolean = { false },
    private val consentMutations: ConsentMutationCoordinator = ConsentMutationCoordinator(),
    private val pendingConsentScheduler: com.anve.app.data.auth.PendingRevocationScheduler =
        com.anve.app.data.auth.NoOpPendingRevocationScheduler,
) : TrackingControl {

    private val _status = MutableStateFlow(TrackingStatus())
    override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

    private val policy = AdaptiveTrackingPolicy()
    private val requestOptions = MutableStateFlow(policy.requestOptions())

    private var collectionJob: Job? = null
    private var tickerJob: Job? = null
    private var queueCountJob: Job? = null
    private var starting = false
    private var startTimeoutJob: Job? = null
    @Volatile private var sessionGeneration = 0L
    private var sessionOwner: String? = null
    private var lastSession: com.anve.app.data.auth.StoredSession? = null
    private var clientDeviceId: String? = null
    private var initialIntentLoaded = false
    private var lastMetricsTickElapsed = elapsedNow()
    private var serviceStartedAtElapsed: Long? = null

    init {
        observeLocalState()
        observeSession()
        observeConnectivity()
        // Establish the OS snapshot immediately so the UI never shows a
        // permission state from a previous process.
        refreshStatus()
    }

    private fun observeLocalState() {
        scope.launch {
            store.state.collectLatest { local ->
                val wasInitial = !initialIntentLoaded
                initialIntentLoaded = true
                val owner = currentOwnerId()
                val ownerMatches = owner != null && local.consentOwnerUserId == owner
                val pendingConsent = owner?.let { userId ->
                    sessions.pendingConsentOperations().firstOrNull { it.ownerUserId == userId }
                }
                val pendingRevocation = ownerMatches && local.pendingConsentRevocation
                if (owner != null && pendingConsent != null) {
                    consentMutations.restoreBlockedAdmission(owner, pendingConsent.generation)
                } else if (owner != null && pendingRevocation) {
                    consentMutations.restoreBlockedAdmission(owner, generation = 0)
                }
                val admissionOpen = ownerMatches && pendingConsent == null && !pendingRevocation &&
                    !consentMutations.isLocationAdmissionClosed(owner)
                _status.update { current ->
                    current.copy(
                        consentEnabled = ownerMatches && local.localConsentConfirmed &&
                            !pendingRevocation && pendingConsent == null && admissionOpen &&
                            current.serverConsentEnabled != false,
                        consentConfirmedByServer = ownerMatches && local.localConsentConfirmed,
                        serverConsentEnabled = if (ownerMatches && local.localConsentConfirmed) {
                            current.serverConsentEnabled ?: true
                        } else if (owner == null || !ownerMatches) {
                            null
                        } else {
                            current.serverConsentEnabled
                        },
                        locationAdmissionOpen = admissionOpen,
                        consentSyncPending = pendingRevocation || pendingConsent != null,
                        consentConflict = pendingConsent?.hasConsentConflict == true,
                        conflictServerConsent = pendingConsent?.conflictServerEnabled,
                        trackingRequested = ownerMatches && local.trackingRequested,
                        lastUploadAtMillis = local.lastSuccessfulUploadAtMillis,
                        privacyDiscardedPoints = ownerMatches && local.lastSyncErrorCode in setOf("LOCATION_CAPTURE_GENERATION_EXPIRED", "LOCATION_HISTORY_DELETED"),
                        recoveryRequired = if (wasInitial && local.trackingRequested && !current.running) {
                            true
                        } else {
                            current.recoveryRequired
                        },
                        operational = if (wasInitial && local.trackingRequested && !current.running) {
                            OperationalState.PAUSED
                        } else {
                            current.operational
                        },
                        lastError = if (pendingConsent?.hasConsentConflict == true) {
                            TrackingProblem.CONSENT_CONFLICT
                        } else if (current.lastError == TrackingProblem.CONSENT_CONFLICT) {
                            null
                        } else {
                            current.lastError
                        },
                    )
                }
                if (wasInitial && local.trackingRequested && !status.value.running) {
                    _status.update {
                        it.copy(lastError = TrackingProblem.RECOVERY_REQUIRED, recoveryRequired = true)
                    }
                }
            }
        }
    }

    private fun observeSession() {
        scope.launch {
            sessions.session.collectLatest { session ->
                if (session == null) {
                    sessionGeneration++
                    migrateLegacyRevocation(lastSession)
                    sessionOwner = null
                    lastSession = null
                    queue.purgeAll()
                    queueCountJob?.cancel()
                    stopInternal(clearIntent = true, clearRecovery = true)
                    _status.update {
                        it.copy(
                            sessionReady = false,
                            trackingRequested = false,
                            consentEnabled = false,
                            consentConfirmedByServer = false,
                            serverConsentEnabled = null,
                            locationAdmissionOpen = false,
                            pendingQueueCount = 0,
                        )
                    }
                    return@collectLatest
                }

                _status.update { it.copy(sessionReady = true) }
                val owner = session.userId
                if (owner != null) {
                    if (owner != sessionOwner) {
                        sessionGeneration++
                        _status.update {
                            it.copy(
                                consentEnabled = false,
                                consentConfirmedByServer = false,
                                serverConsentEnabled = null,
                                locationAdmissionOpen = false,
                                trackingRequested = false,
                                running = false,
                                providerActive = false,
                                serviceRunning = false,
                            )
                        }
                    }
                    val local = store.current()
                    if (local.localConsentConfirmed && local.consentOwnerUserId == null) {
                        // Consent persisted by a build that did not record
                        // its owner. Provenance is unknown, so it is not
                        // allowed to authorize collection for this account.
                        store.setLocalConsentConfirmed(false)
                    }
                    if (local.consentOwnerUserId != null && local.consentOwnerUserId != owner) {
                        // A different account's consent mirror and queue
                        // must never carry over, even if a sign-out was
                        // interrupted before clearing them.
                        queue.purgeAll()
                        stopInternal(clearIntent = true, clearRecovery = true)
                        store.setLocalConsentConfirmed(false)
                        migrateLegacyRevocation(lastSession)
                        store.setPendingConsentRevocation(false)
                        store.setTrackingRequested(false)
                        store.setConsentOwner(null)
                    }
                    if (owner != sessionOwner) {
                        if (sessionOwner != null) {
                            // Session replacement without a null in between.
                            queue.purgeAll()
                            stopInternal(clearIntent = true, clearRecovery = true)
                            migrateLegacyRevocation(lastSession)
                            store.setLocalConsentConfirmed(false)
                            store.setPendingConsentRevocation(false)
                            store.setTrackingRequested(false)
                            store.setConsentOwner(null)
                        }
                        sessionOwner = owner
                        observeQueueCount(owner)
                    }
                }
                lastSession = session
            }
        }
    }

    private fun observeQueueCount(ownerUserId: String) {
        queueCountJob?.cancel()
        queueCountJob = scope.launch {
            queue.observeCount(ownerUserId).collect { count ->
                _status.update { it.copy(pendingQueueCount = count) }
            }
        }
    }

    private fun observeConnectivity() {
        scope.launch {
            connectivity.observe().collect { online ->
                _status.update { current ->
                    current.copy(
                        online = online,
                        operational = when {
                            !online && current.running -> OperationalState.OFFLINE
                            online && current.running && current.operational == OperationalState.OFFLINE ->
                                if (current.permission == LocationPermissionState.GRANTED_APPROXIMATE ||
                                    batterySaver()
                                ) {
                                    OperationalState.DEGRADED
                                } else {
                                    OperationalState.ACTIVE
                                }

                            else -> current.operational
                        },
                    )
                }
            }
        }
    }

    /** The consent switch. OFF closes local collection and upload admission before persistence. */
    override suspend fun setConsent(enabled: Boolean): ConsentResult {
        val off = if (!enabled) prepareConsentOff() else null
        off?.cancellation?.let { throw it }
        currentCoroutineContext().ensureActive()

        val session = if (off != null) off.session else sessions.current()
        val local = if (off == null) store.current() else null
        val owner = off?.ownerUserId ?: session?.userId ?: local?.consentOwnerUserId
        if (off != null && (!off.outboxPersisted || off.operation == null)) {
            val message = if (off.serviceStopFailure != null) {
                "Đã chặn nhận điểm GPS mới, nhưng không xác nhận được việc dừng dịch vụ định vị và không lưu được yêu cầu tắt chia sẻ. Máy chủ chưa được xác nhận. Vui lòng kiểm tra ứng dụng và thử lại."
            } else if (off.storageFailure != null) {
                "Đã dừng theo dõi trên thiết bị này. Không lưu được yêu cầu tắt chia sẻ để đồng bộ với máy chủ. Vui lòng kiểm tra ứng dụng và thử lại."
            } else {
                "Đã dừng theo dõi trên thiết bị này. Đăng nhập lại đúng tài khoản để hoàn tất thu hồi chia sẻ."
            }
            return ConsentResult(enabled = false, serverConfirmed = false, message = message)
        }
        if (session == null || owner == null) {
            return ConsentResult(
                enabled = false,
                serverConfirmed = false,
                message = "Đăng nhập lại để thay đổi quyền chia sẻ vị trí.",
                pendingOperationPersisted = off?.outboxPersisted == true,
            )
        }
        if (session.userId != null && session.userId != owner) {
            return ConsentResult(
                enabled = false,
                serverConfirmed = false,
                message = "Tài khoản đã thay đổi. Vui lòng thử lại.",
                pendingOperationPersisted = off?.outboxPersisted == true,
            )
        }
        val operation = off?.operation ?: run {
            val persisted = sessions.pendingConsentOperations().firstOrNull { it.ownerUserId == owner }
            consentMutations.withNextGeneration(owner, persisted?.generation ?: 0L) { generation ->
                val current = sessions.current()
                if (current?.userId != owner) return@withNextGeneration null
                val pending = newPendingConsentOperation(
                    ownerUserId = owner,
                    desiredEnabled = true,
                    expectedVersion = current.locationSharingVersion ?: session.locationSharingVersion ?: 0L,
                    generation = generation,
                    nowMillis = now(),
                )
                sessions.savePendingConsentOperation(pending)
                pending
            }
        } ?: return ConsentResult(enabled = false, serverConfirmed = false, message = "Tài khoản đã thay đổi. Vui lòng thử lại.")
        if (off == null) pendingConsentScheduler.ensureScheduled()
        _status.update {
            it.copy(
                consentConflict = false,
                conflictServerConsent = null,
                consentSyncPending = true,
            )
        }

        if (operation.requiresOwnerAuthentication) {
            return ConsentResult(
                enabled = false,
                serverConfirmed = false,
                message = "Đã dừng trên thiết bị này. Đăng nhập lại đúng tài khoản để hoàn tất thu hồi chia sẻ.",
                pendingOperationPersisted = true,
            )
        }

        return try {
            val result = confirmConsentOperation(operation)
            if (off?.serviceStopFailure != null || off?.storageFailure != null || off?.queuePurged == false) {
                result.copy(
                    message = when {
                        off.serviceStopFailure != null ->
                            "Máy chủ đã xác nhận trạng thái chia sẻ, nhưng không xác nhận được việc dừng dịch vụ định vị. Đã chặn nhận điểm mới; hãy kiểm tra ứng dụng."
                        off.queuePurged ->
                        "Máy chủ đã xác nhận trạng thái chia sẻ, nhưng trạng thái cục bộ chưa được lưu đầy đủ. Theo dõi vẫn đang dừng trên thiết bị này."
                        else ->
                            "Máy chủ đã xác nhận trạng thái chia sẻ, nhưng chưa xóa được hàng đợi vị trí trên thiết bị. AnVề sẽ không tải các điểm này lên."
                    },
                )
            } else {
                result
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            pendingConsentScheduler.ensureScheduled()
            ConsentResult(
                enabled = enabled,
                serverConfirmed = false,
                message = if (enabled) {
                    "Chưa xác nhận được thay đổi với máy chủ. Vị trí chưa được bật; AnVề sẽ thử lại khi có kết nối."
                } else {
                    "Đã dừng theo dõi trên thiết bị này. Máy chủ chưa xác nhận thu hồi quyền chia sẻ; AnVề sẽ thử lại khi có kết nối."
                },
                pendingOperationPersisted = true,
            )
        } catch (e: IOException) {
            pendingConsentScheduler.ensureScheduled()
            ConsentResult(
                enabled = enabled,
                serverConfirmed = false,
                message = if (enabled) {
                    "Chưa xác nhận được thay đổi với máy chủ. Vị trí chưa được bật; AnVề sẽ thử lại khi có kết nối."
                } else {
                    "Đã dừng theo dõi trên thiết bị này. Máy chủ chưa xác nhận thu hồi quyền chia sẻ; AnVề sẽ thử lại khi có kết nối."
                },
                pendingOperationPersisted = true,
            )
        }
    }

    private suspend fun prepareConsentOff(): ConsentOffPreparation {
        val ownerHint = sessionOwner ?: lastSession?.userId
        consentMutations.closeLocationAdmission(ownerHint)
        val serviceStopFailure = stopInternal(clearIntent = true, clearRecovery = true, persistIntent = false)
        _status.update {
            it.copy(
                consentEnabled = false,
                consentConfirmedByServer = false,
                locationAdmissionOpen = false,
                consentSyncPending = true,
                consentConflict = false,
                conflictServerConsent = null,
            )
        }
        val prepared = withContext(NonCancellable) {
            var firstFailure: Exception? = null
            var cancellation: CancellationException? = null
            suspend fun attempt(block: suspend () -> Unit): Boolean = try {
                block()
                true
            } catch (error: CancellationException) {
                cancellation = cancellation ?: error
                false
            } catch (error: Exception) {
                firstFailure = firstFailure ?: error
                false
            }

            attempt { syncScheduler.cancel() }
            var activeSession: StoredSession? = null
            var localState: LocalTrackingState? = null
            attempt { activeSession = sessions.current() }
            attempt { localState = store.current() }
            val owner = activeSession?.userId ?: localState?.consentOwnerUserId ?: sessionOwner ?: lastSession?.userId
            var operation: PendingConsentOperation? = null
            var outboxPersisted = false
            if (owner != null && (activeSession?.userId == null || activeSession?.userId == owner)) {
                var pending = emptyList<PendingConsentOperation>()
                attempt { pending = sessions.pendingConsentOperations() }
                val saved = attempt {
                    operation = consentMutations.withNextGeneration(
                        owner,
                        pending.filter { it.ownerUserId == owner }.maxOfOrNull { it.generation } ?: 0L,
                    ) { generation ->
                        val current = sessions.current()
                        if (current != null) activeSession = current
                        if (current?.userId != null && current.userId != owner) return@withNextGeneration null
                        val created = newPendingConsentOperation(
                            ownerUserId = owner,
                            desiredEnabled = false,
                            expectedVersion = current?.locationSharingVersion ?: activeSession?.locationSharingVersion ?: 0L,
                            generation = generation,
                            nowMillis = now(),
                        ).let {
                            if (current == null) it.copy(
                                requiresOwnerAuthentication = true,
                                lastErrorCode = "OWNER_AUTH_REQUIRED",
                            ) else it
                        }
                        sessions.savePendingConsentOperation(created)
                        created
                    }
                }
                outboxPersisted = saved && operation != null
            } else {
                firstFailure = firstFailure ?: IOException("Consent owner is unavailable or changed")
            }

            if (owner != null) attempt { store.setConsentOwner(owner) }
            attempt { store.setPendingConsentRevocation(true) }
            attempt { store.setLocalConsentConfirmed(false) }
            attempt { store.setTrackingRequested(false) }
            val queuePurged = attempt {
                consentMutations.withLocalMutation { queue.purgeAll() }
            }
            if (!outboxPersisted) {
                _status.update {
                    it.copy(
                        consentSyncPending = false,
                        lastError = TrackingProblem.CONSENT_STORAGE_UNAVAILABLE,
                        lastErrorAtMillis = now(),
                    )
                }
            } else if (!queuePurged) {
                _status.update {
                    it.copy(lastError = TrackingProblem.CONSENT_QUEUE_PURGE_FAILED, lastErrorAtMillis = now())
                }
            } else if (firstFailure != null) {
                _status.update {
                    it.copy(lastError = TrackingProblem.CONSENT_STORAGE_UNAVAILABLE, lastErrorAtMillis = now())
                }
            }
            if (outboxPersisted) attempt { pendingConsentScheduler.ensureScheduled() }
            if (serviceStopFailure != null) {
                _status.update {
                    it.copy(lastError = TrackingProblem.SERVICE_STOP_FAILED, lastErrorAtMillis = now())
                }
            }
            ConsentOffPreparation(
                activeSession,
                owner,
                operation,
                outboxPersisted,
                queuePurged,
                serviceStopFailure,
                firstFailure,
                cancellation,
            )
        }
        prepared.cancellation?.let { throw it }
        return prepared
    }

    private suspend fun confirmConsentOperation(operation: PendingConsentOperation): ConsentResult {
        var user = try {
            server.setLocationSharing(
                operation.desiredEnabled,
                operation.expectedVersion,
                operation.ownerUserId,
                operation.operationId,
            )
        } catch (conflict: ApiException) {
            if (conflict.code != "CONSENT_VERSION_CONFLICT") throw conflict
            val remote = server.currentUser()
            if (remote.id != operation.ownerUserId) throw IOException("Consent owner changed")
            if (remote.locationSharingEnabled != operation.desiredEnabled) {
                consentMutations.closeLocationAdmission(operation.ownerUserId)
                val recorded = consentMutations.withLocalMutation {
                    val latest = sessions.pendingConsentOperations().firstOrNull {
                        it.ownerUserId == operation.ownerUserId
                    }
                    if (latest?.operationId != operation.operationId ||
                        sessions.current()?.userId != operation.ownerUserId
                    ) {
                        false
                    } else {
                        sessions.updateConsent(
                            remote.id,
                            remote.locationSharingEnabled,
                            remote.locationSharingVersion,
                        )
                        sessions.savePendingConsentOperation(
                            latest.copy(
                                conflictServerEnabled = remote.locationSharingEnabled,
                                conflictServerVersion = remote.locationSharingVersion,
                                conflictObservedAtMillis = now(),
                                lastErrorCode = "CONSENT_VERSION_CONFLICT",
                            ),
                        )
                        true
                    }
                }
                if (recorded) {
                    _status.update {
                        it.copy(
                            consentSyncPending = true,
                            consentConflict = true,
                            conflictServerConsent = remote.locationSharingEnabled,
                            lastError = TrackingProblem.CONSENT_CONFLICT,
                            lastErrorAtMillis = now(),
                        )
                    }
                }
                return ConsentResult(
                    enabled = remote.locationSharingEnabled,
                    serverConfirmed = false,
                    message = "Chia sẻ đã thay đổi trên một thiết bị khác. Vị trí vẫn dừng trên thiết bị này; chọn lại trạng thái để xác nhận quyết định mới.",
                    user = remote,
                    conflict = true,
                pendingOperationPersisted = recorded,
                )
            }
            // The desired state is already authoritative; reconcile the
            // snapshot without creating a redundant mutation or audit row.
            remote
        }
        if (user.id != operation.ownerUserId) throw IOException("Consent response belongs to another account")
        val stillCurrent = consentMutations.withLocalMutation {
            val latest = sessions.pendingConsentOperations().firstOrNull { it.ownerUserId == operation.ownerUserId }
            if (latest?.operationId != operation.operationId ||
                sessions.current()?.userId != operation.ownerUserId ||
                !consentMutations.isCurrent(operation.ownerUserId, operation.generation)
            ) {
                false
            } else {
                sessions.updateConsent(operation.ownerUserId, user.locationSharingEnabled, user.locationSharingVersion)
                sessions.removePendingConsentOperation(operation.operationId)
                store.setLocalConsentConfirmed(user.locationSharingEnabled)
                store.setPendingConsentRevocation(false)
                store.setConsentOwner(operation.ownerUserId)
                if (user.locationSharingEnabled) {
                    consentMutations.openLocationAdmission(operation.ownerUserId, operation.generation)
                }
                true
            }
        }
        if (!stillCurrent) return ConsentResult(enabled = false, serverConfirmed = false)
        _status.update { current ->
            val offFailure = current.lastError in setOf(
                TrackingProblem.SERVICE_STOP_FAILED,
                TrackingProblem.CONSENT_STORAGE_UNAVAILABLE,
                TrackingProblem.CONSENT_QUEUE_PURGE_FAILED,
            )
            current.copy(
                consentConflict = false,
                conflictServerConsent = null,
                consentSyncPending = false,
                consentEnabled = user.locationSharingEnabled &&
                    !consentMutations.isLocationAdmissionClosed(operation.ownerUserId),
                consentConfirmedByServer = true,
                serverConsentEnabled = user.locationSharingEnabled,
                locationAdmissionOpen = !consentMutations.isLocationAdmissionClosed(operation.ownerUserId),
                lastError = current.lastError.takeIf { offFailure },
                lastErrorAtMillis = current.lastErrorAtMillis.takeIf { offFailure },
            )
        }
        if (user.locationSharingEnabled) syncScheduler.ensureScheduled()
        return ConsentResult(user.locationSharingEnabled, serverConfirmed = true, user = user)
    }

    private suspend fun migrateLegacyRevocation(session: com.anve.app.data.auth.StoredSession?) {
        val initial = store.current()
        if (!initial.pendingConsentRevocation) return
        val owner = initial.consentOwnerUserId ?: session?.userId ?: return
        val migrated = consentMutations.withNextGeneration(owner) { generation ->
            val local = store.current()
            if (!local.pendingConsentRevocation) return@withNextGeneration false
            val pending = sessions.pendingConsentOperations().firstOrNull { it.ownerUserId == owner }
            if (pending == null) {
                sessions.savePendingConsentOperation(
                    newPendingConsentOperation(
                        ownerUserId = owner,
                        desiredEnabled = false,
                        expectedVersion = session?.takeIf { it.userId == owner }?.locationSharingVersion ?: 0L,
                        generation = generation,
                        nowMillis = now(),
                    ),
                )
            }
            store.setLocalConsentConfirmed(false)
            store.setPendingConsentRevocation(false)
            true
        }
        if (migrated) pendingConsentScheduler.ensureScheduled()
    }

    override suspend fun recordServerConsent(enabled: Boolean) {
        val active = sessions.current() ?: return
        val owner = active.userId ?: sessionOwner ?: return
        recordServerConsentSnapshot(owner, enabled, version = null)
    }

    override suspend fun recordServerConsent(user: UserDto) {
        recordServerConsentSnapshot(user.id, user.locationSharingEnabled, user.locationSharingVersion)
    }

    private suspend fun recordServerConsentSnapshot(ownerUserId: String, enabled: Boolean, version: Long?) {
        consentMutations.withLocalMutation {
            val session = sessions.current() ?: return@withLocalMutation
            val owner = session.userId ?: sessionOwner ?: ownerUserId
            if (owner != ownerUserId) return@withLocalMutation
            if (version != null && session.userId == owner &&
                version < (session.locationSharingVersion ?: -1L)
            ) {
                return@withLocalMutation
            }

            val pending = sessions.pendingConsentOperations().firstOrNull { it.ownerUserId == owner }
            val local = store.current()
            val pendingRevocation = local.pendingConsentRevocation && local.consentOwnerUserId == owner
            if (pending != null || pendingRevocation) {
                consentMutations.restoreBlockedAdmission(owner, pending?.generation ?: 0L)
                _status.update {
                    it.copy(
                        serverConsentEnabled = enabled,
                        consentConfirmedByServer = true,
                        consentEnabled = false,
                        locationAdmissionOpen = false,
                        consentSyncPending = true,
                        consentConflict = pending?.hasConsentConflict == true,
                        conflictServerConsent = pending?.conflictServerEnabled,
                    )
                }
                return@withLocalMutation
            }

            if (version != null) sessions.updateConsent(owner, enabled, version)
            val mayReconcile = consentMutations.reconcileLocationAdmission(owner, enabled)
            if (enabled && !mayReconcile) {
                // A server GET is not fresh user intent and cannot clear a
                // process-local emergency OFF/conflict barrier.
                _status.update {
                    it.copy(
                        serverConsentEnabled = true,
                        consentConfirmedByServer = true,
                        consentEnabled = false,
                        locationAdmissionOpen = false,
                    )
                }
                return@withLocalMutation
            }

            store.setConsentOwner(owner)
            store.setLocalConsentConfirmed(enabled)
            _status.update {
                it.copy(
                    serverConsentEnabled = enabled,
                    consentConfirmedByServer = true,
                    consentEnabled = enabled,
                    locationAdmissionOpen = enabled && !consentMutations.isLocationAdmissionClosed(owner),
                    consentSyncPending = false,
                    consentConflict = false,
                    conflictServerConsent = null,
                )
            }
        }
    }

    private suspend fun currentOwnerId(): String? =
        sessionOwner ?: sessions.current()?.userId


    override fun startTracking() {
        if (_status.value.running || starting) return
        scope.launch { startInternal() }
    }

    private suspend fun startInternal() {
        val session = sessions.current()
        if (session == null) {
            failStart(TrackingProblem.SESSION_UNAVAILABLE)
            return
        }
        var local = store.current()
        var owner = session.userId ?: sessionOwner
        if (owner == null && !connectivity.isOnline()) {
            failStart(TrackingProblem.SESSION_UNAVAILABLE)
            return
        }
        var pendingConsent = owner?.let { userId ->
            sessions.pendingConsentOperations().firstOrNull { it.ownerUserId == userId }
        }
        if (owner != null && pendingConsent != null) {
            consentMutations.restoreBlockedAdmission(owner, pendingConsent.generation)
            failStart(TrackingProblem.CONSENT_REQUIRED)
            return
        }
        if (owner != null && local.pendingConsentRevocation && local.consentOwnerUserId == owner) {
            consentMutations.restoreBlockedAdmission(owner, generation = 0)
            failStart(TrackingProblem.CONSENT_REQUIRED)
            return
        }
        if (owner != null && local.consentOwnerUserId == owner && !local.localConsentConfirmed) {
            // A server snapshot cannot silently undo a local OFF choice. A
            // fresh consent mutation must authorize tracking again.
            consentMutations.closeLocationAdmission(owner)
            failStart(TrackingProblem.CONSENT_REQUIRED)
            return
        }

        // A new account begins closed until its own authoritative snapshot
        // arrives. Offline recovery is allowed only from that same owner's
        // last server-confirmed consent and only when no local OFF barrier
        // has been set.
        if (connectivity.isOnline()) {
            try {
                val user = server.currentUser()
                if (owner != null && user.id != owner) {
                    failStart(TrackingProblem.SESSION_UNAVAILABLE)
                    return
                }
                owner = user.id
                if (session.userId == null) {
                    sessions.setUserIdIfMatches(session.accessToken, owner)
                    sessionOwner = owner
                }
                recordServerConsent(user)
                if (!user.locationSharingEnabled) {
                    failStart(TrackingProblem.CONSENT_REQUIRED)
                    return
                }
                if (!server.hasCircle()) {
                    failStart(TrackingProblem.CIRCLE_REQUIRED)
                    return
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // The owner's last confirmed local snapshot is the offline
                // fallback. It cannot clear an emergency OFF or conflict.
            }
        }

        local = store.current()
        pendingConsent = owner?.let { userId ->
            sessions.pendingConsentOperations().firstOrNull { it.ownerUserId == userId }
        }
        val ownerMatches = owner != null && local.consentOwnerUserId == owner
        if (ownerMatches && local.localConsentConfirmed && pendingConsent == null &&
            !local.pendingConsentRevocation
        ) {
            consentMutations.reconcileLocationAdmission(owner, enabled = true)
        }
        if (!ownerMatches || !local.localConsentConfirmed || pendingConsent != null ||
            (local.pendingConsentRevocation && local.consentOwnerUserId == owner) ||
            consentMutations.isLocationAdmissionClosed(owner)
        ) {
            failStart(TrackingProblem.CONSENT_REQUIRED)
            return
        }

        val snapshot = permissions.snapshot()
        _status.update {
            it.copy(
                permission = snapshot.location,
                backgroundPermissionGranted = snapshot.backgroundLocation,
                notificationsGranted = snapshot.notifications,
            )
        }
        if (!snapshot.canTrack) {
            failStart(TrackingProblem.PERMISSION_DENIED)
            return
        }
        if (!snapshot.locationServicesEnabled) {
            failStart(TrackingProblem.LOCATION_DISABLED)
            return
        }
        if (!provider.isAvailable()) {
            failStart(TrackingProblem.PROVIDER_UNAVAILABLE)
            return
        }
        if (session.userId == null && sessionOwner == null) {
            failStart(TrackingProblem.SESSION_UNAVAILABLE)
            return
        }

        clientDeviceId = deviceIdentity.registration().clientDeviceId
        val startAdmission = consentMutations.withLocalMutation {
            val latestSession = sessions.current()
            val latestLocal = store.current()
            val latestOwner = latestSession?.userId ?: sessionOwner
            val pending = latestOwner?.let { userId ->
                sessions.pendingConsentOperations().firstOrNull { it.ownerUserId == userId }
            }
            if (starting || _status.value.running) {
                TrackingStartAdmission.ALREADY_STARTING
            } else if (latestSession == null || latestOwner != owner || pending != null ||
                latestLocal.consentOwnerUserId != owner || !latestLocal.localConsentConfirmed ||
                (latestLocal.pendingConsentRevocation && latestLocal.consentOwnerUserId == owner) ||
                consentMutations.isLocationAdmissionClosed(owner)
            ) {
                TrackingStartAdmission.DENIED
            } else {
                sessionGeneration++
                if (latestSession.userId != null) sessionOwner = latestSession.userId
                starting = true
                _status.update {
                    it.copy(
                        trackingRequested = true,
                        recoveryRequired = false,
                        lastError = null,
                        lastErrorAtMillis = null,
                        permission = snapshot.location,
                        backgroundPermissionGranted = snapshot.backgroundLocation,
                        notificationsGranted = snapshot.notifications,
                    )
                }
                store.setTrackingRequested(true)
                val serviceAccepted = try {
                    service.start(sessionGeneration)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    false
                }
                if (serviceAccepted) TrackingStartAdmission.ACCEPTED else TrackingStartAdmission.SERVICE_REFUSED
            }
        }
        if (startAdmission == TrackingStartAdmission.ALREADY_STARTING) return
        if (startAdmission == TrackingStartAdmission.DENIED) {
            failStart(TrackingProblem.CONSENT_REQUIRED)
            return
        }
        if (startAdmission == TrackingStartAdmission.SERVICE_REFUSED) {
            failStart(TrackingProblem.SERVICE_START_FAILED)
            return
        }
        startTimeoutJob?.cancel()
        startTimeoutJob = scope.launch {
            delay(SERVICE_START_TIMEOUT_MILLIS)
            if (starting) {
                starting = false
                failStart(TrackingProblem.SERVICE_START_FAILED)
            }
        }
    }

    /** Called by the service once startForeground succeeded. */
    fun onServiceStarted() = onServiceStarted(sessionGeneration)

    fun onServiceStarted(startGeneration: Long) {
        if (startGeneration != sessionGeneration) {
            service.stop()
            return
        }
        starting = false
        startTimeoutJob?.cancel()
        val owner = sessionOwner
        if (owner == null || consentMutations.isLocationAdmissionClosed(owner) ||
            !_status.value.trackingRequested
        ) {
            // A stale start (for example after the user stopped tracking)
            // must not leave an empty foreground service running.
            if (!service.stop()) {
                _status.update {
                    it.copy(lastError = TrackingProblem.SERVICE_STOP_FAILED, lastErrorAtMillis = now())
                }
            }
            return
        }
        _status.update {
            it.copy(
                serviceRunning = true,
                running = true,
                providerActive = false,
                operational = if (connectivity.isOnline()) OperationalState.ACTIVE else OperationalState.OFFLINE,
            )
        }
        serviceStartedAtElapsed = elapsedNow()
        scope.launch { metrics.recordServiceStart() }
        startAcquisition(owner, sessionGeneration)
    }

    fun onServiceStartFailed(startGeneration: Long? = null) {
        if (startGeneration != null && startGeneration != sessionGeneration) return
        starting = false
        startTimeoutJob?.cancel()
        failStart(TrackingProblem.SERVICE_START_FAILED)
    }

    /** Called from the service's onDestroy. */
    fun onServiceDestroyed() {
        val requested = _status.value.trackingRequested
        recordServiceLifetime()
        stopAcquisition()
        _status.update {
            it.copy(
                serviceRunning = false,
                running = false,
                providerActive = false,
                operational = OperationalState.PAUSED,
                recoveryRequired = requested,
                lastError = if (requested) TrackingProblem.RECOVERY_REQUIRED else it.lastError,
                lastErrorAtMillis = if (requested) now() else it.lastErrorAtMillis,
            )
        }
    }

    /** Explicit user stop (button or notification action). */
    override fun stopTracking() {
        stopInternal(clearIntent = true, clearRecovery = true)
    }

    /** Notification stop action, from a process that may have no UI. */
    fun stopFromNotification() {
        stopTracking()
    }

    override fun retrySync() {
        syncScheduler.enqueueImmediate()
    }

    override fun refreshStatus() {
        scope.launch {
            val snapshot = permissions.snapshot()
            val activeSession = sessions.current()
            var owner = activeSession?.userId ?: sessionOwner
            var remote: UserDto? = null
            if (activeSession != null && connectivity.isOnline()) {
                val refreshedUser = try {
                    server.currentUser()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                if (refreshedUser != null &&
                    (activeSession.userId == null || refreshedUser.id == activeSession.userId)
                ) {
                    remote = refreshedUser
                    if (activeSession.userId == null) {
                        sessions.setUserIdIfMatches(activeSession.accessToken, refreshedUser.id)
                        sessionOwner = refreshedUser.id
                        owner = refreshedUser.id
                    }
                    try {
                        recordServerConsent(refreshedUser)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // A storage failure must not erase the successful
                        // server snapshot or reopen the local stop gate.
                    }
                } else {
                    remote = null
                }
            }

            val local = try {
                store.current()
            } catch (_: Exception) {
                if (owner != null) consentMutations.closeLocationAdmission(owner)
                _status.update {
                    it.copy(
                        locationAdmissionOpen = false,
                        consentSyncPending = true,
                        lastError = TrackingProblem.CONSENT_STORAGE_UNAVAILABLE,
                        lastErrorAtMillis = now(),
                    )
                }
                return@launch
            }
            val ownerMatches = owner != null && local.consentOwnerUserId == owner
            val pendingOperation = owner?.let { userId ->
                sessions.pendingConsentOperations().firstOrNull { it.ownerUserId == userId }
            }
            if (owner != null && pendingOperation != null) {
                consentMutations.restoreBlockedAdmission(owner, pendingOperation.generation)
            }
            val pendingRevocation = ownerMatches && local.pendingConsentRevocation
            if (owner != null && pendingRevocation) {
                consentMutations.restoreBlockedAdmission(owner, pendingOperation?.generation ?: 0L)
            }
            val admissionOpen = ownerMatches && pendingOperation == null && !pendingRevocation &&
                !consentMutations.isLocationAdmissionClosed(owner)
            val count = owner?.let { runCatching { queue.count(it) }.getOrNull() }
            _status.update {
                it.copy(
                    permission = snapshot.location,
                    backgroundPermissionGranted = snapshot.backgroundLocation,
                    notificationsGranted = snapshot.notifications,
                    locationServicesEnabled = snapshot.locationServicesEnabled,
                    online = connectivity.isOnline(),
                    batterySaver = batterySaver(),
                    pendingQueueCount = count ?: it.pendingQueueCount,
                    consentEnabled = ownerMatches && local.localConsentConfirmed &&
                        !pendingRevocation && pendingOperation == null && admissionOpen &&
                        (remote?.locationSharingEnabled ?: it.serverConsentEnabled.takeIf { ownerMatches }) != false,
                    consentConfirmedByServer = ownerMatches && local.localConsentConfirmed,
                    serverConsentEnabled = remote?.locationSharingEnabled ?: it.serverConsentEnabled
                        .takeIf { ownerMatches },
                    locationAdmissionOpen = admissionOpen,
                    consentSyncPending = pendingRevocation || pendingOperation != null,
                    consentConflict = pendingOperation?.hasConsentConflict == true,
                    conflictServerConsent = pendingOperation?.conflictServerEnabled,
                )
            }
        }
    }

    /**
     * Called by the sync engine when the server refuses the account's
     * consent, Circle or device. The engine already purged the queue; the
     * manager stops acquisition and reports the actionable reason.
     */
    suspend fun onServerPolicyRefusal(code: String) {
        val problem = when (code) {
            "LOCATION_CONSENT_REQUIRED" -> TrackingProblem.CONSENT_REQUIRED
            "LOCATION_CIRCLE_REQUIRED" -> TrackingProblem.CIRCLE_REQUIRED
            "DEVICE_NOT_AUTHORIZED" -> TrackingProblem.SESSION_UNAVAILABLE
            else -> TrackingProblem.PROVIDER_ERROR
        }
        stopInternal(clearIntent = true, clearRecovery = true)
        store.setLocalConsentConfirmed(false)
        _status.update {
            it.copy(
                consentEnabled = false,
                consentConfirmedByServer = false,
                trackingRequested = false,
                lastError = problem,
                lastErrorAtMillis = now(),
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startAcquisition(ownerUserId: String, generation: Long) {
        if (collectionJob?.isActive == true) return
        val snapshot = permissions.snapshot()
        if (!snapshot.canTrack) {
            stopForPermissionLoss(TrackingProblem.PERMISSION_REVOKED)
            return
        }
        requestOptions.value = policy.requestOptions(batterySaver())
        lastMetricsTickElapsed = elapsedNow()
        _status.update {
            it.copy(providerActive = true, motion = policy.motion, batterySaver = batterySaver())
        }
        collectionJob = scope.launch {
            try {
                requestOptions
                    .flatMapLatest { options -> provider.updates(options) }
                    .collect { fix -> handleFix(fix, ownerUserId, generation) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: SecurityException) {
                stopForPermissionLoss(TrackingProblem.PERMISSION_REVOKED)
            } catch (_: Exception) {
                stopForProviderFailure()
            }
        }
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (true) {
                delay(OPERATIONAL_CHECK_INTERVAL_MILLIS)
                checkOperationalState()
                val nowElapsed = elapsedNow()
                val slice = (nowElapsed - lastMetricsTickElapsed).coerceAtLeast(0)
                lastMetricsTickElapsed = nowElapsed
                metrics.recordStateSlice(
                    motion = policy.motion,
                    operational = _status.value.operational,
                    durationMillis = slice,
                )
            }
        }
    }

    private suspend fun handleFix(fix: LocationFix, callbackOwner: String, generation: Long) {
        if (generation != sessionGeneration || callbackOwner != sessionOwner ||
            consentMutations.isLocationAdmissionClosed(callbackOwner)
        ) return
        if (fix.recordedAtMillis > now() + MAX_FUTURE_SKEW_MILLIS) {
            // The device clock is not a trusted input. Refuse the fix
            // instead of rewriting its timestamp; the server applies the
            // same two-minute bound.
            _status.update {
                it.copy(
                    lastError = TrackingProblem.CLOCK_SKEW,
                    lastErrorAtMillis = now(),
                )
            }
            return
        }
        val decision = policy.onFix(
            fix = fix,
            nowElapsedMillis = elapsedNow(),
            batterySaver = batterySaver(),
        )
        metrics.recordFix(decision, fix.accuracyMeters)
        _status.update {
            it.copy(
                motion = decision.motion,
                batterySaver = batterySaver(),
            )
        }
        if (!decision.accepted) return

        val owner = callbackOwner
        val device = clientDeviceId ?: return

        val enqueued = try {
            consentMutations.withLocalMutation {
                val session = sessions.current()
                val local = store.current()
                val activeOwner = session?.userId ?: local.consentOwnerUserId
                val pendingMutation = sessions.pendingConsentOperations().any {
                    it.ownerUserId == owner
                }
                if (generation != sessionGeneration || callbackOwner != sessionOwner ||
                    consentMutations.isLocationAdmissionClosed(owner) || activeOwner != owner ||
                    local.consentOwnerUserId != owner || pendingMutation ||
                    (local.pendingConsentRevocation && local.consentOwnerUserId == owner) ||
                    !local.localConsentConfirmed || !local.trackingRequested ||
                    !_status.value.trackingRequested || !permissions.snapshot().canTrack
                ) {
                    false
                } else {
                    queue.enqueue(ownerUserId = owner, clientDeviceId = device, fix = fix)
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _status.update {
                it.copy(lastError = TrackingProblem.QUEUE_UNAVAILABLE, lastErrorAtMillis = now())
            }
            return
        }
        if (enqueued) {
            _status.update {
                it.copy(
                    lastFixAtMillis = fix.recordedAtMillis,
                    lastAccuracyMeters = fix.accuracyMeters,
                    motion = decision.motion,
                    lastError = null,
                )
            }
            syncScheduler.ensureScheduled()
        }
        if (decision.request != requestOptions.value) {
            // flatMapLatest unregisters the old callback and registers the
            // new request; no listener is leaked.
            requestOptions.value = decision.request
        }
    }

    /** Internal for deterministic tests of permission/location loss. */
    internal suspend fun checkOperationalState() {
        val snapshot = permissions.snapshot()
        _status.update {
            it.copy(
                permission = snapshot.location,
                backgroundPermissionGranted = snapshot.backgroundLocation,
                locationServicesEnabled = snapshot.locationServicesEnabled,
                online = connectivity.isOnline(),
                batterySaver = batterySaver(),
            )
        }
        if (!snapshot.canTrack) {
            stopForPermissionLoss(TrackingProblem.PERMISSION_REVOKED)
            return
        }
        if (!snapshot.locationServicesEnabled) {
            stopForDeviceLimitation(TrackingProblem.LOCATION_DISABLED)
            return
        }
        val options = policy.requestOptions(batterySaver())
        if (options != requestOptions.value) requestOptions.value = options
        _status.update {
            val degraded = snapshot.location == LocationPermissionState.GRANTED_APPROXIMATE ||
                batterySaver()
            it.copy(
                operational = when {
                    !it.online -> OperationalState.OFFLINE
                    degraded -> OperationalState.DEGRADED
                    else -> OperationalState.ACTIVE
                },
            )
        }
    }

    private fun stopForPermissionLoss(problem: TrackingProblem) {
        stopInternal(clearIntent = false, clearRecovery = false)
        _status.update {
            it.copy(
                running = false,
                serviceRunning = false,
                providerActive = false,
                operational = OperationalState.PAUSED,
                recoveryRequired = true,
                lastError = problem,
                lastErrorAtMillis = now(),
            )
        }
    }

    private fun stopForProviderFailure() {
        stopInternal(clearIntent = false, clearRecovery = false)
        _status.update {
            it.copy(
                running = false,
                serviceRunning = false,
                providerActive = false,
                operational = OperationalState.PAUSED,
                recoveryRequired = true,
                lastError = TrackingProblem.PROVIDER_ERROR,
                lastErrorAtMillis = now(),
            )
        }
    }

    private fun stopForDeviceLimitation(problem: TrackingProblem) {
        stopInternal(clearIntent = false, clearRecovery = false)
        _status.update {
            it.copy(
                running = false,
                serviceRunning = false,
                providerActive = false,
                operational = OperationalState.PAUSED,
                recoveryRequired = true,
                lastError = problem,
                lastErrorAtMillis = now(),
            )
        }
    }

    private fun stopInternal(
        clearIntent: Boolean,
        clearRecovery: Boolean,
        persistIntent: Boolean = true,
    ): Exception? {
        val serviceMayBeRunning = starting || _status.value.serviceRunning
        starting = false
        startTimeoutJob?.cancel()
        stopAcquisition()
        val serviceStopFailure = try {
            val accepted = service.stop()
            if (serviceMayBeRunning && !accepted) {
                IOException("Foreground tracking service did not accept stop request")
            } else {
                null
            }
        } catch (error: Exception) {
            error.takeIf { serviceMayBeRunning }
        }
        if (clearIntent && persistIntent) {
            // Persist asynchronously: the in-memory state must flip now so
            // the UI cannot keep showing a running tracker during the write.
            scope.launch { store.setTrackingRequested(false) }
        }
        _status.update {
            it.copy(
                running = false,
                serviceRunning = if (serviceStopFailure == null) false else it.serviceRunning,
                providerActive = false,
                trackingRequested = if (clearIntent) false else it.trackingRequested,
                operational = OperationalState.PAUSED,
                motion = MotionState.IDLE,
                recoveryRequired = if (clearRecovery) false else it.recoveryRequired,
                lastError = if (serviceStopFailure != null) TrackingProblem.SERVICE_STOP_FAILED else it.lastError,
                lastErrorAtMillis = if (serviceStopFailure != null) now() else it.lastErrorAtMillis,
            )
        }
        policy.setJourney(false)
        return serviceStopFailure
    }

    private fun stopAcquisition() {
        if (collectionJob != null) {
            val slice = (elapsedNow() - lastMetricsTickElapsed).coerceAtLeast(0)
            val motion = policy.motion
            val operational = _status.value.operational
            scope.launch { metrics.recordStateSlice(motion, operational, slice) }
        }
        collectionJob?.cancel()
        collectionJob = null
        tickerJob?.cancel()
        tickerJob = null
        requestOptions.value = policy.requestOptions()
        _status.update { it.copy(providerActive = false) }
    }

    private fun recordServiceLifetime() {
        val started = serviceStartedAtElapsed ?: return
        serviceStartedAtElapsed = null
        val activeMillis = (elapsedNow() - started).coerceAtLeast(0)
        scope.launch { metrics.recordServiceStop(activeMillis) }
    }

    private fun failStart(problem: TrackingProblem) {
        _status.update {
            it.copy(
                running = false,
                serviceRunning = false,
                providerActive = false,
                operational = OperationalState.PAUSED,
                recoveryRequired = problem == TrackingProblem.SERVICE_START_FAILED ||
                    problem == TrackingProblem.PERMISSION_DENIED ||
                    problem == TrackingProblem.PERMISSION_REVOKED,
                lastError = problem,
                lastErrorAtMillis = now(),
            )
        }
    }

    companion object {
        const val SERVICE_START_TIMEOUT_MILLIS: Long = 10_000
        const val OPERATIONAL_CHECK_INTERVAL_MILLIS: Long = 30_000

        /** Mirrors the server's future-timestamp tolerance. */
        const val MAX_FUTURE_SKEW_MILLIS: Long = 2 * 60 * 1000
    }
}
