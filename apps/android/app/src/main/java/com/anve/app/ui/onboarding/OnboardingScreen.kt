package com.anve.app.ui.onboarding

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.anve.app.data.api.CircleDto
import com.anve.app.data.api.InviteDto
import com.anve.app.data.api.PlaceDto
import com.anve.app.data.circles.InviteLink
import com.anve.app.data.sos.areAppNotificationsEnabled
import com.anve.app.tracking.AndroidLocationPermissions
import com.anve.app.tracking.BackgroundPermissionAction
import com.anve.app.tracking.ForegroundPermissionGrant
import com.anve.app.tracking.FusedLocationProvider
import com.anve.app.tracking.LocationPermissionState
import com.anve.app.tracking.LocationRequestOptions
import com.anve.app.tracking.PermissionSnapshot
import com.anve.app.tracking.backgroundPermissionAction
import com.anve.app.ui.circle.InvitationHost
import com.anve.app.ui.circle.invitationShareIntent
import com.anve.app.ui.common.anveContentWidth
import com.anve.app.ui.places.AndroidPlaceSearcher
import com.anve.app.ui.places.PlaceSearchOutcome
import com.anve.app.ui.places.PlaceSuggestion
import com.anve.app.ui.places.normalizePlaceQuery
import com.anve.app.ui.theme.AnVeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun OnboardingScreen(
    container: AppContainer,
    owner: String,
    startWithPendingInvite: Boolean = false,
    onFinished: () -> Unit,
    onExitRequested: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val trackingPermissions = remember(context) { AndroidLocationPermissions(context) }
    val locationProvider = remember(context) { FusedLocationProvider(context) }
    val placeSearcher = remember(context) { AndroidPlaceSearcher(context) }

    val viewModel: OnboardingViewModel = viewModel(
        key = "onboarding-$owner",
        factory = OnboardingViewModel.factory(
            owner = owner,
            sessions = container.sessionStore,
            onboardingStore = container.onboardingStore,
            circleRepo = container.circleRepository,
            invitationGateway = container.privateInvitationRepository,
            trackingControl = container.trackingManager,
            trackingPermissions = trackingPermissions,
            sharingRepo = container.visibleLocationRepository,
            placesRepo = container.placesRepository,
            startWithPendingInvite = startWithPendingInvite,
        ),
    )

    val state by viewModel.state.collectAsStateWithLifecycle()
    val shareMessage = stringResource(R.string.invite_share_message)
    val shareTitle = stringResource(R.string.invite_share_chooser_title)
    val shareErrorText = stringResource(R.string.invite_share_error)
    val copiedText = stringResource(R.string.onboarding_invite_copied)
    val invalidInviteText = stringResource(R.string.invite_link_invalid)

    var showBackgroundDialog by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable(owner) { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchOutcome by remember { mutableStateOf<PlaceSearchOutcome?>(null) }
    var locatingFix by remember { mutableStateOf(false) }
    var locationFixErrorRes by remember { mutableStateOf<Int?>(null) }
    var pendingFixAfterPermission by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(searchQuery) {
        val normalized = normalizePlaceQuery(searchQuery)
        if (normalized == null) {
            searchOutcome = null
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(350L)
        searchOutcome = placeSearcher.search(normalized)
        searching = false
    }

    LaunchedEffect(state.pendingShareInvite) {
        val invite = state.pendingShareInvite ?: return@LaunchedEffect
        viewModel.consumeShareRequest()
        try {
            val chooser = invitationShareIntent(invite, shareMessage, shareTitle)
            if (chooser != null) {
                context.startActivity(chooser)
            } else {
                snackbarHostState.showSnackbar(shareErrorText)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            snackbarHostState.showSnackbar(shareErrorText)
        }
    }

    LaunchedEffect(state.pendingReviewCode) {
        val code = state.pendingReviewCode ?: return@LaunchedEffect
        viewModel.consumeReviewCode()
        container.invitationInbox.accountChanged(owner)
        container.invitationInbox.offer(InviteLink.create(code).orEmpty())
    }

    fun fetchCurrentPlaceFix() {
        locationFixErrorRes = null
        locatingFix = true
        scope.launch {
            val fix = try {
                withTimeoutOrNull(8_000L) {
                    locationProvider.updates(
                        LocationRequestOptions(
                            intervalMillis = 1_000L,
                            minDistanceMeters = 0f,
                            highAccuracy = true,
                        ),
                    ).first()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            locatingFix = false
            if (fix == null) {
                locationFixErrorRes = R.string.places_editor_location_unavailable
            } else {
                viewModel.updatePlaceCoordinates(fix.latitude.toString(), fix.longitude.toString())
            }
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.refreshPermissions()
        if (areAppNotificationsEnabled(context)) {
            container.pushRegistrationCoordinator.onPermissionGranted(owner)
        }
    }

    val foregroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.refreshPermissions()
        val granted = trackingPermissions.snapshot().canTrack
        if (pendingFixAfterPermission) {
            pendingFixAfterPermission = false
            if (granted) {
                fetchCurrentPlaceFix()
            } else {
                locationFixErrorRes = R.string.places_editor_location_unavailable
            }
        }
    }

    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshPermissions() }

    val fallbackBgOption = stringResource(R.string.tracking_background_option_fallback)
    val backgroundOptionLabel = remember(context, fallbackBgOption) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.backgroundPermissionOptionLabel.toString()
        } else {
            fallbackBgOption
        }
    }

    fun requestForegroundLocation() {
        foregroundLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    fun LocationPermissionState.toForegroundGrant(): ForegroundPermissionGrant = when (this) {
        LocationPermissionState.GRANTED_PRECISE -> ForegroundPermissionGrant.PRECISE
        LocationPermissionState.GRANTED_APPROXIMATE -> ForegroundPermissionGrant.APPROXIMATE
        LocationPermissionState.DENIED,
        LocationPermissionState.NOT_REQUESTED,
        -> ForegroundPermissionGrant.MISSING
    }

    fun handleBackgroundPermissionRequest() {
        val snapshot = trackingPermissions.snapshot()
        when (backgroundPermissionAction(Build.VERSION.SDK_INT, snapshot.location.toForegroundGrant(), snapshot.backgroundLocation)) {
            BackgroundPermissionAction.NOT_REQUIRED -> viewModel.refreshPermissions()
            BackgroundPermissionAction.REQUEST_FOREGROUND -> requestForegroundLocation()
            BackgroundPermissionAction.REQUEST_RUNTIME_BACKGROUND,
            BackgroundPermissionAction.OPEN_APP_SETTINGS,
            -> showBackgroundDialog = true
        }
    }

    BackHandler {
        viewModel.navigateBack(onExitRequested = onExitRequested)
    }

    OnboardingContent(
        state = state,
        snackbarHostState = snackbarHostState,
        backgroundOptionLabel = backgroundOptionLabel,
        notificationsRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
        backgroundRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
        searchQuery = searchQuery,
        searching = searching,
        searchOutcome = searchOutcome,
        locatingCurrentFix = locatingFix,
        locationFixErrorRes = locationFixErrorRes,
        onBack = { viewModel.navigateBack(onExitRequested = onExitRequested) },
        onStartGuided = viewModel::startGuidedSetup,
        onSkipAllToHome = { viewModel.skipAllToHome(onFinished) },
        onSelectFamilyMode = viewModel::selectFamilyMode,
        onCircleNameChange = viewModel::updateCircleName,
        onCreateCircle = viewModel::createCircle,
        onCheckCircleAgain = viewModel::checkCircleAgain,
        onCreateAndShareInvite = viewModel::createAndShareInviteLink,
        onCheckInviteAgain = viewModel::checkInviteAgain,
        onShareInviteAgain = viewModel::requestShareAgain,
        onCopyInviteCode = { code ->
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("AnVe Invite", code))
            scope.launch { snackbarHostState.showSnackbar(copiedText) }
        },
        onJoinCodeChange = viewModel::updateJoinCode,
        onReviewManualInvite = { raw ->
            val extracted = InviteLink.extractCode(raw)
            if (extracted == null) {
                scope.launch { snackbarHostState.showSnackbar(invalidInviteText) }
            } else {
                container.invitationInbox.accountChanged(owner)
                container.invitationInbox.offer(InviteLink.create(extracted).orEmpty())
            }
        },
        onReviewReceivedInvite = viewModel::openReceivedInvitation,
        onRefreshFamily = viewModel::refreshFamilyState,
        onContinueFromFamily = viewModel::proceedFromFamily,
        onSkipFamilyStep = viewModel::proceedFromFamily,
        onRequestNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.refreshPermissions()
                if (areAppNotificationsEnabled(context)) {
                    container.pushRegistrationCoordinator.syncIfPermitted(owner)
                }
            }
        },
        onToggleConsent = viewModel::setConsent,
        onRequestForegroundLocation = ::requestForegroundLocation,
        onRequestBackgroundLocation = ::handleBackgroundPermissionRequest,
        onContinueFromPermissions = viewModel::proceedFromPermissions,
        onSkipPermissionsStep = viewModel::proceedFromPermissions,
        onSearchQueryChange = { searchQuery = it },
        onSelectSearchSuggestion = { suggestion ->
            viewModel.updatePlaceCoordinates(suggestion.latitude.toString(), suggestion.longitude.toString())
            if (state.placeName.isBlank()) {
                viewModel.updatePlaceName(suggestion.title)
            }
            searchQuery = suggestion.title
            searchOutcome = null
        },
        onUseCurrentLocation = {
            if (trackingPermissions.snapshot().canTrack) {
                fetchCurrentPlaceFix()
            } else {
                pendingFixAfterPermission = true
                requestForegroundLocation()
            }
        },
        onToggleViewerGrant = viewModel::toggleViewerGrant,
        onRetryAudience = viewModel::loadAudienceSnapshot,
        onPlaceNameChange = viewModel::updatePlaceName,
        onPlaceCoordinatesChange = viewModel::updatePlaceCoordinates,
        onPlaceRadiusChange = viewModel::updatePlaceRadius,
        onToggleSharedWithCircle = viewModel::togglePlaceSharedWithCircle,
        onTogglePlaceMonitor = viewModel::togglePlaceMonitor,
        onTogglePlaceRecipient = viewModel::togglePlaceRecipient,
        onSavePlaceAndContinue = viewModel::savePlaceAndContinue,
        onSkipPlaceAndContinue = viewModel::skipPlaceAndContinue,
        onEnterApp = { viewModel.completeToHome(onFinished) },
    )

    InvitationHost(
        container = container,
        owner = owner,
        onJoined = { joined -> viewModel.onJoinedCircle(joined) },
    )

    if (showBackgroundDialog) {
        AlertDialog(
            onDismissRequest = { showBackgroundDialog = false },
            title = { Text(stringResource(R.string.tracking_background_settings_title)) },
            text = {
                Text(
                    stringResource(R.string.tracking_background_settings_body, backgroundOptionLabel),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBackgroundDialog = false
                        val snapshot = trackingPermissions.snapshot()
                        val action = backgroundPermissionAction(
                            sdkInt = Build.VERSION.SDK_INT,
                            foreground = snapshot.location.toForegroundGrant(),
                            backgroundGranted = snapshot.backgroundLocation,
                        )
                        when (action) {
                            BackgroundPermissionAction.REQUEST_RUNTIME_BACKGROUND ->
                                backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            BackgroundPermissionAction.OPEN_APP_SETTINGS -> context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                ),
                            )
                            else -> viewModel.refreshPermissions()
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.tracking_background_settings_open))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showBackgroundDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.tracking_disclosure_later))
                }
            },
        )
    }
}

