package com.anve.app.ui.places

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.PanToolAlt
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anve.app.AppContainer
import com.anve.app.BuildConfig
import com.anve.app.R
import com.anve.app.data.api.PlaceDto
import com.anve.app.data.api.PlaceInputDto
import com.anve.app.tracking.AndroidLocationPermissions
import com.anve.app.tracking.FusedLocationProvider
import com.anve.app.tracking.LocationPermissionState
import com.anve.app.tracking.LocationRequestOptions
import com.anve.app.ui.brand.AnVePlacesSceneIllustration
import com.anve.app.ui.common.AnVeCheckingState
import com.anve.app.ui.common.AnveErrorNotice
import com.anve.app.ui.common.AnveTime
import com.anve.app.ui.common.StitchBadgeKind
import com.anve.app.ui.common.StitchEmptyStateCard
import com.anve.app.ui.common.StitchLoadingSkeletonCard
import com.anve.app.ui.common.StitchSemanticBadge
import com.anve.app.ui.common.StitchTimelineItem
import com.anve.app.ui.common.anveContentWidth
import com.anve.app.ui.home.HOME_FAB_CLEARANCE
import com.anve.app.ui.map.MapOverlayPillButton
import com.anve.app.ui.map.rememberAnVeMapProperties
import com.anve.app.ui.map.rememberPlaceMarkerIcon
import com.anve.app.ui.theme.LocalStitchColors
import com.anve.app.ui.theme.isDarkSurface
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// A foreground fused fix usually arrives within seconds; a cold GPS start can
// take longer, and the manual coordinate fields stay the fallback either way.
private const val CURRENT_FIX_TIMEOUT_MS = 10_000L
private const val CURRENT_FIX_INTERVAL_MS = 5_000L

// The recipient picks are stored as a plain ArrayList on the way out because
// autoSaver does not promise a Set survives the Bundle round trip; restore
// maps it back (same approach as JourneyScreen's draft savers).
private val recipientsSaver = Saver<Set<String>, ArrayList<String>>(
    save = { ArrayList(it) },
    restore = { it.toSet() },
)

