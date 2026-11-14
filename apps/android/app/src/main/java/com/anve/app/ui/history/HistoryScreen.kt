package com.anve.app.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import com.anve.app.data.api.HistoryPointDto
import com.anve.app.ui.common.AnveErrorNotice
import com.anve.app.ui.common.AnveTime
import com.anve.app.ui.home.HOME_FAB_CLEARANCE
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
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
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
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

private fun distanceMeters(a: HistoryPointDto, b: HistoryPointDto): Double {
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val deltaLat = lat2 - lat1
    val deltaLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(deltaLat / 2) * sin(deltaLat / 2) + cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
    return 12_742_000.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

@Composable
fun HistoryScreen(container: AppContainer) {
    val session by container.sessionStore.session.collectAsStateWithLifecycle(initialValue = null)
    val owner = session?.userId
    val vm: HistoryViewModel = viewModel(factory = HistoryViewModel.factory(container.historyRepository))
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
    if (state.owner != owner || owner == null) return
    HistoryContent(state, vm)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryContent(state: HistoryState, vm: HistoryViewModel) {
    var selectedId by remember(state.owner, state.member, state.date) { mutableStateOf<String?>(null) }
    val selected = state.points.firstOrNull { it.eventId == selectedId }
    var deletionScope by remember(state.owner) { mutableStateOf<DeletionScope?>(null) }
    var showActions by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val camera = key(state.owner) { rememberCameraPositionState() }
    var mapLoaded by remember(state.owner) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val member = state.member ?: return
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
    val gapStarts = remember(state.points) {
        historySegments(state.points).drop(1).mapNotNull { it.firstOrNull()?.eventId }.toSet()
    }
    LaunchedEffect(state.owner, member, state.date, state.points.isEmpty(), mapLoaded) {
        if (mapLoaded && state.points.isEmpty()) camera.move(CameraUpdateFactory.newLatLngZoom(LatLng(0.0, 0.0), 1f))
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
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.history_refresh_content_desc))
                    }
                    if (state.member == state.owner) Box {
                        IconButton(onClick = { showActions = true }, enabled = !state.mutationPending) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.history_actions_content_desc))
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
                state = listState,
                contentPadding = PaddingValues(top = 16.dp, bottom = HOME_FAB_CLEARANCE),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = member == state.owner,
                                onClick = { state.owner?.let { vm.select(it, state.date) } },
                                label = { Text(stringResource(R.string.history_member_self)) },
                                enabled = !state.mutationPending,
                            )
                        }
                        items(state.members, key = { it.ownerId }) { person ->
                            FilterChip(
                                selected = member == person.ownerId,
                                onClick = { vm.select(person.ownerId, state.date) },
                                label = { Text(person.displayName) },
                                enabled = !state.mutationPending,
                            )
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IconButton(
                                    onClick = { vm.select(member, state.date.minusDays(1)) },
                                    enabled = !state.mutationPending,
                                ) { Icon(Icons.Default.ChevronLeft, contentDescription = stringResource(R.string.history_prev_day_content_desc)) }
                                TextButton(
                                    onClick = { showDatePicker = true },
                                    enabled = !state.mutationPending,
                                    modifier = Modifier.weight(1f),
                                ) { Text(historyDateFormatter.format(state.date)) }
                                IconButton(
                                    onClick = { vm.select(member, state.date.plusDays(1)) },
                                    enabled = !state.mutationPending && state.date < LocalDate.now(),
                                ) { Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.history_next_day_content_desc)) }
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item {
                                    OutlinedButton(onClick = { showStartTimePicker = true }, enabled = !state.mutationPending) {
                                        Text(stringResource(R.string.history_time_from, formatMinute(state.startMinute)))
                                    }
                                }
                                item {
                                    OutlinedButton(onClick = { showEndTimePicker = true }, enabled = !state.mutationPending) {
                                        Text(stringResource(R.string.history_time_to, formatMinute(state.endMinute)))
                                    }
                                }
                                item {
                                    TextButton(onClick = { vm.selectTimes(0, 1440) }, enabled = !state.mutationPending) {
                                        Text(stringResource(R.string.history_time_all_day))
                                    }
                                }
                            }
                        }
                    }
                }
                if (BuildConfig.MAPS_API_KEY.isNotBlank()) {
                    item {
                        // The map is one item inside the LazyColumn, so a
                        // one-finger pan over it would swallow the list's
                        // vertical scroll. Pinch zoom stays on because two
                        // fingers never scroll the list, and camera placement
                        // goes through the center action below the map.
                        GoogleMap(
                            modifier = Modifier.fillMaxWidth().height(220.dp),
                            cameraPositionState = camera,
                            uiSettings = MapUiSettings(scrollGesturesEnabled = false, zoomGesturesEnabled = true),
                            onMapLoaded = { mapLoaded = true },
                        ) {
                            historySegments(state.points).filter { it.size > 1 }.forEach { segment ->
                                Polyline(points = segment.map { LatLng(it.latitude, it.longitude) })
                            }
                            selected?.takeIf {
                                it.latitude.isFinite() && it.longitude.isFinite() &&
                                    it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
                            }?.let { point ->
                                val marker = remember(point.eventId, point.latitude, point.longitude) {
                                    MarkerState(position = LatLng(point.latitude, point.longitude))
                                }
                                // Never leak the raw ISO string as marker copy.
                                Marker(state = marker, title = AnveTime.full(point.recordedAt, point.recordedAt))
                            }
                        }
                        TextButton(onClick = {
                            val point = selected ?: state.points.lastOrNull()
                            if (mapLoaded && point != null && point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0) {
                                scope.launch {
                                    camera.animate(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), 14f))
                                }
                            }
                        }, enabled = mapLoaded && state.points.isNotEmpty()) { Text(stringResource(R.string.history_center_map)) }
                    }
                } else {
                    item { Text(stringResource(R.string.history_map_unconfigured)) }
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
                    // A bare spinner between list items says nothing; name what is
                    // happening (SOS progress-row pattern).
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text(
                            stringResource(if (state.loading) R.string.action_loading else R.string.action_processing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.points.isEmpty() && !state.loading && state.errorRes == null && !state.mutationPending) {
                    item { Text(stringResource(R.string.history_empty_day), modifier = Modifier.padding(vertical = 12.dp)) }
                }
                items(state.points, key = { it.eventId }) { point ->
                    val time = AnveTime.seconds(point.recordedAt, point.recordedAt)
                    val gap = if (point.eventId in gapStarts) stringResource(R.string.history_gap_prefix) else ""
                    val delayed = if (point.backfill) stringResource(R.string.history_backfill_suffix) else ""
                    val mock = if (point.isMock) stringResource(R.string.history_mock_suffix) else ""
                    Text(
                        stringResource(
                            R.string.history_point_row,
                            gap,
                            time,
                            point.horizontalAccuracyMeters.toInt(),
                            delayed,
                            mock,
                        ),
                        Modifier.fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable { selectedId = point.eventId }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        color = if (point.eventId == selectedId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (state.member == state.owner) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.history_retention_title), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(R.string.history_retention_days_value, (state.effectiveRetentionDays ?: state.retentionDays)?.toString() ?: "—"))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(listOf(1, 7, 30)) { days ->
                                        FilterChip(
                                            selected = state.retentionDays == days,
                                            onClick = { vm.changeRetention(days) },
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
                )
            },
            confirmButton = {
                // Same destructive-confirm pattern as the circle/places dialogs:
                // error-colored text action plus a neutral Hủy.
                TextButton(onClick = {
                    deletionScope = null
                    vm.delete(scope == DeletionScope.ALL, scope == DeletionScope.RANGE)
                }) { Text(stringResource(R.string.history_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletionScope = null }) { Text(stringResource(R.string.action_cancel)) } },
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
                ) { Text(stringResource(R.string.history_pick_confirm)) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.action_cancel)) } },
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
                TextButton(onClick = {
                    showStartTimePicker = false
                    vm.selectTimes(timeState.hour * 60 + timeState.minute, state.endMinute)
                }) { Text(stringResource(R.string.history_pick_confirm)) }
            },
            dismissButton = { TextButton(onClick = { showStartTimePicker = false }) { Text(stringResource(R.string.action_cancel)) } },
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
                TextButton(onClick = {
                    showEndTimePicker = false
                    vm.selectTimes(state.startMinute, timeState.hour * 60 + timeState.minute)
                }) { Text(stringResource(R.string.history_pick_confirm)) }
            },
            dismissButton = { TextButton(onClick = { showEndTimePicker = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) {
            TimePicker(state = timeState)
        }
    }
}
