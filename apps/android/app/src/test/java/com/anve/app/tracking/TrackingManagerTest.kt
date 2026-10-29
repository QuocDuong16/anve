package com.anve.app.tracking

import com.anve.app.data.auth.InMemorySessionStorage
import com.anve.app.data.auth.ConsentMutationCoordinator
import com.anve.app.data.auth.PendingConsentOperation
import com.anve.app.data.auth.SessionStorage
import com.anve.app.data.auth.StoredSession
import com.anve.app.data.location.FakeLocationQueueDao
import com.anve.app.data.location.LocationQueueRepository
import com.anve.app.data.location.CaptureGenerationStore
import com.anve.app.ui.awaitUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
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
    private lateinit var managerSessions: SessionStorage
    private lateinit var dao: FakeLocationQueueDao
    private lateinit var queue: LocationQueueRepository
    private lateinit var checkpoints: CaptureGenerationStore
    private var fetchCheckpoint: suspend (String) -> Long = { 1L }
    private lateinit var store: FakeTrackingStore
    private lateinit var permissions: FakePermissions
    private lateinit var provider: FakeLocationProvider
    private lateinit var connectivity: FakeConnectivity
    private lateinit var service: FakeServiceController
    private lateinit var scheduler: FakeSyncScheduler
    private lateinit var gateway: FakeServerGateway
    private lateinit var manager: TrackingManager
    private lateinit var consentMutations: ConsentMutationCoordinator
    private var now = 2_000_000L
    private var elapsed = 1_000_000L
    private var saver = false
    private var failConsentOperationWrite = false
    private var consentWriteEntered: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private var releaseConsentWrite: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    @Before
    fun setUp() = runBlocking {
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        sessions = InMemorySessionStorage(now = { now })
        sessions.save(session("u1"))
        failConsentOperationWrite = false
        consentWriteEntered = null
        releaseConsentWrite = null
        managerSessions = object : SessionStorage by sessions {
            override suspend fun savePendingConsentOperation(operation: PendingConsentOperation) {
                consentWriteEntered?.complete(Unit)
                releaseConsentWrite?.await()
                if (failConsentOperationWrite) throw java.io.IOException("simulated storage failure")
                sessions.savePendingConsentOperation(operation)
            }
        }
        dao = FakeLocationQueueDao()
        checkpoints = CaptureGenerationStore { (elapsed - 2_000) * 1_000_000 }
        fetchCheckpoint = { 1L }
        queue = LocationQueueRepository(dao, now = { now }, captureGeneration = checkpoints::current)
        store = FakeTrackingStore(
            LocalTrackingState(localConsentConfirmed = true, consentOwnerUserId = "u1"),
        )
        permissions = FakePermissions()
        provider = FakeLocationProvider()
        connectivity = FakeConnectivity()
        service = FakeServiceController()
        scheduler = FakeSyncScheduler()
        gateway = FakeServerGateway(consent = true, hasCircle = true, store = store)
        consentMutations = ConsentMutationCoordinator()
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
        sessions = managerSessions,
        deviceIdentity = FakeDeviceIdentity(),
        queue = queue,
        store = trackingStore,
        permissions = permissions,
        provider = provider,
        connectivity = connectivity,
        service = service,
        syncScheduler = scheduler,
        scope = scope,
        captureGenerations = checkpoints,
        fetchCaptureGeneration = { fetchCheckpoint(it) },
        consentMutations = consentMutations,
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
    fun `concurrent start requests launch only one foreground service`() = runBlocking {
        manager.startTracking()
        manager.startTracking()

        awaitUntil { service.starts == 1 }
        assertEquals(1, service.starts)
        assertTrue(manager.status.value.trackingRequested)
        assertEquals(null, manager.status.value.lastError)
    }

    @Test
    fun `stale start response cannot restore consent or restart after local OFF`() = runBlocking {
        sessions.save(session("u1").copy(locationSharingEnabled = true, locationSharingVersion = 4))
        gateway.consent = true
        gateway.consentVersion = 4
        val readStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val finishRead = kotlinx.coroutines.CompletableDeferred<Unit>()
        gateway.beforeCurrentUser = {
            readStarted.complete(Unit)
            finishRead.await()
        }

        manager.startTracking()
        readStarted.await()
        val result = manager.setConsent(false)
        finishRead.complete(Unit)

        awaitUntil { manager.status.value.lastError == TrackingProblem.CONSENT_REQUIRED }
        assertTrue(result.serverConfirmed)
        assertFalse(gateway.consent)
        assertEquals(5L, gateway.consentVersion)
        assertFalse(store.current().localConsentConfirmed)
        assertFalse(manager.status.value.consentEnabled)
        assertEquals(0, service.starts)
        assertEquals(0, provider.activeCollectors)
    }

    @Test
    fun `fresh tracking start queues the first fix with a verified checkpoint`() = runBlocking {
        startAndConfirm()
        emitFix()
        awaitUntil { dao.rows.size == 1 }
        assertEquals(1L, dao.rows.getValue("event-1").captureGeneration)
    }

    @Test
    fun `fresh offline process cannot start without a checkpoint`() = runBlocking {
        connectivity.updateOnline(false)
        manager.startTracking()
        awaitUntil { manager.status.value.lastError == TrackingProblem.CHECKPOINT_UNAVAILABLE }
        assertEquals(0, service.starts)
        assertFalse(manager.status.value.activelyTracking)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `stop cancels a start before its coroutine runs`() = runTest {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        manager = newManager(store)
        testScheduler.runCurrent()
        var calls = 0
        fetchCheckpoint = { calls++; 1L }
        manager.startTracking()
        manager.stopTracking()
        testScheduler.runCurrent()
        assertEquals(0, calls)
        assertEquals(0, service.starts)
    }

    @Test
    fun `disconnect after verified start retains offline checkpoint`() = runBlocking {
        startAndConfirm()
        connectivity.updateOnline(false)
        emitFix()
        awaitUntil { dao.rows.size == 1 }
        assertEquals(1L, dao.rows.getValue("event-1").captureGeneration)
        assertTrue(manager.status.value.activelyTracking)
    }

    @Test
    fun `delayed checkpoint is single flight and old callback is not relabeled`() = runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Long>()
        var calls = 0
        fetchCheckpoint = { calls++; entered.complete(Unit); release.await() }
        manager.startTracking()
        entered.await()
        manager.startTracking()
        assertEquals(0, service.starts)
        release.complete(1)
        awaitUntil { service.starts == 1 }
        assertEquals(1, calls)
        manager.onServiceStarted()
        awaitUntil { provider.activeCollectors == 1 }
        provider.emit(testFix(eventId = "old", recordedAtMillis = now - 2_000,
            elapsedRealtimeNanos = (elapsed - 3_000) * 1_000_000))
        awaitUntil { manager.status.value.lastError == TrackingProblem.CHECKPOINT_UNAVAILABLE }
        assertTrue(dao.rows.isEmpty())
        emitFix()
        awaitUntil { dao.rows.size == 1 }
        assertEquals(1L, dao.rows.getValue("event-1").captureGeneration)
    }

    @Test
    fun `consent off during checkpoint fetch prevents service start`() = runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Long>()
        fetchCheckpoint = { entered.complete(Unit); release.await() }
        manager.startTracking()
        entered.await()
        manager.setConsent(false)
        release.complete(1)
        yield()
        assertEquals(0, service.starts)
        assertFalse(checkpoints.has("u1"))
    }

    @Test
    fun `account switch during checkpoint fetch ignores old response`() = runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Long>()
        fetchCheckpoint = { entered.complete(Unit); release.await() }
        manager.startTracking()
        entered.await()
        sessions.save(session("u2"))
        awaitUntil { !manager.status.value.consentEnabled }
        release.complete(1)
        yield()
        assertEquals(0, service.starts)
        assertFalse(checkpoints.has("u1"))
        assertFalse(checkpoints.has("u2"))
    }

    @Test
    fun `session replacement during checkpoint fetch cannot start tracking`() = runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Long>()
        fetchCheckpoint = { entered.complete(Unit); release.await() }
        manager.startTracking()
        entered.await()
        sessions.save(session("u1").copy(accessToken = "replacement"))
        release.complete(1)
        awaitUntil { manager.status.value.lastError == TrackingProblem.SESSION_UNAVAILABLE }
        assertEquals(0, service.starts)
        assertFalse(checkpoints.has("u1"))
    }

    @Test
    fun `advanced checkpoint never relabels an older callback`() = runBlocking {
        startAndConfirm()
        awaitUntil { provider.activeCollectors == 1 }
        elapsed += 10_000
        checkpoints.advance("u1", 2)
        provider.emit(testFix(eventId = "old", recordedAtMillis = now - 2_000,
            elapsedRealtimeNanos = (elapsed - 3_000) * 1_000_000))
        awaitUntil { manager.status.value.lastError == TrackingProblem.CHECKPOINT_UNAVAILABLE }
        assertTrue(dao.rows.isEmpty())
        emitFix()
        awaitUntil { dao.rows.size == 1 }
        assertEquals(2L, dao.rows.getValue("event-1").captureGeneration)
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
    fun `durable OFF remains closed after tracking manager recreation`() = runBlocking {
        gateway.failConsentWith = networkError()
        val result = manager.setConsent(false)
        assertFalse(result.serverConfirmed)
        assertEquals(false, sessions.pendingConsentOperations().single().desiredEnabled)

        consentMutations = ConsentMutationCoordinator()
        manager = newManager(store)
        manager.startTracking()
        awaitUntil { manager.status.value.lastError == TrackingProblem.CONSENT_REQUIRED }

        assertEquals("process restart cannot revive the stale sharing state", 0, service.starts)
        assertFalse(manager.status.value.activelyTracking)
    }

    @Test
    fun `stale version conflict cannot replace a newer consent operation`() = runBlocking {
        sessions.save(session("u1").copy(locationSharingEnabled = true, locationSharingVersion = 1))
        gateway.consent = true
        gateway.consentVersion = 2
        val userReadEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseUserRead = kotlinx.coroutines.CompletableDeferred<Unit>()
        gateway.beforeCurrentUser = {
            userReadEntered.complete(Unit)
            releaseUserRead.await()
        }

        val staleOff = async { manager.setConsent(false) }
        userReadEntered.await()

        // This models a newer server snapshot observed by the current owner
        // while the old operation is reconciling its CAS conflict.
        sessions.updateConsent("u1", enabled = true, version = 2)
        val latestOn = manager.setConsent(true)

        assertTrue(latestOn.serverConfirmed)
        assertTrue(gateway.consent)
        assertEquals(3L, gateway.consentVersion)
        releaseUserRead.complete(Unit)

        assertFalse(staleOff.await().serverConfirmed)
        assertTrue("the stale OFF must not be retried", gateway.consent)
        assertEquals(3L, gateway.consentVersion)
        assertEquals(listOf(true), gateway.consentCalls)
        assertTrue(sessions.pendingConsentOperations().isEmpty())
    }

    @Test
    fun `stale ON does not override a newer server OFF`() = runBlocking {
        sessions.save(session("u1").copy(locationSharingEnabled = false, locationSharingVersion = 4))
        store.setLocalConsentConfirmed(false)
        gateway.consent = false
        gateway.consentVersion = 5

        val result = manager.setConsent(true)

        assertFalse(result.serverConfirmed)
        assertFalse(gateway.consent)
        assertEquals(5L, gateway.consentVersion)
        assertTrue(gateway.consentCalls.isEmpty())
        val conflict = sessions.pendingConsentOperations().single()
        assertTrue(conflict.desiredEnabled)
        assertEquals(4L, conflict.expectedVersion)
        assertEquals("CONSENT_VERSION_CONFLICT", conflict.lastErrorCode)
        assertFalse(manager.status.value.consentEnabled)
        assertTrue(manager.status.value.consentConflict)
        assertTrue(consentMutations.isLocationAdmissionClosed("u1"))

        val staleOperationId = conflict.operationId
        val explicitRetry = manager.setConsent(true)
        val committed = gateway.consentOperationIds.single()
        assertTrue(explicitRetry.serverConfirmed)
        assertTrue(gateway.consent)
        assertEquals(6L, gateway.consentVersion)
        assertTrue("fresh user intent gets a new operation id", staleOperationId != committed)
    }

    @Test
    fun `stale OFF stays local OFF and does not override a newer server ON`() = runBlocking {
        sessions.save(session("u1").copy(locationSharingEnabled = true, locationSharingVersion = 4))
        gateway.consent = true
        gateway.consentVersion = 5

        val result = manager.setConsent(false)

        assertFalse(result.serverConfirmed)
        assertTrue("the local device stays stopped", !manager.status.value.activelyTracking)
        assertTrue(gateway.consent)
        assertEquals(5L, gateway.consentVersion)
        assertTrue(gateway.consentCalls.isEmpty())
        val conflict = sessions.pendingConsentOperations().single()
        assertFalse(conflict.desiredEnabled)
        assertEquals(4L, conflict.expectedVersion)
        assertEquals("CONSENT_VERSION_CONFLICT", conflict.lastErrorCode)
        assertTrue(manager.status.value.consentConflict)
        assertEquals(true, manager.status.value.conflictServerConsent)

        val staleOperationId = conflict.operationId
        val explicitOff = manager.setConsent(false)
        val committed = gateway.consentOperationIds.single()
        assertTrue(explicitOff.serverConfirmed)
        assertFalse(gateway.consent)
        assertEquals(6L, gateway.consentVersion)
        assertTrue("fresh user intent gets a new operation id", staleOperationId != committed)
    }

    @Test
    fun `CAS conflict whose server state matches reconciles without another write`() = runBlocking {
        sessions.save(session("u1").copy(locationSharingEnabled = false, locationSharingVersion = 4))
        gateway.consent = false
        gateway.consentVersion = 5

        val result = manager.setConsent(false)

        assertTrue(result.serverConfirmed)
        assertFalse(result.conflict)
        assertFalse(gateway.consent)
        assertEquals(5L, gateway.consentVersion)
        assertTrue(gateway.consentCalls.isEmpty())
        assertTrue(sessions.pendingConsentOperations().isEmpty())
    }

    @Test
    fun `storage failure while turning consent OFF still closes tracking locally`() = runBlocking {
        startAndConfirm()
        awaitUntil { provider.activeCollectors == 1 }
        emitFix(eventId = "purged-on-off")
        awaitUntil { dao.rows.size == 1 }
        failConsentOperationWrite = true

        val result = manager.setConsent(false)

        assertFalse(result.serverConfirmed)
        assertFalse(result.pendingOperationPersisted)
        assertTrue(result.message.orEmpty().contains("Không lưu được"))
        assertTrue(service.stops >= 1)
        assertEquals(0, provider.activeCollectors)
        assertTrue(dao.rows.isEmpty())
        assertFalse(manager.status.value.trackingRequested)
        assertEquals(TrackingProblem.CONSENT_STORAGE_UNAVAILABLE, manager.status.value.lastError)
        assertTrue("the server must not be told the operation is durable", gateway.consentCalls.isEmpty())
    }

    @Test
    fun `refresh after OFF storage failure keeps server consent separate from local admission`() = runBlocking {
        assertTrue(manager.setConsent(true).serverConfirmed)
        failConsentOperationWrite = true
        store.failConsentMirrorWrites = true

        val result = manager.setConsent(false)

        assertFalse(result.serverConfirmed)
        assertFalse(result.pendingOperationPersisted)
        val previousReads = gateway.currentUserCalls
        manager.refreshStatus()
        awaitUntil {
            gateway.currentUserCalls > previousReads && manager.status.value.serverConsentEnabled == true
        }

        assertFalse("the local consent control must reflect the closed privacy gate", manager.status.value.consentEnabled)
        assertFalse(manager.status.value.locationAdmissionOpen)
        assertFalse(manager.status.value.activelyTracking)
        assertTrue("the server snapshot may still be ON while local admission stays closed",
            manager.status.value.serverConsentEnabled == true)
    }

    @Test
    fun `A offline OFF remains isolated while B reconciles its own server consent`() = runBlocking {
        gateway.failConsentWith = networkError()
        val off = manager.setConsent(false)
        assertFalse(off.serverConfirmed)
        assertTrue(sessions.pendingConsentOperations().any { it.ownerUserId == "u1" && !it.desiredEnabled })
        assertTrue(consentMutations.isLocationAdmissionClosed("u1"))

        sessions.save(session("u2"))
        awaitUntil { dao.rows.isEmpty() && manager.status.value.sessionReady }
        assertTrue(consentMutations.isLocationAdmissionClosed("u2"))
        assertTrue(sessions.pendingConsentOperations().any { it.ownerUserId == "u1" && !it.desiredEnabled })

        gateway.ownerUserId = "u2"
        gateway.consent = true
        gateway.consentVersion = 8
        val previousReads = gateway.currentUserCalls
        manager.refreshStatus()
        awaitUntil {
            gateway.currentUserCalls > previousReads && manager.status.value.serverConsentEnabled == true &&
                manager.status.value.locationAdmissionOpen
        }

        assertFalse(consentMutations.isLocationAdmissionClosed("u2"))
        assertTrue(consentMutations.isLocationAdmissionClosed("u1"))
        assertTrue(sessions.pendingConsentOperations().any { it.ownerUserId == "u1" && !it.desiredEnabled })
        assertTrue("A's points were purged at the owner transition", dao.rows.isEmpty())
    }

    @Test
    fun `queue purge failure keeps acquisition and upload admission closed`() = runBlocking {
        startAndConfirm()
        awaitUntil { provider.activeCollectors == 1 }
        emitFix(eventId = "queue-purge-fails")
        awaitUntil { dao.rows.size == 1 }
        dao.failDeleteAll = true

        val result = manager.setConsent(false)

        assertTrue(result.serverConfirmed)
        assertTrue(result.message.orEmpty().contains("hàng đợi vị trí"))
        assertEquals(1, dao.rows.size)
        assertEquals(0, provider.activeCollectors)
        assertTrue(service.stops >= 1)
        assertTrue(consentMutations.isLocationAdmissionClosed("u1"))
        assertFalse(consentMutations.authorizeLocationUpload("u1") { false })
    }

    @Test
    fun `failed service stop is reported while provider and upload gates stay closed`() = runBlocking {
        startAndConfirm()
        awaitUntil { provider.activeCollectors == 1 }
        service.failStop = true

        val result = manager.setConsent(false)

        assertTrue(result.serverConfirmed)
        assertTrue(result.message.orEmpty().contains("không xác nhận được việc dừng dịch vụ"))
        assertEquals(0, provider.activeCollectors)
        assertFalse(manager.status.value.activelyTracking)
        assertTrue(manager.status.value.serviceRunning)
        assertEquals(TrackingProblem.SERVICE_STOP_FAILED, manager.status.value.lastError)
        assertTrue(consentMutations.isLocationAdmissionClosed("u1"))
    }

    @Test
    fun `cancellation during OFF persistence still completes local shutdown and propagates`() = runBlocking {
        startAndConfirm()
        awaitUntil { provider.activeCollectors == 1 }
        emitFix(eventId = "cancelled-off")
        awaitUntil { dao.rows.size == 1 }
        val writeEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val finishWrite = kotlinx.coroutines.CompletableDeferred<Unit>()
        consentWriteEntered = writeEntered
        releaseConsentWrite = finishWrite

        val revoke = async { manager.setConsent(false) }
        writeEntered.await()
        revoke.cancel()
        finishWrite.complete(Unit)
        revoke.join()

        assertTrue(revoke.isCancelled)
        assertTrue(service.stops >= 1)
        assertEquals(0, provider.activeCollectors)
        assertTrue(dao.rows.isEmpty())
        assertEquals(false, sessions.pendingConsentOperations().single().desiredEnabled)
        assertTrue(consentMutations.isLocationAdmissionClosed("u1"))
        assertTrue(gateway.consentCalls.isEmpty())
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
    fun `consent OFF waits for an admitted fix then purges it before completion`() = runBlocking {
        startAndConfirm()
        awaitUntil { provider.activeCollectors == 1 }
        val mutationEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseMutation = kotlinx.coroutines.CompletableDeferred<Unit>()
        val lockHolder = async {
            consentMutations.withLocalMutation {
                mutationEntered.complete(Unit)
                releaseMutation.await()
            }
        }
        mutationEntered.await()

        val fix = async { emitFix(eventId = "fix-during-revoke") }
        yield()
        val revoke = async { manager.setConsent(false) }
        yield()
        releaseMutation.complete(Unit)

        lockHolder.await()
        fix.await()
        val result = revoke.await()

        assertTrue(result.serverConfirmed)
        assertTrue(dao.rows.isEmpty())
        assertFalse(manager.status.value.running)
        assertTrue(sessions.pendingConsentOperations().isEmpty())
    }

    @Test
    fun `consent OFF intent survives session clear while its local mutation is queued`() = runBlocking {
        startAndConfirm()
        awaitUntil { provider.activeCollectors == 1 }
        val mutationEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseMutation = kotlinx.coroutines.CompletableDeferred<Unit>()
        val lockHolder = async {
            consentMutations.withLocalMutation {
                mutationEntered.complete(Unit)
                releaseMutation.await()
            }
        }
        mutationEntered.await()

        val revoke = async { manager.setConsent(false) }
        yield()
        sessions.clear()
        releaseMutation.complete(Unit)
        lockHolder.await()

        val result = revoke.await()
        val pending = sessions.pendingConsentOperations().single()
        assertFalse(result.serverConfirmed)
        assertEquals("u1", pending.ownerUserId)
        assertFalse(pending.desiredEnabled)
        assertTrue(pending.requiresOwnerAuthentication)
        assertEquals("OWNER_AUTH_REQUIRED", pending.lastErrorCode)
        assertTrue(dao.rows.isEmpty())
        assertFalse(manager.status.value.running)
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
        checkpoints.advance("u1", 1)
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
        checkpoints.advance("u1", 1)
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
