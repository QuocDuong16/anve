package com.anve.app.data.location

import com.anve.app.data.api.AnveApi
import com.anve.app.data.api.ApiException
import com.anve.app.data.api.LocationBatchRequestDto
import com.anve.app.data.api.LocationBatchResponseDto
import com.anve.app.data.api.LocationEventDto
import com.anve.app.data.api.orThrow
import com.anve.app.data.auth.ConsentMutationCoordinator
import com.anve.app.data.auth.SessionStorage
import com.anve.app.tracking.NoOpTrackingMetricsStore
import com.anve.app.tracking.TrackingMetricsStore
import com.anve.app.tracking.TrackingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Instant
import java.util.UUID

/** What one sync pass achieved. Only [RETRY] asks WorkManager to reschedule. */
enum class SyncDisposition {
    COMPLETE,
    RETRY,
    SIGNED_OUT,
    POLICY_REFUSED,
    BLOCKED,
    PERMANENT_REJECTION,
}

data class SyncOutcome(
    val disposition: SyncDisposition,
    val uploaded: Int = 0,
    val dropped: Int = 0,
    val errorCode: String? = null,
    val retryAfterMillis: Long? = null,
) {
    val retry: Boolean
        get() = disposition == SyncDisposition.RETRY
}

/** The HTTP surface the sync engine needs, so tests can fake it. */
interface LocationSyncGateway {
    suspend fun currentUserId(): String
    suspend fun upload(owner: String, events: List<LocationEventDto>): LocationBatchResponseDto
    suspend fun captureGeneration(owner: String): Long = 0
}

class ApiLocationSyncGateway(private val api: AnveApi) : LocationSyncGateway {
    override suspend fun currentUserId(): String = api.me().orThrow().id

    override suspend fun upload(owner: String, events: List<LocationEventDto>): LocationBatchResponseDto =
        api.uploadLocationBatch(owner, LocationBatchRequestDto(events)).orThrow()

    override suspend fun captureGeneration(owner: String): Long = api.captureGeneration(owner).orThrow().generation

}

