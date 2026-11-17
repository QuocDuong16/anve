package com.anve.app.ui.places

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anve.app.R
import com.anve.app.data.api.IncomingPlaceMonitorDto
import com.anve.app.data.api.PlaceDto
import com.anve.app.data.api.PlaceEventDto
import com.anve.app.data.api.PlaceInputDto
import com.anve.app.data.api.PlaceMonitorDto
import com.anve.app.data.location.PlacesDataSource
import com.anve.app.data.location.SharingManagementSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    val incomingMonitors: List<IncomingPlaceMonitorDto> = emptyList(),
    val loading: Boolean = false,
    val detailLoading: Boolean = false,
    val refreshing: Boolean = false,
    val pending: Boolean = false,
    val confirmation: Long = 0,
    val errorRes: Int? = null,
    val noticeRes: Int? = null,
)

class PlacesViewModel(
    private val repository: PlacesDataSource,
) : ViewModel() {
    private enum class LoadMode { INITIAL, REFRESH, SILENT }

    private data class PlaceDetails(
        val access: Set<String> = emptySet(),
        val eligibleRecipients: Set<String> = emptySet(),
        val monitor: PlaceMonitorDto? = null,
        val incomingMonitors: List<IncomingPlaceMonitorDto> = emptyList(),
    )

    private val mutable = MutableStateFlow(PlacesState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var detailGeneration = 0L
    private var loadJob: Job? = null
    private var detailJob: Job? = null
    private var mutationJob: Job? = null

    fun activate(owner: String?) {
        generation++
        detailGeneration++
        loadJob?.cancel()
        detailJob?.cancel()
        mutationJob?.cancel()
        mutable.value = PlacesState(owner = owner)
        if (owner != null) load(LoadMode.INITIAL)
    }

    /**
     * Reuses the already-loaded snapshot when returning to the Places tab for
     * the same signed-in account, refreshing silently instead of wiping state.
     */
    fun resume(owner: String?) {
        if (owner == null) {
            activate(null)
            return
        }
        if (mutable.value.owner != owner) {
            activate(owner)
        } else if (!mutable.value.loading && !mutable.value.pending && loadJob?.isActive != true) {
            load(LoadMode.SILENT)
        }
    }

    fun select(id: String?) {
        val current = mutable.value
        if (current.pending) return
        val owner = current.owner
        detailJob?.cancel()
        if (id == null) {
            detailGeneration++
            mutable.update {
                it.copy(
                    selectedId = null,
                    access = emptySet(),
                    eligibleRecipients = emptySet(),
                    monitor = null,
                    incomingMonitors = emptyList(),
                    detailLoading = false,
                    errorRes = null,
                    noticeRes = null,
                )
            }
            return
        }
        val selected = current.places.firstOrNull { it.id == id }
        if (owner == null || selected == null) {
            detailGeneration++
            mutable.update {
                it.copy(
                    selectedId = id,
                    access = emptySet(),
                    eligibleRecipients = emptySet(),
                    monitor = null,
                    incomingMonitors = emptyList(),
                    errorRes = null,
                    noticeRes = null,
                )
            }
            load(LoadMode.INITIAL)
            return
        }
        val detailToken = ++detailGeneration
        mutable.update {
            it.copy(
                selectedId = id,
                access = emptySet(),
                eligibleRecipients = emptySet(),
                monitor = null,
                incomingMonitors = emptyList(),
                detailLoading = true,
                errorRes = null,
                noticeRes = null,
            )
        }
        detailJob = viewModelScope.launch {
            try {
                val details = loadPlaceDetails(owner, selected)
                if (detailGeneration != detailToken || mutable.value.owner != owner || mutable.value.selectedId != id) return@launch
                mutable.update {
                    it.copy(
                        access = details.access,
                        eligibleRecipients = details.eligibleRecipients,
                        monitor = details.monitor,
                        incomingMonitors = details.incomingMonitors,
                        detailLoading = false,
                        errorRes = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (detailGeneration == detailToken && mutable.value.owner == owner && mutable.value.selectedId == id) {
                    mutable.update { it.copy(detailLoading = false, errorRes = R.string.places_error_load_failed) }
                }
            }
        }
    }

    fun refresh() = load(LoadMode.REFRESH)

    fun poll() {
        val current = mutable.value
        if (current.owner == null || current.pending || loadJob?.isActive == true) return
        load(LoadMode.SILENT)
    }

    private suspend fun loadPlaceDetails(owner: String, selected: PlaceDto): PlaceDetails = coroutineScope {
        val accessDef = if (selected.ownerId == owner) async { repository.access(owner, selected.id).toSet() } else null
        val eligibleDef = async { repository.eligibleRecipients(owner, selected.id).toSet() }
        val monitorDef = async { repository.monitor(owner, selected.id) }
        val incomingDef = async { repository.incomingMonitors(owner, selected.id) }
        PlaceDetails(
            access = accessDef?.await() ?: emptySet(),
            eligibleRecipients = eligibleDef.await(),
            monitor = monitorDef.await(),
            incomingMonitors = incomingDef.await(),
        )
    }

    private fun load(mode: LoadMode) {
        val current = mutable.value
        val owner = current.owner ?: return
        if (current.pending) return
        val token = ++generation
        val detailToken = ++detailGeneration
        loadJob?.cancel()
        detailJob?.cancel()
        when (mode) {
            LoadMode.INITIAL -> mutable.update { it.copy(loading = true, refreshing = false, errorRes = null) }
            LoadMode.REFRESH -> mutable.update { it.copy(refreshing = true, loading = false, errorRes = null) }
            LoadMode.SILENT -> Unit
        }
        loadJob = viewModelScope.launch {
            try {
                val (places, events, sharing) = coroutineScope {
                    val placesDef = async { repository.places(owner) }
                    val eventsDef = async { repository.events(owner) }
                    val sharingDef = async { repository.members(owner) }
                    Triple(placesDef.await(), eventsDef.await(), sharingDef.await())
                }
                val selected = places.firstOrNull { it.id == mutable.value.selectedId }
                val details = if (selected != null) loadPlaceDetails(owner, selected) else PlaceDetails()
                if (generation != token || detailGeneration != detailToken || mutable.value.owner != owner) return@launch
                mutable.update {
                    it.copy(
                        places = places,
                        events = events,
                        sharing = sharing,
                        selectedId = selected?.id,
                        access = details.access,
                        eligibleRecipients = details.eligibleRecipients,
                        monitor = details.monitor,
                        incomingMonitors = details.incomingMonitors,
                        loading = false,
                        detailLoading = false,
                        refreshing = false,
                        errorRes = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == token && mutable.value.owner == owner) {
                    mutable.update {
                        // A transient poll failure keeps the last good snapshot and
                        // the open selection — the reader stays in the detail they
                        // are reading (like HistoryScreen keeping loaded pages).
                        // "No longer available" is reserved for a successful load
                        // that really lacks the place.
                        it.copy(loading = false, detailLoading = false, refreshing = false, errorRes = R.string.places_error_load_failed)
                    }
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

    fun setSharedWithCircle(place: PlaceDto, shared: Boolean) = mutate { owner ->
        repository.update(
            owner,
            place.id,
            PlaceInputDto(
                name = place.name,
                latitude = place.latitude,
                longitude = place.longitude,
                radiusMeters = place.radiusMeters,
                sharedWithCircle = shared,
            ),
        )
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
        detailGeneration++
        loadJob?.cancel()
        detailJob?.cancel()
        mutable.update { it.copy(pending = true, loading = false, refreshing = false, events = emptyList(), errorRes = null, noticeRes = null) }
        mutationJob = viewModelScope.launch {
            try {
                action(owner)
                if (generation != token || mutable.value.owner != owner) return@launch
                mutable.update { it.copy(pending = false, confirmation = it.confirmation + 1) }
                load(LoadMode.SILENT)
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                if (generation == token && mutable.value.owner == owner) {
                    mutable.update { it.copy(pending = false, events = emptyList(), access = emptySet(), eligibleRecipients = emptySet(), monitor = null, incomingMonitors = emptyList(),
                        noticeRes = R.string.places_error_mutation_unconfirmed) }
                    load(LoadMode.SILENT)
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
