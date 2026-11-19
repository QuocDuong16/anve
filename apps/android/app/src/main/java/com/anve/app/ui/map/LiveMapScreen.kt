package com.anve.app.ui.map

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anve.app.AppContainer
import com.anve.app.BuildConfig
import com.anve.app.R
import com.anve.app.data.api.PlaceDto
import com.anve.app.data.api.VisibleLocationDto
import com.anve.app.data.avatar.AvatarGateway
import com.anve.app.data.location.AudienceMember
import com.anve.app.data.location.VisibleLocationSnapshot
import com.anve.app.data.location.displayLocationConfidence
import com.anve.app.tracking.AndroidLocationPermissions
import com.anve.app.tracking.LocationPermissionState
import com.anve.app.tracking.TrackingStatus
import com.anve.app.ui.avatar.AccountAvatarButton
import com.anve.app.ui.avatar.AvatarFrame
import com.anve.app.ui.avatar.AvatarImage
import com.anve.app.ui.brand.AnVeIllustratedBanner
import com.anve.app.ui.brand.AnVeSafeRadarScene
import com.anve.app.ui.brand.AnVeSharingHeroIllustration
import com.anve.app.ui.common.AnVeSectionHeader
import com.anve.app.ui.common.AnVeSosHoldButton
import com.anve.app.ui.common.AnveErrorNotice
import com.anve.app.ui.common.LowBatteryAlertPill
import com.anve.app.ui.common.resolveLowBatteryPlaceMatch
import com.anve.app.ui.theme.LocalEmergencyColors
import com.anve.app.ui.common.AnveTime
import com.anve.app.ui.common.StitchBadgeKind
import com.anve.app.ui.common.StitchLivePulseDot
import com.anve.app.ui.common.StitchLoadingSkeletonCard
import com.anve.app.ui.common.StitchSemanticBadge
import com.anve.app.ui.common.anveContentWidth
import com.anve.app.ui.theme.AnVeTheme
import com.anve.app.ui.theme.LocalEmergencyColors
import com.anve.app.ui.theme.LocalStitchColors
import com.anve.app.ui.theme.WarmAmber
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class SheetDetent {
    COLLAPSED,
    HALF,
    EXPANDED,
}

