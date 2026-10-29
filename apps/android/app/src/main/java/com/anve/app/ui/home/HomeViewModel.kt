package com.anve.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anve.app.data.api.ApiException
import com.anve.app.data.api.DeviceDto
import com.anve.app.data.api.UserDto
import com.anve.app.data.auth.AuthRepository
import com.anve.app.data.auth.hasConsentConflict
import com.anve.app.tracking.ConsentController
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
    val locationSharingRequested: Boolean? = null,
    val consentSyncPending: Boolean = false,
    val consentConflict: Boolean = false,
    val consentBusy: Boolean = false,
)

class HomeViewModel internal constructor(
    private val repository: AuthRepository,
    private val consentController: ConsentController,
    externalScope: CoroutineScope? = null,
) : ViewModel() {

    /**
     * Production uses `viewModelScope`; tests inject their own scope so they
     * never mutate the global Main dispatcher while a coroutine is alive.
     */
    private val scope: CoroutineScope = externalScope ?: viewModelScope

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var consentGeneration = 0L
    private val consentRequestOrder = Mutex()

    init {
        refresh()
    }

    fun refresh() {
        val generationAtStart = consentGeneration
        scope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val user = repository.me()
                val devices = repository.devices().devices
                val pendingConsent = repository.pendingConsentOperation(user.id)
                val consentConflict = pendingConsent?.hasConsentConflict == true
                // Keep the local consent mirror in step with the server so
                // the tracking engine can start offline from confirmed state.
                consentController.recordServerConsent(user)
                _state.update {
                    val newerIntentExists = consentGeneration != generationAtStart || it.consentBusy
                    val displayedUser = it.user?.takeIf {
                        it.locationSharingVersion > user.locationSharingVersion
                    } ?: user
                    it.copy(
                        loading = false,
                        user = displayedUser,
                        devices = devices,
                        locationSharingRequested = if (newerIntentExists) {
                            it.locationSharingRequested
                        } else if (consentConflict) {
                            user.locationSharingEnabled
                        } else {
                            pendingConsent?.desiredEnabled ?: user.locationSharingEnabled
                        },
                        consentSyncPending = if (newerIntentExists) {
                            it.consentSyncPending
                        } else {
                            pendingConsent != null
                        },
                        consentConflict = if (newerIntentExists) it.consentConflict else consentConflict,
                        message = if (consentConflict && !newerIntentExists) {
                            "Chia sẻ đã thay đổi trên một thiết bị khác. Chọn lại trạng thái để xác nhận quyết định mới."
                        } else {
                            it.message
                        },
                    )
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
        if (_state.value.user == null) return
        val generation = ++consentGeneration
        _state.update {
            it.copy(
                locationSharingRequested = enabled,
                consentBusy = true,
                consentSyncPending = true,
                consentConflict = false,
                error = null,
                message = null,
            )
        }
        scope.launch {
            consentRequestOrder.withLock {
                try {
                    val result = consentController.setConsent(enabled)
                    _state.update { current ->
                        val responseVersion = result.user?.locationSharingVersion
                        val displayedVersion = current.user?.locationSharingVersion ?: -1L
                        val serverUser = if (responseVersion != null && responseVersion >= displayedVersion) {
                            result.user
                        } else {
                            current.user
                        }
                        if (generation == consentGeneration) {
                            current.copy(
                                user = serverUser,
                                locationSharingRequested = if (result.conflict) {
                                    result.user?.locationSharingEnabled ?: current.user?.locationSharingEnabled
                                } else {
                                    result.enabled
                                },
                                consentSyncPending = !result.serverConfirmed && result.pendingOperationPersisted,
                                consentConflict = result.conflict,
                                consentBusy = false,
                                message = result.message,
                            )
                        } else {
                            current.copy(user = serverUser)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (generation == consentGeneration) {
                        _state.update {
                            it.copy(
                                locationSharingRequested = it.user?.locationSharingEnabled,
                                consentSyncPending = false,
                                consentConflict = false,
                                consentBusy = false,
                                error = if (enabled) {
                                    "Không bật được chia sẻ vị trí. Vui lòng thử lại."
                                } else {
                                    "Đã dừng theo dõi trên thiết bị này; chưa xác nhận được với máy chủ."
                                },
                            )
                        }
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
        while (true) {
            val current = _state.value
            if (current.busy || current.user == null) return
            if (_state.compareAndSet(current, current.copy(busy = true, error = null))) break
        }
        scope.launch {
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
        fun factory(
            repository: AuthRepository,
            consentController: ConsentController,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HomeViewModel(repository, consentController) as T
        }
    }
}
