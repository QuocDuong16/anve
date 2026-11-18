package com.anve.app.ui.circle

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import com.anve.app.ui.brand.AnVeFamilySceneIllustration
import com.anve.app.ui.brand.AnVeIllustratedBanner
import com.anve.app.ui.common.AnVeCheckingState
import com.anve.app.ui.common.AnVeSectionHeader
import com.anve.app.ui.common.AnveErrorNotice
import com.anve.app.ui.common.AnveTime
import com.anve.app.ui.common.StitchBadgeKind
import com.anve.app.ui.common.StitchLoadingSkeletonCard
import com.anve.app.ui.common.StitchSemanticBadge
import com.anve.app.ui.common.anveContentWidth
import com.anve.app.ui.home.HOME_FAB_CLEARANCE
import com.anve.app.ui.theme.AnVeTheme
import com.anve.app.ui.theme.LocalStitchColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The circle tab: create or join a circle when the account has none, then
 * members, invites (owner) and leaving. All authorization is server-side;
 * the UI only offers what the role allows.
 *
 * While onboarding is incomplete, [CircleContent] shows an activation
 * checklist: [onOpenLocation] opens the location screen (consent), [onOpenMap]
 * opens the map (viewer grants live there). The audience step stays hidden once
 * server audience grants are observed, cached per account, or the user
 * explicitly dismisses the checklist ([audienceStepHidden] also hides it
 * immediately within the current session when navigating to the map).
 */
