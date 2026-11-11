package com.anve.app.tracking

/** Process-local admission: server reads can renew, but cannot activate a lease. */
class JourneyPriorityLease(private val elapsedNow: () -> Long) {
    private var owner: String? = null
    private var journey: String? = null
    private var hardExpiry = 0L
    private var confirmedUntil = 0L
    @Volatile var revision: Long = 0
        private set

    @Synchronized
    fun activate(owner: String, journey: String, remainingMillis: Long, expectedRevision: Long): Boolean {
        if (expectedRevision != revision || remainingMillis <= 0) return false
        this.owner = owner
        this.journey = journey
        hardExpiry = elapsedNow() + remainingMillis.coerceAtMost(8L * 60 * 60 * 1000)
        confirmedUntil = minOf(hardExpiry, elapsedNow() + CONFIRMATION_WINDOW)
        return true
    }

    @Synchronized
    fun renew(owner: String, journey: String, expectedRevision: Long): Boolean {
        if (expectedRevision != revision || !active(owner, journey)) return false
        confirmedUntil = minOf(hardExpiry, elapsedNow() + CONFIRMATION_WINDOW)
        return true
    }

    @Synchronized
    fun active(owner: String?, journey: String? = this.journey): Boolean =
        owner != null && owner == this.owner && journey == this.journey && elapsedNow() < minOf(hardExpiry, confirmedUntil)

    @Synchronized
    fun clear() {
        owner = null
        journey = null
        hardExpiry = 0
        confirmedUntil = 0
        revision++
    }

    companion object { const val CONFIRMATION_WINDOW = 60_000L }
}
