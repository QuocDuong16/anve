package com.anve.app.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anve.app.AppContainer
import com.anve.app.R
import com.anve.app.data.api.DeviceDto
import com.anve.app.data.api.DeviceSessionDto
import com.anve.app.data.api.UserDto
import com.anve.app.data.sos.IncomingPush
import com.anve.app.data.sos.pushDestination
import com.anve.app.ui.avatar.AvatarEditorHost
import com.anve.app.ui.avatar.AvatarImage
import com.anve.app.ui.brand.AnVeAccountShieldIllustration
import com.anve.app.ui.brand.AnVeBrandMark
import com.anve.app.ui.circle.CircleScreen
import com.anve.app.ui.common.AnVeSectionHeader
import com.anve.app.ui.common.AnVeSosPillButton
import com.anve.app.ui.common.AnveErrorNotice
import com.anve.app.ui.common.StitchBadgeKind
import com.anve.app.ui.common.StitchEmptyStateCard
import com.anve.app.ui.common.StitchLiveSharingPill
import com.anve.app.ui.common.StitchLoadingSkeletonCard
import com.anve.app.ui.common.StitchSemanticBadge
import com.anve.app.ui.diagnostics.DiagnosticsScreen
import com.anve.app.ui.history.HistoryScreen
import com.anve.app.ui.map.LiveMapScreen
import com.anve.app.ui.places.PlacesScreen
import com.anve.app.ui.sos.SosScreen
import com.anve.app.ui.theme.AnVeTheme
import com.anve.app.ui.theme.LocalStitchColors
import com.anve.app.ui.tracking.TrackingScreen
import java.io.Serializable

/**
 * Primary home destinations in the bottom NavigationBar, plus secondary account
 * and emergency routes hosted inside the shell.
 */
private enum class HomeTab(val labelRes: Int) {
    MAP(R.string.tab_map),
    CIRCLE(R.string.tab_circle),
    PLACES(R.string.tab_places),
    HISTORY(R.string.tab_history),
}

private enum class HomeSecondaryPage(val titleRes: Int) {
    ACCOUNT(R.string.tab_account),
    SHARING(R.string.sharing_title),
    SOS(R.string.tab_sos),
}

// Navigation state persists as enum names so a rotation or process death
// restores the open screen; a name from an older install that is no longer
// in the enum set falls back instead of crashing on restore.
private val homeTabSaver = Saver<HomeTab, String>(
    save = { it.name },
    restore = { name -> HomeTab.entries.firstOrNull { it.name == name } ?: HomeTab.CIRCLE },
)

// A null state value is not persisted (the saver returns null, which the
// saveable registry skips), so restore falls back to the init default.
private val homeSecondaryPageSaver = Saver<HomeSecondaryPage?, String>(
    save = { it?.name },
    restore = { name -> HomeSecondaryPage.entries.firstOrNull { it.name == name } },
)

private val nullableStringSaver = Saver<String?, String>(
    save = { it },
    restore = { it },
)

/**
 * Where the user was before a push deep-link rewrote navigation. Pushes
 * overwrite tab, secondary page and selections without going through a back
 * stack, so this snapshot is the only way back. Saved as one Serializable
 * value with the default bundle saver (every field is an enum, String or
 * Boolean) so the whole pre-push state survives rotation and process death
 * together instead of restoring half-applied from separate saveables.
 */
private data class PrePushNav(
    val tab: HomeTab,
    val secondaryPage: HomeSecondaryPage?,
    val sosReturnPage: HomeSecondaryPage?,
    val showDiagnostics: Boolean,
    val showJourneys: Boolean,
    val selectedJourney: String?,
    val selectedInvitation: String?,
    val selectedSos: String?,
) : Serializable {
    companion object {
        /**
         * A fresh session's navigation. A push arriving while the user is
         * here displaces nothing — the destination already is where they
         * would return to — so no "Quay lại" offer is needed.
         */
        val DEFAULT = PrePushNav(
            tab = HomeTab.CIRCLE,
            secondaryPage = null,
            sosReturnPage = null,
            showDiagnostics = false,
            showJourneys = false,
            selectedJourney = null,
            selectedInvitation = null,
            selectedSos = null,
        )
    }
}