@Composable
fun CircleScreen(
    container: AppContainer,
    invitationId: String? = null,
    onInvitationConsumed: () -> Unit = {},
    onOpenLocation: () -> Unit = {},
    onOpenMap: () -> Unit = {},
    audienceStepHidden: Boolean = false,
    // The tab posts one-shot results into the host shell's snackbar (this
    // content owns no Scaffold); passing the host state down keeps a single
    // snackbar surface above the bottom bar instead of stacking hosts.
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onActivationChecklistVisibleChange: (Boolean) -> Unit = {},
) {
    val session by container.sessionStore.session.collectAsStateWithLifecycle(initialValue = null)
    val owner = session?.userId
    if (owner == null) {
        AnVeCheckingState(stringResource(R.string.invite_session_checking))
        return
    }
    val viewModel: CircleViewModel = viewModel(
        key = "circle-$owner",
        factory = CircleViewModel.factory(
            repository = container.circleRepository,
            ownerId = owner,
            onboardingStore = container.circleOnboardingStore,
            checkHasAudienceGrants = { container.visibleLocationRepository.hasAudienceGrants(owner) },
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(owner) {
        if (state.circle != null && !state.loading) {
            viewModel.refreshAudience()
        }
    }

    var showDissolveDialog by remember(owner) { mutableStateOf(false) }
    var showLeaveDialog by remember(owner) { mutableStateOf(false) }
    var removeTarget by remember(owner) { mutableStateOf<MemberDto?>(null) }
    var shareError by remember(owner) { mutableStateOf<Int?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shareMessage = stringResource(R.string.invite_share_message)
    val shareTitle = stringResource(R.string.invite_share_chooser_title)
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
    // The created-notice is a one-shot result, so it surfaces through the host
    // shell snackbar (DESIGN.md Result Notice Rule) instead of persisting as
    // bare text under the inbox. stringResource is composable-only: the string
    // is hoisted into composition and the effect only reads the value — the
    // same shape as the CircleViewModel notice in CircleContent below.
    val inviteCreatedText = privateInvites.messageRes?.let { stringResource(it) }
    LaunchedEffect(inviteCreatedText) {
        if (inviteCreatedText != null) {
            snackbarHostState.showSnackbar(inviteCreatedText)
            invitationsController.consumeResult()
        }
    }

    val showReceivedInbox = state.circle == null ||
        privateInvites.received.isNotEmpty() ||
        privateInvites.inboxErrorRes != null

    CircleContent(
        state = state,
        // A failed share is not a data-load failure: it gets its own notice
        // without the data-retry affordance (see CircleContent below).
        shareErrorRes = shareError,
        onDismissShareError = { shareError = null },
        memberAvatar = { member ->
            com.anve.app.ui.avatar.AvatarImage(owner, member.userId, member.avatarId, member.displayName,
                container.avatarRepository, Modifier.size(40.dp))
        },
        onCreate = viewModel::create,
        onJoin = { code -> scope.launch {
            try {
                if (container.sessionStore.current()?.userId == owner) {
                    container.invitationInbox.accountChanged(owner)
                    val normalized = InviteLink.extractCode(code) ?: code.trim().uppercase()
                    container.invitationInbox.offer(InviteLink.create(normalized) ?: "")
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                shareError = R.string.invite_join_session_error
            }
        } },
        onCreateInvite = invitationsController::open,
        receivedContent = if (showReceivedInbox) {
            {
                ReceivedInvitations(
                    privateInvites,
                    invitationsController::refresh,
                    { invitationsController.review(it) },
                    { invitationsController.review(it, hide = true) },
                )
            }
        } else {
            null
        },
        onRevokeInvite = viewModel::revokeInvite,
        onRemoveMember = { removeTarget = it },
        onLeave = {
            if (state.isOwner) showDissolveDialog = true else showLeaveDialog = true
        },
        onRetry = {
            viewModel.refresh()
            invitationsController.refresh()
        },
        isRefreshing = state.refreshing,
        onRefresh = {
            viewModel.refresh()
            invitationsController.refresh()
        },
        onShareInvite = { invite -> scope.launch {
            try {
                if (container.sessionStore.current()?.userId != owner) return@launch
                val chooser = invitationShareIntent(invite, shareMessage, shareTitle) ?: return@launch
                context.startActivity(chooser)
                shareError = null
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                shareError = R.string.invite_share_error
            }
        } },
        hasOtherMembers = state.members.any { it.userId != owner },
        consentEnabled = tracking.consentEnabled,
        audienceStepHidden = audienceStepHidden,
        onOpenLocation = onOpenLocation,
        onOpenMap = onOpenMap,
        onDismissChecklist = viewModel::dismissChecklist,
        snackbarHostState = snackbarHostState,
        // Consuming the message must not touch errorRes: the failure notice
        // stays until its own retry/dismiss acts on it.
        onMessageConsumed = viewModel::consumeMessage,
        onChecklistVisibleChange = onActivationChecklistVisibleChange,
        currentUserId = owner,
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
            text = {
                Text(
                    stringResource(R.string.circle_leave_body),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.leave(false); showLeaveDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.circle_leave_button)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showLeaveDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    if (showDissolveDialog) {
        AlertDialog(
            onDismissRequest = { showDissolveDialog = false },
            title = { Text(stringResource(R.string.circle_dissolve_title)) },
            text = {
                Text(
                    stringResource(R.string.circle_dissolve_body),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.leave(true)
                        showDissolveDialog = false
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(R.string.circle_dissolve_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDissolveDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.circle_remove_title, target.displayName)) },
            text = {
                Text(
                    stringResource(R.string.circle_remove_body),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeMember(target.userId)
                        removeTarget = null
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(R.string.circle_remove_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { removeTarget = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
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
    // Share failures render through their own notice (no retry): retrying a
    // data reload would not reopen the sharesheet, and the message's own
    // recovery — the invite code on screen — already stands. Dismiss is the
    // acknowledging action.
    shareErrorRes: Int? = null,
    onDismissShareError: () -> Unit = {},
    memberAvatar: (@Composable (MemberDto) -> Unit)? = null,
    receivedContent: (@Composable () -> Unit)? = null,
    hasOtherMembers: Boolean = false,
    consentEnabled: Boolean = false,
    audienceStepHidden: Boolean = false,
    onOpenLocation: () -> Unit = {},
    onOpenMap: () -> Unit = {},
    onDismissChecklist: () -> Unit = {},
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onMessageConsumed: () -> Unit = {},
    onChecklistVisibleChange: (Boolean) -> Unit = {},
    currentUserId: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    Surface(modifier = Modifier.fillMaxSize()) {
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .anveContentWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    StitchLoadingSkeletonCard(rows = 2)
                    StitchLoadingSkeletonCard(rows = 3)
                    StitchLoadingSkeletonCard(rows = 2)
                }
            }
            return@Surface
        }

        val circle = state.circle
        if (circle == null) {
            CircleSetup(onCreate = onCreate, onJoin = onJoin, busy = state.busy, errorRes = state.errorRes,
                onRetry = onRetry, receivedContent = receivedContent)
            return@Surface
        }

        // One-shot results surface as a snackbar in the host shell (DESIGN.md
        // Result Notice Rule): announced once by M3's own accessibility
        // behavior, never persisted, no dismissal UI — replacing the old
        // persistent primary-text row. An open failure notice wins for the
        // same batch, so the message waits instead of stacking two notices.
        val messageText = state.messageRes?.let { stringResource(it) }
        LaunchedEffect(messageText) {
            if (messageText != null && state.errorRes == null) {
                snackbarHostState.showSnackbar(messageText)
                onMessageConsumed()
            }
        }
        // Keep activation steps visible until each step is actually satisfied
        // (another member joined, sharing consent enabled, server audience
        // grants exist) or the user explicitly dismisses the checklist.
        val effectiveAudienceHidden = audienceStepHidden || state.audienceConfigured
        val showInviteStep = state.isOwner && !hasOtherMembers
        val checklistVisible = state.membersLoaded &&
            state.audienceLoaded &&
            !state.checklistDismissed &&
            (showInviteStep || !consentEnabled || !effectiveAudienceHidden)
        LaunchedEffect(checklistVisible) { onChecklistVisibleChange(checklistVisible) }

        // Revoking an invite code closes a join path a relative could still
        // follow — the same destructive-confirm class as remove, leave,
        // dissolve and hide — so the row button only arms the target here and
        // the dialog below commits. The id is saveable so the dialog survives
        // process death while open, like the hide target in the inbox card.
        var revokeInviteId by rememberSaveable { mutableStateOf<String?>(null) }

        // Pull to refresh mirrors the History and Places tabs: the circle tab's
        // data was previously fetched only at init. The loading and setup
        // branches stay unwrapped — with no circle yet there is nothing to
        // refresh, and setup's error notice already carries its own Thử lại.
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { onRefresh?.invoke() },
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.fillMaxHeight().fillMaxWidth().anveContentWidth().testTag("circle-content"),
                // 72dp bottom clears the shell FAB (56dp container + 16dp gap):
                // the Scaffold floats it over tab content without reserving
                // room, so the last row would sit under the thumb-zone FAB
                // (HOME_FAB_CLEARANCE, same as History/Places).
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = HOME_FAB_CLEARANCE),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val errorRes = state.errorRes
                if (errorRes != null) {
                    item {
                        // Failures use the shared errorContainer notice with
                        // retry, like the home and sign-in screens; one-shot
                        // results go to the shell snackbar above instead of
                        // persisting in the list.
                        AnveErrorNotice(stringResource(errorRes), onRetry = onRetry)
                    }
                }
                if (receivedContent != null) item { receivedContent() }
                item {
                    CircleHeaderCard(
                        name = circle.name,
                        memberCount = state.members.size,
                        isOwner = state.isOwner,
                        onLeave = onLeave,
                    )
                }
                if (checklistVisible) {
                    item {
                        CircleActivationChecklist(
                            showInvite = showInviteStep,
                            showSharing = !consentEnabled,
                            showAudience = !effectiveAudienceHidden,
                            onInvite = onCreateInvite,
                            onOpenLocation = onOpenLocation,
                            onOpenMap = onOpenMap,
                            onDismiss = onDismissChecklist,
                        )
                    }
                }
                item {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.circle_members_title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                        StitchSemanticBadge(
                            text = stringResource(R.string.circle_member_count_pill, state.members.size),
                            kind = StitchBadgeKind.SAFE_HOME,
                        )
                    }
                }
                itemsIndexed(state.members, key = { _, it -> it.userId }) { index, member ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (index > 0) {
                            HorizontalDivider(color = stitch.hairline)
                        }
                        MemberRow(
                            member = member,
                            isSelf = currentUserId != null && member.userId == currentUserId,
                            avatar = memberAvatar?.let { { it(member) } },
                            canRemove = state.isOwner && member.role != "owner",
                            onRemove = { onRemoveMember(member) },
                        )
                    }
                }
                if (state.isOwner) {
                    shareErrorRes?.let { res ->
                        item { AnveErrorNotice(stringResource(res), onDismiss = onDismissShareError) }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                itemVerticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.circle_invites_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.semantics { heading() },
                                )
                                StitchSemanticBadge(
                                    text = stringResource(R.string.circle_invites_count_pill, state.invites.size),
                                    kind = StitchBadgeKind.NEUTRAL,
                                )
                            }
                            Text(
                                text = stringResource(R.string.circle_invite_section_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                    itemsIndexed(state.invites, key = { _, it -> it.id }) { index, invite ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (index > 0) {
                                HorizontalDivider(color = stitch.hairline)
                            }
                            InviteRow(invite = invite, busy = state.busy, onRevoke = { revokeInviteId = invite.id }, onShare = { onShareInvite(invite) })
                        }
                    }
                    item {
                        Button(
                            onClick = onCreateInvite,
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) {
                            Icon(Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.circle_invite_create))
                        }
                    }
                }
            }
            }
        }
        revokeInviteId?.let { target ->
            AlertDialog(
                onDismissRequest = { revokeInviteId = null },
                shape = RoundedCornerShape(24.dp),
                title = { Text(stringResource(R.string.circle_invite_revoke_title)) },
                text = {
                    Text(
                        stringResource(R.string.circle_invite_revoke_body),
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onRevokeInvite(target)
                            revokeInviteId = null
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(
                            stringResource(R.string.circle_invite_revoke),
                            color = colors.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { revokeInviteId = null },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun CircleSetup(
    onCreate: (String) -> Unit,
    onJoin: (String) -> Unit,
    busy: Boolean,
    errorRes: Int?,
    onRetry: () -> Unit = {},
    receivedContent: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    var circleName by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .anveContentWidth()
                .imePadding(),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = HOME_FAB_CLEARANCE),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (receivedContent != null) item { receivedContent() }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AnVeIllustratedBanner {
                        AnVeFamilySceneIllustration(
                            modifier = Modifier.fillMaxWidth().height(132.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.circle_setup_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                Text(
                    text = stringResource(R.string.circle_setup_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        if (errorRes != null) {
            item {
                // Setup failures stay actionable too: the same errorContainer
                // notice and retry affordance as the loaded-circle branch.
                AnveErrorNotice(stringResource(errorRes), onRetry = onRetry)
            }
        }
        item {
            OutlinedCard(
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, stitch.hairline),
                colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AnVeSectionHeader(
                        title = stringResource(R.string.circle_create_title),
                    )
                    OutlinedTextField(
                        value = circleName,
                        onValueChange = { circleName = it },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        label = { Text(stringResource(R.string.circle_name_label)) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { onCreate(circleName) },
                        enabled = !busy && circleName.isNotBlank(),
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        Text(stringResource(R.string.circle_create_button))
                    }
                }
            }
        }
        item {
            OutlinedCard(
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, stitch.hairline),
                colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AnVeSectionHeader(
                        title = stringResource(R.string.circle_join_title),
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.uppercase() },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        label = { Text(stringResource(R.string.circle_code_label)) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { onJoin(code) },
                        enabled = !busy && code.isNotBlank(),
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        Text(stringResource(R.string.invite_view))
                    }
                    Text(
                        text = stringResource(R.string.circle_join_caption),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Text(
                text = stringResource(R.string.circle_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        }
    }
}

@Composable
private fun CircleHeaderCard(name: String, memberCount: Int, isOwner: Boolean, onLeave: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    val largeFont = LocalDensity.current.fontScale > 1.25f
    OutlinedCard(
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, stitch.hairline),
        colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            AnVeIllustratedBanner(
                overlayBadge = {
                    StitchSemanticBadge(
                        text = stringResource(R.string.circle_member_count_pill, memberCount),
                        kind = StitchBadgeKind.SAFE_HOME,
                        showDot = true,
                    )
                },
            ) {
                AnVeFamilySceneIllustration(
                    modifier = Modifier.fillMaxWidth().height(124.dp),
                )
            }
            BoxWithConstraints(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                val stackLeave = maxWidth < 340.dp || largeFont
                val headerInfo: @Composable (Modifier) -> Unit = { colMod ->
                    Column(colMod, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FlowRow(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                name,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 3,
                            )
                            StitchSemanticBadge(
                                text = if (isOwner) {
                                    stringResource(R.string.circle_role_owner)
                                } else {
                                    stringResource(R.string.circle_role_member)
                                },
                                kind = if (isOwner) StitchBadgeKind.AT_PLACE else StitchBadgeKind.NEUTRAL,
                            )
                        }
                    }
                }
                val leaveButton: @Composable (Modifier) -> Unit = { btnMod ->
                    TextButton(onClick = onLeave, modifier = btnMod.heightIn(min = 48.dp)) {
                        Text(
                            text = stringResource(
                                if (isOwner) R.string.circle_dissolve_button else R.string.circle_leave_button,
                            ),
                            color = colors.error,
                        )
                    }
                }
                if (stackLeave) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        headerInfo(Modifier.fillMaxWidth())
                        leaveButton(Modifier.align(Alignment.End))
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        headerInfo(Modifier.weight(1f))
                        leaveButton(Modifier)
                    }
                }
            }
        }
    }
}

/** One activation step; step numbers are fixed, so they live in the copy. */
private data class CircleActivationStep(
    val title: String,
    val supporting: String,
    val actionLabel: String,
    val action: () -> Unit,
)

/**
 * Post-circle-creation activation: the three steps that make family location
 * sharing real (invite, consent, viewer grant), each collapsing on actual
 * progress instead of being dismissed. The first remaining step is the
 * primary action; later steps stay outlined.
 */
@Composable
private fun CircleActivationChecklist(
    showInvite: Boolean,
    showSharing: Boolean,
    showAudience: Boolean,
    onInvite: () -> Unit,
    onOpenLocation: () -> Unit,
    onOpenMap: () -> Unit,
    onDismiss: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    val inviteTitle = stringResource(R.string.circle_activation_invite_title)
    val inviteBody = stringResource(R.string.circle_activation_invite_body)
    val inviteAction = stringResource(R.string.circle_activation_invite_action)
    val shareTitle = stringResource(R.string.circle_activation_share_title)
    val shareBody = stringResource(R.string.circle_activation_share_body)
    val shareAction = stringResource(R.string.circle_activation_share_action)
    val audienceTitle = stringResource(R.string.circle_activation_audience_title)
    val audienceBody = stringResource(R.string.circle_activation_audience_body)
    val audienceAction = stringResource(R.string.circle_activation_audience_action)
    val steps = buildList {
        if (showInvite) {
            add(CircleActivationStep(inviteTitle, inviteBody, inviteAction, onInvite))
        }
        if (showSharing) {
            add(CircleActivationStep(shareTitle, shareBody, shareAction, onOpenLocation))
        }
        if (showAudience) {
            add(CircleActivationStep(audienceTitle, audienceBody, audienceAction, onOpenMap))
        }
    }
    if (steps.isEmpty()) return
    val total = 3
    // Steps already done collapse away, so the remaining count is the honest
    // progress: "Bước 2/3" appears once the first step has been completed.
    val currentStep = total - steps.size + 1
    OutlinedCard(
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, stitch.hairline),
        colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StitchSemanticBadge(
                    text = stringResource(R.string.circle_activation_badge),
                    kind = StitchBadgeKind.IN_TRANSIT,
                )
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.circle_activation_dismiss))
                }
            }
            Text(
                stringResource(R.string.circle_activation_warm_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.circle_activation_progress, currentStep.coerceAtMost(total), total),
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary,
            )
            LinearProgressIndicator(
                progress = { (currentStep - 1).coerceIn(0, total) / total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            steps.forEachIndexed { index, step ->
                HorizontalDivider(color = stitch.hairline)
                CircleActivationStepRow(
                    stepNumber = total - steps.size + index + 1,
                    title = step.title,
                    supporting = step.supporting,
                    actionLabel = step.actionLabel,
                    primary = index == 0,
                    action = step.action,
                )
            }
        }
    }
}

@Composable
private fun CircleActivationStepRow(
    stepNumber: Int,
    title: String,
    supporting: String,
    actionLabel: String,
    primary: Boolean,
    action: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    val largeFont = LocalDensity.current.fontScale > 1.25f
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        val stackCta = maxWidth < 340.dp || largeFont
        val stepBadge: @Composable () -> Unit = {
            Surface(
                shape = CircleShape,
                color = if (primary) colors.primaryContainer else colors.surface,
                contentColor = if (primary) colors.onPrimaryContainer else colors.onSurfaceVariant,
                border = if (primary) null else BorderStroke(1.dp, stitch.hairline),
                modifier = Modifier.defaultMinSize(minWidth = 32.dp, minHeight = 32.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                    Text(
                        text = stepNumber.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        val stepAction: @Composable (Modifier) -> Unit = { btnMod ->
            if (primary) {
                Button(
                    onClick = action,
                    shape = CircleShape,
                    modifier = btnMod.heightIn(min = 48.dp),
                ) {
                    Text(actionLabel)
                }
            } else {
                OutlinedButton(
                    onClick = action,
                    shape = CircleShape,
                    modifier = btnMod.heightIn(min = 48.dp),
                ) {
                    Text(actionLabel)
                }
            }
        }
        if (stackCta) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    stepBadge()
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            supporting,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                stepAction(Modifier.fillMaxWidth())
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                stepBadge()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                stepAction(Modifier)
            }
        }
    }
}

@Composable
private fun MemberRow(
    member: MemberDto,
    isSelf: Boolean,
    canRemove: Boolean,
    onRemove: () -> Unit,
    avatar: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val isOwner = member.role == "owner"
    val largeFont = LocalDensity.current.fontScale > 1.25f
    BoxWithConstraints(modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth()) {
        val stackRemove = canRemove && (maxWidth < 320.dp || largeFont)
        val memberDetails: @Composable (Modifier) -> Unit = { rowMod ->
            Row(
                modifier = rowMod,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (avatar != null) {
                    avatar()
                } else {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .background(
                                color = colors.secondaryContainer,
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            member.displayName.take(1).uppercase().ifBlank { "?" },
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSecondaryContainer,
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FlowRow(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            member.displayName,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (isSelf) {
                            StitchSemanticBadge(
                                text = stringResource(R.string.circle_self_badge),
                                kind = StitchBadgeKind.NEUTRAL,
                            )
                        }
                    }
                    StitchSemanticBadge(
                        text = if (isOwner) {
                            stringResource(R.string.circle_role_owner)
                        } else {
                            stringResource(R.string.circle_role_member)
                        },
                        kind = if (isOwner) StitchBadgeKind.AT_PLACE else StitchBadgeKind.SAFE_HOME,
                    )
                }
            }
        }
        val removeButton: @Composable (Modifier) -> Unit = { btnMod ->
            OutlinedButton(
                onClick = onRemove,
                shape = CircleShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
                modifier = btnMod.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.circle_remove_short))
            }
        }
        if (stackRemove) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                memberDetails(Modifier.fillMaxWidth())
                removeButton(Modifier.fillMaxWidth())
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                memberDetails(Modifier.weight(1f))
                if (canRemove) {
                    removeButton(Modifier)
                }
            }
        }
    }
}

@Composable
private fun InviteRow(invite: InviteDto, busy: Boolean, onRevoke: () -> Unit, onShare: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FlowRow(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = colors.primaryContainer,
                contentColor = colors.onPrimaryContainer,
            ) {
                Text(
                    text = invite.code,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Text(
                text = stringResource(
                    R.string.invite_expiry_prefix,
                    AnveTime.full(invite.expiresAt, stringResource(R.string.invite_expiry_unreadable)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = onShare,
                    shape = CircleShape,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.invite_share_link))
                }
                // The row button only arms the confirm dialog; once confirmed,
                // revocation still runs through setBusy like the other
                // mutations, so the button locks to prevent a double-tap
                // double revoke.
                TextButton(
                    onClick = onRevoke,
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(R.string.circle_invite_revoke),
                        color = colors.error,
                    )
                }
            }
    }
}

@Preview(showBackground = true)
@Composable
private fun CircleSetupPreview() {
    AnVeTheme {
        CircleSetup(onCreate = {}, onJoin = {}, busy = false, errorRes = null)
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
            hasOtherMembers = true,
            currentUserId = "u1",
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CircleActiveDarkPreview() {
    AnVeTheme(darkTheme = true) {
        CircleContent(
            state = CircleUiState(
                loading = false,
                circle = CircleDto("c1", "Gia đình mình", "u1", "2026-10-25T00:00:00Z"),
                myRole = "owner",
                members = listOf(
                    MemberDto("u1", "Mai", "owner", "2026-10-25T00:00:00Z"),
                    MemberDto("u2", "Bố", "member", "2026-10-25T01:00:00Z"),
                ),
            ),
            onCreate = {},
            onJoin = {},
            onCreateInvite = {},
            onRevokeInvite = {},
            onRemoveMember = {},
            onLeave = {},
            onRetry = {},
            hasOtherMembers = true,
            currentUserId = "u1",
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CircleActivationPreview() {
    AnVeTheme {
        CircleContent(
            state = CircleUiState(
                loading = false,
                circle = CircleDto("c1", "Gia đình mình", "u1", "2026-10-25T00:00:00Z"),
                myRole = "owner",
                members = listOf(
                    MemberDto("u1", "Mai", "owner", "2026-10-25T00:00:00Z"),
                ),
                membersLoaded = true,
            ),
            onCreate = {},
            onJoin = {},
            onCreateInvite = {},
            onRevokeInvite = {},
            onRemoveMember = {},
            onLeave = {},
            onRetry = {},
            hasOtherMembers = false,
            consentEnabled = false,
            audienceStepHidden = false,
            onOpenLocation = {},
            onOpenMap = {},
        )
    }
}
