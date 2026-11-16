package com.anve.app.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anve.app.AppContainer
import com.anve.app.BuildConfig
import com.anve.app.R
import com.anve.app.data.api.VisibleLocationDto
import com.anve.app.data.location.AudienceMember
import com.anve.app.data.location.VisibleLocationSnapshot
import com.anve.app.data.location.displayLocationConfidence
import com.anve.app.tracking.AndroidLocationPermissions
import com.anve.app.tracking.LocationPermissionState
import com.anve.app.ui.brand.AnVeIllustratedBanner
import com.anve.app.ui.brand.AnVeSafeRadarScene
import com.anve.app.ui.brand.AnVeSharingHeroIllustration
import com.anve.app.ui.common.AnVeSectionHeader
import com.anve.app.ui.common.AnveErrorNotice
import com.anve.app.ui.common.AnveTime
import com.anve.app.ui.common.StitchBadgeKind
import com.anve.app.ui.common.StitchLivePulseDot
import com.anve.app.ui.common.StitchLoadingSkeletonCard
import com.anve.app.ui.common.StitchSemanticBadge
import com.anve.app.ui.common.anveContentWidth
import com.anve.app.ui.home.HOME_FAB_CLEARANCE
import com.anve.app.ui.theme.AnVeTheme
import com.anve.app.ui.theme.LocalStitchColors
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun LiveMapScreen(
    container: AppContainer,
    onOpenLocation: () -> Unit = {},
    onOpenCircle: () -> Unit = {},
    onOpenJourney: () -> Unit = {},
    openSharingRequest: Boolean = false,
    onSharingRequestConsumed: () -> Unit = {},
) {
    val viewModel: LiveMapViewModel = viewModel(
        factory = LiveMapViewModel.factory(container.visibleLocationRepository),
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
        mapsConfigured = BuildConfig.MAPS_API_KEY.isNotBlank(),
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
    mapsConfigured: Boolean,
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
    val context = LocalContext.current
    val permissions = remember(context) { AndroidLocationPermissions(context) }
    var myLocationLayerEnabled by remember { mutableStateOf(false) }
    var selectedOwner by rememberSaveable(ownerId) { mutableStateOf<String?>(null) }
    var sheetExpanded by rememberSaveable(ownerId) { mutableStateOf(false) }
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
            // The blue dot is the device's own position, read locally by the
            // Maps SDK: it follows the OS location permission, not consent —
            // consent stays the gate for anything recorded or uploaded. Read
            // fresh on every START because permissions can be revoked while
            // the app runs (AndroidLocationPermissions contract), and only a
            // precise grant enables the layer (coarse alone throws).
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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxSheetHeight = (maxHeight * 0.56f).coerceAtLeast(180.dp)
        if (mapsConfigured) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraState,
                properties = MapProperties(
                    isBuildingEnabled = false,
                    isMyLocationEnabled = myLocationLayerEnabled,
                ),
                uiSettings = MapUiSettings(
                    compassEnabled = true,
                    zoomControlsEnabled = false,
                    // The Maps SDK locate button sits bottom-right, where the
                    // status and member cards live; the dot itself answers
                    // "where am I" without a second recenter control fighting
                    // them for space.
                    myLocationButtonEnabled = false,
                    mapToolbarEnabled = false,
                ),
                onMapLoaded = { mapLoaded = true },
            ) {
                locations.forEach { location ->
                    val point = location.latLngOrNull() ?: return@forEach
                    key(location.ownerId) {
                        val markerState = remember(point) { MarkerState(position = point) }
                        val confidence = location.displayConfidence(snapshot, nowElapsedMillis)
                        val initial = location.displayName.trim().take(1).uppercase().ifBlank { "?" }
                        val pinIcon = rememberMemberMarkerIcon(initial = initial, confidence = confidence)
                        Marker(
                            state = markerState,
                            title = location.displayName,
                            snippet = confidence.toConfidenceLabel(),
                            icon = pinIcon,
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
                                fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                strokeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
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
                    if (points.size == 1) {
                        cameraState.move(CameraUpdateFactory.newLatLngZoom(points.single(), 14f))
                    } else {
                        val bounds = LatLngBounds.builder().apply { points.forEach(::include) }.build()
                        cameraState.move(CameraUpdateFactory.newLatLngBounds(bounds, 96))
                    }
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
                    .anveContentWidth()
                    .padding(top = 76.dp, start = 16.dp, end = 16.dp, bottom = 140.dp),
            )
        }

        val colors = MaterialTheme.colorScheme
        val stitch = LocalStitchColors.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .anveContentWidth()
                .align(Alignment.TopCenter)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = stitch.cardTactile,
                    border = BorderStroke(1.dp, stitch.hairline),
                    shadowElevation = 2.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Freshness, not danger: an unconnected feed borrows a
                        // neutral tone — the error hue is reserved for failures
                        // (DESIGN.md stale-data rule).
                        StitchLivePulseDot(
                            active = snapshot?.connected == true,
                            haloSize = 14.dp,
                            coreSize = 7.dp,
                        )
                        Text(
                            text = if (snapshot?.connected == true) stringResource(R.string.map_status_updating) else stringResource(R.string.map_status_disconnected),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                FilledTonalButton(
                    onClick = onOpenSharing,
                    shape = CircleShape,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = colors.secondaryContainer,
                        contentColor = colors.onSecondaryContainer,
                    ),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.Group, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.map_audience_action))
                }
            }
            if (mapsConfigured && locations.isNotEmpty()) {
                val targetLocation = locations.firstOrNull { it.ownerId == selectedOwner } ?: locations.firstOrNull()
                Surface(
                    onClick = {
                        targetLocation?.latLngOrNull()?.let { point ->
                            cameraState.move(CameraUpdateFactory.newLatLngZoom(point, 15f))
                        }
                    },
                    shape = CircleShape,
                    color = stitch.cardTactile,
                    contentColor = colors.primary,
                    border = BorderStroke(1.dp, stitch.hairline),
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .align(Alignment.End)
                        .size(48.dp),
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
            snapshot?.error?.let { message ->
                // Same failure presentation as the other screens: the shared
                // errorContainer notice with a retry action that restarts the poll.
                AnveErrorNotice(message, onRetry = onRetryLocation)
            }
        }

        val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        when {
            snapshot == null || snapshot.loading -> Surface(
                shape = sheetShape,
                color = stitch.cardTactile,
                border = BorderStroke(1.dp, stitch.hairline),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .anveContentWidth()
                    .heightIn(max = maxSheetHeight),
            ) {
                Box(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 64.dp)) {
                    StitchLoadingSkeletonCard(rows = 2)
                }
            }
            locations.isEmpty() -> Surface(
                shape = sheetShape,
                color = stitch.cardTactile,
                border = BorderStroke(1.dp, stitch.hairline),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .anveContentWidth()
                    .heightIn(max = maxSheetHeight),
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = HOME_FAB_CLEARANCE),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(width = 36.dp, height = 4.dp)
                            .background(stitch.hairline, CircleShape),
                    )
                    if (snapshot.error != null) {
                        AnveErrorNotice(snapshot.error, onRetry = onRetryLocation)
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(R.string.map_pulse_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.semantics { heading() },
                                    )
                                    StitchLivePulseDot(active = snapshot.connected, haloSize = 14.dp, coreSize = 6.dp)
                                }
                                Text(
                                    text = stringResource(R.string.map_pulse_count, 0),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.map_no_locations_notice),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
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
            else -> {
                val toggleDesc = stringResource(R.string.map_pulse_toggle_content_desc)
                val expandStateDesc = stringResource(
                    if (sheetExpanded) R.string.action_state_expanded else R.string.action_state_collapsed,
                )
                Surface(
                    shape = sheetShape,
                    color = stitch.cardTactile,
                    border = BorderStroke(1.dp, stitch.hairline),
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .anveContentWidth()
                        .heightIn(max = maxSheetHeight)
                        .align(Alignment.BottomCenter),
                ) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(
                                start = 16.dp,
                                top = 4.dp,
                                end = 16.dp,
                                bottom = if (sheetExpanded) HOME_FAB_CLEARANCE else 14.dp,
                            ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(CircleShape)
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = toggleDesc,
                                ) { sheetExpanded = !sheetExpanded }
                                .semantics {
                                    contentDescription = toggleDesc
                                    stateDescription = expandStateDesc
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 36.dp, height = 4.dp)
                                    .background(stitch.hairline, CircleShape),
                            )
                        }
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(R.string.map_pulse_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.semantics { heading() },
                                    )
                                    StitchLivePulseDot(active = snapshot.connected, haloSize = 14.dp, coreSize = 6.dp)
                                }
                                Text(
                                    text = stringResource(R.string.map_pulse_count, locations.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                            FilledTonalButton(
                                onClick = onOpenJourney,
                                shape = CircleShape,
                                modifier = Modifier.heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            ) {
                                Icon(Icons.Outlined.Home, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.map_start_journey_short), maxLines = 1)
                            }
                        }
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                        ) {
                            items(locations, key = VisibleLocationDto::ownerId) { location ->
                                val confidence = location.displayConfidence(snapshot, nowElapsedMillis)
                                val selected = location.ownerId == selectedOwner
                                MemberShelfPillCard(
                                    location = location,
                                    confidence = confidence,
                                    selected = selected,
                                    onSelect = {
                                        selectedOwner = location.ownerId
                                        location.latLngOrNull()?.let { point ->
                                            cameraState.move(CameraUpdateFactory.newLatLngZoom(point, 15f))
                                        }
                                    },
                                )
                            }
                        }
                        AnimatedVisibility(visible = sheetExpanded) {
                            locations.firstOrNull { it.ownerId == selectedOwner }?.let { selected ->
                                MemberLocationCard(
                                    location = selected,
                                    confidence = selected.displayConfidence(snapshot, nowElapsedMillis),
                                    modifier = Modifier.fillMaxWidth(),
                                    onRecenter = {
                                        selected.latLngOrNull()?.let { point ->
                                            cameraState.move(CameraUpdateFactory.newLatLngZoom(point, 15f))
                                        }
                                    },
                                )
                            }
                        }
                        // Reserve right-side clearance so the docked SOS pill
                        // never overlaps the details toggle or expanded card.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 112.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable(role = Role.Button) { sheetExpanded = !sheetExpanded }
                                    .heightIn(min = 48.dp)
                                    .padding(horizontal = 8.dp)
                                    .semantics { stateDescription = expandStateDesc },
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(
                                        if (sheetExpanded) R.string.map_pulse_collapse_details else R.string.map_pulse_expand_details,
                                    ),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colors.primary,
                                )
                                Icon(
                                    imageVector = if (sheetExpanded) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
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
            Text(stringResource(R.string.map_unavailable_title), style = MaterialTheme.typography.titleMedium)
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
private fun MemberShelfPillCard(
    location: VisibleLocationDto,
    confidence: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    val statusDotColor = when (confidence) {
        "FRESH" -> stitch.livePulse
        "DELAYED" -> stitch.onTransitContainer
        else -> colors.outline
    }
    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) stitch.liveContainer.copy(alpha = 0.72f) else colors.surfaceContainerLow,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) colors.primary else stitch.hairline,
        ),
        modifier = Modifier
            .widthIn(min = 144.dp, max = 260.dp)
            .heightIn(min = 48.dp)
            .semantics { this.selected = selected },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = colors.primaryContainer,
                contentColor = colors.onPrimaryContainer,
                modifier = Modifier.size(32.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = location.displayName.take(1).uppercase().ifBlank { "?" },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = location.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(statusDotColor),
                    )
                    Text(
                        text = confidence.toConfidenceLabel(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) colors.onSurface else colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun MemberLocationCard(
    location: VisibleLocationDto,
    confidence: String,
    modifier: Modifier = Modifier,
    onRecenter: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, stitch.hairline),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = colors.primaryContainer,
                contentColor = colors.onPrimaryContainer,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = location.displayName.take(1).uppercase().ifBlank { "?" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = location.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    StitchSemanticBadge(
                        text = confidence.toConfidenceLabel(),
                        kind = confidence.toBadgeKind(),
                        showDot = confidence == "FRESH",
                    )
                }
                Text(
                    stringResource(R.string.map_updated_at, location.recordedAt.toVietnameseTime(stringResource(R.string.map_time_unavailable))),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                location.horizontalAccuracyMeters?.let {
                    Text(
                        stringResource(R.string.map_accuracy, it.toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            FilledTonalIconButton(
                onClick = onRecenter,
                enabled = location.latLngOrNull() != null,
                modifier = Modifier.size(48.dp),
            ) {
                // A refresh glyph reads as "reload the data"; MyLocation is the
                // camera-recenter convention on map surfaces.
                Icon(Icons.Outlined.MyLocation, contentDescription = stringResource(R.string.map_recenter_content_desc))
            }
        }
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
                    icon = Icons.Outlined.Group,
                    title = stringResource(R.string.map_audience_title),
                )
            }
            // One fact per line: the old 60+ word paragraph asked readers to
            // hold consent, timing and offline behavior at once.
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = colors.surfaceVariant.copy(alpha = 0.42f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(stringResource(R.string.map_audience_explainer_consent), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.map_audience_explainer_timing), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.map_audience_explainer_offline), style = MaterialTheme.typography.bodySmall)
                    }
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
                    // Same failure presentation as the rest of the app; the sheet
                    // keeps its own retry below, so the notice takes no second retry.
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
                // The two setup notices carry their own action: a pointer that
                // only names the screen leaves the reader to close the sheet
                // and hunt for the link underneath it.
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
                    items(sharing.members.filter { it.userId != ownerId }, key = AudienceMember::userId) { member ->
                        // The whole row toggles so TalkBack announces the member's
                        // name together with the switch state instead of an
                        // anonymous "on"; toggleable sits before padding so the
                        // touch target covers the padded row (>= 48dp). The inner
                        // switch stays indicative; unresolved members have no
                        // control yet, so their row stays non-interactive.
                        OutlinedCard(
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, colors.outlineVariant),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
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
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = colors.secondaryContainer,
                                    contentColor = colors.onSecondaryContainer,
                                    modifier = Modifier.size(38.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = member.displayName.take(1).uppercase().ifBlank { "?" },
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(member.displayName, style = MaterialTheme.typography.bodyLarge)
                                    // role ships as a raw "owner"/"member" contract
                                    // string; mapping it here instead of in the data
                                    // layer keeps AudienceMember faithful to the server
                                    // payload while reusing the Circle screen's
                                    // standardized labels, so one concept keeps one wording.
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
                                            icon = if (member.canViewLocation) Icons.Outlined.Visibility else null,
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
}

private fun VisibleLocationDto.latLngOrNull(): LatLng? {
    val lat = latitude ?: return null
    val lon = longitude ?: return null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    return LatLng(lat, lon)
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

// Each family member pin is drawn as a warm circular badge with their initial
// and a semantic ring color: deep teal for FRESH, warm amber for DELAYED,
// neutral gray for STALE/unknown (age is never danger), and slate-teal for
// OFFLINE.
@Composable
private fun rememberMemberMarkerIcon(initial: String, confidence: String): BitmapDescriptor {
    val density = LocalDensity.current.density
    return remember(initial, confidence, density) { memberMarkerIcon(initial, confidence, density) }
}

private fun memberMarkerIcon(initial: String, confidence: String, density: Float): BitmapDescriptor {
    val widthPx = (MEMBER_MARKER_WIDTH_DP * density).roundToInt().coerceAtLeast(1)
    val heightPx = (MEMBER_MARKER_HEIGHT_DP * density).roundToInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    bitmap.density = Bitmap.DENSITY_NONE
    val canvas = Canvas(bitmap)
    val cx = widthPx / 2f
    val radius = widthPx / 2f - 2f * density
    val circleCenterY = radius + 2f * density
    val tipY = heightPx - 1f * density
    val drop = tipY - circleCenterY
    val badgeColor = when (confidence) {
        "FRESH" -> MEMBER_MARKER_TEAL
        "DELAYED" -> MEMBER_MARKER_AMBER
        "OFFLINE" -> MEMBER_MARKER_SLATE
        else -> MEMBER_MARKER_GRAY
    }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MEMBER_MARKER_SAND }
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = badgeColor }
    val tail = Path().apply {
        moveTo(cx - radius * 0.72f, circleCenterY + radius * 0.45f)
        lineTo(cx, tipY)
        lineTo(cx + radius * 0.72f, circleCenterY + radius * 0.45f)
        close()
    }
    canvas.drawPath(tail, borderPaint)
    canvas.drawCircle(cx, circleCenterY, radius + 2f * density, borderPaint)
    canvas.drawCircle(cx, circleCenterY, radius, fillPaint)
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MEMBER_MARKER_TEXT
        textAlign = Paint.Align.CENTER
        textSize = 15f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val textY = circleCenterY - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(initial, cx, textY, textPaint)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

private const val MEMBER_MARKER_WIDTH_DP = 36f
private const val MEMBER_MARKER_HEIGHT_DP = 44f
private val MEMBER_MARKER_TEAL = 0xFF3D602F.toInt()
private val MEMBER_MARKER_AMBER = 0xFFC87A28.toInt()
private val MEMBER_MARKER_SLATE = 0xFF695B60.toInt()
private val MEMBER_MARKER_GRAY = 0xFF74796D.toInt()
private val MEMBER_MARKER_SAND = 0xFFFDF9F2.toInt()
private val MEMBER_MARKER_TEXT = 0xFFFFFFFF.toInt()

private fun String.toBadgeKind(): StitchBadgeKind = when (this) {
    "FRESH" -> StitchBadgeKind.SAFE_HOME
    "DELAYED" -> StitchBadgeKind.IN_TRANSIT
    else -> StitchBadgeKind.DELAYED_OFFLINE
}

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

@Preview(showBackground = true)
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
            displayName = "Em Huy",
            latitude = 21.028,
            longitude = 105.829,
            horizontalAccuracyMeters = 24.0,
            recordedAt = "2026-10-25T10:12:00Z",
            receivedAt = "2026-10-25T10:12:03Z",
            ageMillis = 180_000L,
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
            mapsConfigured = false,
            onOpenSharing = {},
            onCloseSharing = {},
            onToggleViewer = { _, _ -> },
            onRetrySharing = {},
        )
    }
}

@Preview(showBackground = true)
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