/**
 * Material Scaffold floats the SOS pill above the bottom bar without adding it
 * to the content padding, so scrollable tab content clears it on its own.
 */
internal val HOME_FAB_CLEARANCE = 64.dp

/**
 * The signed-in surface keeps five primary destinations and routes secondary
 * account and SOS screens above the selected destination.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(container: AppContainer, incomingPush: IncomingPush? = null, onPushConsumed: () -> Unit = {}) {
    var tab by rememberSaveable(stateSaver = homeTabSaver) { mutableStateOf(HomeTab.CIRCLE) }
    var secondaryPage: HomeSecondaryPage? by rememberSaveable(stateSaver = homeSecondaryPageSaver) { mutableStateOf<HomeSecondaryPage?>(null) }
    var sosReturnPage: HomeSecondaryPage? by rememberSaveable(stateSaver = homeSecondaryPageSaver) { mutableStateOf<HomeSecondaryPage?>(null) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var showJourneys by rememberSaveable { mutableStateOf(false) }
    var openMapAudience by rememberSaveable { mutableStateOf(false) }
    var selectedJourney: String? by rememberSaveable(stateSaver = nullableStringSaver) { mutableStateOf<String?>(null) }
    var selectedInvitation: String? by rememberSaveable(stateSaver = nullableStringSaver) { mutableStateOf<String?>(null) }
    var selectedSos: String? by rememberSaveable(stateSaver = nullableStringSaver) { mutableStateOf<String?>(null) }
    // Set exactly once per applied push, so the snackbar effect below shows
    // at most once per push and the snapshot survives rotation.
    var prePushNav: PrePushNav? by rememberSaveable { mutableStateOf<PrePushNav?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    // The circle activation checklist cannot observe viewer grants from its
    // own state, so the "choose viewers" step hides once the user has gone to
    // the map. Held here (not in CircleScreen) so it survives tab switches.
    var circleAudienceStepHidden by remember { mutableStateOf(false) }
    val session by container.sessionStore.session.collectAsStateWithLifecycle(initialValue = null)
    val invitation by container.invitationInbox.invitation.collectAsStateWithLifecycle()
    LaunchedEffect(invitation?.id, invitation?.owner, session?.userId) {
        if (invitation != null && invitation?.owner == session?.userId && session?.userId != null) {
            showDiagnostics = false
            showJourneys = false
            secondaryPage = null
            tab = HomeTab.CIRCLE
        }
    }
    // A recreated Activity lands on this effect while the session flow has
    // not re-emitted yet (initialValue null), then again when it re-emits the
    // same user. Resetting on either frame would discard the navigation state
    // rememberSaveable just restored, so only a real user change resets.
    var resetForUserId by rememberSaveable(stateSaver = nullableStringSaver) { mutableStateOf<String?>(null) }
    LaunchedEffect(session?.userId) {
        val userId = session?.userId
        if (userId == null || userId == resetForUserId) return@LaunchedEffect
        resetForUserId = userId
        showDiagnostics = false
        showJourneys = false
        selectedJourney = null
        selectedSos = null
        secondaryPage = null
        sosReturnPage = null
        selectedInvitation = null
        circleAudienceStepHidden = false
        openMapAudience = false
        prePushNav = null
        tab = HomeTab.CIRCLE
    }
    LaunchedEffect(incomingPush, session?.userId) {
        val hint = incomingPush ?: return@LaunchedEffect
        val owner = session?.userId ?: return@LaunchedEffect
        val destination = pushDestination(hint.kind, hint.eventId, hint.ownerId, owner)
        if (destination != null) {
            // Capture where the user actually was before the push rewrites
            // navigation. Pushes bypass the back stack, so this snapshot plus
            // the snackbar action below is the only way back to it.
            prePushNav = PrePushNav(
                tab,
                secondaryPage,
                sosReturnPage,
                showDiagnostics,
                showJourneys,
                selectedJourney,
                selectedInvitation,
                selectedSos,
            )
            showDiagnostics = false
            showJourneys = destination.kind in com.anve.app.data.sos.JOURNEY_PUSH_KINDS
            selectedJourney = if (showJourneys) destination.eventId else null
            selectedSos = if (destination.kind == "SOS") destination.eventId else null
            secondaryPage = if (destination.kind == "SOS") HomeSecondaryPage.SOS else null
            sosReturnPage = null
            selectedInvitation = if (destination.kind == "CIRCLE_INVITE") destination.eventId else null
            tab = when {
                showJourneys -> HomeTab.MAP
                destination.kind == "SOS" || destination.kind == "CIRCLE_INVITE" -> HomeTab.CIRCLE
                else -> HomeTab.PLACES
            }
        }
        onPushConsumed()
    }
    // One snackbar per applied push: name that a notification moved the user
    // and offer the pre-push screen back once. Resolved in composition — the
    // snackbar call runs in a coroutine (SOS snackbar pattern). Journey and
    // diagnostics pushes cover the shell with a full-screen overlay, so the
    // snackbar surfaces when the user is back in the shell.
    val pushOpenedMessage = stringResource(R.string.home_push_opened)
    val pushBackAction = stringResource(R.string.home_push_back)
    LaunchedEffect(prePushNav) {
        val saved = prePushNav ?: return@LaunchedEffect
        // Only a push that actually displaced the user offers a way back; from
        // the default screen the destination is its own return, and a no-op
        // action would just be noise.
        if (saved == PrePushNav.DEFAULT) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(pushOpenedMessage, actionLabel = pushBackAction)
        if (result == SnackbarResult.ActionPerformed) {
            // Restore the whole pre-push navigation atomically; clearing the
            // snapshot afterwards keeps one offer per push.
            prePushNav?.let {
                tab = it.tab
                secondaryPage = it.secondaryPage
                sosReturnPage = it.sosReturnPage
                showDiagnostics = it.showDiagnostics
                showJourneys = it.showJourneys
                selectedJourney = it.selectedJourney
                selectedInvitation = it.selectedInvitation
                selectedSos = it.selectedSos
            }
        }
        prePushNav = null
    }
    if (showJourneys) {
        com.anve.app.ui.journey.JourneyScreen(
            container,
            onBack = { showJourneys = false },
            onPlaces = { showJourneys = false; secondaryPage = null; tab = HomeTab.PLACES },
            onLocation = { showJourneys = false; secondaryPage = HomeSecondaryPage.SHARING },
            // The overlay Scaffold carries its own SOS FAB; opening SOS leaves
            // the overlay (the journey keeps running server-side), and the
            // return lands on the shell tabs.
            onOpenMap = { showJourneys = false; secondaryPage = null; tab = HomeTab.MAP },
            onOpenSos = { showJourneys = false; sosReturnPage = null; selectedSos = null; secondaryPage = HomeSecondaryPage.SOS },
            journeyId = selectedJourney,
        )
        return
    }
    if (showDiagnostics) {
        BackHandler { showDiagnostics = false }
        DiagnosticsScreen(container = container, onBack = { showDiagnostics = false })
        return
    }
    val returnFromSecondaryPage = {
        secondaryPage = if (secondaryPage == HomeSecondaryPage.SOS) sosReturnPage else null
        sosReturnPage = null
        selectedSos = null
    }
    BackHandler(enabled = secondaryPage != null, onBack = returnFromSecondaryPage)
    Scaffold(
        // Hosts the push deep-link snackbar plus one-shot results posted from
        // tab content (Nhóm) that has no Scaffold of its own; screens owning
        // a Scaffold (History, Places, SOS) keep their own hosts (DESIGN.md).
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            secondaryPage?.let { page ->
                TopAppBar(
                    title = { Text(stringResource(page.titleRes)) },
                    navigationIcon = {
                        IconButton(onClick = returnFromSecondaryPage) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }
        },
        bottomBar = {
            val stitch = LocalStitchColors.current
            Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(color = stitch.hairline)
                NavigationBar(
                    containerColor = stitch.cardTactile,
                    tonalElevation = 0.dp,
                ) {
                    val navColors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HomeTab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = secondaryPage == null && tab == entry,
                            onClick = {
                                tab = entry
                                secondaryPage = null
                                sosReturnPage = null
                                selectedSos = null
                            },
                            colors = navColors,
                            icon = {
                                // The label below already announces the tab; an
                                // icon contentDescription would make TalkBack read
                                // the same text twice (M3 labeled-destination rule).
                                Icon(
                                    imageVector = when (entry) {
                                        HomeTab.MAP -> Icons.Outlined.Map
                                        HomeTab.CIRCLE -> Icons.Outlined.Groups
                                        HomeTab.PLACES -> Icons.Outlined.Place
                                        HomeTab.HISTORY -> Icons.Outlined.History
                                    },
                                    contentDescription = null,
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(entry.labelRes),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (secondaryPage != HomeSecondaryPage.SOS) {
                AnVeSosPillButton(
                    onClick = {
                        sosReturnPage = secondaryPage
                        selectedSos = null
                        secondaryPage = HomeSecondaryPage.SOS
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (secondaryPage) {
                HomeSecondaryPage.ACCOUNT -> AccountContent(
                    container = container,
                    onOpenTracking = {
                        secondaryPage = HomeSecondaryPage.SHARING
                    },
                    onOpenDiagnostics = { showDiagnostics = true },
                )
                HomeSecondaryPage.SHARING -> TrackingScreen(
                    container = container,
                    onOpenDiagnostics = { showDiagnostics = true },
                    onOpenJourney = {
                        selectedJourney = null
                        showJourneys = true
                    },
                    onOpenAudience = {
                        openMapAudience = true
                        tab = HomeTab.MAP
                        secondaryPage = null
                    },
                )
                HomeSecondaryPage.SOS -> SosScreen(container = container, eventId = selectedSos)
                null -> Column(Modifier.fillMaxSize()) {
                    val trackingStatus by container.trackingManager.status.collectAsStateWithLifecycle()
                    AnVeShellHeader(
                        sharing = trackingStatus.activelyTracking,
                        onOpenAccount = { secondaryPage = HomeSecondaryPage.ACCOUNT },
                        onOpenSharing = { secondaryPage = HomeSecondaryPage.SHARING },
                    )
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        when (tab) {
                            HomeTab.MAP -> LiveMapScreen(
                                container = container,
                                onOpenLocation = {
                                    secondaryPage = HomeSecondaryPage.SHARING
                                },
                                onOpenCircle = {
                                    secondaryPage = null
                                    tab = HomeTab.CIRCLE
                                },
                                onOpenJourney = {
                                    selectedJourney = null
                                    showJourneys = true
                                },
                                openSharingRequest = openMapAudience,
                                onSharingRequestConsumed = { openMapAudience = false },
                            )
                            HomeTab.CIRCLE -> CircleScreen(
                                container = container,
                                invitationId = selectedInvitation,
                                onInvitationConsumed = { selectedInvitation = null },
                                onOpenLocation = {
                                    secondaryPage = HomeSecondaryPage.SHARING
                                },
                                onOpenMap = {
                                    circleAudienceStepHidden = true
                                    openMapAudience = true
                                    tab = HomeTab.MAP
                                    secondaryPage = null
                                    sosReturnPage = null
                                    selectedSos = null
                                },
                                audienceStepHidden = circleAudienceStepHidden,
                                snackbarHostState = snackbarHostState,
                            )
                            HomeTab.PLACES -> PlacesScreen(
                                container = container,
                                onOpenLocation = {
                                    secondaryPage = HomeSecondaryPage.SHARING
                                },
                            )
                            HomeTab.HISTORY -> HistoryScreen(container = container)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnVeShellHeader(sharing: Boolean, onOpenAccount: () -> Unit, onOpenSharing: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    Surface(
        color = colors.background,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = colors.primaryContainer.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, stitch.hairline),
                    modifier = Modifier.size(42.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        AnVeBrandMark(Modifier.size(34.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_brand_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.home_brand_tagline),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FilledTonalButton(
                    onClick = onOpenAccount,
                    shape = CircleShape,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = colors.secondaryContainer,
                        contentColor = colors.onSecondaryContainer,
                    ),
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.tab_account), maxLines = 1)
                }
            }
            // Full-width pinned Stitch status pill keeping sharing toggle
            // discoverable across every primary tab without crowding the nav bar.
            StitchLiveSharingPill(
                text = stringResource(if (sharing) R.string.sharing_chip_on else R.string.sharing_chip_off),
                active = sharing,
                onClick = onOpenSharing,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            )
            HorizontalDivider(color = stitch.hairline)
        }
    }
}

@Composable
private fun AccountContent(
    container: AppContainer,
    onOpenTracking: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
) {
    val session by container.sessionStore.session.collectAsStateWithLifecycle(initialValue = null)
    val owner = session?.userId
    if (owner == null) {
        Text(stringResource(R.string.home_checking_account), Modifier.padding(16.dp))
        return
    }
    val viewModel: HomeViewModel = viewModel(
        key = "account-$owner",
        factory = HomeViewModel.factory(container.authRepository, container.trackingManager),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    var showRenameDialog by remember(owner) { mutableStateOf(false) }
    var showDeleteDialog by remember(owner) { mutableStateOf(false) }
    var showSignOutDialog by remember(owner) { mutableStateOf(false) }
    val visible = if (state.user == null || state.user?.id == owner) state else state.copy(user = null, loading = true)

    AvatarEditorHost(container, owner, visible.user?.displayName.orEmpty()) { avatarController, avatar ->
    HomeContent(
        state = visible,
        onToggleSharing = viewModel::setLocationSharing,
        onRename = { showRenameDialog = true },
        onRevokeDevice = viewModel::revokeDevice,
        onSignOut = { showSignOutDialog = true },
        onDeleteAccount = { showDeleteDialog = true },
        onRetry = viewModel::refresh,
        // One result batch per user action: HomeViewModel.consumeMessage
        // clears messageRes, consentMessage and errorRes together, so
        // dismissing any notice acknowledges the whole batch.
        onDismissMessage = viewModel::consumeMessage,
        onOpenTracking = onOpenTracking,
        onOpenDiagnostics = onOpenDiagnostics,
        avatarMessageRes = avatar.messageRes,
        // The controller owns this message: complete() sets it and only a new
        // operation would overwrite it, so acknowledge it here (same batch
        // semantics as onDismissMessage above).
        onDismissAvatarMessage = avatarController::consumeMessage,
        avatarContent = {
            val avatarEditLabel = stringResource(R.string.home_avatar_edit_desc)
            Box(Modifier.size(64.dp).clickable(role = Role.Button, onClick = avatarController::open)
                .semantics { contentDescription = avatarEditLabel }) {
                AvatarImage(owner, owner, avatar.metadata?.imageId, visible.user?.displayName.orEmpty(), container.avatarRepository, Modifier.size(64.dp))
                Surface(Modifier.align(Alignment.BottomEnd), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                    Icon(Icons.Outlined.Edit, null, Modifier.padding(4.dp).size(16.dp))
                }
            }
        },
    )
    }

    if (showRenameDialog) {
        RenameDialog(
            initial = state.user?.displayName.orEmpty(),
            onConfirm = { name ->
                viewModel.rename(name)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false },
        )
    }
    if (showSignOutDialog) {
        SignOutDialog(
            onConfirm = {
                viewModel.signOut()
                showSignOutDialog = false
            },
            onDismiss = { showSignOutDialog = false },
        )
    }
    if (showDeleteDialog) {
        DeleteAccountDialog(
            onConfirm = {
                viewModel.deleteAccount()
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

@Composable
fun HomeContent(
    state: HomeUiState,
    onToggleSharing: (Boolean) -> Unit,
    onRename: () -> Unit,
    onRevokeDevice: (String) -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    onRetry: () -> Unit,
    onDismissMessage: () -> Unit = {},
    onOpenTracking: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    avatarContent: (@Composable () -> Unit)? = null,
    avatarMessageRes: Int? = null,
    onDismissAvatarMessage: () -> Unit = {},
) {
    // Revocation is destructive and server-side, so it waits for an explicit
    // confirm; holding the pending device id here (not inside a LazyColumn
    // item) keeps it alive across scroll and process death.
    var revokeDeviceId: String? by rememberSaveable(stateSaver = nullableStringSaver) { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    Surface(modifier = Modifier.fillMaxSize()) {
        if (state.loading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StitchLoadingSkeletonCard(rows = 2)
                StitchLoadingSkeletonCard(rows = 3)
                StitchLoadingSkeletonCard(rows = 2)
            }
            return@Surface
        }

        val user = state.user
        if (user == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                StitchEmptyStateCard(
                    title = stringResource(R.string.home_load_failed),
                    body = stringResource(R.string.home_account_security_subtitle),
                    icon = Icons.Outlined.Security,
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = onRetry,
                )
            }
            return@Surface
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // 72dp bottom = FAB container 56dp + 16dp gap (HOME_FAB_CLEARANCE):
            // the shell Scaffold floats the FAB over the content without
            // reserving room for it, so the last action row would otherwise
            // sit under the thumb-zone FAB (same clearance as History/Places).
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = HOME_FAB_CLEARANCE),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                StitchLiveSharingPill(
                    text = stringResource(R.string.home_privacy_live_pill),
                    active = state.locationSharingRequested ?: user.locationSharingEnabled,
                )
            }
            val errorRes = state.errorRes
            if (errorRes != null) {
                item {
                    // The app-wide failure presentation (AnveErrorNotice)
                    // keeps the retry at hand: the failure can be a refresh
                    // while old data is still on screen. Dismissing shares the
                    // message consumer — the same result batch acknowledged.
                    AnveErrorNotice(
                        stringResource(errorRes),
                        onRetry = onRetry,
                        onDismiss = onDismissMessage,
                    )
                }
            }
            val messageRes = state.messageRes
            val consentMessage = state.consentMessage
            // One result batch, one acknowledgment card: consumeMessage clears
            // messageRes, consentMessage and errorRes together, so per-notice
            // cards let a single batch stack up to four 'Đã hiểu' buttons on
            // this screen. The first non-null notice shows alone — the rest
            // queue behind it and surface once it is acknowledged — and the
            // priority order is deterministic because the state carries no
            // timestamps. The avatar notice belongs to the AvatarController,
            // so its card acknowledges through that consumer, not the batch.
            when {
                messageRes != null -> item {
                    ResultNoticeCard(stringResource(messageRes), onDismissMessage)
                }
                consentMessage != null -> item {
                    ResultNoticeCard(consentMessage, onDismissMessage)
                }
                avatarMessageRes != null -> item {
                    ResultNoticeCard(stringResource(avatarMessageRes), onDismissAvatarMessage)
                }
            }
            item { ProfileCard(user = user, onRename = onRename, avatarContent = avatarContent) }
            item {
                ConsentCard(
                    user = user,
                    requestedEnabled = state.locationSharingRequested ?: user.locationSharingEnabled,
                    syncPending = state.consentSyncPending,
                    conflict = state.consentConflict,
                    busy = state.consentBusy,
                    onToggle = onToggleSharing,
                    onOpenLocation = onOpenTracking,
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        AnVeSectionHeader(
                            icon = Icons.Outlined.Devices,
                            title = stringResource(R.string.home_group_devices_title),
                            subtitle = stringResource(R.string.home_group_devices_subtitle),
                        )
                    }
                    StitchSemanticBadge(
                        text = stringResource(R.string.home_device_count_badge, state.devices.size),
                        kind = StitchBadgeKind.NEUTRAL,
                    )
                }
            }
            items(state.devices, key = { it.id }) { device ->
                DeviceRow(
                    device = device,
                    onRevoke = { revokeDeviceId = device.id },
                )
            }
            item {
                OutlinedCard(
                    onClick = onOpenDiagnostics,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, stitch.hairline),
                    colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Security,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.diag_title), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(R.string.diag_account_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                OutlinedCard(
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, stitch.hairline),
                    colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AnVeSectionHeader(
                            icon = Icons.Outlined.Lock,
                            title = stringResource(R.string.home_account_security_title),
                            subtitle = stringResource(R.string.home_account_security_subtitle),
                            iconContentColor = colors.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = onSignOut,
                            enabled = !state.busy,
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.home_sign_out))
                        }
                        TextButton(
                            onClick = onDeleteAccount,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = null,
                                tint = colors.error,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.home_delete_account),
                                color = colors.error,
                            )
                        }
                    }
                }
            }
        }
    }
    // The dialog sits outside the Surface so the loading and no-user early
    // returns above cannot unmount it while a confirmation is open.
    revokeDeviceId?.let { deviceId ->
        RevokeSessionDialog(
            onConfirm = {
                onRevokeDevice(deviceId)
                revokeDeviceId = null
            },
            onDismiss = { revokeDeviceId = null },
        )
    }
}

/**
 * The result-notice presentation on this screen (DESIGN.md Result Notice
 * Rule: a surface without a snackbar host shows the result as a
 * secondaryContainer card with an explicit dismiss). Every result notice —
 * message, consent and avatar — shares this one shape, so the merged batch
 * card reads exactly like the single-notice card it replaced.
 */
