package com.anve.app.tracking

import android.content.Context
import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Aggregate, coordinate-free measurement counters for the location engine.
 * They exist so field validation can answer "how often did we request GPS,
 * what did it return, how much time was spent moving/stationary/offline and
 * how many batches left the device" without a debug screen full of raw
 * points.
 *
 * These are counters, not claims: no battery figure is derived from them,
 * and wakeup counts are not measurable through public Android APIs.
 */
data class TrackingMetrics(
    val callbacks: Long = 0,
    val accepted: Long = 0,
    val rejectedStale: Long = 0,
    val rejectedOutOfOrder: Long = 0,
    val rejectedInaccurate: Long = 0,
    val rejectedDuplicate: Long = 0,
    val accuracyUnder25m: Long = 0,
    val accuracyUnder50m: Long = 0,
    val accuracyUnder100m: Long = 0,
    val accuracyUnder500m: Long = 0,
    val accuracyOver500m: Long = 0,
    val idleMillis: Long = 0,
    val stationaryMillis: Long = 0,
    val movingMillis: Long = 0,
    val journeyMillis: Long = 0,
    val activeMillis: Long = 0,
    val degradedMillis: Long = 0,
    val offlineMillis: Long = 0,
    val pausedMillis: Long = 0,
    val uploadBatches: Long = 0,
    val uploadedPoints: Long = 0,
    val serviceStarts: Long = 0,
    val serviceActiveMillis: Long = 0,
    val lastUpdatedMillis: Long = 0,
)

interface TrackingMetricsStore {
    val metrics: Flow<TrackingMetrics>
    suspend fun recordFix(decision: FixDecision, accuracyMeters: Double)
    suspend fun recordStateSlice(
        motion: MotionState,
        operational: OperationalState,
        durationMillis: Long,
    )

    suspend fun recordUpload(batchSize: Int)
    suspend fun recordServiceStart()
    suspend fun recordServiceStop(activeMillis: Long)
}

object NoOpTrackingMetricsStore : TrackingMetricsStore {
    override val metrics: Flow<TrackingMetrics> = kotlinx.coroutines.flow.flowOf(TrackingMetrics())
    override suspend fun recordFix(decision: FixDecision, accuracyMeters: Double) = Unit
    override suspend fun recordStateSlice(
        motion: MotionState,
        operational: OperationalState,
        durationMillis: Long,
    ) = Unit

    override suspend fun recordUpload(batchSize: Int) = Unit
    override suspend fun recordServiceStart() = Unit
    override suspend fun recordServiceStop(activeMillis: Long) = Unit
}

private val Context.trackingMetricsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "anve_tracking_metrics",
)

