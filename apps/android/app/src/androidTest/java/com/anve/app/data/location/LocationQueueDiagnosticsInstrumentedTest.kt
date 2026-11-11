package com.anve.app.data.location

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises SQLite aggregation itself; JVM fakes cannot verify Room's query mapping. */
@RunWith(AndroidJUnit4::class)
class LocationQueueDiagnosticsInstrumentedTest {
    private lateinit var database: LocationDatabase
    private lateinit var dao: LocationQueueDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            LocationDatabase::class.java,
        ).build()
        dao = database.locationQueueDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun emptyQueueHasNoInventedOldestTime() = runBlocking {
        assertEquals(QueueDiagnostics(0, null, 0L, 0), dao.diagnostics("owner-a"))
    }

    @Test
    fun aggregationIsOwnerScopedAndReadOnly() = runBlocking {
        dao.insert(point("first", "owner-a", createdAtMillis = 2_000, attempts = 1))
        dao.insert(point("second", "owner-a", createdAtMillis = 3_000, attempts = 3))
        dao.insert(point("other", "owner-b", createdAtMillis = 1_000, attempts = 9))
        val before = dao.pending("owner-a", 10)

        assertEquals(QueueDiagnostics(2, 2_000L, 4L, 1), dao.diagnostics("owner-a"))
        assertEquals(QueueDiagnostics(1, 1_000L, 9L, 1), dao.diagnostics("owner-b"))
        assertEquals(QueueDiagnostics(0, null, 0L, 0), dao.diagnostics("unknown"))
        assertEquals(before, dao.pending("owner-a", 10))
        assertEquals(3, dao.countAll())
    }

    @Test
    fun attemptTotalsUseLongAndFirstAttemptIsNotARetry() = runBlocking {
        dao.insert(point("first", "owner-a", 1_000, Int.MAX_VALUE))
        dao.insert(point("second", "owner-a", 2_000, Int.MAX_VALUE))
        dao.insert(point("not-sent", "owner-a", 3_000, 0))

        assertEquals(
            QueueDiagnostics(3, 1_000L, Int.MAX_VALUE.toLong() * 2, 2),
            dao.diagnostics("owner-a"),
        )
    }

    private fun point(id: String, owner: String, createdAtMillis: Long, attempts: Int) = QueuedLocation(
        eventId = id,
        ownerUserId = owner,
        clientDeviceId = "installation",
        recordedAtMillis = createdAtMillis - 100,
        latitude = 10.0,
        longitude = 106.0,
        accuracyMeters = 10.0,
        altitudeMeters = null,
        speedMetersPerSecond = null,
        bearingDegrees = null,
        provider = "fused",
        isMock = false,
        schemaVersion = LOCATION_SCHEMA_VERSION,
        createdAtMillis = createdAtMillis,
        attemptCount = attempts,
        captureGeneration = 1,
    )
}
