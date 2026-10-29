package com.anve.app.ui.history

import com.anve.app.data.api.HistoryRetentionDto
import com.anve.app.data.api.HistoryPageDto
import com.anve.app.data.api.HistoryPointDto
import com.anve.app.data.location.HistoryMember
import com.anve.app.data.location.HistoryDataSource
import java.time.LocalDate
import java.time.ZoneId
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val source = FakeHistory()
    private lateinit var vm: HistoryViewModel
    @Before fun setup() { Dispatchers.setMain(dispatcher); vm = HistoryViewModel(source) }
    @After fun cleanup() { vm.activate(null); Dispatchers.resetMain() }

    @Test fun `local day interval respects daylight saving transition`() {
        val (from, to) = dayInterval(LocalDate.of(2026, 3, 8), ZoneId.of("America/New_York"))
        assertEquals(23 * 3600L, to.epochSecond - from.epochSecond)
    }

    @Test fun `time filter uses local clock on daylight saving transition`() {
        val (from, to) = timeInterval(LocalDate.of(2026, 3, 8), ZoneId.of("America/New_York"), 90, 210)
        assertEquals(3600L, to.epochSecond - from.epochSecond)
        val (fallFrom, fallTo) = timeInterval(LocalDate.of(2026, 11, 1), ZoneId.of("America/New_York"), 60, 120)
        assertEquals(7200L, fallTo.epochSecond - fallFrom.epochSecond)
    }

    @Test fun `selected time range is used for history and deletion`() {
        val date = LocalDate.of(2020, 1, 10)
        vm.activate("a")
        vm.select("a", date)
        vm.selectTimes(480, 600)
        val (from, to) = timeInterval(date, ZoneId.systemDefault(), 480, 600)
        assertEquals(from.toString() to to.toString(), source.lastRange)
        vm.delete(all = false, filtered = true)
        assertEquals(from.toString() to to.toString(), source.lastDeletedRange)
    }

    @Test fun `account switch ignores old response and clears protected points`() {
        val old = CompletableDeferred<HistoryPageDto>()
        source.fetch = { owner -> if (owner == "a") withContext(NonCancellable) { old.await() } else HistoryPageDto(emptyList()) }
        vm.activate("a")
        vm.activate("b")
        old.complete(HistoryPageDto(listOf(point("old", "2026-10-29T00:00:00Z", 10.0))))
        assertEquals("b", vm.state.value.owner)
        assertTrue(vm.state.value.points.isEmpty())
    }

    @Test fun `late refresh cannot replace a newer member selection`() {
        val old = CompletableDeferred<HistoryPageDto>()
        var calls = 0
        source.fetch = {
            calls++
            if (calls == 1) withContext(NonCancellable) { old.await() }
            else HistoryPageDto(listOf(point("new", "2026-10-29T00:00:00Z", 10.0)))
        }
        vm.activate("a")
        vm.select("viewer", LocalDate.now())
        old.complete(HistoryPageDto(listOf(point("old", "2026-10-29T00:00:00Z", 10.0))))
        assertEquals("viewer", vm.state.value.member)
        assertEquals("new", vm.state.value.points.single().eventId)
    }

    @Test fun `line segments break at gap low accuracy and deletion boundary`() {
        val p1 = point("1", "2026-10-29T00:00:00Z", 10.0)
        val p2 = point("2", "2026-10-29T00:01:00Z", 10.0001)
        val gap = point("3", "2026-10-29T01:00:00Z", 10.0002)
        val deleted = point("4", "2026-10-29T01:01:00Z", 10.0003).copy(segmentId = "after")
        val noisy = point("5", "2026-10-29T01:02:00Z", 10.0004).copy(horizontalAccuracyMeters = 300.0)
        assertEquals(listOf(2, 1, 1), historySegments(listOf(p1, p2, gap, deleted, noisy)).map { it.size })
    }

    @Test fun `revoked viewer access clears the displayed points`() {
        source.fetch = { HistoryPageDto(listOf(point("1", "2026-10-29T00:00:00Z", 10.0))) }
        vm.activate("a")
        vm.select("viewer", LocalDate.now())
        assertEquals(1, vm.state.value.points.size)
        vm.recheckAccess("a", "viewer")
        assertTrue(vm.state.value.points.isEmpty())
        assertTrue(vm.state.value.error.orEmpty().contains("không còn"))
    }

    @Test fun `timeline pages stay bounded and request the server cursor`() {
        val first = point("first", "2026-10-29T00:00:00Z", 10.0)
        val second = point("second", "2026-10-29T00:01:00Z", 10.0)
        source.fetchPage = { _, cursor ->
            if (cursor == null) HistoryPageDto(listOf(first), "next") else HistoryPageDto(listOf(second))
        }
        vm.activate("a")
        assertEquals("first", vm.state.value.points.single().eventId)
        vm.next()
        assertEquals("second", vm.state.value.points.single().eventId)
        assertEquals(null, vm.state.value.nextCursor)
    }

    @Test fun `failed delete never claims success or retries`() {
        source.failDelete = true
        vm.activate("a")
        vm.delete(all = true)
        assertEquals(1, source.deleteCalls)
        assertTrue(vm.state.value.error.orEmpty().contains("Chưa xác nhận"))
        assertTrue(vm.state.value.points.isEmpty())
        vm.refresh()
        assertEquals(1, source.deleteCalls)
    }

    @Test fun `read failure clears previously visible history`() {
        source.fetch = { HistoryPageDto(listOf(point("1", "2026-10-29T00:00:00Z", 10.0))) }
        vm.activate("a")
        assertEquals(1, vm.state.value.points.size)
        source.fetch = { throw IllegalStateException("denied") }
        vm.refresh()
        assertTrue(vm.state.value.points.isEmpty())
        assertTrue(vm.state.value.error != null)
    }

    private fun point(id: String, timestamp: String, lat: Double) = HistoryPointDto(id, lat, 106.0, 10.0, timestamp, timestamp, "fused", false, false, "epoch")
    private class FakeHistory : HistoryDataSource {
        var failDelete = false
        var deleteCalls = 0
        var lastRange: Pair<String, String>? = null
        var lastDeletedRange: Pair<String?, String>? = null
        var fetch: suspend (String) -> HistoryPageDto = { HistoryPageDto(emptyList()) }
        var fetchPage: suspend (String, String?) -> HistoryPageDto = { owner, _ -> fetch(owner) }
        override suspend fun members(owner: String): List<HistoryMember> = emptyList()
        override suspend fun page(owner: String, member: String, from: String, to: String, cursor: String?): HistoryPageDto {
            lastRange = from to to
            return fetchPage(owner, cursor)
        }
        override suspend fun retention(owner: String) = HistoryRetentionDto(30, 30)
        override suspend fun setRetention(owner: String, days: Int) = HistoryRetentionDto(days, days)
        override suspend fun delete(owner: String, from: String?, to: String) {
            deleteCalls++
            lastDeletedRange = from to to
            if (failDelete) throw IllegalStateException("unknown")
        }
    }
}