@Composable
fun LiveMapScreen(
    container: AppContainer,
    trackingStatus: TrackingStatus = TrackingStatus(),
    userDisplayName: String = "",
    userAvatarId: String? = null,
    onOpenAccount: () -> Unit = {},
    onOpenSos: () -> Unit = {},
    onOpenLocation: () -> Unit = {},
    onOpenCircle: () -> Unit = {},
    onOpenJourney: () -> Unit = {},
    openSharingRequest: Boolean = false,
    onSharingRequestConsumed: () -> Unit = {},
) {
    val viewModel: LiveMapViewModel = viewModel(
        factory = LiveMapViewModel.factory(
            repository = container.visibleLocationRepository,
            placesDataSource = container.placesRepository,
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session by container.sessionStore.session.collectAsStateWithLifecycle(initialValue = null)
    val lifecycleOwner = LocalLifecycleOwner.current
    val ownerId = session?.userId
    var locationRetryTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(ownerId, lifecycleOwner, locationRetryTick) {
        viewModel.ownerChanged(ownerId)
        if (ownerId != null) {
            val snapshotGeneration = viewModel.beginLocationSnapshotCollection(ownerId)
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.visibleLocationRepository.observeLatest(ownerId).collect { snapshot ->
                    viewModel.applySnapshot(snapshot, snapshotGeneration)
                }
            }
        }
    }

    LaunchedEffect(openSharingRequest, ownerId) {
        if (openSharingRequest && ownerId != null) {
            viewModel.showSharing(true, ownerId)
            onSharingRequestConsumed()
        }
    }

    LiveMapContent(
        state = state.takeIf { it.ownerId == ownerId } ?: LiveMapUiState(ownerId = ownerId),
        ownerId = ownerId,
        trackingStatus = trackingStatus,
        userDisplayName = userDisplayName,
        userAvatarId = userAvatarId,
        avatarGateway = container.avatarRepository,
        mapsConfigured = BuildConfig.MAPS_API_KEY.isNotBlank(),
        onOpenAccount = onOpenAccount,
        onOpenSos = onOpenSos,
        onOpenSharing = { viewModel.showSharing(true, ownerId) },
        onCloseSharing = { viewModel.showSharing(false, ownerId) },
        onToggleViewer = { member, enabled -> viewModel.toggleViewer(ownerId, member, enabled) },
        onRetrySharing = { viewModel.refreshSharing(ownerId) },
        onRetryLocation = { locationRetryTick++ },
        onOpenLocation = onOpenLocation,
        onOpenCircle = onOpenCircle,
        onOpenJourney = onOpenJourney,
    )
}

@Composable
private fun LiveMapContent(
    state: LiveMapUiState,
    ownerId: String?,
    trackingStatus: TrackingStatus = TrackingStatus(),
    userDisplayName: String = "",
    userAvatarId: String? = null,
    avatarGateway: AvatarGateway? = null,
    mapsConfigured: Boolean,
    onOpenAccount: () -> Unit = {},
    onOpenSos: () -> Unit = {},
    onOpenSharing: () -> Unit,
    onCloseSharing: () -> Unit,
    onToggleViewer: (AudienceMember, Boolean) -> Unit,
    onRetrySharing: () -> Unit,
    onRetryLocation: () -> Unit = {},
    onOpenLocation: () -> Unit = {},
    onOpenCircle: () -> Unit = {},
    onOpenJourney: () -> Unit = {},
) {
    val snapshot = state.snapshot.takeIf { state.ownerId == ownerId }
    val locations = snapshot?.locations.orEmpty()
    val places = state.places
    val context = LocalContext.current
    val density = LocalDensity.current
    val isDark = isSystemInDarkTheme()
    val permissions = remember(context) { AndroidLocationPermissions(context) }
    var myLocationLayerEnabled by remember { mutableStateOf(false) }
    var selectedOwner by rememberSaveable(ownerId) { mutableStateOf<String?>(null) }
    var sheetDetent by rememberSaveable(ownerId) { mutableStateOf(SheetDetent.COLLAPSED) }
    var quickSheetOpen by rememberSaveable(ownerId) { mutableStateOf(false) }
    var cameraInitialized by remember(ownerId) { mutableStateOf(false) }
    var mapLoaded by remember(ownerId) { mutableStateOf(false) }
    var mapTimedOut by remember(ownerId) { mutableStateOf(false) }
    var nowElapsedMillis by remember { mutableLongStateOf(monotonicNowMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraState = key(ownerId) {
        rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(DEFAULT_CAMERA_TARGET, DEFAULT_CAMERA_ZOOM)
        }
    }
    val mapProperties = rememberAnVeMapProperties(isMyLocationEnabled = myLocationLayerEnabled)

    LaunchedEffect(mapsConfigured, ownerId, mapLoaded) {
        if (!mapsConfigured || mapLoaded) {
            mapTimedOut = false
            return@LaunchedEffect
        }
        delay(10_000)
        if (!mapLoaded) mapTimedOut = true
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            myLocationLayerEnabled =
                permissions.permissionState() == LocationPermissionState.GRANTED_PRECISE
            nowElapsedMillis = monotonicNowMillis()
            while (true) {
                delay(15_000)
                nowElapsedMillis = monotonicNowMillis()
            }
        }
    }
    LaunchedEffect(ownerId, locations.map(VisibleLocationDto::ownerId)) {
        if (selectedOwner !in locations.map(VisibleLocationDto::ownerId)) {
            selectedOwner = locations.firstOrNull()?.ownerId
        }
    }

    val viewerCount = remember(state.sharing, ownerId) {
        state.sharing?.members?.count { it.userId != ownerId && it.canViewLocation } ?: 0
    }
    val memberById = remember(state.sharing) {
        state.sharing?.members?.associateBy(AudienceMember::userId).orEmpty()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        var headerHeightPx by remember { mutableIntStateOf(0) }
        val measuredHeaderDp = with(density) { headerHeightPx.toDp() }
        val fontScale = density.fontScale.coerceAtLeast(1f)
        val largeFont = fontScale >= 1.3f
        val collapsedHeight = if (headerHeightPx > 0) measuredHeaderDp else if (largeFont) 144.dp else 78.dp
        val halfCap = if (largeFont) {
            (maxHeight * 0.52f).coerceIn(260.dp, 420.dp)
        } else {
            (maxHeight * 0.36f).coerceIn(220.dp, 310.dp)
        }
        val halfHeight = when {
            snapshot == null || snapshot.loading ->
                (collapsedHeight + 108.dp).coerceAtMost(halfCap)
            locations.isEmpty() ->
                (collapsedHeight + 132.dp * fontScale).coerceAtMost(halfCap)
            locations.size == 1 ->
                (collapsedHeight + 92.dp * fontScale + 14.dp)
                    .coerceAtMost(maxOf(collapsedHeight + 96.dp, maxHeight * 0.45f))
            else -> halfCap
        }
        val expandedHeight = (maxHeight * 0.78f).coerceAtLeast(320.dp)
        val targetSheetHeight = when (sheetDetent) {
            SheetDetent.COLLAPSED -> collapsedHeight
            SheetDetent.HALF -> halfHeight
            SheetDetent.EXPANDED -> expandedHeight
        }
        var dragOffsetPx by remember { mutableFloatStateOf(0f) }
        var isDragging by remember { mutableStateOf(false) }
        val dragOffsetDp = with(density) { dragOffsetPx.toDp() }
        val animatedSheetHeight by animateDpAsState(
            targetValue = (targetSheetHeight - dragOffsetDp).coerceIn(collapsedHeight, expandedHeight),
            animationSpec = if (isDragging) snap() else tween(durationMillis = 200),
            label = "sheetHeight",
        )
        val colors = MaterialTheme.colorScheme
        val stitch = LocalStitchColors.current

        if (mapsConfigured) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraState,
                properties = mapProperties,
                contentPadding = PaddingValues(bottom = animatedSheetHeight),
                uiSettings = MapUiSettings(
                    compassEnabled = false,
                    zoomControlsEnabled = false,
                    myLocationButtonEnabled = false,
                    mapToolbarEnabled = false,
                ),
                onMapLoaded = { mapLoaded = true },
            ) {
                places.forEach { place ->
                    val placePoint = LatLng(place.latitude, place.longitude)
                    key("place-${place.id}") {
                        val placeMarkerState = remember(placePoint) { MarkerState(position = placePoint) }
                        val placeIcon = rememberPlaceMarkerIcon(place.name, isDark)
                        Circle(
                            center = placePoint,
                            radius = place.radiusMeters,
                            fillColor = colors.primary.copy(alpha = 0.08f),
                            strokeColor = colors.primary.copy(alpha = 0.28f),
                            strokeWidth = 1.5f,
                        )
                        Marker(
                            state = placeMarkerState,
                            title = place.name,
                            icon = placeIcon,
                            anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                        )
                    }
                }

                locations.forEach { location ->
                    val point = location.latLngOrNull() ?: return@forEach
                    key(location.ownerId) {
                        val markerState = remember(point) { MarkerState(position = point) }
                        val confidence = location.displayConfidence(snapshot, nowElapsedMillis)
                        val ageMillis = location.effectiveAgeMillis(snapshot, nowElapsedMillis)
                        val placeMatch = findMatchingPlace(location.latitude, location.longitude, places)
                        val initial = location.displayName.trim().take(1).uppercase().ifBlank { "?" }
                        val pinLabel = buildPinLabel(location.displayName, ageMillis, placeMatch, confidence)
                        val (pinIcon, anchorY) = rememberMemberMarkerIcon(
                            initial = initial,
                            label = pinLabel,
                            confidence = confidence,
                            isDark = isDark,
                        )
                        Marker(
                            state = markerState,
                            title = location.displayName,
                            snippet = confidence.toConfidenceLabel(),
                            icon = pinIcon,
                            anchor = androidx.compose.ui.geometry.Offset(0.5f, anchorY),
                            onClick = {
                                selectedOwner = location.ownerId
                                true
                            },
                        )
                        val accuracy = location.horizontalAccuracyMeters
                        if (accuracy != null && accuracy in 1.0..2_000.0) {
                            Circle(
                                center = point,
                                radius = accuracy,
                                fillColor = colors.primary.copy(alpha = 0.10f),
                                strokeColor = colors.primary.copy(alpha = 0.32f),
                                strokeWidth = 1f,
                            )
                        }
                    }
                }
            }

            LaunchedEffect(mapLoaded, cameraInitialized, locations.map { it.ownerId to it.recordedAt }) {
                if (!mapLoaded || cameraInitialized) return@LaunchedEffect
                val points = locations.mapNotNull(VisibleLocationDto::latLngOrNull).distinct()
                if (points.isEmpty()) return@LaunchedEffect
                val position = cameraState.position
                val stayedAtDefault = kotlin.math.abs(position.target.latitude - DEFAULT_CAMERA_TARGET.latitude) < 0.01 &&
                    kotlin.math.abs(position.target.longitude - DEFAULT_CAMERA_TARGET.longitude) < 0.01 &&
                    kotlin.math.abs(position.zoom - DEFAULT_CAMERA_ZOOM) < 0.05
                if (stayedAtDefault) {
                    fitCameraToPoints(cameraState, points, paddingPx = 96, singleZoom = 14f)
                }
                cameraInitialized = true
            }

            if (!mapLoaded && !mapTimedOut) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        } else {
            MapUnavailableCard(
                message = stringResource(R.string.map_unconfigured_message),
                memberCount = locations.size,
                onOpenSharing = onOpenSharing,
                onOpenLocation = onOpenLocation,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .anveContentWidth()
                    .padding(top = 74.dp, start = 16.dp, end = 16.dp, bottom = collapsedHeight + 16.dp),
            )
        }

        // Single Floating Top Header: consolidated sharing pill + circular account avatar.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .anveContentWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FloatingMapHeader(
                trackingStatus = trackingStatus,
                feedConnected = snapshot?.connected ?: true,
                viewerCount = viewerCount,
                ownerId = ownerId,
                userAvatarId = userAvatarId,
                userDisplayName = userDisplayName,
                avatarGateway = avatarGateway,
                onOpenSharingMenu = { quickSheetOpen = true },
                onOpenAccount = onOpenAccount,
            )
            snapshot?.error?.let { message ->
                AnveErrorNotice(message, onRetry = onRetryLocation)
            }
        }

        // Right-edge floating controls anchored cleanly above the Bottom Sheet.
        val showFloatingControls = sheetDetent != SheetDetent.EXPANDED
        AnimatedVisibility(
            visible = showFloatingControls,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = 16.dp,
                    bottom = (animatedSheetHeight + 14.dp).coerceAtMost(maxHeight - 160.dp),
                ),
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (mapsConfigured && locations.isNotEmpty()) {
                    val targetLocation = locations.firstOrNull { it.ownerId == selectedOwner } ?: locations.firstOrNull()
                    Surface(
                        onClick = {
                            targetLocation?.latLngOrNull()?.let { point ->
                                cameraState.move(CameraUpdateFactory.newLatLngZoom(point, 15f))
                            }
                        },
                        shape = CircleShape,
                        color = stitch.cardTactile.copy(alpha = 0.94f),
                        contentColor = colors.primary,
                        border = BorderStroke(1.dp, stitch.hairline),
                        shadowElevation = 2.dp,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.MyLocation,
                                contentDescription = stringResource(R.string.map_recenter_content_desc),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                AnVeSosHoldButton(onTriggered = onOpenSos)
            }
        }

        // Restructured 3-detent Bottom Sheet: "Nhịp sống gia đình".
        val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        val handleDesc = stringResource(R.string.map_sheet_handle_desc)
        val expandActionLabel = stringResource(R.string.map_sheet_expand_action)
        val collapseActionLabel = stringResource(R.string.map_sheet_collapse_action)
        val detentDesc = stringResource(
            if (sheetDetent == SheetDetent.COLLAPSED) R.string.action_state_collapsed else R.string.action_state_expanded,
        )

        val sheetHeightModifier = if (
            sheetDetent == SheetDetent.COLLAPSED &&
            !isDragging &&
            (headerHeightPx == 0 || animatedSheetHeight < collapsedHeight)
        ) {
            Modifier.wrapContentHeight()
        } else {
            Modifier.height(animatedSheetHeight)
        }

        Surface(
            shape = sheetShape,
            color = stitch.cardTactile,
            border = BorderStroke(1.dp, stitch.hairline),
            shadowElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .anveContentWidth()
                .then(sheetHeightModifier)
                .align(Alignment.BottomCenter),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Drag Handle + Sheet Header + Primary CTA ("Hành trình").
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Measure true header height without outer sheet height clamping (needed when stacked at large font scales).
                        .wrapContentHeight(align = Alignment.Top, unbounded = true)
                        .onSizeChanged { headerHeightPx = it.height }
                        .pointerInput(collapsedHeight, halfHeight, expandedHeight) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    isDragging = true
                                    dragOffsetPx = 0f
                                },
                                onDragEnd = {
                                    val draggedDp = with(density) { dragOffsetPx.toDp() }
                                    val current = targetSheetHeight - draggedDp
                                    val midLow = (collapsedHeight + halfHeight) / 2f
                                    val midHigh = (halfHeight + expandedHeight) / 2f
                                    sheetDetent = when {
                                        current < midLow -> SheetDetent.COLLAPSED
                                        current < midHigh -> SheetDetent.HALF
                                        else -> SheetDetent.EXPANDED
                                    }
                                    dragOffsetPx = 0f
                                    isDragging = false
                                },
                                onDragCancel = {
                                    dragOffsetPx = 0f
                                    isDragging = false
                                },
                                onVerticalDrag = { _, dragAmount ->
                                    dragOffsetPx += dragAmount
                                },
                            )
                        }
                        .clickable(
                            role = Role.Button,
                            onClickLabel = handleDesc,
                        ) {
                            sheetDetent = when (sheetDetent) {
                                SheetDetent.COLLAPSED -> SheetDetent.HALF
                                SheetDetent.HALF -> SheetDetent.EXPANDED
                                SheetDetent.EXPANDED -> SheetDetent.COLLAPSED
                            }
                        }
                        .semantics {
                            contentDescription = handleDesc
                            stateDescription = detentDesc
                            if (sheetDetent != SheetDetent.EXPANDED) {
                                onClick(label = expandActionLabel) {
                                    sheetDetent = SheetDetent.EXPANDED
                                    true
                                }
                            }
                            if (sheetDetent != SheetDetent.COLLAPSED) {
                                onClick(label = collapseActionLabel) {
                                    sheetDetent = SheetDetent.COLLAPSED
                                    true
                                }
                            }
                        }
                        .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 38.dp, height = 4.dp)
                                .background(stitch.hairline, CircleShape),
                        )
                    }

                    val titleBlock: @Composable (Modifier) -> Unit = { blockMod ->
                        Column(
                            modifier = blockMod,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.map_pulse_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                modifier = Modifier.semantics { heading() },
                            )
                            Text(
                                text = if (locations.isEmpty()) {
                                    stringResource(R.string.map_pulse_count_empty)
                                } else {
                                    stringResource(R.string.map_pulse_count, locations.size)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                    }
                    val journeyButton: @Composable (Modifier) -> Unit = { btnMod ->
                        Button(
                            onClick = onOpenJourney,
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.primaryContainer,
                                contentColor = colors.onPrimaryContainer,
                            ),
                            modifier = btnMod.heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Icon(Icons.Outlined.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.map_start_journey_short),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 2,
                            )
                        }
                    }
                    if (largeFont) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            titleBlock(Modifier.fillMaxWidth())
                            journeyButton(Modifier.fillMaxWidth())
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            titleBlock(Modifier.weight(1f))
                            journeyButton(Modifier)
                        }
                    }
                }

                // Body content visible in HALF and EXPANDED detents.
                if (sheetDetent != SheetDetent.COLLAPSED) {
                    when {
                        snapshot == null || snapshot.loading -> Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            StitchLoadingSkeletonCard(rows = 2)
                        }
                        locations.isEmpty() -> Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.map_no_locations_notice),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = onOpenSharing,
                                    shape = CircleShape,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Text(stringResource(R.string.map_audience_action))
                                }
                                FilledTonalButton(
                                    onClick = onOpenLocation,
                                    shape = CircleShape,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Text(stringResource(R.string.action_open_location))
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(locations, key = VisibleLocationDto::ownerId) { location ->
                                val confidence = location.displayConfidence(snapshot, nowElapsedMillis)
                                val effectiveAge = location.effectiveAgeMillis(snapshot, nowElapsedMillis)
                                val isSelected = location.ownerId == selectedOwner
                                val member = memberById[location.ownerId]
                                MemberLocationCard(
                                    ownerId = ownerId,
                                    location = location,
                                    memberAvatarId = member?.avatarId,
                                    avatarGateway = avatarGateway,
                                    places = places,
                                    confidence = confidence,
                                    effectiveAgeMillis = effectiveAge,
                                    selected = isSelected,
                                    onSelect = {
                                        selectedOwner = location.ownerId
                                        location.latLngOrNull()?.let { point ->
                                            cameraState.move(CameraUpdateFactory.newLatLngZoom(point, 15f))
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (quickSheetOpen) {
        QuickSharingSheet(
            trackingStatus = trackingStatus,
            feedConnected = snapshot?.connected ?: true,
            viewerCount = viewerCount,
            onDismiss = { quickSheetOpen = false },
            onOpenLocation = {
                quickSheetOpen = false
                onOpenLocation()
            },
            onOpenAudience = {
                quickSheetOpen = false
                onOpenSharing()
            },
        )
    }

    if (state.sharingOpen) {
        AudienceSheet(
            state = state,
            ownerId = ownerId,
            onDismiss = onCloseSharing,
            onToggle = onToggleViewer,
            onRetry = onRetrySharing,
            onOpenLocation = onOpenLocation,
            onOpenCircle = onOpenCircle,
        )
    }
}

@Composable
private fun FloatingMapHeader(
    trackingStatus: TrackingStatus,
    feedConnected: Boolean,
    viewerCount: Int,
    ownerId: String?,
    userAvatarId: String?,
    userDisplayName: String,
    avatarGateway: AvatarGateway?,
    onOpenSharingMenu: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    val statusColor = when {
        trackingStatus.activelyTracking && feedConnected -> stitch.livePulse
        trackingStatus.activelyTracking || trackingStatus.consentEnabled -> WarmAmber
        else -> colors.outline
    }
    val statusText = when {
        trackingStatus.activelyTracking && viewerCount > 0 ->
            stringResource(R.string.map_floating_sharing_with, viewerCount)
        trackingStatus.activelyTracking ->
            stringResource(R.string.map_floating_sharing_no_viewers)
        trackingStatus.consentEnabled ->
            stringResource(R.string.map_floating_consent_paused)
        else ->
            stringResource(R.string.map_floating_sharing_off)
    }
    val menuDesc = stringResource(R.string.map_floating_menu_desc)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onOpenSharingMenu,
            shape = RoundedCornerShape(24.dp),
            color = stitch.cardTactile.copy(alpha = 0.90f),
            border = BorderStroke(1.dp, stitch.hairline),
            shadowElevation = 2.dp,
            modifier = Modifier
                .weight(1f, fill = false)
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "$statusText. $menuDesc" },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StitchLivePulseDot(
                    active = trackingStatus.activelyTracking,
                    color = statusColor,
                    haloSize = 14.dp,
                    coreSize = 7.dp,
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurface,
                    maxLines = 2,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        AccountAvatarButton(
            ownerId = ownerId,
            imageId = userAvatarId,
            displayName = userDisplayName,
            gateway = avatarGateway,
            onClick = onOpenAccount,
            avatarSize = 42.dp,
            elevated = true,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickSharingSheet(
    trackingStatus: TrackingStatus,
    feedConnected: Boolean,
    viewerCount: Int,
    onDismiss: () -> Unit,
    onOpenLocation: () -> Unit,
    onOpenAudience: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = stitch.cardTactile) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.map_quick_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StitchLivePulseDot(
                    active = feedConnected,
                    haloSize = 14.dp,
                    coreSize = 6.dp,
                )
                Text(
                    text = stringResource(
                        if (feedConnected) R.string.map_quick_feed_live else R.string.map_quick_feed_offline,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }

            HorizontalDivider(color = stitch.hairline)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(onClick = onOpenLocation)
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.map_quick_manage_sharing_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(
                            if (trackingStatus.activelyTracking) {
                                R.string.map_quick_manage_sharing_subtitle_on
                            } else {
                                R.string.map_quick_manage_sharing_subtitle_off
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                )
            }

            HorizontalDivider(color = stitch.hairline)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(onClick = onOpenAudience)
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.map_quick_audience_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (viewerCount > 0) {
                            stringResource(R.string.map_quick_audience_subtitle_count, viewerCount)
                        } else {
                            stringResource(R.string.map_quick_audience_subtitle_empty)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MapUnavailableCard(
    message: String,
    modifier: Modifier = Modifier,
    memberCount: Int = 0,
    onOpenSharing: () -> Unit = {},
    onOpenLocation: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    OutlinedCard(
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, stitch.hairline),
        colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnVeIllustratedBanner(
                overlayBadge = {
                    StitchSemanticBadge(
                        text = stringResource(R.string.map_fallback_radar_badge),
                        kind = StitchBadgeKind.NEUTRAL,
                    )
                },
            ) {
                AnVeSafeRadarScene(
                    memberCount = memberCount,
                    modifier = Modifier.fillMaxWidth().height(152.dp),
                )
            }
            Text(
                stringResource(R.string.map_unavailable_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onOpenSharing,
                    shape = CircleShape,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.map_audience_action))
                }
                FilledTonalButton(
                    onClick = onOpenLocation,
                    shape = CircleShape,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.action_open_location))
                }
            }
        }
    }
}

@Composable
private fun MemberLocationCard(
    ownerId: String?,
    location: VisibleLocationDto,
    memberAvatarId: String?,
    avatarGateway: AvatarGateway?,
    places: List<PlaceDto>,
    confidence: String,
    effectiveAgeMillis: Long?,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    val statusDotColor = when (confidence) {
        "FRESH" -> stitch.livePulse
        "DELAYED" -> WarmAmber
        else -> colors.outline
    }
    val borderColor = when {
        selected && confidence == "FRESH" -> colors.primary
        selected && confidence == "DELAYED" -> WarmAmber
        selected -> colors.outline
        else -> stitch.hairline
    }
    val cardBackground = if (selected) colors.surfaceContainer else colors.surfaceContainerLow

    val placeContextText = resolvePlaceContext(location, places)
    val contextLine = placeContextText ?: location.horizontalAccuracyMeters?.let {
        stringResource(R.string.map_accuracy, it.toInt())
    } ?: stringResource(R.string.map_confidence_unknown)

    val relativeAge = effectiveAgeMillis?.let(AnveTime::relativeAge)
    val clockTime = AnveTime.clock(location.recordedAt, "")
    val timeLine = when {
        relativeAge != null && clockTime.isNotBlank() ->
            stringResource(R.string.map_updated_relative_clock, relativeAge, clockTime)
        relativeAge != null ->
            stringResource(R.string.map_updated_relative, relativeAge)
        else ->
            stringResource(R.string.map_updated_at, location.recordedAt.toVietnameseTime(stringResource(R.string.map_time_unavailable)))
    }
    val statusText = when (confidence) {
        "FRESH" -> stringResource(R.string.map_freshness_just_now)
        "DELAYED" -> stringResource(R.string.map_freshness_delayed_short)
        "OFFLINE" -> stringResource(R.string.map_freshness_offline_short)
        "STALE" -> stringResource(R.string.map_freshness_stale_short)
        else -> stringResource(R.string.map_freshness_unknown_short)
    }

    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(20.dp),
        color = cardBackground,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { this.selected = selected },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(46.dp)) {
                if (ownerId != null && avatarGateway != null && memberAvatarId != null) {
                    AvatarImage(
                        owner = ownerId,
                        subject = location.ownerId,
                        imageId = memberAvatarId,
                        name = location.displayName,
                        gateway = avatarGateway,
                        modifier = Modifier.size(44.dp),
                    )
                } else {
                    AvatarFrame(
                        name = location.displayName,
                        image = null,
                        modifier = Modifier.size(44.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(statusDotColor)
                        .border(2.dp, cardBackground, CircleShape),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = location.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                    )
                    Text(
                        text = "· $statusText",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (confidence == "FRESH") statusDotColor else colors.onSurfaceVariant,
                        maxLines = 2,
                    )
                    location.lowBatteryAlert?.let { alert ->
                        LowBatteryAlertPill(alert = alert)
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (placeContextText != null) {
                        Icon(
                            imageVector = Icons.Outlined.LocationOn,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Text(
                        text = contextLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurface,
                        maxLines = 3,
                        modifier = Modifier.weight(1f),
                    )
                }
                location.lowBatteryAlert?.let { alert ->
                    val isCritical = alert.threshold <= 5 || alert.batteryPercent <= 5
                    val noteColor = if (isCritical) {
                        LocalEmergencyColors.current.onEmergencyContainer
                    } else {
                        stitch.onTransitContainer
                    }
                    val noteBase = stringResource(
                        if (isCritical) R.string.low_battery_critical_note else R.string.low_battery_warning_note,
                    )
                    val alertPlace = resolveLowBatteryPlaceMatch(alert, places)?.let { match ->
                        if (match.isInside) {
                            stringResource(R.string.low_battery_place_at, match.place.name)
                        } else {
                            stringResource(R.string.low_battery_place_near, match.place.name)
                        }
                    }
                    val noteLine = if (alertPlace != null && placeContextText == null) {
                        "$alertPlace · $noteBase"
                    } else {
                        noteBase
                    }
                    Text(
                        text = noteLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = noteColor,
                        maxLines = 2,
                    )
                }
                Text(
                    text = timeLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                )
            }

            FilledTonalIconButton(
                onClick = onSelect,
                enabled = location.latLngOrNull() != null,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = colors.surfaceContainerHigh,
                    contentColor = colors.primary,
                ),
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.MyLocation,
                    contentDescription = stringResource(R.string.map_recenter_content_desc),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun resolvePlaceContext(location: VisibleLocationDto, places: List<PlaceDto>): String? {
    val match = findMatchingPlace(location.latitude, location.longitude, places) ?: return null
    return if (match.isInside) {
        stringResource(R.string.map_place_here, match.place.name)
    } else {
        val rounded = (((match.distanceMeters / 10.0).roundToInt()) * 10).coerceAtLeast(10)
        stringResource(R.string.map_place_near, match.place.name, rounded)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudienceSheet(
    state: LiveMapUiState,
    ownerId: String?,
    onDismiss: () -> Unit,
    onToggle: (AudienceMember, Boolean) -> Unit,
    onRetry: () -> Unit,
    onOpenLocation: () -> Unit = {},
    onOpenCircle: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = stitch.cardTactile) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val sheetMaxHeight = maxHeight * 0.86f
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = sheetMaxHeight)
                    .padding(horizontal = 20.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    AnVeSharingHeroIllustration(
                        active = state.sharing?.consentEnabled ?: true,
                        modifier = Modifier.fillMaxWidth().height(96.dp),
                    )
                }
                item {
                    AnVeSectionHeader(
                        title = stringResource(R.string.map_audience_title),
                    )
                }
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(stringResource(R.string.map_audience_explainer_consent), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        Text(stringResource(R.string.map_audience_explainer_timing), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        Text(stringResource(R.string.map_audience_explainer_offline), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
                if (state.sharingLoading && state.sharing == null) {
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
                state.sharingErrorRes?.let { res ->
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AnveErrorNotice(stringResource(res))
                            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.map_retry))
                            }
                        }
                    }
                }
                val sharing = state.sharing
                when {
                    sharing == null -> Unit
                    !sharing.hasCircle -> item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.map_no_circle), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = onOpenCircle, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.map_open_circle))
                            }
                        }
                    }
                    !sharing.consentEnabled -> item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(R.string.map_consent_off),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = onOpenLocation, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.action_open_location))
                            }
                        }
                    }
                    sharing.members.none { it.userId != ownerId } -> item {
                        Text(
                            stringResource(R.string.map_no_other_members),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    else -> {
                        val otherMembers = sharing.members.filter { it.userId != ownerId }
                        itemsIndexed(otherMembers, key = { _, it -> it.userId }) { index, member ->
                            if (index > 0) {
                                HorizontalDivider(color = stitch.hairline)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .then(
                                        if (member.userId in state.unresolvedViewerIds) {
                                            Modifier
                                        } else {
                                            Modifier.toggleable(
                                                value = member.canViewLocation,
                                                enabled = sharing.consentEnabled && member.userId !in state.pendingViewerIds,
                                                role = Role.Switch,
                                                onValueChange = { onToggle(member, it) },
                                            )
                                        },
                                    )
                                    .padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(colors.secondaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = member.displayName.take(1).uppercase().ifBlank { "?" },
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.onSecondaryContainer,
                                    )
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(member.displayName, style = MaterialTheme.typography.bodyLarge)
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = if (member.role == "owner") {
                                                stringResource(R.string.circle_role_owner)
                                            } else {
                                                stringResource(R.string.circle_role_member)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant,
                                        )
                                        StitchSemanticBadge(
                                            text = stringResource(
                                                if (member.canViewLocation) R.string.map_audience_verified_chip
                                                else R.string.map_audience_paused_chip,
                                            ),
                                            kind = if (member.canViewLocation) StitchBadgeKind.SAFE_HOME else StitchBadgeKind.DELAYED_OFFLINE,
                                        )
                                    }
                                }
                                if (member.userId in state.unresolvedViewerIds) {
                                    Text(stringResource(R.string.map_viewer_unresolved), style = MaterialTheme.typography.bodySmall)
                                } else {
                                    Switch(
                                        checked = member.canViewLocation,
                                        enabled = sharing.consentEnabled && member.userId !in state.pendingViewerIds,
                                        onCheckedChange = null,
                                    )
                                }
                                if (member.userId in state.pendingViewerIds) {
                                    CircularProgressIndicator(Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun VisibleLocationDto.latLngOrNull(): LatLng? {
    val lat = latitude ?: return null
    val lon = longitude ?: return null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    return LatLng(lat, lon)
}

private fun VisibleLocationDto.effectiveAgeMillis(
    snapshot: VisibleLocationSnapshot?,
    nowElapsedMillis: Long,
): Long? {
    val base = ageMillis ?: return null
    val observed = snapshot?.dataObservedElapsedMillis ?: return base
    if (base < 0L || nowElapsedMillis < observed) return base
    return base + (nowElapsedMillis - observed)
}

private fun VisibleLocationDto.displayConfidence(
    snapshot: VisibleLocationSnapshot?,
    nowElapsedMillis: Long,
): String = displayLocationConfidence(
    serverConfidence = confidence,
    ageAtSnapshotMillis = ageMillis,
    snapshotObservedElapsedMillis = snapshot?.dataObservedElapsedMillis,
    nowElapsedMillis = nowElapsedMillis,
)

private fun monotonicNowMillis(): Long = System.nanoTime() / 1_000_000

private fun shortPinName(displayName: String): String {
    val trimmed = displayName.trim()
    if (trimmed.length <= 13) return trimmed
    val parts = trimmed.split(Regex("\\s+"))
    return if (parts.size >= 2) {
        val tail = parts.takeLast(2).joinToString(" ")
        if (tail.length <= 14) tail else parts.last().take(12)
    } else {
        trimmed.take(12)
    }
}

private fun buildPinLabel(
    displayName: String,
    ageMillis: Long?,
    placeMatch: PlaceMatch?,
    confidence: String,
): String {
    val short = shortPinName(displayName)
    if (placeMatch?.isInside == true) {
        val placeName = placeMatch.place.name.trim().take(12)
        return if (confidence == "FRESH" || ageMillis == null) {
            "$short · Ở $placeName"
        } else {
            "$short · Ở $placeName (${AnveTime.compactAge(ageMillis)})"
        }
    }
    return if (ageMillis != null) "$short · ${AnveTime.compactAge(ageMillis)}" else short
}

@Composable
private fun rememberMemberMarkerIcon(
    initial: String,
    label: String,
    confidence: String,
    isDark: Boolean,
): Pair<BitmapDescriptor, Float> {
    val density = LocalDensity.current.density
    return remember(initial, label, confidence, isDark, density) {
        memberMarkerIcon(initial, label, confidence, isDark, density)
    }
}

/**
 * Renders a teardrop member pin with a semantic status ring (Forest Green for FRESH,
 * Warm Amber for DELAYED, Neutral Slate for STALE/OFFLINE) plus a compact time-ago
 * pill badge anchored right below the teardrop tip.
 */
private fun memberMarkerIcon(
    initial: String,
    label: String,
    confidence: String,
    isDark: Boolean,
    density: Float,
): Pair<BitmapDescriptor, Float> {
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isDark) PIN_LABEL_TEXT_DARK else PIN_LABEL_TEXT_LIGHT
        textSize = 11f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val textWidth = labelPaint.measureText(label)
    val pillPadH = 8f * density
    val pillHeight = 20f * density
    val pillWidth = (textWidth + pillPadH * 2f).coerceAtLeast(48f * density)

    val pinDiameter = 36f * density
    val pinRadius = pinDiameter / 2f
    val tipGap = 3f * density
    val pinBodyHeight = pinDiameter + 9f * density
    val totalWidthPx = maxOf(pinDiameter + 6f * density, pillWidth + 4f * density).roundToInt().coerceAtLeast(1)
    val totalHeightPx = (pinBodyHeight + tipGap + pillHeight + 3f * density).roundToInt().coerceAtLeast(1)

    val bitmap = Bitmap.createBitmap(totalWidthPx, totalHeightPx, Bitmap.Config.ARGB_8888)
    bitmap.density = Bitmap.DENSITY_NONE
    val canvas = Canvas(bitmap)
    val cx = totalWidthPx / 2f

    val statusColor = when (confidence) {
        "FRESH" -> MEMBER_MARKER_GREEN
        "DELAYED" -> MEMBER_MARKER_AMBER
        "OFFLINE" -> MEMBER_MARKER_SLATE
        else -> MEMBER_MARKER_GRAY
    }
    val outerBgColor = if (isDark) PIN_LABEL_BG_DARK else MEMBER_MARKER_SAND

    val circleCenterY = pinRadius + 2f * density
    val tipY = pinBodyHeight

    // Teardrop silhouette in outer contrast color, then inner status ring + initial circle.
    val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = outerBgColor }
    val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = statusColor }
    val tail = Path().apply {
        moveTo(cx - pinRadius * 0.68f, circleCenterY + pinRadius * 0.42f)
        lineTo(cx, tipY)
        lineTo(cx + pinRadius * 0.68f, circleCenterY + pinRadius * 0.42f)
        close()
    }
    canvas.drawPath(tail, outerPaint)
    canvas.drawCircle(cx, circleCenterY, pinRadius + 2f * density, outerPaint)
    canvas.drawCircle(cx, circleCenterY, pinRadius, statusPaint)
    canvas.drawCircle(cx, circleCenterY, pinRadius - 3f * density, outerPaint)
    canvas.drawCircle(cx, circleCenterY, pinRadius - 4.5f * density, statusPaint)

    val initialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MEMBER_MARKER_TEXT
        textAlign = Paint.Align.CENTER
        textSize = 13.5f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val initialY = circleCenterY - (initialPaint.descent() + initialPaint.ascent()) / 2f
    canvas.drawText(initial, cx, initialY, initialPaint)

    // Pill badge under teardrop tip: "Thanh Ngọc · 15p trước".
    val pillTop = tipY + tipGap
    val pillRect = RectF(cx - pillWidth / 2f, pillTop, cx + pillWidth / 2f, pillTop + pillHeight)
    val pillRadius = pillHeight / 2f
    val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isDark) PIN_LABEL_BG_DARK else PIN_LABEL_BG_LIGHT
    }
    val pillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.25f * density
        color = statusColor
    }
    canvas.drawRoundRect(pillRect, pillRadius, pillRadius, pillBgPaint)
    canvas.drawRoundRect(pillRect, pillRadius, pillRadius, pillStrokePaint)

    val labelY = pillRect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
    canvas.drawText(label, cx - textWidth / 2f, labelY, labelPaint)

    val anchorY = (tipY / totalHeightPx.toFloat()).coerceIn(0.1f, 0.9f)
    return BitmapDescriptorFactory.fromBitmap(bitmap) to anchorY
}

