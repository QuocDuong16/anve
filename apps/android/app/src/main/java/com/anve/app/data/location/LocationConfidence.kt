package com.anve.app.data.location

/**
 * Advances a server-classified sample's age while the screen is open. The
 * monotonic clock can make confidence less optimistic, never more optimistic.
 */
fun displayLocationConfidence(
    serverConfidence: String,
    ageAtSnapshotMillis: Long?,
    snapshotObservedElapsedMillis: Long?,
    nowElapsedMillis: Long,
): String {
    if (serverConfidence == "UNKNOWN" || serverConfidence == "OFFLINE") return serverConfidence
    val ageAtSnapshot = ageAtSnapshotMillis ?: return serverConfidence
    val observedAt = snapshotObservedElapsedMillis ?: return serverConfidence
    if (ageAtSnapshot < 0 || nowElapsedMillis < observedAt) return serverConfidence

    val age = ageAtSnapshot + (nowElapsedMillis - observedAt)
    val computed = when {
        age <= FRESH_MAX_AGE_MILLIS -> "FRESH"
        age <= DELAYED_MAX_AGE_MILLIS -> "DELAYED"
        else -> "STALE"
    }
    return if (computed.confidenceRank() > serverConfidence.confidenceRank()) computed else serverConfidence
}

private fun String.confidenceRank(): Int = when (this) {
    "FRESH" -> 0
    "DELAYED" -> 1
    "STALE" -> 2
    else -> Int.MAX_VALUE
}

private const val FRESH_MAX_AGE_MILLIS = 2 * 60_000L
private const val DELAYED_MAX_AGE_MILLIS = 15 * 60_000L
