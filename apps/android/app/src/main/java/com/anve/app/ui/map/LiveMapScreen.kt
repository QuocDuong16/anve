package com.anve.app.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import com.anve.app.ui.common.AnveErrorNotice
import com.anve.app.ui.common.AnveTime
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
fun LiveMapScreen(container: AppContainer, onOpenLocation: () -> Unit = {}, onOpenAccount: () -> Unit = {}, onOpenCircle: () -> Unit = {}) {
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

    LiveMapContent(
        state = state.takeIf { it.ownerId == ownerId } ?: LiveMapUiState(ownerId = ownerId),
        ownerId = ownerId,
        mapsConfigured = BuildConfig.MAPS_API_KEY.isNotBlank(),
        onOpenSharing = { viewModel.showSharing(true, ownerId) },
        onCloseSharing = { viewModel.showSharing(false, ownerId) },
        onToggleViewer = { member, enabled -> viewModel.toggleViewer(ownerId, member, enabled) },
        onRetrySharing = { viewModel.refreshSharing(ownerId) },
        onOpenLocation = onOpenLocation,
        onOpenAccount = onOpenAccount,
        onOpenCircle = onOpenCircle,
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
    onOpenAccount: () -> Unit = {},
    onOpenCircle: () -> Unit = {},
) {
    val snapshot = state.snapshot.takeIf { state.ownerId == ownerId }
    val locations = snapshot?.locations.orEmpty()
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
                properties = MapProperties(isBuildingEnabled = false),
                uiSettings = MapUiSettings(
                    compassEnabled = true,
                    zoomControlsEnabled = false,
                    myLocationButtonEnabled = false,
                    mapToolbarEnabled = false,
                ),
                onMapLoaded = { mapLoaded = true },
            ) {
                val staleMarkerIcon = rememberStaleMarkerIcon()
                locations.forEach { location ->
                    val point = location.latLngOrNull() ?: return@forEach
                    key(location.ownerId) {
                        val markerState = remember(point) { MarkerState(position = point) }
                        val confidence = location.displayConfidence(snapshot, nowElapsedMillis)
                        Marker(
                            state = markerState,
                            title = location.displayName,
                            snippet = confidence.toConfidenceLabel(),
                            icon = confidence.confidenceMarker(staleMarkerIcon),
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
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
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
                OutlinedButton(onClick = onOpenSharing) {
                    Icon(Icons.Default.Group, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.map_audience_action))
                }
            }
            // The home shell hides the sharing-settings card on the map tab so
            // its overlay does not eat viewing space, which left this tab with
            // no visible route to the screen where sharing decisions are made.
            // One compact text pointer restores the path without re-adding the
            // card; the label reuses the destination's own title.
            TextButton(
                onClick = onOpenLocation,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.tracking_title))
            }
            // Same reasoning for the account screen: with the sharing card
            // hidden on this tab, sessions and account actions need their own
            // compact pointer.
            TextButton(
                onClick = onOpenAccount,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.tab_account))
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
            locations.isEmpty() -> Card(Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
                Text(
                    stringResource(R.string.map_no_locations_notice),
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            else -> {
                Column(
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(12.dp),
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
private fun MapUnavailableCard(message: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.map_unavailable_title), style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodyMedium)
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
    Card(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(location.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(confidence.toConfidenceLabel(), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.map_updated_at, location.recordedAt.toVietnameseTime(stringResource(R.string.map_time_unavailable))), style = MaterialTheme.typography.bodySmall)
                location.horizontalAccuracyMeters?.let {
                    Text(stringResource(R.string.map_accuracy, it.toInt()), style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = onRecenter, enabled = location.latLngOrNull() != null) {
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.map_audience_title), style = MaterialTheme.typography.titleLarge)
            // One fact per line: the old 60+ word paragraph asked readers to
            // hold consent, timing and offline behavior at once.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.map_audience_explainer_consent), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.map_audience_explainer_timing), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.map_audience_explainer_offline), style = MaterialTheme.typography.bodyMedium)
            }
            if (state.sharingLoading && state.sharing == null) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            }
            state.sharingErrorRes?.let { res ->
                // Same failure presentation as the rest of the app; the sheet
                // keeps its own retry below, so the notice takes no second retry.
                AnveErrorNotice(stringResource(res))
                TextButton(onClick = onRetry) { Text(stringResource(R.string.map_retry)) }
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
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(sharing.members.filter { it.userId != ownerId }, key = AudienceMember::userId) { member ->
                        // The whole row toggles so TalkBack announces the member's
                        // name together with the switch state instead of an
                        // anonymous "on"; toggleable sits before padding so the
                        // touch target covers the padded row (>= 48dp). The inner
                        // switch stays indicative; unresolved members have no
                        // control yet, so their row stays non-interactive.
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
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
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

// STALE renders as a neutral gray pin: the age of a location says nothing
// about whether someone is safe or in danger, so it must not borrow an alarm
// hue like red. DELAYED keeps yellow, FRESH green and OFFLINE azure.
private fun String.confidenceMarker(staleIcon: BitmapDescriptor): BitmapDescriptor = when (this) {
    "FRESH" -> BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
    "DELAYED" -> BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_YELLOW)
    "STALE" -> staleIcon
    "OFFLINE" -> BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)
    // Unknown confidence has no visual story of its own; it falls back to the
    // neutral gray pin instead of introducing an off-palette hue.
    else -> staleIcon
}

@Composable
private fun rememberStaleMarkerIcon(): BitmapDescriptor {
    val density = LocalDensity.current.density
    return remember(density) { staleMarkerIcon(density) }
}

// The Maps SDK hue ramp cannot express a neutral gray — every defaultMarker
// hue stays fully saturated — so the stale pin is drawn as its own bitmap,
// shaped like the default marker and anchored at its bottom tip.
private fun staleMarkerIcon(density: Float): BitmapDescriptor {
    val widthPx = (STALE_MARKER_WIDTH_DP * density).roundToInt().coerceAtLeast(1)
    val heightPx = (STALE_MARKER_HEIGHT_DP * density).roundToInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    bitmap.density = Bitmap.DENSITY_NONE
    val canvas = Canvas(bitmap)
    val cx = widthPx / 2f
    val radius = widthPx / 2f - 1f
    val circleCenterY = radius + 1f
    val tipY = heightPx - 1f
    val drop = tipY - circleCenterY
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = STALE_MARKER_GRAY }
    val tail = Path().apply {
        moveTo(cx - radius, circleCenterY)
        cubicTo(cx - radius, circleCenterY + drop * 0.4f, cx, tipY, cx, tipY)
        cubicTo(cx, tipY, cx + radius, circleCenterY + drop * 0.4f, cx + radius, circleCenterY)
        close()
    }
    canvas.drawCircle(cx, circleCenterY, radius, fill)
    canvas.drawPath(tail, fill)
    canvas.drawCircle(
        cx,
        circleCenterY,
        radius * STALE_MARKER_HOLE_RATIO,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = STALE_MARKER_HOLE },
    )
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

// Default marker proportions (~22x37dp).
private const val STALE_MARKER_WIDTH_DP = 22f
private const val STALE_MARKER_HEIGHT_DP = 37f
private const val STALE_MARKER_HOLE_RATIO = 0.36f
private val STALE_MARKER_GRAY = 0xFF757575.toInt()
private val STALE_MARKER_HOLE = 0xFFFFFFFF.toInt()

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
