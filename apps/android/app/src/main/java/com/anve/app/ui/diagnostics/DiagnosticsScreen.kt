package com.anve.app.ui.diagnostics

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
import com.anve.app.ui.brand.AnVeDiagnosticsIllustration
import com.anve.app.ui.common.AnVeMetricColumn
import com.anve.app.ui.common.AnveErrorNotice
import com.anve.app.ui.common.StitchLoadingSkeletonCard
import com.anve.app.ui.common.anveContentWidth
import com.anve.app.ui.theme.AnVeTheme
import com.anve.app.ui.theme.LocalStitchColors
import com.anve.app.ui.theme.SystemStatusBarIcons
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
        confirmButton = {
            TextButton(
                onClick = onShare,
                colors = ButtonDefaults.textButtonColors(contentColor = actionColor),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.diag_export_share)) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = actionColor),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.action_cancel)) }
        },
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
    val stitch = LocalStitchColors.current
    val dark = isDarkSurface()
    val appBarBackground = if (dark) colors.primaryContainer else colors.primary
    val appBarForeground = if (dark) colors.onPrimaryContainer else colors.onPrimary
    val actionColor = if (dark) colors.onSurface else colors.primary
    val noticeBackground = if (!diagnosis.attention) stitch.liveContainer else stitch.transitContainer
    val noticeForeground = if (!diagnosis.attention) stitch.onLiveContainer else stitch.onTransitContainer
    // Equal-contrast crossover for black versus white system status icons.
    SystemStatusBarIcons(appBarBackground.luminance() > 0.179f)
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diag_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) } },
                actions = { IconButton(onClick = onRefresh, enabled = !state.loading) { Icon(Icons.Outlined.Refresh, stringResource(R.string.diag_refresh_content_desc)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = appBarBackground, titleContentColor = appBarForeground, navigationIconContentColor = appBarForeground, actionIconContentColor = appBarForeground),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.fillMaxHeight().fillMaxWidth().anveContentWidth().testTag("diagnostics-list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.loading) {
                item {
                    // Same labeled progress treatment as the other screens.
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            stringResource(R.string.diag_reading),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        if (details == null) {
                            StitchLoadingSkeletonCard(rows = 2)
                        }
                    }
                }
            }
            if (!ready) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.diag_not_ready))
                    }
                }
                return@LazyColumn
            }
            item {
                OutlinedCard(
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(
                        1.dp,
                        if (diagnosis.attention) colors.secondary.copy(alpha = 0.35f) else stitch.hairline,
                    ),
                    colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        AnVeDiagnosticsIllustration(
                            attention = diagnosis.attention,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(92.dp)
                                .clip(RoundedCornerShape(16.dp)),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                diagnosis.title,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.semantics { heading() },
                            )
                            Text(diagnosis.explanation, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        }
                        HorizontalDivider(color = stitch.hairline)
                        val fixAgeLabel = stringResource(R.string.diag_metric_fix_age)
                        val fixAgeValue = ageLabel(status.lastFixAtMillis, nowMillis)
                        val queueCountLabel = stringResource(R.string.diag_metric_queue_count)
                        val queueCountValue = details?.queue?.queuedPoints?.toString() ?: unknownLabel(state.loading)
                        val queueCountColor = if ((details?.queue?.queuedPoints ?: 0) > 0) stitch.onTransitContainer else colors.onSurface
                        val uploadAgeLabel = stringResource(R.string.diag_metric_upload_age)
                        val uploadAgeValue = ageLabel(status.lastUploadAtMillis, nowMillis)
                        val largeMetrics = LocalDensity.current.fontScale > 1.3f
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            if (maxWidth < 340.dp || largeMetrics) {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    maxItemsInEachRow = 2,
                                ) {
                                    AnVeMetricColumn(
                                        label = fixAgeLabel,
                                        value = fixAgeValue,
                                        modifier = Modifier.weight(1f),
                                    )
                                    AnVeMetricColumn(
                                        label = queueCountLabel,
                                        value = queueCountValue,
                                        valueColor = queueCountColor,
                                        modifier = Modifier.weight(1f),
                                    )
                                    AnVeMetricColumn(
                                        label = uploadAgeLabel,
                                        value = uploadAgeValue,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    AnVeMetricColumn(
                                        label = fixAgeLabel,
                                        value = fixAgeValue,
                                        modifier = Modifier.weight(1f),
                                    )
                                    AnVeMetricColumn(
                                        label = queueCountLabel,
                                        value = queueCountValue,
                                        valueColor = queueCountColor,
                                        modifier = Modifier.weight(1f),
                                    )
                                    AnVeMetricColumn(
                                        label = uploadAgeLabel,
                                        value = uploadAgeValue,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
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
            if (actionError != null) item { AnveErrorNotice(actionError) }
            if (state.readFailed) item { AnveErrorNotice(stringResource(R.string.diag_details_failed), onRetry = onRefresh) }
            if (!showDetails) {
                item { DiagnosticsSection(stringResource(R.string.diag_section_capture)) {
                    DiagnosticRow(stringResource(R.string.diag_row_service), if (status.activelyTracking) stringResource(R.string.diag_value_running) else if (status.serviceRunning) stringResource(R.string.diag_value_capture_unconfirmed) else stringResource(R.string.diag_value_stopped))
                    DiagnosticRow(stringResource(R.string.diag_row_last_fix), ageLabel(status.lastFixAtMillis, nowMillis))
                } }
                item { DiagnosticsSection(stringResource(R.string.diag_section_sync)) {
                    DiagnosticRow(stringResource(R.string.diag_row_queued), details?.queue?.queuedPoints?.let { stringResource(R.string.diag_points_count, it) } ?: unknownLabel(state.loading))
                    DiagnosticRow(stringResource(R.string.diag_row_last_upload), ageLabel(status.lastUploadAtMillis, nowMillis))
                } }
                item { DiagnosticsSection(stringResource(R.string.diag_section_permissions)) {
                    DiagnosticRow(stringResource(R.string.diag_row_location_permission), when (status.permission) {
                        LocationPermissionState.GRANTED_PRECISE -> stringResource(R.string.diag_value_precise)
                        LocationPermissionState.GRANTED_APPROXIMATE -> stringResource(R.string.diag_value_approximate)
                        LocationPermissionState.NOT_REQUESTED -> stringResource(R.string.diag_value_not_requested)
                        LocationPermissionState.DENIED -> stringResource(R.string.diag_value_not_granted)
                    })
                    DiagnosticRow(stringResource(R.string.diag_row_background_permission), yesNo(status.backgroundPermissionGranted, stringResource(R.string.diag_value_granted), stringResource(R.string.diag_value_not_granted)))
                    DiagnosticRow(stringResource(R.string.diag_row_location_services), yesNo(status.locationServicesEnabled, stringResource(R.string.diag_value_enabled), stringResource(R.string.diag_value_disabled)))
                    DiagnosticRow(stringResource(R.string.diag_row_battery_saver), yesNo(status.batterySaver, stringResource(R.string.diag_value_on), stringResource(R.string.diag_value_off)))
                    if (status.batterySaver || !status.backgroundPermissionGranted) Text(stringResource(R.string.diag_battery_limit_notice), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                } }
            } else {
                item { DiagnosticsSection(stringResource(R.string.diag_section_queue)) {
                    DiagnosticRow(stringResource(R.string.diag_row_queued), details?.queue?.queuedPoints?.toString() ?: unknownLabel(state.loading))
                    DiagnosticRow(stringResource(R.string.diag_row_oldest), ageLabel(details?.queue?.oldestCreatedAtMillis, nowMillis))
                    DiagnosticRow(stringResource(R.string.diag_row_attempts), details?.queue?.attempts?.toString() ?: unknownLabel(state.loading))
                    DiagnosticRow(stringResource(R.string.diag_row_retried), details?.queue?.retriedPoints?.toString() ?: unknownLabel(state.loading))
                    DiagnosticRow(stringResource(R.string.diag_row_checkpoint), details?.captureCheckpoint?.let { yesNo(it, stringResource(R.string.diag_value_saved), stringResource(R.string.diag_value_absent)) } ?: unknownLabel(state.loading))
                    DiagnosticRow(stringResource(R.string.diag_row_recovery), yesNo(status.recoveryRequired, stringResource(R.string.diag_value_yes), stringResource(R.string.diag_value_no)))
                    DiagnosticRow(stringResource(R.string.diag_row_sync_work), details?.syncWork?.let { work -> if (work.isEmpty()) stringResource(R.string.diag_value_no_pending_work) else work.map { workLabel(it) }.joinToString() } ?: unknownLabel(state.loading))
                    if (status.privacyDiscardedPoints) Text(stringResource(R.string.diag_privacy_discarded), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    OutlinedButton(
                        onClick = { onAction(DiagnosticsAction.RETRY_SYNC) },
                        enabled = !state.loading && status.online && status.consentEnabled && (details?.queue?.queuedPoints ?: 0) > 0,
                        shape = CircleShape,
                        modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = actionColor),
                    ) { Text(stringResource(R.string.diag_retry_sync)) }
                } }
                item { DiagnosticsSection(stringResource(R.string.diag_section_notifications)) {
                    DiagnosticRow(stringResource(R.string.diag_row_notification_permission), yesNo(status.notificationsGranted, stringResource(R.string.diag_value_granted), stringResource(R.string.diag_value_not_granted)))
                    DiagnosticRow(stringResource(R.string.diag_row_push_registered), details?.pushRegistered?.let { yesNo(it, stringResource(R.string.diag_value_registered), stringResource(R.string.diag_value_not_registered)) } ?: unknownLabel(state.loading || state.checkingPush))
                    DiagnosticRow(stringResource(R.string.diag_row_push_configured), details?.pushConfigured?.let { yesNo(it, stringResource(R.string.diag_value_configured), stringResource(R.string.diag_value_not_configured)) } ?: unknownLabel(state.loading || state.checkingPush))
                    DiagnosticRow(stringResource(R.string.diag_row_sos_pending), details?.sosPending?.toString() ?: unknownLabel(state.loading))
                    Text(stringResource(R.string.diag_push_notice), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                } }
                item { DiagnosticsSection(stringResource(R.string.diag_section_device)) {
                    DiagnosticRow(stringResource(R.string.diag_row_battery_optimization), details?.batteryOptimizationExempt?.let { yesNo(it, stringResource(R.string.diag_value_yes), stringResource(R.string.diag_value_no)) } ?: unknownLabel(state.loading))
                    DiagnosticRow(stringResource(R.string.diag_row_version), BuildConfig.VERSION_NAME)
                    DiagnosticRow(stringResource(R.string.diag_row_api), Build.VERSION.SDK_INT.toString())
                    Text(stringResource(R.string.diag_battery_not_measured), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                } }
            }
            item {
                OutlinedButton(
                    onClick = onExport,
                    enabled = !state.loading,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, stitch.hairline),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = actionColor),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Description,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                        Text(stringResource(R.string.diag_export_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.diag_export_subtitle), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun DiagnosticsNotice(title: String, actionLabel: String, networkIssue: Boolean, background: Color, foreground: Color, onAction: () -> Unit) {
    val stitch = LocalStitchColors.current
    OutlinedCard(
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, stitch.hairline),
        colors = CardDefaults.outlinedCardColors(containerColor = background, contentColor = foreground),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
            val icon = if (networkIssue) Icons.Outlined.WifiOff else Icons.Outlined.Info
            val action: @Composable (Modifier) -> Unit = { modifier ->
                OutlinedButton(
                    onClick = onAction,
                    modifier = modifier.heightIn(min = 48.dp),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, foreground),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground),
                ) {
                    Icon(Icons.Outlined.Settings, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(actionLabel)
                }
            }
            if (maxWidth < 340.dp || LocalDensity.current.fontScale > 1.15f) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(title, style = MaterialTheme.typography.titleMedium)
                    }
                    action(Modifier.fillMaxWidth())
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    action(Modifier)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsSection(title: String, content: @Composable () -> Unit) {
    val stitch = LocalStitchColors.current
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // TalkBack's heading navigation is the fastest way through
        // this, the app's longest screen: marking each section title
        // lets users jump by section instead of swiping every row.
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp).semantics { heading() },
        )
        HorizontalDivider(color = stitch.hairline)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            content()
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    val colors = MaterialTheme.colorScheme
    val largeFont = LocalDensity.current.fontScale > 1.25f
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
    ) {
        if (maxWidth < 300.dp || largeFont) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Text(
                    value,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.End,
                    color = colors.onSurface,
                )
            }
        }
    }
}

private fun yesNo(value: Boolean, yes: String, no: String) = if (value) yes else no

@Composable
private fun unknownLabel(loading: Boolean) = if (loading) stringResource(R.string.diag_reading) else stringResource(R.string.diag_unknown)

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

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DiagnosticsDarkPreview() {
    AnVeTheme(darkTheme = true) {
        DiagnosticsContent(DiagnosticsUiState(owner = "preview", status = TrackingStatus(ownerUserId = "preview", sessionReady = true)), 1_000_000, {}, {}, {}, {})
    }
}
