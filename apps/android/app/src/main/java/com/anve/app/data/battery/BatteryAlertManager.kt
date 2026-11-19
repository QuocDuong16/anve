package com.anve.app.data.battery

import com.anve.app.data.api.AnveApi
import com.anve.app.data.api.ApiException
import com.anve.app.data.api.LowBatteryAlertCreateDto
import com.anve.app.data.api.LowBatteryAlertDto
import com.anve.app.data.api.LowBatteryLocationDto
import com.anve.app.data.api.orThrow
import com.anve.app.tracking.ConnectivityMonitor
import com.anve.app.tracking.LocationFix
import com.anve.app.tracking.TrackingStatus
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface BatteryAlertGateway {
    suspend fun createAlert(ownerUserId: String, body: LowBatteryAlertCreateDto): LowBatteryAlertDto
    suspend fun resolveAlerts(ownerUserId: String)
}

class ApiBatteryAlertGateway(private val api: AnveApi) : BatteryAlertGateway {
    override suspend fun createAlert(ownerUserId: String, body: LowBatteryAlertCreateDto): LowBatteryAlertDto =
        api.createBatteryAlert(ownerUserId, body).orThrow()

    override suspend fun resolveAlerts(ownerUserId: String) {
        api.resolveBatteryAlerts(ownerUserId).orThrow()
    }
}

/**
 * Coordinates opt-in low-battery threshold detection (15% and 5%) while active
 * foreground tracking is running.
 *
 * All queued pending alerts stay strictly in-memory with a 15-minute freshness
 * TTL and are cancelled immediately if the phone starts charging, recovers,
 * stops tracking, disables consent/alerts, switches accounts, or dies.
 */
