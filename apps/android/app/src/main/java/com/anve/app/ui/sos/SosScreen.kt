package com.anve.app.ui.sos

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anve.app.AppContainer
import com.anve.app.R
import com.anve.app.data.sos.FirebaseSetup
import com.anve.app.data.sos.SosLocalState
import com.anve.app.ui.common.AnveErrorNotice
import com.anve.app.ui.common.AnveTime
import com.anve.app.ui.theme.LocalEmergencyColors
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

@Composable
fun SosScreen(container: AppContainer, eventId: String? = null) {
    val session by container.sessionStore.session.collectAsStateWithLifecycle(initialValue = null)
    val owner = session?.userId
    val vm: SosViewModel = viewModel(factory = SosViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    var permission by remember(owner) { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var explainPermission by remember(owner) { mutableStateOf(false) }
    var pushMessage by remember(owner) { mutableStateOf<String?>(null) }
    // Callbacks cannot resolve resources themselves, so the messages they
    // write are resolved once here.
    val pushRegisteringMessage = stringResource(R.string.sos_push_registering)
    val pushUnavailableMessage = stringResource(R.string.sos_push_not_configured)
    val openSettingsFailedMessage = stringResource(R.string.sos_open_settings_failed)
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (permission && owner != null) {
            if (FirebaseSetup.initialize(context)) { container.pushRegistrationScheduler.schedule(owner); pushMessage = pushRegisteringMessage }
            else pushMessage = pushUnavailableMessage
        }
    }
    DisposableEffect(owner, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { vm.activate(owner); permission = NotificationManagerCompat.from(context).areNotificationsEnabled() }
                Lifecycle.Event.ON_STOP -> vm.activate(null)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); vm.activate(null) }
    }
    LaunchedEffect(owner, lifecycle) {
        if (owner != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { delay(10_000); vm.refresh() }
        }
    }
    LaunchedEffect(owner, eventId, state.owner) { if (owner != null && state.owner == owner && eventId != null) vm.select(eventId) }
    if (owner == null || state.owner != owner) return
    SosContent(state, vm, permission, pushMessage, onNotifications = { explainPermission = true }, onSystemSettings = {
        val intent = if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(intent) }.onFailure { pushMessage = openSettingsFailedMessage }
    })
    if (explainPermission) AlertDialog(
        onDismissRequest = { explainPermission = false },
        title = { Text(stringResource(R.string.sos_notifications_dialog_title)) },
        text = { Text(stringResource(R.string.sos_notifications_dialog_body)) },
        confirmButton = { Button(onClick = {
            explainPermission = false
            if (Build.VERSION.SDK_INT >= 33 && !permission) permissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            else if (FirebaseSetup.initialize(context)) { container.pushRegistrationScheduler.schedule(owner); pushMessage = pushRegisteringMessage }
            else pushMessage = pushUnavailableMessage
        }) { Text(stringResource(R.string.sos_notifications_continue)) } },
        dismissButton = { TextButton(onClick = { explainPermission = false }) { Text(stringResource(R.string.sos_notifications_later)) } },
    )
}

/**
 * One row of the scrollable SOS list. Rows are described declaratively so the
 * outbox section can be scrolled to by key after a send.
 */
private class SosRow(val key: String, val content: @Composable () -> Unit)

private const val OUTBOX_TITLE_KEY = "outbox-title"

private const val DETAIL_CARD_KEY = "detail-card"

/**
 * Group container following the JourneyScreen/DiagnosticsScreen outlined-card
 * pattern: a header plus its related content, so the highest-stakes surface
 * reads as grouped sections instead of flat text.
 */
@Composable
private fun SosCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/**
 * The SOS surface keeps the send action reachable while the recipient list,
 * notification setup and event history scroll: the "Gửi yêu cầu SOS" call is
 * pinned to the bottom, and a send confirms itself with a snackbar that can
 * jump to the outbox status.
 */
