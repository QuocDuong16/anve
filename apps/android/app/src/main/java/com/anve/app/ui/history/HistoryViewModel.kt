package com.anve.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anve.app.R
import com.anve.app.data.api.HistoryPointDto
import com.anve.app.data.api.ApiException
import com.anve.app.data.api.PlaceDto
import com.anve.app.data.location.HistoryMember
import com.anve.app.data.location.HistoryDataSource
import com.anve.app.data.location.PlacesDataSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HistoryPlaybackSpeed(
    val label: String,
    val intervalMillis: Long,
    val stepStride: Int = 1,
) {
    X1("1x", 700L, 1),
    X2("2x", 350L, 1),
    X4("4x", 175L, 1),
    X8("8x", 90L, 1),
    X16("16x", 90L, 2),
}

internal fun HistoryPointDto.hasValidCoordinates(): Boolean =
    latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0

data class HistoryState(
    val owner: String? = null,
    val member: String? = null,
    val date: LocalDate = LocalDate.now(),
    val startMinute: Int = 0,
    val endMinute: Int = 1440,
    val members: List<HistoryMember> = emptyList(),
    val points: List<HistoryPointDto> = emptyList(),
    val places: List<PlaceDto> = emptyList(),
    val nextCursor: String? = null,
    val retentionDays: Int? = null,
    val effectiveRetentionDays: Int? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val mutationPending: Boolean = false,
    val errorRes: Int? = null,
    val selectedPointId: String? = null,
    val isPlaying: Boolean = false,
    val playbackSpeed: HistoryPlaybackSpeed = HistoryPlaybackSpeed.X1,
)

/**
 * One-shot signal for a retention change the server accepted. The chip state
 * alone cannot prove the new horizon took effect (same reason PlacesViewModel
 * counts confirmations), so the screen announces the outcome. [days] is the
 * value the server returned, not the requested one; copy stays a resource id
 * resolved at the screen.
 */
data class RetentionConfirmation(val messageRes: Int, val days: Int)

fun dayInterval(day: LocalDate, zone: ZoneId): Pair<Instant, Instant> =
    day.atStartOfDay(zone).toInstant() to day.plusDays(1).atStartOfDay(zone).toInstant()

fun timeInterval(day: LocalDate, zone: ZoneId, startMinute: Int, endMinute: Int): Pair<Instant, Instant> {
    require(startMinute in 0..1439 && endMinute in 1..1440 && startMinute < endMinute)
    val start = day.atTime(LocalTime.of(startMinute / 60, startMinute % 60)).atZone(zone).withEarlierOffsetAtOverlap().toInstant()
    val end = if (endMinute == 1440) day.plusDays(1).atStartOfDay(zone).toInstant() else
        day.atTime(LocalTime.of(endMinute / 60, endMinute % 60)).atZone(zone).withLaterOffsetAtOverlap().toInstant()
    require(start < end)
    return start to end
}

