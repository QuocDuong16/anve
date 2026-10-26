package com.anve.app.ui.tracking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
    val message: String? = null,
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
        _state.update { it.copy(message = "Đã yêu cầu đồng bộ lại. AnVề sẽ gửi các điểm đang chờ khi có mạng.") }
    }

    fun setConsent(enabled: Boolean) {
        if (_state.value.busy) return
        scope.launch {
            _state.update { it.copy(busy = true, message = null) }
            try {
                val result = manager.setConsent(enabled)
                _state.update { it.copy(busy = false, message = result.message) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        busy = false,
                        message = if (enabled) {
                            "Không bật được chia sẻ vị trí. Vui lòng thử lại."
                        } else {
                            "Đã dừng theo dõi trên thiết bị này. Chưa xác nhận được với máy chủ."
                        },
                    )
                }
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
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
