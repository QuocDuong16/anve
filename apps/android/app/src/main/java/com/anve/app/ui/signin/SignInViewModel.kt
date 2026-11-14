package com.anve.app.ui.signin

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anve.app.R
import com.anve.app.data.api.ApiException
import com.anve.app.data.auth.AuthRepository
import com.anve.app.ui.common.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the sign-in screen is showing. */
sealed interface SignInUiState {
    /** Checking which methods the server offers. */
    data object Loading : SignInUiState

    data class Ready(
        val googleSignIn: Boolean,
        val devLogin: Boolean,
    ) : SignInUiState

    data class SignedIn(val displayName: String) : SignInUiState
}

/**
 * Sign-in flow: fetch the server's auth config, then either exchange a
 * Google ID token (obtained by the screen through Credential Manager) or
 * use the development login when the server mounts it.
 */
class SignInViewModel internal constructor(
    private val repository: AuthRepository,
    externalScope: CoroutineScope? = null,
) : ViewModel() {

    /** Production uses `viewModelScope`; tests inject a disposable scope. */
    private val scope: CoroutineScope = externalScope ?: viewModelScope

    private val _state = MutableStateFlow<SignInUiState>(SignInUiState.Loading)
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    /** Failure of the last attempt, if any, as resource-backed copy. */
    private val _error = MutableStateFlow<UiMessage?>(null)
    val error: StateFlow<UiMessage?> = _error.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        refreshConfig()
    }

    fun refreshConfig() {
        scope.launch {
            _state.value = SignInUiState.Loading
            try {
                val config = repository.authConfig()
                _state.value = SignInUiState.Ready(
                    googleSignIn = config.googleSignIn,
                    devLogin = config.devLogin,
                )
            } catch (e: Exception) {
                _error.value = friendly(e)
                _state.value = SignInUiState.Ready(googleSignIn = false, devLogin = false)
            }
        }
    }

    /** The screen has already obtained the Google ID token. */
    fun signInWithGoogleToken(idToken: String) {
        signInWithGoogle({ Result.success(idToken) }, { R.string.signin_credential_incomplete })
    }

    /** Own the whole attempt so leaving the composition for Google's UI does not lose its result. */
    fun signInWithGoogle(fetchIdToken: suspend () -> Result<String>, credentialError: (Throwable) -> Int) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        scope.launch {
            try {
                val credential = fetchIdToken()
                val failure = credential.exceptionOrNull()
                if (failure is CancellationException) throw failure
                if (failure != null) {
                    _error.value = UiMessage(credentialError(failure))
                    return@launch
                }
                val idToken = credential.getOrThrow()
                val user = repository.signInWithGoogleToken(idToken)
                _state.update { SignInUiState.SignedIn(user.displayName) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = friendly(e)
            } finally {
                _busy.value = false
            }
        }
    }

    fun signInDev(email: String) {
        if (_busy.value || email.isBlank()) return
        scope.launch {
            _busy.value = true
            _error.value = null
            try {
                val user = repository.signInDev(email.trim())
                _state.update { SignInUiState.SignedIn(user.displayName) }
            } catch (e: Exception) {
                _error.value = friendly(e)
            } finally {
                _busy.value = false
            }
        }
    }

    fun consumeError() {
        _error.value = null
    }

    /** Surfaces an error produced outside the repository (e.g. Credential
     *  Manager itself failed before any request was made). */
    fun reportError(@StringRes resId: Int) {
        _error.value = UiMessage(resId)
    }

    /** Maps contract errors to resource-backed copy; unknown codes keep the server's message. */
    private fun friendly(e: Exception): UiMessage = when (e) {
        is ApiException -> when (e.code) {
            "UNAUTHENTICATED" -> UiMessage(R.string.signin_unauthenticated)
            "AUTH_NOT_CONFIGURED" -> UiMessage(R.string.signin_auth_not_configured)
            "RATE_LIMITED" -> UiMessage(R.string.signin_rate_limited)
            "VALIDATION" -> UiMessage(R.string.signin_validation_failed)
            else -> UiMessage(R.string.signin_server_error, listOf(e.message ?: ""))
        }
        else -> UiMessage(R.string.signin_network_failed)
    }

    companion object {
        fun factory(repository: AuthRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SignInViewModel(repository) as T
            }
    }
}
