package com.anve.app.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anve.app.data.auth.SessionStorage
import com.anve.app.data.diagnostics.DiagnosticsSource
import com.anve.app.tracking.TrackingControl
import com.anve.app.tracking.TrackingStatus
import com.anve.app.tracking.diagnostics.DiagnosticsDetails
import com.anve.app.tracking.diagnostics.safeDiagnosticsReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiagnosticsUiState(
    val owner: String? = null,
    val status: TrackingStatus = TrackingStatus(),
    val details: DiagnosticsDetails? = null,
    val loading: Boolean = false,
    val checkingPush: Boolean = false,
    val readFailed: Boolean = false,
    val refreshedAtMillis: Long? = null,
)

class DiagnosticsExport internal constructor(val owner: String, val generation: Long, val text: String)

class DiagnosticsViewModel internal constructor(
    private val control: TrackingControl,
    private val sessions: SessionStorage,
    private val source: DiagnosticsSource,
    externalScope: CoroutineScope? = null,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val scope = externalScope ?: viewModelScope
    private val mutableState = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = mutableState.asStateFlow()
    private var generation = 0L
    private var refreshJob: Job? = null
    private var active = true

    init {
        scope.launch {
            sessions.session.map { it?.userId }.distinctUntilChanged().collect { owner ->
                generation++
                refreshJob?.cancel()
                mutableState.value = DiagnosticsUiState(
                    owner = owner,
                    status = control.status.value.takeIf { active && owner != null && it.ownerUserId == owner } ?: TrackingStatus(),
                )
                if (owner != null && active) refresh()
            }
        }
        scope.launch {
            control.status.collect { status ->
                mutableState.update {
                    it.copy(status = status.takeIf { s -> active && it.owner != null && s.ownerUserId == it.owner } ?: TrackingStatus())
                }
            }
        }
    }

    fun refresh() {
        if (!active) return
        val owner = state.value.owner ?: return
        refreshJob?.cancel()
        val request = ++generation
        mutableState.update { it.copy(loading = true, checkingPush = false, readFailed = false, details = null) }
        control.refreshStatus()
        refreshJob = scope.launch {
            try {
                val local = source.loadLocal(owner)
                if (request != generation || sessions.current()?.userId != owner) return@launch
                mutableState.update { it.copy(details = local, loading = false, checkingPush = true, refreshedAtMillis = now()) }
                val details = source.loadRemote(owner, local)
                if (request != generation || sessions.current()?.userId != owner) return@launch
                mutableState.update { it.copy(details = details, checkingPush = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (request == generation && sessions.current()?.userId == owner) {
                    mutableState.update { it.copy(loading = false, checkingPush = false, readFailed = true) }
                }
            }
        }
    }

    fun activate() {
        active = true
        mutableState.update {
            it.copy(status = control.status.value.takeIf { s -> it.owner != null && s.ownerUserId == it.owner } ?: TrackingStatus())
        }
        refresh()
    }

    fun deactivate() {
        active = false
        generation++
        refreshJob?.cancel()
        mutableState.update { it.copy(status = TrackingStatus(), details = null, loading = false, checkingPush = false, readFailed = false, refreshedAtMillis = null) }
    }

    suspend fun prepareExport(version: String, androidApi: Int): DiagnosticsExport? {
        val snapshot = state.value
        val owner = snapshot.owner ?: return null
        val request = generation
        if (!active || snapshot.loading || snapshot.status.ownerUserId != owner || sessions.current()?.userId != owner) return null
        if (request != generation) return null
        return DiagnosticsExport(owner, request, safeDiagnosticsReport(snapshot.status, snapshot.details, version, androidApi, now()))
    }

    suspend fun canShare(export: DiagnosticsExport): Boolean =
        active && sessions.current()?.userId == export.owner && state.value.owner == export.owner && generation == export.generation

    companion object {
        fun factory(control: TrackingControl, sessions: SessionStorage, source: DiagnosticsSource): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = DiagnosticsViewModel(control, sessions, source) as T
            }
    }
}
