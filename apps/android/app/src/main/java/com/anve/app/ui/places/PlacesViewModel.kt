package com.anve.app.ui.places

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anve.app.data.api.PlaceDto
import com.anve.app.data.api.PlaceEventDto
import com.anve.app.data.api.PlaceInputDto
import com.anve.app.data.api.PlaceMonitorDto
import com.anve.app.data.location.PlacesDataSource
import com.anve.app.data.location.SharingManagementSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

fun validPlaceInput(name: String, latitude: Double?, longitude: Double?, radius: Double): Boolean {
    val trimmed = name.trim()
    return trimmed.codePointCount(0, trimmed.length) in 1..80 &&
        latitude != null && latitude.isFinite() && latitude in -90.0..90.0 &&
        longitude != null && longitude.isFinite() && longitude in -180.0..180.0 &&
        radius.isFinite() && radius in 50.0..5000.0
}

data class PlacesState(
    val owner: String? = null,
    val places: List<PlaceDto> = emptyList(),
    val events: List<PlaceEventDto> = emptyList(),
    val sharing: SharingManagementSnapshot = SharingManagementSnapshot(),
    val selectedId: String? = null,
    val access: Set<String> = emptySet(),
    val eligibleRecipients: Set<String> = emptySet(),
    val monitor: PlaceMonitorDto? = null,
    val loading: Boolean = false,
    val pending: Boolean = false,
    val confirmation: Long = 0,
    val error: String? = null,
    val notice: String? = null,
)

class PlacesViewModel(private val repository: PlacesDataSource) : ViewModel() {
    private val mutable = MutableStateFlow(PlacesState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var loadJob: Job? = null
    private var mutationJob: Job? = null

    fun activate(owner: String?) {
        generation++
        loadJob?.cancel()
        mutationJob?.cancel()
        mutable.value = PlacesState(owner = owner)
        if (owner != null) refresh()
    }

    fun select(id: String?) {
        if (mutable.value.pending) return
        mutable.update { it.copy(selectedId = id, access = emptySet(), eligibleRecipients = emptySet(), monitor = null, error = null, notice = null) }
        refresh()
    }

    fun refresh() {
        val current = mutable.value
        val owner = current.owner ?: return
        if (current.pending) return
        val token = ++generation
        loadJob?.cancel()
        mutable.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val places = repository.places(owner)
                val events = repository.events(owner)
                val sharing = repository.members(owner)
                val selected = places.firstOrNull { it.id == current.selectedId }
                val access = if (selected?.ownerId == owner) repository.access(owner, selected.id).toSet() else emptySet()
                val eligible = if (selected != null) repository.eligibleRecipients(owner, selected.id).toSet() else emptySet()
                val monitor = if (selected != null) repository.monitor(owner, selected.id) else null
                if (generation != token || mutable.value.owner != owner) return@launch
                mutable.update { it.copy(places = places, events = events, sharing = sharing, selectedId = selected?.id,
                    access = access, eligibleRecipients = eligible, monitor = monitor, loading = false, error = null) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                if (generation == token && mutable.value.owner == owner) mutable.update {
                    it.copy(places = emptyList(), events = emptyList(), sharing = SharingManagementSnapshot(),
                        selectedId = null, access = emptySet(), eligibleRecipients = emptySet(), monitor = null, loading = false,
                        error = "Không thể kiểm tra địa điểm và quyền hiện tại. Kiểm tra kết nối rồi thử lại.")
                }
            }
        }
    }

    fun save(placeID: String?, input: PlaceInputDto) = mutate { owner ->
        if (placeID == null) repository.create(owner, input) else repository.update(owner, placeID, input)
    }

    fun delete(id: String) = mutate { owner ->
        repository.delete(owner, id)
    }

    fun share(id: String, member: String, enabled: Boolean) = mutate { owner ->
        repository.share(owner, id, member, enabled)
    }

    fun configure(id: String, recipients: Set<String>) = mutate { owner ->
        repository.configure(owner, id, recipients.sorted())
    }

    fun stop(id: String) = mutate { owner ->
        repository.stop(owner, id)
    }

    private fun mutate(action: suspend (String) -> Unit) {
        val owner = mutable.value.owner ?: return
        if (mutable.value.pending) return
        val token = ++generation
        loadJob?.cancel()
        mutable.update { it.copy(pending = true, loading = false, events = emptyList(), error = null, notice = null) }
        mutationJob = viewModelScope.launch {
            try {
                action(owner)
                if (generation != token || mutable.value.owner != owner) return@launch
                mutable.update { it.copy(pending = false, confirmation = it.confirmation + 1) }
                refresh()
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                if (generation == token && mutable.value.owner == owner) {
                    mutable.update { it.copy(pending = false, events = emptyList(), access = emptySet(), eligibleRecipients = emptySet(), monitor = null,
                        notice = "Chưa xác nhận thay đổi. Kiểm tra lại trước khi thực hiện thao tác mới.") }
                    refresh()
                }
            }
        }
    }

    companion object {
        fun factory(repository: PlacesDataSource): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PlacesViewModel(repository) as T
        }
    }
}