private val MEMBER_MARKER_GREEN = 0xFF3D602F.toInt()
private val MEMBER_MARKER_AMBER = 0xFFC87A28.toInt()
private val MEMBER_MARKER_SLATE = 0xFF695B60.toInt()
private val MEMBER_MARKER_GRAY = 0xFF74796D.toInt()
private val MEMBER_MARKER_SAND = 0xFFFDF9F2.toInt()
private val MEMBER_MARKER_TEXT = 0xFFFFFFFF.toInt()
private val PIN_LABEL_BG_LIGHT = 0xFFFEFAF3.toInt()
private val PIN_LABEL_TEXT_LIGHT = 0xFF1C1C18.toInt()
private val PIN_LABEL_BG_DARK = 0xFF21211C.toInt()
private val PIN_LABEL_TEXT_DARK = 0xFFE6E2DB.toInt()

@Composable
private fun String.toConfidenceLabel(): String = when (this) {
    "FRESH" -> stringResource(R.string.map_confidence_fresh)
    "DELAYED" -> stringResource(R.string.map_confidence_delayed)
    "STALE" -> stringResource(R.string.map_confidence_stale)
    "OFFLINE" -> stringResource(R.string.map_confidence_offline)
    else -> stringResource(R.string.map_confidence_unknown)
}

