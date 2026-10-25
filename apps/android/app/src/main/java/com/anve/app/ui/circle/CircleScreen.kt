package com.anve.app.ui.circle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anve.app.AppContainer
import com.anve.app.R
import com.anve.app.data.api.CircleDto
import com.anve.app.data.api.InviteDto
import com.anve.app.data.api.MemberDto
import com.anve.app.ui.theme.AnVeTheme

/**
 * The circle tab: create or join a circle when the account has none, then
 * members, invites (owner) and leaving. All authorization is server-side;
 * the UI only offers what the role allows.
 */
@Composable
fun CircleScreen(container: AppContainer) {
    val viewModel: CircleViewModel = viewModel(
        factory = CircleViewModel.factory(container.circleRepository),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    var showDissolveDialog by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<MemberDto?>(null) }

    CircleContent(
        state = state,
        onCreate = viewModel::create,
        onJoin = viewModel::join,
        onCreateInvite = viewModel::createInvite,
        onRevokeInvite = viewModel::revokeInvite,
        onRemoveMember = { removeTarget = it },
        onLeave = {
            if (state.isOwner && state.members.size > 1) showDissolveDialog = true else viewModel.leave(false)
        },
        onRetry = viewModel::refresh,
    )

    if (showDissolveDialog) {
        AlertDialog(
            onDismissRequest = { showDissolveDialog = false },
            title = { Text(stringResource(R.string.circle_dissolve_title)) },
            text = { Text(stringResource(R.string.circle_dissolve_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.leave(true)
                    showDissolveDialog = false
                }) {
                    Text(
                        stringResource(R.string.circle_dissolve_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDissolveDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.circle_remove_title, target.displayName)) },
            text = { Text(stringResource(R.string.circle_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeMember(target.userId)
                    removeTarget = null
                }) {
                    Text(
                        stringResource(R.string.circle_remove_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
fun CircleContent(
    state: CircleUiState,
    onCreate: (String) -> Unit,
    onJoin: (String) -> Unit,
    onCreateInvite: () -> Unit,
    onRevokeInvite: (String) -> Unit,
    onRemoveMember: (MemberDto) -> Unit,
    onLeave: () -> Unit,
    onRetry: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Surface
        }

        val circle = state.circle
        if (circle == null) {
            CircleSetup(onCreate = onCreate, onJoin = onJoin, busy = state.busy, error = state.error)
            return@Surface
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.error != null || state.message != null) {
                item {
                    Text(
                        text = state.error ?: state.message ?: "",
                        color = if (state.error != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item { CircleHeaderCard(name = circle.name, isOwner = state.isOwner, onLeave = onLeave) }
            item {
                Text(
                    text = stringResource(R.string.circle_members_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(state.members, key = { it.userId }) { member ->
                MemberRow(
                    member = member,
                    canRemove = state.isOwner && member.role != "owner",
                    onRemove = { onRemoveMember(member) },
                )
            }
            if (state.isOwner) {
                item {
                    Text(
                        text = stringResource(R.string.circle_invites_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(state.invites, key = { it.id }) { invite ->
                    InviteRow(invite = invite, onRevoke = { onRevokeInvite(invite.id) })
                }
                item {
                    OutlinedButton(onClick = onCreateInvite, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.circle_invite_create))
                    }
                }
            }
        }
    }
}

@Composable
private fun CircleSetup(
    onCreate: (String) -> Unit,
    onJoin: (String) -> Unit,
    busy: Boolean,
    error: String?,
) {
    var circleName by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.circle_setup_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.circle_setup_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (error != null) {
            item {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.circle_create_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedTextField(
                        value = circleName,
                        onValueChange = { circleName = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.circle_name_label)) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { onCreate(circleName) },
                        enabled = !busy && circleName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.circle_create_button))
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.circle_join_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.uppercase() },
                        singleLine = true,
                        label = { Text(stringResource(R.string.circle_code_label)) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { onJoin(code) },
                        enabled = !busy && code.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.circle_join_button))
                    }
                    Text(
                        text = stringResource(R.string.circle_join_caption),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Text(
                text = stringResource(R.string.circle_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CircleHeaderCard(name: String, isOwner: Boolean, onLeave: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = if (isOwner) {
                        stringResource(R.string.circle_role_owner)
                    } else {
                        stringResource(R.string.circle_role_member)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onLeave) {
                Text(
                    text = stringResource(
                        if (isOwner) R.string.circle_dissolve_button else R.string.circle_leave_button,
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun MemberRow(member: MemberDto, canRemove: Boolean, onRemove: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    member.displayName.take(1).uppercase().ifBlank { "?" },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(member.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (member.role == "owner") {
                        stringResource(R.string.circle_role_owner)
                    } else {
                        stringResource(R.string.circle_role_member)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (canRemove) {
                TextButton(onClick = onRemove) {
                    Text(
                        stringResource(R.string.circle_remove_short),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun InviteRow(invite: InviteDto, onRevoke: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    invite.code,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.circle_invite_expires),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRevoke) {
                Text(
                    stringResource(R.string.circle_invite_revoke),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CircleSetupPreview() {
    AnVeTheme {
        CircleSetup(onCreate = {}, onJoin = {}, busy = false, error = null)
    }
}

@Preview(showBackground = true)
@Composable
private fun CircleActivePreview() {
    AnVeTheme {
        CircleContent(
            state = CircleUiState(
                loading = false,
                circle = CircleDto("c1", "Gia đình mình", "u1", "2026-10-25T00:00:00Z"),
                myRole = "owner",
                members = listOf(
                    MemberDto("u1", "Mai", "owner", "2026-10-25T00:00:00Z"),
                    MemberDto("u2", "Bố", "member", "2026-10-25T01:00:00Z"),
                ),
                invites = listOf(
                    InviteDto("i1", "c1", "ABCD234567", "u1", "2026-10-27T00:00:00Z", "2026-10-25T00:00:00Z"),
                ),
            ),
            onCreate = {},
            onJoin = {},
            onCreateInvite = {},
            onRevokeInvite = {},
            onRemoveMember = {},
            onLeave = {},
            onRetry = {},
        )
    }
}
