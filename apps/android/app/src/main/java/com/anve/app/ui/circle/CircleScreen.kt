package com.anve.app.ui.circle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.luminance
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
import com.anve.app.data.circles.InviteLink
import com.anve.app.ui.theme.AnVeTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The circle tab: create or join a circle when the account has none, then
 * members, invites (owner) and leaving. All authorization is server-side;
 * the UI only offers what the role allows.
 */
@Composable
fun CircleScreen(container: AppContainer, invitationId: String? = null, onInvitationConsumed: () -> Unit = {}) {
    val session by container.sessionStore.session.collectAsStateWithLifecycle(initialValue = null)
    val owner = session?.userId
    if (owner == null) {
        Text("Đang kiểm tra phiên đăng nhập. Mở mục Cá nhân nếu chưa tải được.", Modifier.padding(24.dp))
        return
    }
    val viewModel: CircleViewModel = viewModel(
        key = "circle-$owner",
        factory = CircleViewModel.factory(container.circleRepository),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    var showDissolveDialog by remember(owner) { mutableStateOf(false) }
    var showLeaveDialog by remember(owner) { mutableStateOf(false) }
    var removeTarget by remember(owner) { mutableStateOf<MemberDto?>(null) }
    var shareError by remember(owner) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val invitationsController = remember(owner) {
        PrivateInvitationsController(owner, container.sessionStore, container.privateInvitationRepository, scope,
            onCreated = viewModel::refresh, onReview = { code ->
                if (container.sessionStore.current()?.userId == owner) {
                    container.invitationInbox.accountChanged(owner)
                    container.invitationInbox.offer(InviteLink.create(code).orEmpty())
                }
            })
    }
    val privateInvites by invitationsController.state.collectAsStateWithLifecycle()
    val tracking by container.trackingManager.status.collectAsStateWithLifecycle()
    DisposableEffect(invitationsController) { onDispose { invitationsController.dispose() } }
    LaunchedEffect(owner, invitationId) {
        if (invitationId != null) {
            invitationsController.review(invitationId)
            onInvitationConsumed()
        }
    }

    CircleContent(
        state = state.copy(error = shareError ?: state.error),
        memberAvatar = { member ->
            com.anve.app.ui.avatar.AvatarImage(owner, member.userId, member.avatarId, member.displayName,
                container.avatarRepository, Modifier.size(40.dp))
        },
        onCreate = viewModel::create,
        onJoin = { code -> scope.launch {
            try {
                if (container.sessionStore.current()?.userId == owner) {
                    container.invitationInbox.accountChanged(owner)
                    container.invitationInbox.offer(InviteLink.create(code.trim().uppercase()) ?: "")
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                shareError = "Chưa kiểm tra được tài khoản. Mở lại lời mời khi phiên đăng nhập sẵn sàng."
            }
        } },
        onCreateInvite = invitationsController::open,
        receivedContent = { ReceivedInvitations(privateInvites, invitationsController::refresh,
            { invitationsController.review(it) }, { invitationsController.review(it, hide = true) }) },
        onRevokeInvite = viewModel::revokeInvite,
        onRemoveMember = { removeTarget = it },
        onLeave = {
            if (state.isOwner) showDissolveDialog = true else showLeaveDialog = true
        },
        onRetry = viewModel::refresh,
        onShareInvite = { invite -> scope.launch {
            try {
                if (container.sessionStore.current()?.userId != owner) return@launch
                val chooser = invitationShareIntent(invite) ?: return@launch
                context.startActivity(chooser)
                shareError = null
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                shareError = "Không mở được trình chia sẻ. Bạn có thể gửi mã mời đang hiển thị."
            }
        } },
    )
    if (privateInvites.open) EmailInvitationSheet(privateInvites, tracking.online, invitationsController::dismiss,
        invitationsController::mode, invitationsController::email, invitationsController::hours,
        { invitationsController.create(state.circle?.id?.takeIf { state.isOwner }, tracking.online) },
        { invitationsController.checkAgain(tracking.online) })
    InvitationHost(container, owner, onJoined = { joined ->
        invitationsController.refresh()
        if (joined != null) viewModel.acceptJoined(owner, joined) else viewModel.refresh()
    })

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text(stringResource(R.string.circle_leave_title)) },
            text = { Text(stringResource(R.string.circle_leave_body)) },
            confirmButton = { TextButton(onClick = { viewModel.leave(false); showLeaveDialog = false }) { Text(stringResource(R.string.circle_leave_button)) } },
            dismissButton = { TextButton(onClick = { showLeaveDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
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
    onShareInvite: (InviteDto) -> Unit = {},
    memberAvatar: (@Composable (MemberDto) -> Unit)? = null,
    receivedContent: (@Composable () -> Unit)? = null,
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
            CircleSetup(onCreate = onCreate, onJoin = onJoin, busy = state.busy, error = state.error, receivedContent = receivedContent)
            return@Surface
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("circle-content"),
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
            if (receivedContent != null) item { receivedContent() }
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
                    avatar = memberAvatar?.let { { it(member) } },
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
                    InviteRow(invite = invite, onRevoke = { onRevokeInvite(invite.id) }, onShare = { onShareInvite(invite) })
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
    receivedContent: (@Composable () -> Unit)? = null,
) {
    var circleName by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (receivedContent != null) item { receivedContent() }
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
                        Text("Xem lời mời")
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
private fun MemberRow(member: MemberDto, canRemove: Boolean, onRemove: () -> Unit, avatar: (@Composable () -> Unit)? = null) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (avatar != null) avatar() else Box(
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
private fun InviteRow(invite: InviteDto, onRevoke: () -> Unit, onShare: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column {
                Text(
                    invite.code,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Hết hạn: " + runCatching { DateTimeFormatter.ofPattern("dd/MM, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(invite.expiresAt)) }.getOrDefault("Chưa đọc được"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onShare, colors = ButtonDefaults.textButtonColors(contentColor =
                    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary)) { Text("Chia sẻ link mời") }
                TextButton(onClick = onRevoke) {
                Text(
                    stringResource(R.string.circle_invite_revoke),
                    color = MaterialTheme.colorScheme.error,
                )
                }
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
