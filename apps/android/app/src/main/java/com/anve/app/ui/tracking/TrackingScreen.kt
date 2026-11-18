package com.anve.app.ui.tracking

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anve.app.AppContainer
import com.anve.app.R
import com.anve.app.data.sos.areAppNotificationsEnabled
import com.anve.app.tracking.BackgroundPermissionAction
import com.anve.app.tracking.LocationPermissionState
import com.anve.app.tracking.MotionState
import com.anve.app.tracking.OperationalState
import com.anve.app.tracking.TrackingProblem
import com.anve.app.tracking.TrackingStatus
import com.anve.app.tracking.backgroundPermissionAction
import com.anve.app.ui.brand.AnVeJourneySceneIllustration
import com.anve.app.ui.brand.AnVeSharingHeroIllustration
import com.anve.app.ui.common.AnVeMetricColumn
import com.anve.app.ui.common.AnVeSectionHeader
import com.anve.app.ui.common.AnveTime
import com.anve.app.ui.common.StitchBadgeKind
import com.anve.app.ui.common.StitchLiveSharingPill
import com.anve.app.ui.common.StitchSemanticBadge
import com.anve.app.ui.common.anveContentWidth
import com.anve.app.ui.home.HOME_FAB_CLEARANCE
import com.anve.app.ui.theme.AnVeTheme
import com.anve.app.ui.theme.LocalStitchColors

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
fun TrackingScreen(
    container: AppContainer,
    onOpenDiagnostics: () -> Unit = {},
    onOpenJourney: () -> Unit = {},
    onOpenAudience: () -> Unit = {},
) {
    val viewModel: TrackingViewModel = viewModel(
        factory = TrackingViewModel.factory(container.trackingManager),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session by container.sessionStore.session.collectAsStateWithLifecycle(initialValue = null)
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
    ) {
        viewModel.refresh()
        if (areAppNotificationsEnabled(context)) {
            session?.userId?.let { container.pushRegistrationCoordinator.onPermissionGranted(it) }
        }
    }

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
            text = {
                Text(
                    stringResource(R.string.tracking_disclosure_body),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
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
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.tracking_disclosure_accept)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDisclosure = false
                        pending = PendingAction.NONE
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
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
            text = {
                Text(
                    stringResource(R.string.tracking_background_settings_body, systemOption),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
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
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.tracking_background_settings_open)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showBackgroundEducation = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
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
        onOpenAudience = onOpenAudience,
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
    onOpenAudience: () -> Unit = {},
) {
    val status = state.status
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    Surface(modifier = Modifier.fillMaxSize(), color = colors.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.fillMaxHeight().fillMaxWidth().anveContentWidth(),
            // 72dp bottom clears the shell FAB (56dp container + 16dp gap,
            // HOME_FAB_CLEARANCE): the Scaffold floats it over secondary-page
            // content without reserving room, so the last card would sit
            // under the thumb-zone FAB (same clearance as History/Places).
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = HOME_FAB_CLEARANCE),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // The TRACKING secondary page's top bar already carries the screen
            // title, so the list opens with a compact live-status pill and
            // calm subtitle instead of repeating the page heading.
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StitchLiveSharingPill(
                        text = stringResource(
                            if (status.activelyTracking) {
                                R.string.tracking_state_active_pill
                            } else {
                                R.string.tracking_state_paused_pill
                            },
                        ),
                        active = status.activelyTracking,
                    )
                    Text(
                        stringResource(R.string.tracking_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }

            val messageRes = state.messageRes
            if (messageRes != null) {
                item {
                    OutlinedCard(
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, stitch.hairline),
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = stitch.transitContainer,
                            contentColor = stitch.onTransitContainer,
                        ),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            // Polite live region, same as AnveErrorNotice: the
                            // one-shot result is announced, not shown silently.
                            Text(
                                stringResource(messageRes),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                            TextButton(
                                onClick = onDismissMessage,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(R.string.tracking_message_dismiss))
                            }
                        }
                    }
                }
            }
            state.consentMessage?.let { message ->
                item {
                    OutlinedCard(
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, stitch.hairline),
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = stitch.transitContainer,
                            contentColor = stitch.onTransitContainer,
                        ),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                message,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                            TextButton(
                                onClick = onDismissMessage,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
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
                    onOpenAudience = onOpenAudience,
                    enabled = !state.busy,
                )
            }
            item {
                JourneyFeatureCard(onOpenJourney = onOpenJourney)
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
            // Technical status stays one tap away instead of competing with the
            // one decision a family member came here to make.
            item {
                var showDetails by rememberSaveable { mutableStateOf(false) }
                val detailsState = stringResource(
                    if (showDetails) R.string.action_state_expanded else R.string.action_state_collapsed,
                )
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    HorizontalDivider(color = stitch.hairline)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(
                                value = showDetails,
                                role = Role.Button,
                                onValueChange = { showDetails = it },
                            )
                            .padding(vertical = 8.dp)
                            .semantics { stateDescription = detailsState },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.tracking_details_toggle),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (showDetails) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                        )
                    }
                    AnimatedVisibility(visible = showDetails) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            StatusCard(status = status, onRetrySync = onRetrySync)
                            DiagnosticsCard(status = status)
                            OutlinedButton(
                                onClick = onOpenDiagnostics,
                                shape = CircleShape,
                                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                            ) {
                                Text(stringResource(R.string.diag_title))
                            }
                        }
                    }
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
    onOpenAudience: () -> Unit,
    enabled: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
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
    val consentBadgeKind = when {
        status.consentConflict -> StitchBadgeKind.EMERGENCY
        status.consentSyncPending -> StitchBadgeKind.IN_TRANSIT
        status.consentEnabled -> StitchBadgeKind.SAFE_HOME
        else -> StitchBadgeKind.NEUTRAL
    }
    val heroTitle = stringResource(
        if (status.activelyTracking) R.string.tracking_hero_active_title else R.string.tracking_hero_idle_title,
    )
    val heroBody = stringResource(
        when {
            status.activelyTracking -> R.string.tracking_hero_active_body
            status.consentEnabled -> R.string.tracking_hero_paused_body
            else -> R.string.tracking_hero_idle_body
        },
    )
    OutlinedCard(
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(
            if (status.activelyTracking) 1.5.dp else 1.dp,
            if (status.activelyTracking) stitch.livePulse.copy(alpha = 0.55f) else stitch.hairline,
        ),
        colors = CardDefaults.outlinedCardColors(
            containerColor = stitch.cardTactile,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val largeFont = LocalDensity.current.fontScale > 1.25f
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val stackVertical = maxWidth < 320.dp || largeFont
                val bannerText: @Composable (Modifier) -> Unit = { colMod ->
                    Column(
                        modifier = colMod,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        StitchSemanticBadge(
                            text = stateLabel,
                            kind = when {
                                status.activelyTracking -> StitchBadgeKind.SAFE_HOME
                                status.recoveryRequired -> StitchBadgeKind.DELAYED_OFFLINE
                                else -> StitchBadgeKind.NEUTRAL
                            },
                            showDot = status.activelyTracking,
                        )
                        Text(
                            text = heroTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            text = heroBody,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                if (stackVertical) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AnVeSharingHeroIllustration(
                            active = status.activelyTracking,
                            modifier = Modifier.fillMaxWidth().height(88.dp),
                        )
                        bannerText(Modifier.fillMaxWidth())
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AnVeSharingHeroIllustration(
                            active = status.activelyTracking,
                            modifier = Modifier.width(114.dp).height(88.dp),
                        )
                        bannerText(Modifier.weight(1f))
                    }
                }
            }
            HorizontalDivider(color = stitch.hairline)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AnVeMetricColumn(
                    label = stringResource(R.string.tracking_metric_last_fix),
                    value = formatTime(status.lastFixAtMillis, stringResource(R.string.tracking_never)),
                    modifier = Modifier.weight(1f),
                )
                AnVeMetricColumn(
                    label = stringResource(R.string.tracking_metric_queue),
                    value = pluralStringResource(
                        R.plurals.tracking_pending_value,
                        status.pendingQueueCount,
                        status.pendingQueueCount,
                    ),
                    valueColor = if (status.pendingQueueCount > 0) stitch.onTransitContainer else colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider(color = stitch.hairline)
            // The server-consent switch is the product's most consequential
            // decision, so TalkBack must read 'Đồng ý chia sẻ: …' + the
            // server-vs-device note + state from ONE focus — row-toggleable
            // with the switch itself indicative, not an anonymous "bật".
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.tracking_consent_card_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    StitchSemanticBadge(
                        text = consentValue,
                        kind = consentBadgeKind,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = status.conflictServerConsent ?: status.consentEnabled,
                            enabled = enabled,
                            role = Role.Switch,
                            onValueChange = onToggleConsent,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.tracking_consent_server_value, consentValue),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            stringResource(R.string.tracking_consent_server_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = status.conflictServerConsent ?: status.consentEnabled,
                        onCheckedChange = null,
                        enabled = enabled,
                    )
                }
                if (status.consentConflict) {
                    Text(
                        stringResource(R.string.tracking_problem_consent_conflict),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                    )
                }
            }
            if (status.activelyTracking) {
                Button(
                    onClick = onStop,
                    enabled = enabled,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp),
                ) {
                    Text(stringResource(R.string.tracking_stop))
                }
            } else {
                Button(
                    onClick = onStart,
                    enabled = enabled && status.sessionReady,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp),
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
            OutlinedButton(
                onClick = onOpenAudience,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
            ) {
                Text(
                    text = stringResource(R.string.tracking_step_audience_action),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(8.dp))
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
            }
        }
    }
}