/**
 * Uploads the durable queue with at-least-once semantics and server-side
 * idempotency:
 *
 * 1. Any owner-scoped consent mutation blocks upload. An OFF operation has
 *    already purged the queue locally and is delivered by the auth outbox;
 *    offline consent OFF must never be followed by a silent backfill.
 * 2. Rows are uploaded oldest-first in bounded batches; accepted and
 *    duplicate ids are deleted only after the server acknowledged them.
 * 3. A lost response is safe: the retry sends the same event ids and the
 *    server reports them as duplicates, which are also deleted.
 * 4. Transport, 429 and 5xx responses keep every row and ask for backoff.
 *    A permanent batch validation error is isolated with a bounded request
 *    budget; only an invalid singleton is dropped, so valid neighbors remain.
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
    private val consentMutations: ConsentMutationCoordinator = ConsentMutationCoordinator(),
    private val expectedClientDeviceId: (suspend () -> String)? = null,
    private val captureCheckpointRevision: () -> Long = { 0 },
    private val onCaptureGeneration: (String, Long, Long) -> Unit = { _, _, _ -> },
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

        val pendingOperation = sessions.pendingConsentOperations().firstOrNull {
            it.ownerUserId == ownerUserId
        }
        if (pendingOperation != null) {
            consentMutations.restoreBlockedAdmission(ownerUserId, pendingOperation.generation)
        }
        queue.purgeOtherAccounts(ownerUserId)
        queue.purgeExpired()

        var uploaded = 0
        var dropped = 0
        // A durable consent operation owns its own reconciliation worker.
        // In particular, a persisted OFF must purge this owner's queue even
        // though the in-memory admission gate was restored closed above.
        if (pendingOperation != null) {
            if (!pendingOperation.desiredEnabled) queue.purgeAll()
            return SyncOutcome(
                SyncDisposition.COMPLETE,
                errorCode = "CONSENT_MUTATION_PENDING",
            )
        }
        val expectedDevice = try {
            expectedClientDeviceId?.invoke()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return SyncOutcome(SyncDisposition.RETRY, errorCode = "DEVICE_ID_UNAVAILABLE")
        }
        val budget = RequestBudget(MAX_HTTP_REQUESTS_PER_PASS)
        var batches = 0
        var generationChecked = false
        while (batches < MAX_BATCHES_PER_PASS && budget.remaining > 0) {
            val currentPending = sessions.pendingConsentOperations().firstOrNull {
                it.ownerUserId == ownerUserId
            }
            if (currentPending != null) {
                if (!currentPending.desiredEnabled) queue.purgeAll()
                return SyncOutcome(
                    SyncDisposition.COMPLETE,
                    uploaded = uploaded,
                    dropped = dropped,
                    errorCode = "CONSENT_MUTATION_PENDING",
                )
            }
            if (consentMutations.isLocationAdmissionClosed(ownerUserId)) {
                return SyncOutcome(
                    SyncDisposition.BLOCKED,
                    uploaded = uploaded,
                    dropped = dropped,
                    errorCode = "LOCAL_CONSENT_STOP",
                )
            }
            val local = store.current()
            if (local.pendingConsentRevocation && local.consentOwnerUserId == ownerUserId) {
                queue.purgeAll()
                return SyncOutcome(SyncDisposition.RETRY, uploaded = uploaded, dropped = dropped,
                    errorCode = "CONSENT_REVOCATION_PENDING")
            }
            if (!generationChecked) {
                try {
                    val revision = captureCheckpointRevision()
                    val generation = gateway.captureGeneration(ownerUserId)
                    consentMutations.withLocalMutation {
                        val current = sessions.current()
                        val localState = store.current()
                        val pending = sessions.pendingConsentOperations().any { it.ownerUserId == ownerUserId }
                        if (generation > 0 && current?.userId == ownerUserId && !pending &&
                            !consentMutations.isLocationAdmissionClosed(ownerUserId) &&
                            localState.consentOwnerUserId == ownerUserId && localState.localConsentConfirmed &&
                            !localState.pendingConsentRevocation
                        ) onCaptureGeneration(ownerUserId, generation, revision)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return SyncOutcome(SyncDisposition.RETRY, errorCode = "CAPTURE_GENERATION_UNAVAILABLE")
                }
                if (sessions.current()?.userId != ownerUserId) {
                    return SyncOutcome(SyncDisposition.BLOCKED, errorCode = "ACCOUNT_CHANGED")
                }
                generationChecked = true
            }
            val rows = queue.pending(ownerUserId, SYNC_BATCH_SIZE)
            if (rows.isEmpty()) {
                return SyncOutcome(SyncDisposition.COMPLETE, uploaded = uploaded, dropped = dropped)
            }
            val invalid = rows.firstOrNull { it.validationError(now(), expectedDevice) != null }
            if (invalid != null) {
                queue.remove(listOf(invalid.eventId))
                dropped++
                store.setLastSyncError(invalid.validationError(now(), expectedDevice))
                batches++
                continue
            }
            queue.markAttempt(rows.map { it.eventId })
            val outcome = uploadWithIsolation(ownerUserId, rows, budget)
            uploaded += outcome.uploaded
            dropped += outcome.dropped
            if (outcome.disposition == SyncDisposition.RETRY ||
                outcome.disposition == SyncDisposition.SIGNED_OUT ||
                outcome.disposition == SyncDisposition.POLICY_REFUSED ||
                outcome.disposition == SyncDisposition.BLOCKED
            ) {
                return outcome.copy(uploaded = uploaded, dropped = dropped)
            }
            batches++
        }
        if (queue.count(ownerUserId) == 0) {
            return SyncOutcome(SyncDisposition.COMPLETE, uploaded = uploaded, dropped = dropped)
        }
        // More work remains, but this pass is bounded so one run cannot
        // hold the device awake indefinitely.
        return SyncOutcome(SyncDisposition.RETRY, uploaded = uploaded, dropped = dropped)
    }

    private suspend fun uploadWithIsolation(
        ownerUserId: String,
        rows: List<QueuedLocation>,
        budget: RequestBudget,
    ): SyncOutcome {
        if (budget.remaining <= 0) return SyncOutcome(SyncDisposition.RETRY)
        var pendingOff = false
        var legacyOff = false
        val uploadAuthorized = consentMutations.authorizeLocationUpload(ownerUserId) {
            val pending = sessions.pendingConsentOperations().filter { it.ownerUserId == ownerUserId }
            pendingOff = pending.any { !it.desiredEnabled }
            legacyOff = store.current().let {
                it.pendingConsentRevocation && it.consentOwnerUserId == ownerUserId
            }
            pending.isNotEmpty() || legacyOff
        }
        if (!uploadAuthorized) {
            if (pendingOff || legacyOff) queue.purgeAll()
            return if (consentMutations.isLocationAdmissionClosed(ownerUserId)) {
                SyncOutcome(SyncDisposition.BLOCKED, errorCode = "LOCAL_CONSENT_STOP")
            } else {
                SyncOutcome(SyncDisposition.COMPLETE, errorCode = "CONSENT_MUTATION_PENDING")
            }
        }
        budget.remaining--
        val response = try {
            gateway.upload(ownerUserId, rows.map { it.toDto() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.code in VALIDATION_REJECTIONS) {
                if (rows.size == 1) {
                    val removed = queue.remove(listOf(rows.single().eventId))
                    store.setLastSyncError(e.code)
                    return SyncOutcome(SyncDisposition.PERMANENT_REJECTION, dropped = removed, errorCode = e.code)
                }
                val midpoint = rows.size / 2
                val left = uploadWithIsolation(ownerUserId, rows.subList(0, midpoint), budget)
                if (left.disposition in setOf(
                        SyncDisposition.RETRY,
                        SyncDisposition.SIGNED_OUT,
                        SyncDisposition.POLICY_REFUSED,
                        SyncDisposition.BLOCKED,
                    )
                ) {
                    return left
                }
                val right = uploadWithIsolation(ownerUserId, rows.subList(midpoint, rows.size), budget)
                return SyncOutcome(
                    disposition = when {
                        right.disposition in setOf(
                            SyncDisposition.RETRY,
                            SyncDisposition.SIGNED_OUT,
                            SyncDisposition.POLICY_REFUSED,
                        ) ->
                            right.disposition
                        right.disposition == SyncDisposition.BLOCKED -> SyncDisposition.BLOCKED
                        left.disposition == SyncDisposition.PERMANENT_REJECTION ||
                            right.disposition == SyncDisposition.PERMANENT_REJECTION -> SyncDisposition.PERMANENT_REJECTION
                        else -> SyncDisposition.COMPLETE
                    },
                    uploaded = left.uploaded + right.uploaded,
                    dropped = left.dropped + right.dropped,
                    errorCode = right.errorCode ?: left.errorCode,
                    retryAfterMillis = right.retryAfterMillis ?: left.retryAfterMillis,
                )
            }
            return handleApiFailure(e)
        } catch (_: IOException) {
            store.setLastSyncError("NETWORK")
            return SyncOutcome(SyncDisposition.RETRY, errorCode = "NETWORK")
        }

        val acknowledged = (response.accepted + response.duplicates).toSet()
        val acknowledgedRows = rows.filter { it.eventId in acknowledged }
        queue.remove(acknowledgedRows.map { it.eventId })
        if (acknowledgedRows.isNotEmpty()) {
            metrics.recordUpload(acknowledgedRows.size)
            store.setLastSuccessfulUpload(now())
        }
        if (acknowledgedRows.size != rows.size) {
            store.setLastSyncError("PARTIAL_ACK")
            return SyncOutcome(SyncDisposition.RETRY, uploaded = acknowledgedRows.size, errorCode = "PARTIAL_ACK")
        }
        return SyncOutcome(SyncDisposition.COMPLETE, uploaded = acknowledgedRows.size)
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

    private suspend fun handleApiFailure(error: ApiException): SyncOutcome {
        return when (error.code) {
            "UNAUTHENTICATED" ->
                // A 401 can be a transient refresh failure. Only report
                // signed-out when the interceptor actually cleared storage;
                // otherwise keep the rows and retry with backoff.
                if (sessions.current() == null) {
                    SyncOutcome(SyncDisposition.SIGNED_OUT, errorCode = error.code)
                } else {
                    store.setLastSyncError(error.code)
                    SyncOutcome(SyncDisposition.RETRY, errorCode = error.code)
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

            else -> {
                store.setLastSyncError(error.code)
                when {
                    error.httpStatus == 429 || error.httpStatus != null && error.httpStatus >= 500 ->
                        SyncOutcome(
                            SyncDisposition.RETRY,
                            errorCode = error.code,
                            retryAfterMillis = error.retryAfterMillis,
                        )
                    error.httpStatus?.let { it in 400..499 } == true ->
                        SyncOutcome(SyncDisposition.BLOCKED, errorCode = error.code)
                    else -> SyncOutcome(SyncDisposition.RETRY, errorCode = error.code)
                }
            }
        }
    }

    companion object {
        /** Five batches (500 points) per pass bounds wake time and request count. */
        const val MAX_BATCHES_PER_PASS: Int = 5
        const val MAX_HTTP_REQUESTS_PER_PASS: Int = 64
        val VALIDATION_REJECTIONS = setOf(
            "LOCATION_BATCH_INVALID",
            "LOCATION_EVENT_TOO_OLD",
            "LOCATION_HISTORY_DELETED",
            "LOCATION_CAPTURE_GENERATION_EXPIRED",
            "LOCATION_BATCH_TOO_LARGE",
        )
    }
}

