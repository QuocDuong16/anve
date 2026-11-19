package com.anve.app.data.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryAlertPolicyTest {

    @Test
    fun `discharging triggers 15 then 5 once each`() {
        val policy = BatteryAlertPolicy()

        assertEquals(listOf(BatteryAction.CancelPending), policy.onSample(BatterySample(20, false)))
        assertTrue(policy.onSample(BatterySample(16, false)).isEmpty())
        assertEquals(
            listOf(BatteryAction.TriggerAlert(15, 15)),
            policy.onSample(BatterySample(15, false)),
        )
        assertTrue(policy.onSample(BatterySample(14, false)).isEmpty())
        assertTrue(policy.onSample(BatterySample(6, false)).isEmpty())
        assertEquals(
            listOf(BatteryAction.TriggerAlert(5, 5)),
            policy.onSample(BatterySample(5, false)),
        )
        assertTrue(policy.onSample(BatterySample(4, false)).isEmpty())
    }

    @Test
    fun `starting below 5 percent triggers only 5 percent and marks 15 passed`() {
        val policy = BatteryAlertPolicy()

        assertEquals(
            listOf(BatteryAction.TriggerAlert(5, 4)),
            policy.onSample(BatterySample(4, false)),
        )
        // Brief voltage bounce above 10% while below 20% rearms 5% only, not 15%.
        assertTrue(policy.onSample(BatterySample(12, false)).isEmpty())
        assertEquals(
            listOf(BatteryAction.TriggerAlert(5, 5)),
            policy.onSample(BatterySample(5, false)),
        )
    }

    @Test
    fun `starting at 12 percent discharging triggers 15 percent once`() {
        val policy = BatteryAlertPolicy()

        assertEquals(
            listOf(BatteryAction.TriggerAlert(15, 12)),
            policy.onSample(BatterySample(12, false)),
        )
        assertTrue(policy.onSample(BatterySample(11, false)).isEmpty())
    }

    @Test
    fun `charging rearms at 20 percent and requests silent resolve only when previously triggered`() {
        val policy = BatteryAlertPolicy()

        // Charging to >= 20% without a previous trigger does not resolve.
        assertEquals(
            listOf(BatteryAction.CancelPending),
            policy.onSample(BatterySample(18, true)),
        )
        assertEquals(
            listOf(BatteryAction.CancelPending),
            policy.onSample(BatterySample(20, true)),
        )

        // After triggering 15%, charging below 20% cancels pending only.
        assertEquals(
            listOf(BatteryAction.TriggerAlert(15, 15)),
            policy.onSample(BatterySample(15, false)),
        )
        assertEquals(
            listOf(BatteryAction.CancelPending),
            policy.onSample(BatterySample(19, true)),
        )
        // Reaching >= 20% charging resolves active alerts and rearms 15%.
        assertEquals(
            listOf(BatteryAction.CancelPending, BatteryAction.ResolveActiveAlerts),
            policy.onSample(BatterySample(20, true)),
        )
        assertEquals(
            listOf(BatteryAction.CancelPending),
            policy.onSample(BatterySample(21, true)),
        )
        // Unplugging and dropping back to 15% can now fire 15% again.
        assertEquals(
            listOf(BatteryAction.TriggerAlert(15, 15)),
            policy.onSample(BatterySample(15, false)),
        )
    }
}
