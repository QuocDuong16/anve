package com.anve.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anve.app.data.api.ApiException
import com.anve.app.data.api.DeviceDto
import com.anve.app.data.api.UserDto
import com.anve.app.data.auth.AuthRepository
import com.anve.app.data.auth.SignOutOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Account, privacy consent and device sessions in one screen.
 *
 * Authentication state is deliberately absent here: navigation follows the
 * session storage flow in `AnVeApp`, because a failed `/me` can mean
 * "offline" just as well as "signed out" and only the session source knows
 * which. This state describes the *screen data* load, nothing else.
 */
data class HomeUiState(
    val loading: Boolean = true,
    val user: UserDto? = null,
    val devices: List<DeviceDto> = emptyList(),
    val message: String? = null,
    val error: String? = null,
    val busy: Boolean = false,
)

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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline is not signed-out: keep whatever the screen knows
                // and let the session flow decide whether the user leaves.
                _state.update {
                    it.copy(
                        loading = false,
                        error = "Không tải được thông tin tài khoản. Kiểm tra kết nối và thử lại.",
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
            } catch (e: CancellationException) {
                throw e
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
            } catch (e: CancellationException) {
                throw e
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "Không đăng xuất được thiết bị.") }
            }
        }
    }

    /**
     * Local sign-out always succeeds; the message only distinguishes whether
     * the backend confirmed the revocation. Navigation happens on the
     * session flow once storage is cleared.
     */
    fun signOut() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val outcome = try {
                repository.signOut()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            _state.update {
                it.copy(
                    busy = false,
                    message = if (outcome == SignOutOutcome.LocalOnly) {
                        "Đã đăng xuất khỏi thiết bị này; chưa liên hệ được máy chủ nên phiên có thể còn hiệu lực đến khi hết hạn."
                    } else {
                        null
                    },
                )
            }
        }
    }

    fun deleteAccount() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                repository.deleteAccount()
                // Confirmed: storage is cleared and AnVeApp reroutes.
                _state.update { HomeUiState(loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _state.update { it.copy(busy = false, error = deleteErrorMessage(e.code)) }
            } catch (e: IOException) {
                // Unknown outcome: the server may have deleted the account
                // without the response arriving. Keep the session and
                // reconcile with an authenticated read — if the account is
                // gone, the read is rejected and the interceptor clears the
                // local session; otherwise the user simply retries.
                _state.update {
                    it.copy(
                        busy = false,
                        error = "Chưa xác nhận được kết quả xóa tài khoản. Đang kiểm tra lại với máy chủ…",
                    )
                }
                reconcileAfterUnknownDeletion()
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = "Không xóa được tài khoản. Vui lòng thử lại.") }
            }
        }
    }

    private suspend fun reconcileAfterUnknownDeletion() {
        try {
            repository.me()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline: the user retries. Rejected session: the interceptor
            // already cleared storage and the session flow reroutes.
        }
    }

    private fun deleteErrorMessage(code: String): String = when (code) {
        "CIRCLE_HAS_MEMBERS" ->
            "Tài khoản của bạn đang quản lý một nhóm gia đình có thành viên. Bạn cần giải tán nhóm trước khi xóa tài khoản."
        "UNAUTHENTICATED" ->
            "Phiên đăng nhập không còn hiệu lực. Vui lòng đăng nhập lại."
        else -> "Không xóa được tài khoản. Vui lòng thử lại."
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
