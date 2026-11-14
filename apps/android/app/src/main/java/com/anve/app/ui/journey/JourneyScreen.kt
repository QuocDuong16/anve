package com.anve.app.ui.journey

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anve.app.AppContainer
import com.anve.app.R
import com.anve.app.data.api.JourneyDto
import com.anve.app.data.api.PlaceDto
import com.anve.app.data.journey.JourneyAction
import com.anve.app.data.sos.authorizedJourneySignal
import com.anve.app.tracking.MotionState
import com.anve.app.tracking.TrackingStatus
import com.anve.app.ui.common.AnveTime
import com.anve.app.ui.theme.AnVeTheme
import com.anve.app.ui.theme.isDarkSurface
import com.anve.app.ui.theme.rememberInkOnSurface
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun JourneyScreen(container: AppContainer, onBack: () -> Unit, onPlaces: () -> Unit, onLocation: () -> Unit, journeyId: String? = null) {
    val model: JourneyViewModel = viewModel(key = "journey-$journeyId", factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            JourneyViewModel(container.sessionStore, container.journeyRepository, container.placesRepository, container.trackingManager, journeyId) as T
    })
    val state by model.state.collectAsStateWithLifecycle()
    val tracking by container.trackingManager.status.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> model.visible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
        lifecycle.addObserver(observer)
        model.visible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); model.visible(false) }
    }
    key(state.owner) {
        JourneyContent(state, tracking, onBack, model::reload, model::select, model::create, model::action, model::resumePriority, onPlaces, onLocation,
            onCreateWithOptions = model::createWithOptions)
    }
}