@Composable
private fun ResultNoticeCard(text: String, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.secondaryContainer,
            contentColor = colors.onSecondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Surface(
                    shape = CircleShape,
                    color = colors.primaryContainer,
                    contentColor = colors.onPrimaryContainer,
                    modifier = Modifier.size(34.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    // Same polite live region as AnveErrorNotice: a one-shot result
                    // is announced instead of sitting silent for TalkBack users.
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
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
private fun ProfileCard(user: UserDto, onRename: () -> Unit, avatarContent: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    OutlinedCard(
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, stitch.hairline),
        colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            AnVeAccountShieldIllustration(
                modifier = Modifier.fillMaxWidth().height(92.dp),
            )
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (avatarContent != null) {
                    avatarContent()
                } else {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .background(
                                color = colors.primaryContainer,
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = user.displayName.take(1).uppercase().ifBlank { "?" },
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.onPrimaryContainer,
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(user.displayName, style = MaterialTheme.typography.titleLarge)
                    Text(
                        user.email,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                FilledTonalButton(
                    onClick = onRename,
                    shape = CircleShape,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_rename))
                }
            }
        }
    }
}

@Composable
private fun ConsentCard(
    user: UserDto,
    requestedEnabled: Boolean,
    syncPending: Boolean,
    conflict: Boolean,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenLocation: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    val statusBadge = when {
        syncPending -> stringResource(R.string.home_sharing_state_syncing)
        requestedEnabled -> stringResource(R.string.home_sharing_state_on)
        else -> stringResource(R.string.home_sharing_state_off)
    }
    OutlinedCard(
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, stitch.hairline),
        colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The consent switches on Vị trí and Tài khoản decide whether the
            // server may hold this family's location — the product's most
            // consequential control. Row-toggleable so TalkBack reads the
            // consent title + state from ONE focus; a bare switch would read
            // an anonymous "bật" with no name (conventions toggleable-row).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.home_sharing_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                StitchSemanticBadge(
                    text = statusBadge,
                    kind = when {
                        syncPending -> StitchBadgeKind.IN_TRANSIT
                        requestedEnabled -> StitchBadgeKind.SAFE_HOME
                        else -> StitchBadgeKind.DELAYED_OFFLINE
                    },
                    showDot = requestedEnabled || syncPending,
                )
            }
            HorizontalDivider(color = stitch.hairline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = requestedEnabled,
                        enabled = !busy,
                        role = Role.Switch,
                        onValueChange = onToggle,
                    ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.home_sharing_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = requestedEnabled,
                    enabled = !busy,
                    // Indicative only: the row above carries the toggle so the
                    // switch does not double-register as a toggleable node.
                    onCheckedChange = null,
                )
            }
            if (conflict) {
                Text(
                    stringResource(R.string.home_sharing_conflict_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                )
            } else if (syncPending) {
                // Waiting-for-server is attention, not failure: error stays
                // reserved for the conflict branch above (DESIGN.md).
                Text(
                    stringResource(R.string.home_sharing_pending_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            } else if (user.locationSharingEnabled) {
                Text(
                    stringResource(R.string.home_sharing_on_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.primary,
                )
                // Consent turned on used to dead-end here; the one screen
                // where the shared location actually appears is a hop away,
                // so offer it while the state is fresh.
                onOpenLocation?.let { openLocation ->
                    FilledTonalButton(
                        onClick = openLocation,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.action_open_location))
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: DeviceDto, onRevoke: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    val isCurrent = device.session?.current == true
    OutlinedCard(
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, stitch.hairline),
        colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Devices,
                contentDescription = null,
                tint = if (isCurrent) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        device.name.ifBlank { stringResource(R.string.home_device_fallback_name) },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (isCurrent) {
                        // A read-only label, not a chip: the device name is
                        // informational and has no action behind it.
                        StitchSemanticBadge(
                            text = stringResource(R.string.home_device_this),
                            kind = StitchBadgeKind.SAFE_HOME,
                        )
                    }
                }
                Text(
                    text = listOfNotNull(
                        device.platform,
                        device.appVersion?.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            // The session in use cannot revoke itself: confirming it would
            // sign this very device out and throw the user out of the app
            // mid-settings. Other devices revoke, and only via the dialog.
            OutlinedButton(
                onClick = onRevoke,
                enabled = !isCurrent,
                shape = CircleShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.home_device_revoke))
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        icon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
        title = { Text(stringResource(R.string.home_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                label = { Text(stringResource(R.string.home_rename_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
                shape = CircleShape,
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun SignOutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        icon = { Icon(Icons.AutoMirrored.Outlined.ExitToApp, contentDescription = null) },
        title = { Text(stringResource(R.string.home_sign_out_title)) },
        text = { Text(stringResource(R.string.home_sign_out_body)) },
        confirmButton = {
            Button(onClick = onConfirm, shape = CircleShape) {
                Text(stringResource(R.string.home_sign_out))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun DeleteAccountDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        icon = { Icon(Icons.Outlined.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.home_delete_title)) },
        text = { Text(stringResource(R.string.home_delete_body)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.home_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun RevokeSessionDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    // Same destructive-confirm pattern as sign-out and account deletion: a
    // server-side action that ends access must be named before it runs.
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        icon = { Icon(Icons.Outlined.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.home_device_revoke_title)) },
        text = { Text(stringResource(R.string.home_device_revoke_body)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.home_device_revoke))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun HomeContentPreview() {
    val user = UserDto(
        id = "u1",
        email = "me@example.com",
        displayName = "Mai",
        avatarUrl = null,
        locationSharingEnabled = false,
        createdAt = "2026-10-25T10:00:00Z",
    )
    val device = DeviceDto(
        id = "d1",
        name = "Pixel 8",
        platform = "android",
        appVersion = "0.2.0-debug",
        createdAt = "2026-10-25T10:00:00Z",
        lastSeenAt = "2026-10-25T12:00:00Z",
        session = DeviceSessionDto(
            id = "s1",
            createdAt = "2026-10-25T10:00:00Z",
            lastUsedAt = "2026-10-25T12:00:00Z",
            expiresAt = "2026-11-24T10:00:00Z",
            current = true,
        ),
    )
    AnVeTheme {
        HomeContent(
            state = HomeUiState(loading = false, user = user, devices = listOf(device)),
            onToggleSharing = {},
            onRename = {},
            onRevokeDevice = {},
            onSignOut = {},
            onDeleteAccount = {},
            onRetry = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeContentDarkPreview() {
    val user = UserDto(
        id = "u1",
        email = "me@example.com",
        displayName = "Mai",
        avatarUrl = null,
        locationSharingEnabled = true,
        createdAt = "2026-10-25T10:00:00Z",
    )
    AnVeTheme(darkTheme = true) {
        HomeContent(
            state = HomeUiState(loading = false, user = user, devices = emptyList()),
            onToggleSharing = {},
            onRename = {},
            onRevokeDevice = {},
            onSignOut = {},
            onDeleteAccount = {},
            onRetry = {},
        )
    }
}
