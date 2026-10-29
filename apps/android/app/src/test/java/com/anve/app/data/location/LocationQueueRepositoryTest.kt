package com.anve.app.data.location

import com.anve.app.tracking.testFix
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationQueueRepositoryTest {

    @Test
    fun `unknown capture provenance never enters the durable queue`() = runBlocking {
        val dao = FakeLocationQueueDao()
        val repo = LocationQueueRepository(dao, now = { now })
        assertFalse(repo.enqueue("user-1", "device-1", testFix()))
        assertTrue(dao.rows.isEmpty())
    }

    private val now = 10_000_000L

    private fun repository(
        dao: FakeLocationQueueDao = FakeLocationQueueDao(),
    ) = LocationQueueRepository(dao, now = { now }, captureGeneration = { _, _ -> 1 }) to dao

    @Test
    fun `a valid fix is stored with its owner and original event identity`() = runBlocking {
        val (repo, dao) = repository()
        val fix = testFix(eventId = "event-a", recordedAtMillis = now - 1_000)

        assertTrue(repo.enqueue("user-1", "device-1", fix))

        val row = dao.rows.getValue("event-a")
        assertEquals("user-1", row.ownerUserId)
        assertEquals("device-1", row.clientDeviceId)
        assertEquals(fix.recordedAtMillis, row.recordedAtMillis)
        assertEquals(fix.latitude, row.latitude, 0.0)
        assertEquals(fix.longitude, row.longitude, 0.0)
        assertEquals(LOCATION_SCHEMA_VERSION, row.schemaVersion)
    }

    @Test
    fun `invalid provider values never become durable rows`() = runBlocking {
        val (repo, dao) = repository()

        assertFalse(repo.enqueue("user-1", "device-1", testFix(latitude = Double.NaN)))
        assertFalse(repo.enqueue("user-1", "device-1", testFix(longitude = 181.0)))
        assertFalse(repo.enqueue("user-1", "device-1", testFix(accuracy = -1.0)))
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `re-enqueuing the same event id never duplicates a row`() = runBlocking {
        val (repo, dao) = repository()
        val fix = testFix(eventId = "same-event")

        assertTrue(repo.enqueue("user-1", "device-1", fix))
        assertFalse(repo.enqueue("user-1", "device-1", fix))

        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `the queue is bounded by dropping the oldest points`() = runBlocking {
        val (repo, dao) = repository()
        dao.forcedCount = MAX_QUEUED_LOCATIONS + 5

        assertTrue(repo.enqueue("user-1", "device-1", testFix()))

        assertEquals(
            "only the overflow beyond the bound is dropped",
            "user-1" to 5,
            dao.lastDeleteOldest,
        )
    }

    @Test
    fun `pending rows are oldest-first and account-scoped`() = runBlocking {
        val (repo, dao) = repository()
        dao.rows["a"] = queued("a", "user-1", now - 1_000)
        dao.rows["b"] = queued("b", "user-1", now - 3_000)
        dao.rows["c"] = queued("c", "user-2", now - 2_000)

        val rows = repo.pending("user-1")

        assertEquals(listOf("b", "a"), rows.map { it.eventId })
    }

    @Test
    fun `retention is measured from the original capture time`() = runBlocking {
        val (repo, dao) = repository()
        dao.rows["old"] = queued("old", "user-1", now - LOCATION_QUEUE_TTL_MILLIS - 1)
        dao.rows["fresh"] = queued("fresh", "user-1", now - LOCATION_QUEUE_TTL_MILLIS + 1)

        val deleted = repo.purgeExpired()

        assertEquals(1, deleted)
        assertEquals(now - LOCATION_QUEUE_TTL_MILLIS, dao.lastRetentionCutoff)
        assertTrue(dao.rows.containsKey("fresh"))
        assertFalse(dao.rows.containsKey("old"))
    }

    @Test
    fun `consent revocation purges every queued point`() = runBlocking {
        val (repo, dao) = repository()
        dao.rows["a"] = queued("a", "user-1", now)
        dao.rows["b"] = queued("b", "user-2", now)

        assertEquals(2, repo.purgeAll())
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `rows of another account are removed before upload`() = runBlocking {
        val (repo, dao) = repository()
        dao.rows["mine"] = queued("mine", "user-1", now)
        dao.rows["other"] = queued("other", "user-2", now)

        assertEquals(1, repo.purgeOtherAccounts("user-1"))
        assertEquals(setOf("mine"), dao.rows.keys)
    }

    @Test
    fun `marking an attempt records retry metadata on exactly those rows`() = runBlocking {
        val (repo, dao) = repository()
        dao.rows["a"] = queued("a", "user-1", now)
        dao.rows["b"] = queued("b", "user-1", now)

        repo.markAttempt(listOf("a"))

        assertEquals(1, dao.rows.getValue("a").attemptCount)
        assertEquals(now, dao.rows.getValue("a").lastAttemptAtMillis)
        assertEquals(0, dao.rows.getValue("b").attemptCount)
        assertNotNull(dao.lastAttemptMarked)
    }

    @Test
    fun `the observable count follows enqueue and removal`() = runBlocking {
        val (repo, dao) = repository()
        assertEquals(0, repo.observeCount("user-1").first())

        repo.enqueue("user-1", "device-1", testFix(eventId = "a"))
        assertEquals(1, repo.observeCount("user-1").first())

        repo.remove(listOf("a"))
        assertEquals(0, repo.observeCount("user-1").first())
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `the queue survives a process restart because it lives in durable storage`() = runBlocking {
        val dao = FakeLocationQueueDao()
        val first = LocationQueueRepository(dao, now = { now }, captureGeneration = { _, _ -> 1 })
        first.enqueue("user-1", "device-1", testFix(eventId = "persisted"))

        val restarted = LocationQueueRepository(dao, now = { now })

        assertEquals(listOf("persisted"), restarted.pending("user-1").map { it.eventId })
    }

    @Test
    fun `concurrent enqueues of different events never drop each other`() = runBlocking {
        val (repo, dao) = repository()

        coroutineScope {
            (1..20).map { index ->
                async {
                    repo.enqueue(
                        "user-1",
                        "device-1",
                        testFix(eventId = "event-$index"),
                    )
                }
            }.awaitAll()
        }

        assertEquals(20, dao.rows.size)
    }

    private fun queued(
        eventId: String,
        owner: String,
        recordedAtMillis: Long,
    ) = QueuedLocation(
        eventId = eventId,
        ownerUserId = owner,
        clientDeviceId = "device-1",
        recordedAtMillis = recordedAtMillis,
        latitude = 10.0,
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
