package com.anve.app.ui.circle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anve.app.R
import com.anve.app.ui.common.AnveErrorNotice
import com.anve.app.ui.common.AnveTime
import com.anve.app.ui.theme.rememberInkOnSurface

@Composable
fun ReceivedInvitations(state: PrivateInvitationsState, onRefresh: () -> Unit, onReview: (String) -> Unit, onHide: (String) -> Unit) {
    val actionColor = rememberInkOnSurface()
    // Hiding is a server mutation with no undo in the UI, so it confirms first —
    // the same destructive-confirm pattern as leave/dissolve/remove. The target
    // is saveable so the dialog survives process death while open.
    var hideTarget by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().testTag("received-invitations"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.invite_received_title), style = MaterialTheme.typography.titleMedium)
        if (state.loading) CircularProgressIndicator()
        else if (state.received.isEmpty() && state.inboxErrorRes == null) Text(stringResource(R.string.invite_received_empty), style = MaterialTheme.typography.bodyMedium)
        state.inboxErrorRes?.let { res -> AnveErrorNotice(stringResource(res)) }
        state.received.forEach { invite ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(invite.circleName, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.invite_received_intro, invite.inviterDisplayName), style = MaterialTheme.typography.bodyMedium)
                val expiry = AnveTime.full(invite.expiresAt, stringResource(R.string.invite_expiry_unknown))
                Text(stringResource(R.string.invite_expiry_prefix, expiry), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onReview(invite.id) }, enabled = !state.detailBusy,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = actionColor),
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.invite_view)) }
                    TextButton(onClick = { hideTarget = invite.id }, enabled = !state.detailBusy,
                        colors = ButtonDefaults.textButtonColors(contentColor = actionColor),
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.invite_hide)) }
                }
            }
        }
        TextButton(onClick = onRefresh, enabled = !state.loading && !state.detailBusy,
            colors = ButtonDefaults.textButtonColors(contentColor = actionColor), modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.invite_reload)) }
    }
    hideTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { hideTarget = null },
            title = { Text(stringResource(R.string.invite_hide_title)) },
            text = { Text(stringResource(R.string.invite_hide_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onHide(target)
                    hideTarget = null
                }) {
                    Text(
                        stringResource(R.string.invite_hide_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { hideTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
