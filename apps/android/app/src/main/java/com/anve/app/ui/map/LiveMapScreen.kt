package com.anve.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anve.app.AppContainer
import com.anve.app.BuildConfig
import com.anve.app.data.api.VisibleLocationDto
import com.anve.app.data.location.AudienceMember
import com.anve.app.data.location.VisibleLocationSnapshot
import com.anve.app.data.location.displayLocationConfidence
import com.google.android.gms.maps.CameraUpdateFactory
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun LiveMapScreen(container: AppContainer) {
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
                locations.forEach { location ->
                    val point = location.latLngOrNull() ?: return@forEach
                    key(location.ownerId) {
                        val markerState = remember(point) { MarkerState(position = point) }
                        val confidence = location.displayConfidence(snapshot, nowElapsedMillis)
                        Marker(
                            state = markerState,
                            title = location.displayName,
                            snippet = confidence.toConfidenceLabel(),
                            icon = BitmapDescriptorFactory.defaultMarker(confidence.markerHue()),
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
                message = "Bản đồ chưa được cấu hình cho bản dựng này. Bạn vẫn có thể quản lý quyền chia sẻ.",
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
                        Box(Modifier.size(8.dp).background(
                            if (snapshot?.connected == true) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                            CircleShape,
                        ))
                        Text(
                            text = if (snapshot?.connected == true) "Đang cập nhật" else "Chưa kết nối",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                OutlinedButton(onClick = onOpenSharing) {
                    Icon(Icons.Default.Group, contentDescription = null)
                    Text("  Quyền xem")
                }
            }
            snapshot?.error?.let { message ->
                Card {
                    Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        when {
            snapshot?.loading == true -> CircularProgressIndicator(
                Modifier.align(Alignment.Center),
            )
            locations.isEmpty() -> Card(Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
                Text(
                    "Chưa có thành viên nào cấp quyền xem vị trí cho bạn.",
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
            Text("Bản đồ chưa sẵn sàng", style = MaterialTheme.typography.titleMedium)
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
                Text("Cập nhật: ${location.recordedAt.toVietnameseTime()}", style = MaterialTheme.typography.bodySmall)
                location.horizontalAccuracyMeters?.let {
                    Text("Độ chính xác: ±${it.toInt()} m", style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = onRecenter, enabled = location.latLngOrNull() != null) {
                Icon(Icons.Default.Refresh, contentDescription = "Đưa bản đồ tới vị trí thành viên")
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
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Ai có thể xem vị trí của bạn?", style = MaterialTheme.typography.titleLarge)
            Text(
                "Bật chia sẻ chỉ ghi nhận sự đồng ý; hãy cấp quyền riêng cho từng người. Quyền mới chỉ áp dụng từ lúc cấp và có thể thu hồi bất cứ lúc nào.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.sharingLoading && state.sharing == null) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            }
            state.sharingError?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRetry) { Text("Kiểm tra lại") }
            }
            val sharing = state.sharing
            when {
                sharing == null -> Unit
                !sharing.hasCircle -> Text("Bạn chưa tham gia nhóm gia đình.", style = MaterialTheme.typography.bodyMedium)
                !sharing.consentEnabled -> Text(
                    "Chia sẻ vị trí đang tắt. Bật trong Tài khoản trước khi chọn người xem.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                sharing.members.none { it.userId != ownerId } -> Text(
                    "Nhóm chưa có thành viên khác.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(sharing.members.filter { it.userId != ownerId }, key = AudienceMember::userId) { member ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(member.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text(member.role, style = MaterialTheme.typography.bodySmall)
                            }
                            if (member.userId in state.unresolvedViewerIds) {
                                Text("Chưa xác định", style = MaterialTheme.typography.bodySmall)
                            } else {
                                Switch(
                                    checked = member.canViewLocation,
                                    enabled = sharing.consentEnabled && member.userId !in state.pendingViewerIds,
                                    onCheckedChange = { enabled -> onToggle(member, enabled) },
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

private fun String.markerHue(): Float = when (this) {
    "FRESH" -> BitmapDescriptorFactory.HUE_GREEN
    "DELAYED" -> BitmapDescriptorFactory.HUE_YELLOW
    "STALE" -> BitmapDescriptorFactory.HUE_RED
    "OFFLINE" -> BitmapDescriptorFactory.HUE_AZURE
    else -> BitmapDescriptorFactory.HUE_VIOLET
}

private fun String.toConfidenceLabel(): String = when (this) {
    "FRESH" -> "Vị trí mới"
    "DELAYED" -> "Vị trí cập nhật chậm"
    "STALE" -> "Vị trí đã cũ"
    "OFFLINE" -> "Ngoại tuyến"
    else -> "Chưa có vị trí"
}

private fun String?.toVietnameseTime(): String {
    if (this == null) return "chưa có dữ liệu"
    return try {
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.forLanguageTag("vi"))
            .withZone(ZoneId.systemDefault())
            .format(Instant.parse(this))
    } catch (_: DateTimeParseException) {
        this
    }
}

private val DEFAULT_CAMERA_TARGET = LatLng(16.0, 107.5)
private const val DEFAULT_CAMERA_ZOOM = 5.0f
