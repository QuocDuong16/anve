package com.anve.app.ui.update

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.anve.app.R
import com.anve.app.ui.common.AnveErrorNotice
import com.anve.app.ui.theme.AnVeTheme
import com.anve.app.ui.theme.LocalStitchColors
import com.anve.app.ui.theme.isDarkSurface
import com.anve.app.ui.theme.rememberInkOnSurface

/**
 * Blocking update modal triggered only when Google Play reports a newer release.
 * Back press and outside taps are disabled so deferring the update explicitly
 * exits the Activity via lifecycle-safe termination.
 */
@Composable
fun ForceUpdateDialog(
    state: AppUpdateUiState,
    onConfirmUpdate: () -> Unit,
    onOpenPlayStore: () -> Unit,
    onExitApp: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    val dark = isDarkSurface()
    val actionColor = rememberInkOnSurface()
    val busy = state.launchingUpdate || state.flowInProgress

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        shape = RoundedCornerShape(26.dp),
        containerColor = stitch.cardTactile,
        tonalElevation = 0.dp,
        modifier = Modifier.testTag("force-update-dialog"),
        icon = {
            Icon(
                imageVector = Icons.Outlined.SystemUpdate,
                contentDescription = null,
                tint = colors.primary,
            )
        },
        title = {
            Text(
                text = stringResource(R.string.app_update_title),
                textAlign = TextAlign.Center,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.app_update_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                if (state.launchingUpdate) {
                    Text(
                        text = stringResource(R.string.app_update_launching),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                state.errorRes?.let { res ->
                    AnveErrorNotice(stringResource(res))
                    TextButton(
                        onClick = onOpenPlayStore,
                        colors = ButtonDefaults.textButtonColors(contentColor = actionColor),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("force-update-open-play"),
                    ) {
                        Text(stringResource(R.string.app_update_open_play))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmUpdate,
                enabled = !busy,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (dark) colors.primaryContainer else colors.primary,
                    contentColor = if (dark) colors.onPrimaryContainer else colors.onPrimary,
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("force-update-confirm"),
            ) {
                Text(stringResource(R.string.app_update_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onExitApp,
                colors = ButtonDefaults.textButtonColors(contentColor = actionColor),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("force-update-later"),
            ) {
                Text(stringResource(R.string.app_update_later))
            }
        },
    )
}

@Preview(name = "1. Standard Light", showBackground = true)
@Composable
private fun ForceUpdateDialogLightPreview() {
    AnVeTheme(darkTheme = false) {
        ForceUpdateDialog(
            state = AppUpdateUiState(modalVisible = true, availableVersionCode = 12),
            onConfirmUpdate = {},
            onOpenPlayStore = {},
            onExitApp = {},
        )
    }
}

@Preview(name = "2. Dark Mode", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun ForceUpdateDialogDarkPreview() {
    AnVeTheme(darkTheme = true) {
        ForceUpdateDialog(
            state = AppUpdateUiState(modalVisible = true, availableVersionCode = 12),
            onConfirmUpdate = {},
            onOpenPlayStore = {},
            onExitApp = {},
        )
    }
}

@Preview(name = "3. Elderly 200% Font", fontScale = 2.0f, showBackground = true)
@Composable
private fun ForceUpdateDialogElderlyFontPreview() {
    AnVeTheme(darkTheme = false) {
        ForceUpdateDialog(
            state = AppUpdateUiState(modalVisible = true, availableVersionCode = 12),
            onConfirmUpdate = {},
            onOpenPlayStore = {},
            onExitApp = {},
        )
    }
}
