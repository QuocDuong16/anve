package com.anve.app.ui.sos

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anve.app.AppContainer
import com.anve.app.R
import com.anve.app.data.api.SosEventDto
import com.anve.app.data.sos.FirebaseSetup
import com.anve.app.data.sos.SosCachedMember
import com.anve.app.data.sos.SosLocalState
import com.anve.app.data.sos.SosOutboxItem
import com.anve.app.data.sos.areAppNotificationsEnabled
import com.anve.app.ui.brand.AnVeSosCareIllustration
import com.anve.app.ui.common.AnVeCheckingState
import com.anve.app.ui.common.AnveErrorNotice
import com.anve.app.ui.common.AnveTime
import com.anve.app.ui.common.StitchBadgeKind
import com.anve.app.ui.common.StitchLoadingSkeletonCard
import com.anve.app.ui.common.StitchSemanticBadge
import com.anve.app.ui.common.StitchTimelineItem
import com.anve.app.ui.common.anveContentWidth
import com.anve.app.ui.signin.findActivity
import com.anve.app.ui.theme.AnVeTheme
import com.anve.app.ui.theme.LocalEmergencyColors
import com.anve.app.ui.theme.LocalStitchColors
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
    val activity = remember(context) { context.findActivity() }
    var permission by remember(owner) { mutableStateOf(areAppNotificationsEnabled(context)) }
    var notificationPermissionRequested by rememberSaveable(owner) { mutableStateOf(false) }
    var notificationPermissionPermanentlyDenied by rememberSaveable(owner) { mutableStateOf(false) }
    var autoRetriedRegistration by remember(owner) { mutableStateOf(false) }
    var settingsMessage by remember(owner) { mutableStateOf<String?>(null) }
    val openSettingsFailedMessage = stringResource(R.string.sos_open_settings_failed)
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission = areAppNotificationsEnabled(context)
        if (permission && owner != null) {
            notificationPermissionPermanentlyDenied = false
            container.pushRegistrationCoordinator.onPermissionGranted(owner)
            vm.refresh(keepError = true)
        } else if (!permission && notificationPermissionRequested && activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionPermanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        notificationPermissionRequested = false
    }
    DisposableEffect(owner, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    vm.activate(owner)
                    permission = areAppNotificationsEnabled(context)
                    if (permission && owner != null) {
                        notificationPermissionPermanentlyDenied = false
                        container.pushRegistrationCoordinator.syncIfPermitted(owner)
                    }
                }
                Lifecycle.Event.ON_STOP -> vm.activate(null)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); vm.activate(null) }
    }
    LaunchedEffect(owner, lifecycle) {
        if (owner != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { delay(10_000); vm.poll() }
        }
    }
    LaunchedEffect(owner, permission, state.pushRegistered) {
        if (owner != null && permission && state.pushRegistered == false && !autoRetriedRegistration && FirebaseSetup.isConfigured()) {
            autoRetriedRegistration = true
            container.pushRegistrationCoordinator.syncIfPermitted(owner, force = true)
        }
    }
    LaunchedEffect(owner, eventId, state.owner) { if (owner != null && state.owner == owner && eventId != null) vm.select(eventId) }
    if (owner == null || state.owner != owner) {
        // Session not re-emitted yet (fresh process or account switch): state
        // the wait instead of a blank frame, same wording as the account area.
        AnVeCheckingState(stringResource(R.string.home_checking_account))
        return
    }
    val canRequestNotificationPermission =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationPermissionPermanentlyDenied
    SosContent(
        state = state,
        vm = vm,
        notificationPermission = permission,
        settingsMessage = settingsMessage,
        onNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionRequested = true
                permissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onSystemSettings = {
            val intent = if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            runCatching { context.startActivity(intent) }.onFailure { settingsMessage = openSettingsFailedMessage }
        },
        canRequestNotificationPermission = canRequestNotificationPermission,
        pushConfiguredOnBuild = FirebaseSetup.isConfigured(),
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
private fun SosCard(
    title: String,
    trailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val stitch = LocalStitchColors.current
    OutlinedCard(
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, stitch.hairline),
        colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                trailing()
            }
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
internal fun SosContent(
    state: SosState,
    vm: SosViewModel,
    notificationPermission: Boolean,
    settingsMessage: String? = null,
    onNotifications: () -> Unit,
    onSystemSettings: () -> Unit = {},
    canRequestNotificationPermission: Boolean = true,
    pushConfiguredOnBuild: Boolean = true,
) {
    val queuedEventId by vm.queuedEventId.collectAsStateWithLifecycle()
    SosContentBody(
        state = state,
        queuedEventId = queuedEventId,
        notificationPermission = notificationPermission,
        settingsMessage = settingsMessage,
        onNotifications = onNotifications,
        onSystemSettings = onSystemSettings,
        canRequestNotificationPermission = canRequestNotificationPermission,
        pushConfiguredOnBuild = pushConfiguredOnBuild,
        onRefresh = { vm.refresh() },
        onSend = { vm.send(it) },
        onCancel = { vm.cancel(it) },
        onResolve = { vm.resolve(it) },
        onAcknowledge = { vm.acknowledge(it) },
        onRecheckCancel = { vm.recheckCancel(it) },
        onRecheckUnknown = { vm.recheckUnknown(it) },
        onClearFinished = { vm.clearFinished(it) },
        onSelectEvent = { vm.select(it) },
        onRevokeRecipient = { eventId, recipientId -> vm.revokeRecipient(eventId, recipientId) },
        onConsumeQueuedEventId = { vm.consumeQueuedEventId() },
    )
}

@Composable
private fun SosContentBody(
    state: SosState,
    queuedEventId: String?,
    notificationPermission: Boolean,
    settingsMessage: String?,
    onNotifications: () -> Unit,
    onSystemSettings: () -> Unit,
    canRequestNotificationPermission: Boolean,
    pushConfiguredOnBuild: Boolean,
    onRefresh: () -> Unit,
    onSend: (Set<String>) -> Unit,
    onCancel: (String) -> Unit,
    onResolve: (String) -> Unit,
    onAcknowledge: (String) -> Unit,
    onRecheckCancel: (String) -> Unit,
    onRecheckUnknown: (String) -> Unit,
    onClearFinished: (String) -> Unit,
    onSelectEvent: (String) -> Unit,
    onRevokeRecipient: (String, String) -> Unit,
    onConsumeQueuedEventId: () -> Unit,
) {
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
    val stitch = LocalStitchColors.current
    val colors = MaterialTheme.colorScheme

    val rows = buildList {
        add(SosRow("title") {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.sos_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                StitchSemanticBadge(
                    text = stringResource(
                        if (state.pending) R.string.sos_status_sending else R.string.sos_status_ready,
                    ),
                    kind = if (state.pending) StitchBadgeKind.EMERGENCY else StitchBadgeKind.SAFE_HOME,
                )
            }
        })
        if (state.loading || state.pending) add(SosRow("progress") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // A bare spinner says nothing to sight or screen reader alike; same
                // labeled progress row as History and Places.
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = stitch.cardTactile,
                    border = BorderStroke(1.dp, stitch.hairline),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
                        Text(
                            stringResource(if (state.loading) R.string.action_loading else R.string.action_processing),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                if (state.loading && state.members.isEmpty() && state.events.isEmpty()) {
                    StitchLoadingSkeletonCard(rows = 2)
                }
            }
        })
        state.errorRes?.let { res ->
            add(SosRow("error") {
                // The bottom-of-list "Kiểm tra lại" stays as the secondary
                // affordance, but it sits after a long scroll; the failure read
                // at the top carries its own adjacent retry so recovery lives
                // where the message is.
                AnveErrorNotice(stringResource(res), onRetry = onRefresh)
            })
        }
        if (state.offline) add(SosRow("offline") {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = stitch.transitContainer,
                contentColor = stitch.onTransitContainer,
                border = BorderStroke(1.dp, stitch.hairline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.sos_offline_notice),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(14.dp),
                )
            }
        })
        add(SosRow("hero-card") {
            val dialer = LocalContext.current
            // Stitch Screen 58 Hero SOS Focus Card: compact soothing halo + non-blocking safety notice + 113/114/115 grid.
            OutlinedCard(
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, emergency.emergency.copy(alpha = 0.35f)),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = stitch.cardTactile,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AnVeSosCareIllustration(
                            modifier = Modifier.size(64.dp),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.sos_hero_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(R.string.sos_emergency_disclaimer),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(color = stitch.hairline)
                    Text(
                        text = stringResource(R.string.sos_emergency_calls_label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // The disclaimer asks the user to call the official emergency
                    // numbers, so the dial affordances sit right beside it: ACTION_DIAL
                    // only pre-fills the dialer — AnVề still never places the call.
                    val largeFont = LocalDensity.current.fontScale > 1.25f
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val stackHotlines = maxWidth < 340.dp || largeFont
                        val hotlines = listOf(
                            113 to R.string.sos_service_police,
                            114 to R.string.sos_service_fire,
                            115 to R.string.sos_service_ambulance,
                        )
                        val hotlineButton: @Composable (Int, Int, Modifier) -> Unit = { number, serviceRes, btnMod ->
                            OutlinedButton(
                                onClick = { runCatching { dialer.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))) } },
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, stitch.hairline),
                                modifier = btnMod.heightIn(min = 52.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                if (stackHotlines) {
                                    Icon(
                                        imageVector = Icons.Outlined.Phone,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "$number · ${stringResource(serviceRes)}",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = stringResource(R.string.sos_call_number, number),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                } else {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            text = number.toString(),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            text = stringResource(serviceRes),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colors.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        if (stackHotlines) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                hotlines.forEach { (number, serviceRes) ->
                                    hotlineButton(number, serviceRes, Modifier.fillMaxWidth())
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                hotlines.forEach { (number, serviceRes) ->
                                    hotlineButton(number, serviceRes, Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        })
        add(SosRow("recipients-card") {
            SosCard(
                title = stringResource(R.string.sos_recipients_title),
                trailing = {
                    if (state.members.isNotEmpty()) {
                        StitchSemanticBadge(
                            text = "${selected.size}/${state.members.size}",
                            kind = if (selected.isNotEmpty()) StitchBadgeKind.EMERGENCY else StitchBadgeKind.NEUTRAL,
                        )
                    }
                },
            ) {
                if (state.members.isEmpty()) {
                    Text(stringResource(R.string.sos_recipients_empty), color = colors.onSurfaceVariant)
                }
                state.members.forEachIndexed { index, member ->
                    if (index > 0) {
                        HorizontalDivider(color = stitch.hairline)
                    }
                    val checked = member.userId in selected
                    // The whole row toggles so the checkbox target is not the only
                    // touchable point; the checkbox itself stays indicative.
                    // Recipient rows remain the first toggleables in SosContent for tests.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .toggleable(
                                value = checked,
                                enabled = !state.pending,
                                role = Role.Checkbox,
                                onValueChange = { isChecked -> selected = if (isChecked) selected + member.userId else selected - member.userId },
                            )
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(if (checked) emergency.emergencyContainer else stitch.primaryFixed),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                member.displayName.take(1).uppercase().ifBlank { "?" },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (checked) emergency.onEmergencyContainer else stitch.onPrimaryFixed,
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = member.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            if (index == 0) {
                                StitchSemanticBadge(
                                    text = stringResource(R.string.sos_primary_recipient_badge),
                                    kind = StitchBadgeKind.SAFE_HOME,
                                )
                            }
                        }
                        Checkbox(checked = checked, enabled = !state.pending, onCheckedChange = null)
                    }
                }
            }
        })
        add(SosRow(OUTBOX_TITLE_KEY) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.sos_outbox_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
                if (state.outbox.isEmpty()) {
                    Text(stringResource(R.string.sos_outbox_empty), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                state.outbox.forEachIndexed { index, item ->
                    val (badgeKind, nodeBg, nodeFg) = when (item.state) {
                        SosLocalState.CONFIRMED -> Triple(StitchBadgeKind.EMERGENCY, emergency.emergencyContainer, emergency.onEmergencyContainer)
                        SosLocalState.QUEUED, SosLocalState.SENDING, SosLocalState.UNKNOWN, SosLocalState.CANCEL_REQUESTED ->
                            Triple(StitchBadgeKind.IN_TRANSIT, stitch.transitContainer, stitch.onTransitContainer)
                        else -> Triple(StitchBadgeKind.NEUTRAL, colors.surfaceContainerHigh, colors.onSurfaceVariant)
                    }
                    val timelineIcon = when (item.state) {
                        SosLocalState.CONFIRMED -> Icons.Outlined.Warning
                        SosLocalState.CANCELLED, SosLocalState.RESOLVED -> Icons.Outlined.CheckCircle
                        else -> Icons.Outlined.Schedule
                    }
                    StitchTimelineItem(
                        icon = timelineIcon,
                        nodeContainerColor = nodeBg,
                        nodeContentColor = nodeFg,
                        isLast = index == state.outbox.lastIndex,
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            // Time and audience separate the attempts from each
                            // other; two "Đang chờ mạng" rows must stay
                            // distinguishable on a safety feature.
                            Text(
                                stringResource(R.string.sos_outbox_row_meta, sosTime(item.recordedAt, stringResource(R.string.journey_time_unknown)), item.recipientIds.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                            StitchSemanticBadge(
                                text = when (item.state) {
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
                                kind = badgeKind,
                            )
                            if (item.state in setOf(SosLocalState.QUEUED, SosLocalState.SENDING, SosLocalState.UNKNOWN, SosLocalState.CONFIRMED)) {
                                TextButton(
                                    onClick = { action = item.eventId to "cancel" },
                                    enabled = !state.pending,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text(stringResource(R.string.sos_cancel_action)) }
                            }
                            if (item.state == SosLocalState.CANCEL_REQUESTED) {
                                TextButton(
                                    onClick = { onRecheckCancel(item.eventId) },
                                    enabled = !state.pending,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text(stringResource(R.string.sos_cancel_recheck)) }
                            }
                            if (item.state == SosLocalState.UNKNOWN) {
                                TextButton(
                                    onClick = { onRecheckUnknown(item.eventId) },
                                    enabled = !state.pending,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text(stringResource(R.string.sos_recheck)) }
                            }
                            if (item.state in setOf(SosLocalState.CANCELLED, SosLocalState.RESOLVED, SosLocalState.EXPIRED, SosLocalState.SERVER_EXPIRED)) {
                                TextButton(
                                    onClick = { onClearFinished(item.eventId) },
                                    enabled = !state.pending,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text(stringResource(R.string.sos_clear_finished)) }
                            }
                        }
                    }
                }
            }
        })
        add(SosRow("notifications-card") {
            SosCard(stringResource(R.string.sos_notifications_section)) {
                // Push registration runs automatically when OS notifications are allowed;
                // the card reports readiness without exposing a manual registration step.
                Text(
                    text = when (state.pushConfigured) {
                        true -> stringResource(R.string.sos_push_configured)
                        false -> stringResource(R.string.sos_push_unconfigured)
                        null -> stringResource(R.string.sos_push_unknown)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = if (!notificationPermission) {
                        stringResource(R.string.sos_notifications_disabled)
                    } else when (state.pushRegistered) {
                        true -> stringResource(R.string.sos_push_registered)
                        false -> if (pushConfiguredOnBuild) {
                            stringResource(R.string.sos_push_auto_connecting)
                        } else {
                            stringResource(R.string.sos_push_not_configured)
                        }
                        null -> stringResource(R.string.sos_push_check_unknown)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (!notificationPermission) emergency.onEmergencyContainer else colors.onSurface,
                )
                HorizontalDivider(color = stitch.hairline)
                // Status rows use plain language; the acceptance-vs-delivery
                // distinction stays explicit in this technical annotation.
                Text(
                    stringResource(R.string.sos_notifications_technical_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                settingsMessage?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (!notificationPermission) {
                    OutlinedButton(
                        onClick = if (canRequestNotificationPermission) onNotifications else onSystemSettings,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(
                            stringResource(
                                if (canRequestNotificationPermission) R.string.sos_notifications_enable
                                else R.string.sos_open_notification_settings,
                            ),
                        )
                    }
                }
            }
        })
        add(SosRow("events-card") {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.sos_events_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
                if (state.events.isEmpty() && !state.loading) {
                    Text(stringResource(R.string.sos_events_empty), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                state.events.forEachIndexed { index, event ->
                    val person = if (event.ownerId == owner) stringResource(R.string.sos_person_you) else state.members.firstOrNull { it.userId == event.ownerId }?.displayName ?: stringResource(R.string.sos_person_member)
                    val active = event.state == "ACTIVE"
                    StitchTimelineItem(
                        icon = if (active) Icons.Outlined.Warning else Icons.Outlined.CheckCircle,
                        nodeContainerColor = if (active) emergency.emergencyContainer else colors.surfaceContainerHigh,
                        nodeContentColor = if (active) emergency.onEmergencyContainer else colors.onSurfaceVariant,
                        isLast = index == state.events.lastIndex,
                    ) {
                        Surface(
                            onClick = { scrollToDetailCount++; onSelectEvent(event.id) },
                            color = if (active) emergency.emergencyContainer.copy(alpha = 0.45f) else colors.surfaceContainerLow,
                            contentColor = if (active) emergency.onEmergencyContainer else colors.onSurface,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                StitchSemanticBadge(
                                    text = sosState(event.state),
                                    kind = if (active) StitchBadgeKind.EMERGENCY else StitchBadgeKind.NEUTRAL,
                                )
                                Text(
                                    text = "$person · ${sosTime(event.recordedAt, stringResource(R.string.journey_time_unknown))}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        })
        state.selected?.let { event ->
            add(SosRow(DETAIL_CARD_KEY) {
                SosCard(stringResource(R.string.sos_detail_title)) {
                    val sender = if (event.ownerId == owner) stringResource(R.string.sos_person_you) else state.members.firstOrNull { it.userId == event.ownerId }?.displayName ?: stringResource(R.string.sos_person_member_unnamed)
                    Text(stringResource(R.string.sos_detail_sender, sender), fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(
                            R.string.sos_detail_status,
                            sosTime(event.recordedAt, stringResource(R.string.journey_time_unknown)),
                            sosElapsed(event.recordedAt),
                            sosTime(event.receivedAt, stringResource(R.string.journey_time_unknown)),
                            sosState(event.state),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    if (event.ownerId == owner) {
                        state.recipients.forEach { recipient ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                        color = stitch.transitContainer,
                                        contentColor = stitch.onTransitContainer,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(statusLine, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                                    }
                                } else {
                                    Text(statusLine, style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(
                                    onClick = { revokeRecipient = event.id to recipient.userId },
                                    enabled = !state.pending,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    // Destructive trigger carries the error color,
                                    // same as the revoke rows in Home/Circle/Places.
                                    Text(stringResource(R.string.sos_revoke_viewer, person), color = colors.error)
                                }
                            }
                        }
                        if (event.state == "ACTIVE") {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TextButton(
                                    onClick = { action = event.id to "cancel" },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text(stringResource(R.string.sos_cancel)) }
                                TextButton(
                                    onClick = { action = event.id to "resolve" },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text(stringResource(R.string.sos_resolve)) }
                            }
                        }
                    } else if (event.state == "ACTIVE" && event.acknowledgedAt == null) {
                        Button(
                            onClick = { action = event.id to "acknowledge" },
                            shape = CircleShape,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.sos_acknowledge)) }
                    }
                }
            })
        }
        add(SosRow("refresh") {
            TextButton(
                onClick = onRefresh,
                enabled = !state.loading && !state.pending,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.sos_check_again)) }
        })
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
        onConsumeQueuedEventId()
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
            // user has scrolled to. The outer HomeScreen Scaffold already
            // consumes navigationBars insets, so none is added again here.
            Surface(
                color = emergency.emergencyContainer,
                contentColor = emergency.onEmergencyContainer,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                border = BorderStroke(1.dp, emergency.emergency.copy(alpha = 0.25f)),
                tonalElevation = 3.dp,
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .anveContentWidth()
                            .padding(horizontal = 18.dp, vertical = 14.dp),
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
                            fontWeight = FontWeight.SemiBold,
                        )
                        Button(
                            onClick = { confirmSend = true },
                            enabled = !state.pending && selected.isNotEmpty(),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = emergency.emergency,
                                contentColor = emergency.onEmergency,
                            ),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) { Text(stringResource(R.string.sos_send_button)) }
                        Text(stringResource(R.string.sos_bottom_disclaimer), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxHeight().fillMaxWidth().anveContentWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(rows, key = { it.key }) { row -> row.content() }
            }
        }
    }

    // Sending is the wanted action, not a destruction, so it keeps a filled
    // primary confirm; only cancel/resolve/revoke below take the error pair.
    if (confirmSend) AlertDialog(
        onDismissRequest = { confirmSend = false },
        title = { Text(stringResource(R.string.sos_confirm_title)) },
        text = {
            Text(
                stringResource(R.string.sos_confirm_body, selected.size),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    confirmSend = false
                    onSend(selected); selected = emptySet()
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.sos_confirm_send)) }
        },
        dismissButton = {
            TextButton(
                onClick = { confirmSend = false },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.action_cancel)) }
        },
    )
    // One dialog serves three kinds with different weight: cancel and resolve
    // destroy the request, so their confirm follows the app-wide
    // destructive-confirm pattern (error text + neutral Hủy). Acknowledge is a
    // positive one-way confirmation from a viewer — the same class as the
    // journey ARRIVE confirm — and coloring it error would break the semantic
    // color roles: SOS urgency is not a failure (cf. the emergency-vs-error
    // rule in DESIGN.md and the HomeScreen SOS FAB comment).
    action?.let { (id, kind) ->
        AlertDialog(
            onDismissRequest = { action = null },
            title = {
                Text(
                    when (kind) {
                        "cancel" -> stringResource(R.string.sos_action_cancel_title)
                        "resolve" -> stringResource(R.string.sos_action_resolve_title)
                        else -> stringResource(R.string.sos_action_acknowledge_title)
                    },
                )
            },
            text = {
                Text(
                    stringResource(R.string.sos_action_dialog_body),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        action = null
                        when (kind) {
                            "cancel" -> onCancel(id)
                            "resolve" -> onResolve(id)
                            else -> onAcknowledge(id)
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(R.string.sos_confirm),
                        color = when (kind) {
                            "cancel", "resolve" -> MaterialTheme.colorScheme.error
                            else -> Color.Unspecified
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { action = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    revokeRecipient?.let { (event, recipient) ->
        AlertDialog(
            onDismissRequest = { revokeRecipient = null },
            title = { Text(stringResource(R.string.sos_revoke_dialog_title)) },
            text = {
                Text(
                    stringResource(R.string.sos_revoke_dialog_body),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            // Revoking a viewer's access is always destructive: the confirm keeps
            // the error pair while the dismiss stays neutral.
            confirmButton = {
                TextButton(
                    onClick = { revokeRecipient = null; onRevokeRecipient(event, recipient) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.sos_revoke), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    onClick = { revokeRecipient = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

// AnveTime.full already falls back when parsing fails; the fallback comes
// from the call site because this helper stays pure (no composable resource
// reads). A parse failure must show the neutral unknown-time copy, never the
// raw ISO string — same rule HistoryScreen codifies, kept on the
// highest-stakes surface too.
private fun sosTime(value: String, fallback: String): String = AnveTime.full(value, fallback)

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

@Preview(name = "1. Standard Light", showBackground = true)
@Composable
private fun SosContentPreview() {
    AnVeTheme {
        SosContentBody(
            state = previewSosState(),
            queuedEventId = null,
            notificationPermission = true,
            settingsMessage = null,
            onNotifications = {},
            onSystemSettings = {},
            canRequestNotificationPermission = true,
            pushConfiguredOnBuild = true,
            onRefresh = {},
            onSend = {},
            onCancel = {},
            onResolve = {},
            onAcknowledge = {},
            onRecheckCancel = {},
            onRecheckUnknown = {},
            onClearFinished = {},
            onSelectEvent = {},
            onRevokeRecipient = { _, _ -> },
            onConsumeQueuedEventId = {},
        )
    }
}

@Preview(name = "2. Dark Mode", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SosContentDarkPreview() {
    AnVeTheme(darkTheme = true) {
        SosContentBody(
            state = previewSosState(),
            queuedEventId = null,
            notificationPermission = false,
            settingsMessage = null,
            onNotifications = {},
            onSystemSettings = {},
            canRequestNotificationPermission = true,
            pushConfiguredOnBuild = true,
            onRefresh = {},
            onSend = {},
            onCancel = {},
            onResolve = {},
            onAcknowledge = {},
            onRecheckCancel = {},
            onRecheckUnknown = {},
            onClearFinished = {},
            onSelectEvent = {},
            onRevokeRecipient = { _, _ -> },
            onConsumeQueuedEventId = {},
        )
    }
}

@Preview(name = "3. Elderly 200% Font", fontScale = 2.0f, showBackground = true)
@Composable
private fun SosContentElderlyFontPreview() {
    AnVeTheme {
        SosContentBody(
            state = previewSosState(),
            queuedEventId = null,
            notificationPermission = false,
            settingsMessage = null,
            onNotifications = {},
            onSystemSettings = {},
            canRequestNotificationPermission = true,
            pushConfiguredOnBuild = true,
            onRefresh = {},
            onSend = {},
            onCancel = {},
            onResolve = {},
            onAcknowledge = {},
            onRecheckCancel = {},
            onRecheckUnknown = {},
            onClearFinished = {},
            onSelectEvent = {},
            onRevokeRecipient = { _, _ -> },
            onConsumeQueuedEventId = {},
        )
    }
}

private fun previewSosState(): SosState = SosState(
    owner = "owner",
    members = listOf(
        SosCachedMember("owner", "m1", "Bố Hải", 0L),
        SosCachedMember("owner", "m2", "Mẹ Lan", 0L),
    ),
    outbox = listOf(
        SosOutboxItem(
            eventId = "outbox-1",
            ownerId = "owner",
            recordedAt = "2026-11-16T00:55:00Z",
            recipientIds = listOf("m1", "m2"),
            createdAtMillis = 1_000L,
            state = SosLocalState.QUEUED,
        ),
    ),
    events = listOf(
        SosEventDto(
            id = "ev-1",
            ownerId = "owner",
            recordedAt = "2026-11-16T00:50:00Z",
            receivedAt = "2026-11-16T00:50:05Z",
            state = "ACTIVE",
        ),
    ),
    pushRegistered = true,
    pushConfigured = true,
)
