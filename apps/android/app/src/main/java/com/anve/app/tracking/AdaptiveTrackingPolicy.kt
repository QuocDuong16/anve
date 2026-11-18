package com.anve.app.tracking

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deterministic adaptive acquisition policy. It is deliberately free of
 * Android types so the thresholds can be unit-tested with a fake clock.
 *
 * The numbers below are engineering defaults, not measured optima. They
 * follow one rule: do not request high-accuracy GPS while the device is
 * stationary, and do not accept every provider callback as a trustworthy
 * coordinate. Field calibration is still required (see the M3 docs).
 */
class AdaptiveTrackingPolicy(
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    @Volatile
    var motion: MotionState = MotionState.IDLE
        private set

    private var journeyActive = false
    private var lastAccepted: LocationFix? = null
    private var lastAcceptedAtMillis: Long = 0
    private var lastMovementAtMillis: Long = 0
    private var decelerationConfirmUntilMillis: Long = 0
    private var lastFixElapsedMillis: Long = 0

    /** Clears accepted-fix history and stationary state for a new session or checkpoint. */
    @Synchronized
    fun reset() {
        motion = if (journeyActive) MotionState.JOURNEY else MotionState.IDLE
        lastAccepted = null
        lastAcceptedAtMillis = 0L
        lastMovementAtMillis = 0L
        decelerationConfirmUntilMillis = 0L
        lastFixElapsedMillis = 0L
    }

    /** M7 hook; M3 never enters JOURNEY automatically. */
    @Synchronized
    fun setJourney(active: Boolean) {
        journeyActive = active
        if (active) {
            motion = MotionState.JOURNEY
        } else if (motion == MotionState.JOURNEY) {
            motion = MotionState.MOVING
        }
    }

    @Synchronized
    fun onFix(
        fix: LocationFix,
        nowElapsedMillis: Long = clockMillis(),
        batterySaver: Boolean = false,
    ): FixDecision {
        if (!fix.hasValidCoordinates()) {
            return decision(false, FixRejection.INACCURATE, batterySaver)
        }
        val ageMillis = nowElapsedMillis - fix.elapsedRealtimeNanos / 1_000_000
        if (ageMillis > MAX_FIX_AGE_MILLIS) {
            return decision(false, FixRejection.STALE, batterySaver)
        }
        if (!fix.accuracyMeters.isFinite() ||
            fix.accuracyMeters <= 0 ||
            fix.accuracyMeters > MAX_ACCEPTED_ACCURACY_METERS
        ) {
            return decision(false, FixRejection.INACCURATE, batterySaver)
        }

        val previous = lastAccepted
        if (previous != null && fix.elapsedRealtimeNanos <= previous.elapsedRealtimeNanos) {
            return decision(false, FixRejection.OUT_OF_ORDER, batterySaver)
        }

        val displacement = previous?.let { distanceMeters(it, fix) } ?: 0.0
        val sinceLastAccepted = nowElapsedMillis - lastAcceptedAtMillis
        lastFixElapsedMillis = nowElapsedMillis
        val inConfirmWindow = journeyActive || nowElapsedMillis < decelerationConfirmUntilMillis
        val duplicateWindowMillis = if (inConfirmWindow) {
            CONFIRM_SAME_PLACE_WINDOW_MILLIS
        } else {
            SAME_PLACE_WINDOW_MILLIS
        }
        if (previous != null &&
            displacement < SAME_PLACE_DISPLACEMENT_METERS &&
            sinceLastAccepted < duplicateWindowMillis
        ) {
            return decision(false, FixRejection.DUPLICATE, batterySaver)
        }

        lastAccepted = fix
        lastAcceptedAtMillis = nowElapsedMillis
        updateMotion(fix, displacement, nowElapsedMillis)
        return decision(true, null, batterySaver)
    }

    /** Current request parameters; the coordinator restarts the provider when they change. */
    @Synchronized
    fun requestOptions(batterySaver: Boolean = false): LocationRequestOptions =
        baseOptions(motion, batterySaver, lastFixElapsedMillis)

    private fun updateMotion(fix: LocationFix, displacementMeters: Double, nowMillis: Long) {
        if (journeyActive) {
            motion = MotionState.JOURNEY
            lastMovementAtMillis = nowMillis
            return
        }

        val speed = fix.speedMetersPerSecond
        val fast = (speed != null && speed.isFinite() && speed >= MOVING_ENTER_SPEED_MPS) ||
            displacementMeters >= MOVING_ENTER_DISPLACEMENT_METERS
        if (fast) {
            motion = MotionState.MOVING
            lastMovementAtMillis = nowMillis
            decelerationConfirmUntilMillis = 0L
            return
        }

        when (motion) {
            MotionState.IDLE, MotionState.JOURNEY -> {
                motion = MotionState.STATIONARY
                lastMovementAtMillis = nowMillis
            }

            MotionState.STATIONARY -> {
                // Stay stationary: the provider's heartbeat keeps freshness,
                // and this branch only runs for an accepted displacement.
                if (displacementMeters >= MOVING_ENTER_DISPLACEMENT_METERS) {
                    motion = MotionState.MOVING
                    lastMovementAtMillis = nowMillis
                    decelerationConfirmUntilMillis = 0L
                }
            }

            MotionState.MOVING -> {
                if (displacementMeters >= MOVEMENT_KEEPALIVE_DISPLACEMENT_METERS) {
                    lastMovementAtMillis = nowMillis
                    decelerationConfirmUntilMillis = 0L
                } else if (decelerationConfirmUntilMillis == 0L) {
                    // Maintain 45s arrival confirmation cadence for the first 3 minutes
                    // after slowing down so server-side place/journey dwell has enough samples.
                    decelerationConfirmUntilMillis = nowMillis + DECELERATION_CONFIRM_WINDOW_MILLIS
                }
                val slow = speed == null || !speed.isFinite() || speed <= STATIONARY_ENTER_SPEED_MPS
                if (slow && nowMillis - lastMovementAtMillis >= STATIONARY_CONFIRM_MILLIS) {
                    motion = MotionState.STATIONARY
                }
            }
        }
    }

    private fun decision(
        accepted: Boolean,
        rejection: FixRejection?,
        batterySaver: Boolean,
    ): FixDecision = FixDecision(
        accepted = accepted,
        rejection = rejection,
        motion = motion,
        request = baseOptions(motion, batterySaver, lastFixElapsedMillis),
    )

    private fun baseOptions(
        motion: MotionState,
        batterySaver: Boolean,
        nowElapsedMillis: Long = lastFixElapsedMillis,
    ): LocationRequestOptions {
        val base = when (motion) {
            MotionState.IDLE -> LocationRequestOptions(
                intervalMillis = 60_000,
                minDistanceMeters = 100f,
                highAccuracy = false,
            )

            MotionState.STATIONARY -> LocationRequestOptions(
                intervalMillis = if (nowElapsedMillis < decelerationConfirmUntilMillis) {
                    DECELERATION_CONFIRM_INTERVAL_MILLIS
                } else {
                    5 * 60_000
                },
                minDistanceMeters = 50f,
                highAccuracy = false,
            )

            MotionState.MOVING -> LocationRequestOptions(
                intervalMillis = 30_000,
                minDistanceMeters = 25f,
                highAccuracy = true,
            )

            MotionState.JOURNEY -> LocationRequestOptions(
                intervalMillis = 15_000,
                minDistanceMeters = 20f,
                highAccuracy = true,
            )
        }
        if (!batterySaver) return base
        // Power saver doubles the interval and the displacement filter. It
        // never silently stops acquisition: the UI reports the limitation.
        return base.copy(
            intervalMillis = if (nowElapsedMillis < decelerationConfirmUntilMillis) {
                minOf(base.intervalMillis * 2, DECELERATION_CONFIRM_INTERVAL_MILLIS)
            } else {
                base.intervalMillis * 2
            },
            minDistanceMeters = base.minDistanceMeters * 2,
        )
    }

    private fun LocationFix.hasValidCoordinates(): Boolean =
        latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0

    companion object {
        /** A cached/provider-replayed fix older than this is not "current". */
        const val MAX_FIX_AGE_MILLIS: Long = 120_000

        /** Above this the coordinate is too coarse to represent a position. */
        const val MAX_ACCEPTED_ACCURACY_METERS: Double = 500.0

        /** Jitter smaller than this within the window is not a new sample. */
        const val SAME_PLACE_DISPLACEMENT_METERS: Double = 5.0
        const val SAME_PLACE_WINDOW_MILLIS: Long = 60_000
        const val CONFIRM_SAME_PLACE_WINDOW_MILLIS: Long = 45_000
        const val DECELERATION_CONFIRM_INTERVAL_MILLIS: Long = 45_000
        const val DECELERATION_CONFIRM_WINDOW_MILLIS: Long = 180_000

        /** Hysteresis: entering movement is easier than leaving it. */
        const val MOVING_ENTER_SPEED_MPS: Double = 1.4
        const val MOVING_ENTER_DISPLACEMENT_METERS: Double = 50.0
        const val MOVEMENT_KEEPALIVE_DISPLACEMENT_METERS: Double = 20.0
        const val STATIONARY_ENTER_SPEED_MPS: Double = 0.5
        const val STATIONARY_CONFIRM_MILLIS: Long = 120_000
    }
}

/** Outcome of one fix; [request] is what the provider should use next. */
data class FixDecision(
    val accepted: Boolean,
    val rejection: FixRejection?,
    val motion: MotionState,
    val request: LocationRequestOptions,
)

/**
 * Great-circle distance on the WGS-84 mean sphere. Accuracy is sufficient
 * for motion hysteresis and coarse place proximity labels.
 */
internal fun distanceMeters(a: LocationFix, b: LocationFix): Double =
    distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)

internal fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadius = 6_371_000.0
    val rLat1 = Math.toRadians(lat1)
    val rLat2 = Math.toRadians(lat2)
    val dLat = rLat2 - rLat1
    val dLon = Math.toRadians(lon2 - lon1)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(rLat1) * cos(rLat2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * earthRadius * asin(min(1.0, sqrt(h)))
}

