package com.anve.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anve.app.data.api.DeviceDto
import com.anve.app.data.api.UserDto
import com.anve.app.data.auth.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Account, privacy consent and device sessions in one screen. */
data class HomeUiState(
    val loading: Boolean = true,
    val user: UserDto? = null,
    val devices: List<DeviceDto> = emptyList(),
    val message: String? = null,
    val error: String? = null,
    val busy: Boolean = false,
) {
    val signedOut: Boolean get() = !loading && user == null
}

class HomeViewModel(
    private val repository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val user = repository.me()
                val devices = repository.devices().devices
                _state.update {
                    it.copy(loading = false, user = user, devices = devices)
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = "Không tải được thông tin tài khoản.",
                    )
                }
            }
        }
    }

    /** The privacy consent switch — always the data subject's own call. */
    fun setLocationSharing(enabled: Boolean) {
        val current = _state.value.user ?: return
        // Optimistic flip; the server's answer is authoritative.
        _state.update { it.copy(user = current.copy(locationSharingEnabled = enabled)) }
        viewModelScope.launch {
            try {
                val user = repository.setLocationSharing(enabled)
                _state.update { it.copy(user = user) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        user = current,
                        error = if (enabled) {
                            "Không bật được chia sẻ vị trí. Vui lòng thử lại."
                        } else {
                            "Không tắt được chia sẻ vị trí. Vui lòng thử lại."
                        },
                    )
                }
            }
        }
    }

    fun rename(displayName: String) {
        if (displayName.isBlank()) return
        viewModelScope.launch {
            try {
                val user = repository.updateDisplayName(displayName.trim())
                _state.update { it.copy(user = user, message = "Đã cập nhật tên hiển thị.") }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Không cập nhật được tên hiển thị.") }
            }
        }
    }

    fun revokeDevice(deviceId: String) {
        viewModelScope.launch {
            try {
                repository.revokeDevice(deviceId)
                _state.update {
                    it.copy(
                        devices = it.devices.filterNot { d -> d.id == deviceId },
                        message = "Đã đăng xuất thiết bị.",
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Không đăng xuất được thiết bị.") }
            }
        }
    }

    fun signOut(onSignedOut: () -> Unit) {
        viewModelScope.launch {
            try {
                repository.signOut()
            } catch (_: Exception) {
                // Local session is cleared regardless; being unable to tell
                // the server means it will expire naturally.
            }
            _state.update { HomeUiState(loading = false) }
            onSignedOut()
        }
    }

    fun deleteAccount(onDeleted: () -> Unit) {
        viewModelScope.launch {
            try {
                repository.deleteAccount()
                _state.update { HomeUiState(loading = false) }
                onDeleted()
            } catch (e: Exception) {
                _state.update { it.copy(error = "Không xóa được tài khoản. Vui lòng thử lại.") }
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null, error = null) }
    }

    companion object {
        fun factory(repository: AuthRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(repository) as T
            }
    }
}
