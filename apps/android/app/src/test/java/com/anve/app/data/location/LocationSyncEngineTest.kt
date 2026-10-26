package com.anve.app.data.location

import com.anve.app.data.api.ApiException
import com.anve.app.data.api.LocationBatchResponseDto
import com.anve.app.data.auth.InMemorySessionStorage
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

class LocationSyncEngineTest {

    private val now = 10_000_000L
    private lateinit var dao: FakeLocationQueueDao
    private lateinit var queue: LocationQueueRepository
    private lateinit var store: FakeTrackingStore
    private lateinit var sessions: InMemorySessionStorage
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
        gateway = FakeSyncGateway(owner = "u1", store = store)
        engine = LocationSyncEngine(
            gateway = gateway,
            queue = queue,
            store = store,
            sessions = sessions,
            now = { now },
            onPolicyRefusal = { policyRefusals += it },
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
            val eventId = "event-$owner-$index"
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
            listOf("event-u1-0", "event-u1-1", "event-u1-2"),
            gateway.uploads.single().map { it.eventId },
        )
        assertEquals(now, store.current().lastSuccessfulUploadAtMillis)
        assertNull(store.current().lastSyncErrorCode)
        assertEquals(listOf("event-u1-0", "event-u1-1", "event-u1-2"), dao.lastAttemptMarked?.first)
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
    fun `a partial acknowledgement keeps only the unacknowledged rows`() = runBlocking {
        seed(count = 2)
        gateway.uploadHandler = { events ->
            LocationBatchResponseDto(accepted = listOf(events.first().eventId))
        }

        val outcome = engine.sync()

        assertEquals(SyncDisposition.RETRY, outcome.disposition)
        assertEquals(1, outcome.uploaded)
        assertEquals(setOf("event-u1-1"), dao.rows.keys)
        assertEquals("PARTIAL_ACK", store.current().lastSyncErrorCode)
    }

    @Test
    fun `an offline consent revocation is delivered before any upload`() = runBlocking {
        store.setConsentOwner("u1")
        store.setPendingConsentRevocation(true)
        store.setLocalConsentConfirmed(false)
        seed(count = 2)

        val outcome = engine.sync()

        assertEquals(SyncDisposition.COMPLETE, outcome.disposition)
        assertEquals(1, gateway.disableCalls)
        assertTrue("the revocation intent must exist before the request", gateway.pendingAtDisable == true)
        assertTrue("queued points must never be uploaded after a local revocation", gateway.uploads.isEmpty())
        assertTrue(dao.rows.isEmpty())
        assertFalse(store.current().pendingConsentRevocation)
    }

    @Test
    fun `a revocation intent from another account is discarded, not applied`() = runBlocking {
        store.setConsentOwner("u2")
        store.setPendingConsentRevocation(true)
        seed(owner = "u1")

        val outcome = engine.sync()

        assertEquals(SyncDisposition.COMPLETE, outcome.disposition)
        assertEquals(0, gateway.disableCalls)
        assertFalse(store.current().pendingConsentRevocation)
        assertTrue("the stale intent still purges the old queue", dao.rows.isEmpty())
        assertTrue(gateway.uploads.isEmpty())
    }

    @Test
    fun `a failed consent revocation keeps the pending flag and blocks uploads`() = runBlocking {
        store.setConsentOwner("u1")
        store.setPendingConsentRevocation(true)
        seed()
        gateway.failDisableWith = networkError()

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
    fun `a permanently invalid batch is dropped while the next one uploads`() = runBlocking {
        seed(count = SYNC_BATCH_SIZE + 1)
        var call = 0
        gateway.uploadHandler = { events ->
            call++
            if (call == 1) throw ApiException("LOCATION_BATCH_INVALID", "bad", null)
            LocationBatchResponseDto(accepted = events.map { it.eventId })
        }

        val outcome = engine.sync()

        assertEquals(SyncDisposition.COMPLETE, outcome.disposition)
        assertEquals(1, outcome.uploaded)
        assertEquals(SYNC_BATCH_SIZE, outcome.dropped)
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
            }
            LocationBatchResponseDto(accepted = events.map { it.eventId })
        }

        val outcome = engine.sync()

        assertEquals(SyncDisposition.COMPLETE, outcome.disposition)
        assertEquals("the second batch must not be sent", 1, calls)
        assertEquals(1, gateway.disableCalls)
        assertTrue("everything still queued is purged", dao.rows.isEmpty())
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
