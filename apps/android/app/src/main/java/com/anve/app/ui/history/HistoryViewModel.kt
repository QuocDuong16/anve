package com.anve.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anve.app.data.api.HistoryPointDto
import com.anve.app.data.api.ApiException
import com.anve.app.data.location.HistoryMember
import com.anve.app.data.location.HistoryDataSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryState(
    val owner: String? = null,
    val member: String? = null,
    val date: LocalDate = LocalDate.now(),
    val startMinute: Int = 0,
    val endMinute: Int = 1440,
    val members: List<HistoryMember> = emptyList(),
    val points: List<HistoryPointDto> = emptyList(),
    val nextCursor: String? = null,
    val retentionDays: Int? = null,
    val effectiveRetentionDays: Int? = null,
    val loading: Boolean = false,
    val mutationPending: Boolean = false,
    val error: String? = null,
)

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

class HistoryViewModel(private val repository: HistoryDataSource) : ViewModel() {
    private val mutable = MutableStateFlow(HistoryState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var loadJob: Job? = null
    private var mutationJob: Job? = null
    private var accessJob: Job? = null

    fun activate(owner: String?) {
        generation++
        loadJob?.cancel()
        mutationJob?.cancel()
        accessJob?.cancel()
        mutable.value = HistoryState(owner = owner, member = owner)
        if (owner != null) refresh()
    }

    fun select(member: String, date: LocalDate) {
        if (mutable.value.mutationPending) return
        mutable.update { it.copy(member = member, date = date, points = emptyList(), nextCursor = null) }
        refresh()
    }

    fun refresh() = load(false)
    fun next() = load(true)

    fun selectTimes(start: Int, end: Int) {
        if (mutable.value.mutationPending) return
        if (runCatching { timeInterval(mutable.value.date, ZoneId.systemDefault(), start, end) }.isFailure) {
            mutable.update { it.copy(error = "Khoảng giờ không hợp lệ.") }
            return
        }
        mutable.update { it.copy(startMinute = start, endMinute = end, points = emptyList(), nextCursor = null) }
        refresh()
    }

    private fun load(append: Boolean) {
        val current = mutable.value
        if (current.mutationPending) return
        val owner = current.owner ?: return
        val member = current.member ?: return
        val cursor = if (append) current.nextCursor ?: return else null
        val token = ++generation
        loadJob?.cancel()
        mutable.update { it.copy(loading = true, error = null, points = emptyList()) }
        loadJob = viewModelScope.launch {
            try {
                val (from, to) = timeInterval(current.date, ZoneId.systemDefault(), current.startMinute, current.endMinute)
                val page = repository.page(owner, member, from.toString(), to.toString(), cursor)
                val members = if (append) current.members else repository.members(owner)
                val retention = if (append) null else repository.retention(owner)
                if (generation != token || mutable.value.owner != owner) return@launch
                mutable.update { it.copy(points = page.points, nextCursor = page.nextCursor, members = members, retentionDays = retention?.days ?: current.retentionDays, effectiveRetentionDays = retention?.effectiveDays ?: current.effectiveRetentionDays, loading = false) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                val message = when {
                    e is IOException -> "Không có kết nối. Kiểm tra mạng rồi thử lại."
                    e is ApiException && e.httpStatus in listOf(403, 404) -> "Không còn quyền xem lịch sử này."
                    else -> "Không thể tải lịch sử. Vui lòng thử lại."
                }
                if (generation == token && mutable.value.owner == owner) mutable.update { it.copy(points = emptyList(), nextCursor = null, members = emptyList(), loading = false, error = message) }
            }
        }
    }

    fun recheckAccess(owner: String, member: String) {
        val current = mutable.value
        if (current.owner != owner || current.member != member || member == owner || current.loading || accessJob?.isActive == true) return
        val token = generation
        accessJob = viewModelScope.launch {
            try {
                val members = repository.members(owner)
                if (generation != token || mutable.value.owner != owner || mutable.value.member != member) return@launch
                if (members.none { it.ownerId == member }) {
                    generation++
                    loadJob?.cancel()
                    mutable.update { it.copy(points = emptyList(), nextCursor = null, members = members, loading = false, error = "Quyền xem không còn hiệu lực.") }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                if (generation == token && mutable.value.owner == owner && mutable.value.member == member) {
                    generation++
                    loadJob?.cancel()
                    mutable.update { it.copy(points = emptyList(), nextCursor = null, members = emptyList(), loading = false, error = "Không thể kiểm tra quyền xem. Thử lại khi có kết nối.") }
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
        mutable.update { it.copy(points = emptyList(), nextCursor = null, mutationPending = true, error = null) }
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
                if (generation == token && mutable.value.owner == owner) mutable.update { it.copy(mutationPending = false, error = "Chưa xác nhận việc xóa. Kiểm tra lại lịch sử trước khi thực hiện thao tác mới.") }
            }
        }
    }

    fun changeRetention(days: Int) {
        val owner = mutable.value.owner ?: return
        if (mutable.value.mutationPending) return
        val token = ++generation
        loadJob?.cancel()
        mutable.update { it.copy(points = emptyList(), nextCursor = null, mutationPending = true, error = null) }
        mutationJob = viewModelScope.launch {
            try {
                val result = repository.setRetention(owner, days)
                if (generation != token || mutable.value.owner != owner) return@launch
                mutable.update { it.copy(retentionDays = result.days, effectiveRetentionDays = result.effectiveDays, mutationPending = false) }
                refresh()
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                if (generation == token && mutable.value.owner == owner) mutable.update { it.copy(mutationPending = false, retentionDays = null, effectiveRetentionDays = null, error = "Chưa xác nhận thời hạn lưu. Kiểm tra lại trước khi thay đổi.") }
            }
        }
    }

    companion object {
        fun factory(repository: HistoryDataSource): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = HistoryViewModel(repository) as T
        }
    }
}
