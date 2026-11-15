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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.anve.app.ui.common.AnveErrorNotice
import com.anve.app.ui.common.AnveTime
import com.anve.app.ui.home.HOME_FAB_CLEARANCE
import com.anve.app.ui.theme.isDarkSurface
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
fun PlacesScreen(container: AppContainer, onOpenLocation: () -> Unit = {}) {
    val session by container.sessionStore.session.collectAsStateWithLifecycle(initialValue = null)
    val owner = session?.userId
    val vm: PlacesViewModel = viewModel(factory = PlacesViewModel.factory(container.placesRepository))
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(owner, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.activate(owner)
                Lifecycle.Event.ON_STOP -> vm.activate(null)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); vm.activate(null) }
    }
    LaunchedEffect(owner, lifecycle) {
        if (owner != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(10_000)
                vm.refresh()
            }
        }
    }
    if (owner == null || state.owner != owner) {
        // Session not re-emitted yet (fresh process or account switch): state
        // the wait instead of a blank frame, same wording as the account area.
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) { Text(stringResource(R.string.home_checking_account)) }
        return
    }
    PlacesContent(state, vm, onOpenLocation)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlacesContent(state: PlacesState, vm: PlacesViewModel, onOpenLocation: () -> Unit = {}) {
    val owner = state.owner ?: return
    val selected = state.places.firstOrNull { it.id == state.selectedId }
    var showDetails by remember(owner) { mutableStateOf(false) }
    val visibleEvents = if (showDetails) state.events.filter { it.placeId == selected?.id } else state.events
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
                        IconButton(onClick = { showDetails = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.places_back_content_desc))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh, enabled = !state.pending && !state.loading) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.places_refresh))
                    }
                    if (showDetails && selected?.ownerId == owner) {
                        Box {
                            IconButton(onClick = { showActions = true }, enabled = !state.pending) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.places_actions_content_desc))
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
        // Pull to refresh alongside the top-bar action: same repository call,
        // two affordances. The refresh button stays for users who do not use
        // the gesture.
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = HOME_FAB_CLEARANCE),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!showDetails) {
                    item { Text(stringResource(R.string.places_monitoring_notice)) }
                    state.noticeRes?.let { res -> item { AnveErrorNotice(stringResource(res)) } }
                    state.errorRes?.let { res -> item { AnveErrorNotice(stringResource(res)) } }
                    item {
                        Button(
                            onClick = {
                                editingPlace = null
                                editConfirmation = state.confirmation
                                editing = true
                            },
                            enabled = !state.pending,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.places_add))
                        }
                    }
                    if (state.loading || state.pending) item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                            Text(
                                stringResource(if (state.loading) R.string.action_loading else R.string.action_processing),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (state.places.isEmpty() && !state.loading && state.errorRes == null) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    AnVePlacesSceneIllustration(
                                        modifier = Modifier.fillMaxWidth().height(124.dp),
                                    )
                                    Text(
                                        stringResource(R.string.places_empty),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                    items(state.places, key = { it.id }) { place ->
                        val isMine = place.ownerId == owner
                        val label = if (isMine) {
                            stringResource(R.string.places_owner_you)
                        } else {
                            state.sharing.members.firstOrNull { it.userId == place.ownerId }?.displayName ?: stringResource(R.string.places_member_fallback)
                        }
                        val colors = MaterialTheme.colorScheme
                        OutlinedCard(
                            onClick = {
                                if (!state.pending) {
                                    vm.select(place.id)
                                    showDetails = true
                                }
                            },
                            enabled = !state.pending,
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, colors.outlineVariant),
                            colors = CardDefaults.outlinedCardColors(containerColor = colors.surface),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (isMine) colors.primaryContainer else colors.secondaryContainer,
                                    contentColor = if (isMine) colors.onPrimaryContainer else colors.onSecondaryContainer,
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (isMine) Icons.Default.Home else Icons.Default.Place,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(place.name, style = MaterialTheme.typography.titleSmall)
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
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else if (selected == null) {
                    state.errorRes?.let { res -> item { AnveErrorNotice(stringResource(res)) } }
                    item { Text(stringResource(R.string.places_missing)) }
                    item {
                        TextButton(onClick = { showDetails = false; vm.refresh() }, enabled = !state.loading) {
                            Text(stringResource(R.string.places_refresh_list))
                        }
                    }
                } else {
                    item { Text(stringResource(R.string.places_radius, selected.radiusMeters.toInt()), style = MaterialTheme.typography.titleMedium) }
                    if (BuildConfig.MAPS_API_KEY.isNotBlank()) {
                        item(key = "preview-${selected.id}") { PlacePreview(selected) }
                    }
                    state.noticeRes?.let { res -> item { AnveErrorNotice(stringResource(res)) } }
                    state.errorRes?.let { res -> item { AnveErrorNotice(stringResource(res)) } }
                    if (state.loading || state.pending) item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                            Text(
                                stringResource(if (state.loading) R.string.action_loading else R.string.action_processing),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (selected.ownerId == owner) {
                        val otherMembers = state.sharing.members.filter { it.userId != owner }
                        item {
                            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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
                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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
                                    stringResource(R.string.places_alerts_section_title),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                eligibleMembers.forEach { person ->
                                    // Row-level checkbox for the same TalkBack reason as the
                                    // share rows above; the inner checkbox stays indicative.
                                    ListItem(
                                        modifier = Modifier.toggleable(
                                            value = person.userId in recipients,
                                            enabled = !state.pending,
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
                                                enabled = !state.pending,
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
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            configureBaseline = state.confirmation
                                            vm.configure(selected.id, recipients.intersect(state.eligibleRecipients))
                                        },
                                        enabled = !state.pending && state.sharing.consentEnabled,
                                    ) {
                                        Text(if (state.monitor == null) stringResource(R.string.places_monitor_enable) else stringResource(R.string.places_monitor_save))
                                    }
                                    if (state.monitor != null) {
                                        TextButton(onClick = { vm.stop(selected.id) }, enabled = !state.pending) { Text(stringResource(R.string.places_monitor_stop)) }
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
                        item { Text(stringResource(R.string.places_events_empty)) }
                    }
                    items(visibleEvents, key = { "event-${it.id}" }) { event ->
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
                        val colors = MaterialTheme.colorScheme
                        val isEnter = event.type == "ENTER"
                        OutlinedCard(
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, colors.outlineVariant),
                            colors = CardDefaults.outlinedCardColors(containerColor = colors.surface),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (isEnter) colors.primaryContainer else colors.secondaryContainer,
                                    contentColor = if (isEnter) colors.onPrimaryContainer else colors.onSecondaryContainer,
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Notifications,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
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
    deleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text(stringResource(R.string.places_delete_title)) },
            text = { Text(stringResource(R.string.places_delete_body)) },
            confirmButton = {
                // Same destructive-confirm pattern as the circle dialogs:
                // error-colored text action plus a neutral Hủy.
                TextButton(onClick = {
                    deleteId = null
                    showDetails = false
                    vm.delete(id)
                }) { Text(stringResource(R.string.places_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.action_cancel)) } },
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
            Icon(Icons.Default.Info, null, Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.places_consent_notice))
                TextButton(onClick = onOpenLocation) {
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
            TextButton(onClick = onToggle, modifier = Modifier.semantics { stateDescription = disclosureState }) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
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
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(point, 14f) }
    // View-only preview of the selected place inside the details LazyColumn:
    // there is nothing to operate on, so every gesture is off — a one-finger
    // pan or a drag-zoom would swallow the list's scroll instead of moving
    // this map.
    GoogleMap(
        modifier = Modifier.fillMaxWidth().height(200.dp),
        cameraPositionState = camera,
        uiSettings = MapUiSettings(scrollGesturesEnabled = false, zoomGesturesEnabled = false),
    ) {
        val marker = remember(place.id, place.latitude, place.longitude) { MarkerState(position = point) }
        Marker(state = marker, title = place.name)
        Circle(center = point, radius = place.radiusMeters, strokeColor = MaterialTheme.colorScheme.primary, fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f))
    }
}

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
        radius != (place?.radiusMeters ?: 150.0)
    BackHandler {
        when {
            pending -> onCancel()
            hasChanges -> confirmDiscard = true
            else -> onCancel()
        }
    }
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
    val presetNames = listOf(
        stringResource(R.string.places_preset_home),
        stringResource(R.string.places_preset_school),
        stringResource(R.string.places_preset_work),
        stringResource(R.string.places_preset_grandparents),
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = HOME_FAB_CLEARANCE),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                stringResource(if (place == null) R.string.places_add else R.string.places_edit),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item {
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
        }
        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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
                        verticalArrangement = Arrangement.spacedBy(4.dp),
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
                        label = { Text(stringResource(R.string.places_editor_name_label)) },
                        enabled = !pending,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (BuildConfig.MAPS_API_KEY.isNotBlank()) {
                        // The editor's map is one item in the LazyColumn form, so pan
                        // would swallow the form's scroll. Tap-to-select stays wired
                        // through onMapClick, and the position can still be set with
                        // the current-location action and the coordinate fields.
                        GoogleMap(
                            modifier = Modifier.fillMaxWidth().height(240.dp),
                            cameraPositionState = camera,
                            uiSettings = MapUiSettings(scrollGesturesEnabled = false),
                            onMapClick = {
                                if (!pending) {
                                    latitude = it.latitude.toString()
                                    longitude = it.longitude.toString()
                                }
                            },
                        ) {
                            point?.let {
                                val marker = remember(it.latitude, it.longitude) { MarkerState(position = it) }
                                Marker(state = marker, title = name)
                                Circle(
                                    center = it,
                                    radius = radius,
                                    strokeColor = MaterialTheme.colorScheme.primary,
                                    fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
                                )
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
        }
        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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
        }
        item {
            val coordsToggleState = stringResource(
                if (showManualCoords) R.string.action_state_expanded else R.string.action_state_collapsed,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { showManualCoords = !showManualCoords }
                        .semantics { stateDescription = coordsToggleState },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.places_manual_coords_toggle),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Icon(
                        imageVector = if (showManualCoords) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
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
        }
        noticeRes?.let { res -> item { AnveErrorNotice(stringResource(res)) } }
        // The Save button locks on invalid coordinates; say what is wrong while
        // the user is still in the fields, not only through a disabled button.
        val coordsInvalid = (latitude.isNotBlank() || longitude.isNotBlank()) &&
            !(lat != null && lon != null && lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0)
        // Same explanation style as the coordinate check: the name constraint
        // (1–80 codepoints, per validPlaceInput) is stated where the user is
        // typing instead of only through a silently locked Save button.
        val nameInvalid = name.trim().isEmpty() || name.trim().codePointCount(0, name.trim().length) > 80
        if (coordsInvalid) {
            item {
                Text(
                    stringResource(R.string.places_editor_invalid_coords),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (nameInvalid) {
            item {
                Text(
                    stringResource(R.string.places_editor_invalid_name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (pending) item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(
                    stringResource(R.string.action_processing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (valid && lat != null && lon != null) onSave(PlaceInputDto(name.trim(), lat, lon, radius)) }, enabled = valid && !pending) { Text(stringResource(R.string.action_save)) }
                TextButton(onClick = onCancel, enabled = !pending) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.places_discard_title)) },
        text = { Text(stringResource(R.string.places_discard_body)) },
        confirmButton = {
            // Discard is destructive to the typed draft; same confirm pattern
            // as the delete dialog in this file.
            TextButton(onClick = { confirmDiscard = false; onCancel() }) {
                Text(stringResource(R.string.places_discard_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
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
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.places_search_clear))
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
                            leadingContent = { Icon(Icons.Default.Place, contentDescription = null) },
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
