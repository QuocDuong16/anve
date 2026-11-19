package com.anve.app.data.battery

data class BatterySample(
    val percent: Int,
    val isCharging: Boolean,
)

sealed interface BatteryAction {
    data class TriggerAlert(val threshold: Int, val batteryPercent: Int) : BatteryAction
    data object CancelPending : BatteryAction
    data object ResolveActiveAlerts : BatteryAction
}

/**
 * Pure threshold state machine for opt-in low-battery alerts (15% and 5%).
 *
 * - Discharging only: charging samples cancel pending offline sends and never
 *   emit threshold triggers.
 * - Lowest satisfied threshold fires once when entering below 15%: starting at
 *   <= 5% triggers only 5% and marks 15% as passed.
 * - Hysteresis: 5% rearms at >= 10%; both 15% and 5% rearm at >= 20%.
 * - Charging recovery to >= 20% emits [BatteryAction.ResolveActiveAlerts] when
 *   at least one threshold previously fired in this cycle.
 */
class BatteryAlertPolicy {
    private var passed15 = false
    private var passed5 = false
    private var hasTriggered = false

    fun reset() {
        passed15 = false
        passed5 = false
        hasTriggered = false
    }

    fun onSample(sample: BatterySample): List<BatteryAction> {
        if (sample.percent !in 0..100) return emptyList()

        if (sample.isCharging) {
            val actions = mutableListOf<BatteryAction>(BatteryAction.CancelPending)
            if (sample.percent >= REARM_CRITICAL_PERCENT) {
                passed5 = false
            }
            if (sample.percent >= REARM_WARNING_PERCENT) {
                passed15 = false
                passed5 = false
                if (hasTriggered) {
                    hasTriggered = false
                    actions += BatteryAction.ResolveActiveAlerts
                }
            }
            return actions
        }

        if (sample.percent >= REARM_WARNING_PERCENT) {
            passed15 = false
            passed5 = false
            return listOf(BatteryAction.CancelPending)
        }
        if (sample.percent >= REARM_CRITICAL_PERCENT) {
            passed5 = false
        }

        if (sample.percent <= THRESHOLD_CRITICAL && !passed5) {
            passed15 = true
            passed5 = true
            hasTriggered = true
            return listOf(BatteryAction.TriggerAlert(THRESHOLD_CRITICAL, sample.percent))
        }
        if (sample.percent <= THRESHOLD_WARNING && !passed15) {
            passed15 = true
            hasTriggered = true
            return listOf(BatteryAction.TriggerAlert(THRESHOLD_WARNING, sample.percent))
        }
        return emptyList()
    }

    companion object {
        const val THRESHOLD_WARNING: Int = 15
        const val THRESHOLD_CRITICAL: Int = 5
        const val REARM_CRITICAL_PERCENT: Int = 10
        const val REARM_WARNING_PERCENT: Int = 20
        const val RESOLVE_CHARGING_PERCENT: Int = 20
    }
}
