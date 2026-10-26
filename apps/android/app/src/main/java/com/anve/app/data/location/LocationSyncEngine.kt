package com.anve.app.data.location

import com.anve.app.data.api.AnveApi
import com.anve.app.data.api.ApiException
import com.anve.app.data.api.LocationBatchRequestDto
import com.anve.app.data.api.LocationBatchResponseDto
import com.anve.app.data.api.LocationEventDto
import com.anve.app.data.api.LocationSharingRequestDto
import com.anve.app.data.api.orThrow
import com.anve.app.data.auth.SessionStorage
import com.anve.app.tracking.NoOpTrackingMetricsStore
import com.anve.app.tracking.TrackingMetricsStore
import com.anve.app.tracking.TrackingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Instant

/** What one sync pass achieved. Only [RETRY] asks WorkManager to reschedule. */
enum class SyncDisposition {
    COMPLETE,
    RETRY,
    SIGNED_OUT,
    POLICY_REFUSED,
    PERMANENT_REJECTION,
}

data class SyncOutcome(
    val disposition: SyncDisposition,
    val uploaded: Int = 0,
    val dropped: Int = 0,
    val errorCode: String? = null,
) {
    val retry: Boolean
        get() = disposition == SyncDisposition.RETRY
}

/** The HTTP surface the sync engine needs, so tests can fake it. */
interface LocationSyncGateway {
    suspend fun currentUserId(): String
    suspend fun upload(events: List<LocationEventDto>): LocationBatchResponseDto
    suspend fun disableLocationSharing()
}

class ApiLocationSyncGateway(private val api: AnveApi) : LocationSyncGateway {
    override suspend fun currentUserId(): String = api.me().orThrow().id

    override suspend fun upload(events: List<LocationEventDto>): LocationBatchResponseDto =
        api.uploadLocationBatch(LocationBatchRequestDto(events)).orThrow()

    override suspend fun disableLocationSharing() {
        api.setLocationSharing(LocationSharingRequestDto(enabled = false)).orThrow()
    }
}

/**
 * Uploads the durable queue with at-least-once semantics and server-side
 * idempotency:
 *
 * 1. A pending consent revocation is delivered **before any location** and
 *    the queue is purged. Offline consent OFF must never be followed by a
 *    silent backfill.
 * 2. Rows are uploaded oldest-first in bounded batches; accepted and
 *    duplicate ids are deleted only after the server acknowledged them.
 * 3. A lost response is safe: the retry sends the same event ids and the
 *    server reports them as duplicates, which are also deleted.
 * 4. Transport, 429 and 5xx responses keep every row and ask for backoff.
 *    Permanent validation errors drop only the offending batch: retrying
 *    it forever would block the rest of the queue.
 *
 * The engine never uploads rows of another account, and never rows when
 * the session is gone.
 */
