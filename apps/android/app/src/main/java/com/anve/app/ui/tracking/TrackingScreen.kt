package com.anve.app.ui.tracking

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.ContextWrapper
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anve.app.AppContainer
import com.anve.app.R
import com.anve.app.tracking.LocationPermissionState
import com.anve.app.tracking.BackgroundPermissionAction
import com.anve.app.tracking.backgroundPermissionAction
import com.anve.app.tracking.MotionState
import com.anve.app.tracking.OperationalState
import com.anve.app.tracking.TrackingProblem
import com.anve.app.tracking.TrackingStatus
import com.anve.app.ui.common.AnveTime
import com.anve.app.ui.theme.AnVeTheme

private enum class PendingAction { NONE, START, ENABLE_THEN_START, CONSENT_ONLY }

/**
 * Tracking control and status. Consent and actual collection still read as
 * two different things, now inside one card: the hero Start/Stop button and
 * the activity label under it describe what the phone is doing, while the
 * consent switch row describes the server-side agreement. The product rule
 * that consent is not evidence of collection stays visible in copy, not in
 * a second competing card.
 */
@Composable
fun TrackingScreen(container: AppContainer, onOpenDiagnostics: () -> Unit = {}, onOpenJourney: () -> Unit = {}) {
    val viewModel: TrackingViewModel = viewModel(
        factory = TrackingViewModel.factory(container.trackingManager),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var disclosureAccepted by rememberSaveable { mutableStateOf(false) }
    var showDisclosure by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(PendingAction.NONE) }
    var showBackgroundEducation by rememberSaveable { mutableStateOf(false) }
    var backgroundActionAfterEducation by rememberSaveable {
        mutableStateOf(BackgroundPermissionAction.NOT_REQUIRED)
    }
    var foregroundPermissionRequested by rememberSaveable { mutableStateOf(false) }
    var foregroundPermissionPermanentlyDenied by rememberSaveable { mutableStateOf(false) }
    val activity = remember(context) { context.findActivity() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            foregroundPermissionPermanentlyDenied = false
        } else if (foregroundPermissionRequested && activity != null) {
            val rationale = ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) || ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            foregroundPermissionPermanentlyDenied = !rationale
        }
        foregroundPermissionRequested = false
        viewModel.refresh()
        val action = pending
        pending = PendingAction.NONE
        if (granted && (action == PendingAction.START || action == PendingAction.ENABLE_THEN_START)) {
            viewModel.start()
        }
    }
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }

    LaunchedEffect(state.status.permission) {
        if (state.status.permission != LocationPermissionState.DENIED) {
            foregroundPermissionPermanentlyDenied = false
        }
    }

    // Continuing a first-time enable after the server confirmed consent.
    LaunchedEffect(state.status.consentEnabled, pending) {
        if (pending == PendingAction.ENABLE_THEN_START && state.status.consentEnabled) {
            val canTrack = state.status.permission == LocationPermissionState.GRANTED_PRECISE ||
                state.status.permission == LocationPermissionState.GRANTED_APPROXIMATE
            if (canTrack) {
                pending = PendingAction.NONE
                viewModel.start()
            } else {
                pending = PendingAction.START
                foregroundPermissionRequested = true
                locationLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            }
        }
    }

    fun requestLocationThenStart() {
        pending = PendingAction.START
        foregroundPermissionRequested = true
        locationLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    fun beginStart() {
        val canTrack = state.status.permission == LocationPermissionState.GRANTED_PRECISE ||
            state.status.permission == LocationPermissionState.GRANTED_APPROXIMATE
        if (disclosureAccepted) {
            when {
                !state.status.consentEnabled -> {
                    pending = PendingAction.ENABLE_THEN_START
                    viewModel.setConsent(true)
                }
                !canTrack -> requestLocationThenStart()
                else -> viewModel.start()
            }
            return
        }
        pending = if (!state.status.consentEnabled) {
            PendingAction.ENABLE_THEN_START
        } else {
            PendingAction.START
        }
        showDisclosure = true
    }

    fun toggleConsent(enabled: Boolean) {
        if (!enabled) {
            viewModel.setConsent(false)
            return
        }
        if (disclosureAccepted) {
            viewModel.setConsent(true)
            return
        }
        pending = PendingAction.CONSENT_ONLY
        showDisclosure = true
    }

    if (showDisclosure) {
        AlertDialog(
            onDismissRequest = {
                showDisclosure = false
                pending = PendingAction.NONE
            },
            title = { Text(stringResource(R.string.tracking_disclosure_title)) },
            text = { Text(stringResource(R.string.tracking_disclosure_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        disclosureAccepted = true
                        showDisclosure = false
                        when (pending) {
                            PendingAction.CONSENT_ONLY -> viewModel.setConsent(true)
                            PendingAction.ENABLE_THEN_START -> viewModel.setConsent(true)
                            PendingAction.START -> {
                                val canTrack =
                                    state.status.permission == LocationPermissionState.GRANTED_PRECISE ||
                                        state.status.permission == LocationPermissionState.GRANTED_APPROXIMATE
                                if (canTrack) viewModel.start() else requestLocationThenStart()
                            }

                            PendingAction.NONE -> Unit
                        }
                    },
                ) { Text(stringResource(R.string.tracking_disclosure_accept)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDisclosure = false
                        pending = PendingAction.NONE
                    },
                ) { Text(stringResource(R.string.tracking_disclosure_later)) }
            },
        )
    }

    if (showBackgroundEducation) {
        val fallbackPermissionOption = stringResource(R.string.tracking_background_option_fallback)
        val systemPermissionOption = remember(context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.backgroundPermissionOptionLabel.toString()
            } else {
                null
            }
        }
        val systemOption = systemPermissionOption ?: fallbackPermissionOption
        AlertDialog(
            onDismissRequest = { showBackgroundEducation = false },
            title = { Text(stringResource(R.string.tracking_background_settings_title)) },
            text = { Text(stringResource(R.string.tracking_background_settings_body, systemOption)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBackgroundEducation = false
                        when (backgroundActionAfterEducation) {
                            BackgroundPermissionAction.REQUEST_RUNTIME_BACKGROUND ->
                                backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            BackgroundPermissionAction.OPEN_APP_SETTINGS -> context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                ),
                            )
                            else -> viewModel.refresh()
                        }
                    },
                ) { Text(stringResource(R.string.tracking_background_settings_open)) }
            },
            dismissButton = {
                TextButton(onClick = { showBackgroundEducation = false }) {
                    Text(stringResource(R.string.tracking_disclosure_later))
                }
            },
        )
    }

    TrackingContent(
        state = state,
        onStartFlow = ::beginStart,
        onStop = viewModel::stop,
        onToggleConsent = ::toggleConsent,
        onRetrySync = viewModel::retrySync,
        onRequestBackground = {
            val foregroundGranted = state.status.permission == LocationPermissionState.GRANTED_PRECISE ||
                state.status.permission == LocationPermissionState.GRANTED_APPROXIMATE
            when (
                backgroundPermissionAction(
                    Build.VERSION.SDK_INT,
                    foregroundGranted,
                    state.status.backgroundPermissionGranted,
                )
            ) {
                BackgroundPermissionAction.NOT_REQUIRED -> viewModel.refresh()
                BackgroundPermissionAction.REQUEST_FOREGROUND -> {
                    pending = PendingAction.NONE
                    foregroundPermissionRequested = true
                    locationLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                }
                BackgroundPermissionAction.REQUEST_RUNTIME_BACKGROUND,
                BackgroundPermissionAction.OPEN_APP_SETTINGS -> {
                    backgroundActionAfterEducation = backgroundPermissionAction(
                        Build.VERSION.SDK_INT,
                        foregroundGranted,
                        state.status.backgroundPermissionGranted,
                    )
                    showBackgroundEducation = true
                }
            }
        },
        onRequestNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onOpenAppSettings = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
        },
        onOpenLocationSettings = {
            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        },
        onDismissMessage = viewModel::consumeMessage,
        permissionPermanentlyDenied = foregroundPermissionPermanentlyDenied,
        onOpenDiagnostics = onOpenDiagnostics,
        onOpenJourney = onOpenJourney,
    )
}

