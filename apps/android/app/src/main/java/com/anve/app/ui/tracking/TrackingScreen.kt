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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import com.anve.app.ui.theme.AnVeTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private enum class PendingAction { NONE, START, ENABLE_THEN_START, CONSENT_ONLY }

/**
 * Tracking control and status. It deliberately separates "I agreed to
 * share" from "the phone is actually updating" in two cards, because the
 * product rule is that consent is not evidence of collection.
 */
@Composable
fun TrackingScreen(container: AppContainer) {
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
        onRefresh = viewModel::refresh,
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
    )
}

@Composable
fun TrackingContent(
    state: TrackingUiState,
    onStartFlow: () -> Unit,
    onStop: () -> Unit,
    onToggleConsent: (Boolean) -> Unit,
    onRetrySync: () -> Unit,
    onRefresh: () -> Unit,
    onRequestBackground: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onDismissMessage: () -> Unit,
    permissionPermanentlyDenied: Boolean = false,
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

            state.message?.let { message ->
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
                ConsentCard(status = status, onToggle = onToggleConsent, enabled = !state.busy)
            }
            item {
                TrackingStateCard(
                    status = status,
                    onStart = onStartFlow,
                    onStop = onStop,
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
            item { StatusCard(status = status, onRetrySync = onRetrySync, onRefresh = onRefresh) }
            item { DiagnosticsCard(status = status) }
        }
    }
}

@Composable
private fun ConsentCard(
    status: TrackingStatus,
    onToggle: (Boolean) -> Unit,
    enabled: Boolean,
) {
    val label = when {
        status.consentSyncPending -> stringResource(R.string.tracking_consent_pending)
        status.consentEnabled -> stringResource(R.string.tracking_consent_on)
        else -> stringResource(R.string.tracking_consent_off)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.tracking_consent_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.tracking_consent_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = status.consentEnabled,
                    onCheckedChange = onToggle,
                    enabled = enabled,
                )
            }
            Spacer(Modifier.height(8.dp))
            AssistChip(
                onClick = {},
                label = { Text(label) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                    )
                },
            )
        }
    }
}

@Composable
private fun TrackingStateCard(
    status: TrackingStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
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
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.tracking_state_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(stateLabel, style = MaterialTheme.typography.titleLarge)
            Text(
                operationalLabel(status.operational),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            if (status.activelyTracking) {
                OutlinedButton(
                    onClick = onStop,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.tracking_stop))
                }
            } else {
                Button(
                    onClick = onStart,
                    enabled = enabled && status.sessionReady,
                    modifier = Modifier.fillMaxWidth(),
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.tracking_permissions_title),
                style = MaterialTheme.typography.titleMedium,
            )
            InfoRow(
                label = stringResource(R.string.tracking_permission_location),
                value = permissionLabel(status.permission),
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
            if (status.permission == LocationPermissionState.DENIED) {
                if (permissionPermanentlyDenied) {
                    Text(
                        stringResource(R.string.tracking_permission_permanently_denied),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (permissionPermanentlyDenied) {
                    TextButton(onClick = onOpenAppSettings) {
                        Text(stringResource(R.string.tracking_permission_settings))
                    }
                } else {
                    TextButton(onClick = onStartFlow) {
                        Text(stringResource(R.string.tracking_permission_grant))
                    }
                }
            }
            HorizontalDivider()
            InfoRow(
                label = stringResource(R.string.tracking_permission_background),
                value = if (status.backgroundPermissionGranted) {
                    stringResource(R.string.tracking_permission_background_granted)
                } else {
                    stringResource(R.string.tracking_permission_background_missing)
                },
            )
            if (!status.backgroundPermissionGranted) {
                Text(
                    stringResource(R.string.tracking_permission_background_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRequestBackground) {
                    Text(stringResource(R.string.tracking_permission_background_grant))
                }
            }
            HorizontalDivider()
            InfoRow(
                label = stringResource(R.string.tracking_permission_notifications),
                value = if (status.notificationsGranted) {
                    stringResource(R.string.tracking_permission_notifications_granted)
                } else {
                    stringResource(R.string.tracking_permission_notifications_missing)
                },
            )
            if (!status.notificationsGranted) {
                TextButton(onClick = onRequestNotifications) {
                    Text(stringResource(R.string.tracking_permission_notifications_grant))
                }
            }
            TextButton(onClick = onOpenAppSettings) {
                Text(stringResource(R.string.tracking_permission_settings))
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
    onRefresh: () -> Unit,
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
                label = stringResource(R.string.tracking_last_fix_accuracy),
                value = status.lastAccuracyMeters?.let {
                    stringResource(R.string.tracking_accuracy_value, it.roundToInt())
                } ?: stringResource(R.string.tracking_never),
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
            InfoRow(
                label = stringResource(R.string.tracking_connection),
                value = if (status.online) {
                    stringResource(R.string.tracking_connection_online)
                } else {
                    stringResource(R.string.tracking_connection_offline)
                },
            )
            InfoRow(
                label = stringResource(R.string.tracking_battery_saver),
                value = if (status.batterySaver) {
                    stringResource(R.string.tracking_battery_on)
                } else {
                    stringResource(R.string.tracking_battery_off)
                },
            )
            Row {
                TextButton(onClick = onRetrySync, enabled = status.pendingQueueCount > 0) {
                    Text(stringResource(R.string.tracking_retry_sync))
                }
                TextButton(onClick = onRefresh) {
                    Text(stringResource(R.string.tracking_refresh))
                }
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
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
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
    TrackingProblem.CIRCLE_REQUIRED -> stringResource(R.string.tracking_problem_circle)
    TrackingProblem.SESSION_UNAVAILABLE -> stringResource(R.string.tracking_problem_session)
    TrackingProblem.SERVICE_START_FAILED -> stringResource(R.string.tracking_problem_service)
    TrackingProblem.PROVIDER_ERROR -> stringResource(R.string.tracking_problem_provider)
    TrackingProblem.QUEUE_UNAVAILABLE -> stringResource(R.string.tracking_problem_queue)
    TrackingProblem.RECOVERY_REQUIRED -> stringResource(R.string.tracking_problem_recovery)
    TrackingProblem.CLOCK_SKEW -> stringResource(R.string.tracking_problem_clock)
}

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(ZoneId.systemDefault())

fun formatTime(millis: Long?, fallback: String): String =
    millis?.let { timeFormatter.format(Instant.ofEpochMilli(it)) } ?: fallback

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
            onRefresh = {},
            onRequestBackground = {},
            onRequestNotifications = {},
            onOpenAppSettings = {},
            onOpenLocationSettings = {},
            onDismissMessage = {},
        )
    }
}
