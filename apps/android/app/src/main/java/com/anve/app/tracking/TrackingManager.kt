package com.anve.app.tracking

import android.os.SystemClock
import com.anve.app.data.api.ApiException
import com.anve.app.data.api.UserDto
import com.anve.app.data.auth.DeviceRegistrationProvider
import com.anve.app.data.auth.SessionStorage
import com.anve.app.data.location.LocationQueueRepository
import com.anve.app.data.location.LocationSyncScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/** Outcome of a consent switch, separating local effect from server confirmation. */
data class ConsentResult(
    val enabled: Boolean,
    val serverConfirmed: Boolean,
    val message: String? = null,
    /** The account returned by a confirmed write, so the UI can apply its fresh state. */
    val user: UserDto? = null,
)

/**
 * The consent surface the account screen uses. Keeping it an interface
 * avoids pulling the whole tracking stack into that screen's tests.
 */
interface ConsentController {
    suspend fun setConsent(enabled: Boolean): ConsentResult
    suspend fun recordServerConsent(enabled: Boolean)
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
    private var sessionOwner: String? = null
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
                _status.update { current ->
                    current.copy(
                        consentEnabled = local.localConsentConfirmed &&
                            !local.pendingConsentRevocation,
                        consentConfirmedByServer = local.localConsentConfirmed &&
                            !local.pendingConsentRevocation,
                        consentSyncPending = local.pendingConsentRevocation,
                        trackingRequested = local.trackingRequested,
                        lastUploadAtMillis = local.lastSuccessfulUploadAtMillis,
                        // A fresh process that finds persisted intent must
                        // ask the user to resume; Android may not allow (and
                        // the app may not truthfully claim) a background
                        // restart.
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
                    sessionOwner = null
                    queue.purgeAll()
                    queueCountJob?.cancel()
                    // The location-consent revocation intent is tied to the
                    // account that made it; it must never be delivered under
                    // a different session later.
                    store.setPendingConsentRevocation(false)
                    store.setConsentOwner(null)
                    stopInternal(clearIntent = true, clearRecovery = true)
                    _status.update {
                        it.copy(
                            sessionReady = false,
                            trackingRequested = false,
                            consentEnabled = false,
                            consentConfirmedByServer = false,
                            pendingQueueCount = 0,
                        )
                    }
                    return@collectLatest
                }

                _status.update { it.copy(sessionReady = true) }
                val owner = session.userId
                if (owner != null) {
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
                        store.setPendingConsentRevocation(false)
                        store.setTrackingRequested(false)
                        store.setConsentOwner(null)
                    }
                    if (owner != sessionOwner) {
                        if (sessionOwner != null) {
                            // Session replacement without a null in between.
                            queue.purgeAll()
                            stopInternal(clearIntent = true, clearRecovery = true)
                            store.setLocalConsentConfirmed(false)
                            store.setPendingConsentRevocation(false)
                            store.setTrackingRequested(false)
                            store.setConsentOwner(null)
                        }
                        sessionOwner = owner
                        observeQueueCount(owner)
                    }
                }
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

