package com.anve.app.ui.diagnostics

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.repeatOnLifecycle
import com.anve.app.AppContainer
import com.anve.app.BuildConfig
import com.anve.app.R
import com.anve.app.data.diagnostics.AndroidDiagnosticsSource
import com.anve.app.tracking.LocationPermissionState
import com.anve.app.tracking.TrackingStatus
import com.anve.app.tracking.diagnostics.DiagnosticsAction
import com.anve.app.tracking.diagnostics.SyncWorkStatus
import com.anve.app.tracking.diagnostics.diagnoseTracking
import com.anve.app.tracking.diagnostics.diagnosticAgeSeconds
import com.anve.app.ui.theme.AnVeTheme
import com.anve.app.ui.theme.isDarkSurface
import com.anve.app.ui.theme.rememberInkOnSurface
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
fun DiagnosticsScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val source = remember(container) {
        AndroidDiagnosticsSource(
            context, container.locationQueue, container.sosOutbox, container.sosRepository,
            container::hasCaptureCheckpoint, isOnline = { container.trackingManager.status.value.online },
        )
    }
    val model: DiagnosticsViewModel = viewModel(
        factory = DiagnosticsViewModel.factory(container.trackingManager, container.sessionStore, source),
    )
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var export by remember { mutableStateOf<DiagnosticsExport?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(state.owner) { export = null; actionError = null }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                nowMillis = System.currentTimeMillis()
                delay(30_000)
            }
        }
    }
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.activate()
            if (event == Lifecycle.Event.ON_STOP) { model.deactivate(); export = null }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) model.activate()
        onDispose { lifecycle.removeObserver(observer); model.deactivate() }
    }
    // Callback bodies are not composable, so their messages resolve here.
    val openSettingsFailedMessage = stringResource(R.string.diag_open_settings_failed)
    val exportStaleMessage = stringResource(R.string.diag_export_stale)
    val exportSubject = stringResource(R.string.diag_export_subject)
    val exportChooserTitle = stringResource(R.string.diag_export_chooser_title)
    val exportChangedMessage = stringResource(R.string.diag_export_changed)

    fun open(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            actionError = openSettingsFailedMessage
        }
    }

    DiagnosticsContent(
        state = state,
        nowMillis = nowMillis,
        onBack = onBack,
        onRefresh = model::refresh,
        onAction = { action ->
            when (action) {
                DiagnosticsAction.TRACKING -> onBack()
                DiagnosticsAction.APP_SETTINGS -> open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                DiagnosticsAction.LOCATION_SETTINGS -> open(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                DiagnosticsAction.NETWORK_SETTINGS -> open(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                DiagnosticsAction.RETRY_SYNC -> { container.trackingManager.retrySync(); model.refresh() }
            }
        },
        onExport = {
            scope.launch {
                export = model.prepareExport(BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT)
                if (export == null) actionError = exportStaleMessage
            }
        },
        actionError = actionError,
    )

    export?.let { report ->
        DiagnosticsExportReview(
            report = report.text,
            onDismiss = { export = null },
            onShare = {
                scope.launch {
                    if (model.canShare(report)) {
                        open(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, exportSubject)
                            putExtra(Intent.EXTRA_TEXT, report.text)
                        }, exportChooserTitle))
                    } else {
                        actionError = exportChangedMessage
                    }
                    export = null
                }
            },
        )
    }
}

