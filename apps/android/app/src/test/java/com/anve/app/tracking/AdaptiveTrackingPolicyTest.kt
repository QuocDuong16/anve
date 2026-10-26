package com.anve.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveTrackingPolicyTest {

    private val baseElapsed = 100_000L

    private fun fix(
        elapsed: Long,
        latitude: Double = 10.7769,
        longitude: Double = 106.7009,
        accuracy: Double = 10.0,
        speed: Double? = null,
    ) = testFix(
        eventId = "event-$elapsed",
        recordedAtMillis = elapsed,
        elapsedRealtimeNanos = elapsed * 1_000_000,
        latitude = latitude,
        longitude = longitude,
        accuracy = accuracy,
        speed = speed,
    )

    @Test
    fun `the first accurate fix becomes stationary and uses the low-power request`() {
        val policy = AdaptiveTrackingPolicy()
        val decision = policy.onFix(fix(baseElapsed, speed = 0.0), baseElapsed)

        assertTrue(decision.accepted)
        assertEquals(MotionState.STATIONARY, decision.motion)
        assertEquals(5 * 60_000, decision.request.intervalMillis)
        assertEquals(50f, decision.request.minDistanceMeters)
        assertFalse(decision.request.highAccuracy)
    }

    @Test
    fun `stationary becomes moving when displacement crosses the threshold`() {
        val policy = AdaptiveTrackingPolicy()
        val stationary = policy.onFix(fix(baseElapsed, speed = 0.0), baseElapsed)
        assertEquals(MotionState.STATIONARY, stationary.motion)

        // ~167 m north after one minute, without a provider speed value.
        val moved = policy.onFix(
            fix(baseElapsed + 60_000, latitude = 10.7784),
            baseElapsed + 60_000,
        )

        assertTrue(moved.accepted)
        assertEquals(MotionState.MOVING, moved.motion)
        assertTrue(moved.request.highAccuracy)
    }

    @Test
    fun `a fast fix enters moving and asks for high accuracy`() {
        val policy = AdaptiveTrackingPolicy()
        val decision = policy.onFix(fix(baseElapsed, speed = 3.0), baseElapsed)

        assertTrue(decision.accepted)
        assertEquals(MotionState.MOVING, decision.motion)
        assertEquals(30_000, decision.request.intervalMillis)
        assertTrue(decision.request.highAccuracy)
    }

    @Test
    fun `moving returns to stationary after two minutes below the exit speed`() {
        val policy = AdaptiveTrackingPolicy()
        policy.onFix(fix(baseElapsed, speed = 3.0), baseElapsed)

        val atOneMinute = baseElapsed + 60_000
        val stillMoving = policy.onFix(
            fix(atOneMinute, latitude = 10.7770, speed = 0.2),
            atOneMinute,
        )
        assertTrue(stillMoving.accepted)
        assertEquals(MotionState.MOVING, stillMoving.motion)

        val atThreeMinutes = baseElapsed + 180_000
        val stopped = policy.onFix(
            fix(atThreeMinutes, latitude = 10.7770, speed = 0.2),
            atThreeMinutes,
        )
        assertTrue(stopped.accepted)
        assertEquals(MotionState.STATIONARY, stopped.motion)
        assertFalse(stopped.request.highAccuracy)
    }

    @Test
    fun `the journey hook overrides inference but is never automatic`() {
        val policy = AdaptiveTrackingPolicy()
        policy.onFix(fix(baseElapsed, speed = 4.0), baseElapsed)
        assertEquals(MotionState.MOVING, policy.motion)

        policy.setJourney(true)
        assertEquals(MotionState.JOURNEY, policy.motion)
        val decision = policy.onFix(fix(baseElapsed + 1_000, speed = 4.0), baseElapsed + 1_000)
        assertEquals(MotionState.JOURNEY, decision.motion)
        assertEquals(15_000, decision.request.intervalMillis)

        policy.setJourney(false)
        assertEquals(MotionState.MOVING, policy.motion)
    }

    @Test
    fun `stale cached fixes are rejected`() {
        val policy = AdaptiveTrackingPolicy()
        val decision = policy.onFix(
            fix(baseElapsed - AdaptiveTrackingPolicy.MAX_FIX_AGE_MILLIS - 1),
            baseElapsed,
        )
        assertFalse(decision.accepted)
        assertEquals(FixRejection.STALE, decision.rejection)
    }

    @Test
    fun `inaccurate fixes are not treated as positions`() {
        val policy = AdaptiveTrackingPolicy()
        val decision = policy.onFix(
            fix(baseElapsed, accuracy = AdaptiveTrackingPolicy.MAX_ACCEPTED_ACCURACY_METERS + 1),
            baseElapsed,
        )
        assertFalse(decision.accepted)
        assertEquals(FixRejection.INACCURATE, decision.rejection)
    }

    @Test
    fun `out-of-order callbacks are rejected`() {
        val policy = AdaptiveTrackingPolicy()
        assertTrue(policy.onFix(fix(baseElapsed), baseElapsed).accepted)

        val older = policy.onFix(fix(baseElapsed - 5_000), baseElapsed)
        assertFalse(older.accepted)
        assertEquals(FixRejection.OUT_OF_ORDER, older.rejection)
    }

    @Test
    fun `same-place jitter inside the window is a duplicate but a later heartbeat is accepted`() {
        val policy = AdaptiveTrackingPolicy()
        assertTrue(policy.onFix(fix(baseElapsed), baseElapsed).accepted)

        val jitter = policy.onFix(fix(baseElapsed + 10_000), baseElapsed + 10_000)
        assertFalse(jitter.accepted)
        assertEquals(FixRejection.DUPLICATE, jitter.rejection)

        val heartbeat = policy.onFix(fix(baseElapsed + 61_000), baseElapsed + 61_000)
        assertTrue(heartbeat.accepted)
    }

    @Test
    fun `battery saver halves the acquisition rate without stopping it`() {
        val policy = AdaptiveTrackingPolicy()
        policy.onFix(fix(baseElapsed, speed = 3.0), baseElapsed)
        val normal = policy.requestOptions(batterySaver = false)
        val saver = policy.requestOptions(batterySaver = true)

        assertEquals(normal.intervalMillis * 2, saver.intervalMillis)
        assertEquals(normal.minDistanceMeters * 2, saver.minDistanceMeters)
        assertEquals(normal.highAccuracy, saver.highAccuracy)
    }

    @Test
    fun `invalid coordinates are rejected`() {
        val policy = AdaptiveTrackingPolicy()
        val decision = policy.onFix(fix(baseElapsed, latitude = 91.0), baseElapsed)
        assertFalse(decision.accepted)
        assertNotNull(decision.rejection)
    }

    @Test
    fun `distance is a sane great-circle measurement`() {
        val a = fix(baseElapsed, latitude = 10.0, longitude = 106.0)
        val b = fix(baseElapsed, latitude = 11.0, longitude = 106.0)
        val distance = distanceMeters(a, b)
        assertEquals(111_195.0, distance, 500.0)
    }
}
