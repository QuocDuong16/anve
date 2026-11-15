package com.anve.app.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
import com.anve.app.ui.brand.AnVePlacesSceneIllustration
import com.anve.app.ui.brand.AnVeSharingHeroIllustration
import com.anve.app.ui.common.AnVeSectionHeader
import com.anve.app.ui.common.AnVeStatusPill
import com.anve.app.ui.common.AnveErrorNotice
import com.anve.app.ui.common.AnveTime
import com.anve.app.ui.home.HOME_FAB_CLEARANCE
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

    LaunchedEffect(ownerId, lifecycleOwner) {
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
    onOpenLocation: () -> Unit = {},
    onOpenCircle: () -> Unit = {},
    onOpenJourney: () -> Unit = {},
) {
    val snapshot = state.snapshot.takeIf { state.ownerId == ownerId }
    val locations = snapshot?.locations.orEmpty()
    val context = LocalContext.current
    val permissions = remember(context) { AndroidLocationPermissions(context) }
    var myLocationLayerEnabled by remember { mutableStateOf(false) }
    var selectedOwner by remember(ownerId) { mutableStateOf<String?>(null) }
    var cameraInitialized by remember(ownerId) { mutableStateOf(false) }
    var mapLoaded by remember(ownerId) { mutableStateOf(false) }
    var nowElapsedMillis by remember { mutableLongStateOf(monotonicNowMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraState = key(ownerId) {
        rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(DEFAULT_CAMERA_TARGET, DEFAULT_CAMERA_ZOOM)
        }
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

    Box(Modifier.fillMaxSize()) {
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

            if (!mapLoaded) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        } else {
            MapUnavailableCard(
                message = stringResource(R.string.map_unconfigured_message),
                onOpenSharing = onOpenSharing,
                onOpenLocation = onOpenLocation,
                modifier = Modifier.align(Alignment.Center).padding(20.dp),
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Card {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Freshness, not danger: an unconnected feed borrows a
                        // neutral tone — the error hue is reserved for failures
                        // (DESIGN.md stale-data rule).
                        Box(Modifier.size(8.dp).background(
                            if (snapshot?.connected == true) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            CircleShape,
                        ))
                        Text(
                            text = if (snapshot?.connected == true) stringResource(R.string.map_status_updating) else stringResource(R.string.map_status_disconnected),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                FilledTonalButton(
                    onClick = onOpenSharing,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.map_audience_action))
                }
            }
            snapshot?.error?.let { message ->
                // Same failure presentation as the other screens: the shared
                // errorContainer notice, not a bare text card.
                AnveErrorNotice(message)
            }
        }

        when {
            snapshot?.loading == true -> CircularProgressIndicator(
                Modifier.align(Alignment.Center),
            )
            locations.isEmpty() -> Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = HOME_FAB_CLEARANCE),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.map_no_locations_notice),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        OutlinedButton(onClick = onOpenSharing, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.map_audience_action))
                        }
                        TextButton(onClick = onOpenLocation, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.action_open_location))
                        }
                    }
                }
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(start = 12.dp, end = 12.dp, bottom = HOME_FAB_CLEARANCE),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(locations, key = VisibleLocationDto::ownerId) { location ->
                            FilterChip(
                                selected = location.ownerId == selectedOwner,
                                onClick = { selectedOwner = location.ownerId },
                                label = { Text(location.displayName) },
                            )
                        }
                    }
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
                    Button(
                        onClick = onOpenJourney,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary,
                        ),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.map_start_journey))
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
    onOpenSharing: () -> Unit = {},
    onOpenLocation: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    OutlinedCard(
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, colors.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = colors.surface),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnVePlacesSceneIllustration(
                modifier = Modifier.fillMaxWidth().height(120.dp),
            )
            Text(stringResource(R.string.map_unavailable_title), style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onOpenSharing,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.map_audience_action))
                }
                FilledTonalButton(
                    onClick = onOpenLocation,
                    shape = RoundedCornerShape(12.dp),
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
    location: VisibleLocationDto,
    confidence: String,
    modifier: Modifier = Modifier,
    onRecenter: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pillColors = when (confidence) {
        "FRESH" -> colors.primaryContainer to colors.onPrimaryContainer
        "DELAYED" -> colors.secondaryContainer to colors.onSecondaryContainer
        else -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    OutlinedCard(
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, colors.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = colors.surface),
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(location.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    AnVeStatusPill(
                        text = confidence.toConfidenceLabel(),
                        containerColor = pillColors.first,
                        contentColor = pillColors.second,
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
                Icon(Icons.Default.MyLocation, contentDescription = stringResource(R.string.map_recenter_content_desc))
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
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AnVeSharingHeroIllustration(
                active = state.sharing?.consentEnabled ?: true,
                modifier = Modifier.fillMaxWidth().height(96.dp),
            )
            AnVeSectionHeader(
                icon = Icons.Default.Group,
                title = stringResource(R.string.map_audience_title),
            )
            // One fact per line: the old 60+ word paragraph asked readers to
            // hold consent, timing and offline behavior at once.
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
            if (state.sharingLoading && state.sharing == null) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            }
            state.sharingErrorRes?.let { res ->
                // Same failure presentation as the rest of the app; the sheet
                // keeps its own retry below, so the notice takes no second retry.
                AnveErrorNotice(stringResource(res))
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.map_retry))
                }
            }
            val sharing = state.sharing
            when {
                sharing == null -> Unit
                // The two setup notices carry their own action: a pointer that
                // only names the screen leaves the reader to close the sheet
                // and hunt for the link underneath it.
                !sharing.hasCircle -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.map_no_circle), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onOpenCircle, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.map_open_circle))
                    }
                }
                !sharing.consentEnabled -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.map_consent_off),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onOpenLocation, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.action_open_location))
                    }
                }
                sharing.members.none { it.userId != ownerId } -> Text(
                    stringResource(R.string.map_no_other_members),
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                Column(Modifier.weight(1f)) {
                                    Text(member.displayName, style = MaterialTheme.typography.bodyLarge)
                                    // role ships as a raw "owner"/"member" contract
                                    // string; mapping it here instead of in the data
                                    // layer keeps AudienceMember faithful to the server
                                    // payload while reusing the Circle screen's
                                    // standardized labels, so one concept keeps one wording.
                                    Text(
                                        text = if (member.role == "owner") {
                                            stringResource(R.string.circle_role_owner)
                                        } else {
                                            stringResource(R.string.circle_role_member)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant,
                                    )
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
            Spacer(Modifier.height(8.dp))
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
private val MEMBER_MARKER_TEAL = 0xFF00505A.toInt()
private val MEMBER_MARKER_AMBER = 0xFFD98E32.toInt()
private val MEMBER_MARKER_SLATE = 0xFF4A7C88.toInt()
private val MEMBER_MARKER_GRAY = 0xFF757575.toInt()
private val MEMBER_MARKER_SAND = 0xFFFBF7F0.toInt()
private val MEMBER_MARKER_TEXT = 0xFFFBF7F0.toInt()

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
