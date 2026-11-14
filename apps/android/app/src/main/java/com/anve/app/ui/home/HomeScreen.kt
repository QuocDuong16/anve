package com.anve.app.ui.home

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.anve.app.ui.circle.CircleScreen
import com.anve.app.ui.diagnostics.DiagnosticsScreen
import com.anve.app.ui.history.HistoryScreen
import com.anve.app.ui.map.LiveMapScreen
import com.anve.app.ui.places.PlacesScreen
import com.anve.app.ui.sos.SosScreen
import com.anve.app.ui.theme.AnVeTheme
import com.anve.app.ui.theme.LocalEmergencyColors
import com.anve.app.ui.tracking.TrackingScreen
import java.io.Serializable

/**
 * M1 home: the account, the privacy consent switch and the device list.
 * Later milestones grow the dashboard around this (Live Map first), so it
 * deliberately stays a working screen rather than a menu of buttons.
 */
private enum class HomeTab(val labelRes: Int) {
    CIRCLE(R.string.tab_circle),
    MAP(R.string.tab_map),
    HISTORY(R.string.tab_history),
    PLACES(R.string.tab_places),
}

private enum class HomeSecondaryPage(val titleRes: Int) {
    TRACKING(R.string.tracking_title),
    ACCOUNT(R.string.tab_account),
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
 * Material Scaffold floats the FAB above the bottom bar without adding it to
 * the content padding, so full-height tab content (History, Places) clears it
 * on its own: the FAB's 56dp container (FabBaselineTokens) plus the 16dp gap
 * between the FAB and the bottom bar (Scaffold FabSpacing).
 */
internal val HOME_FAB_CLEARANCE = 72.dp

/**
 * The signed-in surface keeps four primary destinations and routes secondary
 * settings screens above the selected destination.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(container: AppContainer, incomingPush: IncomingPush? = null, onPushConsumed: () -> Unit = {}) {
    var tab by rememberSaveable(stateSaver = homeTabSaver) { mutableStateOf(HomeTab.CIRCLE) }
    var secondaryPage: HomeSecondaryPage? by rememberSaveable(stateSaver = homeSecondaryPageSaver) { mutableStateOf<HomeSecondaryPage?>(null) }
    var sosReturnPage: HomeSecondaryPage? by rememberSaveable(stateSaver = homeSecondaryPageSaver) { mutableStateOf<HomeSecondaryPage?>(null) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var showJourneys by rememberSaveable { mutableStateOf(false) }
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
            secondaryPage = when {
                destination.kind == "SOS" -> HomeSecondaryPage.SOS
                showJourneys -> HomeSecondaryPage.TRACKING
                else -> null
            }
            sosReturnPage = null
            selectedInvitation = if (destination.kind == "CIRCLE_INVITE") destination.eventId else null
            tab = when (destination.kind) {
                "SOS", "CIRCLE_INVITE" -> HomeTab.CIRCLE
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
            onLocation = { showJourneys = false; secondaryPage = HomeSecondaryPage.TRACKING },
            journeyId = selectedJourney,
        )
        return
    }
    if (showDiagnostics) {
        BackHandler { showDiagnostics = false }
        DiagnosticsScreen(container = container, onBack = { showDiagnostics = false })
        return
    }
    val sosOpenLabel = stringResource(R.string.sos_open)
    val returnFromSecondaryPage = {
        secondaryPage = if (secondaryPage == HomeSecondaryPage.SOS) sosReturnPage else null
        sosReturnPage = null
        selectedSos = null
    }
    BackHandler(enabled = secondaryPage != null, onBack = returnFromSecondaryPage)
    Scaffold(
        // Hosts the push deep-link snackbar; nothing else in the shell posts
        // here today, so the host stays otherwise default.
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            secondaryPage?.let { page ->
                TopAppBar(
                    title = { Text(stringResource(page.titleRes)) },
                    navigationIcon = {
                        IconButton(onClick = returnFromSecondaryPage) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
            }
        },
        bottomBar = {
            NavigationBar {
                HomeTab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = secondaryPage == null && tab == entry,
                        onClick = {
                            tab = entry
                            secondaryPage = null
                            sosReturnPage = null
                            selectedSos = null
                        },
                        icon = {
                            Icon(
                                imageVector = when (entry) {
                                    HomeTab.CIRCLE -> Icons.Default.Groups
                                    HomeTab.MAP -> Icons.Default.Map
                                    HomeTab.HISTORY -> Icons.Default.History
                                    HomeTab.PLACES -> Icons.Default.Place
                                },
                                contentDescription = stringResource(entry.labelRes),
                            )
                        },
                        label = { Text(stringResource(entry.labelRes)) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (secondaryPage != HomeSecondaryPage.SOS) {
                // SOS is urgent-but-not-a-failure, so it uses the emergency
                // amber role instead of error.
                val emergency = LocalEmergencyColors.current
                ExtendedFloatingActionButton(
                    modifier = Modifier.semantics { contentDescription = sosOpenLabel },
                    onClick = {
                        sosReturnPage = secondaryPage
                        selectedSos = null
                        secondaryPage = HomeSecondaryPage.SOS
                    },
                    containerColor = emergency.emergency,
                    contentColor = emergency.onEmergency,
                    icon = { Icon(Icons.Default.Sos, contentDescription = null) },
                    text = { Text(stringResource(R.string.tab_sos)) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (secondaryPage) {
                HomeSecondaryPage.TRACKING -> TrackingScreen(
                    container = container,
                    onOpenDiagnostics = { showDiagnostics = true },
                    onOpenJourney = { selectedJourney = null; showJourneys = true },
                )
                HomeSecondaryPage.ACCOUNT -> AccountContent(container = container)
                HomeSecondaryPage.SOS -> SosScreen(container = container, eventId = selectedSos)
                null -> Column(Modifier.fillMaxSize()) {
                    // Vị trí (consent + capture — the product's core control)
                    // and Tài khoản (rename, sessions, deletion) are top-level
                    // destinations: the labeled card keeps both reachable from
                    // every tab, not only Nhóm where the activation checklist
                    // used to be the only pointer. The map tab is exempt: it
                    // already carries its own status chip and audience overlay,
                    // and the card would eat viewing space there.
                    if (tab != HomeTab.MAP) {
                        SharingSettingsCard(
                            onOpenTracking = { secondaryPage = HomeSecondaryPage.TRACKING },
                            onOpenAccount = { secondaryPage = HomeSecondaryPage.ACCOUNT },
                        )
                    }
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        when (tab) {
                            HomeTab.CIRCLE -> CircleScreen(
                                container = container,
                                invitationId = selectedInvitation,
                                onInvitationConsumed = { selectedInvitation = null },
                                onOpenLocation = { secondaryPage = HomeSecondaryPage.TRACKING },
                                onOpenMap = {
                                    circleAudienceStepHidden = true
                                    tab = HomeTab.MAP
                                    secondaryPage = null
                                    sosReturnPage = null
                                    selectedSos = null
                                },
                                audienceStepHidden = circleAudienceStepHidden,
                            )
                            HomeTab.MAP -> LiveMapScreen(container = container)
                            HomeTab.HISTORY -> HistoryScreen(container = container)
                            HomeTab.PLACES -> PlacesScreen(
                                container = container,
                                onOpenLocation = { secondaryPage = HomeSecondaryPage.TRACKING },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SharingSettingsCard(onOpenTracking: () -> Unit, onOpenAccount: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.home_quick_settings_title), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenTracking, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.LocationOn, contentDescription = null)
                    Text(stringResource(R.string.tab_location), modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedButton(onClick = onOpenAccount, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Person, contentDescription = null)
                    Text(stringResource(R.string.tab_account), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun AccountContent(container: AppContainer) {
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
        avatarMessageRes = avatar.messageRes,
        avatarContent = {
            val avatarEditLabel = stringResource(R.string.home_avatar_edit_desc)
            Box(Modifier.size(64.dp).clickable(role = Role.Button, onClick = avatarController::open)
                .semantics { contentDescription = avatarEditLabel }) {
                AvatarImage(owner, owner, avatar.metadata?.imageId, visible.user?.displayName.orEmpty(), container.avatarRepository, Modifier.size(64.dp))
                Surface(Modifier.align(Alignment.BottomEnd), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                    Icon(Icons.Default.Edit, null, Modifier.padding(4.dp).size(16.dp))
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
    avatarContent: (@Composable () -> Unit)? = null,
    avatarMessageRes: Int? = null,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Surface
        }

        val user = state.user
        if (user == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.home_load_failed),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
            return@Surface
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val errorRes = state.errorRes
            if (errorRes != null) {
                item {
                    // Same error presentation as the sign-in screen: a
                    // container card, not bare text — with the retry at hand,
                    // since the failure can be a refresh while old data is
                    // still on screen.
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(errorRes),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.action_retry))
                            }
                        }
                    }
                }
            }
            val messageRes = state.messageRes
            if (messageRes != null) {
                item {
                    // Same notice presentation as the tracking message card.
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    ) {
                        Text(
                            text = stringResource(messageRes),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            val consentMessage = state.consentMessage
            if (consentMessage != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    ) {
                        Text(
                            text = consentMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            item { ProfileCard(user = user, onRename = onRename, avatarContent = avatarContent) }
            avatarMessageRes?.let { res ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    ) {
                        Text(
                            text = stringResource(res),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            item {
                ConsentCard(
                    user = user,
                    requestedEnabled = state.locationSharingRequested ?: user.locationSharingEnabled,
                    syncPending = state.consentSyncPending,
                    conflict = state.consentConflict,
                    busy = state.consentBusy,
                    onToggle = onToggleSharing,
                )
            }
            item {
                Text(
                    text = stringResource(R.string.home_devices_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(state.devices, key = { it.id }) { device ->
                DeviceRow(
                    device = device,
                    onRevoke = { onRevokeDevice(device.id) },
                )
            }
            item {
                HorizontalDivider()
                OutlinedButton(
                    onClick = onSignOut,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.home_sign_out))
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onDeleteAccount,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.home_delete_account),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(user: UserDto, onRename: () -> Unit, avatarContent: (@Composable () -> Unit)? = null) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (avatarContent != null) avatarContent() else Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = user.displayName.take(1).uppercase().ifBlank { "?" },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            ) {
                Text(user.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    user.email,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRename) {
                Text(stringResource(R.string.action_rename))
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
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.home_sharing_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.home_sharing_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = requestedEnabled,
                    enabled = !busy,
                    onCheckedChange = onToggle,
                )
            }
            if (conflict) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.home_sharing_conflict_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (syncPending) {
                Spacer(Modifier.height(8.dp))
                // Waiting-for-server is attention, not failure: error stays
                // reserved for the conflict branch above (DESIGN.md).
                Text(
                    stringResource(R.string.home_sharing_pending_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (user.locationSharingEnabled) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.home_sharing_on_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(device: DeviceDto, onRevoke: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(device.name.ifBlank { stringResource(R.string.home_device_fallback_name) }, style = MaterialTheme.typography.titleSmall)
                    if (device.session?.current == true) {
                        Spacer(Modifier.size(8.dp))
                        // A read-only label, not a chip: the device name is
                        // informational and has no action behind it.
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            Text(
                                stringResource(R.string.home_device_this),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text(
                    text = listOfNotNull(
                        device.platform,
                        device.appVersion?.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRevoke) {
                Text(
                    stringResource(R.string.home_device_revoke),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.home_rename_label)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
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
        title = { Text(stringResource(R.string.home_sign_out_title)) },
        text = { Text(stringResource(R.string.home_sign_out_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.home_sign_out)) }
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
        title = { Text(stringResource(R.string.home_delete_title)) },
        text = { Text(stringResource(R.string.home_delete_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.home_delete_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
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
