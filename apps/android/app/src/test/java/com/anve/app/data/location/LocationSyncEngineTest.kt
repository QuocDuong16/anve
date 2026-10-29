package com.anve.app.data.location

import com.anve.app.data.api.ApiException
import com.anve.app.data.api.LocationBatchResponseDto
import com.anve.app.data.auth.InMemorySessionStorage
import com.anve.app.data.auth.ConsentMutationCoordinator
import com.anve.app.data.auth.PendingConsentOperation
import com.anve.app.data.auth.StoredSession
import com.anve.app.tracking.FakeSyncGateway
import com.anve.app.tracking.FakeTrackingStore
import com.anve.app.tracking.LocalTrackingState
import com.anve.app.tracking.networkError
import com.anve.app.ui.awaitUntil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class LocationSyncEngineTest {

    private val now = 10_000_000L
    private lateinit var dao: FakeLocationQueueDao
    private lateinit var queue: LocationQueueRepository
    private lateinit var store: FakeTrackingStore
    private lateinit var sessions: InMemorySessionStorage
    private lateinit var consentMutations: ConsentMutationCoordinator
    private lateinit var gateway: FakeSyncGateway
    private lateinit var engine: LocationSyncEngine
    private val policyRefusals = mutableListOf<String>()

    @Before
    fun setUp() = runBlocking {
        dao = FakeLocationQueueDao()
        queue = LocationQueueRepository(dao, now = { now })
        store = FakeTrackingStore(
            LocalTrackingState(localConsentConfirmed = true),
        )
        sessions = InMemorySessionStorage()
        sessions.save(session("u1"))
        consentMutations = ConsentMutationCoordinator()
        consentMutations.reconcileLocationAdmission("u1", enabled = true)
        gateway = FakeSyncGateway(owner = "u1")
        engine = LocationSyncEngine(
            gateway = gateway,
            queue = queue,
            store = store,
            sessions = sessions,
            now = { now },
            onPolicyRefusal = { policyRefusals += it },
            consentMutations = consentMutations,
        )
    }

    private fun session(owner: String?) = StoredSession(
        accessToken = "access",
        refreshToken = "refresh",
        accessExpiresAt = "later",
        refreshExpiresAt = "later",
        userId = owner,
    )

    private fun seed(owner: String = "u1", count: Int = 1, startAt: Long = now - 10_000) {
        repeat(count) { index ->
            val eventId = eventId(owner, index)
            dao.rows[eventId] = QueuedLocation(
                eventId = eventId,
                ownerUserId = owner,
                clientDeviceId = "device-owner-0001",
                recordedAtMillis = startAt + index,
                latitude = 10.0 + index * 0.0001,
                longitude = 106.0,
                accuracyMeters = 10.0,
                altitudeMeters = null,
                speedMetersPerSecond = null,
                bearingDegrees = null,
                provider = "fused",
                isMock = false,
                schemaVersion = 1,
                createdAtMillis = now,
            )
        }
    }

    private fun eventId(owner: String, index: Int): String =
        UUID.nameUUIDFromBytes("$owner-$index".toByteArray()).toString()

    private fun pendingConsentOff(owner: String, op: String = "revoke-$owner") = PendingConsentOperation(
        operationId = op,
        ownerUserId = owner,
        desiredEnabled = false,
        expectedVersion = 0,
        generation = 1,
        createdAtMillis = now,
        expiresAtMillis = now + 60_000,
    )

    @Test
    fun `uploads oldest-first, deletes acknowledged rows and records success`() = runBlocking {
        seed(count = 3, startAt = now - 30_000)
        gateway.uploadHandler = { events ->
            LocationBatchResponseDto(accepted = events.map { it.eventId })
        }

        val outcome = engine.sync()

        assertEquals(SyncDisposition.COMPLETE, outcome.disposition)
        assertEquals(3, outcome.uploaded)
        assertTrue(dao.rows.isEmpty())
        assertEquals(
            listOf(eventId("u1", 0), eventId("u1", 1), eventId("u1", 2)),
            gateway.uploads.single().map { it.eventId },
        )
        assertEquals(now, store.current().lastSuccessfulUploadAtMillis)
        assertNull(store.current().lastSyncErrorCode)
        assertEquals(listOf(eventId("u1", 0), eventId("u1", 1), eventId("u1", 2)), dao.lastAttemptMarked?.first)
    }

    @Test
    fun `account switch while reading checkpoint cannot update new account or upload`() = runBlocking {
        seed()
        val requested = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Long>()
        val checkpoints = mutableListOf<Pair<String, Long>>()
        gateway.generationHandler = { requested.complete(Unit); reply.await() }
        engine = LocationSyncEngine(gateway, queue, store, sessions, now = { now },
            consentMutations = consentMutations, onCaptureGeneration = { owner, value -> checkpoints += owner to value })
        val worker = async { engine.sync() }
        requested.await()
        sessions.save(session("u2"))
        reply.complete(5)
        assertEquals(SyncDisposition.BLOCKED, worker.await().disposition)
        assertTrue(checkpoints.isEmpty())
        assertTrue(gateway.uploads.isEmpty())
    }

    @Test
    fun `offline checkpoint cannot prevent local consent off from purging queue`() = runBlocking {
        seed()
        store.setConsentOwner("u1")
        store.setPendingConsentRevocation(true)
        gateway.generationHandler = { throw networkError() }
        val outcome = engine.sync()
        assertEquals("CONSENT_REVOCATION_PENDING", outcome.errorCode)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `server-reported duplicates are acknowledged and deleted`() = runBlocking {
        seed(count = 2)
        gateway.uploadHandler = { events ->
            LocationBatchResponseDto(duplicates = events.map { it.eventId })
        }

        val outcome = engine.sync()

        assertEquals(SyncDisposition.COMPLETE, outcome.disposition)
        assertEquals(2, outcome.uploaded)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `local OFF before HTTP admission prevents a prepared worker upload`() = runBlocking {
        seed()
        val reachedAdmission = CompletableDeferred<Unit>()
        val continueWorker = CompletableDeferred<Unit>()
        dao.beforeMarkAttempt = {
            reachedAdmission.complete(Unit)
            continueWorker.await()
        }

        val worker = async { engine.sync() }
        reachedAdmission.await()
        consentMutations.closeLocationAdmission("u1")
        continueWorker.complete(Unit)

        val result = worker.await()

        assertEquals(SyncDisposition.BLOCKED, result.disposition)
        assertEquals("no HTTP request begins after the local barrier", 0, gateway.uploads.size)
        assertEquals("the worker preserves rows when privacy cleanup is unresolved", 1, dao.rows.size)
    }

    @Test
    fun `a transport failure keeps every row for the next attempt`() = runBlocking {
        seed(count = 2)
        gateway.uploadHandler = { throw networkError() }

        val outcome = engine.sync()

        assertEquals(SyncDisposition.RETRY, outcome.disposition)
        assertEquals("NETWORK", store.current().lastSyncErrorCode)
        assertEquals(2, dao.rows.size)
    }

    @Test
    fun `a server error keeps the rows and asks for backoff`() = runBlocking {
        seed()
        gateway.uploadHandler = { throw ApiException("INTERNAL", "boom", null) }

        val outcome = engine.sync()

        assertEquals(SyncDisposition.RETRY, outcome.disposition)
        assertEquals("INTERNAL", store.current().lastSyncErrorCode)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `429 preserves the queue and propagates a server retry-after delay`() = runBlocking {
        seed(count = 2)
        gateway.uploadHandler = {
            throw ApiException(
                "RATE_LIMITED",
                "slow down",
                null,
                httpStatus = 429,
                retryAfterMillis = 17_000,
            )
        }

        val outcome = engine.sync()

        assertEquals(SyncDisposition.RETRY, outcome.disposition)
        assertEquals(17_000L, outcome.retryAfterMillis)
        assertEquals(2, dao.rows.size)
        assertEquals(1, gateway.uploads.size)
    }

    @Test
    fun `a partial acknowledgement keeps only the unacknowledged rows`() = runBlocking {
        seed(count = 2)
        gateway.uploadHandler = { events ->
            LocationBatchResponseDto(accepted = listOf(events.first().eventId))
        }

        val outcome = engine.sync()

        assertEquals(SyncDisposition.RETRY, outcome.disposition)
        assertEquals(1, outcome.uploaded)
        assertEquals(setOf(eventId("u1", 1)), dao.rows.keys)
        assertEquals("PARTIAL_ACK", store.current().lastSyncErrorCode)
    }

    @Test
    fun `a durable consent revocation blocks uploads and purges queued points`() = runBlocking {
        store.setConsentOwner("u1")
        store.setLocalConsentConfirmed(false)
        sessions.savePendingConsentOperation(pendingConsentOff("u1"))
        seed(count = 2)

        val outcome = engine.sync()

        assertEquals(SyncDisposition.COMPLETE, outcome.disposition)
        assertTrue("queued points must never be uploaded after a local revocation", gateway.uploads.isEmpty())
        assertTrue(dao.rows.isEmpty())
        assertTrue(sessions.pendingConsentOperations().any { it.ownerUserId == "u1" })
    }

    @Test
    fun `another accounts pending consent does not mutate or block the active owner`() = runBlocking {
        sessions.savePendingConsentOperation(pendingConsentOff("u2"))
        seed(owner = "u1")

        val outcome = engine.sync()

        assertEquals(SyncDisposition.COMPLETE, outcome.disposition)
        assertTrue(sessions.pendingConsentOperations().any { it.ownerUserId == "u2" })
        assertTrue(gateway.uploads.single().isNotEmpty())
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `a legacy owner-scoped revocation flag blocks uploads until it is migrated`() = runBlocking {
        store.setConsentOwner("u1")
        store.setPendingConsentRevocation(true)
        seed()

        val outcome = engine.sync()

        assertEquals(SyncDisposition.RETRY, outcome.disposition)
        assertTrue(store.current().pendingConsentRevocation)
        assertTrue(gateway.uploads.isEmpty())
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `a server consent refusal purges the queue and stops tracking`() = runBlocking {
        seed(count = 2)
        gateway.uploadHandler = { throw ApiException("LOCATION_CONSENT_REQUIRED", "off", null) }

        val outcome = engine.sync()

        assertEquals(SyncDisposition.POLICY_REFUSED, outcome.disposition)
        assertEquals(listOf("LOCATION_CONSENT_REQUIRED"), policyRefusals)
        assertFalse(store.current().localConsentConfirmed)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `a circle refusal purges the queue and notifies the coordinator`() = runBlocking {
        seed()
        gateway.uploadHandler = { throw ApiException("LOCATION_CIRCLE_REQUIRED", "no circle", null) }

        val outcome = engine.sync()

        assertEquals(SyncDisposition.POLICY_REFUSED, outcome.disposition)
        assertEquals(listOf("LOCATION_CIRCLE_REQUIRED"), policyRefusals)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `a revoked device stops the queue without uploading`() = runBlocking {
        seed()
        gateway.uploadHandler = { throw ApiException("DEVICE_NOT_AUTHORIZED", "no device", null) }

        val outcome = engine.sync()

        assertEquals(SyncDisposition.POLICY_REFUSED, outcome.disposition)
        assertEquals(listOf("DEVICE_NOT_AUTHORIZED"), policyRefusals)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `unknown permanent 4xx blocks the batch without deleting or resplitting it`() = runBlocking {
        seed(count = 3)
        gateway.uploadHandler = {
            throw ApiException("FORBIDDEN", "not authorized", null, httpStatus = 403)
        }

        val outcome = engine.sync()

        assertEquals(SyncDisposition.BLOCKED, outcome.disposition)
        assertEquals(0, outcome.dropped)
        assertEquals(3, dao.rows.size)
        assertEquals(1, gateway.uploads.size)
        assertEquals("FORBIDDEN", outcome.errorCode)
    }

    @Test
    fun `one invalid event in an atomic batch preserves the other 99`() = runBlocking {
        seed(count = SYNC_BATCH_SIZE)
        val invalid = eventId("u1", 72)
        gateway.uploadHandler = { events ->
            if (events.any { it.eventId == invalid }) throw ApiException("LOCATION_BATCH_INVALID", "bad", null)
            LocationBatchResponseDto(accepted = events.map { it.eventId })
        }

        val outcome = engine.sync()

        assertEquals(SyncDisposition.COMPLETE, outcome.disposition)
        assertEquals(SYNC_BATCH_SIZE - 1, outcome.uploaded)
        assertEquals(1, outcome.dropped)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `deleted history is isolated without dropping unrelated queued points`() = runBlocking {
        seed(count = 3)
        val deleted = eventId("u1", 1)
        gateway.uploadHandler = { events ->
            if (events.any { it.eventId == deleted }) throw ApiException("LOCATION_HISTORY_DELETED", "deleted", null)
            LocationBatchResponseDto(accepted = events.map { it.eventId })
        }

        val outcome = engine.sync()
        assertEquals(SyncDisposition.COMPLETE, outcome.disposition)
        assertEquals(2, outcome.uploaded)
        assertEquals(1, outcome.dropped)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `expired checkpoint is isolated while newer points upload`() = runBlocking {
        seed(count = 3)
        val stale = eventId("u1", 1)
        gateway.uploadHandler = { events ->
            if (events.any { it.eventId == stale }) throw ApiException("LOCATION_CAPTURE_GENERATION_EXPIRED", "expired", null)
            LocationBatchResponseDto(accepted = events.map { it.eventId })
        }
        val outcome = engine.sync()
        assertEquals(2, outcome.uploaded)
        assertEquals(1, outcome.dropped)
        assertTrue(dao.rows.isEmpty())
        assertEquals("LOCATION_CAPTURE_GENERATION_EXPIRED", store.current().lastSyncErrorCode)
    }

    @Test
    fun `server oversized batch response isolates smaller acceptable children`() = runBlocking {
        seed(count = SYNC_BATCH_SIZE)
        gateway.uploadHandler = { events ->
            if (events.size > 50) throw ApiException("LOCATION_BATCH_TOO_LARGE", "large", null)
            LocationBatchResponseDto(accepted = events.map { it.eventId })
        }

        val outcome = engine.sync()

        assertEquals(SyncDisposition.COMPLETE, outcome.disposition)
        assertEquals(SYNC_BATCH_SIZE, outcome.uploaded)
        assertEquals(0, outcome.dropped)
        assertTrue(dao.rows.isEmpty())
        assertTrue(gateway.uploads.size <= LocationSyncEngine.MAX_HTTP_REQUESTS_PER_PASS)
    }

    @Test
    fun `only expired capture times are dropped while recent queued events upload`() = runBlocking {
        val expiryNow = now + 8L * LOCATION_QUEUE_TTL_MILLIS
        seed(count = 2)
        val oldId = eventId("u1", 0)
        val freshId = eventId("u1", 1)
        dao.rows[oldId] = dao.rows.getValue(oldId).copy(
            recordedAtMillis = expiryNow - LOCATION_QUEUE_TTL_MILLIS - 1,
            createdAtMillis = expiryNow,
        )
        dao.rows[freshId] = dao.rows.getValue(freshId).copy(
            recordedAtMillis = expiryNow - LOCATION_QUEUE_TTL_MILLIS + 1,
            createdAtMillis = expiryNow,
        )
        engine = LocationSyncEngine(
            gateway,
            queue,
            store,
            sessions,
            now = { expiryNow },
            consentMutations = consentMutations,
        )

        val outcome = engine.sync()

        assertEquals(SyncDisposition.COMPLETE, outcome.disposition)
        assertEquals(1, outcome.uploaded)
        assertEquals(1, outcome.dropped)
        assertEquals(listOf(freshId), gateway.uploads.single().map { it.eventId })
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `invalid first and last events are isolated without losing neighbors`() = runBlocking {
        listOf(0, SYNC_BATCH_SIZE - 1).forEach { badIndex ->
            dao.rows.clear()
            seed(count = SYNC_BATCH_SIZE)
            val invalid = eventId("u1", badIndex)
            gateway.uploads.clear()
            gateway.uploadHandler = { events ->
                if (events.any { it.eventId == invalid }) throw ApiException("LOCATION_BATCH_INVALID", "bad", null)
                LocationBatchResponseDto(accepted = events.map { it.eventId })
            }

            val outcome = engine.sync()

            assertEquals(SyncDisposition.COMPLETE, outcome.disposition)
            assertEquals(SYNC_BATCH_SIZE - 1, outcome.uploaded)
            assertEquals(1, outcome.dropped)
            assertTrue(dao.rows.isEmpty())
        }
    }

    @Test
    fun `multiple invalid events are dropped individually and valid rows upload`() = runBlocking {
        seed(count = SYNC_BATCH_SIZE)
        val invalid = setOf(3, 41, 98).map { eventId("u1", it) }.toSet()
        gateway.uploadHandler = { events ->
            if (events.any { it.eventId in invalid }) throw ApiException("LOCATION_BATCH_INVALID", "bad", null)
            LocationBatchResponseDto(accepted = events.map { it.eventId })
        }

        val outcome = engine.sync()

        assertEquals(SYNC_BATCH_SIZE - invalid.size, outcome.uploaded)
        assertEquals(invalid.size, outcome.dropped)
        assertTrue(dao.rows.isEmpty())
        assertTrue(gateway.uploads.size <= LocationSyncEngine.MAX_HTTP_REQUESTS_PER_PASS)
    }

    @Test
    fun `server 500 and lost response preserve identical event ids for retry`() = runBlocking {
        seed(count = 2)
        gateway.uploadHandler = { throw networkError() }
        assertEquals(SyncDisposition.RETRY, engine.sync().disposition)
        val firstIds = gateway.uploads.single().map { it.eventId }

        gateway.uploadHandler = { events ->
            assertEquals(firstIds, events.map { it.eventId })
            LocationBatchResponseDto(duplicates = events.map { it.eventId })
        }
        val retry = engine.sync()
        assertEquals(SyncDisposition.COMPLETE, retry.disposition)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `validation isolation request count is bounded`() = runBlocking {
        seed(count = SYNC_BATCH_SIZE)
        gateway.uploadHandler = { throw ApiException("LOCATION_BATCH_INVALID", "bad", null) }

        val outcome = engine.sync()

        assertTrue(gateway.uploads.size <= LocationSyncEngine.MAX_HTTP_REQUESTS_PER_PASS)
        assertEquals(SyncDisposition.RETRY, outcome.disposition)
        assertTrue("unisolated rows must remain durable", dao.rows.isNotEmpty())
    }

    @Test
    fun `process interruption during split leaves remaining events retryable`() = runBlocking {
        seed(count = SYNC_BATCH_SIZE)
        val invalid = eventId("u1", 72)
        var calls = 0
        gateway.uploadHandler = { events ->
            calls++
            when {
                calls == 1 -> throw ApiException("LOCATION_BATCH_INVALID", "bad", null)
                calls == 2 -> LocationBatchResponseDto(accepted = events.map { it.eventId })
                calls == 3 -> throw kotlinx.coroutines.CancellationException("process stopped")
                events.any { it.eventId == invalid } -> throw ApiException("LOCATION_BATCH_INVALID", "bad", null)
                else -> LocationBatchResponseDto(accepted = events.map { it.eventId })
            }
        }

        try {
            engine.sync()
        } catch (_: kotlinx.coroutines.CancellationException) {
            // The worker process can stop after an acknowledged child batch.
        }
        assertEquals(SYNC_BATCH_SIZE / 2, dao.rows.size)

        gateway.uploadHandler = { events ->
            if (events.any { it.eventId == invalid }) throw ApiException("LOCATION_BATCH_INVALID", "bad", null)
            LocationBatchResponseDto(accepted = events.map { it.eventId })
        }
        val resumed = LocationSyncEngine(
            gateway,
            queue,
            store,
            sessions,
            now = { now },
            consentMutations = consentMutations,
        ).sync()
        assertEquals(SYNC_BATCH_SIZE / 2 - 1, resumed.uploaded)
        assertEquals(1, resumed.dropped)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `consent off during isolation prevents child uploads`() = runBlocking {
        seed(count = SYNC_BATCH_SIZE)
        var calls = 0
        gateway.uploadHandler = { _ ->
            calls++
            sessions.savePendingConsentOperation(
                PendingConsentOperation(
                    operationId = "revoke-u1",
                    ownerUserId = "u1",
                    desiredEnabled = false,
                    expectedVersion = 0,
                    generation = 1,
                    createdAtMillis = now,
                    expiresAtMillis = now + 60_000,
                ),
            )
            throw ApiException("LOCATION_BATCH_INVALID", "bad", null)
        }

        val outcome = engine.sync()

        assertEquals(SyncDisposition.COMPLETE, outcome.disposition)
        assertEquals(1, calls)
        assertTrue(gateway.uploads.size <= 1)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `without a session the queue is purged and nothing is requested`() = runBlocking {
        sessions.clear()
        seed()

        val outcome = engine.sync()

        assertEquals(SyncDisposition.COMPLETE, outcome.disposition)
        assertTrue(dao.rows.isEmpty())
        assertTrue(gateway.uploads.isEmpty())
    }

    @Test
    fun `a pre-M3 session gets its owner id persisted before upload`() = runBlocking {
        sessions.save(session(owner = null))
        seed(owner = "u1")

        val outcome = engine.sync()

        assertEquals(SyncDisposition.COMPLETE, outcome.disposition)
        assertEquals("u1", sessions.current()?.userId)
        assertEquals(
            "recording the owner must not rewrite the token pair",
            "refresh",
            sessions.current()?.refreshToken,
        )
        assertTrue(dao.rows.isEmpty())
        assertEquals(1, gateway.uploads.size)
    }

    @Test
    fun `rows belonging to another account are never uploaded`() = runBlocking {
        seed(owner = "u1")
        seed(owner = "u2")

        val outcome = engine.sync()

        assertEquals(SyncDisposition.COMPLETE, outcome.disposition)
        assertEquals(1, gateway.uploads.single().size)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `a long queue drains in bounded batches and asks for another pass`() = runBlocking {
        seed(count = 600)

        val outcome = engine.sync()

        assertEquals(SyncDisposition.RETRY, outcome.disposition)
        assertEquals(LocationSyncEngine.MAX_BATCHES_PER_PASS, gateway.uploads.size)
        assertEquals(600 - LocationSyncEngine.MAX_BATCHES_PER_PASS * SYNC_BATCH_SIZE, dao.rows.size)
    }

    @Test
    fun `a revocation between batches stops the remaining uploads`() = runBlocking {
        seed(count = SYNC_BATCH_SIZE * 2)
        var calls = 0
        gateway.uploadHandler = { events ->
            calls++
            if (calls == 1) {
                store.setConsentOwner("u1")
                store.setPendingConsentRevocation(true)
                sessions.savePendingConsentOperation(pendingConsentOff("u1", "off-during-upload"))
                queue.purgeAll()
            }
            LocationBatchResponseDto(accepted = events.map { it.eventId })
        }

        val outcome = engine.sync()

        assertEquals(SyncDisposition.COMPLETE, outcome.disposition)
        assertEquals("the second batch must not be sent", 1, calls)
        assertTrue("everything still queued is purged", dao.rows.isEmpty())
        assertTrue(sessions.pendingConsentOperations().any { !it.desiredEnabled })
    }

    @Test
    fun `a consent purge during an in-flight upload neither crashes nor restores rows`() = runBlocking {
        seed(count = 2)
        val gate = CompletableDeferred<Unit>()
        gateway.uploadHandler = { events ->
            gate.await()
            LocationBatchResponseDto(accepted = events.map { it.eventId })
        }

        val pass = async { engine.sync() }
        awaitUntil { gateway.uploads.isNotEmpty() }
        // The manager purges the queue when consent is revoked while the
        // request is already in flight.
        queue.purgeAll()
        gate.complete(Unit)

        val outcome = pass.await()
        assertEquals(SyncDisposition.COMPLETE, outcome.disposition)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `a transient 401 keeps the rows and asks for another attempt`() = runBlocking {
        seed()
        gateway.uploadHandler = { throw ApiException("UNAUTHENTICATED", "refresh failed", null) }

        val outcome = engine.sync()

        assertEquals(SyncDisposition.RETRY, outcome.disposition)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `a 401 after the session was cleared reports signed out`() = runBlocking {
        seed()
        gateway.uploadHandler = {
            sessions.clear()
            throw ApiException("UNAUTHENTICATED", "gone", null)
        }

        val outcome = engine.sync()

        assertEquals(SyncDisposition.SIGNED_OUT, outcome.disposition)
        assertEquals(1, dao.rows.size)
    }
}
