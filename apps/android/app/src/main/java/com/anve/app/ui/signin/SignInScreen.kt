package com.anve.app.ui.signin

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import com.anve.app.data.auth.SessionNotice
import com.anve.app.data.auth.SessionNoticeKind
import com.anve.app.ui.brand.AnVeBrandMark
import com.anve.app.ui.brand.AnVeFamilySceneIllustration
import com.anve.app.ui.common.AnveErrorNotice
import com.anve.app.ui.common.StitchLoadingSkeletonCard
import com.anve.app.ui.common.UiMessage
import com.anve.app.ui.common.anveContentWidth
import com.anve.app.ui.theme.AnVeTheme
import com.anve.app.ui.theme.LocalStitchColors


/**
 * The sign-in screen: Google Sign-In when both the server and this install
 * support it, the development login in debug builds when the server mounts
 * it. Never fakes a signed-in state.
 */
@Composable
fun SignInScreen(
    container: AppContainer,
    onSignedIn: () -> Unit,
    invitationPending: Boolean = false,
) {
    val viewModel: SignInViewModel = viewModel(
        factory = SignInViewModel.factory(container.authRepository),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val notice by container.sessionNotices.notice.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state) {
        if (state is SignInUiState.SignedIn) onSignedIn()
    }

    SignInContent(
        invitationPending = invitationPending,
        state = state,
        error = error,
        busy = busy,
        notice = notice,
        onDismissNotice = { notice?.let { container.sessionNotices.dismiss(it.id) } },
        onGoogleSignIn = {
            val activity = context.findActivity()
            if (activity == null) {
                viewModel.reportError(R.string.signin_no_activity_resolver)
                return@SignInContent
            }
            viewModel.signInWithGoogle(
                fetchIdToken = { GoogleCredentialSource(activity).fetchIdToken() },
                credentialError = { cause ->
                    // Providers also use cancellation for authorization/configuration
                    // failures, so a cancellation alone cannot prove the user
                    // dismissed the chooser on every OEM. Closing the chooser is
                    // still the user's own act, not a failure: nothing surfaces
                    // and the sign-in button simply stays. A real configuration
                    // failure keeps coming back on the next attempt through this
                    // same path, so it is never silently lost.
                    if (cause is GetCredentialCancellationException) null
                    else R.string.signin_generic_error
                },
            )
        },
        onDevSignIn = { viewModel.signInDev(it) },
        onRetry = viewModel::refreshConfig,
    )
}

@Composable
fun SignInContent(
    state: SignInUiState,
    error: UiMessage?,
    busy: Boolean,
    notice: SessionNotice?,
    onDismissNotice: () -> Unit,
    onGoogleSignIn: () -> Unit,
    onDevSignIn: (String) -> Unit,
    onRetry: () -> Unit,
    invitationPending: Boolean = false,
) {
    val stitch = LocalStitchColors.current
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state) {
            is SignInUiState.Loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .anveContentWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    StitchLoadingSkeletonCard(rows = 2)
                }
            }

            is SignInUiState.Ready -> {
                val colors = MaterialTheme.colorScheme
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .anveContentWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AnVeFamilySceneIllustration(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(104.dp)
                                .clip(RoundedCornerShape(18.dp)),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AnVeBrandMark(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .background(stitch.secondaryFixed)
                                    .border(BorderStroke(1.5.dp, stitch.hairline), CircleShape)
                                    .padding(6.dp),
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.app_name),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.primary,
                                    modifier = Modifier.semantics { heading() },
                                )
                                Text(
                                    text = stringResource(R.string.splash_tagline),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }

                        if (invitationPending) {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = stitch.transitContainer,
                                contentColor = stitch.onTransitContainer,
                                border = BorderStroke(1.dp, stitch.hairline),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.MailOutline,
                                        contentDescription = null,
                                        modifier = Modifier.padding(top = 2.dp).size(20.dp),
                                    )
                                    Text(
                                        text = stringResource(R.string.signin_invitation_prompt),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }

                        if (notice != null) {
                            SignOutNoticeCard(notice = notice, onDismiss = onDismissNotice)
                        }

                        if (error != null) {
                            // No retry inside the notice: the main button below already
                            // relabels to "Thử lại" while an error shows, so a second
                            // retry control would duplicate the recovery action.
                            // Live region lives inside AnveErrorNotice now; declaring it
                            // here too would double-announce the same failure.
                            AnveErrorNotice(
                                text = stringResource(error.id, *error.args.toTypedArray()),
                            )
                        }

                        if (busy) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                                Text(
                                    text = stringResource(R.string.signin_in_progress),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .semantics { liveRegion = LiveRegionMode.Polite },
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (state.googleSignIn) {
                                Button(
                                    onClick = onGoogleSignIn,
                                    enabled = !busy,
                                    shape = CircleShape,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                ) {
                                    Text(stringResource(if (error == null) R.string.signin_google else R.string.signin_retry))
                                }
                                Text(
                                    text = stringResource(R.string.signin_google_caption),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.signin_google_unavailable),
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                )
                                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.signin_retry))
                                }
                            }
                        }

                        if (state.devLogin && BuildConfig.DEBUG) {
                            HorizontalDivider(color = stitch.hairline)
                            DevLoginForm(enabled = !busy, onSignIn = onDevSignIn)
                        }
                    }
                }
            }

            is SignInUiState.SignedIn -> Unit
        }
    }
}