class LocationSyncEngine(
    private val gateway: LocationSyncGateway,
    private val queue: LocationQueueRepository,
    private val store: TrackingStore,
    private val sessions: SessionStorage,
    private val now: () -> Long = System::currentTimeMillis,
    private val onPolicyRefusal: suspend (String) -> Unit = {},
    private val metrics: TrackingMetricsStore = NoOpTrackingMetricsStore,
) {

    // One pass at a time: the unique WorkManager job is normally the only
    // caller, but a manual "retry now" must not race it.
    private val passLock = Mutex()

    suspend fun sync(): SyncOutcome = passLock.withLock { syncPass() }

    private suspend fun syncPass(): SyncOutcome {
        val session = sessions.current() ?: run {
            // No account: queued coordinates must not outlive the session.
            queue.purgeAll()
            return SyncOutcome(SyncDisposition.COMPLETE)
        }
        val ownerUserId = session.userId ?: resolveLegacyOwner(session) ?: return SyncOutcome(
            SyncDisposition.RETRY,
            errorCode = "SESSION_OWNER_UNKNOWN",
        )

        queue.purgeOtherAccounts(ownerUserId)
        queue.purgeExpired()

        pendingRevocationOutcome(ownerUserId)?.let { return it }

        var uploaded = 0
        var dropped = 0
        var batches = 0
        while (batches < MAX_BATCHES_PER_PASS) {
            // A revocation may have landed while the previous batch was in
            // flight. Re-check before every request, not only at pass start.
            pendingRevocationOutcome(ownerUserId)?.let { return it }
            val rows = queue.pending(ownerUserId, SYNC_BATCH_SIZE)
            if (rows.isEmpty()) {
                return SyncOutcome(SyncDisposition.COMPLETE, uploaded = uploaded, dropped = dropped)
            }
            queue.markAttempt(rows.map { it.eventId })

            val response = try {
                gateway.upload(rows.map { it.toDto() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                val outcome = handleApiFailure(e, rows, uploaded, dropped)
                if (outcome.disposition != SyncDisposition.PERMANENT_REJECTION) return outcome
                // That batch can never be accepted; drop it and continue
                // with the rest instead of blocking the queue.
                uploaded = outcome.uploaded
                dropped = outcome.dropped
                batches++
                continue
            } catch (e: IOException) {
                store.setLastSyncError("NETWORK")
                return SyncOutcome(SyncDisposition.RETRY, uploaded = uploaded, dropped = dropped,
                    errorCode = "NETWORK")
            }

            val acknowledged = (response.accepted + response.duplicates).toSet()
            val acknowledgedRows = rows.filter { it.eventId in acknowledged }
            queue.remove(acknowledgedRows.map { it.eventId })
            uploaded += acknowledgedRows.size
            metrics.recordUpload(acknowledgedRows.size)
            store.setLastSuccessfulUpload(now())

            if (acknowledgedRows.size != rows.size) {
                // The server's atomic contract should acknowledge every
                // event; keep unacknowledged rows rather than guessing.
                store.setLastSyncError("PARTIAL_ACK")
                return SyncOutcome(SyncDisposition.RETRY, uploaded = uploaded, dropped = dropped,
                    errorCode = "PARTIAL_ACK")
            }
            batches++
        }
        // More work remains, but this pass is bounded so one run cannot
        // hold the device awake indefinitely.
        return SyncOutcome(SyncDisposition.RETRY, uploaded = uploaded, dropped = dropped)
    }

    /**
     * Handles a pending consent revocation, if any. Returns null when the
     * queue may continue. The revocation is always purged from the local
     * queue, and is only sent for the account that created it.
     */
    private suspend fun pendingRevocationOutcome(ownerUserId: String): SyncOutcome? {
        val local = store.current()
        if (!local.pendingConsentRevocation) return null
        queue.purgeAll()
        if (local.consentOwnerUserId != ownerUserId) {
            // The revoking account is not the signed-in one (or its
            // provenance is unknown). Sending it would disable sharing for
            // an innocent account; drop the stale intent instead.
            store.setPendingConsentRevocation(false)
            return SyncOutcome(SyncDisposition.COMPLETE)
        }
        return deliverConsentRevocation()
    }

    /** Fills the owner id for a session persisted before M3 added it. */
    private suspend fun resolveLegacyOwner(session: com.anve.app.data.auth.StoredSession): String? {
        return try {
            val id = gateway.currentUserId()
            // Only the account id is written; the tokens stay whatever the
            // interceptor last rotated them to.
            sessions.setUserId(id)
            id
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun deliverConsentRevocation(): SyncOutcome {
        return try {
            gateway.disableLocationSharing()
            store.setPendingConsentRevocation(false)
            store.setLocalConsentConfirmed(false)
            store.setLastSyncError(null)
            SyncOutcome(SyncDisposition.COMPLETE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.code == "UNAUTHENTICATED") {
                SyncOutcome(SyncDisposition.SIGNED_OUT, errorCode = e.code)
            } else {
                store.setLastSyncError(e.code)
                SyncOutcome(SyncDisposition.RETRY, errorCode = e.code)
            }
        } catch (_: IOException) {
            store.setLastSyncError("NETWORK")
            SyncOutcome(SyncDisposition.RETRY, errorCode = "NETWORK")
        }
    }

    private suspend fun handleApiFailure(
        error: ApiException,
        rows: List<QueuedLocation>,
        uploaded: Int,
        dropped: Int,
    ): SyncOutcome {
        return when (error.code) {
            "UNAUTHENTICATED" ->
                // A 401 can be a transient refresh failure. Only report
                // signed-out when the interceptor actually cleared storage;
                // otherwise keep the rows and retry with backoff.
                if (sessions.current() == null) {
                    SyncOutcome(SyncDisposition.SIGNED_OUT, uploaded = uploaded, dropped = dropped,
                        errorCode = error.code)
                } else {
                    store.setLastSyncError(error.code)
                    SyncOutcome(SyncDisposition.RETRY, uploaded = uploaded, dropped = dropped,
                        errorCode = error.code)
                }

            "LOCATION_CONSENT_REQUIRED" -> {
                // The server is authoritative: local consent was stale.
                queue.purgeAll()
                store.setLocalConsentConfirmed(false)
                store.setLastSyncError(error.code)
                onPolicyRefusal(error.code)
                SyncOutcome(SyncDisposition.POLICY_REFUSED, errorCode = error.code)
            }

            "LOCATION_CIRCLE_REQUIRED" -> {
                queue.purgeAll()
                store.setLastSyncError(error.code)
                onPolicyRefusal(error.code)
                SyncOutcome(SyncDisposition.POLICY_REFUSED, errorCode = error.code)
            }

            "DEVICE_NOT_AUTHORIZED" -> {
                queue.purgeAll()
                store.setLastSyncError(error.code)
                onPolicyRefusal(error.code)
                SyncOutcome(SyncDisposition.POLICY_REFUSED, errorCode = error.code)
            }

            "LOCATION_BATCH_INVALID", "LOCATION_EVENT_TOO_OLD", "LOCATION_BATCH_TOO_LARGE" -> {
                // Permanent for these rows. Drop exactly this batch; the
                // rest of the queue can still sync.
                val removed = queue.remove(rows.map { it.eventId })
                store.setLastSyncError(error.code)
                SyncOutcome(
                    SyncDisposition.PERMANENT_REJECTION,
                    uploaded = uploaded,
                    dropped = dropped + removed,
                    errorCode = error.code,
                )
            }

            else -> {
                store.setLastSyncError(error.code)
                SyncOutcome(SyncDisposition.RETRY, uploaded = uploaded, dropped = dropped,
                    errorCode = error.code)
            }
        }
    }

    companion object {
        /** Five batches (500 points) per pass bounds wake time and request count. */
        const val MAX_BATCHES_PER_PASS: Int = 5
    }
}

/** Wire shape of one queued row; the capture time stays the original one. */
internal fun QueuedLocation.toDto(): LocationEventDto = LocationEventDto(
    eventId = eventId,
    clientDeviceId = clientDeviceId,
    recordedAt = Instant.ofEpochMilli(recordedAtMillis).toString(),
    latitude = latitude,
    longitude = longitude,
    horizontalAccuracyMeters = accuracyMeters,
    altitudeMeters = altitudeMeters,
    speedMetersPerSecond = speedMetersPerSecond,
    bearingDegrees = bearingDegrees,
    provider = provider,
    isMock = isMock,
    schemaVersion = schemaVersion,
)