private data class Confirmation(val journey: JourneyDto, val action: JourneyAction, val recipient: String? = null, val name: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JourneyContent(
    state: JourneyUiState,
    tracking: TrackingStatus,
    onBack: () -> Unit = {},
    onReload: () -> Unit = {},
    onSelect: (String?) -> Unit = {},
    onCreate: (PlaceDto, Int, Set<String>) -> Unit = { _, _, _ -> },
    onAction: (String, JourneyAction, String?) -> Unit = { _, _, _ -> },
    onResume: (String) -> Unit = {},
    onPlaces: () -> Unit = {},
    onLocation: () -> Unit = {},
    initiallyCreating: Boolean = false,
    onCreateWithOptions: ((PlaceDto, Int, Set<String>, Boolean, Int) -> Unit)? = null,
) {
    var creating by remember(state.owner) { mutableStateOf(initiallyCreating) }
    var confirmation by remember(state.owner) { mutableStateOf<Confirmation?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(10_000) } }
    val selected = state.journeys.firstOrNull { it.id == state.selectedId }
    LaunchedEffect(selected?.id) { if (selected != null) creating = false }
    BackHandler { if (creating) creating = false else onBack() }
    val colors = MaterialTheme.colorScheme
    val dark = isDarkSurface()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (creating) stringResource(R.string.journey_creating_title) else stringResource(R.string.journey_title)) },
            navigationIcon = { IconButton(onClick = { if (creating) creating = false else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            actions = { IconButton(onClick = onReload, enabled = !state.loading && !state.busy) { Icon(Icons.Default.Refresh, stringResource(R.string.journey_reload_content_desc)) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = if (dark) colors.primaryContainer else colors.primary,
                titleContentColor = if (dark) colors.onPrimaryContainer else colors.onPrimary,
                navigationIconContentColor = if (dark) colors.onPrimaryContainer else colors.onPrimary,
                actionIconContentColor = if (dark) colors.onPrimaryContainer else colors.onPrimary),
        )
    }) { padding ->
        LazyColumn(Modifier.padding(padding).testTag("journey-content"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.loading || state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.errorRes?.let { res -> item { JourneyNotice(stringResource(res)) { ink -> JourneyTextAction(stringResource(R.string.journey_reload_action), onReload, !state.busy, ink) } } }
            if (!tracking.online && !state.loading && (selected == null || creating)) item {
                JourneyNotice(stringResource(R.string.journey_offline_end_notice), Icons.Default.WifiOff)
            }
            items(state.pending, key = { it.operationId }) { operation ->
                JourneyNotice(stringResource(
                    R.string.journey_pending_prefix,
                    when (operation.action) {
                        JourneyAction.CREATE -> stringResource(R.string.journey_action_create)
                        JourneyAction.ARRIVE -> stringResource(R.string.journey_action_arrive)
                        JourneyAction.END -> stringResource(R.string.journey_action_end)
                        JourneyAction.CANCEL -> stringResource(R.string.journey_action_cancel)
                        JourneyAction.REVOKE_RECIPIENT -> stringResource(R.string.journey_action_revoke)
                    },
                ) + " " + stringResource(if (operation.action == JourneyAction.CREATE) R.string.journey_pending_create_hint else R.string.journey_pending_other_hint)) { ink ->
                    JourneyTextAction(stringResource(R.string.journey_pending_recheck), onReload, !state.busy, ink)
                    if (operation.action != JourneyAction.CANCEL) JourneyTextAction(stringResource(R.string.journey_pending_cancel), { onAction(operation.journeyId, JourneyAction.CANCEL, null) }, !state.busy, ink)
                }
            }
            when {
                creating -> item {
                    JourneyCreateForm(state, tracking, { place, minutes, recipients, automatic, stale ->
                        if (onCreateWithOptions != null) onCreateWithOptions(place, minutes, recipients, automatic, stale)
                        else onCreate(place, minutes, recipients)
                    }, onPlaces, onLocation)
                }
                selected != null -> item {
                    JourneyDetails(selected, state.owner == selected.ownerId, tracking, now, state.busy,
                        pending = state.pending.any { it.journeyId == selected.id },
                        onConfirm = { action, recipient, name -> confirmation = Confirmation(selected, action, recipient, name) },
                        onResume = { onResume(selected.id) }, onLocation = onLocation)
                }
                !state.loading -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.journey_intro_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                        Text(stringResource(R.string.journey_intro_body))
                        JourneyPrimaryAction(stringResource(R.string.journey_start), { creating = true }, !state.busy && state.pending.isEmpty())
                    }
                }
            }
            val remaining = state.journeys.filter { it.id != selected?.id }
            if (remaining.isNotEmpty()) item { Text(stringResource(R.string.journey_viewable_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) }
            items(remaining, key = { it.id }) { journey ->
                OutlinedCard(onClick = { onSelect(journey.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(
                            R.string.journey_card_title,
                            if (journey.ownerId == state.owner) stringResource(R.string.journey_card_owner_you) else journey.ownerName,
                            journey.destination.label,
                        ), style = MaterialTheme.typography.titleMedium)
                        Text(if (journey.arrivalSource == "AUTOMATIC") stringResource(R.string.journey_arrival_automatic) else journeyStateLabel(journey.state), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.journey_started_at, journeyTime(journey.startedAt, stringResource(R.string.journey_time_unknown))), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (selected != null && selected.state != "ACTIVE" && selected.ownerId == state.owner) item {
                JourneyPrimaryAction(stringResource(R.string.journey_start_new), { onSelect(null); creating = true }, !state.busy && state.pending.isEmpty())
            }
        }
    }
    confirmation?.let { choice ->
        val title = when (choice.action) {
            JourneyAction.ARRIVE -> stringResource(R.string.journey_confirm_arrive_title)
            JourneyAction.REVOKE_RECIPIENT -> stringResource(R.string.journey_confirm_revoke_title, choice.name.orEmpty())
            else -> stringResource(R.string.journey_confirm_end_title)
        }
        val label = when (choice.action) {
            JourneyAction.ARRIVE -> stringResource(R.string.journey_arrive_action)
            JourneyAction.REVOKE_RECIPIENT -> stringResource(R.string.journey_revoke_recipient)
            else -> stringResource(R.string.journey_end_action)
        }
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(title) },
            text = { Text(when (choice.action) {
                JourneyAction.ARRIVE -> stringResource(R.string.journey_confirm_arrive_body, choice.journey.destination.label)
                JourneyAction.REVOKE_RECIPIENT -> stringResource(R.string.journey_confirm_revoke_body)
                else -> stringResource(R.string.journey_confirm_end_body)
            }) },
            dismissButton = { JourneyTextAction(stringResource(R.string.action_cancel), { confirmation = null }) },
            confirmButton = { JourneyTextAction(label, { confirmation = null; onAction(choice.journey.id, choice.action, choice.recipient) }, !state.busy) },
        )
    }
}

@Composable
private fun JourneyDetails(journey: JourneyDto, owner: Boolean, tracking: TrackingStatus, now: Long, busy: Boolean, pending: Boolean,
    onConfirm: (JourneyAction, String?, String?) -> Unit, onResume: () -> Unit, onLocation: () -> Unit) {
    var people by remember(journey.id) { mutableStateOf(false) }
    val active = journey.state == "ACTIVE"
    val expiredLocally = runCatching { Instant.parse(journey.expiresAt).toEpochMilli() <= now }.getOrDefault(true)
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Default.Home, null, Modifier.padding(14.dp).size(28.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f)) {
                Text(journey.destination.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                if (!owner) Text(stringResource(R.string.journey_of_owner, journey.ownerName), style = MaterialTheme.typography.titleMedium)
                val timeUnknown = stringResource(R.string.journey_time_unknown)
                Text(if (active) stringResource(R.string.journey_expires_auto, journeyTime(journey.expiresAt, timeUnknown)) else journey.endedAt?.let { stringResource(R.string.journey_ended_at, journeyTime(it, timeUnknown)) } ?: stringResource(R.string.journey_ended), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Column {
            JourneyStep(stringResource(R.string.journey_step_started, journeyTime(journey.startedAt, stringResource(R.string.journey_time_unknown))), completed = true)
            JourneyStep(if (active) stringResource(R.string.journey_step_active) else stringResource(R.string.journey_ended), current = active, completed = !active) {
                val progress = journey.progress
                if (progress == null) Text(if (active) stringResource(R.string.journey_no_fix_after_start) else stringResource(R.string.journey_pending_ended), style = MaterialTheme.typography.bodyMedium)
                else {
                    val age = ((now - Instant.parse(progress.recordedAt).toEpochMilli()).coerceAtLeast(0) / 60_000)
                    JourneyObservation(Icons.Default.LocationOn, stringResource(
                        R.string.journey_observation_age,
                        if (age == 0L) stringResource(R.string.journey_age_under_minute) else stringResource(R.string.journey_age_minutes, age),
                    ))
                    JourneyObservation(Icons.Default.Route, stringResource(R.string.journey_distance_km, String.format(Locale.forLanguageTag("vi-VN"), "%.1f", progress.distanceMeters / 1000)))
                    Text(stringResource(
                        R.string.journey_accuracy,
                        progress.accuracyMeters.toInt(),
                        if (progress.isMock) stringResource(R.string.journey_mock_suffix) else "",
                    ), style = MaterialTheme.typography.bodySmall)
                }
                if (active) Text(stringResource(R.string.journey_distance_disclaimer), style = MaterialTheme.typography.bodySmall)
            }
            JourneyStep(when {
                journey.arrivalSource == "AUTOMATIC" -> stringResource(R.string.journey_arrival_automatic)
                journey.state == "ARRIVED" -> stringResource(R.string.journey_arrival_confirmed)
                active && journey.automaticArrival -> stringResource(R.string.journey_arrival_auto_possible)
                active && owner -> stringResource(R.string.journey_arrival_confirm)
                active -> stringResource(R.string.journey_arrival_waiting)
                else -> stringResource(R.string.journey_arrival_unconfirmed)
            }, last = true, completed = journey.state == "ARRIVED") {
                Text(when {
                    journey.arrivalSource == "AUTOMATIC" -> stringResource(R.string.journey_arrival_auto_caveat)
                    active && journey.automaticArrival -> stringResource(R.string.journey_arrival_auto_manual_note)
                    !active -> stringResource(R.string.journey_arrival_no_infer)
                    owner -> stringResource(R.string.journey_arrival_owner_confirm)
                    else -> stringResource(R.string.journey_arrival_waiting_confirm)
                }, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (active && !expiredLocally && authorizedJourneySignal(journey, "JOURNEY_STALE", journey.id, journey.staleEpisode)) {
            JourneyNotice(stringResource(R.string.journey_stale_notice))
        }
        if (active && (journey.automaticArrival || journey.staleAfterMinutes > 0)) {
            Text(stringResource(
                R.string.journey_config_line,
                if (journey.automaticArrival) stringResource(R.string.journey_config_auto) else stringResource(R.string.journey_config_manual),
                if (journey.staleAfterMinutes > 0) stringResource(R.string.journey_config_stale_suffix, journey.staleAfterMinutes) else "",
            ), style = MaterialTheme.typography.bodySmall)
        }
        if (expiredLocally && active) JourneyNotice(stringResource(R.string.journey_expired_locally))
        if (owner) {
            Column {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(
                        R.string.journey_followers,
                        journey.recipients.joinToString { it.displayName }.ifBlank { stringResource(R.string.journey_no_viewers_left) },
                    ), Modifier.padding(vertical = 12.dp))
                    JourneyTextAction(if (people) stringResource(R.string.journey_collapse_viewers) else stringResource(R.string.journey_manage_viewers), { people = !people })
                }
                if (people) journey.recipients.forEach { recipient ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(recipient.displayName, Modifier.weight(1f))
                        JourneyTextAction(stringResource(R.string.journey_revoke_row), { onConfirm(JourneyAction.REVOKE_RECIPIENT, recipient.userId, recipient.displayName) }, !busy && !pending)
                    }
                }
            }
            if (active) {
                if (!tracking.online) JourneyNotice(stringResource(R.string.journey_offline_end_notice), Icons.Default.WifiOff)
                if (!tracking.activelyTracking) JourneyNotice(stringResource(R.string.journey_capture_stopped_notice)) { ink ->
                    JourneyTextAction(stringResource(R.string.journey_open_location), onLocation, color = ink)
                }
                if (tracking.motion != MotionState.JOURNEY && !pending && !expiredLocally) {
                    JourneyTextAction(stringResource(R.string.journey_resume_priority), onResume, !busy && tracking.activelyTracking && tracking.online)
                }
                JourneyPrimaryAction(stringResource(R.string.journey_arrive_action), { onConfirm(JourneyAction.ARRIVE, null, null) }, !busy && !pending && !expiredLocally)
                OutlinedButton(onClick = { onConfirm(JourneyAction.END, null, null) }, enabled = !busy && !pending,
                    border = BorderStroke(1.dp, rememberInkOnSurface()), colors = ButtonDefaults.outlinedButtonColors(contentColor = rememberInkOnSurface()), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Default.Close, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.journey_end_action), style = MaterialTheme.typography.titleMedium)
                }
                Text(stringResource(R.string.journey_end_priority_note), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            if (!tracking.online) JourneyNotice(stringResource(R.string.journey_offline_reload_notice), Icons.Default.WifiOff)
            Text(stringResource(R.string.journey_viewer_manages_arrival), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun JourneyStep(title: String, current: Boolean = false, completed: Boolean = false, last: Boolean = false, content: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(32.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(when { completed -> Icons.Default.CheckCircle; current -> Icons.Default.RadioButtonChecked; else -> Icons.Default.RadioButtonUnchecked }, null, Modifier.size(32.dp), tint = rememberInkOnSurface())
            if (!last) VerticalDivider(Modifier.weight(1f), color = if (completed) rememberInkOnSurface() else MaterialTheme.colorScheme.outlineVariant)
        }
        Surface(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 24.dp),
            color = if (current) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(if (current) 12.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                content()
            }
        }
    }
}

@Composable
private fun JourneyCreateForm(state: JourneyUiState, tracking: TrackingStatus, onCreate: (PlaceDto, Int, Set<String>, Boolean, Int) -> Unit, onPlaces: () -> Unit, onLocation: () -> Unit) {
    var destination by remember(state.owner) { mutableStateOf<String?>(null) }
    var minutes by remember(state.owner) { mutableStateOf(120) }
    var recipients by remember(state.owner) { mutableStateOf(emptySet<String>()) }
    var automatic by remember(state.owner) { mutableStateOf(false) }
    var staleMinutes by remember(state.owner) { mutableStateOf(0) }
    var optionsOpen by remember(state.owner) { mutableStateOf(false) }
    val ready = !state.busy && state.pending.isEmpty() && state.consentEnabled && tracking.ownerUserId == state.owner && tracking.activelyTracking && tracking.online
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.journey_step_destination), style = MaterialTheme.typography.titleMedium)
            if (state.places.isEmpty()) Text(stringResource(R.string.journey_no_places))
            state.places.forEach { place ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(destination == place.id, role = Role.RadioButton, onClick = { destination = place.id }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(destination == place.id, onClick = null)
                    Text(place.name, Modifier.padding(start = 8.dp))
                }
            }
            JourneyTextAction(stringResource(R.string.journey_manage_places), onPlaces)
        } }
        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.journey_step_expiry), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60, 120, 240, 480).forEach { value ->
                    FilterChip(minutes == value, onClick = { minutes = value }, label = {
                        Text(if (value < 60) stringResource(R.string.journey_chip_minutes, value) else stringResource(R.string.journey_chip_hours, value / 60))
                    },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer))
                }
            }
            Text(stringResource(R.string.journey_expiry_note), style = MaterialTheme.typography.bodySmall)
        } }
        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.journey_step_viewers), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.journey_viewers_note), style = MaterialTheme.typography.bodySmall)
            if (state.members.isEmpty()) Text(stringResource(R.string.journey_no_viewers))
            state.members.forEach { member ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(member.userId in recipients, role = Role.Checkbox,
                    onClick = { recipients = if (member.userId in recipients) recipients - member.userId else recipients + member.userId }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(member.userId in recipients, onCheckedChange = null)
                    Text(member.displayName, Modifier.padding(start = 8.dp))
                }
            }
        } }
        OutlinedCard(onClick = { optionsOpen = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("journey-options")) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, null)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.journey_options_entry), style = MaterialTheme.typography.titleMedium)
                    Text(if (!automatic && staleMinutes == 0) stringResource(R.string.journey_option_off) else stringResource(
                        R.string.journey_option_summary,
                        if (automatic) stringResource(R.string.journey_option_auto_summary) else stringResource(R.string.journey_option_manual_summary),
                        if (staleMinutes > 0) stringResource(R.string.journey_option_stale_summary, staleMinutes) else "",
                    ), style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Default.ChevronRight, null)
            }
        }
        // One blocking condition at a time, highest priority first: capture
        // stopped, then offline, then the missing viewer, then the missing
        // destination. Stacking every rule at once buried the actual blocker;
        // the always-true auto-arrival caveat drops to a subtitle under the
        // button instead.
        when {
            !state.consentEnabled || !tracking.activelyTracking ->
                JourneyNotice(stringResource(R.string.journey_requires_capture)) { ink -> JourneyTextAction(stringResource(R.string.journey_open_location), onLocation, color = ink) }
            !tracking.online -> JourneyNotice(stringResource(R.string.journey_requires_network), Icons.Default.WifiOff)
            recipients.isEmpty() -> JourneyNotice(stringResource(R.string.journey_no_recipients_selected))
            destination == null -> JourneyNotice(stringResource(R.string.journey_no_destination_selected))
        }
        JourneyPrimaryAction(stringResource(R.string.journey_start), {
            val place = state.places.firstOrNull { it.id == destination } ?: return@JourneyPrimaryAction
            onCreate(place, minutes, recipients, automatic, staleMinutes)
        }, ready && destination != null && recipients.isNotEmpty() && recipients.all { id -> state.members.any { it.userId == id } })
        Text(
            if (automatic) stringResource(R.string.journey_auto_caveat) else stringResource(R.string.journey_manual_caveat),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (optionsOpen) JourneyOptionsSheet(automatic, staleMinutes, onDismiss = { optionsOpen = false }, onApply = { arrival, stale ->
        automatic = arrival
        staleMinutes = stale
        optionsOpen = false
    })
}

