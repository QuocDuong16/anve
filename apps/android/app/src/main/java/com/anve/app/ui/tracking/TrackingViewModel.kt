package com.anve.app.ui.tracking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anve.app.R
import com.anve.app.tracking.TrackingControl
import com.anve.app.tracking.TrackingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrackingUiState(
    val status: TrackingStatus = TrackingStatus(),
    val busy: Boolean = false,
    /** Outcome copy this ViewModel owns; the screen resolves it from resources. */
    val messageRes: Int? = null,
    /**
     * Consent outcome text handed over by the tracking layer (`ConsentResult`).
     * That contract still reports resolved copy, so it passes through verbatim
     * until `ConsentResult` itself becomes resource-backed.
     */
    val consentMessage: String? = null,
)

class TrackingViewModel internal constructor(
    private val manager: TrackingControl,
    externalScope: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = externalScope ?: viewModelScope

    private val _state = MutableStateFlow(TrackingUiState(status = manager.status.value))
    val state: StateFlow<TrackingUiState> = _state.asStateFlow()

    init {
        scope.launch {
            manager.status.collect { status ->
                _state.update { it.copy(status = status) }
            }
        }
        manager.refreshStatus()
    }

    fun refresh() {
        manager.refreshStatus()
    }

    fun start() {
        manager.startTracking()
    }

    fun stop() {
        manager.stopTracking()
    }

    fun retrySync() {
        manager.retrySync()
        _state.update { it.copy(consentMessage = null, messageRes = R.string.tracking_retry_sync_queued) }
    }

    fun setConsent(enabled: Boolean) {
        if (_state.value.busy) return
        scope.launch {
            _state.update { it.copy(busy = true, messageRes = null, consentMessage = null) }
            try {
                val result = manager.setConsent(enabled)
                _state.update { it.copy(busy = false, consentMessage = result.message) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        busy = false,
                        messageRes = if (enabled) R.string.tracking_consent_enable_failed else R.string.tracking_consent_disable_unconfirmed,
                    )
                }
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(messageRes = null, consentMessage = null) }
    }

    companion object {
        fun factory(manager: TrackingControl): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    TrackingViewModel(manager) as T
            }
    }
}