@Composable
fun PlacesScreen(
    container: AppContainer,
    onOpenLocation: () -> Unit = {},
    onEditorOpenChange: (Boolean) -> Unit = {},
) {
    val session by container.sessionStore.session.collectAsStateWithLifecycle(initialValue = null)
    val owner = session?.userId
    val vm: PlacesViewModel = viewModel(factory = PlacesViewModel.factory(container.placesRepository))
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(owner) {
        vm.resume(owner)
    }
    DisposableEffect(owner, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                vm.resume(owner)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(owner, lifecycle) {
        if (owner != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(10_000)
                vm.poll()
            }
        }
    }
    if (owner == null || state.owner != owner) {
        // Session not re-emitted yet (fresh process or account switch): state
        // the wait instead of a blank frame, same wording as the account area.
        AnVeCheckingState(stringResource(R.string.home_checking_account))
        return
    }
    PlacesContent(state, vm, onOpenLocation, onEditorOpenChange)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlacesContent(
    state: PlacesState,
    vm: PlacesViewModel,
    onOpenLocation: () -> Unit = {},
    onEditorOpenChange: (Boolean) -> Unit = {},
) {
    val owner = state.owner ?: return
    val selected = state.places.firstOrNull { it.id == state.selectedId }
    var showDetails by remember(owner) { mutableStateOf(false) }
    var filterMine by rememberSaveable(owner) { mutableStateOf(false) }
    val visibleEvents = if (showDetails) state.events.filter { it.placeId == selected?.id } else state.events
    val displayedPlaces = if (filterMine) state.places.filter { it.ownerId == owner } else state.places
    var editing by remember(owner) { mutableStateOf(false) }
    var editingPlace by remember(owner) { mutableStateOf<PlaceDto?>(null) }
    var editConfirmation by remember(owner) { mutableStateOf(state.confirmation) }
    var deleteId by remember(owner) { mutableStateOf<String?>(null) }
    var showActions by remember(selected?.id) { mutableStateOf(false) }
    // Recipient picks survive process death and rotation; a person who ticked
    // rows mid-configure should not find the checkboxes silently reset — the
    // same guarantee JourneyScreen gives its viewer draft.
    var recipients by rememberSaveable(owner, selected?.id, state.monitor, stateSaver = recipientsSaver) {
        mutableStateOf(state.monitor?.recipientIds?.toSet() ?: emptySet())
    }
    // Disclosure state for the notices explainer; hoisted above the details
    // LazyColumn so scrolling the item out of the viewport cannot dispose it
    // and silently collapse the section.
    var notesExpanded by rememberSaveable { mutableStateOf(false) }
    val editorAuthorized = editingPlace == null ||
        (state.errorRes == null && state.places.any { it.id == editingPlace?.id })
    LaunchedEffect(editorAuthorized) {
        if (!editorAuthorized) {
            editing = false
            editingPlace = null
        }
    }
    val editorOpen = editing && editorAuthorized
    LaunchedEffect(editorOpen) { onEditorOpenChange(editorOpen) }
    DisposableEffect(Unit) { onDispose { onEditorOpenChange(false) } }
    val snackbarHostState = remember { SnackbarHostState() }
    // Resolved before the effect: LaunchedEffect bodies are not composable.
    val saveConfirmedMessage = stringResource(R.string.places_save_confirmed)
    val configureConfirmedMessage = stringResource(R.string.places_configure_confirmed)
    LaunchedEffect(state.confirmation, owner) {
        if (editing && state.confirmation > editConfirmation) {
            // The editor only closes on a server-confirmed save; name the
            // outcome instead of letting the list change speak for itself.
            editing = false
            editingPlace = null
            snackbarHostState.showSnackbar(saveConfirmedMessage)
        }
    }
    // Configure (Bật theo dõi / Lưu người nhận) confirms the same way: the
    // button records the confirmation baseline at click time, and once the
    // mutation is no longer pending a higher confirmation means the server
    // accepted it. Failure keeps the baseline unset and the ViewModel's notice.
    var configureBaseline by remember(owner, selected?.id) { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.pending, state.confirmation) {
        val baseline = configureBaseline
        if (baseline == null || state.pending) return@LaunchedEffect
        configureBaseline = null
        if (state.confirmation > baseline) snackbarHostState.showSnackbar(configureConfirmedMessage)
    }
    BackHandler(enabled = showDetails || editing) {
        if (editing) {
            editing = false
            editingPlace = null
        } else {
            showDetails = false
            vm.select(null)
        }
    }
    if (editing && editorAuthorized) {
        PlaceEditor(
            editingPlace,
            state.pending,
            state.noticeRes,
            onCancel = { editing = false; editingPlace = null },
            onSave = { input ->
                editConfirmation = state.confirmation
                vm.save(editingPlace?.id, input)
            },
        )
        return
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // Same header pattern as the other screens: title plus icon
            // actions. The back arrow only appears in the details view, so
            // system and in-app back behave as before. The host home scaffold
            // already consumed the system insets for this tab, so the app bar
            // adds none of its own.
            TopAppBar(
                title = {
                    Text(if (showDetails && selected != null) selected.name else stringResource(R.string.places_title))
                },
                navigationIcon = {
                    if (showDetails) {
                        IconButton(onClick = { showDetails = false; vm.select(null) }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.places_back_content_desc))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh, enabled = !state.pending && !state.loading && !state.refreshing) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.places_refresh))
                    }
                    if (showDetails && selected?.ownerId == owner) {
                        Box {
                            IconButton(onClick = { showActions = true }, enabled = !state.pending) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.places_actions_content_desc))
                            }
                            DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.places_edit)) },
                                    onClick = {
                                        showActions = false
                                        editingPlace = selected
                                        editConfirmation = state.confirmation
                                        editing = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.places_delete_menu)) },
                                    onClick = { showActions = false; deleteId = selected.id },
                                )
                            }
                        }
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val colors = MaterialTheme.colorScheme
        val stitch = LocalStitchColors.current
        // Pull to refresh alongside the top-bar action: same repository call,
        // two affordances. The refresh button stays for users who do not use
        // the gesture.
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.fillMaxHeight().fillMaxWidth().anveContentWidth().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = HOME_FAB_CLEARANCE),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!showDetails) {
                    item {
                        Text(
                            text = stringResource(R.string.places_monitoring_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    state.noticeRes?.let { res -> item { AnveErrorNotice(stringResource(res)) } }
                    state.errorRes?.let { res -> item { AnveErrorNotice(stringResource(res), onRetry = vm::refresh) } }
                    item {
                        Button(
                            onClick = {
                                editingPlace = null
                                editConfirmation = state.confirmation
                                editing = true
                            },
                            enabled = !state.pending,
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.places_add))
                        }
                    }
                    if (state.loading || state.pending) item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(24.dp))
                                Text(
                                    stringResource(if (state.loading) R.string.action_loading else R.string.action_processing),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                            if (state.loading && state.places.isEmpty()) {
                                StitchLoadingSkeletonCard(rows = 2)
                            }
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
                                text = stringResource(R.string.places_list_section_title),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { heading() },
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = !filterMine,
                                    onClick = { filterMine = false },
                                    label = { Text(stringResource(R.string.places_filter_all)) },
                                )
                                FilterChip(
                                    selected = filterMine,
                                    onClick = { filterMine = true },
                                    label = { Text(stringResource(R.string.places_filter_mine)) },
                                )
                            }
                        }
                    }
                    if (displayedPlaces.isEmpty() && !state.loading && state.errorRes == null) {
                        item {
                            StitchEmptyStateCard(
                                title = stringResource(R.string.places_header_title),
                                body = stringResource(R.string.places_empty),
                                illustration = {
                                    AnVePlacesSceneIllustration(
                                        modifier = Modifier.fillMaxWidth().height(124.dp),
                                    )
                                },
                            )
                        }
                    }
                    items(displayedPlaces, key = { it.id }) { place ->
                        val isMine = place.ownerId == owner
                        val label = if (isMine) {
                            stringResource(R.string.places_owner_you)
                        } else {
                            state.sharing.members.firstOrNull { it.userId == place.ownerId }?.displayName ?: stringResource(R.string.places_member_fallback)
                        }
                        OutlinedCard(
                            onClick = {
                                if (!state.pending) {
                                    vm.select(place.id)
                                    showDetails = true
                                }
                            },
                            enabled = !state.pending,
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, stitch.hairline),
                            colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                        itemVerticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = place.name,
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        StitchSemanticBadge(
                                            text = label,
                                            kind = if (isMine) StitchBadgeKind.SAFE_HOME else StitchBadgeKind.AT_PLACE,
                                        )
                                        if (place.sharedWithCircle) {
                                            StitchSemanticBadge(
                                                text = stringResource(R.string.places_family_shared_badge),
                                                kind = StitchBadgeKind.AT_PLACE,
                                            )
                                        }
                                    }
                                    Text(
                                        stringResource(
                                            R.string.places_radius_of_walk,
                                            label,
                                            place.radiusMeters.toInt(),
                                            maxOf(1, (place.radiusMeters / 80.0).roundToInt()),
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = colors.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                } else if (selected == null) {
                    state.errorRes?.let { res -> item { AnveErrorNotice(stringResource(res), onRetry = vm::refresh) } }
                    item { Text(stringResource(R.string.places_missing)) }
                    item {
                        TextButton(
                            onClick = { showDetails = false; vm.refresh() },
                            enabled = !state.loading && !state.refreshing,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.places_refresh_list))
                        }
                    }
                } else {
                    item {
                        OutlinedCard(
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, stitch.hairline),
                            colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Radar,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(22.dp),
                                )
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        stringResource(R.string.places_radius, selected.radiusMeters.toInt()),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        stringResource(R.string.places_radius_bar_label, selected.radiusMeters.toInt()),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    if (BuildConfig.MAPS_API_KEY.isNotBlank()) {
                        item(key = "preview-${selected.id}") { PlacePreview(selected) }
                    }
                    state.noticeRes?.let { res -> item { AnveErrorNotice(stringResource(res)) } }
                    state.errorRes?.let { res -> item { AnveErrorNotice(stringResource(res), onRetry = vm::refresh) } }
                    if (state.loading || state.detailLoading || state.pending) item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                            Text(
                                stringResource(if (state.loading || state.detailLoading) R.string.action_loading else R.string.action_processing),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                    item(key = "incoming-monitors-${selected.id}") {
                        OutlinedCard(
                            shape = RoundedCornerShape(22.dp),
                            border = BorderStroke(1.dp, stitch.hairline),
                            colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    stringResource(R.string.places_incoming_monitors_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.semantics { heading() },
                                )
                                Text(
                                    stringResource(R.string.places_incoming_monitors_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                                when {
                                    state.detailLoading && state.incomingMonitors.isEmpty() -> {
                                        Text(
                                            text = stringResource(R.string.action_loading),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant,
                                        )
                                    }
                                    state.incomingMonitors.isEmpty() -> {
                                        Text(
                                            text = stringResource(R.string.places_incoming_monitors_empty),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant,
                                        )
                                    }
                                    else -> {
                                        state.incomingMonitors.forEach { incoming ->
                                            ListItem(
                                                leadingContent = {
                                                    Icon(
                                                        imageVector = Icons.Outlined.Notifications,
                                                        contentDescription = null,
                                                        tint = colors.primary,
                                                    )
                                                },
                                                headlineContent = { Text(incoming.ownerName) },
                                                supportingContent = {
                                                    Text(stringResource(R.string.places_incoming_monitor_active, incoming.ownerName))
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (selected.ownerId == owner) {
                        val otherMembers = state.sharing.members.filter { it.userId != owner }
                        item {
                            OutlinedCard(
                                shape = RoundedCornerShape(22.dp),
                                border = BorderStroke(1.dp, stitch.hairline),
                                colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.places_share_with),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.semantics { heading() },
                                    )
                                    Text(
                                        stringResource(R.string.places_share_section_title),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    // The share notice sits inline above the toggles it
                                    // explains: the rebuttal of the most dangerous
                                    // misreading (sharing a place does not grant
                                    // event-viewing rights) must be readable where the
                                    // sharing decision happens, not behind the notes
                                    // toggle further down the list.
                                    Text(
                                        stringResource(R.string.places_share_notice),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    ListItem(
                                        modifier = Modifier.toggleable(
                                            value = selected.sharedWithCircle,
                                            enabled = !state.pending && !state.loading,
                                            role = Role.Switch,
                                            onValueChange = { vm.setSharedWithCircle(selected, it) },
                                        ),
                                        headlineContent = { Text(stringResource(R.string.places_family_share_detail_title)) },
                                        supportingContent = { Text(stringResource(R.string.places_family_share_detail_subtitle)) },
                                        trailingContent = {
                                            Switch(
                                                checked = selected.sharedWithCircle,
                                                enabled = !state.pending && !state.loading,
                                                onCheckedChange = null,
                                            )
                                        },
                                    )
                                    HorizontalDivider(color = stitch.hairline)
                                    if (otherMembers.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.places_share_empty_members),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant,
                                        )
                                    }
                                    otherMembers.forEach { person ->
                                        // The whole row toggles so TalkBack announces the member's
                                        // name together with the switch state; the switch itself
                                        // stays indicative (same pattern as SosScreen's rows).
                                        ListItem(
                                            modifier = Modifier.toggleable(
                                                value = person.userId in state.access,
                                                enabled = !state.pending && !state.loading,
                                                role = Role.Switch,
                                                onValueChange = { vm.share(selected.id, person.userId, it) },
                                            ),
                                            headlineContent = { Text(person.displayName) },
                                            supportingContent = { Text(stringResource(R.string.places_access_supporting)) },
                                            trailingContent = {
                                                Switch(
                                                    checked = person.userId in state.access,
                                                    enabled = !state.pending && !state.loading,
                                                    onCheckedChange = null,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    val eligibleMembers = state.sharing.members.filter {
                        it.userId != owner && it.userId in state.eligibleRecipients
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
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                // Same TalkBack heading treatment as the share title.
                                Text(
                                    stringResource(R.string.places_monitor_mine_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.semantics { heading() },
                                )
                                Text(
                                    stringResource(R.string.places_monitor_mine_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (selected.ownerId != owner) {
                                    val selectedOwnerName = state.sharing.members
                                        .firstOrNull { it.userId == selected.ownerId }
                                        ?.displayName
                                        ?: stringResource(R.string.places_member_fallback)
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = colors.surfaceContainerLow,
                                        border = BorderStroke(1.dp, stitch.hairline),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.places_monitor_other_place_hint, selectedOwnerName),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant,
                                            modifier = Modifier.padding(12.dp),
                                        )
                                    }
                                }
                                if (eligibleMembers.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.places_alerts_empty_members),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                                eligibleMembers.forEach { person ->
                                    // Row-level checkbox for the same TalkBack reason as the
                                    // share rows above; the inner checkbox stays indicative.
                                    ListItem(
                                        modifier = Modifier.toggleable(
                                            value = person.userId in recipients,
                                            enabled = !state.pending && !state.detailLoading,
                                            role = Role.Checkbox,
                                            onValueChange = { checked ->
                                                recipients = if (checked) recipients + person.userId else recipients - person.userId
                                            },
                                        ),
                                        headlineContent = { Text(person.displayName) },
                                        supportingContent = { Text(stringResource(R.string.places_recipient_supporting)) },
                                        trailingContent = {
                                            Checkbox(
                                                checked = person.userId in recipients,
                                                enabled = !state.pending && !state.detailLoading,
                                                onCheckedChange = null,
                                            )
                                        },
                                    )
                                }
                                // The enable button below is locked while consent is off; the
                                // notice next to it keeps the way out visible instead of a
                                // silent dead end for first-time users (consent defaults to
                                // off). Same pattern as JourneyScreen's requires-capture notice.
                                if (!state.sharing.consentEnabled) {
                                    ConsentNotice(onOpenLocation)
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = {
                                            configureBaseline = state.confirmation
                                            vm.configure(selected.id, recipients.intersect(state.eligibleRecipients))
                                        },
                                        shape = CircleShape,
                                        enabled = !state.pending && !state.detailLoading && state.sharing.consentEnabled,
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) {
                                        Text(if (state.monitor == null) stringResource(R.string.places_monitor_enable) else stringResource(R.string.places_monitor_save))
                                    }
                                    if (state.monitor != null) {
                                        TextButton(
                                            onClick = { vm.stop(selected.id) },
                                            enabled = !state.pending,
                                            modifier = Modifier.heightIn(min = 48.dp),
                                        ) { Text(stringResource(R.string.places_monitor_stop)) }
                                    }
                                }
                            }
                        }
                    }
                    item(key = "places-notes") {
                        PlacesNotesCard(
                            expanded = notesExpanded,
                            onToggle = { notesExpanded = !notesExpanded },
                        )
                    }
                }
                if (!showDetails || selected != null) {
                    item {
                        Text(
                            stringResource(R.string.places_events_title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                    if (visibleEvents.isEmpty() && !state.loading && state.errorRes == null) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = stitch.cardTactile,
                                border = BorderStroke(1.dp, stitch.hairline),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    stringResource(R.string.places_events_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    }
                    itemsIndexed(visibleEvents, key = { _, it -> "event-${it.id}" }) { index, event ->
                        // Same rule as HistoryScreen: never leak the raw ISO
                        // timestamp — an unreadable value falls back to the
                        // localized unknown-time string, not the wire format.
                        val at = AnveTime.full(event.recordedAt, stringResource(R.string.journey_time_unknown))
                        val transition = when (event.type) {
                            "ENTER" -> stringResource(R.string.places_event_enter)
                            "EXIT" -> stringResource(R.string.places_event_exit)
                            else -> stringResource(R.string.places_event_unknown)
                        }
                        val delayed = if (event.delayed) stringResource(R.string.places_event_delayed) else ""
                        val confidence = if (event.confidence == "OBSERVED") stringResource(R.string.places_confidence_observed) else stringResource(R.string.places_confidence_unknown)
                        val isEnter = event.type == "ENTER"
                        StitchTimelineItem(
                            icon = Icons.Outlined.Notifications,
                            nodeContainerColor = if (isEnter) colors.primaryContainer else colors.secondaryContainer,
                            nodeContentColor = if (isEnter) colors.onPrimaryContainer else colors.onSecondaryContainer,
                            isLast = index == visibleEvents.lastIndex,
                        ) {
                            OutlinedCard(
                                shape = RoundedCornerShape(18.dp),
                                border = BorderStroke(1.dp, stitch.hairline),
                                colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.places_event_title, event.ownerName, transition, event.placeName),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        "$at$delayed$confidence",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }
    deleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text(stringResource(R.string.places_delete_title)) },
            text = {
                Text(
                    stringResource(R.string.places_delete_body),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                // Same destructive-confirm pattern as the circle dialogs:
                // error-colored text action plus a neutral Hủy.
                TextButton(
                    onClick = {
                        deleteId = null
                        showDetails = false
                        vm.delete(id)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.places_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteId = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/**
 * Attention notice in the app's secondaryContainer treatment (DESIGN.md
 * attention notices): explains a locked control and offers the way out.
 */
@Composable
private fun ConsentNotice(onOpenLocation: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val dark = isDarkSurface()
    Surface(
        color = if (dark) colors.secondary else colors.secondaryContainer,
        contentColor = if (dark) colors.onSecondary else colors.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Info, null, Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.places_consent_notice))
                TextButton(
                    onClick = onOpenLocation,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.action_open_location))
                }
            }
        }
    }
}

/**
 * Collapsible explainer holding the two static places notices (own
 * monitoring, offline), the same collapsed pattern as TrackingScreen's
 * DiagnosticsCard. The round-3 merge pulled the share notice in here too;
 * round 4 split it back out: the rebuttal of the most dangerous misreading
 * (sharing a place does not grant event-viewing rights) must be readable
 * where the share toggles sit, not behind this toggle. The monitoring and
 * offline notices stay — they explain consequences people re-read after the
 * fact, not a decision in progress.
 */
@Composable
private fun PlacesNotesCard(expanded: Boolean, onToggle: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            // TalkBack announces the disclosure state instead of only the icon
            // swap; same treatment as the Tracking technical-details toggle.
            val disclosureState = stringResource(if (expanded) R.string.action_state_expanded else R.string.action_state_collapsed)
            TextButton(
                onClick = onToggle,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { stateDescription = disclosureState },
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                )
                Text(stringResource(R.string.places_notes_toggle))
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.places_monitor_mine_notice))
                    Text(stringResource(R.string.places_offline_notice))
                }
            }
        }
    }
}

@Composable
private fun PlacePreview(place: PlaceDto) {
    val point = LatLng(place.latitude, place.longitude)
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(point, 14.5f) }
    val stitch = LocalStitchColors.current
    val mapProperties = rememberAnVeMapProperties()
    val placeIcon = rememberPlaceMarkerIcon(place.name)
    var fullScreenOpen by rememberSaveable(place.id) { mutableStateOf(false) }

    OutlinedCard(
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, stitch.hairline),
        colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(216.dp)
                .clip(RoundedCornerShape(22.dp)),
        ) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = camera,
                properties = mapProperties,
                uiSettings = MapUiSettings(
                    scrollGesturesEnabled = false,
                    zoomGesturesEnabled = false,
                    myLocationButtonEnabled = false,
                    mapToolbarEnabled = false,
                    compassEnabled = false,
                ),
            ) {
                val marker = remember(place.id, place.latitude, place.longitude) { MarkerState(position = point) }
                Circle(
                    center = point,
                    radius = place.radiusMeters,
                    strokeColor = MaterialTheme.colorScheme.primary,
                    fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
                )
                Marker(
                    state = marker,
                    title = place.name,
                    icon = placeIcon,
                    anchor = Offset(0.5f, 0.5f),
                )
            }
            MapOverlayPillButton(
                text = stringResource(R.string.map_fullscreen_open),
                icon = Icons.Outlined.OpenInFull,
                onClick = { fullScreenOpen = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
            )
        }
    }

    if (fullScreenOpen) {
        Dialog(
            onDismissRequest = { fullScreenOpen = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            val colors = MaterialTheme.colorScheme
            val scope = rememberCoroutineScope()
            val fullCamera = rememberCameraPositionState {
                position = CameraPosition.fromLatLngZoom(point, 15f)
            }
            Surface(modifier = Modifier.fillMaxSize(), color = colors.background) {
                Box(Modifier.fillMaxSize()) {
                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = fullCamera,
                        properties = mapProperties,
                        uiSettings = MapUiSettings(
                            scrollGesturesEnabled = true,
                            zoomGesturesEnabled = true,
                            myLocationButtonEnabled = false,
                            mapToolbarEnabled = false,
                            compassEnabled = false,
                        ),
                    ) {
                        val marker = remember(place.id, place.latitude, place.longitude) { MarkerState(position = point) }
                        Circle(
                            center = point,
                            radius = place.radiusMeters,
                            strokeColor = colors.primary,
                            fillColor = colors.primary.copy(alpha = 0.14f),
                        )
                        Marker(
                            state = marker,
                            title = place.name,
                            icon = placeIcon,
                            anchor = Offset(0.5f, 0.5f),
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = stitch.cardTactile.copy(alpha = 0.94f),
                        border = BorderStroke(1.dp, stitch.hairline),
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .fillMaxWidth()
                            .anveContentWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            IconButton(onClick = { fullScreenOpen = false }) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.map_fullscreen_close))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = place.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = stringResource(R.string.places_radius, place.radiusMeters.toInt()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    Surface(
                        onClick = {
                            scope.launch {
                                fullCamera.animate(CameraUpdateFactory.newLatLngZoom(point, 15f))
                            }
                        },
                        shape = CircleShape,
                        color = stitch.cardTactile.copy(alpha = 0.94f),
                        contentColor = colors.primary,
                        border = BorderStroke(1.dp, stitch.hairline),
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            .padding(16.dp)
                            .size(48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.MyLocation, contentDescription = stringResource(R.string.places_map_preview_title), modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceEditor(place: PlaceDto?, pending: Boolean, noticeRes: Int?, onCancel: () -> Unit, onSave: (PlaceInputDto) -> Unit) {
    // Draft fields and the pending discard confirm survive process death. The
    // editor already guards a deliberate discard with a confirm dialog, but
    // with plain remember a mid-edit reclaim would drop everything typed
    // anyway — an inconsistent guard, and not the guarantee JourneyScreen's
    // create flow normalized. Keying on the place id keeps the
    // reset-when-switching-place semantics the plain remember had.
    var name by rememberSaveable(place?.id) { mutableStateOf(place?.name ?: "") }
    var latitude by rememberSaveable(place?.id) { mutableStateOf(place?.latitude?.toString() ?: "") }
    var longitude by rememberSaveable(place?.id) { mutableStateOf(place?.longitude?.toString() ?: "") }
    var radius by rememberSaveable(place?.id) { mutableStateOf(place?.radiusMeters ?: 150.0) }
    var sharedWithCircle by rememberSaveable(place?.id) { mutableStateOf(place?.sharedWithCircle ?: false) }
    var confirmDiscard by rememberSaveable(place?.id) { mutableStateOf(false) }
    val lat = latitude.toDoubleOrNull()
    val lon = longitude.toDoubleOrNull()
    val valid = validPlaceInput(name, lat, lon, radius)
    var searchQuery by rememberSaveable(place?.id) { mutableStateOf("") }
    var searchOutcome by remember(place?.id) { mutableStateOf<PlaceSearchOutcome?>(null) }
    var searching by remember(place?.id) { mutableStateOf(false) }
    val point = if (lat != null && lon != null && lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) LatLng(lat, lon) else null
    // Composed after the outer BackHandler, so this handler wins while the
    // editor is on screen; it only asks when the draft actually differs from
    // what was loaded, otherwise back still closes silently.
    val hasChanges = name != (place?.name ?: "") ||
        latitude != (place?.latitude?.toString() ?: "") ||
        longitude != (place?.longitude?.toString() ?: "") ||
        radius != (place?.radiusMeters ?: 150.0) ||
        sharedWithCircle != (place?.sharedWithCircle ?: false)
    val requestBack = {
        when {
            pending -> onCancel()
            hasChanges -> confirmDiscard = true
            else -> onCancel()
        }
    }
    BackHandler(onBack = requestBack)
    val camera = rememberCameraPositionState {
        position = if (point == null) CameraPosition.fromLatLngZoom(LatLng(0.0, 0.0), 1f) else CameraPosition.fromLatLngZoom(point, 14f)
    }
    // Current-location escape hatch: a person who cannot name coordinates has
    // no way to place a marker without it, and geocoding a name is out of
    // scope. Manual entry stays the primary path — the fix only fills the two
    // fields and moves the camera to the same point, never saves on its own.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Read fresh at click time; a cached permission boolean is how a tracker
    // keeps believing it may collect (AndroidLocationPermissions contract).
    val permissions = remember(context) { AndroidLocationPermissions(context) }
    val provider = remember(context) { FusedLocationProvider(context) }
    val searcher = remember(context) { AndroidPlaceSearcher(context) }
    // Debounced so typing never fires one lookup per keystroke; a newer query
    // cancels the older one, so a slow answer cannot overwrite a fresher one.
    LaunchedEffect(searchQuery) {
        val normalized = normalizePlaceQuery(searchQuery)
        if (normalized == null) {
            searchOutcome = null
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(SEARCH_DEBOUNCE_MS)
        searchOutcome = searcher.search(normalized)
        searching = false
    }
    var locationNote by remember(place?.id) { mutableStateOf<Int?>(null) }
    var locationError by remember(place?.id) { mutableStateOf<Int?>(null) }
    var locating by remember(place?.id) { mutableStateOf(false) }
    fun fetchCurrentFix() {
        locationNote = null
        locationError = null
        locating = true
        scope.launch {
            // First fix of a fresh request, not a cached lastLocation: the
            // fused provider here deliberately never returns one, and a
            // "current location" button that fills stale coordinates would
            // misplace the marker. Timeout keeps manual entry viable when GPS
            // cannot deliver.
            val fix = try {
                withTimeoutOrNull(CURRENT_FIX_TIMEOUT_MS) {
                    provider.updates(
                        LocationRequestOptions(
                            intervalMillis = CURRENT_FIX_INTERVAL_MS,
                            minDistanceMeters = 0f,
                            highAccuracy = true,
                        ),
                    ).first()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            locating = false
            if (fix == null) {
                locationError = R.string.places_editor_location_unavailable
            } else {
                latitude = fix.latitude.toString()
                longitude = fix.longitude.toString()
                // Moving the camera only when a map exists (same condition as
                // the map item below) keeps the no-map build free of any
                // Maps SDK camera machinery.
                if (BuildConfig.MAPS_API_KEY.isNotBlank()) {
                    camera.position = CameraPosition.fromLatLngZoom(LatLng(fix.latitude, fix.longitude), 14f)
                }
            }
        }
    }
    val fixLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        // The launcher result alone lies on Android 12+: choosing
        // "approximate" denies the requested fine permission while still
        // granting coarse. Read the OS state instead, so an approximate fix
        // still fills the fields and only a real denial shows the note —
        // first or permanent, same one line, since the manual fields are the
        // way out either way.
        when (permissions.permissionState()) {
            LocationPermissionState.GRANTED_PRECISE, LocationPermissionState.GRANTED_APPROXIMATE -> fetchCurrentFix()
            else -> locationNote = R.string.places_editor_location_permission_note
        }
    }
    fun useCurrentLocation() {
        if (locating) return
        if (permissions.permissionState() == LocationPermissionState.GRANTED_PRECISE) {
            fetchCurrentFix()
        } else {
            // Coarse-only or denied: the OS dialog is the fine upgrade path.
            locationNote = R.string.places_editor_location_permission_note
            fixLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    var showManualCoords by rememberSaveable(place?.id) {
        mutableStateOf(BuildConfig.MAPS_API_KEY.isBlank())
    }
    var inlineMapPanEnabled by remember(place?.id) { mutableStateOf(false) }
    var fullScreenPickerOpen by rememberSaveable(place?.id) { mutableStateOf(false) }
    val presetNames = listOf(
        stringResource(R.string.places_preset_home),
        stringResource(R.string.places_preset_school),
        stringResource(R.string.places_preset_work),
        stringResource(R.string.places_preset_grandparents),
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (place == null) R.string.places_add else R.string.places_edit)) },
                navigationIcon = {
                    IconButton(onClick = requestBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = LocalStitchColors.current.hairline)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .anveContentWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = {
                                    if (valid && lat != null && lon != null) {
                                        onSave(PlaceInputDto(name.trim(), lat, lon, radius, sharedWithCircle))
                                    }
                                },
                                enabled = valid && !pending,
                                shape = CircleShape,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text(stringResource(R.string.action_save)) }
                            TextButton(
                                onClick = onCancel,
                                enabled = !pending,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text(stringResource(R.string.action_cancel)) }
                        }
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { editorPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(editorPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .anveContentWidth()
                    .verticalScroll(rememberScrollState(), enabled = !inlineMapPanEnabled)
                    .imePadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PlaceSearchCard(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    outcome = searchOutcome,
                    searching = searching,
                    enabled = !pending,
                    onPick = { suggestion ->
                        latitude = suggestion.latitude.toString()
                        longitude = suggestion.longitude.toString()
                        if (name.isBlank()) name = suggestion.title.take(80)
                        if (BuildConfig.MAPS_API_KEY.isNotBlank()) {
                            camera.position = CameraPosition.fromLatLngZoom(LatLng(suggestion.latitude, suggestion.longitude), 16f)
                        }
                        searchQuery = ""
                        searchOutcome = null
                    },
                )
                val stitch = LocalStitchColors.current
                OutlinedCard(
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, stitch.hairline),
                    colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            stringResource(R.string.places_quick_name_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            presetNames.forEach { preset ->
                                FilterChip(
                                    selected = name.trim() == preset,
                                    onClick = { if (!pending) name = preset },
                                    enabled = !pending,
                                    label = { Text(preset) },
                                )
                            }
                        }
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            shape = RoundedCornerShape(14.dp),
                            label = { Text(stringResource(R.string.places_editor_name_label)) },
                            enabled = !pending,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                OutlinedCard(
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, stitch.hairline),
                    colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (BuildConfig.MAPS_API_KEY.isNotBlank()) {
                            val mapProperties = rememberAnVeMapProperties()
                            val placeIcon = rememberPlaceMarkerIcon(name.ifBlank { stringResource(R.string.places_title) })
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(248.dp)
                                    .clip(RoundedCornerShape(18.dp)),
                            ) {
                                GoogleMap(
                                    modifier = Modifier.fillMaxSize(),
                                    cameraPositionState = camera,
                                    properties = mapProperties,
                                    uiSettings = MapUiSettings(
                                        scrollGesturesEnabled = inlineMapPanEnabled,
                                        zoomGesturesEnabled = true,
                                        myLocationButtonEnabled = false,
                                        mapToolbarEnabled = false,
                                        compassEnabled = false,
                                    ),
                                    onMapClick = {
                                        if (!pending) {
                                            latitude = it.latitude.toString()
                                            longitude = it.longitude.toString()
                                        }
                                    },
                                ) {
                                    point?.let {
                                        val marker = remember(it.latitude, it.longitude) { MarkerState(position = it) }
                                        Circle(
                                            center = it,
                                            radius = radius,
                                            strokeColor = MaterialTheme.colorScheme.primary,
                                            fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                        )
                                        Marker(
                                            state = marker,
                                            title = name.ifBlank { stringResource(R.string.places_title) },
                                            icon = placeIcon,
                                            anchor = Offset(0.5f, 0.5f),
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .align(Alignment.TopCenter)
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    MapOverlayPillButton(
                                        text = stringResource(
                                            if (inlineMapPanEnabled) R.string.map_pan_lock else R.string.map_pan_unlock,
                                        ),
                                        icon = if (inlineMapPanEnabled) Icons.Outlined.Lock else Icons.Outlined.PanToolAlt,
                                        active = inlineMapPanEnabled,
                                        onClick = { inlineMapPanEnabled = !inlineMapPanEnabled },
                                    )
                                    MapOverlayPillButton(
                                        text = stringResource(R.string.map_fullscreen_open),
                                        icon = Icons.Outlined.OpenInFull,
                                        onClick = {
                                            inlineMapPanEnabled = false
                                            fullScreenPickerOpen = true
                                        },
                                    )
                                }
                                if (inlineMapPanEnabled) {
                                    Surface(
                                        shape = CircleShape,
                                        color = stitch.cardTactile.copy(alpha = 0.94f),
                                        border = BorderStroke(1.dp, stitch.hairline),
                                        shadowElevation = 3.dp,
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.map_pan_unlocked_hint),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                            }
                            Text(
                                stringResource(R.string.places_editor_map_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            AnVePlacesSceneIllustration(
                                modifier = Modifier.fillMaxWidth().height(128.dp),
                            )
                            Text(
                                stringResource(R.string.places_editor_no_map),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.places_map_cta_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = { useCurrentLocation() },
                            enabled = !pending && !locating,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.places_editor_use_current_location))
                        }
                        locationNote?.let { res ->
                            Text(
                                stringResource(res),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        locationError?.let { res -> AnveErrorNotice(stringResource(res)) }
                        if (locating) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(Modifier.size(24.dp))
                                Text(
                                    stringResource(R.string.action_processing),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                OutlinedCard(
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, stitch.hairline),
                    colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            // About 80 m per minute at an ordinary walking pace, so the
                            // radius reads as something a family member can picture.
                            stringResource(R.string.places_radius_walk, radius.toInt(), maxOf(1, (radius / 80.0).roundToInt())),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Slider(
                            value = radius.toFloat(),
                            onValueChange = { radius = it.toDouble() },
                            valueRange = 50f..5000f,
                            enabled = !pending,
                        )
                        if (place != null) {
                            Text(
                                stringResource(R.string.places_editor_radius_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                OutlinedCard(
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, stitch.hairline),
                    colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(
                                value = sharedWithCircle,
                                enabled = !pending,
                                role = Role.Switch,
                                onValueChange = { sharedWithCircle = it },
                            )
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.places_family_share_toggle_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = stringResource(R.string.places_family_share_toggle_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = sharedWithCircle,
                            enabled = !pending,
                            onCheckedChange = null,
                        )
                    }
                }
                val coordsToggleState = stringResource(
                    if (showManualCoords) R.string.action_state_expanded else R.string.action_state_collapsed,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button) { showManualCoords = !showManualCoords }
                            .semantics { stateDescription = coordsToggleState },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.places_manual_coords_toggle),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Icon(
                            imageVector = if (showManualCoords) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                        )
                    }
                    AnimatedVisibility(visible = showManualCoords) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = latitude,
                                onValueChange = { latitude = it },
                                label = { Text(stringResource(R.string.places_editor_lat_label)) },
                                enabled = !pending,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = longitude,
                                onValueChange = { longitude = it },
                                label = { Text(stringResource(R.string.places_editor_lon_label)) },
                                enabled = !pending,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                noticeRes?.let { res -> AnveErrorNotice(stringResource(res)) }
                // The Save button locks on invalid coordinates; say what is wrong while
                // the user is still in the fields, not only through a disabled button.
                val coordsInvalid = (latitude.isNotBlank() || longitude.isNotBlank()) &&
                    !(lat != null && lon != null && lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0)
                // Same explanation style as the coordinate check: the name constraint
                // (1–80 codepoints, per validPlaceInput) is stated where the user is
                // typing instead of only through a silently locked Save button.
                val nameInvalid = name.trim().isEmpty() || name.trim().codePointCount(0, name.trim().length) > 80
                if (coordsInvalid) {
                    Text(
                        stringResource(R.string.places_editor_invalid_coords),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (nameInvalid) {
                    Text(
                        stringResource(R.string.places_editor_invalid_name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (pending) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text(
                            stringResource(R.string.action_processing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.places_discard_title)) },
        text = {
            Text(
                stringResource(R.string.places_discard_body),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            // Discard is destructive to the typed draft; same confirm pattern
            // as the delete dialog in this file.
            TextButton(
                onClick = { confirmDiscard = false; onCancel() },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.places_discard_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(
                onClick = { confirmDiscard = false },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.action_cancel)) }
        },
    )
    if (fullScreenPickerOpen && BuildConfig.MAPS_API_KEY.isNotBlank()) {
        PlaceFullScreenPickerDialog(
            name = name.ifBlank { stringResource(R.string.places_title) },
            point = point,
            radius = radius,
            pending = pending,
            onPickPoint = { picked ->
                if (!pending) {
                    latitude = picked.latitude.toString()
                    longitude = picked.longitude.toString()
                }
            },
            onRadiusChange = { radius = it },
            onDismiss = {
                point?.let {
                    camera.position = CameraPosition.fromLatLngZoom(it, 15f)
                }
                fullScreenPickerOpen = false
            },
        )
    }
}

@Composable
private fun PlaceFullScreenPickerDialog(
    name: String,
    point: LatLng?,
    radius: Double,
    pending: Boolean,
    onPickPoint: (LatLng) -> Unit,
    onRadiusChange: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val colors = MaterialTheme.colorScheme
        val stitch = LocalStitchColors.current
        val scope = rememberCoroutineScope()
        val fullCamera = rememberCameraPositionState {
            position = if (point == null) {
                CameraPosition.fromLatLngZoom(LatLng(16.0, 107.5), 5f)
            } else {
                CameraPosition.fromLatLngZoom(point, 15f)
            }
        }
        val placeIcon = rememberPlaceMarkerIcon(name)
        Surface(modifier = Modifier.fillMaxSize(), color = colors.background) {
            Box(Modifier.fillMaxSize()) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = fullCamera,
                    properties = rememberAnVeMapProperties(),
                    uiSettings = MapUiSettings(
                        scrollGesturesEnabled = true,
                        zoomGesturesEnabled = true,
                        myLocationButtonEnabled = false,
                        mapToolbarEnabled = false,
                        compassEnabled = false,
                    ),
                    onMapClick = { clicked ->
                        if (!pending) onPickPoint(clicked)
                    },
                ) {
                    point?.let {
                        val marker = remember(it.latitude, it.longitude) { MarkerState(position = it) }
                        Circle(
                            center = it,
                            radius = radius,
                            strokeColor = colors.primary,
                            fillColor = colors.primary.copy(alpha = 0.14f),
                        )
                        Marker(
                            state = marker,
                            title = name,
                            icon = placeIcon,
                            anchor = Offset(0.5f, 0.5f),
                        )
                    }
                }

                // Floating top bar with hint and Done action.
                Surface(
                    shape = CircleShape,
                    color = stitch.cardTactile.copy(alpha = 0.94f),
                    border = BorderStroke(1.dp, stitch.hairline),
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .fillMaxWidth()
                        .anveContentWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.map_fullscreen_close))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.places_map_picker_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = stringResource(R.string.places_map_picker_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Button(
                            onClick = onDismiss,
                            shape = CircleShape,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.places_map_picker_done))
                        }
                    }
                }

                if (point != null) {
                    Surface(
                        onClick = {
                            scope.launch {
                                fullCamera.animate(CameraUpdateFactory.newLatLngZoom(point, 15.5f))
                            }
                        },
                        shape = CircleShape,
                        color = stitch.cardTactile.copy(alpha = 0.94f),
                        contentColor = colors.primary,
                        border = BorderStroke(1.dp, stitch.hairline),
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            .padding(end = 16.dp, bottom = 148.dp)
                            .size(48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.MyLocation, contentDescription = stringResource(R.string.places_map_preview_title), modifier = Modifier.size(20.dp))
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = stitch.cardTactile,
                    border = BorderStroke(1.dp, stitch.hairline),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .fillMaxWidth()
                        .anveContentWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.places_radius_walk,
                                radius.toInt(),
                                maxOf(1, (radius / 80.0).roundToInt()),
                            ),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Slider(
                            value = radius.toFloat(),
                            onValueChange = { onRadiusChange(it.toDouble()) },
                            valueRange = 50f..5000f,
                            enabled = !pending,
                        )
                    }
                }
            }
        }
    }
}

private const val SEARCH_DEBOUNCE_MS = 400L

@Composable
private fun PlaceSearchCard(
    query: String,
    onQueryChange: (String) -> Unit,
    outcome: PlaceSearchOutcome?,
    searching: Boolean,
    enabled: Boolean,
    onPick: (PlaceSuggestion) -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                enabled = enabled,
                singleLine = true,
                label = { Text(stringResource(R.string.places_search_label)) },
                placeholder = { Text(stringResource(R.string.places_search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.places_search_clear))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (searching) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(R.string.places_search_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when (outcome) {
                is PlaceSearchOutcome.Found -> if (!searching) {
                    outcome.suggestions.forEach { suggestion ->
                        ListItem(
                            headlineContent = { Text(suggestion.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(suggestion.subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                            leadingContent = { Icon(Icons.Outlined.Place, contentDescription = null) },
                            modifier = Modifier.heightIn(min = 56.dp).clickable(enabled = enabled) { onPick(suggestion) },
                        )
                    }
                }
                PlaceSearchOutcome.NoResult -> if (!searching) SearchNote(R.string.places_search_none)
                PlaceSearchOutcome.Failed -> if (!searching) SearchNote(R.string.places_search_failed)
                PlaceSearchOutcome.Unavailable -> if (!searching) SearchNote(R.string.places_search_unavailable)
                null -> Unit
            }
            Text(
                stringResource(R.string.places_search_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SearchNote(res: Int) {
    Text(
        stringResource(res),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