@Composable
fun TrackingContent(
    state: TrackingUiState,
    onStartFlow: () -> Unit,
    onStop: () -> Unit,
    onToggleConsent: (Boolean) -> Unit,
    onRetrySync: () -> Unit,
    onRequestBackground: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onDismissMessage: () -> Unit,
    permissionPermanentlyDenied: Boolean = false,
    onOpenDiagnostics: () -> Unit = {},
    onOpenJourney: () -> Unit = {},
) {
    val status = state.status
    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column {
                    Text(stringResource(R.string.tracking_title), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.tracking_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val messageRes = state.messageRes
            if (messageRes != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(messageRes), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = onDismissMessage) {
                                Text(stringResource(R.string.tracking_message_dismiss))
                            }
                        }
                    }
                }
            }
            state.consentMessage?.let { message ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(message, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = onDismissMessage) {
                                Text(stringResource(R.string.tracking_message_dismiss))
                            }
                        }
                    }
                }
            }

            item {
                TrackingControlCard(
                    status = status,
                    onStart = onStartFlow,
                    onStop = onStop,
                    onToggleConsent = onToggleConsent,
                    enabled = !state.busy,
                )
            }
            if (status.lastError != null) {
                item { ProblemCard(status = status, onOpenSettings = onOpenLocationSettings) }
            }
            item {
                PermissionsCard(
                    status = status,
                    permissionPermanentlyDenied = permissionPermanentlyDenied,
                    onStartFlow = onStartFlow,
                    onRequestBackground = onRequestBackground,
                    onRequestNotifications = onRequestNotifications,
                    onOpenAppSettings = onOpenAppSettings,
                    onOpenLocationSettings = onOpenLocationSettings,
                )
            }
            item { StatusCard(status = status, onRetrySync = onRetrySync) }
            item { DiagnosticsCard(status = status) }
            // Navigation to related surfaces sits below the status cards so the
            // consent/Start control stays the single primary focus on open.
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenJourney, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.journey_title))
                    }
                    OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.diag_title))
                    }
                }
            }
        }
    }
}