@Composable
private fun JourneyFeatureCard(onOpenJourney: () -> Unit) {
    val stitch = LocalStitchColors.current
    val largeFont = LocalDensity.current.fontScale > 1.25f
    OutlinedCard(
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, stitch.hairline),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = stitch.transitContainer.copy(alpha = 0.65f),
            contentColor = stitch.onTransitContainer,
        ),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
            val stackVertical = maxWidth < 320.dp || largeFont
            val details: @Composable (Modifier) -> Unit = { colMod ->
                Column(
                    modifier = colMod,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.tracking_journey_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(R.string.tracking_journey_card_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = onOpenJourney,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text(stringResource(R.string.tracking_journey_card_cta))
                    }
                }
            }
            if (stackVertical) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AnVeJourneySceneIllustration(
                        modifier = Modifier.fillMaxWidth().height(92.dp),
                    )
                    details(Modifier.fillMaxWidth())
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnVeJourneySceneIllustration(
                        modifier = Modifier.width(108.dp).height(92.dp),
                    )
                    details(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ProblemCard(status: TrackingStatus, onOpenSettings: () -> Unit) {
    val text = problemText(status.lastError) ?: return
    val colors = MaterialTheme.colorScheme
    OutlinedCard(
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, colors.error.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = colors.errorContainer,
            contentColor = colors.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = colors.onErrorContainer,
                    modifier = Modifier.size(20.dp),
                )
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            if (status.lastError == TrackingProblem.LOCATION_DISABLED) {
                TextButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
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
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    val locationGranted = status.permission == LocationPermissionState.GRANTED_PRECISE ||
        status.permission == LocationPermissionState.GRANTED_APPROXIMATE
    if (locationGranted &&
        status.backgroundPermissionGranted &&
        status.notificationsGranted &&
        status.locationServicesEnabled
    ) {
        OutlinedCard(
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, stitch.hairline),
            colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
            modifier = Modifier.fillMaxWidth(),
        ) {
            AnVeSectionHeader(
                title = stringResource(R.string.tracking_permissions_title),
                badgeText = stringResource(R.string.tracking_permissions_ok),
                badgeContainerColor = stitch.liveContainer,
                badgeContentColor = stitch.onLiveContainer,
                modifier = Modifier.padding(16.dp),
            )
        }
        return
    }
    OutlinedCard(
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, stitch.hairline),
        colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AnVeSectionHeader(
                title = stringResource(R.string.tracking_permissions_title),
                badgeText = stringResource(R.string.tracking_permissions_need_attention),
                badgeContainerColor = stitch.transitContainer,
                badgeContentColor = stitch.onTransitContainer,
            )
            if (!status.locationServicesEnabled) {
                HorizontalDivider(color = stitch.hairline)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.tracking_location_services_off),
                        color = colors.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FilledTonalButton(
                        onClick = onOpenLocationSettings,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.tracking_location_services_settings))
                    }
                }
            }
            if (!locationGranted) {
                HorizontalDivider(color = stitch.hairline)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow(
                        label = stringResource(R.string.tracking_permission_location),
                        value = permissionLabel(status.permission),
                    )
                    if (permissionPermanentlyDenied) {
                        Text(
                            stringResource(R.string.tracking_permission_permanently_denied),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.error,
                        )
                        OutlinedButton(
                            onClick = onOpenAppSettings,
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.tracking_permission_settings))
                        }
                    } else {
                        FilledTonalButton(
                            onClick = onStartFlow,
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.tracking_permission_grant))
                        }
                    }
                }
            }
            if (!status.backgroundPermissionGranted) {
                HorizontalDivider(color = stitch.hairline)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow(
                        label = stringResource(R.string.tracking_permission_background),
                        value = stringResource(R.string.tracking_permission_background_missing),
                    )
                    Text(
                        stringResource(R.string.tracking_permission_background_explainer),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = onRequestBackground,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.tracking_permission_background_grant))
                    }
                }
            }
            if (!status.notificationsGranted) {
                HorizontalDivider(color = stitch.hairline)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow(
                        label = stringResource(R.string.tracking_permission_notifications),
                        value = stringResource(R.string.tracking_permission_notifications_missing),
                    )
                    FilledTonalButton(
                        onClick = onRequestNotifications,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.tracking_permission_notifications_grant))
                    }
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
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    OutlinedCard(
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, stitch.hairline),
        colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AnVeSectionHeader(
                title = stringResource(R.string.tracking_status_title),
                badgeText = stringResource(
                    if (status.activelyTracking) {
                        R.string.tracking_state_active_pill
                    } else {
                        R.string.tracking_state_paused_pill
                    },
                ),
                badgeContainerColor = if (status.activelyTracking) stitch.liveContainer else colors.surfaceContainerHigh,
                badgeContentColor = if (status.activelyTracking) stitch.onLiveContainer else colors.onSurfaceVariant,
            )
            HorizontalDivider(color = stitch.hairline)
            InfoRow(
                label = stringResource(R.string.tracking_last_fix),
                value = formatTime(
                    status.lastFixAtMillis,
                    stringResource(R.string.tracking_never),
                ),
            )
            HorizontalDivider(color = stitch.hairline)
            InfoRow(
                label = stringResource(R.string.tracking_last_upload),
                value = formatTime(
                    status.lastUploadAtMillis,
                    stringResource(R.string.tracking_never),
                ),
            )
            HorizontalDivider(color = stitch.hairline)
            InfoRow(
                label = stringResource(R.string.tracking_pending_count),
                value = pluralStringResource(
                    R.plurals.tracking_pending_value,
                    status.pendingQueueCount,
                    status.pendingQueueCount,
                ),
            )
            HorizontalDivider(color = stitch.hairline)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.tracking_connection),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                StitchSemanticBadge(
                    text = if (status.online) {
                        stringResource(R.string.tracking_connection_online)
                    } else {
                        stringResource(R.string.tracking_connection_offline)
                    },
                    kind = if (status.online) StitchBadgeKind.SAFE_HOME else StitchBadgeKind.DELAYED_OFFLINE,
                    showDot = status.online,
                )
            }
            if (status.privacyDiscardedPoints) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = stitch.transitContainer,
                    contentColor = stitch.onTransitContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.tracking_privacy_discarded_notice),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            FilledTonalButton(
                onClick = onRetrySync,
                enabled = status.pendingQueueCount > 0,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.tracking_retry_sync))
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(status: TrackingStatus) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val stitch = LocalStitchColors.current
    val disclosureState = stringResource(if (expanded) R.string.action_state_expanded else R.string.action_state_collapsed)
    OutlinedCard(
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, stitch.hairline),
        colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = expanded,
                        role = Role.Button,
                        onValueChange = { expanded = it },
                    )
                    .semantics { stateDescription = disclosureState },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.tracking_diagnostics),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color = stitch.hairline)
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
    if (LocalDensity.current.fontScale >= 1.3f) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
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
    TrackingProblem.CHECKPOINT_ROLLED -> stringResource(R.string.tracking_problem_checkpoint_rolled)
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

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TrackingContentDarkPreview() {
    AnVeTheme(darkTheme = true) {
        TrackingContent(
            state = TrackingUiState(
                status = TrackingStatus(
                    consentEnabled = true,
                    consentConfirmedByServer = true,
                    trackingRequested = true,
                    serviceRunning = true,
                    providerActive = true,
                    running = true,
                    motion = MotionState.MOVING,
                    operational = OperationalState.ACTIVE,
                    permission = LocationPermissionState.GRANTED_PRECISE,
                    backgroundPermissionGranted = true,
                    notificationsGranted = true,
                    lastFixAtMillis = System.currentTimeMillis(),
                    lastAccuracyMeters = 10.0,
                    lastUploadAtMillis = System.currentTimeMillis(),
                    pendingQueueCount = 0,
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
