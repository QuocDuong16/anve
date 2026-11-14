package com.anve.app.ui.journey

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anve.app.R
import com.anve.app.data.api.CreateJourneyDto
import com.anve.app.data.api.ApiException
import com.anve.app.data.api.JourneyDestinationDto
import com.anve.app.data.api.JourneyDto
import com.anve.app.data.api.PlaceDto
import com.anve.app.data.auth.SessionStorage
import com.anve.app.data.journey.JourneyAction
import com.anve.app.data.journey.JourneyDataSource
import com.anve.app.data.journey.JourneyOperation
import com.anve.app.data.location.AudienceMember
import com.anve.app.data.location.PlacesDataSource
import com.anve.app.tracking.JourneyTrackingControl
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class JourneyUiState(
    val owner: String? = null,
    val journeys: List<JourneyDto> = emptyList(),
    val places: List<PlaceDto> = emptyList(),
    val members: List<AudienceMember> = emptyList(),
    val pending: List<JourneyOperation> = emptyList(),
    val selectedId: String? = null,
    val consentEnabled: Boolean = false,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val errorRes: Int? = null,
)

/** Only visible, current-session generations may publish private journey state. */
class JourneyViewModel(
    private val sessions: SessionStorage,
    private val repository: JourneyDataSource,
    private val places: PlacesDataSource,
    private val tracking: JourneyTrackingControl,
    private val incomingId: String? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(JourneyUiState())
    val state = _state.asStateFlow()
    private var epoch = 0L
    private var refreshSequence = 0L
    private var visible = false
    private var polling: Job? = null

    init {
        viewModelScope.launch {
            sessions.session.collectLatest { session ->
                epoch++
                _state.value = JourneyUiState(owner = session?.userId, selectedId = incomingId)
                if (visible && session?.userId != null) viewModelScope.launch { refresh() }
            }
        }
    }

    fun visible(value: Boolean) {
        if (visible == value) return
        visible = value
        epoch++
        polling?.cancel()
        if (!value) {
            _state.value = JourneyUiState(owner = _state.value.owner)
            return
        }
        polling = viewModelScope.launch {
            while (visible) {
                refresh()
                delay(10_000)
            }
        }
    }

    fun reload() { viewModelScope.launch { refresh(explicit = true) } }
    fun select(id: String?) { _state.update { it.copy(selectedId = id, errorRes = null) } }

    private suspend fun refresh(explicit: Boolean = false) {
        val owner = _state.value.owner ?: return
        if (!visible || _state.value.busy) return
        val generation = epoch
        val sequence = ++refreshSequence
        fun current() = visible && generation == epoch && sequence == refreshSequence && _state.value.owner == owner
        try {
            val pending = repository.pending(owner)
            if (current()) _state.update { it.copy(pending = pending) }
            if (pending.isNotEmpty()) repository.reconcile(owner, explicit)
            var journeys = repository.list(owner)
            var selected = _state.value.selectedId
            if (selected != null && journeys.none { it.id == selected }) {
                try {
                    journeys = journeys + repository.get(owner, selected)
                } catch (error: ApiException) {
                    if (error.httpStatus != 404 && error.httpStatus != 403) throw error
                    selected = null
                }
            }
            val remaining = repository.pending(owner)
            if (!current()) return
            _state.update {
                it.copy(
                    journeys = journeys,
                    pending = remaining,
                    selectedId = selected ?: journeys.firstOrNull { j -> j.ownerId == owner && j.state == "ACTIVE" }?.id,
                    loading = false,
                    errorRes = null,
                )
            }
            val destinations = places.places(owner)
            val audience = places.members(owner)
            if (current()) _state.update {
                it.copy(places = destinations, members = audience.members.filter { m -> m.canViewLocation }, consentEnabled = audience.consentEnabled)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (current()) _state.update {
                val denied = error is ApiException && error.httpStatus in setOf(401, 403, 404)
                it.copy(journeys = if (denied) emptyList() else it.journeys, selectedId = if (denied) null else it.selectedId,
                    loading = false, errorRes = R.string.journey_reload_failed)
            }
        }
    }

    fun create(place: PlaceDto, minutes: Int, recipients: Set<String>) {
        createWithOptions(place, minutes, recipients, false, 0)
    }

    fun createWithOptions(place: PlaceDto, minutes: Int, recipients: Set<String>, automaticArrival: Boolean, staleAfterMinutes: Int) {
        if (staleAfterMinutes !in listOf(0, 5, 10, 15, 30)) return
        val state = _state.value
        val owner = state.owner ?: return
        if (!visible || state.busy || state.pending.isNotEmpty() || !state.consentEnabled || tracking.status.value.ownerUserId != owner || !tracking.status.value.activelyTracking || !tracking.status.value.online) return
        if (state.places.none { it.id == place.id } || recipients.isEmpty() || recipients.any { id -> state.members.none { it.userId == id } }) return
        val generation = epoch
        val revision = tracking.journeyAdmissionRevision()
        refreshSequence++
        _state.update { it.copy(busy = true, errorRes = null) }
        viewModelScope.launch {
            try {
                val journey = repository.create(owner, CreateJourneyDto(UUID.randomUUID().toString(), Instant.now().toString(), minutes,
                    JourneyDestinationDto(place.name, place.latitude, place.longitude), recipients.toList(), automaticArrival, staleAfterMinutes))
                if (visible && generation == epoch && _state.value.owner == owner) {
                    if (journey.state == "ACTIVE") tracking.enableJourneyPriority(owner, journey.id, Instant.parse(journey.expiresAt).toEpochMilli(), revision)
                    _state.update { it.copy(selectedId = journey.id) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (visible && generation == epoch) _state.update { it.copy(errorRes = R.string.journey_start_unconfirmed) }
            } finally {
                if (visible && generation == epoch) {
                    _state.update { it.copy(busy = false) }
                    refresh()
                }
            }
        }
    }

    fun action(id: String, action: JourneyAction, recipient: String? = null) {
        val owner = _state.value.owner ?: return
        if (!visible || _state.value.busy) return
        tracking.stopJourneyPriority(owner)
        refreshSequence++
        val generation = epoch
        _state.update { it.copy(busy = true, errorRes = null) }
        viewModelScope.launch {
            try {
                repository.request(owner, id, action, recipient)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (visible && generation == epoch) _state.update { it.copy(errorRes = R.string.journey_action_unconfirmed) }
            } finally {
                if (visible && generation == epoch) {
                    _state.update { it.copy(busy = false) }
                    refresh()
                }
            }
        }
    }

    fun resumePriority(id: String) {
        val owner = _state.value.owner ?: return
        if (_state.value.busy || !visible || _state.value.pending.isNotEmpty()) return
        val generation = epoch
        val revision = tracking.journeyAdmissionRevision()
        viewModelScope.launch {
            try {
                val journey = repository.get(owner, id)
                if (visible && generation == epoch && journey.ownerId == owner && journey.state == "ACTIVE") {
                    val enabled = tracking.enableJourneyPriority(owner, id, Instant.parse(journey.expiresAt).toEpochMilli(), revision)
                    if (!enabled) _state.update { it.copy(errorRes = R.string.journey_priority_capture_off) }
                }
            } catch (error: CancellationException) { throw error } catch (_: Exception) {
                if (visible && generation == epoch) _state.update { it.copy(errorRes = R.string.journey_priority_unconfirmed) }
            }
        }
    }
}