private fun String?.toVietnameseTime(fallback: String): String = AnveTime.full(this, fallback)

private val DEFAULT_CAMERA_TARGET = LatLng(16.0, 107.5)
private const val DEFAULT_CAMERA_ZOOM = 5.0f

@Preview(name = "1. Standard Light", showBackground = true)
@Composable
private fun LiveMapContentPreview() {
    val sample = listOf(
        VisibleLocationDto(
            ownerId = "u1",
            displayName = "Mẹ Lan",
            latitude = 21.033,
            longitude = 105.834,
            horizontalAccuracyMeters = 8.0,
            recordedAt = "2026-10-25T10:15:00Z",
            receivedAt = "2026-10-25T10:15:02Z",
            ageMillis = 20_000L,
            confidence = "FRESH",
        ),
        VisibleLocationDto(
            ownerId = "u2",
            displayName = "Thanh Ngọc",
            latitude = 21.028,
            longitude = 105.829,
            horizontalAccuracyMeters = 24.0,
            recordedAt = "2026-10-25T10:00:00Z",
            receivedAt = "2026-10-25T10:00:03Z",
            ageMillis = 900_000L,
            confidence = "DELAYED",
        ),
    )
    AnVeTheme {
        LiveMapContent(
            state = LiveMapUiState(
                ownerId = "me",
                snapshot = VisibleLocationSnapshot(
                    ownerId = "me",
                    connected = true,
                    loading = false,
                    locations = sample,
                ),
            ),
            ownerId = "me",
            trackingStatus = TrackingStatus(consentEnabled = true, running = true, serviceRunning = true, providerActive = true),
            userDisplayName = "Minh",
            mapsConfigured = false,
            onOpenSharing = {},
            onCloseSharing = {},
            onToggleViewer = { _, _ -> },
            onRetrySharing = {},
        )
    }
}

