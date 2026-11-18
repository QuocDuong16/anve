package com.anve.app.ui.history

import android.content.res.Configuration
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.PanToolAlt
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
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
import com.anve.app.data.api.HistoryPageDto
import com.anve.app.data.api.HistoryPointDto
import com.anve.app.data.api.HistoryRetentionDto
import com.anve.app.data.api.PlaceDto
import com.anve.app.data.location.HistoryDataSource
import com.anve.app.data.location.HistoryMember
import com.anve.app.ui.brand.AnVeHistorySceneIllustration
import com.anve.app.ui.common.AnVeCheckingState
import com.anve.app.ui.common.AnVeSectionHeader
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
import com.anve.app.ui.map.findMatchingPlace
import com.anve.app.ui.map.fitCameraToPoints
import com.anve.app.ui.map.rememberAnVeMapProperties
import com.anve.app.ui.map.rememberHistoryPointMarkerIcon
import com.anve.app.ui.map.rememberPlaceMarkerIcon
import com.anve.app.ui.theme.AnVeTheme
import com.anve.app.ui.theme.LocalStitchColors
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val MAX_GAP_SECONDS = 900L
private const val MAX_ACCURACY_METERS = 100.0
private const val MAX_SPEED_METERS_PER_SECOND = 70.0
// Rows before the end of the timeline at which the next page starts loading,
// so the reader reaches fresh content before hitting a hard stop.
private const val LOAD_MORE_LAST_ROWS = 4
private enum class DeletionScope { DAY, RANGE, ALL }

private fun formatMinute(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

// Same dd/MM/yyyy date shape as the other screens (SOS, Live Map), instead
// of LocalDate's ISO toString.
private val historyDateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

/** Render only observed, accurate samples that can plausibly belong to one segment. */
fun historySegments(points: List<HistoryPointDto>): List<List<HistoryPointDto>> {
    val result = mutableListOf<MutableList<HistoryPointDto>>()
    var previous: HistoryPointDto? = null
    points.forEach { point ->
        val time = runCatching { Instant.parse(point.recordedAt) }.getOrNull()
        val valid = time != null && point.latitude.isFinite() && point.longitude.isFinite() &&
            point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0 &&
            point.horizontalAccuracyMeters.isFinite() && point.horizontalAccuracyMeters in 0.0..MAX_ACCURACY_METERS && !point.isMock
        if (!valid) {
            previous = null
        } else {
            val old = previous
            val seconds = old?.let { time.epochSecond - Instant.parse(it.recordedAt).epochSecond }
            val separated = old == null || old.segmentId != point.segmentId || seconds == null ||
                seconds <= 0 || seconds > MAX_GAP_SECONDS || distanceMeters(old, point) > seconds * MAX_SPEED_METERS_PER_SECOND
            if (separated) result.add(mutableListOf())
            result.last().add(point)
            previous = point
        }
    }
    return result
}

private fun HistoryPointDto.latLngOrNull(): LatLng? {
    if (!latitude.isFinite() || !longitude.isFinite()) return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    return LatLng(latitude, longitude)
}

private fun distanceMeters(a: HistoryPointDto, b: HistoryPointDto): Double =
    com.anve.app.tracking.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)

