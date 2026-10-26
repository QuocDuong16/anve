package com.anve.app.tracking

import com.anve.app.data.auth.InMemorySessionStorage
import com.anve.app.data.auth.StoredSession
import com.anve.app.data.location.FakeLocationQueueDao
import com.anve.app.data.location.LocationQueueRepository
import com.anve.app.ui.awaitUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The tracking coordinator against fake OS/server dependencies: the tests
 * pin the separation between consent, service state and provider state,
 * plus the privacy ordering of an offline revocation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackingManagerTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var scope: CoroutineScope
    private lateinit var sessions: InMemorySessionStorage
    private lateinit var dao: FakeLocationQueueDao
    private lateinit var queue: LocationQueueRepository
    private lateinit var store: FakeTrackingStore
    private lateinit var permissions: FakePermissions
    private lateinit var provider: FakeLocationProvider
    private lateinit var connectivity: FakeConnectivity
    private lateinit var service: FakeServiceController
    private lateinit var scheduler: FakeSyncScheduler
    private lateinit var gateway: FakeServerGateway
    private lateinit var manager: TrackingManager
    private var now = 2_000_000L
    private var elapsed = 1_000_000L
    private var saver = false

    @Before
    fun setUp() = runBlocking {
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        sessions = InMemorySessionStorage()
        sessions.save(session("u1"))
        dao = FakeLocationQueueDao()
        queue = LocationQueueRepository(dao, now = { now })
        store = FakeTrackingStore(
            LocalTrackingState(localConsentConfirmed = true, consentOwnerUserId = "u1"),
        )
        permissions = FakePermissions()
        provider = FakeLocationProvider()
        connectivity = FakeConnectivity()
        service = FakeServiceController()
        scheduler = FakeSyncScheduler()
        gateway = FakeServerGateway(consent = true, hasCircle = true, store = store)
        manager = newManager(store)
        awaitUntil { manager.status.value.sessionReady && manager.status.value.consentEnabled }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun session(owner: String?) = StoredSession(
        accessToken = "access",
        refreshToken = "refresh",
        accessExpiresAt = "later",
        refreshExpiresAt = "later",
        userId = owner,
    )

    private fun newManager(trackingStore: TrackingStore) = TrackingManager(
        server = gateway,
        sessions = sessions,
        deviceIdentity = FakeDeviceIdentity(),
        queue = queue,
        store = trackingStore,
        permissions = permissions,
        provider = provider,
        connectivity = connectivity,
        service = service,
        syncScheduler = scheduler,
        scope = scope,
        now = { now },
        elapsedNow = { elapsed },
        batterySaver = { saver },
    )

    private suspend fun startAndConfirm() {
        manager.startTracking()
        awaitUntil { service.starts == 1 }
        manager.onServiceStarted()
        awaitUntil { manager.status.value.providerActive }
    }

    private suspend fun emitFix(
        eventId: String = "event-1",
        latitude: Double = 10.7769,
        speed: Double? = null,
    ) {
        provider.emit(
            testFix(
                eventId = eventId,
                recordedAtMillis = now - 1_000,
                elapsedRealtimeNanos = (elapsed - 1_000) * 1_000_000,
                latitude = latitude,
                speed = speed,
            ),
        )
    }

    @Test
    fun `tracking is not reported active before the service confirms`() = runBlocking {
        manager.startTracking()
        awaitUntil { manager.status.value.trackingRequested }

        assertEquals(1, service.starts)
        assertFalse(manager.status.value.serviceRunning)
        assertFalse("startForegroundService alone is not tracking", manager.status.value.activelyTracking)
        assertFalse(manager.status.value.running)

        manager.onServiceStarted()
        awaitUntil { manager.status.value.activelyTracking }
        assertTrue(manager.status.value.providerActive)
    }

    @Test
    fun `an accepted fix becomes a durable queued point and schedules sync`() = runBlocking {
        startAndConfirm()
        awaitUntil { provider.activeCollectors == 1 }

        emitFix()

        awaitUntil { dao.rows.size == 1 }
        val row = dao.rows.getValue("event-1")
        assertEquals("u1", row.ownerUserId)
        assertEquals("device-owner-0001", row.clientDeviceId)
        assertEquals(now - 1_000, row.recordedAtMillis)
        assertEquals(MotionState.STATIONARY, manager.status.value.motion)
        assertEquals(now - 1_000, manager.status.value.lastFixAtMillis)
        assertEquals(10.0, manager.status.value.lastAccuracyMeters!!, 0.0)
        assertTrue(scheduler.scheduled >= 1)
        awaitUntil { manager.status.value.pendingQueueCount == 1 }
    }

    @Test
    fun `consent OFF stops acquisition, purges the queue and persists intent before the request`() =
        runBlocking {
            startAndConfirm()
            awaitUntil { provider.activeCollectors == 1 }
            emitFix()
            awaitUntil { dao.rows.size == 1 }

            gateway.failConsentWith = networkError()
            val result = manager.setConsent(false)

            assertFalse(result.enabled)
            assertFalse(result.serverConfirmed)
            assertNotNull(result.message)
            assertEquals(
                "the durable intent must exist when the request is attempted",
                true,
                gateway.pendingAtConsentCall,
            )
            assertTrue(store.current().pendingConsentRevocation)
            assertTrue("privacy-first: unsent points are dropped", dao.rows.isEmpty())
            assertTrue(service.stops >= 1)
            assertFalse(manager.status.value.trackingRequested)
            assertFalse(manager.status.value.running)
        }

    @Test
    fun `a confirmed consent OFF clears the pending revocation flag`() = runBlocking {
        val result = manager.setConsent(false)

        assertTrue(result.serverConfirmed)
        assertFalse(store.current().pendingConsentRevocation)
        assertFalse(store.current().localConsentConfirmed)
    }

    @Test
    fun `enabling consent is only reported after the server confirms`() = runBlocking {
        store.setLocalConsentConfirmed(false)
        awaitUntil { !manager.status.value.consentEnabled }

        val result = manager.setConsent(true)

        assertTrue(result.enabled)
        assertTrue(result.serverConfirmed)
        assertTrue(store.current().localConsentConfirmed)
        assertTrue(manager.status.value.consentEnabled)
    }

    @Test
    fun `starting without a location permission fails closed`() = runBlocking {
        permissions.value = permissions.value.copy(location = LocationPermissionState.DENIED)

        manager.startTracking()
        awaitUntil { manager.status.value.lastError == TrackingProblem.PERMISSION_DENIED }

        assertEquals(0, service.starts)
        assertFalse(manager.status.value.running)
    }

    @Test
    fun `starting without consent fails closed`() = runBlocking {
        store.setLocalConsentConfirmed(false)
        awaitUntil { !manager.status.value.consentEnabled }

        manager.startTracking()
        awaitUntil { manager.status.value.lastError == TrackingProblem.CONSENT_REQUIRED }

        assertEquals(0, service.starts)
    }

    @Test
    fun `starting without a circle fails closed when the server is reachable`() = runBlocking {
        gateway.hasCircle = false

        manager.startTracking()
        awaitUntil { manager.status.value.lastError == TrackingProblem.CIRCLE_REQUIRED }

        assertEquals(0, service.starts)
    }

    @Test
    fun `a missing provider is reported instead of starting an empty service`() = runBlocking {
        provider.available = false

        manager.startTracking()
        awaitUntil { manager.status.value.lastError == TrackingProblem.PROVIDER_UNAVAILABLE }

        assertEquals(0, service.starts)
    }

    @Test
    fun `the device location switch being off is reported`() = runBlocking {
        permissions.value = permissions.value.copy(locationServicesEnabled = false)

        manager.startTracking()
        awaitUntil { manager.status.value.lastError == TrackingProblem.LOCATION_DISABLED }

        assertEquals(0, service.starts)
    }

    @Test
    fun `a foreground-service start refusal keeps the intent and asks for a retry`() = runBlocking {
        service.startResult = false

        manager.startTracking()
        awaitUntil { manager.status.value.lastError == TrackingProblem.SERVICE_START_FAILED }

        assertTrue(store.current().trackingRequested)
        assertTrue(manager.status.value.recoveryRequired)
        assertFalse(manager.status.value.running)
    }

    @Test
    fun `permission revocation while running stops the engine`() = runBlocking {
        startAndConfirm()
        awaitUntil { provider.activeCollectors == 1 }

        permissions.value = permissions.value.copy(location = LocationPermissionState.DENIED)
        manager.checkOperationalState()

        awaitUntil { manager.status.value.lastError == TrackingProblem.PERMISSION_REVOKED }
        assertFalse(manager.status.value.running)
        assertTrue(service.stops >= 1)
        assertEquals(0, provider.activeCollectors)
    }

    @Test
    fun `a device clock far in the future is refused, not written`() = runBlocking {
        startAndConfirm()
        awaitUntil { provider.activeCollectors == 1 }

        provider.emit(
            testFix(
                eventId = "future",
                recordedAtMillis = now + 60 * 60 * 1000,
                elapsedRealtimeNanos = (elapsed - 1_000) * 1_000_000,
            ),
        )

        awaitUntil { manager.status.value.lastError == TrackingProblem.CLOCK_SKEW }
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `a provider security error stops instead of pretending to track`() = runBlocking {
        startAndConfirm()
        awaitUntil { provider.activeCollectors == 1 }

        provider.throwOnNextFix = SecurityException("permission revoked")
        emitFix()

        awaitUntil { manager.status.value.lastError == TrackingProblem.PERMISSION_REVOKED }
        assertFalse(manager.status.value.activelyTracking)
    }

    @Test
    fun `turning the device location switch off stops the service`() = runBlocking {
        startAndConfirm()
        awaitUntil { provider.activeCollectors == 1 }

        permissions.value = permissions.value.copy(locationServicesEnabled = false)
        manager.checkOperationalState()

        awaitUntil { manager.status.value.lastError == TrackingProblem.LOCATION_DISABLED }
        assertFalse(manager.status.value.running)
    }

    @Test
    fun `approximate permission degrades but does not stop tracking`() = runBlocking {
        permissions.value = permissions.value.copy(
            location = LocationPermissionState.GRANTED_APPROXIMATE,
        )
        startAndConfirm()
        awaitUntil { provider.activeCollectors == 1 }

        manager.checkOperationalState()

        assertEquals(OperationalState.DEGRADED, manager.status.value.operational)
        assertTrue(manager.status.value.running)
    }

    @Test
    fun `an OS service kill is reported as recovery, not as running`() = runBlocking {
        startAndConfirm()
        awaitUntil { manager.status.value.activelyTracking }

        // Android may destroy the service without the user asking.
        manager.onServiceDestroyed()

        awaitUntil { !manager.status.value.running }
        assertFalse(manager.status.value.serviceRunning)
        assertFalse(manager.status.value.providerActive)
        assertTrue(manager.status.value.recoveryRequired)
        assertEquals(TrackingProblem.RECOVERY_REQUIRED, manager.status.value.lastError)
        assertEquals(0, provider.activeCollectors)
    }

    @Test
    fun `stopping clears the persisted intent and stops the service`() = runBlocking {
        startAndConfirm()
        awaitUntil { manager.status.value.activelyTracking }

        manager.stopTracking()

        awaitUntil { !manager.status.value.running }
        assertFalse(store.current().trackingRequested)
        assertTrue(service.stops >= 1)
        assertEquals(0, provider.activeCollectors)
    }

    @Test
    fun `the notification stop action has the same semantics as the UI stop`() = runBlocking {
        startAndConfirm()
        awaitUntil { manager.status.value.activelyTracking }

        manager.stopFromNotification()

        awaitUntil { !manager.status.value.running }
        assertFalse(store.current().trackingRequested)
    }

    @Test
    fun `a fresh process with persisted intent asks the user to resume`() = runBlocking {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        val recoveringStore = FakeTrackingStore(
            LocalTrackingState(
                trackingRequested = true,
                localConsentConfirmed = true,
                consentOwnerUserId = "u1",
            ),
        )
        manager = newManager(recoveringStore)

        awaitUntil { manager.status.value.recoveryRequired }
        assertFalse(manager.status.value.running)
        assertEquals(0, service.starts)
        assertEquals(TrackingProblem.RECOVERY_REQUIRED, manager.status.value.lastError)
    }

    @Test
    fun `a server policy refusal stops acquisition and clears consent`() = runBlocking {
        startAndConfirm()
        awaitUntil { manager.status.value.activelyTracking }

        manager.onServerPolicyRefusal("LOCATION_CONSENT_REQUIRED")

        awaitUntil { !manager.status.value.running }
        assertFalse(manager.status.value.consentEnabled)
        assertFalse(store.current().localConsentConfirmed)
        assertFalse(manager.status.value.trackingRequested)
        assertEquals(TrackingProblem.CONSENT_REQUIRED, manager.status.value.lastError)
    }

    @Test
    fun `switching accounts purges the previous account's queue and intent`() = runBlocking {
        queue.enqueue("u1", "device-owner-0001", testFix(eventId = "old"))
        awaitUntil { dao.rows.size == 1 }

        sessions.save(session("u2"))

        awaitUntil { dao.rows.isEmpty() }
        assertFalse(store.current().localConsentConfirmed)
        assertFalse(store.current().trackingRequested)
    }

    @Test
    fun `losing the session stops tracking and purges the queue`() = runBlocking {
        startAndConfirm()
        awaitUntil { provider.activeCollectors == 1 }
        emitFix()
        awaitUntil { dao.rows.size == 1 }
        store.setConsentOwner("u1")
        store.setPendingConsentRevocation(true)

        sessions.clear()

        awaitUntil { !manager.status.value.sessionReady }
        assertTrue(dao.rows.isEmpty())
        assertFalse(
            "a revocation intent must not outlive its account",
            store.current().pendingConsentRevocation,
        )
        assertFalse(manager.status.value.running)
        assertTrue(service.stops >= 1)
    }

    @Test
    fun `an offline start uses the last confirmed consent`() = runBlocking {
        connectivity.updateOnline(false)
        gateway.failCurrentUserWith = networkError()

        manager.startTracking()
        awaitUntil { service.starts == 1 }
        manager.onServiceStarted()

        awaitUntil { manager.status.value.operational == OperationalState.OFFLINE }
        assertTrue(manager.status.value.running)
    }

    @Test
    fun `switching to battery saver reduces the request rate without stopping`() = runBlocking {
        startAndConfirm()
        awaitUntil { provider.activeCollectors == 1 }
        emitFix()
        awaitUntil { dao.rows.size == 1 }
        val before = provider.lastOptions

        saver = true
        manager.checkOperationalState()

        awaitUntil { provider.lastOptions?.intervalMillis == before?.intervalMillis?.times(2) }
        assertTrue(manager.status.value.running)
        assertEquals(OperationalState.DEGRADED, manager.status.value.operational)
    }
}