@Preview(name = "2. Dark Mode", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun LiveMapContentDarkPreview() {
    AnVeTheme(darkTheme = true) {
        LiveMapContent(
            state = LiveMapUiState(
                ownerId = "me",
                snapshot = VisibleLocationSnapshot(
                    ownerId = "me",
                    connected = false,
                    loading = false,
                    locations = emptyList(),
                ),
            ),
            ownerId = "me",
            mapsConfigured = false,
            onOpenSharing = {},
            onCloseSharing = {},
            onToggleViewer = { _, _ -> },
            onRetrySharing = {},
        )
    }
}

@Preview(name = "3. Elderly 200% Font", fontScale = 2.0f, showBackground = true)
@Composable
private fun LiveMapContentElderlyFontPreview() {
    val sample = listOf(
        VisibleLocationDto(
            ownerId = "u1",
            displayName = "Mẹ Lan",
            latitude = 21.033,
            longitude = 105.834,
            horizontalAccuracyMeters = 8.0,
            recordedAt = "2026-10-25T10:15:00Z",
            receivedAt = "2026-10-25T10:15:02Z",
            ageMillis = 20_000L,
            confidence = "FRESH",
        ),
    )
    AnVeTheme {
        LiveMapContent(
            state = LiveMapUiState(
                ownerId = "me",
                snapshot = VisibleLocationSnapshot(
                    ownerId = "me",
                    connected = true,
                    loading = false,
                    locations = sample,
                ),
            ),
            ownerId = "me",
            trackingStatus = TrackingStatus(consentEnabled = true, running = true, serviceRunning = true, providerActive = true),
            userDisplayName = "Minh",
            mapsConfigured = false,
            onOpenSharing = {},
            onCloseSharing = {},
            onToggleViewer = { _, _ -> },
            onRetrySharing = {},
        )
    }
}