@Composable
fun HistoryScreen(container: AppContainer) {
    val session by container.sessionStore.session.collectAsStateWithLifecycle(initialValue = null)
    val owner = session?.userId
    val vm: HistoryViewModel = viewModel(
        factory = HistoryViewModel.factory(
            repository = container.historyRepository,
            placesDataSource = container.placesRepository,
        ),
    )
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
        onDispose {
            lifecycle.removeObserver(observer)
            vm.activate(null)
        }
    }
    LaunchedEffect(owner, state.member, lifecycle) {
        val member = state.member
        if (owner != null && member != null && member != owner) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    vm.recheckAccess(owner, member)
                    delay(10_000)
                }
            }
        }
    }
    if (state.owner != owner || owner == null) {
        // Session not re-emitted yet (fresh process or account switch): state
        // the wait instead of a blank frame, same wording as the account area.
        AnVeCheckingState(stringResource(R.string.home_checking_account))
        return
    }
    HistoryContent(state, vm)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryContent(state: HistoryState, vm: HistoryViewModel) {
    var selectedId by remember(state.owner, state.member, state.date) { mutableStateOf<String?>(null) }
    val selected = state.points.firstOrNull { it.eventId == selectedId }
    var deletionScope by remember(state.owner) { mutableStateOf<DeletionScope?>(null) }
    // Retention decrease pending confirmation, hoisted above the LazyColumn so
    // it survives list updates; saveable because the guard must not silently
    // drop on recreation while the dialog is up.
    var pendingRetentionDays by rememberSaveable { mutableStateOf<Int?>(null) }
    var showActions by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val camera = key(state.owner) { rememberCameraPositionState() }
    var mapLoaded by remember(state.owner) { mutableStateOf(false) }
    val member = state.member
    if (member == null) {
        AnVeCheckingState(stringResource(R.string.home_checking_account))
        return
    }
    var inlineMapPanEnabled by remember(state.owner, member, state.date) { mutableStateOf(false) }
    var fullScreenMapOpen by rememberSaveable(state.owner) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val validPoints = remember(state.points) {
        state.points.filter { it.latLngOrNull() != null }
    }
    val segments = remember(state.points) { historySegments(state.points) }
    val memberLabel = if (member == state.owner) {
        stringResource(R.string.history_member_self)
    } else {
        state.members.firstOrNull { it.ownerId == member }?.displayName ?: stringResource(R.string.history_member_self)
    }
    // Same server-confirmed mutation mechanism as PlacesScreen: the ViewModel
    // emits one confirmation event, the screen resolves the copy in
    // composition (stringResource is a composable call, the snackbar runs in
    // a coroutine) and consumes the event once the snackbar finishes.
    val snackbarHostState = remember { SnackbarHostState() }
    val retentionConfirmation by vm.retentionConfirmation.collectAsStateWithLifecycle()
    val confirmation = retentionConfirmation
    val retentionSavedMessage = if (confirmation != null) {
        stringResource(confirmation.messageRes, confirmation.days)
    } else null
    LaunchedEffect(confirmation) {
        val message = retentionSavedMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        vm.consumeRetentionConfirmation()
    }
    // Auto load-more replaces the old "Trang tiếp theo" button: when the reader
    // is within a few rows of the end, the next page is fetched. Loading and
    // cursor guards are read from vm.state inside the combine — a captured
    // `state` would go stale between recompositions — so a finished page cannot
    // retrigger: success grows the list or exhausts the cursor, and a failure
    // clears the cursor, which forces the condition back through false.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= 0 && lastVisible >= info.totalItemsCount - LOAD_MORE_LAST_ROWS
        }
            .combine(vm.state) { nearEnd, current ->
                nearEnd && !current.loading && !current.mutationPending && current.nextCursor != null
            }
            .collect { ready -> if (ready) vm.next() }
    }
    val gapStarts = remember(segments) {
        segments.drop(1).mapNotNull { it.firstOrNull()?.eventId }.toSet()
    }
    LaunchedEffect(state.owner, member, state.date, validPoints.size, mapLoaded) {
        if (!mapLoaded) return@LaunchedEffect
        val latLngs = validPoints.mapNotNull(HistoryPointDto::latLngOrNull)
        if (latLngs.isEmpty()) {
            camera.move(CameraUpdateFactory.newLatLngZoom(LatLng(0.0, 0.0), 1f))
        } else {
            fitCameraToPoints(camera, latLngs, paddingPx = 84, singleZoom = 15f)
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // Same header pattern as the other screens: title plus icon
            // actions. The host home scaffold already consumed the system
            // insets for this tab, so the app bar adds none of its own.
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                actions = {
                    IconButton(onClick = vm::refresh, enabled = !state.loading && !state.mutationPending) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.history_refresh_content_desc))
                    }
                    if (state.member == state.owner) Box {
                        IconButton(onClick = { showActions = true }, enabled = !state.mutationPending) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.history_actions_content_desc))
                        }
                        DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.history_delete_range_menu)) },
                                onClick = { showActions = false; deletionScope = DeletionScope.RANGE },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.history_delete_day_menu)) },
                                onClick = { showActions = false; deletionScope = DeletionScope.DAY },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.history_delete_all_menu)) },
                                onClick = { showActions = false; deletionScope = DeletionScope.ALL },
                            )
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
            isRefreshing = state.loading,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.fillMaxHeight().fillMaxWidth().anveContentWidth().padding(horizontal = 16.dp),
                state = listState,
                userScrollEnabled = !inlineMapPanEnabled,
                contentPadding = PaddingValues(top = 16.dp, bottom = HOME_FAB_CLEARANCE),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.history_journal_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                FilterChip(
                                    selected = member == state.owner,
                                    onClick = { state.owner?.let { vm.select(it, state.date) } },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.Person,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                    label = { Text(stringResource(R.string.history_member_self)) },
                                    enabled = !state.mutationPending,
                                )
                            }
                            items(state.members, key = { it.ownerId }) { person ->
                                FilterChip(
                                    selected = member == person.ownerId,
                                    onClick = { vm.select(person.ownerId, state.date) },
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                                                .background(
                                                    color = if (member == person.ownerId) {
                                                        colors.primary
                                                    } else {
                                                        colors.surfaceContainerHigh
                                                    },
                                                    shape = CircleShape,
                                                )
                                                .padding(horizontal = 4.dp, vertical = 1.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = person.displayName.take(1).uppercase().ifBlank { "?" },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (member == person.ownerId) {
                                                    colors.onPrimary
                                                } else {
                                                    colors.onSurfaceVariant
                                                },
                                            )
                                        }
                                    },
                                    label = { Text(person.displayName) },
                                    enabled = !state.mutationPending,
                                )
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = { vm.select(member, state.date.minusDays(1)) },
                                enabled = !state.mutationPending,
                            ) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = stringResource(R.string.history_prev_day_content_desc)) }
                            FilledTonalButton(
                                onClick = { showDatePicker = true },
                                enabled = !state.mutationPending,
                                shape = CircleShape,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            ) {
                                Icon(Icons.Outlined.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(historyDateFormatter.format(state.date))
                            }
                            IconButton(
                                onClick = { vm.select(member, state.date.plusDays(1)) },
                                enabled = !state.mutationPending && state.date < LocalDate.now(),
                            ) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = stringResource(R.string.history_next_day_content_desc)) }
                        }
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            item {
                                OutlinedButton(
                                    onClick = { showStartTimePicker = true },
                                    enabled = !state.mutationPending,
                                    shape = CircleShape,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.history_time_from, formatMinute(state.startMinute)))
                                }
                            }
                            item {
                                OutlinedButton(
                                    onClick = { showEndTimePicker = true },
                                    enabled = !state.mutationPending,
                                    shape = CircleShape,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Text(stringResource(R.string.history_time_to, formatMinute(state.endMinute)))
                                }
                            }
                            item {
                                TextButton(
                                    onClick = { vm.selectTimes(0, 1440) },
                                    enabled = !state.mutationPending,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Text(stringResource(R.string.history_time_all_day))
                                }
                            }
                        }
                    }
                }
                item {
                    val retentionDays = (state.effectiveRetentionDays ?: state.retentionDays)?.toString() ?: "—"
                    HistorySummaryCard(
                        pointCount = state.points.size,
                        delayedCount = state.points.count { it.backfill },
                        retentionDaysLabel = retentionDays,
                    )
                }
                if (BuildConfig.MAPS_API_KEY.isNotBlank()) {
                    item {
                        // Inline map locks 1-finger pan by default so LazyColumn scroll
                        // stays smooth; users can unlock pan directly inside the card
                        // or open the edge-to-edge History Map Explorer with a timeline slider.
                        val mapProperties = rememberAnVeMapProperties()
                        OutlinedCard(
                            shape = RoundedCornerShape(22.dp),
                            border = BorderStroke(1.dp, stitch.hairline),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = stitch.cardTactile,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(248.dp)
                                        .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)),
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
                                        onMapLoaded = { mapLoaded = true },
                                    ) {
                                        HistoryMapContent(
                                            segments = segments,
                                            validPoints = validPoints,
                                            selected = selected,
                                            places = state.places,
                                            onSelectPoint = { selectedId = it.eventId },
                                        )
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
                                                fullScreenMapOpen = true
                                            },
                                        )
                                    }
                                    if (inlineMapPanEnabled) {
                                        Surface(
                                            shape = RoundedCornerShape(20.dp),
                                            color = stitch.cardTactile.copy(alpha = 0.94f),
                                            border = BorderStroke(1.dp, stitch.hairline),
                                            shadowElevation = 1.dp,
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                        ) {
                                            Text(
                                                text = stringResource(R.string.map_pan_unlocked_hint),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = colors.onSurface,
                                                maxLines = 2,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                            )
                                        }
                                    }
                                }
                                HorizontalDivider(color = stitch.hairline)
                                FlowRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    TextButton(
                                        onClick = {
                                            val point = selected ?: validPoints.lastOrNull()
                                            val latLng = point?.latLngOrNull()
                                            if (mapLoaded && latLng != null) {
                                                scope.launch {
                                                    camera.animate(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                                                }
                                            }
                                        },
                                        enabled = mapLoaded && validPoints.isNotEmpty(),
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) {
                                        Icon(Icons.Outlined.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.history_center_map),
                                            maxLines = 2,
                                        )
                                    }
                                    TextButton(
                                        onClick = {
                                            val latLngs = validPoints.mapNotNull(HistoryPointDto::latLngOrNull)
                                            if (mapLoaded && latLngs.isNotEmpty()) {
                                                scope.launch {
                                                    fitCameraToPoints(camera, latLngs, paddingPx = 84, singleZoom = 15f, animate = true)
                                                }
                                            }
                                        },
                                        enabled = mapLoaded && validPoints.isNotEmpty(),
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) {
                                        Icon(Icons.Outlined.ZoomOutMap, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.map_fit_route), maxLines = 2)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AnVeHistorySceneIllustration(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(92.dp)
                                    .clip(RoundedCornerShape(18.dp)),
                            )
                            Text(
                                text = stringResource(R.string.history_map_unconfigured),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
                state.errorRes?.let { res ->
                    item {
                        // Retry stays next to the failure instead of only behind
                        // the top-bar refresh icon, in the shared errorContainer
                        // notice.
                        AnveErrorNotice(stringResource(res), onRetry = vm::refresh)
                    }
                }
                if (state.loading || state.mutationPending) item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // A bare spinner between list items says nothing; name what is
                        // happening (SOS progress-row pattern).
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                            Text(
                                stringResource(if (state.loading) R.string.action_loading else R.string.action_processing),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        if (state.loading && state.points.isEmpty()) {
                            StitchLoadingSkeletonCard(rows = 2)
                        }
                    }
                }
                if (state.points.isEmpty() && !state.loading && state.errorRes == null && !state.mutationPending) {
                    item {
                        StitchEmptyStateCard(
                            title = stringResource(R.string.history_empty_warm_title),
                            body = stringResource(R.string.history_empty_warm_body),
                            illustration = {
                                AnVeHistorySceneIllustration(
                                    modifier = Modifier.fillMaxWidth().height(112.dp),
                                )
                            },
                        )
                    }
                }
                if (state.points.isNotEmpty()) {
                    // Teach the tab's primary interaction once, ahead of the
                    // rows, instead of repeating a touch affordance on every
                    // row: the rows already carry selectable() plus a
                    // selection background, but the list as a whole had no
                    // visible cue. An empty list stays quiet — there is
                    // nothing there to tap.
                    item {
                        Text(
                            stringResource(R.string.history_hint_select_point),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                itemsIndexed(state.points, key = { _, it -> it.eventId }) { index, point ->
                    val time = AnveTime.seconds(point.recordedAt, point.recordedAt)
                    val gap = if (point.eventId in gapStarts) stringResource(R.string.history_gap_prefix) else ""
                    val delayed = if (point.backfill) stringResource(R.string.history_backfill_suffix) else ""
                    val mock = if (point.isMock) stringResource(R.string.history_mock_suffix) else ""
                    val placeMatch = remember(point.eventId, state.places) {
                        findMatchingPlace(point.latitude, point.longitude, state.places)
                    }
                    val isSelected = point.eventId == selectedId
                    StitchTimelineItem(
                        icon = if (isSelected) Icons.Outlined.MyLocation else Icons.Outlined.Schedule,
                        nodeContainerColor = if (isSelected) colors.primary else colors.surfaceContainerHigh,
                        nodeContentColor = if (isSelected) colors.onPrimary else colors.onSurfaceVariant,
                        isLast = index == state.points.lastIndex,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) colors.primaryContainer.copy(alpha = 0.28f) else colors.surface,
                            border = if (isSelected) BorderStroke(1.dp, colors.primary) else null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    // Selection must not depend on text color alone:
                                    // selectable() carries the state in semantics for
                                    // accessibility services, and container highlight +
                                    // border keep it visible regardless of hue.
                                    .selectable(selected = isSelected, onClick = { selectedId = point.eventId })
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.history_point_row,
                                        gap,
                                        time,
                                        point.horizontalAccuracyMeters.toInt(),
                                        delayed,
                                        mock,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isSelected) colors.primary else colors.onSurface,
                                )
                                if (placeMatch?.isInside == true) {
                                    StitchSemanticBadge(
                                        text = stringResource(R.string.history_at_place_badge, placeMatch.place.name),
                                        kind = StitchBadgeKind.AT_PLACE,
                                        icon = Icons.Outlined.Place,
                                    )
                                }
                            }
                        }
                    }
                }
                if (state.member == state.owner) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            HorizontalDivider(color = stitch.hairline)
                            AnVeSectionHeader(
                                title = stringResource(R.string.history_retention_title),
                            )
                            val currentDays = state.effectiveRetentionDays ?: state.retentionDays
                            Text(
                                stringResource(R.string.history_retention_days_value, currentDays?.toString() ?: "—"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(listOf(1, 7, 30), key = { it }) { days ->
                                    FilterChip(
                                        selected = state.retentionDays == days,
                                        onClick = {
                                            // Shortening the horizon
                                            // permanently deletes older
                                            // history on the server — the
                                            // same destructive class as the
                                            // delete actions, which always
                                            // confirm. Raising it deletes
                                            // nothing, so it commits in one
                                            // tap without added friction.
                                            if (days < (currentDays ?: Int.MAX_VALUE)) {
                                                pendingRetentionDays = days
                                            } else {
                                                vm.changeRetention(days)
                                            }
                                        },
                                        enabled = !state.mutationPending && state.retentionDays != null,
                                        label = { Text(stringResource(R.string.history_retention_chip_days, days)) },
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

    deletionScope?.let { scope ->
        AlertDialog(
            onDismissRequest = { deletionScope = null },
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = {
                Text(
                    stringResource(when (scope) {
                        DeletionScope.ALL -> R.string.history_delete_scope_all
                        DeletionScope.DAY -> R.string.history_delete_scope_day
                        DeletionScope.RANGE -> R.string.history_delete_scope_range
                    }) + " " + stringResource(R.string.history_delete_privacy_note),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                // Same destructive-confirm pattern as the circle/places dialogs:
                // error-colored text action plus a neutral Hủy.
                TextButton(
                    onClick = {
                        deletionScope = null
                        vm.delete(scope == DeletionScope.ALL, scope == DeletionScope.RANGE)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.history_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    onClick = { deletionScope = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    // Decrease-only guard for the retention horizon: the dialog blocks the
    // branch that deletes data and never shows for a raise, mirroring the
    // delete dialog's destructive-confirm pattern (error-colored confirm plus
    // a neutral Hủy).
    pendingRetentionDays?.let { days ->
        AlertDialog(
            onDismissRequest = { pendingRetentionDays = null },
            title = { Text(stringResource(R.string.history_retention_confirm_title)) },
            text = {
                Text(
                    stringResource(R.string.history_retention_confirm_body),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRetentionDays = null
                        vm.changeRetention(days)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.history_retention_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingRetentionDays = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    // Material3 pickers replace the platform widgets so the date and time
    // dialogs match the app's visual language. The date dialog mirrors the
    // old platform maxDate: no date after today is selectable.
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= System.currentTimeMillis()
            },
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDatePicker = false
                        datePickerState.selectedDateMillis?.let { millis ->
                            vm.select(member, Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate())
                        }
                    },
                    enabled = datePickerState.selectedDateMillis != null,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.history_pick_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDatePicker = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.action_cancel)) }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
    if (showStartTimePicker) {
        val timeState = rememberTimePickerState(
            initialHour = state.startMinute / 60,
            initialMinute = state.startMinute % 60,
            is24Hour = true,
        )
        TimePickerDialog(
            onDismissRequest = { showStartTimePicker = false },
            title = { Text(stringResource(R.string.history_start_time_title)) },
            confirmButton = {
                // Prevents an invalid interval at the source: confirm enables
                // only when start < end, matching timeInterval's require. The
                // ViewModel keeps its own error as the backstop.
                TextButton(
                    onClick = {
                        showStartTimePicker = false
                        vm.selectTimes(timeState.hour * 60 + timeState.minute, state.endMinute)
                    },
                    enabled = timeState.hour * 60 + timeState.minute < state.endMinute,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.history_pick_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showStartTimePicker = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.action_cancel)) }
            },
        ) {
            TimePicker(state = timeState)
        }
    }
    if (showEndTimePicker) {
        val timeState = rememberTimePickerState(
            initialHour = minOf(state.endMinute, 1439) / 60,
            initialMinute = minOf(state.endMinute, 1439) % 60,
            is24Hour = true,
        )
        TimePickerDialog(
            onDismissRequest = { showEndTimePicker = false },
            title = { Text(stringResource(R.string.history_end_time_title)) },
            confirmButton = {
                // Same source-level prevention as the start picker: end must
                // stay after start.
                TextButton(
                    onClick = {
                        showEndTimePicker = false
                        vm.selectTimes(state.startMinute, timeState.hour * 60 + timeState.minute)
                    },
                    enabled = state.startMinute < timeState.hour * 60 + timeState.minute,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.history_pick_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEndTimePicker = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.action_cancel)) }
            },
        ) {
            TimePicker(state = timeState)
        }
    }
    if (fullScreenMapOpen && BuildConfig.MAPS_API_KEY.isNotBlank()) {
        HistoryFullScreenMapDialog(
            memberLabel = memberLabel,
            dateLabel = historyDateFormatter.format(state.date),
            segments = segments,
            validPoints = validPoints,
            selected = selected,
            places = state.places,
            onSelectPoint = { selectedId = it.eventId },
            onDismiss = { fullScreenMapOpen = false },
        )
    }
}

@Composable
private fun HistoryMapContent(
    segments: List<List<HistoryPointDto>>,
    validPoints: List<HistoryPointDto>,
    selected: HistoryPointDto?,
    places: List<PlaceDto> = emptyList(),
    onSelectPoint: (HistoryPointDto) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val routeColor = colors.primary
    places.forEach { place ->
        val center = LatLng(place.latitude, place.longitude)
        val placeMarker = remember(place.id, place.latitude, place.longitude) { MarkerState(position = center) }
        val placeIcon = rememberPlaceMarkerIcon(name = place.name)
        Circle(
            center = center,
            radius = place.radiusMeters,
            fillColor = colors.primaryContainer.copy(alpha = 0.2f),
            strokeColor = colors.primary.copy(alpha = 0.65f),
            strokeWidth = 2f,
        )
        Marker(
            state = placeMarker,
            title = place.name,
            icon = placeIcon,
            anchor = Offset(0.5f, 1f),
        )
    }
    segments.filter { it.size > 1 }.forEach { segment ->
        Polyline(
            points = segment.map { LatLng(it.latitude, it.longitude) },
            color = routeColor,
            width = 10f,
            startCap = RoundCap(),
            endCap = RoundCap(),
        )
    }
    val startPoint = validPoints.firstOrNull()
    val endPoint = validPoints.lastOrNull()?.takeIf { it.eventId != startPoint?.eventId }
    startPoint?.latLngOrNull()?.let { latLng ->
        if (startPoint.eventId != selected?.eventId) {
            val marker = remember(startPoint.eventId, latLng) { MarkerState(position = latLng) }
            val (icon, anchorY) = rememberHistoryPointMarkerIcon(
                label = stringResource(R.string.history_start_label),
                isSelected = false,
            )
            Marker(
                state = marker,
                title = AnveTime.full(startPoint.recordedAt, stringResource(R.string.journey_time_unknown)),
                icon = icon,
                anchor = Offset(0.5f, anchorY),
                onClick = { onSelectPoint(startPoint); true },
            )
        }
    }
    endPoint?.latLngOrNull()?.let { latLng ->
        if (endPoint.eventId != selected?.eventId) {
            val marker = remember(endPoint.eventId, latLng) { MarkerState(position = latLng) }
            val (icon, anchorY) = rememberHistoryPointMarkerIcon(
                label = stringResource(R.string.history_end_label),
                isSelected = false,
            )
            Marker(
                state = marker,
                title = AnveTime.full(endPoint.recordedAt, stringResource(R.string.journey_time_unknown)),
                icon = icon,
                anchor = Offset(0.5f, anchorY),
                onClick = { onSelectPoint(endPoint); true },
            )
        }
    }
    selected?.latLngOrNull()?.let { latLng ->
        val marker = remember(selected.eventId, latLng) { MarkerState(position = latLng) }
        val clock = AnveTime.seconds(selected.recordedAt, "")
        val (icon, anchorY) = rememberHistoryPointMarkerIcon(
            label = clock,
            isSelected = true,
        )
        Marker(
            state = marker,
            title = AnveTime.full(selected.recordedAt, stringResource(R.string.journey_time_unknown)),
            icon = icon,
            anchor = Offset(0.5f, anchorY),
            zIndex = 1f,
        )
    }
}

@Composable
private fun HistoryFullScreenMapDialog(
    memberLabel: String,
    dateLabel: String,
    segments: List<List<HistoryPointDto>>,
    validPoints: List<HistoryPointDto>,
    selected: HistoryPointDto?,
    places: List<PlaceDto> = emptyList(),
    onSelectPoint: (HistoryPointDto) -> Unit,
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
        val camera = rememberCameraPositionState()
        var mapLoaded by remember { mutableStateOf(false) }
        val activePoint = selected ?: validPoints.lastOrNull()
        val activeIndex = activePoint?.let { p -> validPoints.indexOfFirst { it.eventId == p.eventId } }
            ?.takeIf { it >= 0 } ?: 0

        LaunchedEffect(mapLoaded, validPoints.size) {
            if (!mapLoaded) return@LaunchedEffect
            val latLngs = validPoints.mapNotNull(HistoryPointDto::latLngOrNull)
            fitCameraToPoints(camera, latLngs, paddingPx = 120, singleZoom = 15f)
        }

        Surface(modifier = Modifier.fillMaxSize(), color = colors.background) {
            Box(Modifier.fillMaxSize()) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = camera,
                    properties = rememberAnVeMapProperties(),
                    uiSettings = MapUiSettings(
                        scrollGesturesEnabled = true,
                        zoomGesturesEnabled = true,
                        myLocationButtonEnabled = false,
                        mapToolbarEnabled = false,
                        compassEnabled = false,
                    ),
                    onMapLoaded = { mapLoaded = true },
                ) {
                    HistoryMapContent(
                        segments = segments,
                        validPoints = validPoints,
                        selected = activePoint,
                        places = places,
                        onSelectPoint = onSelectPoint,
                    )
                }

                // Floating top bar with member + date context and close action.
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = stitch.cardTactile.copy(alpha = 0.94f),
                    border = BorderStroke(1.dp, stitch.hairline),
                    shadowElevation = 2.dp,
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
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.map_fullscreen_close))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.history_map_explorer_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                            )
                            Text(
                                text = "$memberLabel · $dateLabel",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                    }
                }

                // Right-side fit & recenter buttons above the scrubber card.
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 16.dp, bottom = if (validPoints.isNotEmpty()) 172.dp else 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (validPoints.isNotEmpty()) {
                        Surface(
                            onClick = {
                                val latLngs = validPoints.mapNotNull(HistoryPointDto::latLngOrNull)
                                scope.launch {
                                    fitCameraToPoints(camera, latLngs, paddingPx = 120, singleZoom = 15f, animate = true)
                                }
                            },
                            shape = CircleShape,
                            color = stitch.cardTactile.copy(alpha = 0.94f),
                            contentColor = colors.onSurface,
                            border = BorderStroke(1.dp, stitch.hairline),
                            shadowElevation = 2.dp,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.ZoomOutMap, contentDescription = stringResource(R.string.map_fit_route), modifier = Modifier.size(20.dp))
                            }
                        }
                        Surface(
                            onClick = {
                                activePoint?.latLngOrNull()?.let { latLng ->
                                    scope.launch {
                                        camera.animate(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
                                    }
                                }
                            },
                            shape = CircleShape,
                            color = stitch.cardTactile.copy(alpha = 0.94f),
                            contentColor = colors.primary,
                            border = BorderStroke(1.dp, stitch.hairline),
                            shadowElevation = 2.dp,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.MyLocation, contentDescription = stringResource(R.string.history_center_map), modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }

                // Bottom timeline scrubber card for effortless 1-thumb point stepping.
                if (validPoints.isNotEmpty() && activePoint != null) {
                    val time = AnveTime.seconds(activePoint.recordedAt, stringResource(R.string.journey_time_unknown))
                    val focusPoint: (HistoryPointDto) -> Unit = { point ->
                        onSelectPoint(point)
                        point.latLngOrNull()?.let { latLng ->
                            scope.launch {
                                camera.animate(CameraUpdateFactory.newLatLngZoom(latLng, 15.5f))
                            }
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = stitch.cardTactile,
                        border = BorderStroke(1.dp, stitch.hairline),
                        shadowElevation = 2.dp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                            .fillMaxWidth()
                            .anveContentWidth(),
                    ) {
                        val stackScrubberHeader = LocalDensity.current.fontScale >= 1.3f
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val scrubberSummary: @Composable (Modifier) -> Unit = { colMod ->
                                Column(modifier = colMod, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    val activePlaceMatch = remember(activePoint.eventId, places) {
                                        findMatchingPlace(activePoint.latitude, activePoint.longitude, places)
                                    }
                                    val accuracyText = stringResource(R.string.map_accuracy, activePoint.horizontalAccuracyMeters.toInt())
                                    val detailText = if (activePlaceMatch?.isInside == true) {
                                        "${stringResource(R.string.history_at_place_badge, activePlaceMatch.place.name)} · $accuracyText"
                                    } else {
                                        accuracyText
                                    }
                                    Text(
                                        text = stringResource(R.string.history_point_step, activeIndex + 1, validPoints.size, time),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                    )
                                    Text(
                                        text = detailText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (activePlaceMatch?.isInside == true) colors.primary else colors.onSurfaceVariant,
                                        maxLines = 2,
                                    )
                                }
                            }
                            val scrubberButtons: @Composable () -> Unit = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalIconButton(
                                        onClick = {
                                            if (activeIndex > 0) focusPoint(validPoints[activeIndex - 1])
                                        },
                                        enabled = activeIndex > 0,
                                        modifier = Modifier.size(48.dp),
                                    ) {
                                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = stringResource(R.string.history_prev_point_desc))
                                    }
                                    FilledTonalIconButton(
                                        onClick = {
                                            if (activeIndex < validPoints.lastIndex) focusPoint(validPoints[activeIndex + 1])
                                        },
                                        enabled = activeIndex < validPoints.lastIndex,
                                        modifier = Modifier.size(48.dp),
                                    ) {
                                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = stringResource(R.string.history_next_point_desc))
                                    }
                                }
                            }
                            if (stackScrubberHeader) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    scrubberSummary(Modifier.fillMaxWidth())
                                    Box(modifier = Modifier.align(Alignment.End)) {
                                        scrubberButtons()
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    scrubberSummary(Modifier.weight(1f))
                                    scrubberButtons()
                                }
                            }
                            if (validPoints.size > 1) {
                                Slider(
                                    value = activeIndex.toFloat(),
                                    onValueChange = { raw ->
                                        val idx = raw.roundToInt().coerceIn(0, validPoints.lastIndex)
                                        if (idx != activeIndex) {
                                            focusPoint(validPoints[idx])
                                        }
                                    },
                                    valueRange = 0f..validPoints.lastIndex.toFloat(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistorySummaryCard(
    pointCount: Int,
    delayedCount: Int,
    retentionDaysLabel: String,
) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    val largeFont = LocalDensity.current.fontScale > 1.35f
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, stitch.hairline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (maxWidth < 320.dp || largeFont) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    maxItemsInEachRow = 2,
                ) {
                    HistoryStatItem(
                        label = stringResource(R.string.history_metric_points),
                        value = pointCount.toString(),
                        modifier = Modifier.weight(1f),
                    )
                    HistoryStatItem(
                        label = stringResource(R.string.history_metric_delayed),
                        value = delayedCount.toString(),
                        modifier = Modifier.weight(1f),
                    )
                    HistoryStatItem(
                        label = stringResource(R.string.history_metric_retention),
                        value = retentionDaysLabel,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HistoryStatItem(
                        label = stringResource(R.string.history_metric_points),
                        value = pointCount.toString(),
                        modifier = Modifier.weight(1f),
                    )
                    HistoryStatItem(
                        label = stringResource(R.string.history_metric_delayed),
                        value = delayedCount.toString(),
                        modifier = Modifier.weight(1f),
                    )
                    HistoryStatItem(
                        label = stringResource(R.string.history_metric_retention),
                        value = retentionDaysLabel,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryStatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
    }
}

@Preview(name = "1. Standard Light", showBackground = true)
@Composable
private fun HistorySummaryCardPreview() {
    AnVeTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(Modifier.padding(16.dp)) {
                HistorySummaryCard(pointCount = 12, delayedCount = 1, retentionDaysLabel = "7")
            }
        }
    }
}

@Preview(name = "2. Dark Mode", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun HistorySummaryCardDarkPreview() {
    AnVeTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(Modifier.padding(16.dp)) {
                HistorySummaryCard(pointCount = 12, delayedCount = 1, retentionDaysLabel = "7")
            }
        }
    }
}