private data class RequestBudget(var remaining: Int)

private fun QueuedLocation.validationError(nowMillis: Long, expectedDeviceId: String?): String? = when {
    runCatching { UUID.fromString(eventId) }.getOrNull()?.toString()?.equals(eventId, ignoreCase = true) != true ->
        "CLIENT_INVALID_EVENT_ID"
    clientDeviceId.length !in 8..64 -> "CLIENT_INVALID_DEVICE_ID"
    expectedDeviceId != null && clientDeviceId != expectedDeviceId -> "CLIENT_DEVICE_MISMATCH"
    recordedAtMillis <= 0L || recordedAtMillis > nowMillis + 2 * 60_000L -> "CLIENT_INVALID_TIMESTAMP"
    recordedAtMillis < nowMillis - LOCATION_QUEUE_TTL_MILLIS -> "CLIENT_EVENT_EXPIRED"
    !latitude.isFinite() || latitude !in -90.0..90.0 -> "CLIENT_INVALID_COORDINATES"
    !longitude.isFinite() || longitude !in -180.0..180.0 -> "CLIENT_INVALID_COORDINATES"
    !accuracyMeters.isFinite() || accuracyMeters < 0.0 || accuracyMeters > 100_000.0 -> "CLIENT_INVALID_ACCURACY"
    altitudeMeters?.let { !it.isFinite() || kotlin.math.abs(it) > 1_000_000.0 } == true ->
        "CLIENT_INVALID_MEASUREMENT"
    speedMetersPerSecond?.let { !it.isFinite() || it < 0.0 || it > 1_000.0 } == true -> "CLIENT_INVALID_MEASUREMENT"
    bearingDegrees?.let { !it.isFinite() || it < 0.0 || it > 360.0 } == true -> "CLIENT_INVALID_MEASUREMENT"
    provider.isBlank() || provider.length > 32 || provider.any {
        it !in 'a'..'z' && it !in '0'..'9' && it != '_' && it != '-'
    } -> "CLIENT_INVALID_PROVIDER"
    schemaVersion !in 1..16 -> "CLIENT_INVALID_SCHEMA"
    else -> null
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
    captureGeneration = captureGeneration,
)
