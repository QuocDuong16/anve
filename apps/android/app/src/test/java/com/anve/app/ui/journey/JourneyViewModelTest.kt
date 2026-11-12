package com.anve.app.ui.journey

import androidx.lifecycle.viewModelScope
import com.anve.app.data.api.ApiException
import com.anve.app.data.api.CreateJourneyDto
import com.anve.app.data.api.JourneyDestinationDto
import com.anve.app.data.api.JourneyDto
import com.anve.app.data.api.PlaceDto
import com.anve.app.data.api.PlaceInputDto
import com.anve.app.data.api.PlaceMonitorDto
import com.anve.app.data.auth.InMemorySessionStorage
import com.anve.app.data.auth.StoredSession
import com.anve.app.data.journey.JourneyAction
import com.anve.app.data.journey.JourneyDataSource
import com.anve.app.data.journey.JourneyOperation
import com.anve.app.data.location.AudienceMember
import com.anve.app.data.location.PlacesDataSource
import com.anve.app.data.location.SharingManagementSnapshot
import com.anve.app.tracking.JourneyTrackingControl
import com.anve.app.tracking.TrackingStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JourneyViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val sessions = InMemorySessionStorage()
    private val repository = Source()
    private val tracking = Tracking()
    private val places = Places()

    @Before fun mainDispatcher() { Dispatchers.setMain(dispatcher) }
    @After fun restoreDispatcher() { Dispatchers.resetMain() }
    private fun session(owner: String) = StoredSession("access-$owner", "refresh-$owner", "later", "later", owner)
    private fun journey(owner: String) = JourneyDto("trip-$owner", owner, owner, "ACTIVE", "2026-11-11T10:00:00Z", "2026-11-11T12:00:00Z", JourneyDestinationDto("Private $owner", 21.0, 105.0))

    @Test fun `old owner response cannot restore private state after account replacement`() = runTest(dispatcher) {
        val old = CompletableDeferred<List<JourneyDto>>()
        sessions.save(session("a"))
        repository.read = { owner -> if (owner == "a") withContext(NonCancellable) { old.await() } else listOf(journey(owner)) }
        val model = JourneyViewModel(sessions, repository, places, tracking)
        model.visible(true)
        try {
            sessions.save(session("b"))
            old.complete(listOf(journey("a")))
            assertEquals("b", model.state.value.owner)
            assertEquals(listOf("b"), model.state.value.journeys.map { it.ownerId })
        } finally { model.visible(false); model.viewModelScope.cancel() }
    }

    @Test fun `leaving the screen clears private state and discards an in flight read`() = runTest(dispatcher) {
        sessions.save(session("a"))
        val reply = CompletableDeferred<List<JourneyDto>>()
        repository.read = { withContext(NonCancellable) { reply.await() } }
        val model = JourneyViewModel(sessions, repository, places, tracking)
        model.visible(true)
        model.visible(false)
        reply.complete(listOf(journey("a")))
        assertTrue(model.state.value.journeys.isEmpty())
        assertTrue(model.state.value.places.isEmpty())
        model.viewModelScope.cancel()
    }

    @Test fun `revoked selected journey is removed rather than retained as an offline snapshot`() = runTest(dispatcher) {
        sessions.save(session("viewer"))
        val trip = journey("owner")
        repository.read = { listOf(trip) }
        val model = JourneyViewModel(sessions, repository, places, tracking, incomingId = trip.id)
        model.visible(true)
        try {
            assertEquals(1, model.state.value.journeys.size)
            repository.read = { emptyList() }
            repository.detail = { _, _ -> throw ApiException("JOURNEY_UNAVAILABLE", "unavailable", null, 404) }
            model.reload()
            assertTrue(model.state.value.journeys.isEmpty())
            assertNull(model.state.value.selectedId)
        } finally { model.visible(false); model.viewModelScope.cancel() }
    }

    @Test fun `late create response from previous owner cannot activate journey priority`() = runTest(dispatcher) {
        sessions.save(session("a"))
        tracking.status.value = TrackingStatus(ownerUserId = "a", running = true, serviceRunning = true, providerActive = true)
        val reply = CompletableDeferred<JourneyDto>()
        repository.createReply = { withContext(NonCancellable) { reply.await() } }
        val model = JourneyViewModel(sessions, repository, places, tracking)
        model.visible(true)
        try {
            model.create(places.place.copy(ownerId = "a", name = "a"), 120, setOf("viewer"))
            sessions.save(session("b"))
            reply.complete(journey("a"))
            assertEquals(0, tracking.enables)
            assertEquals("b", model.state.value.owner)
            assertTrue(model.state.value.journeys.none { it.ownerId == "a" })
        } finally { model.visible(false); model.viewModelScope.cancel() }
    }

    @Test fun `automation choices reach immutable create input and legacy creation remains manual`() = runTest(dispatcher) {
        sessions.save(session("a"))
        tracking.status.value = TrackingStatus(ownerUserId = "a", running = true, serviceRunning = true, providerActive = true, online = true)
        repository.createReply = { journey("a") }
        val model = JourneyViewModel(sessions, repository, places, tracking)
        model.visible(true)
        try {
            model.createWithOptions(places.place, 120, setOf("viewer"), true, 15)
            assertEquals(true, repository.captured?.automaticArrival)
            assertEquals(15, repository.captured?.staleAfterMinutes)
            model.create(places.place, 120, setOf("viewer"))
            assertEquals(false, repository.captured?.automaticArrival)
            assertEquals(0, repository.captured?.staleAfterMinutes)
        } finally { model.visible(false); model.viewModelScope.cancel() }
    }

    private class Source : JourneyDataSource {
        var captured: CreateJourneyDto? = null
        var read: suspend (String) -> List<JourneyDto> = { emptyList() }
        var detail: suspend (String, String) -> JourneyDto = { _, _ -> error("unexpected detail") }
        var createReply: suspend () -> JourneyDto = { error("unexpected create") }
        override suspend fun list(owner: String) = read(owner)
        override suspend fun get(owner: String, id: String) = detail(owner, id)
        override suspend fun pending(owner: String) = emptyList<JourneyOperation>()
        override suspend fun create(owner: String, request: CreateJourneyDto): JourneyDto { captured = request; return createReply() }
        override suspend fun request(owner: String, id: String, action: JourneyAction, recipient: String?) = Unit
        override suspend fun reconcile(owner: String, explicit: Boolean) = false
    }

    private class Tracking : JourneyTrackingControl {
        override val status = MutableStateFlow(TrackingStatus())
        var enables = 0
        override fun journeyAdmissionRevision() = 0L
        override fun enableJourneyPriority(owner: String, id: String, expiresAtMillis: Long, revision: Long): Boolean { enables++; return true }
        override fun stopJourneyPriority(owner: String) = Unit
    }

    private class Places : PlacesDataSource {
        val place = PlaceDto("home", "a", "circle", "a", 21.0, 105.0, 100.0, "now", "now")
        override suspend fun places(owner: String) = listOf(place.copy(ownerId = owner, name = owner))
        override suspend fun members(owner: String) = SharingManagementSnapshot(hasCircle = true, consentEnabled = true, members = listOf(AudienceMember("viewer", "Viewer", "member", true)))
        override suspend fun events(owner: String) = error("unused")
        override suspend fun access(owner: String, place: String) = error("unused")
        override suspend fun monitor(owner: String, place: String): PlaceMonitorDto? = error("unused")
        override suspend fun eligibleRecipients(owner: String, place: String) = error("unused")
        override suspend fun create(owner: String, input: PlaceInputDto) = error("unused")
        override suspend fun update(owner: String, place: String, input: PlaceInputDto) = error("unused")
        override suspend fun delete(owner: String, place: String) = error("unused")
        override suspend fun share(owner: String, place: String, member: String, enabled: Boolean) = error("unused")
        override suspend fun configure(owner: String, place: String, recipients: List<String>) = error("unused")
        override suspend fun stop(owner: String, place: String) = error("unused")
    }
}
