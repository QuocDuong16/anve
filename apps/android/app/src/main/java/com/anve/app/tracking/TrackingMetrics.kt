package com.anve.app.tracking

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

class DataStoreTrackingMetricsStore(context: Context) : TrackingMetricsStore {

    private val dataStore = context.applicationContext.trackingMetricsDataStore

    override val metrics: Flow<TrackingMetrics> = dataStore.data.map { prefs ->
        TrackingMetrics(
            callbacks = prefs[KEY_CALLBACKS] ?: 0,
            accepted = prefs[KEY_ACCEPTED] ?: 0,
            rejectedStale = prefs[KEY_REJECTED_STALE] ?: 0,
            rejectedOutOfOrder = prefs[KEY_REJECTED_OUT_OF_ORDER] ?: 0,
            rejectedInaccurate = prefs[KEY_REJECTED_INACCURATE] ?: 0,
            rejectedDuplicate = prefs[KEY_REJECTED_DUPLICATE] ?: 0,
            accuracyUnder25m = prefs[KEY_ACC_25] ?: 0,
            accuracyUnder50m = prefs[KEY_ACC_50] ?: 0,
            accuracyUnder100m = prefs[KEY_ACC_100] ?: 0,
            accuracyUnder500m = prefs[KEY_ACC_500] ?: 0,
            accuracyOver500m = prefs[KEY_ACC_OVER_500] ?: 0,
            idleMillis = prefs[KEY_IDLE] ?: 0,
            stationaryMillis = prefs[KEY_STATIONARY] ?: 0,
            movingMillis = prefs[KEY_MOVING] ?: 0,
            journeyMillis = prefs[KEY_JOURNEY] ?: 0,
            activeMillis = prefs[KEY_ACTIVE] ?: 0,
            degradedMillis = prefs[KEY_DEGRADED] ?: 0,
            offlineMillis = prefs[KEY_OFFLINE] ?: 0,
            pausedMillis = prefs[KEY_PAUSED] ?: 0,
            uploadBatches = prefs[KEY_UPLOAD_BATCHES] ?: 0,
            uploadedPoints = prefs[KEY_UPLOADED_POINTS] ?: 0,
            serviceStarts = prefs[KEY_SERVICE_STARTS] ?: 0,
            serviceActiveMillis = prefs[KEY_SERVICE_ACTIVE_MILLIS] ?: 0,
            lastUpdatedMillis = prefs[KEY_LAST_UPDATED] ?: 0,
        )
    }

    override suspend fun recordFix(decision: FixDecision, accuracyMeters: Double) {
        dataStore.edit { prefs ->
            prefs[KEY_CALLBACKS] = (prefs[KEY_CALLBACKS] ?: 0) + 1
            if (decision.accepted) {
                prefs[KEY_ACCEPTED] = (prefs[KEY_ACCEPTED] ?: 0) + 1
            } else {
                val key = when (decision.rejection) {
                    FixRejection.STALE -> KEY_REJECTED_STALE
                    FixRejection.OUT_OF_ORDER -> KEY_REJECTED_OUT_OF_ORDER
                    FixRejection.INACCURATE -> KEY_REJECTED_INACCURATE
                    FixRejection.DUPLICATE -> KEY_REJECTED_DUPLICATE
                    null -> KEY_REJECTED_INACCURATE
                }
                prefs[key] = (prefs[key] ?: 0) + 1
            }
            val bucket = when {
                accuracyMeters < 25 -> KEY_ACC_25
                accuracyMeters < 50 -> KEY_ACC_50
                accuracyMeters < 100 -> KEY_ACC_100
                accuracyMeters < 500 -> KEY_ACC_500
                else -> KEY_ACC_OVER_500
            }
            prefs[bucket] = (prefs[bucket] ?: 0) + 1
            prefs[KEY_LAST_UPDATED] = System.currentTimeMillis()
        }
    }

    override suspend fun recordStateSlice(
        motion: MotionState,
        operational: OperationalState,
        durationMillis: Long,
    ) {
        if (durationMillis <= 0) return
        dataStore.edit { prefs ->
            val motionKey = when (motion) {
                MotionState.IDLE -> KEY_IDLE
                MotionState.STATIONARY -> KEY_STATIONARY
                MotionState.MOVING -> KEY_MOVING
                MotionState.JOURNEY -> KEY_JOURNEY
            }
            val operationalKey = when (operational) {
                OperationalState.ACTIVE -> KEY_ACTIVE
                OperationalState.DEGRADED -> KEY_DEGRADED
                OperationalState.OFFLINE -> KEY_OFFLINE
                OperationalState.PAUSED -> KEY_PAUSED
            }
            prefs[motionKey] = (prefs[motionKey] ?: 0) + durationMillis
            prefs[operationalKey] = (prefs[operationalKey] ?: 0) + durationMillis
            prefs[KEY_LAST_UPDATED] = System.currentTimeMillis()
        }
    }

    override suspend fun recordUpload(batchSize: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_UPLOAD_BATCHES] = (prefs[KEY_UPLOAD_BATCHES] ?: 0) + 1
            prefs[KEY_UPLOADED_POINTS] = (prefs[KEY_UPLOADED_POINTS] ?: 0) + batchSize
            prefs[KEY_LAST_UPDATED] = System.currentTimeMillis()
        }
    }

    override suspend fun recordServiceStart() {
        dataStore.edit { prefs ->
            prefs[KEY_SERVICE_STARTS] = (prefs[KEY_SERVICE_STARTS] ?: 0) + 1
        }
    }

    override suspend fun recordServiceStop(activeMillis: Long) {
        if (activeMillis <= 0) return
        dataStore.edit { prefs ->
            prefs[KEY_SERVICE_ACTIVE_MILLIS] =
                (prefs[KEY_SERVICE_ACTIVE_MILLIS] ?: 0) + activeMillis
        }
    }

    private companion object {
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