@Preview(name = "3. Elderly 200% Font", fontScale = 2.0f, showBackground = true)
@Composable
private fun HistorySummaryCardElderlyFontPreview() {
    AnVeTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(Modifier.padding(16.dp)) {
                HistorySummaryCard(pointCount = 12, delayedCount = 1, retentionDaysLabel = "7")
            }
        }
    }
}

private object PreviewHistoryDataSource : HistoryDataSource {
    override suspend fun members(owner: String): List<HistoryMember> = emptyList()
    override suspend fun page(owner: String, member: String, from: String, to: String, cursor: String?) = HistoryPageDto(points = emptyList())
    override suspend fun retention(owner: String) = HistoryRetentionDto(7, 7)
    override suspend fun setRetention(owner: String, days: Int) = HistoryRetentionDto(days, days)
    override suspend fun delete(owner: String, from: String?, to: String) = Unit
}

@Preview(name = "1. Content Light", showBackground = true)
@Preview(name = "2. Content Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "3. Content Elderly 200% Font", fontScale = 2.0f, showBackground = true)
@Composable
private fun HistoryContentPreview() {
    val vm = remember { HistoryViewModel(PreviewHistoryDataSource) }
    AnVeTheme {
        HistoryContent(
            state = HistoryState(
                owner = "owner",
                member = "owner",
                date = LocalDate.of(2026, 11, 16),
                members = listOf(HistoryMember("m1", "Mẹ Lan")),
                points = listOf(
                    HistoryPointDto("p1", 21.0285, 105.8542, 12.0, "2026-11-16T01:00:00Z", "2026-11-16T01:00:05Z", "fused", false, false, "seg1"),
                ),
                retentionDays = 7,
                effectiveRetentionDays = 7,
            ),
            vm = vm,
        )
    }
}