/**
 * The single primary control of the screen: Start/Stop capture, the current
 * activity label right under it, and the server-side consent as a small
 * secondary switch row. The two concepts stay separable by copy and by
 * control, not by card position.
 */
@Composable
private fun TrackingControlCard(
    status: TrackingStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onToggleConsent: (Boolean) -> Unit,
    enabled: Boolean,
) {
    val stateLabel = when {
        status.activelyTracking && status.lastFixAtMillis == null ->
            stringResource(R.string.tracking_state_waiting)

        status.activelyTracking -> stringResource(R.string.tracking_state_active)
        status.recoveryRequired && status.trackingRequested ->
            stringResource(R.string.tracking_state_recovery)

        status.trackingRequested -> stringResource(R.string.tracking_state_requested)
        else -> stringResource(R.string.tracking_state_stopped)
    }
    val consentValue = when {
        status.consentConflict -> stringResource(R.string.tracking_consent_conflict)
        status.consentSyncPending -> stringResource(R.string.tracking_consent_pending)
        status.consentEnabled -> stringResource(R.string.tracking_consent_on)
        else -> stringResource(R.string.tracking_consent_off)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (status.activelyTracking) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status.activelyTracking) {
                OutlinedButton(
                    onClick = onStop,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                ) {
                    Text(stringResource(R.string.tracking_stop))
                }
            } else {
                Button(
                    onClick = onStart,
                    enabled = enabled && status.sessionReady,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                ) {
                    Text(
                        when {
                            status.recoveryRequired && status.trackingRequested ->
                                stringResource(R.string.tracking_resume)

                            !status.consentEnabled ->
                                stringResource(R.string.tracking_enable_consent)

                            else -> stringResource(R.string.tracking_start)
                        },
                    )
                }
            }
            Text(stateLabel, style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.tracking_consent_server_value, consentValue),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.tracking_consent_server_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = status.conflictServerConsent ?: status.consentEnabled,
                    onCheckedChange = onToggleConsent,
                    enabled = enabled,
                )
            }
            if (status.consentConflict) {
                Text(
                    stringResource(R.string.tracking_problem_consent_conflict),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ProblemCard(status: TrackingStatus, onOpenSettings: () -> Unit) {
    val text = problemText(status.lastError) ?: return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
            if (status.lastError == TrackingProblem.LOCATION_DISABLED) {
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.tracking_location_services_settings))
                }
            }
        }
    }
}

/**
 * Only what is still missing surfaces here, each with its own action; when
 * every permission and the location services switch are in place the card
 * collapses to a single settled row so nothing competes with the hero
 * control. The disclosure and education dialogs stay in the shared flows.
 */
