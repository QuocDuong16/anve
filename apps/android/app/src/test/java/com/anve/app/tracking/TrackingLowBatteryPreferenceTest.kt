package com.anve.app.tracking

import com.anve.app.data.auth.ConsentMutationCoordinator
import com.anve.app.data.auth.InMemorySessionStorage
import com.anve.app.data.auth.StoredSession
import com.anve.app.data.location.CaptureGenerationStore
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackingLowBatteryPreferenceTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var scope: CoroutineScope
    private lateinit var sessions: InMemorySessionStorage
    private lateinit var store: FakeTrackingStore
    private lateinit var gateway: FakeServerGateway
    private lateinit var connectivity: FakeConnectivity
    private lateinit var provider: FakeLocationProvider
    private lateinit var service: FakeServiceController
    private lateinit var manager: TrackingManager
    private var now = 5_000_000L
    private var elapsed = 2_000_000L

    @Before
    fun setUp() = runBlocking {
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        sessions = InMemorySessionStorage(now = { now })
        sessions.save(StoredSession("access", "refresh", "later", "later", userId = "u1"))
        store = FakeTrackingStore(
            LocalTrackingState(localConsentConfirmed = true, consentOwnerUserId = "u1"),
        )
        gateway = FakeServerGateway(
            consent = true,
            lowBatteryAlertsEnabled = false,
            lowBatteryAlertsVersion = 0,
            hasCircle = true,
            store = store,
        )
        connectivity = FakeConnectivity(online = true)
        provider = FakeLocationProvider()
        service = FakeServiceController()
        val checkpoints = CaptureGenerationStore { (elapsed - 2_000) * 1_000_000 }
        val queue = LocationQueueRepository(
            FakeLocationQueueDao(),
            now = { now },
            captureGeneration = checkpoints::current,
        )
        manager = TrackingManager(
            server = gateway,
            sessions = sessions,
            deviceIdentity = FakeDeviceIdentity(),
            queue = queue,
            store = store,
            permissions = FakePermissions(),
            provider = provider,
            connectivity = connectivity,
            service = service,
            syncScheduler = FakeSyncScheduler(),
            scope = scope,
            captureGenerations = checkpoints,
            fetchCaptureGeneration = { 1L },
            consentMutations = ConsentMutationCoordinator(),
            now = { now },
            elapsedNow = { elapsed },
        )
        awaitUntil { manager.status.value.sessionReady && manager.status.value.consentEnabled }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `low battery preference defaults off, updates via CAS, and handles 409 conflict without silent overwrite`() =
        runBlocking {
            assertFalse(manager.status.value.lowBatteryAlertsEnabled)
            assertEquals(0L, manager.status.value.lowBatteryAlertsVersion)

            val enabled = manager.setLowBatteryAlerts(true)
            assertTrue(enabled.serverConfirmed)
            assertTrue(enabled.enabled)
            assertFalse(enabled.conflict)
            assertEquals(listOf(true to 0L), gateway.lowBatteryCalls)
            assertTrue(manager.status.value.lowBatteryAlertsEnabled)
            assertEquals(1L, manager.status.value.lowBatteryAlertsVersion)
            assertTrue(store.current().lowBatteryAlertsEnabled)
            assertEquals(1L, store.current().lowBatteryAlertsVersion)

            // Another device updates server preference to OFF at version 2.
            gateway.lowBatteryAlertsEnabled = false
            gateway.lowBatteryAlertsVersion = 2L
            val conflict = manager.setLowBatteryAlerts(true)
            assertFalse(conflict.serverConfirmed)
            assertTrue(conflict.conflict)
            assertFalse(conflict.enabled)
            // Authoritative server state is re-read and mirrored without silent overwrite.
            assertFalse(manager.status.value.lowBatteryAlertsEnabled)
            assertEquals(2L, manager.status.value.lowBatteryAlertsVersion)
            assertFalse(gateway.lowBatteryAlertsEnabled)
            assertEquals(2L, gateway.lowBatteryAlertsVersion)
        }

    @Test
    fun `switching account clears cached low battery state and external fresh fix updates accepted fix`() =
        runBlocking {
            val enabled = manager.setLowBatteryAlerts(true)
            assertTrue(enabled.serverConfirmed)
            assertTrue(manager.status.value.lowBatteryAlertsEnabled)

            manager.startTracking()
            awaitUntil { service.starts == 1 }
            manager.onServiceStarted()
            awaitUntil { manager.status.value.activelyTracking }

            val fresh = testFix(
                eventId = "fresh-bat",
                recordedAtMillis = now - 500,
                elapsedRealtimeNanos = (elapsed - 500) * 1_000_000,
            )
            manager.submitExternalFreshFix("u1", fresh)
            awaitUntil { manager.latestAcceptedFix()?.eventId == "fresh-bat" }
            assertNotNull(manager.latestAcceptedFix())

            // Switch account -> clears accepted fix & previous owner's low-battery preference.
            gateway.ownerUserId = "u2"
            gateway.lowBatteryAlertsEnabled = false
            gateway.lowBatteryAlertsVersion = 0L
            sessions.save(StoredSession("access-2", "refresh-2", "later", "later", userId = "u2"))
            awaitUntil { manager.status.value.ownerUserId == "u2" }
            assertNull(manager.latestAcceptedFix())
            assertFalse(manager.status.value.lowBatteryAlertsEnabled)
            assertEquals(0L, manager.status.value.lowBatteryAlertsVersion)
        }
}