class HistoryViewModel(
    private val repository: HistoryDataSource,
    private val placesDataSource: PlacesDataSource? = null,
) : ViewModel() {
    private val mutable = MutableStateFlow(HistoryState())
    val state = mutable.asStateFlow()

    /** Event channel for the retention snackbar; the screen consumes after showing. */
    private val mutableRetentionConfirmation = MutableStateFlow<RetentionConfirmation?>(null)
    val retentionConfirmation = mutableRetentionConfirmation.asStateFlow()

    fun consumeRetentionConfirmation() {
        mutableRetentionConfirmation.value = null
    }

    private var generation = 0L
    private var loadJob: Job? = null
    private var mutationJob: Job? = null
    private var accessJob: Job? = null
    private var playbackJob: Job? = null

    fun activate(owner: String?) {
        generation++
        loadJob?.cancel()
        mutationJob?.cancel()
        accessJob?.cancel()
        playbackJob?.cancel()
        mutable.value = HistoryState(owner = owner, member = owner)
        // An unconsumed confirmation belongs to the session that earned it; a
        // fresh owner must not see a stale "saved" announcement.
        mutableRetentionConfirmation.value = null
        if (owner != null) refresh()
    }

    fun select(member: String, date: LocalDate) {
        if (mutable.value.mutationPending) return
        playbackJob?.cancel()
        mutable.update {
            it.copy(
                member = member,
                date = date,
                points = emptyList(),
                nextCursor = null,
                selectedPointId = null,
                isPlaying = false,
                loadingMore = false,
            )
        }
        refresh()
    }

    fun refresh() = load(false)
    fun next() = load(true)

    fun selectTimes(start: Int, end: Int) {
        if (mutable.value.mutationPending) return
        if (runCatching { timeInterval(mutable.value.date, ZoneId.systemDefault(), start, end) }.isFailure) {
            mutable.update { it.copy(errorRes = R.string.history_error_invalid_interval) }
            return
        }
        playbackJob?.cancel()
        mutable.update {
            it.copy(
                startMinute = start,
                endMinute = end,
                points = emptyList(),
                nextCursor = null,
                selectedPointId = null,
                isPlaying = false,
                loadingMore = false,
            )
        }
        refresh()
    }

    fun selectPoint(eventId: String?) {
        playbackJob?.cancel()
        mutable.update { it.copy(selectedPointId = eventId, isPlaying = false) }
    }

    fun pausePlayback() {
        playbackJob?.cancel()
        mutable.update { it.copy(isPlaying = false) }
    }

    fun setPlaybackSpeed(speed: HistoryPlaybackSpeed) {
        mutable.update { it.copy(playbackSpeed = speed) }
    }

    fun stepPrevious() {
        playbackJob?.cancel()
        val current = mutable.value
        val valid = current.points.filter(HistoryPointDto::hasValidCoordinates)
        if (valid.isEmpty()) {
            mutable.update { it.copy(isPlaying = false) }
            return
        }
        val index = current.selectedPointId?.let { id -> valid.indexOfFirst { it.eventId == id } } ?: -1
        val target = when {
            index < 0 -> valid.last()
            index > 0 -> valid[index - 1]
            else -> valid.first()
        }
        mutable.update { it.copy(selectedPointId = target.eventId, isPlaying = false) }
    }

    fun stepNext() {
        playbackJob?.cancel()
        val current = mutable.value
        val valid = current.points.filter(HistoryPointDto::hasValidCoordinates)
        if (valid.isEmpty()) {
            mutable.update { it.copy(isPlaying = false) }
            return
        }
        val index = current.selectedPointId?.let { id -> valid.indexOfFirst { it.eventId == id } } ?: -1
        val target = when {
            index < 0 -> valid.first()
            index < valid.lastIndex -> valid[index + 1]
            else -> valid.last()
        }
        mutable.update { it.copy(selectedPointId = target.eventId, isPlaying = false) }
    }

    fun togglePlayback() {
        val current = mutable.value
        if (current.isPlaying) {
            pausePlayback()
            return
        }
        val valid = current.points.filter(HistoryPointDto::hasValidCoordinates)
        if (valid.size < 2) return
        val currentIndex = current.selectedPointId?.let { id -> valid.indexOfFirst { it.eventId == id } } ?: -1
        val startId = if (currentIndex < 0 || currentIndex >= valid.lastIndex) {
            valid.first().eventId
        } else {
            valid[currentIndex].eventId
        }
        startPlaybackLoop(startId)
    }

    fun restartPlayback() {
        val valid = mutable.value.points.filter(HistoryPointDto::hasValidCoordinates)
        if (valid.size < 2) return
        startPlaybackLoop(valid.first().eventId)
    }

    private fun startPlaybackLoop(startPointId: String) {
        val token = generation
        val owner = mutable.value.owner ?: return
        playbackJob?.cancel()
        mutable.update { it.copy(selectedPointId = startPointId, isPlaying = true) }
        playbackJob = viewModelScope.launch {
            while (true) {
                val speed = mutable.value.playbackSpeed
                delay(speed.intervalMillis)
                val snapshot = mutable.value
                if (generation != token || snapshot.owner != owner || !snapshot.isPlaying) return@launch
                val valid = snapshot.points.filter(HistoryPointDto::hasValidCoordinates)
                if (valid.size < 2) {
                    mutable.update { it.copy(isPlaying = false) }
                    return@launch
                }
                val currentIndex = snapshot.selectedPointId?.let { id ->
                    valid.indexOfFirst { it.eventId == id }
                } ?: -1
                if (currentIndex < 0) {
                    mutable.update { it.copy(selectedPointId = valid.first().eventId) }
                    continue
                }
                if (currentIndex >= valid.lastIndex) {
                    if (snapshot.loadingMore) continue
                    mutable.update { it.copy(isPlaying = false) }
                    return@launch
                }
                val nextIndex = minOf(currentIndex + snapshot.playbackSpeed.stepStride, valid.lastIndex)
                val reachedEnd = nextIndex == valid.lastIndex && !snapshot.loadingMore
                mutable.update {
                    it.copy(
                        selectedPointId = valid[nextIndex].eventId,
                        isPlaying = !reachedEnd,
                    )
                }
                if (reachedEnd) return@launch
            }
        }
    }

    private fun load(append: Boolean) {
        val current = mutable.value
        if (current.mutationPending) return
        val owner = current.owner ?: return
        val member = current.member ?: return
        val startCursor = if (append) current.nextCursor ?: return else null
        val token = ++generation
        loadJob?.cancel()
        if (!append) playbackJob?.cancel()
        // A fresh query resets to empty and shows full loading state; appending
        // keeps the points already rendered so map and timeline stay responsive.
        mutable.update {
            if (append) {
                it.copy(loadingMore = true, errorRes = null)
            } else {
                it.copy(
                    loading = true,
                    loadingMore = false,
                    errorRes = null,
                    points = emptyList(),
                    nextCursor = null,
                    selectedPointId = null,
                    isPlaying = false,
                )
            }
        }
        loadJob = viewModelScope.launch {
            var hasEmittedFirstPage = append
            var cursor: String? = startCursor
            var pagesLoadedInBatch = 0
            val seenIds = HashSet<String>().apply {
                if (append) current.points.forEach { add(it.eventId) }
            }
            try {
                val (from, to) = timeInterval(current.date, ZoneId.systemDefault(), current.startMinute, current.endMinute)
                if (!append) {
                    val page = repository.page(owner, member, from.toString(), to.toString(), null)
                    val members = repository.members(owner)
                    val retention = repository.retention(owner)
                    val places = runCatching { placesDataSource?.places(owner).orEmpty() }.getOrElse { current.places }
                    if (generation != token || mutable.value.owner != owner) return@launch
                    pagesLoadedInBatch = 1
                    val dedupedFirst = page.points.filter { seenIds.add(it.eventId) }
                    val initialCursor = page.nextCursor?.takeIf { page.points.isNotEmpty() }
                    val keepLoading = initialCursor != null && pagesLoadedInBatch < MAX_AUTO_PAGES
                    mutable.update {
                        it.copy(
                            points = dedupedFirst,
                            nextCursor = initialCursor,
                            members = members,
                            places = places,
                            retentionDays = retention.days,
                            effectiveRetentionDays = retention.effectiveDays,
                            loading = false,
                            loadingMore = keepLoading,
                        )
                    }
                    hasEmittedFirstPage = true
                    cursor = initialCursor
                }
                while (cursor != null && pagesLoadedInBatch < MAX_AUTO_PAGES) {
                    val nextPage = repository.page(owner, member, from.toString(), to.toString(), cursor)
                    if (generation != token || mutable.value.owner != owner) return@launch
                    pagesLoadedInBatch++
                    val freshPoints = nextPage.points.filter { seenIds.add(it.eventId) }
                    val next = if (nextPage.nextCursor == cursor || (freshPoints.isEmpty() && nextPage.points.isEmpty())) {
                        null
                    } else {
                        nextPage.nextCursor
                    }
                    val keepLoading = next != null && pagesLoadedInBatch < MAX_AUTO_PAGES
                    mutable.update {
                        it.copy(
                            points = it.points + freshPoints,
                            nextCursor = next,
                            loadingMore = keepLoading,
                        )
                    }
                    cursor = next
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val isAccessLost = e is ApiException && e.httpStatus in listOf(403, 404)
                val messageRes = when {
                    e is IOException -> R.string.history_error_offline
                    isAccessLost -> R.string.history_error_access_lost
                    else -> R.string.history_error_load_failed
                }
                if (generation == token && mutable.value.owner == owner) {
                    if (!hasEmittedFirstPage || isAccessLost) {
                        playbackJob?.cancel()
                        mutable.update {
                            it.copy(
                                points = emptyList(),
                                nextCursor = null,
                                members = emptyList(),
                                selectedPointId = null,
                                isPlaying = false,
                                loading = false,
                                loadingMore = false,
                                errorRes = messageRes,
                            )
                        }
                    } else {
                        // Keep pages already loaded on transient errors and retain the failed cursor
                        // so a single retry resumes from where pagination stopped.
                        mutable.update {
                            it.copy(
                                nextCursor = cursor,
                                loading = false,
                                loadingMore = false,
                                errorRes = messageRes,
                            )
                        }
                    }
                }
            }
        }
    }

    fun recheckAccess(owner: String, member: String) {
        val current = mutable.value
        if (current.owner != owner || current.member != member || member == owner || current.loading || current.loadingMore || accessJob?.isActive == true) return
        val token = generation
        accessJob = viewModelScope.launch {
            try {
                val members = repository.members(owner)
                if (generation != token || mutable.value.owner != owner || mutable.value.member != member) return@launch
                if (members.none { it.ownerId == member }) {
                    generation++
                    loadJob?.cancel()
                    playbackJob?.cancel()
                    mutable.update {
                        it.copy(
                            points = emptyList(),
                            nextCursor = null,
                            members = members,
                            selectedPointId = null,
                            isPlaying = false,
                            loading = false,
                            loadingMore = false,
                            errorRes = R.string.history_error_viewer_revoked,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == token && mutable.value.owner == owner && mutable.value.member == member) {
                    generation++
                    loadJob?.cancel()
                    playbackJob?.cancel()
                    mutable.update {
                        it.copy(
                            points = emptyList(),
                            nextCursor = null,
                            members = emptyList(),
                            selectedPointId = null,
                            isPlaying = false,
                            loading = false,
                            loadingMore = false,
                            errorRes = R.string.history_error_access_check_failed,
                        )
                    }
                }
            }
        }
    }

    fun delete(all: Boolean, filtered: Boolean = false) {
        val current = mutable.value
        val owner = current.owner ?: return
        if (current.member != owner || current.mutationPending) return
        val token = ++generation
        loadJob?.cancel()
        playbackJob?.cancel()
        mutable.update {
            it.copy(
                points = emptyList(),
                nextCursor = null,
                mutationPending = true,
                errorRes = null,
                loadingMore = false,
                selectedPointId = null,
                isPlaying = false,
            )
        }
        mutationJob = viewModelScope.launch {
            try {
                val (from, end) = if (filtered) timeInterval(current.date, ZoneId.systemDefault(), current.startMinute, current.endMinute) else dayInterval(current.date, ZoneId.systemDefault())
                val now = Instant.now()
                val to = if (all) now else minOf(end, now)
                repository.delete(owner, if (all) null else from.toString(), to.toString())
                if (generation != token || mutable.value.owner != owner) return@launch
                mutable.update { it.copy(mutationPending = false) }
                refresh()
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                if (generation == token && mutable.value.owner == owner) mutable.update { it.copy(mutationPending = false, errorRes = R.string.history_error_delete_unconfirmed) }
            }
        }
    }

    fun changeRetention(days: Int) {
        val owner = mutable.value.owner ?: return
        if (mutable.value.mutationPending) return
        val token = ++generation
        loadJob?.cancel()
        playbackJob?.cancel()
        mutable.update {
            it.copy(
                points = emptyList(),
                nextCursor = null,
                mutationPending = true,
                errorRes = null,
                loadingMore = false,
                selectedPointId = null,
                isPlaying = false,
            )
        }
        mutationJob = viewModelScope.launch {
            try {
                val result = repository.setRetention(owner, days)
                if (generation != token || mutable.value.owner != owner) return@launch
                mutable.update { it.copy(retentionDays = result.days, effectiveRetentionDays = result.effectiveDays, mutationPending = false) }
                // Announce only after the server accepted; failure keeps the
                // chip unchanged and speaks through the existing error notice.
                mutableRetentionConfirmation.value = RetentionConfirmation(R.string.history_retention_saved, result.days)
                refresh()
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                if (generation == token && mutable.value.owner == owner) mutable.update { it.copy(mutationPending = false, retentionDays = null, effectiveRetentionDays = null, errorRes = R.string.history_error_retention_unconfirmed) }
            }
        }
    }

    companion object {
        internal const val MAX_AUTO_PAGES = 50

        fun factory(
            repository: HistoryDataSource,
            placesDataSource: PlacesDataSource? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = HistoryViewModel(repository, placesDataSource) as T
        }
    }
}