@Composable
fun OnboardingContent(
    state: OnboardingUiState,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    backgroundOptionLabel: String = stringResource(R.string.tracking_background_option_fallback),
    notificationsRequired: Boolean = true,
    backgroundRequired: Boolean = true,
    searchQuery: String = "",
    searching: Boolean = false,
    searchOutcome: PlaceSearchOutcome? = null,
    locatingCurrentFix: Boolean = false,
    locationFixErrorRes: Int? = null,
    onBack: () -> Unit = {},
    onStartGuided: () -> Unit = {},
    onSkipAllToHome: () -> Unit = {},
    onSelectFamilyMode: (OnboardingFamilyMode) -> Unit = {},
    onCircleNameChange: (String) -> Unit = {},
    onCreateCircle: () -> Unit = {},
    onCheckCircleAgain: () -> Unit = {},
    onCreateAndShareInvite: () -> Unit = {},
    onCheckInviteAgain: () -> Unit = {},
    onShareInviteAgain: () -> Unit = {},
    onCopyInviteCode: (String) -> Unit = {},
    onJoinCodeChange: (String) -> Unit = {},
    onReviewManualInvite: (String) -> Unit = {},
    onReviewReceivedInvite: (String) -> Unit = {},
    onRefreshFamily: () -> Unit = {},
    onContinueFromFamily: () -> Unit = {},
    onSkipFamilyStep: () -> Unit = {},
    onRequestNotifications: () -> Unit = {},
    onToggleConsent: (Boolean) -> Unit = {},
    onRequestForegroundLocation: () -> Unit = {},
    onRequestBackgroundLocation: () -> Unit = {},
    onContinueFromPermissions: () -> Unit = {},
    onSkipPermissionsStep: () -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onSelectSearchSuggestion: (PlaceSuggestion) -> Unit = {},
    onUseCurrentLocation: () -> Unit = {},
    onToggleViewerGrant: (String, Boolean) -> Unit = { _, _ -> },
    onRetryAudience: () -> Unit = {},
    onPlaceNameChange: (String) -> Unit = {},
    onPlaceCoordinatesChange: (String, String) -> Unit = { _, _ -> },
    onPlaceRadiusChange: (Double) -> Unit = {},
    onToggleSharedWithCircle: (Boolean) -> Unit = {},
    onTogglePlaceMonitor: (Boolean) -> Unit = {},
    onTogglePlaceRecipient: (String) -> Unit = {},
    onSavePlaceAndContinue: () -> Unit = {},
    onSkipPlaceAndContinue: () -> Unit = {},
    onEnterApp: () -> Unit = {},
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(modifier = Modifier.fillMaxWidth().anveContentWidth()) {
                        OnboardingTopProgress(
                            stepNumber = state.stepNumber,
                            stepCount = state.stepCount,
                            canGoBack = state.step != OnboardingStep.WELCOME,
                            onBack = onBack,
                        )
                    }
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .anveContentWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    when (state.step) {
                        OnboardingStep.WELCOME -> WelcomeStepContent(
                            onStartGuided = onStartGuided,
                            onSkipToHome = onSkipAllToHome,
                        )
                        OnboardingStep.FAMILY -> FamilyStepContent(
                            state = state,
                            onSelectMode = onSelectFamilyMode,
                            onCircleNameChange = onCircleNameChange,
                            onCreateCircle = onCreateCircle,
                            onCheckCircleAgain = onCheckCircleAgain,
                            onCreateAndShareInvite = onCreateAndShareInvite,
                            onCheckInviteAgain = onCheckInviteAgain,
                            onShareInviteAgain = onShareInviteAgain,
                            onCopyInviteCode = onCopyInviteCode,
                            onJoinCodeChange = onJoinCodeChange,
                            onReviewManualInvite = onReviewManualInvite,
                            onReviewReceivedInvite = onReviewReceivedInvite,
                            onRefreshFamily = onRefreshFamily,
                            onContinue = onContinueFromFamily,
                            onSkipStep = onSkipFamilyStep,
                        )
                        OnboardingStep.PERMISSIONS -> PermissionsStepContent(
                            state = state,
                            backgroundOptionLabel = backgroundOptionLabel,
                            notificationsRequired = notificationsRequired,
                            backgroundRequired = backgroundRequired,
                            onRequestNotifications = onRequestNotifications,
                            onToggleConsent = onToggleConsent,
                            onRequestForegroundLocation = onRequestForegroundLocation,
                            onRequestBackgroundLocation = onRequestBackgroundLocation,
                            onContinue = onContinueFromPermissions,
                            onSkipStep = onSkipPermissionsStep,
                        )
                        OnboardingStep.AUDIENCE_AND_PLACE -> AudienceAndPlaceStepContent(
                            state = state,
                            searchQuery = searchQuery,
                            searching = searching,
                            searchOutcome = searchOutcome,
                            locatingCurrentFix = locatingCurrentFix,
                            locationFixErrorRes = locationFixErrorRes,
                            onSearchQueryChange = onSearchQueryChange,
                            onSelectSearchSuggestion = onSelectSearchSuggestion,
                            onUseCurrentLocation = onUseCurrentLocation,
                            onToggleViewerGrant = onToggleViewerGrant,
                            onRetryAudience = onRetryAudience,
                            onPlaceNameChange = onPlaceNameChange,
                            onPlaceCoordinatesChange = onPlaceCoordinatesChange,
                            onPlaceRadiusChange = onPlaceRadiusChange,
                            onToggleSharedWithCircle = onToggleSharedWithCircle,
                            onTogglePlaceMonitor = onTogglePlaceMonitor,
                            onTogglePlaceRecipient = onTogglePlaceRecipient,
                            onSavePlaceAndContinue = onSavePlaceAndContinue,
                            onSkipPlaceAndContinue = onSkipPlaceAndContinue,
                        )
                        OnboardingStep.COMPLETE -> CompleteStepContent(
                            state = state,
                            backgroundRequired = backgroundRequired,
                            onEnterApp = onEnterApp,
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingWelcomePreview() {
    AnVeTheme {
        OnboardingContent(state = OnboardingUiState(step = OnboardingStep.WELCOME))
    }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun OnboardingPermissionsDarkPreview() {
    AnVeTheme(darkTheme = true) {
        OnboardingContent(
            state = OnboardingUiState(
                step = OnboardingStep.PERMISSIONS,
                permissions = PermissionSnapshot(
                    location = LocationPermissionState.GRANTED_PRECISE,
                    backgroundLocation = false,
                    notifications = true,
                    locationServicesEnabled = true,
                ),
                consentEnabled = true,
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingCompletePreview() {
    AnVeTheme {
        OnboardingContent(
            state = OnboardingUiState(
                step = OnboardingStep.COMPLETE,
                circle = CircleDto("c1", "Gia đình mình", "u1", "now"),
                createdInvite = InviteDto("i1", "c1", "ABCD234567", "u1", "later", "now"),
                permissions = PermissionSnapshot(
                    location = LocationPermissionState.GRANTED_PRECISE,
                    backgroundLocation = true,
                    notifications = true,
                    locationServicesEnabled = true,
                ),
                consentEnabled = true,
                createdPlace = PlaceDto("p1", "u1", "c1", "Nhà", 10.77, 106.69, 150.0, "now", "now", true),
            ),
        )
    }
}