@Composable
internal fun SosContent(state: SosState, vm: SosViewModel, notificationPermission: Boolean, pushMessage: String?, onNotifications: () -> Unit, onSystemSettings: () -> Unit = {}) {
    val owner = state.owner ?: return
    var selected by remember(owner) { mutableStateOf(emptySet<String>()) }
    var confirmSend by remember(owner) { mutableStateOf(false) }
    var action by remember(owner) { mutableStateOf<Pair<String, String>?>(null) }
    var revokeRecipient by remember(owner) { mutableStateOf<Pair<String, String>?>(null) }
    var scrollToOutboxCount by remember(owner) { mutableStateOf(0) }
    var scrollToDetailCount by remember(owner) { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val emergency = LocalEmergencyColors.current
    val queuedEventId by vm.queuedEventId.collectAsStateWithLifecycle()

    val rows = buildList {
        add(SosRow("title") { Text(stringResource(R.string.sos_title), style = MaterialTheme.typography.headlineSmall) })
        add(SosRow("emergency-disclaimer") {
            Text(stringResource(R.string.sos_emergency_disclaimer))
        })
        if (state.loading || state.pending) add(SosRow("progress") { CircularProgressIndicator() })
        state.errorRes?.let { res -> add(SosRow("error") { AnveErrorNotice(stringResource(res)) }) }
        if (state.offline) add(SosRow("offline") {
            Text(stringResource(R.string.sos_offline_notice))
        })
        add(SosRow("recipients-card") {
            SosCard(stringResource(R.string.sos_recipients_title)) {
                if (state.members.isEmpty()) {
                    Text(stringResource(R.string.sos_recipients_empty))
                }
                state.members.forEach { member ->
                    // The whole row toggles so the checkbox target is not the only
                    // touchable point; the checkbox itself stays indicative.
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(
                                value = member.userId in selected,
                                enabled = !state.pending,
                                role = Role.Checkbox,
                                onValueChange = { checked -> selected = if (checked) selected + member.userId else selected - member.userId },
                            )
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(member.displayName, Modifier.weight(1f))
                        Checkbox(checked = member.userId in selected, enabled = !state.pending, onCheckedChange = null)
                    }
                }
            }
        })
        add(SosRow(OUTBOX_TITLE_KEY) {
            SosCard(stringResource(R.string.sos_outbox_title)) {
                if (state.outbox.isEmpty()) {
                    Text(stringResource(R.string.sos_outbox_empty))
                }
                state.outbox.forEach { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Time and audience separate the attempts from each
                        // other; two "Đang chờ mạng" rows must stay
                        // distinguishable on a safety feature.
                        Text(
                            stringResource(R.string.sos_outbox_row_meta, sosTime(item.recordedAt), item.recipientIds.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // The status label gets a state-dependent surface so
                        // the list is scanned by color: confirmed (the server
                        // records an open event) stays emergency amber,
                        // in-flight requests read as attention, finished ones
                        // go neutral. The wording is untouched.
                        val (statusContainer, statusContent) = when (item.state) {
                            SosLocalState.CONFIRMED -> emergency.emergencyContainer to emergency.onEmergencyContainer
                            SosLocalState.QUEUED, SosLocalState.SENDING, SosLocalState.UNKNOWN, SosLocalState.CANCEL_REQUESTED ->
                                MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Surface(color = statusContainer, contentColor = statusContent, shape = MaterialTheme.shapes.small) {
                            Text(
                                when (item.state) {
                                    SosLocalState.QUEUED -> stringResource(R.string.sos_state_queued)
                                    SosLocalState.SENDING -> stringResource(R.string.sos_state_sending)
                                    SosLocalState.UNKNOWN -> stringResource(R.string.sos_state_unknown)
                                    SosLocalState.CONFIRMED -> stringResource(R.string.sos_state_confirmed)
                                    SosLocalState.CANCEL_REQUESTED -> stringResource(R.string.sos_state_cancel_requested)
                                    SosLocalState.CANCELLED -> stringResource(R.string.sos_state_cancelled)
                                    SosLocalState.RESOLVED -> stringResource(R.string.sos_state_resolved)
                                    SosLocalState.EXPIRED -> stringResource(R.string.sos_state_expired)
                                    SosLocalState.SERVER_EXPIRED -> stringResource(R.string.sos_state_server_expired)
                                },
                                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                        if (item.state in setOf(SosLocalState.QUEUED, SosLocalState.SENDING, SosLocalState.UNKNOWN, SosLocalState.CONFIRMED)) {
                            TextButton(onClick = { action = item.eventId to "cancel" }, enabled = !state.pending) { Text(stringResource(R.string.sos_cancel_action)) }
                        }
                        if (item.state == SosLocalState.CANCEL_REQUESTED) {
                            TextButton(onClick = { vm.recheckCancel(item.eventId) }, enabled = !state.pending) { Text(stringResource(R.string.sos_cancel_recheck)) }
                        }
                        if (item.state == SosLocalState.UNKNOWN) {
                            TextButton(onClick = { vm.recheckUnknown(item.eventId) }, enabled = !state.pending) { Text(stringResource(R.string.sos_recheck)) }
                        }
                        if (item.state in setOf(SosLocalState.CANCELLED, SosLocalState.RESOLVED, SosLocalState.EXPIRED, SosLocalState.SERVER_EXPIRED)) {
                            TextButton(onClick = { vm.clearFinished(item.eventId) }, enabled = !state.pending) { Text(stringResource(R.string.sos_clear_finished)) }
                        }
                    }
                }
            }
        })
        add(SosRow("notifications-card") {
            SosCard(stringResource(R.string.sos_notifications_section)) {
                // Server-side push configuration lives here, next to the
                // device registration it enables; the top of the screen keeps
                // its focus on picking who receives the request.
                Text(when (state.pushConfigured) {
                    true -> stringResource(R.string.sos_push_configured)
                    false -> stringResource(R.string.sos_push_unconfigured)
                    null -> stringResource(R.string.sos_push_unknown)
                })
                Text(if (!notificationPermission) stringResource(R.string.sos_notifications_disabled) else when (state.pushRegistered) {
                    true -> stringResource(R.string.sos_push_registered)
                    false -> stringResource(R.string.sos_push_not_registered)
                    null -> stringResource(R.string.sos_push_check_unknown)
                })
                // Status rows use plain language; the acceptance-vs-delivery
                // distinction stays explicit in this technical annotation.
                Text(stringResource(R.string.sos_notifications_technical_note), style = MaterialTheme.typography.bodySmall)
                pushMessage?.let { message -> Text(message) }
                OutlinedButton(onClick = onNotifications) { Text(stringResource(R.string.sos_notifications_setup)) }
                if (!notificationPermission) TextButton(onClick = onSystemSettings) { Text(stringResource(R.string.sos_open_notification_settings)) }
            }
        })
        add(SosRow("events-card") {
            SosCard(stringResource(R.string.sos_events_title)) {
                if (state.events.isEmpty() && !state.loading) {
                    Text(stringResource(R.string.sos_events_empty))
                }
                state.events.forEach { event ->
                    val person = if (event.ownerId == owner) stringResource(R.string.sos_person_you) else state.members.firstOrNull { it.userId == event.ownerId }?.displayName ?: stringResource(R.string.sos_person_member)
                    // Active events borrow the emergency pair; finished ones go
                    // neutral, so the list is scannable by color. The whole row
                    // stays the touch target at a 48dp minimum.
                    val (rowContainer, rowContent) = if (event.state == "ACTIVE") {
                        emergency.emergencyContainer to emergency.onEmergencyContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Surface(
                        onClick = { scrollToDetailCount++; vm.select(event.id) },
                        color = rowContainer,
                        contentColor = rowContent,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(
                            "$person · ${sosTime(event.recordedAt)} · ${sosState(event.state)}",
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        })
        state.selected?.let { event ->
            add(SosRow(DETAIL_CARD_KEY) {
                SosCard(stringResource(R.string.sos_detail_title)) {
                    val sender = if (event.ownerId == owner) stringResource(R.string.sos_person_you) else state.members.firstOrNull { it.userId == event.ownerId }?.displayName ?: stringResource(R.string.sos_person_member_unnamed)
                    Text(stringResource(R.string.sos_detail_sender, sender))
                    Text(stringResource(
                        R.string.sos_detail_status,
                        sosTime(event.recordedAt),
                        sosElapsed(event.recordedAt),
                        sosTime(event.receivedAt),
                        sosState(event.state),
                    ))
                    if (event.ownerId == owner) {
                        state.recipients.forEach { recipient ->
                            Column {
                                val person = state.members.firstOrNull { it.userId == recipient.userId }?.displayName ?: stringResource(R.string.sos_person_member)
                                val push = when (recipient.pushState) {
                                    "QUEUED" -> if (recipient.acceptedAt != null) stringResource(R.string.sos_push_queued_accepted) else stringResource(R.string.sos_push_queued_pending)
                                    "ACCEPTED" -> stringResource(R.string.sos_push_accepted)
                                    "SUPPRESSED" -> if (recipient.acceptedAt != null) stringResource(R.string.sos_push_suppressed_accepted) else stringResource(R.string.sos_push_suppressed)
                                    "EXPIRED" -> if (recipient.acceptedAt != null) stringResource(R.string.sos_push_expired_accepted) else stringResource(R.string.sos_push_expired)
                                    else -> stringResource(R.string.sos_push_state_unknown)
                                }
                                val statusLine = "$person · $push · ${if (recipient.acknowledgedAt != null) stringResource(R.string.sos_acknowledged) else stringResource(R.string.sos_not_acknowledged)}"
                                // A push the provider has not accepted for every
                                // send is still in flight; the attention tone
                                // shows at a glance who has not been reached.
                                if (recipient.pushState != "ACCEPTED") {
                                    Surface(
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        shape = MaterialTheme.shapes.small,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(statusLine, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                                    }
                                } else {
                                    Text(statusLine)
                                }
                                TextButton(onClick = { revokeRecipient = event.id to recipient.userId }, enabled = !state.pending) { Text(stringResource(R.string.sos_revoke_viewer, person)) }
                            }
                        }
                        if (event.state == "ACTIVE") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { action = event.id to "cancel" }) { Text(stringResource(R.string.sos_cancel)) }
                                TextButton(onClick = { action = event.id to "resolve" }) { Text(stringResource(R.string.sos_resolve)) }
                            }
                        }
                    } else if (event.state == "ACTIVE" && event.acknowledgedAt == null) {
                        Button(onClick = { action = event.id to "acknowledge" }) { Text(stringResource(R.string.sos_acknowledge)) }
                    }
                }
            })
        }
        add(SosRow("refresh") { TextButton(onClick = { vm.refresh() }, enabled = !state.loading && !state.pending) { Text(stringResource(R.string.sos_check_again)) } })
    }

    // The send confirms itself in place the moment the request is durably
    // queued; the snackbar offers the outbox status, which stays the source
    // of truth for server confirmation. The queued id is consumed only after
    // the snackbar finishes so the display is not cancelled mid-way.
    // Resolved in composition: the snackbar call runs in a coroutine.
    val queuedMessage = stringResource(R.string.sos_snackbar_queued)
    val viewStatusLabel = stringResource(R.string.sos_snackbar_view_status)
    LaunchedEffect(queuedEventId) {
        if (queuedEventId == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = queuedMessage,
            actionLabel = viewStatusLabel,
        )
        if (result == SnackbarResult.ActionPerformed) scrollToOutboxCount++
        vm.consumeQueuedEventId()
    }
    LaunchedEffect(scrollToOutboxCount) {
        if (scrollToOutboxCount == 0) return@LaunchedEffect
        val outboxIndex = rows.indexOfFirst { it.key == OUTBOX_TITLE_KEY }
        if (outboxIndex >= 0) listState.animateScrollToItem(outboxIndex)
    }
    // Selecting an event reveals its detail card further down the list; the
    // scroll makes the consequence of the tap visible instead of leaving the
    // user to hunt for it. Re-keyed on the selection so it fires after the
    // card has actually been added.
    LaunchedEffect(scrollToDetailCount, state.selected?.id) {
        if (scrollToDetailCount == 0 || state.selected == null) return@LaunchedEffect
        val detailIndex = rows.indexOfFirst { it.key == DETAIL_CARD_KEY }
        if (detailIndex >= 0) listState.animateScrollToItem(detailIndex)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // The outer home scaffold already consumes system insets for this page.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // Emergency container marks urgency without claiming a failure;
            // the send call stays visible above whatever part of the list the
            // user has scrolled to.
            Surface(
                color = emergency.emergencyContainer,
                contentColor = emergency.onEmergencyContainer,
                tonalElevation = 3.dp,
            ) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        when {
                            state.pending -> stringResource(R.string.sos_bottom_pending)
                            state.members.isEmpty() -> stringResource(R.string.sos_bottom_no_recipients)
                            selected.isEmpty() -> stringResource(R.string.sos_bottom_none_selected)
                            else -> stringResource(R.string.sos_bottom_ready, selected.size)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = { confirmSend = true },
                        enabled = !state.pending && selected.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.sos_send_button)) }
                    Text(stringResource(R.string.sos_bottom_disclaimer), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp + padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(rows, key = { it.key }) { row -> row.content() }
        }
    }

    if (confirmSend) AlertDialog(onDismissRequest = { confirmSend = false }, title = { Text(stringResource(R.string.sos_confirm_title)) },
        text = { Text(stringResource(R.string.sos_confirm_body, selected.size)) },
        confirmButton = { Button(onClick = {
            confirmSend = false
            vm.send(selected); selected = emptySet()
        }) { Text(stringResource(R.string.sos_confirm_send)) } },
        dismissButton = { TextButton(onClick = { confirmSend = false }) { Text(stringResource(R.string.action_cancel)) } })
    action?.let { (id, kind) -> AlertDialog(onDismissRequest = { action = null }, title = { Text(when (kind) { "cancel" -> stringResource(R.string.sos_action_cancel_title); "resolve" -> stringResource(R.string.sos_action_resolve_title); else -> stringResource(R.string.sos_action_acknowledge_title) }) },
        text = { Text(stringResource(R.string.sos_action_dialog_body)) },
        confirmButton = { Button(onClick = { action = null; when (kind) { "cancel" -> vm.cancel(id); "resolve" -> vm.resolve(id); else -> vm.acknowledge(id) } }) { Text(stringResource(R.string.sos_confirm)) } },
        dismissButton = { TextButton(onClick = { action = null }) { Text(stringResource(R.string.action_cancel)) } }) }
    revokeRecipient?.let { (event, recipient) -> AlertDialog(
        onDismissRequest = { revokeRecipient = null },
        title = { Text(stringResource(R.string.sos_revoke_dialog_title)) },
        text = { Text(stringResource(R.string.sos_revoke_dialog_body)) },
        confirmButton = { Button(onClick = { revokeRecipient = null; vm.revokeRecipient(event, recipient) }) { Text(stringResource(R.string.sos_revoke)) } },
        dismissButton = { TextButton(onClick = { revokeRecipient = null }) { Text(stringResource(R.string.action_cancel)) } },
    ) }
}

private fun sosTime(value: String): String = AnveTime.full(value, value)

@Composable
private fun sosElapsed(value: String): String {
    // Parsing stays inside runCatching; composable calls are not allowed
    // inside try-catch, so resources resolve after the result is known.
    val minutes = runCatching {
        Duration.between(Instant.parse(value), Instant.now()).toMinutes().coerceAtLeast(0)
    }.getOrNull()
    val unknown = stringResource(R.string.sos_elapsed_unknown)
    return when {
        minutes == null -> unknown
        minutes < 1L -> stringResource(R.string.sos_elapsed_just_sent)
        minutes < 60L -> stringResource(R.string.sos_elapsed_minutes, minutes)
        minutes < 1440L -> stringResource(R.string.sos_elapsed_hours, minutes / 60)
        else -> stringResource(R.string.sos_elapsed_days, minutes / 1440)
    }
}

@Composable
private fun sosState(value: String): String = when (value) {
    "ACTIVE" -> stringResource(R.string.sos_event_active)
    "CANCELLED" -> stringResource(R.string.sos_state_cancelled)
    "RESOLVED" -> stringResource(R.string.sos_state_resolved)
    "EXPIRED" -> stringResource(R.string.sos_event_expired)
    else -> stringResource(R.string.sos_event_unknown)
}
