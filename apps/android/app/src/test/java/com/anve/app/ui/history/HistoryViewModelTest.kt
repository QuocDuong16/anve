package com.anve.app.ui.history

import com.anve.app.R
import com.anve.app.data.api.ApiException
import com.anve.app.data.api.HistoryPageDto
import com.anve.app.data.api.HistoryPointDto
import com.anve.app.data.api.HistoryRetentionDto
import com.anve.app.data.api.PlaceDto
import com.anve.app.data.api.PlaceEventDto
import com.anve.app.data.api.PlaceInputDto
import com.anve.app.data.api.PlaceMonitorDto
import com.anve.app.data.location.HistoryDataSource
import com.anve.app.data.location.HistoryMember
import com.anve.app.data.location.PlacesDataSource
import com.anve.app.data.location.SharingManagementSnapshot
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertEquals(R.string.history_error_viewer_revoked, vm.state.value.errorRes)
    }

    @Test fun `auto pagination loads full day past 200 nodes and deduplicates`() {
        val base = Instant.parse("2026-10-29T00:00:00Z")
        val page1 = (0 until 200).map { i -> point("p-$i", base.plusSeconds(i * 10L).toString(), 10.0 + i * 0.0001) }
        val page2 = listOf(page1.last()) + (200 until 400).map { i ->
            point("p-$i", base.plusSeconds(i * 10L).toString(), 10.0 + i * 0.0001)
        }
        val page3 = (400 until 450).map { i -> point("p-$i", base.plusSeconds(i * 10L).toString(), 10.0 + i * 0.0001) }
        source.fetchPage = { _, cursor ->
            when (cursor) {
                null -> HistoryPageDto(page1, "c2")
                "c2" -> HistoryPageDto(page2, "c3")
                "c3" -> HistoryPageDto(page3, null)
                else -> error("unexpected cursor $cursor")
            }
        }
        vm.activate("a")
        assertEquals(450, vm.state.value.points.size)
        assertEquals("p-0", vm.state.value.points.first().eventId)
        assertEquals("p-449", vm.state.value.points.last().eventId)
        assertNull(vm.state.value.nextCursor)
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.loadingMore)
    }

    @Test fun `stalled cursor stops pagination loop safely`() {
        val first = point("first", "2026-10-29T00:00:00Z", 10.0)
        val second = point("second", "2026-10-29T00:01:00Z", 10.0)
        var calls = 0
        source.fetchPage = { _, cursor ->
            calls++
            when (cursor) {
                null -> HistoryPageDto(listOf(first), "stuck")
                "stuck" -> HistoryPageDto(listOf(second), "stuck")
                else -> error("unexpected cursor")
            }
        }
        vm.activate("a")
        assertEquals(2, calls)
        assertEquals(listOf("first", "second"), vm.state.value.points.map { it.eventId })
        assertNull(vm.state.value.nextCursor)
        assertFalse(vm.state.value.loadingMore)
    }

    @Test fun `auto pagination respects page cap and next loads remaining pages`() {
        var pageCalls = 0
        source.fetchPage = { _, cursor ->
            pageCalls++
            val pageNum = cursor?.removePrefix("c")?.toInt() ?: 1
            val pt = point("p-$pageNum", Instant.parse("2026-10-29T00:00:00Z").plusSeconds(pageNum.toLong()).toString(), 10.0)
            val next = if (pageNum < 52) "c${pageNum + 1}" else null
            HistoryPageDto(listOf(pt), next)
        }
        vm.activate("a")
        assertEquals(HistoryViewModel.MAX_AUTO_PAGES, pageCalls)
        assertEquals(HistoryViewModel.MAX_AUTO_PAGES, vm.state.value.points.size)
        assertEquals("c51", vm.state.value.nextCursor)
        assertFalse(vm.state.value.loadingMore)

        vm.next()
        assertEquals(52, pageCalls)
        assertEquals(52, vm.state.value.points.size)
        assertNull(vm.state.value.nextCursor)
    }

    @Test fun `transient failure on later page keeps loaded timeline and retries from failed cursor`() {
        val first = point("first", "2026-10-29T00:00:00Z", 10.0)
        val second = point("second", "2026-10-29T00:01:00Z", 10.0)
        var failSecond = true
        source.fetchPage = { _, cursor ->
            when (cursor) {
                null -> HistoryPageDto(listOf(first), "c2")
                "c2" -> if (failSecond) throw IOException("offline") else HistoryPageDto(listOf(second), null)
                else -> error("unexpected cursor")
            }
        }
        vm.activate("a")
        assertEquals(listOf("first"), vm.state.value.points.map { it.eventId })
        assertEquals("c2", vm.state.value.nextCursor)
        assertEquals(R.string.history_error_offline, vm.state.value.errorRes)
        assertFalse(vm.state.value.loadingMore)

        failSecond = false
        vm.next()
        assertEquals(listOf("first", "second"), vm.state.value.points.map { it.eventId })
        assertNull(vm.state.value.nextCursor)
        assertNull(vm.state.value.errorRes)
    }

    @Test fun `access revoked during later page clears all loaded points`() {
        val first = point("first", "2026-10-29T00:00:00Z", 10.0)
        source.fetchPage = { _, cursor ->
            if (cursor == null) {
                HistoryPageDto(listOf(first), "c2")
            } else {
                throw ApiException("NOT_FOUND", "denied", null, httpStatus = 404)
            }
        }
        vm.activate("a")
        assertTrue(vm.state.value.points.isEmpty())
        assertNull(vm.state.value.nextCursor)
        assertEquals(R.string.history_error_access_lost, vm.state.value.errorRes)
    }

    @Test fun `playback starts from start of day advances by speed and stops at end`() = runTest(dispatcher) {
        val points = (0 until 6).map { i ->
            point("p-$i", Instant.parse("2026-10-29T00:00:00Z").plusSeconds(i * 60L).toString(), 10.0 + i * 0.001)
        }
        source.fetch = { HistoryPageDto(points) }
        vm.activate("a")

        vm.togglePlayback()
        assertTrue(vm.state.value.isPlaying)
        assertEquals("p-0", vm.state.value.selectedPointId)

        // Default speed X1 (700 ms/step)
        advanceTimeBy(700)
        runCurrent()
        assertEquals("p-1", vm.state.value.selectedPointId)
        assertTrue(vm.state.value.isPlaying)

        // Live speed switch to X4 (175 ms/step) waits remainder of current 700ms tick first, then runs at 175ms.
        vm.setPlaybackSpeed(HistoryPlaybackSpeed.X4)
        advanceTimeBy(700)
        runCurrent()
        assertEquals("p-2", vm.state.value.selectedPointId)

        advanceTimeBy(175)
        runCurrent()
        assertEquals("p-3", vm.state.value.selectedPointId)

        // Live speed switch to X16 (90 ms, stride 2) jumps from p-3 -> p-5 (last point) and stops cleanly.
        vm.setPlaybackSpeed(HistoryPlaybackSpeed.X16)
        advanceTimeBy(175)
        runCurrent()
        assertEquals("p-5", vm.state.value.selectedPointId)
        assertFalse(vm.state.value.isPlaying)

        // Tapping play again when at the end restarts from start of day (p-0).
        vm.togglePlayback()
        assertTrue(vm.state.value.isPlaying)
        assertEquals("p-0", vm.state.value.selectedPointId)
    }

    @Test fun `pause resume restart stepping and lifecycle boundaries behave cleanly`() = runTest(dispatcher) {
        val points = (0 until 4).map { i ->
            point("p-$i", Instant.parse("2026-10-29T00:00:00Z").plusSeconds(i * 60L).toString(), 10.0 + i * 0.001)
        }
        source.fetch = { HistoryPageDto(points) }
        vm.activate("a")

        vm.setPlaybackSpeed(HistoryPlaybackSpeed.X2)
        vm.togglePlayback()
        advanceTimeBy(350)
        runCurrent()
        assertEquals("p-1", vm.state.value.selectedPointId)

        vm.pausePlayback()
        assertFalse(vm.state.value.isPlaying)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("p-1", vm.state.value.selectedPointId)

        // Resume continues from paused point p-1 -> p-2
        vm.togglePlayback()
        assertTrue(vm.state.value.isPlaying)
        assertEquals("p-1", vm.state.value.selectedPointId)
        advanceTimeBy(350)
        runCurrent()
        assertEquals("p-2", vm.state.value.selectedPointId)

        // Stepping pauses playback and moves cleanly
        vm.stepPrevious()
        assertFalse(vm.state.value.isPlaying)
        assertEquals("p-1", vm.state.value.selectedPointId)
        vm.stepNext()
        assertEquals("p-2", vm.state.value.selectedPointId)

        // Restart jumps back to index 0 and starts playing
        vm.restartPlayback()
        assertTrue(vm.state.value.isPlaying)
        assertEquals("p-0", vm.state.value.selectedPointId)

        // Manual point selection pauses playback
        vm.selectPoint("p-2")
        assertFalse(vm.state.value.isPlaying)
        assertEquals("p-2", vm.state.value.selectedPointId)

        // Switching member or revoking access stops playback and clears selection
        vm.togglePlayback()
        assertTrue(vm.state.value.isPlaying)
        vm.select("viewer", LocalDate.now())
        assertFalse(vm.state.value.isPlaying)
        assertNull(vm.state.value.selectedPointId)

        vm.togglePlayback()
        assertTrue(vm.state.value.isPlaying)
        vm.recheckAccess("a", "viewer")
        assertFalse(vm.state.value.isPlaying)
        assertNull(vm.state.value.selectedPointId)
        assertTrue(vm.state.value.points.isEmpty())
    }

    @Test fun `playback continues seamlessly while additional pages stream in`() = runTest(dispatcher) {
        val page1 = listOf(
            point("p-0", "2026-10-29T00:00:00Z", 10.0),
            point("p-1", "2026-10-29T00:01:00Z", 10.001),
        )
        val page2 = listOf(
            point("p-2", "2026-10-29T00:02:00Z", 10.002),
            point("p-3", "2026-10-29T00:03:00Z", 10.003),
        )
        val page2Gate = CompletableDeferred<HistoryPageDto>()
        source.fetchPage = { _, cursor ->
            if (cursor == null) {
                HistoryPageDto(page1, "c2")
            } else {
                withContext(NonCancellable) { page2Gate.await() }
            }
        }
        vm.activate("a")
        assertTrue(vm.state.value.loadingMore)
        assertEquals(2, vm.state.value.points.size)

        vm.togglePlayback()
        assertEquals("p-0", vm.state.value.selectedPointId)
        advanceTimeBy(700)
        runCurrent()
        assertEquals("p-1", vm.state.value.selectedPointId)
        assertTrue(vm.state.value.isPlaying)

        // Reaches end of page 1 while page 2 is still streaming -> remains playing
        advanceTimeBy(700)
        runCurrent()
        assertEquals("p-1", vm.state.value.selectedPointId)
        assertTrue(vm.state.value.isPlaying)

        // Page 2 arrives -> playback continues onto p-2 and stops cleanly at p-3
        page2Gate.complete(HistoryPageDto(page2, null))
        runCurrent()
        assertFalse(vm.state.value.loadingMore)
        advanceTimeBy(700)
        runCurrent()
        assertEquals("p-2", vm.state.value.selectedPointId)
        assertTrue(vm.state.value.isPlaying)

        advanceTimeBy(700)
        runCurrent()
        assertEquals("p-3", vm.state.value.selectedPointId)
        assertFalse(vm.state.value.isPlaying)
    }

    @Test fun `traversed segments stop at active point and preserve segment breaks`() {
        val p1 = point("1", "2026-10-29T00:00:00Z", 10.0)
        val p2 = point("2", "2026-10-29T00:01:00Z", 10.0001)
        val p3 = point("3", "2026-10-29T00:02:00Z", 10.0002)
        val gapStart = point("4", "2026-10-29T02:00:00Z", 10.0003)
        val gapEnd = point("5", "2026-10-29T02:01:00Z", 10.0004)
        val all = listOf(p1, p2, p3, gapStart, gapEnd)
        val segments = historySegments(all)
        assertEquals(listOf(3, 2), segments.map { it.size })

        assertTrue(historyTraversedSegments(all, segments, null).isEmpty())
        assertTrue(historyTraversedSegments(all, segments, "1").isEmpty())
        assertEquals(listOf(listOf("1", "2")), historyTraversedSegments(all, segments, "2").map { s -> s.map { it.eventId } })
        assertEquals(listOf(listOf("1", "2", "3")), historyTraversedSegments(all, segments, "4").map { s -> s.map { it.eventId } })
        assertEquals(
            listOf(listOf("1", "2", "3"), listOf("4", "5")),
            historyTraversedSegments(all, segments, "5").map { s -> s.map { it.eventId } },
        )
    }

    @Test fun `failed delete never claims success or retries`() {
        source.failDelete = true
        vm.activate("a")
        vm.delete(all = true)
        assertEquals(1, source.deleteCalls)
        assertEquals(R.string.history_error_delete_unconfirmed, vm.state.value.errorRes)
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
        assertTrue(vm.state.value.errorRes != null)
    }

    @Test fun `confirmed retention change reports the server-accepted days once`() {
        vm.activate("a")
        vm.changeRetention(7)
        assertEquals(R.string.history_retention_saved, vm.retentionConfirmation.value?.messageRes)
        assertEquals(7, vm.retentionConfirmation.value?.days)
        vm.consumeRetentionConfirmation()
        assertEquals(null, vm.retentionConfirmation.value)
    }

    @Test fun `failed retention change never announces success`() {
        source.failRetention = true
        vm.activate("a")
        vm.changeRetention(7)
        assertEquals(null, vm.retentionConfirmation.value)
        assertEquals(R.string.history_error_retention_unconfirmed, vm.state.value.errorRes)
    }

    @Test fun `account switch clears an unconsumed retention confirmation`() {
        vm.activate("a")
        vm.changeRetention(7)
        vm.activate("b")
        assertEquals(null, vm.retentionConfirmation.value)
    }

    @Test fun `loads family places for owner and clears on account switch`() {
        val fakePlaces = FakePlaces(
            listOf(
                PlaceDto(
                    id = "place-1",
                    ownerId = "a",
                    circleId = "c-1",
                    name = "Nhà",
                    latitude = 10.77,
                    longitude = 106.69,
                    radiusMeters = 150.0,
                    sharedWithCircle = true,
                    createdAt = "2026-10-29T00:00:00Z",
                    updatedAt = "2026-10-29T00:00:00Z",
                ),
            ),
        )
        val withPlaces = HistoryViewModel(source, fakePlaces)
        try {
            withPlaces.activate("a")
            assertEquals(listOf("Nhà"), withPlaces.state.value.places.map { it.name })
            withPlaces.activate("b")
            assertTrue(withPlaces.state.value.places.isEmpty())
        } finally {
            withPlaces.activate(null)
        }
    }

    private fun point(id: String, timestamp: String, lat: Double) = HistoryPointDto(id, lat, 106.0, 10.0, timestamp, timestamp, "fused", false, false, "epoch")
    private class FakePlaces(private val forOwnerA: List<PlaceDto>) : PlacesDataSource {
        override suspend fun places(owner: String): List<PlaceDto> = if (owner == "a") forOwnerA else emptyList()
        override suspend fun events(owner: String): List<PlaceEventDto> = emptyList()
        override suspend fun members(owner: String) = SharingManagementSnapshot()
        override suspend fun access(owner: String, place: String): List<String> = emptyList()
        override suspend fun monitor(owner: String, place: String): PlaceMonitorDto? = null
        override suspend fun eligibleRecipients(owner: String, place: String): List<String> = emptyList()
        override suspend fun create(owner: String, input: PlaceInputDto): PlaceDto = error("unused")
        override suspend fun update(owner: String, place: String, input: PlaceInputDto): PlaceDto = error("unused")
        override suspend fun delete(owner: String, place: String) = Unit
        override suspend fun share(owner: String, place: String, member: String, enabled: Boolean) = Unit
        override suspend fun configure(owner: String, place: String, recipients: List<String>): PlaceMonitorDto = error("unused")
        override suspend fun stop(owner: String, place: String) = Unit
    }
    private class FakeHistory : HistoryDataSource {
        var failDelete = false
        var failRetention = false
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
        override suspend fun setRetention(owner: String, days: Int): HistoryRetentionDto {
            if (failRetention) throw IllegalStateException("unconfirmed")
            return HistoryRetentionDto(days, days)
        }
        override suspend fun delete(owner: String, from: String?, to: String) {
            deleteCalls++
            lastDeletedRange = from to to
            if (failDelete) throw IllegalStateException("unknown")
        }
    }
}
