package com.anve.app.ui.places

import com.anve.app.data.api.PlaceDto
import com.anve.app.data.api.PlaceEventDto
import com.anve.app.data.api.PlaceInputDto
import com.anve.app.data.api.PlaceMonitorDto
import com.anve.app.data.location.PlacesDataSource
import com.anve.app.data.location.SharingManagementSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlacesViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val source = FakePlaces()
    private lateinit var vm: PlacesViewModel

    @Before fun setup() { Dispatchers.setMain(dispatcher); vm = PlacesViewModel(source) }
    @After fun cleanup() { vm.activate(null); Dispatchers.resetMain() }

    @Test fun `place editor validates coordinates radius and name`() {
        assertTrue(validPlaceInput("Nhà", 10.0, 106.0, 150.0))
        assertFalse(validPlaceInput("  ", 10.0, 106.0, 150.0))
        assertFalse(validPlaceInput("Nhà", Double.NaN, 106.0, 150.0))
        assertFalse(validPlaceInput("Nhà", 10.0, 181.0, 150.0))
        assertFalse(validPlaceInput("Nhà", 10.0, 106.0, 49.0))
        assertFalse(validPlaceInput("Nhà", 10.0, 106.0, 5001.0))
    }

    @Test fun `account switch clears events and ignores old noncancellable response`() {
        val old = CompletableDeferred<List<PlaceDto>>()
        source.list = { owner -> if (owner == "a") withContext(NonCancellable) { old.await() } else emptyList() }
        vm.activate("a")
        vm.activate("b")
        old.complete(listOf(place("old", "a")))
        assertEquals("b", vm.state.value.owner)
        assertTrue(vm.state.value.places.isEmpty())
        assertTrue(vm.state.value.events.isEmpty())
    }

    @Test fun `failed recheck clears previously visible event metadata`() {
        source.eventList = listOf(event())
        vm.activate("a")
        assertEquals(1, vm.state.value.events.size)
        source.failRead = true
        vm.refresh()
        assertTrue(vm.state.value.events.isEmpty())
        assertTrue(vm.state.value.places.isEmpty())
        assertTrue(vm.state.value.error != null)
    }

    @Test fun `unknown monitor result is not replayed and requires explicit action`() {
        source.failMutation = true
        vm.activate("a")
        vm.select("p")
        vm.configure("p", setOf("viewer"))
        assertEquals(1, source.mutations)
        assertTrue(vm.state.value.notice.orEmpty().contains("Chưa xác nhận"))
        vm.refresh()
        assertEquals(1, source.mutations)
    }

    @Test fun `pending mutation is single flight and old result cannot update new account`() {
        val result = CompletableDeferred<Unit>()
        source.mutation = { withContext(NonCancellable) { result.await() } }
        vm.activate("a")
        vm.select("p")
        vm.stop("p")
        vm.stop("p")
        assertEquals(1, source.mutations)
        vm.activate("b")
        result.complete(Unit)
        assertEquals("b", vm.state.value.owner)
        assertEquals(0L, vm.state.value.confirmation)
    }

    @Test fun `editor save is confirmed only after server acknowledgement`() {
        val result = CompletableDeferred<Unit>()
        source.mutation = { result.await() }
        vm.activate("a")
        vm.save(null, PlaceInputDto("Nhà", 10.0, 106.0, 150.0))
        assertTrue(vm.state.value.pending)
        assertEquals(0L, vm.state.value.confirmation)
        result.complete(Unit)
        assertEquals(1L, vm.state.value.confirmation)
        assertFalse(vm.state.value.pending)
    }

    private fun place(id: String, owner: String) = PlaceDto(id, owner, "circle", "Nhà", 10.0, 106.0, 150.0, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")
    private fun event() = PlaceEventDto("e", "p", "Nhà", "a", "A", "ENTER", "2026-01-01T00:00:00Z", "2026-01-01T00:05:00Z", true, "OBSERVED")

    private inner class FakePlaces : PlacesDataSource {
        var list: suspend (String) -> List<PlaceDto> = { owner -> if (owner == "a") listOf(place("p", owner)) else emptyList() }
        var eventList = emptyList<PlaceEventDto>()
        var failRead = false
        var failMutation = false
        var mutations = 0
        var mutation: suspend () -> Unit = {}
        override suspend fun places(owner: String): List<PlaceDto> { if (failRead) error("offline"); return list(owner) }
        override suspend fun events(owner: String) = eventList
        override suspend fun members(owner: String) = SharingManagementSnapshot()
        override suspend fun access(owner: String, place: String) = emptyList<String>()
        override suspend fun eligibleRecipients(owner: String, place: String) = emptyList<String>()
        override suspend fun monitor(owner: String, place: String): PlaceMonitorDto? = null
        override suspend fun create(owner: String, input: PlaceInputDto): PlaceDto { mutate(); return place("new", owner) }
        override suspend fun update(owner: String, place: String, input: PlaceInputDto): PlaceDto { mutate(); return this@PlacesViewModelTest.place(place, owner) }
        override suspend fun delete(owner: String, place: String) = mutate()
        override suspend fun share(owner: String, place: String, member: String, enabled: Boolean) = mutate()
        override suspend fun configure(owner: String, place: String, recipients: List<String>): PlaceMonitorDto { mutate(); return PlaceMonitorDto(place, owner, recipients, true) }
        override suspend fun stop(owner: String, place: String) = mutate()
        private suspend fun mutate() { mutations++; if (failMutation) error("unknown"); mutation() }
    }
}