@Composable
private fun JourneyNotice(text: String, icon: ImageVector = Icons.Default.Info, actions: @Composable (Color) -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    val dark = isDarkSurface()
    val ink = if (dark) colors.onSecondary else colors.onSecondaryContainer
    Surface(color = if (dark) colors.secondary else colors.secondaryContainer, contentColor = ink, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(text); actions(ink) }
        }
    }
}

@Composable
private fun JourneyTextAction(text: String, onClick: () -> Unit, enabled: Boolean = true, color: Color? = null) {
    TextButton(onClick, enabled = enabled, colors = ButtonDefaults.textButtonColors(contentColor = color ?: rememberInkOnSurface()), modifier = Modifier.heightIn(min = 48.dp)) { Text(text) }
}

@Composable
private fun JourneyPrimaryAction(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val dark = isDarkSurface()
    Button(onClick, enabled = enabled, colors = ButtonDefaults.buttonColors(containerColor = if (dark) colors.primaryContainer else colors.primary,
        contentColor = if (dark) colors.onPrimaryContainer else colors.onPrimary), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

private fun journeyTime(value: String, fallback: String): String = AnveTime.full(value, fallback)

@Composable
private fun JourneyObservation(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = rememberInkOnSurface())
        Text(text, Modifier.weight(1f))
    }
}

@Composable
private fun journeyStateLabel(state: String): String = when (state) {
    "ACTIVE" -> stringResource(R.string.journey_state_active)
    "ARRIVED" -> stringResource(R.string.journey_arrival_confirmed)
    "ENDED" -> stringResource(R.string.journey_state_ended)
    "CANCELLED" -> stringResource(R.string.journey_state_cancelled)
    "EXPIRED" -> stringResource(R.string.journey_state_expired)
    else -> stringResource(R.string.journey_state_unknown)
}

@Preview
@Composable
private fun JourneyEmptyPreview() { AnVeTheme { JourneyContent(JourneyUiState(owner = "preview", loading = false), TrackingStatus()) } }
