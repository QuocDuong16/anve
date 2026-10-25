package com.anve.app.ui.signin

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anve.app.AppContainer
import com.anve.app.BuildConfig
import com.anve.app.R
import com.anve.app.ui.theme.AnVeTheme

import kotlinx.coroutines.launch


/**
 * The sign-in screen: Google Sign-In when both the server and this install
 * support it, the development login in debug builds when the server mounts
 * it. Never fakes a signed-in state.
 */
@Composable
fun SignInScreen(
    container: AppContainer,
    onSignedIn: () -> Unit,
) {
    val viewModel: SignInViewModel = viewModel(
        factory = SignInViewModel.factory(container.authRepository),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(state) {
        if (state is SignInUiState.SignedIn) onSignedIn()
    }

    SignInContent(
        state = state,
        error = error,
        busy = busy,
        onGoogleSignIn = {
            val activity = context.findActivity()
            if (activity == null) {
                viewModel.reportError("Không mở được trình đăng nhập trên màn hình này.")
                return@SignInContent
            }
            scope.launch {
                val result = GoogleCredentialSource(activity).fetchIdToken()
                result.fold(
                    onSuccess = { viewModel.signInWithGoogleToken(it) },
                    onFailure = { cause ->
                        // Cancelling the account sheet is a normal exit, not
                        // an error the user needs to hear about.
                        if (cause !is GetCredentialCancellationException) {
                            viewModel.reportError(
                                "Không lấy được tài khoản Google: ${cause.message ?: "lỗi không rõ"}",
                            )
                        }
                    },
                )
            }
        },
        onDevSignIn = { viewModel.signInDev(it) },
        onRetry = viewModel::refreshConfig,
    )
}

@Composable
fun SignInContent(
    state: SignInUiState,
    error: String?,
    busy: Boolean,
    onGoogleSignIn: () -> Unit,
    onDevSignIn: (String) -> Unit,
    onRetry: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        when (state) {
            is SignInUiState.Loading -> Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }

            is SignInUiState.Ready -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    text = stringResource(R.string.splash_tagline),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.height(32.dp))

                if (error != null) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                }

                if (busy) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.height(16.dp))
                }

                if (state.googleSignIn) {
                    Button(
                        onClick = onGoogleSignIn,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.signin_google))
                    }
                    Text(
                        text = stringResource(R.string.signin_google_caption),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.signin_google_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.signin_retry))
                    }
                }

                if (state.devLogin && BuildConfig.DEBUG) {
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(24.dp))
                    DevLoginForm(enabled = !busy, onSignIn = onDevSignIn)
                }
            }

            is SignInUiState.SignedIn -> Unit
        }
    }
}

@Composable
private fun DevLoginForm(enabled: Boolean, onSignIn: (String) -> Unit) {
    var email by remember { mutableStateOf("") }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.signin_dev_title),
            style = MaterialTheme.typography.titleSmall,
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.signin_dev_email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 12.dp),
        )
        Button(
            onClick = { onSignIn(email) },
            enabled = enabled && email.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.signin_dev_button))
        }
    }
}

/** Walks up the context chain to the hosting Activity, if any. */
fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

@Preview(showBackground = true)
@Composable
private fun SignInContentGooglePreview() {
    AnVeTheme {
        SignInContent(
            state = SignInUiState.Ready(googleSignIn = true, devLogin = false),
            error = null,
            busy = false,
            onGoogleSignIn = {},
            onDevSignIn = {},
            onRetry = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SignInContentUnavailablePreview() {
    AnVeTheme {
        SignInContent(
            state = SignInUiState.Ready(googleSignIn = false, devLogin = false),
            error = "Không kết nối được máy chủ.",
            busy = false,
            onGoogleSignIn = {},
            onDevSignIn = {},
            onRetry = {},
        )
    }
}
