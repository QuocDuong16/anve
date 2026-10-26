package com.anve.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anve.app.data.api.ApiException
import com.anve.app.data.api.DeviceDto
import com.anve.app.data.api.UserDto
import com.anve.app.data.auth.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

class HomeViewModel internal constructor(
    private val repository: AuthRepository,
    externalScope: CoroutineScope? = null,
) : ViewModel() {

    /**
     * Production uses `viewModelScope`; tests inject their own scope so they
     * never mutate the global Main dispatcher while a coroutine is alive.
     */
    private val scope: CoroutineScope = externalScope ?: viewModelScope

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /**
     * Serializes consent writes so rapid toggles cannot interleave: each
     * request starts after the previous one settled, and a failed request
     * rolls the switch back to the last state the server confirmed — never
     * leaving the UI disagreeing with the backend.
     */
    private val consentLock = Mutex()
    private var confirmedSharing: Boolean? = null

    init {
        refresh()
    }

    fun refresh() {
        scope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val user = repository.me()
                val devices = repository.devices().devices
                confirmedSharing = user.locationSharingEnabled
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
        val atCall = _state.value.user ?: return
        // Optimistic flip; the server's answer is authoritative.
        _state.update { it.copy(user = it.user?.copy(locationSharingEnabled = enabled), error = null) }
        scope.launch {
            consentLock.withLock {
                try {
                    val user = repository.setLocationSharing(enabled)
                    confirmedSharing = user.locationSharingEnabled
                    _state.update { it.copy(user = user) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val fallback = confirmedSharing ?: atCall.locationSharingEnabled
                    _state.update {
                        it.copy(
                            user = it.user?.copy(locationSharingEnabled = fallback),
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
    }

    fun rename(displayName: String) {
        if (displayName.isBlank()) return
        scope.launch {
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
        scope.launch {
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
     * Local sign-out always succeeds; whether the backend confirmed the
     * revocation is published as an app-scoped notice by the repository,
     * because clearing the session immediately navigation-away disposes this
     * screen. Home state stays free of messages that could vanish unseen.
     */
    fun signOut() {
        if (_state.value.busy) return
        scope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                repository.signOut()
                _state.update { it.copy(busy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update {
                    it.copy(busy = false, error = "Không đăng xuất được. Vui lòng thử lại.")
                }
            }
        }
    }

    fun deleteAccount() {
        if (_state.value.busy) return
        scope.launch {
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
