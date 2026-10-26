package com.anve.app.data.location

import com.anve.app.tracking.LocationFix
import kotlinx.coroutines.flow.Flow

/** At most this many accepted points wait on the device. */
const val MAX_QUEUED_LOCATIONS: Int = 5_000

/**
 * A queued point older than this is dropped before upload. The server
 * accepts a longer backfill window, but the device should not retain a
 * week of someone's movements just because it never found a network.
 */
const val LOCATION_QUEUE_TTL_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

/** Server batch limit is 200; 100 keeps requests small on mobile networks. */
const val SYNC_BATCH_SIZE: Int = 100

/**
 * The durable offline queue. Every method is account-scoped: the owner id
 * is stored in each row and filters every read, so queued points can never
 * be uploaded under a different account.
 */
class LocationQueueRepository(
    private val dao: LocationQueueDao,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Persists one accepted fix before it is considered captured. Returns
     * false when the fix is invalid or the row already exists (same event
     * id), which is not an error: idempotency is the point.
     */
    suspend fun enqueue(
        ownerUserId: String,
        clientDeviceId: String,
        fix: LocationFix,
    ): Boolean {
        if (!fix.isStorable()) return false
        purgeExpired()
        val inserted = dao.insert(
            fix.toQueuedLocation(ownerUserId, clientDeviceId, now()),
        ) != -1L
        val count = dao.count(ownerUserId)
        if (count > MAX_QUEUED_LOCATIONS) {
            dao.deleteOldest(ownerUserId, count - MAX_QUEUED_LOCATIONS)
        }
        return inserted
    }

    /** Oldest-first batch for upload; event-time order is preserved. */
    suspend fun pending(ownerUserId: String, limit: Int = SYNC_BATCH_SIZE): List<QueuedLocation> =
        dao.pending(ownerUserId, limit)

    suspend fun remove(eventIds: List<String>): Int =
        if (eventIds.isEmpty()) 0 else dao.deleteByEventIds(eventIds)

    suspend fun markAttempt(eventIds: List<String>) {
        if (eventIds.isNotEmpty()) dao.markAttempt(eventIds, now())
    }

    suspend fun count(ownerUserId: String): Int = dao.count(ownerUserId)

    suspend fun countAll(): Int = dao.countAll()

    fun observeCount(ownerUserId: String): Flow<Int> = dao.observeCount(ownerUserId)

    /** Drops points that outlived the on-device retention window. */
    suspend fun purgeExpired(): Int = dao.deleteOlderThan(now() - LOCATION_QUEUE_TTL_MILLIS)

    /**
     * Privacy-first cleanup: consent revocation, sign-out and account
     * deletion drop every unsent point immediately. Sharing data captured
     * under an earlier consent state is never resumed silently.
     */
    suspend fun purgeAll(): Int = dao.deleteAll()

    /**
     * Defense in depth for a stale queue: rows of any other account are
     * removed before upload. Normally sign-out already purges the queue.
     */
    suspend fun purgeOtherAccounts(ownerUserId: String): Int =
        dao.deleteOtherAccounts(ownerUserId)
}

/**
 * Device-side sanity check before a fix becomes durable. The server
 * validates the same ranges; this keeps obviously broken provider values
 * out of the queue instead of spending retries on them.
 */
internal fun LocationFix.isStorable(): Boolean =
    eventId.isNotBlank() &&
        latitude.isFinite() && latitude in -90.0..90.0 &&
        longitude.isFinite() && longitude in -180.0..180.0 &&
        accuracyMeters.isFinite() && accuracyMeters in 0.0..100_000.0 &&
        altitudeMeters.let { it == null || it.isFinite() } &&
        speedMetersPerSecond.let { it == null || (it.isFinite() && it in 0.0..1_000.0) } &&
        bearingDegrees.let { it == null || (it.isFinite() && it in 0.0..360.0) } &&
        provider.isNotBlank() && provider.length <= 32 &&
        recordedAtMillis > 0