@Composable
internal fun DiagnosticsExportReview(report: String, onDismiss: () -> Unit, onShare: () -> Unit) {
    val actionColor = rememberInkOnSurface()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.diag_export_review_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.diag_export_review_body))
                SelectionContainer {
                    Text(report, Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = { TextButton(onClick = onShare, colors = ButtonDefaults.textButtonColors(contentColor = actionColor)) { Text(stringResource(R.string.diag_export_share)) } },
        dismissButton = { TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = actionColor)) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsContent(
    state: DiagnosticsUiState,
    nowMillis: Long,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onAction: (DiagnosticsAction) -> Unit,
    onExport: () -> Unit,
    actionError: String? = null,
) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val status = state.status
    val details = state.details
    val ready = state.owner != null && status.ownerUserId == state.owner
    val diagnosis = diagnoseTracking(status)
    val colors = MaterialTheme.colorScheme
    val dark = isDarkSurface()
    val appBarBackground = if (dark) colors.primaryContainer else colors.primary
    val appBarForeground = if (dark) colors.onPrimaryContainer else colors.onPrimary
    val actionColor = if (dark) colors.onSurface else colors.primary
    val noticeBackground = if (!diagnosis.attention) colors.primaryContainer else if (dark) colors.secondary else colors.secondaryContainer
    val noticeForeground = if (!diagnosis.attention) colors.onPrimaryContainer else if (dark) colors.onSecondary else colors.onSecondaryContainer
    // Equal-contrast crossover for black versus white system status icons.
    DiagnosticsStatusBar(appBarBackground.luminance() > 0.179f)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diag_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                actions = { IconButton(onClick = onRefresh, enabled = !state.loading) { Icon(Icons.Default.Refresh, stringResource(R.string.diag_refresh_content_desc)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = appBarBackground, titleContentColor = appBarForeground, navigationIconContentColor = appBarForeground, actionIconContentColor = appBarForeground),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("diagnostics-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.loading) {
                item {
                    // Same labeled progress treatment as the other screens.
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            stringResource(R.string.diag_reading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (!ready) {
                item { Text(stringResource(R.string.diag_not_ready)) }
                return@LazyColumn
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(Modifier.size(56.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.LocationOn, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    Column {
                        Text(diagnosis.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(diagnosis.explanation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val overviewLabel = stringResource(R.string.diag_segment_overview)
                    val detailsLabel = stringResource(R.string.diag_segment_details)
                    listOf(overviewLabel, detailsLabel).forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = showDetails == (index == 1),
                            onClick = { showDetails = index == 1 },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                            modifier = Modifier.weight(1f),
                            colors = SegmentedButtonDefaults.colors(activeContainerColor = appBarBackground, activeContentColor = appBarForeground),
                            icon = {},
                        ) { Text(label) }
                    }
                }
            }
            if (diagnosis.action != null) item {
                DiagnosticsNotice(
                    title = if (diagnosis.action == DiagnosticsAction.NETWORK_SETTINGS) stringResource(R.string.diag_network_title) else diagnosis.title,
                    actionLabel = diagnosis.actionLabel.orEmpty(),
                    networkIssue = diagnosis.action == DiagnosticsAction.NETWORK_SETTINGS,
                    background = noticeBackground,
                    foreground = noticeForeground,
                    onAction = { onAction(diagnosis.action) },
                )
            }
            if (actionError != null) item { Text(actionError, color = MaterialTheme.colorScheme.error) }
            if (state.readFailed) item { Text(stringResource(R.string.diag_details_failed), color = MaterialTheme.colorScheme.error) }
            if (!showDetails) {
                item { DiagnosticsSection(stringResource(R.string.diag_section_capture), Icons.Default.LocationOn) {
                    DiagnosticRow(stringResource(R.string.diag_row_service), if (status.activelyTracking) stringResource(R.string.diag_value_running) else if (status.serviceRunning) stringResource(R.string.diag_value_capture_unconfirmed) else stringResource(R.string.diag_value_stopped))
                    DiagnosticRow(stringResource(R.string.diag_row_last_fix), ageLabel(status.lastFixAtMillis, nowMillis))
                } }
                item { DiagnosticsSection(stringResource(R.string.diag_section_sync), Icons.Default.Sync) {
                    DiagnosticRow(stringResource(R.string.diag_row_queued), details?.queue?.queuedPoints?.let { stringResource(R.string.diag_points_count, it) } ?: unknownLabel(state.loading))
                    DiagnosticRow(stringResource(R.string.diag_row_last_upload), ageLabel(status.lastUploadAtMillis, nowMillis))
                } }
                item { DiagnosticsSection(stringResource(R.string.diag_section_permissions), Icons.Default.Security) {
                    DiagnosticRow(stringResource(R.string.diag_row_location_permission), when (status.permission) {
                        LocationPermissionState.GRANTED_PRECISE -> stringResource(R.string.diag_value_precise)
                        LocationPermissionState.GRANTED_APPROXIMATE -> stringResource(R.string.diag_value_approximate)
                        LocationPermissionState.NOT_REQUESTED -> stringResource(R.string.diag_value_not_requested)
                        LocationPermissionState.DENIED -> stringResource(R.string.diag_value_not_granted)
                    })
                    DiagnosticRow(stringResource(R.string.diag_row_background_permission), yesNo(status.backgroundPermissionGranted, stringResource(R.string.diag_value_granted), stringResource(R.string.diag_value_not_granted)))
                    DiagnosticRow(stringResource(R.string.diag_row_location_services), yesNo(status.locationServicesEnabled, stringResource(R.string.diag_value_enabled), stringResource(R.string.diag_value_disabled)))
                    DiagnosticRow(stringResource(R.string.diag_row_battery_saver), yesNo(status.batterySaver, stringResource(R.string.diag_value_on), stringResource(R.string.diag_value_off)))
                    if (status.batterySaver || !status.backgroundPermissionGranted) Text(stringResource(R.string.diag_battery_limit_notice), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                } }
            } else {
                item { DiagnosticsSection(stringResource(R.string.diag_section_queue), Icons.Default.Sync) {
                    DiagnosticRow(stringResource(R.string.diag_row_queued), details?.queue?.queuedPoints?.toString() ?: unknownLabel(state.loading))
                    DiagnosticRow(stringResource(R.string.diag_row_oldest), ageLabel(details?.queue?.oldestCreatedAtMillis, nowMillis))
                    DiagnosticRow(stringResource(R.string.diag_row_attempts), details?.queue?.attempts?.toString() ?: unknownLabel(state.loading))
                    DiagnosticRow(stringResource(R.string.diag_row_retried), details?.queue?.retriedPoints?.toString() ?: unknownLabel(state.loading))
                    DiagnosticRow(stringResource(R.string.diag_row_checkpoint), details?.captureCheckpoint?.let { yesNo(it, stringResource(R.string.diag_value_saved), stringResource(R.string.diag_value_absent)) } ?: unknownLabel(state.loading))
                    DiagnosticRow(stringResource(R.string.diag_row_recovery), yesNo(status.recoveryRequired, stringResource(R.string.diag_value_yes), stringResource(R.string.diag_value_no)))
                    DiagnosticRow(stringResource(R.string.diag_row_sync_work), details?.syncWork?.let { work -> if (work.isEmpty()) stringResource(R.string.diag_value_no_pending_work) else work.map { workLabel(it) }.joinToString() } ?: unknownLabel(state.loading))
                    if (status.privacyDiscardedPoints) Text(stringResource(R.string.diag_privacy_discarded), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { onAction(DiagnosticsAction.RETRY_SYNC) }, enabled = !state.loading && status.online && status.consentEnabled && (details?.queue?.queuedPoints ?: 0) > 0, modifier = Modifier.padding(16.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = actionColor)) { Text(stringResource(R.string.diag_retry_sync)) }
                } }
                item { DiagnosticsSection(stringResource(R.string.diag_section_notifications), Icons.Default.Security) {
                    DiagnosticRow(stringResource(R.string.diag_row_notification_permission), yesNo(status.notificationsGranted, stringResource(R.string.diag_value_granted), stringResource(R.string.diag_value_not_granted)))
                    DiagnosticRow(stringResource(R.string.diag_row_push_registered), details?.pushRegistered?.let { yesNo(it, stringResource(R.string.diag_value_registered), stringResource(R.string.diag_value_not_registered)) } ?: unknownLabel(state.loading || state.checkingPush))
                    DiagnosticRow(stringResource(R.string.diag_row_push_configured), details?.pushConfigured?.let { yesNo(it, stringResource(R.string.diag_value_configured), stringResource(R.string.diag_value_not_configured)) } ?: unknownLabel(state.loading || state.checkingPush))
                    DiagnosticRow(stringResource(R.string.diag_row_sos_pending), details?.sosPending?.toString() ?: unknownLabel(state.loading))
                    Text(stringResource(R.string.diag_push_notice), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                } }
                item { DiagnosticsSection(stringResource(R.string.diag_section_device), Icons.Default.Security) {
                    DiagnosticRow(stringResource(R.string.diag_row_battery_optimization), details?.batteryOptimizationExempt?.let { yesNo(it, stringResource(R.string.diag_value_yes), stringResource(R.string.diag_value_no)) } ?: unknownLabel(state.loading))
                    DiagnosticRow(stringResource(R.string.diag_row_version), BuildConfig.VERSION_NAME)
                    DiagnosticRow(stringResource(R.string.diag_row_api), Build.VERSION.SDK_INT.toString())
                    Text(stringResource(R.string.diag_battery_not_measured), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                } }
            }
            item {
                TextButton(onClick = onExport, enabled = !state.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = actionColor)) {
                    Icon(Icons.Default.Description, null, Modifier.padding(end = 12.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                        Text(stringResource(R.string.diag_export_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.diag_export_subtitle), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Text(stringResource(R.string.diag_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun DiagnosticsNotice(title: String, actionLabel: String, networkIssue: Boolean, background: Color, foreground: Color, onAction: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = background, contentColor = foreground)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(12.dp)) {
            val icon = if (networkIssue) Icons.Default.WifiOff else Icons.Default.Info
            val action: @Composable (Modifier) -> Unit = { modifier ->
                OutlinedButton(onClick = onAction, modifier = modifier, border = BorderStroke(1.dp, foreground), colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground)) {
                    Icon(Icons.Default.Settings, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(actionLabel)
                }
            }
            if (maxWidth < 340.dp || LocalDensity.current.fontScale > 1.15f) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(icon, null)
                        Text(title, style = MaterialTheme.typography.titleMedium)
                    }
                    action(Modifier.fillMaxWidth())
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(icon, null)
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    action(Modifier)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsSection(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun yesNo(value: Boolean, yes: String, no: String) = if (value) yes else no

@Composable
private fun unknownLabel(loading: Boolean) = if (loading) stringResource(R.string.diag_reading) else stringResource(R.string.diag_unknown)

@Composable
private fun DiagnosticsStatusBar(darkIcons: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(context, view, darkIcons) {
        val controller = context.diagnosticsActivity()?.window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = darkIcons
        onDispose { if (previous != null) controller.isAppearanceLightStatusBars = previous }
    }
}

private fun Context.diagnosticsActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.diagnosticsActivity()
    else -> null
}

@Composable
private fun workLabel(status: SyncWorkStatus): String = when (status) {
    SyncWorkStatus.ENQUEUED -> stringResource(R.string.diag_work_enqueued)
    SyncWorkStatus.RUNNING -> stringResource(R.string.diag_value_running)
    SyncWorkStatus.BLOCKED -> stringResource(R.string.diag_work_blocked)
    SyncWorkStatus.SUCCEEDED -> stringResource(R.string.diag_work_done)
    SyncWorkStatus.FAILED -> stringResource(R.string.diag_work_failed)
    SyncWorkStatus.CANCELLED -> stringResource(R.string.diag_work_cancelled)
}

@Composable
private fun ageLabel(timestamp: Long?, now: Long): String {
    val ageSeconds = diagnosticAgeSeconds(timestamp, now)
    val noData = stringResource(R.string.diag_age_no_data)
    val clock = stringResource(R.string.diag_age_clock)
    return when {
        ageSeconds == null -> if (timestamp == null) noData else clock
        ageSeconds < 60 -> stringResource(R.string.diag_age_under_minute)
        ageSeconds < 3_600 -> stringResource(R.string.diag_age_minutes, ageSeconds / 60)
        ageSeconds < 86_400 -> stringResource(R.string.diag_age_hours, ageSeconds / 3_600)
        else -> stringResource(R.string.diag_age_days, ageSeconds / 86_400)
    }
}

@Preview(showBackground = true)
@Composable
private fun DiagnosticsPreview() {
    AnVeTheme {
        DiagnosticsContent(DiagnosticsUiState(owner = "preview", status = TrackingStatus(ownerUserId = "preview", sessionReady = true)), 1_000_000, {}, {}, {}, {})
    }
}