class DataStoreTrackingMetricsStore(
    context: Context,
    private val elapsedNow: () -> Long = { SystemClock.elapsedRealtime() },
) : TrackingMetricsStore {

    private val dataStore = context.applicationContext.trackingMetricsDataStore
    private val bufferLock = Mutex()
    private var pending = TrackingMetrics()
    private var pendingUpdates = 0
    private var lastFlushAtElapsed = elapsedNow()
    private val pendingFlow = MutableStateFlow(TrackingMetrics())

    override val metrics: Flow<TrackingMetrics> = combine(dataStore.data, pendingFlow) { prefs, buffered ->
        TrackingMetrics(
            callbacks = (prefs[KEY_CALLBACKS] ?: 0) + buffered.callbacks,
            accepted = (prefs[KEY_ACCEPTED] ?: 0) + buffered.accepted,
            rejectedStale = (prefs[KEY_REJECTED_STALE] ?: 0) + buffered.rejectedStale,
            rejectedOutOfOrder = (prefs[KEY_REJECTED_OUT_OF_ORDER] ?: 0) + buffered.rejectedOutOfOrder,
            rejectedInaccurate = (prefs[KEY_REJECTED_INACCURATE] ?: 0) + buffered.rejectedInaccurate,
            rejectedDuplicate = (prefs[KEY_REJECTED_DUPLICATE] ?: 0) + buffered.rejectedDuplicate,
            accuracyUnder25m = (prefs[KEY_ACC_25] ?: 0) + buffered.accuracyUnder25m,
            accuracyUnder50m = (prefs[KEY_ACC_50] ?: 0) + buffered.accuracyUnder50m,
            accuracyUnder100m = (prefs[KEY_ACC_100] ?: 0) + buffered.accuracyUnder100m,
            accuracyUnder500m = (prefs[KEY_ACC_500] ?: 0) + buffered.accuracyUnder500m,
            accuracyOver500m = (prefs[KEY_ACC_OVER_500] ?: 0) + buffered.accuracyOver500m,
            idleMillis = (prefs[KEY_IDLE] ?: 0) + buffered.idleMillis,
            stationaryMillis = (prefs[KEY_STATIONARY] ?: 0) + buffered.stationaryMillis,
            movingMillis = (prefs[KEY_MOVING] ?: 0) + buffered.movingMillis,
            journeyMillis = (prefs[KEY_JOURNEY] ?: 0) + buffered.journeyMillis,
            activeMillis = (prefs[KEY_ACTIVE] ?: 0) + buffered.activeMillis,
            degradedMillis = (prefs[KEY_DEGRADED] ?: 0) + buffered.degradedMillis,
            offlineMillis = (prefs[KEY_OFFLINE] ?: 0) + buffered.offlineMillis,
            pausedMillis = (prefs[KEY_PAUSED] ?: 0) + buffered.pausedMillis,
            uploadBatches = (prefs[KEY_UPLOAD_BATCHES] ?: 0) + buffered.uploadBatches,
            uploadedPoints = (prefs[KEY_UPLOADED_POINTS] ?: 0) + buffered.uploadedPoints,
            serviceStarts = (prefs[KEY_SERVICE_STARTS] ?: 0) + buffered.serviceStarts,
            serviceActiveMillis = (prefs[KEY_SERVICE_ACTIVE_MILLIS] ?: 0) + buffered.serviceActiveMillis,
            lastUpdatedMillis = maxOf(prefs[KEY_LAST_UPDATED] ?: 0, buffered.lastUpdatedMillis),
        )
    }

    override suspend fun recordFix(decision: FixDecision, accuracyMeters: Double) {
        val nowMillis = System.currentTimeMillis()
        bufferLock.withLock {
            pending = pending.copy(
                callbacks = pending.callbacks + 1,
                accepted = pending.accepted + if (decision.accepted) 1 else 0,
                rejectedStale = pending.rejectedStale + if (!decision.accepted && decision.rejection == FixRejection.STALE) 1 else 0,
                rejectedOutOfOrder = pending.rejectedOutOfOrder + if (!decision.accepted && decision.rejection == FixRejection.OUT_OF_ORDER) 1 else 0,
                rejectedInaccurate = pending.rejectedInaccurate + if (!decision.accepted && (decision.rejection == FixRejection.INACCURATE || decision.rejection == null)) 1 else 0,
                rejectedDuplicate = pending.rejectedDuplicate + if (!decision.accepted && decision.rejection == FixRejection.DUPLICATE) 1 else 0,
                accuracyUnder25m = pending.accuracyUnder25m + if (accuracyMeters < 25) 1 else 0,
                accuracyUnder50m = pending.accuracyUnder50m + if (accuracyMeters >= 25 && accuracyMeters < 50) 1 else 0,
                accuracyUnder100m = pending.accuracyUnder100m + if (accuracyMeters >= 50 && accuracyMeters < 100) 1 else 0,
                accuracyUnder500m = pending.accuracyUnder500m + if (accuracyMeters >= 100 && accuracyMeters < 500) 1 else 0,
                accuracyOver500m = pending.accuracyOver500m + if (!(accuracyMeters < 500)) 1 else 0,
                lastUpdatedMillis = nowMillis,
            )
            pendingUpdates++
            pendingFlow.value = pending
            flushIfNeededLocked(force = false)
        }
    }

    override suspend fun recordStateSlice(
        motion: MotionState,
        operational: OperationalState,
        durationMillis: Long,
    ) {
        if (durationMillis <= 0) return
        val nowMillis = System.currentTimeMillis()
        bufferLock.withLock {
            pending = pending.copy(
                idleMillis = pending.idleMillis + if (motion == MotionState.IDLE) durationMillis else 0,
                stationaryMillis = pending.stationaryMillis + if (motion == MotionState.STATIONARY) durationMillis else 0,
                movingMillis = pending.movingMillis + if (motion == MotionState.MOVING) durationMillis else 0,
                journeyMillis = pending.journeyMillis + if (motion == MotionState.JOURNEY) durationMillis else 0,
                activeMillis = pending.activeMillis + if (operational == OperationalState.ACTIVE) durationMillis else 0,
                degradedMillis = pending.degradedMillis + if (operational == OperationalState.DEGRADED) durationMillis else 0,
                offlineMillis = pending.offlineMillis + if (operational == OperationalState.OFFLINE) durationMillis else 0,
                pausedMillis = pending.pausedMillis + if (operational == OperationalState.PAUSED) durationMillis else 0,
                lastUpdatedMillis = nowMillis,
            )
            pendingUpdates++
            pendingFlow.value = pending
            flushIfNeededLocked(force = false)
        }
    }

    override suspend fun recordUpload(batchSize: Int) {
        val nowMillis = System.currentTimeMillis()
        bufferLock.withLock {
            pending = pending.copy(
                uploadBatches = pending.uploadBatches + 1,
                uploadedPoints = pending.uploadedPoints + batchSize,
                lastUpdatedMillis = nowMillis,
            )
            pendingUpdates++
            pendingFlow.value = pending
            flushIfNeededLocked(force = true)
        }
    }

    override suspend fun recordServiceStart() {
        bufferLock.withLock {
            pending = pending.copy(serviceStarts = pending.serviceStarts + 1)
            pendingUpdates++
            pendingFlow.value = pending
            flushIfNeededLocked(force = true)
        }
    }

    override suspend fun recordServiceStop(activeMillis: Long) {
        bufferLock.withLock {
            if (activeMillis > 0) {
                pending = pending.copy(serviceActiveMillis = pending.serviceActiveMillis + activeMillis)
                pendingUpdates++
                pendingFlow.value = pending
            }
            flushIfNeededLocked(force = true)
        }
    }

    private suspend fun flushIfNeededLocked(force: Boolean) {
        if (pendingUpdates == 0) return
        val elapsedSinceFlush = elapsedNow() - lastFlushAtElapsed
        if (!force && pendingUpdates < FLUSH_UPDATE_THRESHOLD && elapsedSinceFlush < FLUSH_INTERVAL_MILLIS) {
            return
        }
        val snapshot = pending
        dataStore.edit { prefs ->
            fun add(key: Preferences.Key<Long>, delta: Long) {
                if (delta > 0) prefs[key] = (prefs[key] ?: 0) + delta
            }
            add(KEY_CALLBACKS, snapshot.callbacks)
            add(KEY_ACCEPTED, snapshot.accepted)
            add(KEY_REJECTED_STALE, snapshot.rejectedStale)
            add(KEY_REJECTED_OUT_OF_ORDER, snapshot.rejectedOutOfOrder)
            add(KEY_REJECTED_INACCURATE, snapshot.rejectedInaccurate)
            add(KEY_REJECTED_DUPLICATE, snapshot.rejectedDuplicate)
            add(KEY_ACC_25, snapshot.accuracyUnder25m)
            add(KEY_ACC_50, snapshot.accuracyUnder50m)
            add(KEY_ACC_100, snapshot.accuracyUnder100m)
            add(KEY_ACC_500, snapshot.accuracyUnder500m)
            add(KEY_ACC_OVER_500, snapshot.accuracyOver500m)
            add(KEY_IDLE, snapshot.idleMillis)
            add(KEY_STATIONARY, snapshot.stationaryMillis)
            add(KEY_MOVING, snapshot.movingMillis)
            add(KEY_JOURNEY, snapshot.journeyMillis)
            add(KEY_ACTIVE, snapshot.activeMillis)
            add(KEY_DEGRADED, snapshot.degradedMillis)
            add(KEY_OFFLINE, snapshot.offlineMillis)
            add(KEY_PAUSED, snapshot.pausedMillis)
            add(KEY_UPLOAD_BATCHES, snapshot.uploadBatches)
            add(KEY_UPLOADED_POINTS, snapshot.uploadedPoints)
            add(KEY_SERVICE_STARTS, snapshot.serviceStarts)
            add(KEY_SERVICE_ACTIVE_MILLIS, snapshot.serviceActiveMillis)
            if (snapshot.lastUpdatedMillis > (prefs[KEY_LAST_UPDATED] ?: 0)) {
                prefs[KEY_LAST_UPDATED] = snapshot.lastUpdatedMillis
            }
        }
        pending = TrackingMetrics()
        pendingUpdates = 0
        lastFlushAtElapsed = elapsedNow()
        pendingFlow.value = pending
    }

    private companion object {
        const val FLUSH_UPDATE_THRESHOLD = 10
        const val FLUSH_INTERVAL_MILLIS = 5 * 60_000L
        val KEY_CALLBACKS = longPreferencesKey("callbacks")
        val KEY_ACCEPTED = longPreferencesKey("accepted")
        val KEY_REJECTED_STALE = longPreferencesKey("rejected_stale")
        val KEY_REJECTED_OUT_OF_ORDER = longPreferencesKey("rejected_out_of_order")
        val KEY_REJECTED_INACCURATE = longPreferencesKey("rejected_inaccurate")
        val KEY_REJECTED_DUPLICATE = longPreferencesKey("rejected_duplicate")
        val KEY_ACC_25 = longPreferencesKey("accuracy_under_25")
        val KEY_ACC_50 = longPreferencesKey("accuracy_under_50")
        val KEY_ACC_100 = longPreferencesKey("accuracy_under_100")
        val KEY_ACC_500 = longPreferencesKey("accuracy_under_500")
        val KEY_ACC_OVER_500 = longPreferencesKey("accuracy_over_500")
        val KEY_IDLE = longPreferencesKey("motion_idle_millis")
        val KEY_STATIONARY = longPreferencesKey("motion_stationary_millis")
        val KEY_MOVING = longPreferencesKey("motion_moving_millis")
        val KEY_JOURNEY = longPreferencesKey("motion_journey_millis")
        val KEY_ACTIVE = longPreferencesKey("operational_active_millis")
        val KEY_DEGRADED = longPreferencesKey("operational_degraded_millis")
        val KEY_OFFLINE = longPreferencesKey("operational_offline_millis")
        val KEY_PAUSED = longPreferencesKey("operational_paused_millis")
        val KEY_UPLOAD_BATCHES = longPreferencesKey("upload_batches")
        val KEY_UPLOADED_POINTS = longPreferencesKey("uploaded_points")
        val KEY_SERVICE_STARTS = longPreferencesKey("service_starts")
        val KEY_SERVICE_ACTIVE_MILLIS = longPreferencesKey("service_active_millis")
        val KEY_LAST_UPDATED = longPreferencesKey("last_updated")
    }
}
