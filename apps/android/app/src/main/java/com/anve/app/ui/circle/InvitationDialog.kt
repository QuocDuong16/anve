package com.anve.app.ui.circle

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.anve.app.R
import com.anve.app.data.api.InvitePreviewDto
import com.anve.app.ui.common.AnveErrorNotice
import com.anve.app.ui.common.AnveTime
import com.anve.app.ui.common.StitchBadgeKind
import com.anve.app.ui.common.StitchSemanticBadge
import com.anve.app.ui.theme.AnVeTheme
import com.anve.app.ui.theme.LocalStitchColors
import com.anve.app.ui.theme.isDarkSurface
import com.anve.app.ui.theme.rememberInkOnSurface

@Composable
fun InvitationDialog(
    state: InvitationUiState,
    online: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onCheck: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    val dark = isDarkSurface()
    val action = rememberInkOnSurface()
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(26.dp),
        icon = {
            Icon(
                imageVector = Icons.Outlined.Email,
                contentDescription = null,
                tint = colors.primary,
            )
        },
        title = { Text(stringResource(R.string.invite_dialog_title)) },
        containerColor = stitch.cardTactile,
        tonalElevation = 0.dp,
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("invitation-details"),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (state.loading) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.invite_dialog_checking), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                state.preview?.let { preview ->
                    OutlinedCard(
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, stitch.hairline),
                        colors = androidx.compose.material3.CardDefaults.outlinedCardColors(
                            containerColor = colors.surfaceContainerLow,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(preview.circleName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                                StitchSemanticBadge(
                                    text = stringResource(R.string.circle_role_member),
                                    kind = StitchBadgeKind.SAFE_HOME,
                                )
                            }
                            InvitationDetail(Icons.Outlined.Email, stringResource(R.string.invite_single_use))
                            val expiry = AnveTime.full(preview.expiresAt, stringResource(R.string.invite_expiry_unreadable_detail))
                            InvitationDetail(Icons.Outlined.DateRange, stringResource(R.string.invite_expiry_prefix, expiry))
                        }
                    }
                }
                Surface(
                    color = if (dark) colors.secondary else colors.secondaryContainer,
                    contentColor = if (dark) colors.onSecondary else colors.onSecondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Info, null)
                        Text(stringResource(R.string.invite_join_consent_note), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (state.accountLabel.isNotEmpty()) {
                    HorizontalDivider(color = colors.onSurface.copy(alpha = 0.16f))
                    InvitationDetail(Icons.Outlined.Person, stringResource(R.string.invite_logged_in_as, state.accountLabel))
                }
                if (!online) Text(stringResource(R.string.invite_dialog_offline), style = MaterialTheme.typography.bodyMedium)
                state.errorRes?.let { res -> AnveErrorNotice(stringResource(res)) }
                if (state.busy) Text(stringResource(R.string.invite_dialog_busy), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            when {
                state.unknown -> TextButton(onClick = onCheck, enabled = online && !state.busy, colors = ButtonDefaults.textButtonColors(contentColor = action)) {
                    Text(stringResource(R.string.invite_check_result))
                }
                state.retryable -> TextButton(onClick = onRetry, enabled = online && !state.loading && !state.busy, colors = ButtonDefaults.textButtonColors(contentColor = action)) {
                    Text(stringResource(R.string.action_retry))
                }
                state.preview != null && !state.blocked -> Button(
                    onClick = onConfirm,
                    enabled = online && !state.loading && !state.busy,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (dark) colors.primaryContainer else colors.primary,
                        contentColor = if (dark) colors.onPrimaryContainer else colors.onPrimary,
                    ),
                ) {
                    Text(stringResource(R.string.invite_dialog_join))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.invite_dialog_later), color = action) }
        },
    )
}

@Composable
private fun InvitationDetail(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview
@Composable
private fun InvitationPreview() {
    AnVeTheme { InvitationDialog(InvitationUiState(loading = false, preview = InvitePreviewDto("example", "Nhà mình", "2026-11-14T13:00:00Z"), accountLabel = "Dương · duong@example.test"), true, {}, {}, {}, {}) }
}