    /**
     * The consent switch. Disabling is privacy-first and does not depend on
     * the network: acquisition stops, unsent points are purged, and a
     * durable pending-revocation flag is written **before** the request so
     * the sync worker cannot backfill afterwards.
     */
    override suspend fun setConsent(enabled: Boolean): ConsentResult {
        if (!enabled) {
            // Record which account the revocation belongs to before the
            // durable flag; a later session must not deliver it for someone
            // else.
            currentOwnerId()?.let { store.setConsentOwner(it) }
            store.setPendingConsentRevocation(true)
            store.setLocalConsentConfirmed(false)
            stopInternal(clearIntent = true, clearRecovery = true)
            queue.purgeAll()
            syncScheduler.ensureScheduled()
            return try {
                val user = server.setLocationSharing(false)
                store.setPendingConsentRevocation(false)
                currentOwnerId()?.let { store.setConsentOwner(it) }
                ConsentResult(enabled = false, serverConfirmed = true, user = user)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                ConsentResult(
                    enabled = false,
                    serverConfirmed = false,
                    message = "Đã dừng theo dõi trên thiết bị này. Máy chủ chưa xác nhận thu hồi quyền chia sẻ; AnVề sẽ thử lại khi có kết nối.",
                )
            } catch (e: IOException) {
                ConsentResult(
                    enabled = false,
                    serverConfirmed = false,
                    message = "Đã dừng theo dõi trên thiết bị này. Máy chủ chưa xác nhận thu hồi quyền chia sẻ; AnVề sẽ thử lại khi có kết nối.",
                )
            }
        }

        return try {
            val user = server.setLocationSharing(true)
            // A successful enable cancels an undelivered revocation: the
            // server state the user just requested is authoritative.
            store.setPendingConsentRevocation(false)
            store.setLocalConsentConfirmed(user.locationSharingEnabled)
            currentOwnerId()?.let { store.setConsentOwner(it) }
            syncScheduler.ensureScheduled()
            ConsentResult(enabled = user.locationSharingEnabled, serverConfirmed = true, user = user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            ConsentResult(
                enabled = false,
                serverConfirmed = false,
                message = "Không bật được chia sẻ vị trí. Vui lòng thử lại khi có kết nối.",
            )
        } catch (e: IOException) {
            ConsentResult(
                enabled = false,
                serverConfirmed = false,
                message = "Không bật được chia sẻ vị trí. Vui lòng thử lại khi có kết nối.",
            )
        }
    }

    /** Records what an authenticated read observed, unless a revocation is pending. */
    override suspend fun recordServerConsent(enabled: Boolean) {
        val local = store.current()
        if (local.pendingConsentRevocation) {
            store.setLocalConsentConfirmed(false)
            return
        }
        store.setConsentOwner(currentOwnerId())
        store.setLocalConsentConfirmed(enabled)
    }

    private suspend fun currentOwnerId(): String? =
        sessionOwner ?: sessions.current()?.userId


    /**
     * Starts acquisition. Must be called in response to a user action from a
     * visible screen (Android background-start rules); permission dialogs
     * are the UI's responsibility, this method verifies the grants.
     */
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
        val local = store.current()
        if (local.pendingConsentRevocation || !local.localConsentConfirmed) {
            failStart(TrackingProblem.CONSENT_REQUIRED)
            return
        }
        val snapshot = permissions.snapshot()
        _status.update { it.copy(permission = snapshot.location) }
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

        // Verify the server-side prerequisites when online. Offline start is
        // allowed from the last confirmed consent, but every upload still
        // goes through server authorization and a refusal stops tracking.
        if (connectivity.isOnline()) {
            try {
                val user = server.currentUser()
                if (session.userId == null) {
                    // Sessions created before M3 did not persist the owner
                    // id. Fill it without rewriting tokens (a rotation may
                    // have happened concurrently).
                    sessions.setUserId(user.id)
                    sessionOwner = user.id
                }
                recordServerConsent(user.locationSharingEnabled)
                if (!user.locationSharingEnabled) {
                    failStart(TrackingProblem.CONSENT_REQUIRED)
                    return
                }
                if (!server.hasCircle()) {
                    failStart(TrackingProblem.CIRCLE_REQUIRED)
                    return
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline verification is best-effort; local confirmed
                // consent is the fallback, and the server remains the
                // authority for every batch.
            }
        }

        if (session.userId == null && sessionOwner == null) {
            // Offline with a pre-M3 session: there is no way to attribute
            // queued points to an account, so refuse rather than collect
            // data that could never be uploaded safely.
            failStart(TrackingProblem.SESSION_UNAVAILABLE)
            return
        }
        clientDeviceId = deviceIdentity.registration().clientDeviceId
        // The session collector normally sets this; do not lose the first
        // fixes if it has not emitted yet.
        if (session.userId != null) sessionOwner = session.userId
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
        if (!service.start()) {
            starting = false
            failStart(TrackingProblem.SERVICE_START_FAILED)
            return
        }
        // The service reports back through onServiceStarted. If Android
        // silently drops the start, do not leave the UI in "starting".
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
    fun onServiceStarted() {
        starting = false
        startTimeoutJob?.cancel()
        if (!_status.value.trackingRequested) {
            // A stale start (for example after the user stopped tracking)
            // must not leave an empty foreground service running.
            service.stop()
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
        startAcquisition()
    }

    fun onServiceStartFailed() {
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

    /** Re-reads OS/network state for the UI. */
    override fun refreshStatus() {
        scope.launch {
            val snapshot = permissions.snapshot()
            val owner = sessionOwner
            val count = owner?.let { runCatching { queue.count(it) }.getOrNull() }
            val local = store.current()
            _status.update {
                it.copy(
                    permission = snapshot.location,
                    backgroundPermissionGranted = snapshot.backgroundLocation,
                    notificationsGranted = snapshot.notifications,
                    locationServicesEnabled = snapshot.locationServicesEnabled,
                    online = connectivity.isOnline(),
                    batterySaver = batterySaver(),
                    pendingQueueCount = count ?: it.pendingQueueCount,
                    consentEnabled = local.localConsentConfirmed && !local.pendingConsentRevocation,
                    consentSyncPending = local.pendingConsentRevocation,
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
    private fun startAcquisition() {
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
                    .collect { fix -> handleFix(fix) }
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

    private suspend fun handleFix(fix: LocationFix) {
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

        val owner = sessionOwner ?: return
        val device = clientDeviceId ?: return

        val enqueued = runCatching {
            queue.enqueue(ownerUserId = owner, clientDeviceId = device, fix = fix)
        }.getOrElse { error ->
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

    private fun stopInternal(clearIntent: Boolean, clearRecovery: Boolean) {
        starting = false
        startTimeoutJob?.cancel()
        stopAcquisition()
        service.stop()
        if (clearIntent) {
            // Persist asynchronously: the in-memory state must flip now so
            // the UI cannot keep showing a running tracker during the write.
            scope.launch { store.setTrackingRequested(false) }
        }
        _status.update {
            it.copy(
                running = false,
                serviceRunning = false,
                providerActive = false,
                operational = OperationalState.PAUSED,
                motion = MotionState.IDLE,
                recoveryRequired = if (clearRecovery) false else it.recoveryRequired,
            )
        }
        policy.setJourney(false)
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
