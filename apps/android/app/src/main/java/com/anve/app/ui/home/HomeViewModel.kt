package com.anve.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anve.app.R
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
    /** Outcome copy this ViewModel owns; the screen resolves it from resources. */
    val messageRes: Int? = null,
    /**
     * Consent outcome text handed over by the tracking layer (`ConsentResult`).
     * That contract still reports resolved copy, so it passes through verbatim
     * until `ConsentResult` itself becomes resource-backed.
     */
    val consentMessage: String? = null,
    val errorRes: Int? = null,
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
            _state.update { it.copy(loading = true, errorRes = null) }
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
                        messageRes = if (consentConflict && !newerIntentExists) {
                            R.string.home_conflict_reload_notice
                        } else {
                            it.messageRes
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
                        errorRes = R.string.home_load_failed_retry,
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
                errorRes = null,
                messageRes = null,
                consentMessage = null,
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
                                consentMessage = result.message,
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
                                errorRes = if (enabled) R.string.home_enable_failed else R.string.home_disable_unconfirmed,
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
                _state.update { it.copy(user = user, consentMessage = null, messageRes = R.string.home_rename_done) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorRes = R.string.home_rename_failed) }
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
                        consentMessage = null,
                        messageRes = R.string.home_device_revoked,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorRes = R.string.home_device_revoke_failed) }
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
            _state.update { it.copy(busy = true, errorRes = null) }
            try {
                repository.signOut()
                _state.update { it.copy(busy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update {
                    it.copy(busy = false, errorRes = R.string.home_sign_out_failed)
                }
            }
        }
    }

    fun deleteAccount() {
        while (true) {
            val current = _state.value
            if (current.busy || current.user == null) return
            if (_state.compareAndSet(current, current.copy(busy = true, errorRes = null))) break
        }
        scope.launch {
            try {
                repository.deleteAccount()
                // Confirmed: storage is cleared and AnVeApp reroutes.
                _state.update { HomeUiState(loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _state.update { it.copy(busy = false, errorRes = deleteErrorMessage(e.code)) }
            } catch (e: IOException) {
                // Unknown outcome: the server may have deleted the account
                // without the response arriving. Keep the session and
                // reconcile with an authenticated read — if the account is
                // gone, the read is rejected and the interceptor clears the
                // local session; otherwise the user simply retries.
                _state.update {
                    it.copy(
                        busy = false,
                        errorRes = R.string.home_delete_unconfirmed,
                    )
                }
                reconcileAfterUnknownDeletion()
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, errorRes = R.string.home_delete_failed) }
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

    private fun deleteErrorMessage(code: String): Int = when (code) {
        "CIRCLE_HAS_MEMBERS" -> R.string.home_delete_circle_has_members
        "UNAUTHENTICATED" -> R.string.home_delete_unauthenticated
        else -> R.string.home_delete_failed
    }

    fun consumeMessage() {
        _state.update { it.copy(messageRes = null, consentMessage = null, errorRes = null) }
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