@Composable
private fun SignOutNoticeCard(notice: SessionNotice, onDismiss: () -> Unit) {
    val stitch = LocalStitchColors.current
    val text = when (notice.kind) {
        SessionNoticeKind.LocalSignOutRetryScheduled ->
            stringResource(R.string.signin_local_signout_retry)
        SessionNoticeKind.LocalSignOutRetryUnavailable ->
            stringResource(R.string.signin_local_signout_no_retry)
    }
    Surface(
        color = stitch.transitContainer,
        contentColor = stitch.onTransitContainer,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, stitch.hairline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.action_dismiss))
            }
        }
    }
}

@Composable
private fun DevLoginForm(enabled: Boolean, onSignIn: (String) -> Unit) {
    var email by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.signin_dev_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.signin_dev_email)) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSignIn(email) },
            enabled = enabled && email.isNotBlank(),
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
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

@Preview(name = "1. Standard Light", showBackground = true)
@Composable
private fun SignInContentGooglePreview() {
    AnVeTheme {
        SignInContent(
            state = SignInUiState.Ready(googleSignIn = true, devLogin = false),
            error = null,
            busy = false,
            notice = null,
            onDismissNotice = {},
            onGoogleSignIn = {},
            onDevSignIn = {},
            onRetry = {},
        )
    }
}

@Preview(name = "2. Dark Mode", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SignInContentGoogleDarkPreview() {
    AnVeTheme(darkTheme = true) {
        SignInContent(
            state = SignInUiState.Ready(googleSignIn = true, devLogin = false),
            error = null,
            busy = false,
            notice = null,
            onDismissNotice = {},
            onGoogleSignIn = {},
            onDevSignIn = {},
            onRetry = {},
        )
    }
}

@Preview(name = "3. Elderly 200% Font", fontScale = 2.0f, showBackground = true)
@Composable
private fun SignInContentGoogleElderlyFontPreview() {
    AnVeTheme {
        SignInContent(
            state = SignInUiState.Ready(googleSignIn = true, devLogin = false),
            error = null,
            busy = false,
            notice = null,
            onDismissNotice = {},
            onGoogleSignIn = {},
            onDevSignIn = {},
            onRetry = {},
            invitationPending = true,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SignInContentUnavailablePreview() {
    AnVeTheme {
        SignInContent(
            state = SignInUiState.Ready(googleSignIn = false, devLogin = false),
            error = UiMessage(R.string.signin_network_failed),
            busy = false,
            notice = null,
            onDismissNotice = {},
            onGoogleSignIn = {},
            onDevSignIn = {},
            onRetry = {},
        )
    }
}
