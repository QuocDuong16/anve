package com.anve.app.ui.circle

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anve.app.R
import com.anve.app.ui.common.AnVeSectionHeader
import com.anve.app.ui.common.AnveErrorNotice
import com.anve.app.ui.common.AnveTime
import com.anve.app.ui.common.StitchBadgeKind
import com.anve.app.ui.common.StitchSemanticBadge
import com.anve.app.ui.theme.LocalStitchColors
import com.anve.app.ui.theme.rememberInkOnSurface

@Composable
fun ReceivedInvitations(state: PrivateInvitationsState, onRefresh: () -> Unit, onReview: (String) -> Unit, onHide: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    val actionColor = rememberInkOnSurface()
    // Hiding is a server mutation with no undo in the UI, so it confirms first —
    // the same destructive-confirm pattern as leave/dissolve/remove. The target
    // is saveable so the dialog survives process death while open.
    var hideTarget by rememberSaveable { mutableStateOf<String?>(null) }
    OutlinedCard(
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, stitch.hairline),
        colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
        modifier = Modifier.fillMaxWidth().testTag("received-invitations"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                    AnVeSectionHeader(
                        title = stringResource(R.string.invite_received_title),
                    )
                }
                if (state.received.isNotEmpty()) {
                    StitchSemanticBadge(
                        text = state.received.size.toString(),
                        kind = StitchBadgeKind.IN_TRANSIT,
                    )
                }
                IconButton(
                    onClick = onRefresh,
                    enabled = !state.loading && !state.detailBusy,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.invite_reload),
                        tint = actionColor,
                    )
                }
            }
            if (state.loading) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.action_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            } else if (state.received.isEmpty() && state.inboxErrorRes == null) {
                Text(
                    text = stringResource(R.string.invite_received_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            state.inboxErrorRes?.let { res -> AnveErrorNotice(stringResource(res), onRetry = onRefresh) }
            state.received.forEachIndexed { index, invite ->
                if (index > 0) {
                    HorizontalDivider(color = stitch.hairline)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(invite.circleName, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.invite_received_intro, invite.inviterDisplayName), style = MaterialTheme.typography.bodyMedium)
                    val expiry = AnveTime.full(invite.expiresAt, stringResource(R.string.invite_expiry_unknown))
                    Text(
                        stringResource(R.string.invite_expiry_prefix, expiry),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = { onReview(invite.id) },
                            enabled = !state.detailBusy,
                            shape = CircleShape,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.invite_view))
                        }
                        TextButton(
                            onClick = { hideTarget = invite.id },
                            enabled = !state.detailBusy,
                            colors = ButtonDefaults.textButtonColors(contentColor = actionColor),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.invite_hide))
                        }
                    }
                }
            }
        }
    }
    hideTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { hideTarget = null },
            shape = RoundedCornerShape(24.dp),
            title = { Text(stringResource(R.string.invite_hide_title)) },
            text = {
                Text(
                    stringResource(R.string.invite_hide_body),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onHide(target)
                        hideTarget = null
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.error,
                        contentColor = colors.onError,
                    ),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.invite_hide_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { hideTarget = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
