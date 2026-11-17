package com.anve.app.ui.onboarding

import com.anve.app.R
import com.anve.app.data.api.ApiFactory
import com.anve.app.data.api.InviteDto
import com.anve.app.data.api.PlaceDto
import com.anve.app.data.api.PlaceInputDto
import com.anve.app.data.api.PlaceMonitorDto
import com.anve.app.data.api.PrivateInvitationRequestDto
import com.anve.app.data.api.ReceivedInvitationDto
import com.anve.app.data.auth.InMemorySessionStorage
import com.anve.app.data.auth.StoredSession
import com.anve.app.data.circles.CircleRepository
import com.anve.app.data.circles.PrivateInvitationGateway
import com.anve.app.data.location.AudienceMember
import com.anve.app.data.location.AudienceMutationOutcome
import com.anve.app.data.location.PlacesDataSource
import com.anve.app.data.location.SharingManagementDataSource
import com.anve.app.data.location.SharingManagementSnapshot
import com.anve.app.data.onboarding.OnboardingStatus
import com.anve.app.data.onboarding.OnboardingStorage
import com.anve.app.tracking.ConsentResult
import com.anve.app.tracking.LocationPermissionState
import com.anve.app.tracking.PermissionSnapshot
import com.anve.app.tracking.TrackingControl
import com.anve.app.tracking.TrackingPermissions
import com.anve.app.tracking.TrackingStatus
import com.anve.app.ui.awaitUntil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var sessions: InMemorySessionStorage
    private lateinit var onboardingStore: FakeOnboardingStore
    private lateinit var invitationGateway: FakeInvitationGateway
    private lateinit var trackingControl: FakeTrackingControl
    private lateinit var trackingPermissions: FakeTrackingPermissions
    private lateinit var sharingRepo: FakeSharingRepo
    private lateinit var placesRepo: FakePlacesRepo

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        server = MockWebServer()
        server.start()
        sessions = InMemorySessionStorage()
        runBlocking { sessions.save(StoredSession("acc", "ref", "later", "later", "owner-1")) }
        onboardingStore = FakeOnboardingStore()
        invitationGateway = FakeInvitationGateway()
        trackingControl = FakeTrackingControl()
        trackingPermissions = FakeTrackingPermissions()
        sharingRepo = FakeSharingRepo()
        placesRepo = FakePlacesRepo()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun createViewModel(startWithPendingInvite: Boolean = false): OnboardingViewModel {
        val api = ApiFactory.api(ApiFactory.retrofit(server.url("/").toString(), ApiFactory.okHttp { it.proceed(it.request()) }))
        return OnboardingViewModel(
            owner = "owner-1",
            sessions = sessions,
            onboardingStore = onboardingStore,
            circleRepo = CircleRepository(api),
            invitationGateway = invitationGateway,
            trackingControl = trackingControl,
            trackingPermissions = trackingPermissions,
            sharingRepo = sharingRepo,
            placesRepo = placesRepo,
            startWithPendingInvite = startWithPendingInvite,
            externalScope = scope,
        )
    }

    @Test
    fun `welcome skip marks onboarding skipped without network mutations`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"circle":null,"membership":null}"""))
        val vm = createViewModel()
        var exited = false
        vm.skipAllToHome { exited = true }
        assertTrue(exited)
        assertEquals(OnboardingStatus.SKIPPED, onboardingStore.status("owner-1"))
        assertEquals(0, trackingControl.starts)
    }

    @Test
    fun `pending invite starts directly in family join mode`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"circle":null,"membership":null}"""))
        val vm = createViewModel(startWithPendingInvite = true)
        assertEquals(OnboardingStep.FAMILY, vm.state.value.step)
        assertEquals(OnboardingFamilyMode.JOIN, vm.state.value.familyMode)
    }

    @Test
    fun `create circle and 7-day link invite reconciles unknown outcome via readback without POST replay`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"circle":null,"membership":null}"""))
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"circle":{"id":"c1","name":"Nhà mình","createdBy":"owner-1","createdAt":"now"},"membership":{"id":"m1","circleId":"c1","userId":"owner-1","role":"owner","joinedAt":"now"}}""",
            ),
        )

        val vm = createViewModel()
        awaitUntil { !vm.state.value.loadingFamily }
        vm.startGuidedSetup()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"circle":null,"membership":null}"""))
        awaitUntil { !vm.state.value.loadingFamily }
        vm.updateCircleName("Nhà mình")
        vm.createCircle()
        awaitUntil { vm.state.value.circle != null }

        assertEquals("Nhà mình", vm.state.value.circle?.name)
        assertTrue(vm.state.value.isOwner)

        val reply = CompletableDeferred<Unit>()
        invitationGateway.onCreate = { req ->
            reply.await()
            throw IOException("timeout")
        }
        vm.createAndShareInviteLink()
        awaitUntil { invitationGateway.createCalls == 1 }
        reply.complete(Unit)
        awaitUntil { vm.state.value.inviteUnknown }

        assertEquals(R.string.invite_create_unconfirmed, vm.state.value.inviteErrorRes)

        val expectedInvite = InviteDto("req", "c1", "ABCD234567", "owner-1", "2099-01-01T00:00:00Z", "now", false)
        invitationGateway.onCreated = { requestId -> expectedInvite.copy(id = requestId) }
        vm.checkInviteAgain()
        awaitUntil { !vm.state.value.inviteBusy && vm.state.value.createdInvite != null }

        assertFalse(vm.state.value.inviteUnknown)
        assertEquals(1, invitationGateway.createCalls)
        assertEquals("ABCD234567", vm.state.value.createdInvite?.code)
        assertEquals(168, invitationGateway.lastRequest?.expiresInHours)
        assertEquals("LINK", invitationGateway.lastRequest?.mode)
        assertNotNull(vm.state.value.pendingShareInvite)
    }

    @Test
    fun `permissions step keeps consent off by default, never starts tracking, and allows skipping`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"circle":null,"membership":null}"""))
        val vm = createViewModel()
        vm.startGuidedSetup()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"circle":null,"membership":null}"""))
        vm.proceedFromFamily()

        assertEquals(OnboardingStep.PERMISSIONS, vm.state.value.step)
        assertFalse(vm.state.value.consentEnabled)
        assertEquals(0, trackingControl.starts)

        vm.proceedFromPermissions()
        assertEquals(OnboardingStep.AUDIENCE_AND_PLACE, vm.state.value.step)
        assertEquals(0, trackingControl.starts)
    }

    @Test
    fun `step 4 saves place with eligible recipients and step 5 completes onboarding`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"circle":null,"membership":null}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"circle":null,"membership":null}"""))
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"circle":{"id":"c1","name":"Nhà mình","createdBy":"owner-1","createdAt":"now"},"membership":{"id":"m1","circleId":"c1","userId":"owner-1","role":"owner","joinedAt":"now"}}""",
            ),
        )
        sharingRepo.snapshot = SharingManagementSnapshot(
            hasCircle = true,
            circleName = "Nhà mình",
            consentEnabled = true,
            members = listOf(AudienceMember("m2", "Lan", "member", canViewLocation = true)),
        )

        val vm = createViewModel()
        awaitUntil { !vm.state.value.loadingFamily }
        vm.startGuidedSetup()
        awaitUntil { !vm.state.value.loadingFamily }
        vm.updateCircleName("Nhà mình")
        vm.createCircle()
        awaitUntil { vm.state.value.circle != null }
        vm.proceedFromFamily()
        vm.setConsent(true)
        vm.proceedFromPermissions()
        awaitUntil { !vm.state.value.audienceLoading }

        assertEquals(1, vm.state.value.otherMembers.size)
        vm.updatePlaceName("Nhà")
        vm.updatePlaceCoordinates("10.77", "106.69")
        vm.togglePlaceMonitor(true)
        vm.togglePlaceRecipient("m2")
        vm.savePlaceAndContinue()
        awaitUntil { vm.state.value.step == OnboardingStep.COMPLETE }

        assertEquals("Nhà", vm.state.value.createdPlace?.name)
        assertEquals(listOf("m2"), placesRepo.configuredRecipients)

        var done = false
        vm.completeToHome { done = true }
        assertTrue(done)
        assertEquals(OnboardingStatus.COMPLETED, onboardingStore.status("owner-1"))
    }

    private class FakeOnboardingStore : OnboardingStorage {
        private val data = mutableMapOf<String, OnboardingStatus>()
        override fun status(ownerId: String): OnboardingStatus = data[ownerId] ?: OnboardingStatus.NONE
        override fun setStatus(ownerId: String, status: OnboardingStatus) {
            data[ownerId] = status
        }
    }

    private class FakeInvitationGateway : PrivateInvitationGateway {
        var createCalls = 0
        var lastRequest: PrivateInvitationRequestDto? = null
        var onCreate: suspend (PrivateInvitationRequestDto) -> InviteDto = {
            InviteDto(it.requestId, "c1", "ABCD234567", "owner-1", "2099-01-01T00:00:00Z", "now", false)
        }
        var onCreated: suspend (String) -> InviteDto = { throw IOException("missing") }

        override suspend fun create(owner: String, circle: String, request: PrivateInvitationRequestDto): InviteDto {
            createCalls++
            lastRequest = request
            return onCreate(request)
        }

        override suspend fun created(owner: String, circle: String, requestId: String): InviteDto = onCreated(requestId)
        override suspend fun received(owner: String): List<ReceivedInvitationDto> = emptyList()
        override suspend fun detail(owner: String, id: String): ReceivedInvitationDto = error("unused")
        override suspend fun hide(owner: String, id: String) {}
    }

    private class FakeTrackingControl : TrackingControl {
        val statusFlow = MutableStateFlow(TrackingStatus(online = true, consentEnabled = false))
        override val status: StateFlow<TrackingStatus> = statusFlow
        var starts = 0
        override fun startTracking() { starts++ }
        override fun stopTracking() {}
        override fun retrySync() {}
        override fun refreshStatus() {}
        override suspend fun setConsent(enabled: Boolean): ConsentResult {
            statusFlow.value = statusFlow.value.copy(consentEnabled = enabled)
            return ConsentResult(enabled = enabled, serverConfirmed = true)
        }
        override suspend fun recordServerConsent(enabled: Boolean) {
            statusFlow.value = statusFlow.value.copy(consentEnabled = enabled)
        }
    }

    private class FakeTrackingPermissions : TrackingPermissions {
        var current = PermissionSnapshot(LocationPermissionState.DENIED, false, false, true)
        override fun snapshot(): PermissionSnapshot = current
    }

    private class FakeSharingRepo : SharingManagementDataSource {
        var snapshot = SharingManagementSnapshot()
        override suspend fun sharingManagement(expectedOwnerId: String): SharingManagementSnapshot = snapshot
        override suspend fun setViewerGrant(ownerId: String, viewerId: String, enabled: Boolean): AudienceMutationOutcome =
            AudienceMutationOutcome.Confirmed(snapshot)
    }

    private class FakePlacesRepo : PlacesDataSource {
        var configuredRecipients: List<String> = emptyList()
        override suspend fun places(owner: String) = emptyList<PlaceDto>()
        override suspend fun events(owner: String) = emptyList<com.anve.app.data.api.PlaceEventDto>()
        override suspend fun members(owner: String) = SharingManagementSnapshot()
        override suspend fun access(owner: String, place: String) = emptyList<String>()
        override suspend fun eligibleRecipients(owner: String, place: String) = emptyList<String>()
        override suspend fun monitor(owner: String, place: String): PlaceMonitorDto? = null
        override suspend fun create(owner: String, input: PlaceInputDto) =
            PlaceDto("p1", owner, "c1", input.name, input.latitude, input.longitude, input.radiusMeters, "now", "now", input.sharedWithCircle)
        override suspend fun update(owner: String, place: String, input: PlaceInputDto) = error("unused")
        override suspend fun delete(owner: String, place: String) {}
        override suspend fun share(owner: String, place: String, member: String, enabled: Boolean) {}
        override suspend fun configure(owner: String, place: String, recipients: List<String>): PlaceMonitorDto {
            configuredRecipients = recipients
            return PlaceMonitorDto(place, owner, recipients, true)
        }
        override suspend fun stop(owner: String, place: String) {}
    }
}