class BatteryAlertManager(
    private val trackingStatus: StateFlow<TrackingStatus>,
    private val connectivity: ConnectivityMonitor,
    private val gateway: BatteryAlertGateway,
    private val snapshotProvider: BatterySnapshotProvider,
    private val localNotifier: BatteryAlertLocalNotifier,
    private val latestAcceptedFix: () -> LocationFix?,
    private val onFreshFixCaptured: (String, LocationFix) -> Unit,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val newEventId: () -> String = { UUID.randomUUID().toString() },
) {
    private data class PendingAlert(
        val ownerUserId: String,
        val threshold: Int,
        val batteryPercent: Int,
        val recordedAtMillis: Long,
        val epoch: Long,
        val dto: LowBatteryAlertCreateDto,
    )

    private data class CapturedSnapshot(
        val fix: LocationFix?,
        val cameFromFreshCapture: Boolean,
    )

    private val stateMutex = Mutex()
    private val networkMutex = Mutex()

    private var activeOwner: String? = null
    private var eligibilityEpoch: Long = 0L
    private var wasEligible: Boolean = false
    private var lastSample: BatterySample? = null
    private val policy = BatteryAlertPolicy()
    private val pendingByThreshold = LinkedHashMap<Int, PendingAlert>(2)
    private var serverAlertOutstanding: Boolean = false
    private var confirmedServerAlert: Boolean = false
    private var pendingResolveOwner: String? = null

    init {
        scope.launch {
            trackingStatus.collect { status ->
                processStatus(status)
            }
        }
        scope.launch {
            connectivity.observe().collect { online ->
                if (online) {
                    syncPendingOnNetwork()
                }
            }
        }
    }

    fun onBatterySample(percent: Int, isCharging: Boolean) {
        scope.launch {
            processSample(BatterySample(percent = percent, isCharging = isCharging))
        }
    }

    private suspend fun processStatus(status: TrackingStatus) {
        val triggerActions: Pair<String, List<BatteryAction>>? = stateMutex.withLock {
            val epochTriggered = applyStatusLocked(status)
            if (epochTriggered && lastSample != null) {
                val sample = lastSample!!
                val actions = evaluatePolicyLocked(sample)
                val owner = activeOwner
                if (owner != null && actions.isNotEmpty()) owner to actions else null
            } else {
                null
            }
        }
        if (triggerActions != null) {
            executeActions(triggerActions.first, triggerActions.second)
        }
    }

    private suspend fun processSample(sample: BatterySample) {
        val dispatch: Pair<String?, List<BatteryAction>> = stateMutex.withLock {
            applyStatusLocked(trackingStatus.value)
            lastSample = sample
            val actions = if (sample.isCharging || wasEligible) {
                evaluatePolicyLocked(sample)
            } else {
                pendingByThreshold.clear()
                emptyList()
            }
            activeOwner to actions
        }
        val owner = dispatch.first
        if (owner != null && dispatch.second.isNotEmpty()) {
            executeActions(owner, dispatch.second)
        } else if (sample.isCharging && sample.percent >= BatteryAlertPolicy.RESOLVE_CHARGING_PERCENT) {
            maybeResolveAfterRecovery()
        }
    }

    /** Returns true if tracking eligibility transitioned from false to true. */
    private fun applyStatusLocked(status: TrackingStatus): Boolean {
        val ownerChanged = status.ownerUserId != activeOwner
        if (ownerChanged) {
            activeOwner = status.ownerUserId
            eligibilityEpoch++
            wasEligible = false
            policy.reset()
            pendingByThreshold.clear()
            serverAlertOutstanding = false
            confirmedServerAlert = false
            pendingResolveOwner = null
        }

        val eligible = status.isLowBatteryEligible()
        if (!eligible) {
            if (wasEligible || pendingByThreshold.isNotEmpty()) {
                eligibilityEpoch++
            }
            wasEligible = false
            policy.reset()
            pendingByThreshold.clear()
            return false
        }

        val becameEligible = !wasEligible
        wasEligible = true
        return becameEligible
    }

    private fun evaluatePolicyLocked(sample: BatterySample): List<BatteryAction> {
        val actions = policy.onSample(sample)
        for (action in actions) {
            when (action) {
                is BatteryAction.CancelPending -> {
                    pendingByThreshold.clear()
                    if (!sample.isCharging) {
                        pendingResolveOwner = null
                    }
                }
                is BatteryAction.ResolveActiveAlerts -> {
                    pendingByThreshold.clear()
                }
                is BatteryAction.TriggerAlert -> {
                    pendingResolveOwner = null
                }
            }
        }
        if (!sample.isCharging) {
            pendingResolveOwner = null
        }
        return actions
    }

    private suspend fun executeActions(owner: String, actions: List<BatteryAction>) {
        for (action in actions) {
            when (action) {
                BatteryAction.CancelPending -> Unit
                BatteryAction.ResolveActiveAlerts -> triggerResolveForOwner(owner)
                is BatteryAction.TriggerAlert -> captureAndSendAlert(owner, action.threshold, action.batteryPercent)
            }
        }
    }

    private suspend fun captureAndSendAlert(owner: String, threshold: Int, batteryPercent: Int) {
        val startState = stateMutex.withLock {
            if (activeOwner != owner || !wasEligible || !trackingStatus.value.isLowBatteryEligible()) return@withLock null
            val sample = lastSample
            if (sample != null && (sample.isCharging || sample.percent >= BatteryAlertPolicy.REARM_WARNING_PERCENT)) {
                return@withLock null
            }
            val capturedAt = now()
            Triple(capturedAt, newEventId(), eligibilityEpoch)
        } ?: return

        val (triggerAt, eventId, capturedEpoch) = startState
        val snapshot = captureLocationForAlert(triggerAt)

        if (snapshot.cameFromFreshCapture && snapshot.fix != null) {
            val stillValid = stateMutex.withLock {
                activeOwner == owner && eligibilityEpoch == capturedEpoch && trackingStatus.value.isLowBatteryEligible()
            }
            if (stillValid) {
                onFreshFixCaptured(owner, snapshot.fix)
            }
        }

        val enqueued = stateMutex.withLock {
            val currentTime = now()
            val sample = lastSample
            if (activeOwner != owner ||
                eligibilityEpoch != capturedEpoch ||
                !trackingStatus.value.isLowBatteryEligible() ||
                sample == null ||
                sample.isCharging ||
                sample.percent >= BatteryAlertPolicy.REARM_WARNING_PERCENT ||
                currentTime - triggerAt > PENDING_TTL_MILLIS
            ) {
                return@withLock false
            }

            val locationDto = snapshot.fix?.let { fix ->
                LowBatteryLocationDto(
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    horizontalAccuracyMeters = fix.accuracyMeters,
                    recordedAt = Instant.ofEpochMilli(fix.recordedAtMillis).toString(),
                )
            }
            val dto = LowBatteryAlertCreateDto(
                eventId = eventId,
                threshold = threshold,
                batteryPercent = batteryPercent,
                recordedAt = Instant.ofEpochMilli(triggerAt).toString(),
                location = locationDto,
            )
            pendingByThreshold[threshold] = PendingAlert(
                ownerUserId = owner,
                threshold = threshold,
                batteryPercent = batteryPercent,
                recordedAtMillis = triggerAt,
                epoch = capturedEpoch,
                dto = dto,
            )
            true
        }

        if (enqueued && connectivity.isOnline()) {
            flushPending()
        }
    }

    private suspend fun captureLocationForAlert(triggerAtMillis: Long): CapturedSnapshot {
        val cached = latestAcceptedFix()?.takeIf { !it.isMock }
        if (cached != null && (triggerAtMillis - cached.recordedAtMillis) in 0L..REUSE_FIX_MAX_AGE_MILLIS) {
            return CapturedSnapshot(fix = cached, cameFromFreshCapture = false)
        }

        val fresh = try {
            snapshotProvider.captureFreshFix()?.takeIf { !it.isMock }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (fresh != null) {
            return CapturedSnapshot(fix = fresh, cameFromFreshCapture = true)
        }

        val fallback = latestAcceptedFix()?.takeIf { !it.isMock }
        if (fallback != null && (triggerAtMillis - fallback.recordedAtMillis) in 0L..FALLBACK_FIX_MAX_AGE_MILLIS) {
            return CapturedSnapshot(fix = fallback, cameFromFreshCapture = false)
        }
        return CapturedSnapshot(fix = null, cameFromFreshCapture = false)
    }

    private suspend fun syncPendingOnNetwork() {
        maybeResolveAfterRecovery()
        flushPending()
    }

    private suspend fun flushPending() {
        networkMutex.withLock {
            while (true) {
                val next: PendingAlert = stateMutex.withLock {
                    val currentTime = now()
                    val expiredKeys = pendingByThreshold.entries
                        .filter { currentTime - it.value.recordedAtMillis > PENDING_TTL_MILLIS }
                        .map { it.key }
                    expiredKeys.forEach { pendingByThreshold.remove(it) }

                    val candidate = pendingByThreshold.values.firstOrNull() ?: return@withLock null
                    val sample = lastSample
                    if (candidate.ownerUserId != activeOwner ||
                        candidate.epoch != eligibilityEpoch ||
                        !trackingStatus.value.isLowBatteryEligible() ||
                        sample?.isCharging == true ||
                        (sample != null && sample.percent >= BatteryAlertPolicy.REARM_WARNING_PERCENT)
                    ) {
                        pendingByThreshold.clear()
                        return@withLock null
                    }
                    candidate
                } ?: break

                val sendResult = try {
                    stateMutex.withLock {
                        if (activeOwner == next.ownerUserId && eligibilityEpoch == next.epoch) {
                            serverAlertOutstanding = true
                        }
                    }
                    val created = gateway.createAlert(next.ownerUserId, next.dto)
                    Result.success(created)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }

                if (sendResult.isSuccess) {
                    val created = sendResult.getOrThrow()
                    val notifyArgs = stateMutex.withLock {
                        if (pendingByThreshold[next.threshold]?.dto?.eventId == next.dto.eventId) {
                            pendingByThreshold.remove(next.threshold)
                        }
                        if (activeOwner == next.ownerUserId && eligibilityEpoch == next.epoch) {
                            confirmedServerAlert = true
                            serverAlertOutstanding = true
                            if (created.recipientCount > 0) {
                                Triple(next.ownerUserId, next.threshold, created.batteryPercent to created.recipientCount)
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                    }
                    if (notifyArgs != null) {
                        localNotifier.notifySent(
                            ownerUserId = notifyArgs.first,
                            threshold = notifyArgs.second,
                            batteryPercent = notifyArgs.third.first,
                            recipientCount = notifyArgs.third.second,
                        )
                    }
                    val shouldResolveNow = stateMutex.withLock {
                        activeOwner == next.ownerUserId &&
                            serverAlertOutstanding &&
                            lastSample?.let { it.isCharging && it.percent >= BatteryAlertPolicy.RESOLVE_CHARGING_PERCENT } == true
                    }
                    if (shouldResolveNow) {
                        resolveAlertsLockedOnNetwork(next.ownerUserId)
                    }
                } else {
                    val error = sendResult.exceptionOrNull()
                    if (error is ApiException && error.httpStatus in TERMINAL_HTTP_STATUSES) {
                        stateMutex.withLock {
                            if (pendingByThreshold[next.threshold]?.dto?.eventId == next.dto.eventId) {
                                pendingByThreshold.remove(next.threshold)
                            }
                            if (!confirmedServerAlert && pendingByThreshold.isEmpty()) {
                                serverAlertOutstanding = false
                            }
                        }
                    } else {
                        // Transient 5xx or IOException: keep in memory while within 15m TTL.
                        break
                    }
                }
            }
        }
    }

    private suspend fun triggerResolveForOwner(owner: String) {
        stateMutex.withLock {
            if (!serverAlertOutstanding || activeOwner != owner) return
            pendingResolveOwner = owner
        }
        if (connectivity.isOnline()) {
            networkMutex.withLock {
                resolveAlertsLockedOnNetwork(owner)
            }
        }
    }

    private suspend fun maybeResolveAfterRecovery() {
        val owner = stateMutex.withLock {
            val currentOwner = activeOwner ?: return
            val sample = lastSample ?: return
            val needsResolve = (pendingResolveOwner == currentOwner || serverAlertOutstanding) &&
                sample.isCharging &&
                sample.percent >= BatteryAlertPolicy.RESOLVE_CHARGING_PERCENT
            if (!needsResolve) return
            currentOwner
        }
        if (connectivity.isOnline()) {
            networkMutex.withLock {
                resolveAlertsLockedOnNetwork(owner)
            }
        }
    }

    private suspend fun resolveAlertsLockedOnNetwork(owner: String) {
        val stillRelevant = stateMutex.withLock {
            activeOwner == owner &&
                (serverAlertOutstanding || pendingResolveOwner == owner) &&
                lastSample?.let { it.isCharging && it.percent >= BatteryAlertPolicy.RESOLVE_CHARGING_PERCENT } == true
        }
        if (!stillRelevant) return

        try {
            gateway.resolveAlerts(owner)
            stateMutex.withLock {
                if (activeOwner == owner) {
                    serverAlertOutstanding = false
                    confirmedServerAlert = false
                    pendingResolveOwner = null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.httpStatus in setOf(401, 403, 404)) {
                stateMutex.withLock {
                    if (activeOwner == owner) {
                        serverAlertOutstanding = false
                        confirmedServerAlert = false
                        pendingResolveOwner = null
                    }
                }
            }
        } catch (_: IOException) {
            stateMutex.withLock {
                if (activeOwner == owner) {
                    pendingResolveOwner = owner
                }
            }
        }
    }

    companion object {
        const val REUSE_FIX_MAX_AGE_MILLIS: Long = 30_000L
        const val FALLBACK_FIX_MAX_AGE_MILLIS: Long = 15L * 60_000L
        const val PENDING_TTL_MILLIS: Long = 15L * 60_000L
        private val TERMINAL_HTTP_STATUSES = setOf(400, 401, 403, 404, 409, 429)
    }
}

private fun TrackingStatus.isLowBatteryEligible(): Boolean =
    ownerUserId != null &&
        activelyTracking &&
        consentEnabled &&
        consentConfirmedByServer &&
        locationAdmissionOpen &&
        lowBatteryAlertsEnabled
