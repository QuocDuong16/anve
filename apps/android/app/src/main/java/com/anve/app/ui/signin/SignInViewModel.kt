package com.anve.app.ui.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anve.app.data.api.ApiException
import com.anve.app.data.auth.AuthRepository
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

    /** Human-readable failure of the last attempt, if any. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

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
        if (_busy.value) return
        scope.launch {
            _busy.value = true
            _error.value = null
            try {
                val user = repository.signInWithGoogleToken(idToken)
                _state.update { SignInUiState.SignedIn(user.displayName) }
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
    fun reportError(message: String) {
        _error.value = message
    }

    companion object {
        fun friendly(e: Exception): String = when (e) {
            is ApiException -> when (e.code) {
                "UNAUTHENTICATED" -> "Đăng nhập không thành công. Vui lòng thử lại."
                "AUTH_NOT_CONFIGURED" ->
                    "Máy chủ chưa cấu hình đăng nhập Google. Hãy xem hướng dẫn thiết lập."
                "RATE_LIMITED" -> "Bạn đã thử quá nhiều lần. Vui lòng đợi một lát."
                "VALIDATION" -> "Thông tin đăng nhập chưa hợp lệ."
                else -> "Máy chủ báo lỗi: ${e.message}"
            }
            else -> "Không kết nối được máy chủ. Kiểm tra mạng và thử lại."
        }

        fun factory(repository: AuthRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SignInViewModel(repository) as T
            }
    }
}