@Composable
private fun PermissionsCard(
    status: TrackingStatus,
    permissionPermanentlyDenied: Boolean,
    onStartFlow: () -> Unit,
    onRequestBackground: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    val locationGranted = status.permission == LocationPermissionState.GRANTED_PRECISE ||
        status.permission == LocationPermissionState.GRANTED_APPROXIMATE
    if (locationGranted &&
        status.backgroundPermissionGranted &&
        status.notificationsGranted &&
        status.locationServicesEnabled
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            InfoRow(
                label = stringResource(R.string.tracking_permissions_title),
                value = stringResource(R.string.tracking_permissions_ok),
                modifier = Modifier.padding(16.dp),
            )
        }
        return
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.tracking_permissions_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (!status.locationServicesEnabled) {
                Text(
                    stringResource(R.string.tracking_location_services_off),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onOpenLocationSettings) {
                    Text(stringResource(R.string.tracking_location_services_settings))
                }
            }
            if (!locationGranted) {
                InfoRow(
                    label = stringResource(R.string.tracking_permission_location),
                    value = permissionLabel(status.permission),
                )
                if (permissionPermanentlyDenied) {
                    Text(
                        stringResource(R.string.tracking_permission_permanently_denied),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onOpenAppSettings) {
                        Text(stringResource(R.string.tracking_permission_settings))
                    }
                } else {
                    TextButton(onClick = onStartFlow) {
                        Text(stringResource(R.string.tracking_permission_grant))
                    }
                }
            }
            if (!status.backgroundPermissionGranted) {
                InfoRow(
                    label = stringResource(R.string.tracking_permission_background),
                    value = stringResource(R.string.tracking_permission_background_missing),
                )
                Text(
                    stringResource(R.string.tracking_permission_background_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRequestBackground) {
                    Text(stringResource(R.string.tracking_permission_background_grant))
                }
            }
            if (!status.notificationsGranted) {
                InfoRow(
                    label = stringResource(R.string.tracking_permission_notifications),
                    value = stringResource(R.string.tracking_permission_notifications_missing),
                )
                TextButton(onClick = onRequestNotifications) {
                    Text(stringResource(R.string.tracking_permission_notifications_grant))
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun StatusCard(
    status: TrackingStatus,
    onRetrySync: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.tracking_status_title),
                style = MaterialTheme.typography.titleMedium,
            )
            InfoRow(
                label = stringResource(R.string.tracking_last_fix),
                value = formatTime(
                    status.lastFixAtMillis,
                    stringResource(R.string.tracking_never),
                ),
            )
            InfoRow(
                label = stringResource(R.string.tracking_last_upload),
                value = formatTime(
                    status.lastUploadAtMillis,
                    stringResource(R.string.tracking_never),
                ),
            )
            InfoRow(
                label = stringResource(R.string.tracking_pending_count),
                value = pluralStringResource(
                    R.plurals.tracking_pending_value,
                    status.pendingQueueCount,
                    status.pendingQueueCount,
                ),
            )
            if (status.privacyDiscardedPoints) {
                Text(stringResource(R.string.tracking_privacy_discarded_notice))
            }
            InfoRow(
                label = stringResource(R.string.tracking_connection),
                value = if (status.online) {
                    stringResource(R.string.tracking_connection_online)
                } else {
                    stringResource(R.string.tracking_connection_offline)
                },
            )
            TextButton(onClick = onRetrySync, enabled = status.pendingQueueCount > 0) {
                Text(stringResource(R.string.tracking_retry_sync))
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(status: TrackingStatus) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                )
                Text(stringResource(R.string.tracking_diagnostics))
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoRow(
                        stringResource(R.string.tracking_diagnostics_motion),
                        motionLabel(status.motion),
                    )
                    InfoRow(
                        stringResource(R.string.tracking_diagnostics_operational),
                        operationalLabel(status.operational),
                    )
                    InfoRow(
                        stringResource(R.string.tracking_diagnostics_service),
                        stringResource(
                            if (status.serviceRunning) {
                                R.string.tracking_diagnostics_yes
                            } else {
                                R.string.tracking_diagnostics_no
                            },
                        ),
                    )
                    InfoRow(
                        stringResource(R.string.tracking_diagnostics_provider),
                        stringResource(
                            if (status.providerActive) {
                                R.string.tracking_diagnostics_yes
                            } else {
                                R.string.tracking_diagnostics_no
                            },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun permissionLabel(state: LocationPermissionState): String = when (state) {
    LocationPermissionState.GRANTED_PRECISE -> stringResource(R.string.tracking_permission_precise)
    LocationPermissionState.GRANTED_APPROXIMATE -> stringResource(R.string.tracking_permission_approximate)
    LocationPermissionState.DENIED -> stringResource(R.string.tracking_permission_denied)
    LocationPermissionState.NOT_REQUESTED -> stringResource(R.string.tracking_permission_denied)
}

@Composable
private fun motionLabel(state: MotionState): String = when (state) {
    MotionState.IDLE -> stringResource(R.string.tracking_motion_idle)
    MotionState.STATIONARY -> stringResource(R.string.tracking_motion_stationary)
    MotionState.MOVING -> stringResource(R.string.tracking_motion_moving)
    MotionState.JOURNEY -> stringResource(R.string.tracking_motion_journey)
}

@Composable
private fun operationalLabel(state: OperationalState): String = when (state) {
    OperationalState.ACTIVE -> stringResource(R.string.tracking_operational_active)
    OperationalState.DEGRADED -> stringResource(R.string.tracking_operational_degraded)
    OperationalState.OFFLINE -> stringResource(R.string.tracking_operational_offline)
    OperationalState.PAUSED -> stringResource(R.string.tracking_operational_paused)
}

@Composable
private fun problemText(problem: TrackingProblem?): String? = when (problem) {
    null -> null
    TrackingProblem.PERMISSION_DENIED -> stringResource(R.string.tracking_problem_permission)
    TrackingProblem.PERMISSION_REVOKED -> stringResource(R.string.tracking_problem_permission)
    TrackingProblem.LOCATION_DISABLED -> stringResource(R.string.tracking_problem_location_disabled)
    TrackingProblem.PROVIDER_UNAVAILABLE -> stringResource(R.string.tracking_problem_provider_unavailable)
    TrackingProblem.CONSENT_REQUIRED -> stringResource(R.string.tracking_problem_consent)
    TrackingProblem.CONSENT_CONFLICT -> stringResource(R.string.tracking_problem_consent_conflict)
    TrackingProblem.CONSENT_STORAGE_UNAVAILABLE -> stringResource(R.string.tracking_problem_consent_storage)
    TrackingProblem.CONSENT_QUEUE_PURGE_FAILED -> stringResource(R.string.tracking_problem_consent_queue)
    TrackingProblem.CIRCLE_REQUIRED -> stringResource(R.string.tracking_problem_circle)
    TrackingProblem.SESSION_UNAVAILABLE -> stringResource(R.string.tracking_problem_session)
    TrackingProblem.CHECKPOINT_UNAVAILABLE -> stringResource(R.string.tracking_problem_checkpoint)
    TrackingProblem.SERVICE_START_FAILED -> stringResource(R.string.tracking_problem_service)
    TrackingProblem.SERVICE_STOP_FAILED -> stringResource(R.string.tracking_problem_service_stop)
    TrackingProblem.PROVIDER_ERROR -> stringResource(R.string.tracking_problem_provider)
    TrackingProblem.QUEUE_UNAVAILABLE -> stringResource(R.string.tracking_problem_queue)
    TrackingProblem.RECOVERY_REQUIRED -> stringResource(R.string.tracking_problem_recovery)
    TrackingProblem.CLOCK_SKEW -> stringResource(R.string.tracking_problem_clock)
}

fun formatTime(millis: Long?, fallback: String): String = AnveTime.full(millis, fallback)

@Preview(showBackground = true)
@Composable
private fun TrackingContentPreview() {
    AnVeTheme {
        TrackingContent(
            state = TrackingUiState(
                status = TrackingStatus(
                    consentEnabled = true,
                    consentConfirmedByServer = true,
                    trackingRequested = true,
                    serviceRunning = true,
                    providerActive = true,
                    running = true,
                    motion = MotionState.STATIONARY,
                    operational = OperationalState.ACTIVE,
                    permission = LocationPermissionState.GRANTED_PRECISE,
                    backgroundPermissionGranted = true,
                    notificationsGranted = true,
                    lastFixAtMillis = System.currentTimeMillis(),
                    lastAccuracyMeters = 12.0,
                    lastUploadAtMillis = System.currentTimeMillis(),
                    pendingQueueCount = 3,
                ),
            ),
            onStartFlow = {},
            onStop = {},
            onToggleConsent = {},
            onRetrySync = {},
            onRequestBackground = {},
            onRequestNotifications = {},
            onOpenAppSettings = {},
            onOpenLocationSettings = {},
            onDismissMessage = {},
        )
    }
}
