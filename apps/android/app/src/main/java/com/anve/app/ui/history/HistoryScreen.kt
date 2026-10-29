package com.anve.app.ui.history

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anve.app.AppContainer
import com.anve.app.BuildConfig
import com.anve.app.data.api.HistoryPointDto
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope

private const val MAX_GAP_SECONDS = 900L
private const val MAX_ACCURACY_METERS = 100.0
private const val MAX_SPEED_METERS_PER_SECOND = 70.0
private enum class DeletionScope { DAY, RANGE, ALL }

private fun formatMinute(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

/** Render only observed, accurate samples that can plausibly belong to one segment. */
fun historySegments(points: List<HistoryPointDto>): List<List<HistoryPointDto>> {
    val result = mutableListOf<MutableList<HistoryPointDto>>()
    var previous: HistoryPointDto? = null
    points.forEach { point ->
        val time = runCatching { Instant.parse(point.recordedAt) }.getOrNull()
        val valid = time != null && point.latitude.isFinite() && point.longitude.isFinite() &&
            point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0 &&
            point.horizontalAccuracyMeters.isFinite() && point.horizontalAccuracyMeters in 0.0..MAX_ACCURACY_METERS && !point.isMock
        if (!valid) {
            previous = null
        } else {
            val old = previous
            val seconds = old?.let { time.epochSecond - Instant.parse(it.recordedAt).epochSecond }
            val separated = old == null || old.segmentId != point.segmentId || seconds == null ||
                seconds <= 0 || seconds > MAX_GAP_SECONDS || distanceMeters(old, point) > seconds * MAX_SPEED_METERS_PER_SECOND
            if (separated) result.add(mutableListOf())
            result.last().add(point)
            previous = point
        }
    }
    return result
}

private fun distanceMeters(a: HistoryPointDto, b: HistoryPointDto): Double {
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val deltaLat = lat2 - lat1
    val deltaLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(deltaLat / 2) * sin(deltaLat / 2) + cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
    return 12_742_000.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

@Composable
fun HistoryScreen(container: AppContainer) {
    val session by container.sessionStore.session.collectAsStateWithLifecycle(initialValue = null)
    val owner = session?.userId
    val vm: HistoryViewModel = viewModel(factory = HistoryViewModel.factory(container.historyRepository))
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(owner, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.activate(owner)
                Lifecycle.Event.ON_STOP -> vm.activate(null)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            vm.activate(null)
        }
    }
    LaunchedEffect(owner, state.member, lifecycle) {
        val member = state.member
        if (owner != null && member != null && member != owner) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    vm.recheckAccess(owner, member)
                    delay(10_000)
                }
            }
        }
    }
    if (state.owner != owner || owner == null) return
    HistoryContent(state, vm)
}

@Composable
internal fun HistoryContent(state: HistoryState, vm: HistoryViewModel) {
    var selectedId by remember(state.owner, state.member, state.date) { mutableStateOf<String?>(null) }
    val selected = state.points.firstOrNull { it.eventId == selectedId }
    var deletionScope by remember(state.owner) { mutableStateOf<DeletionScope?>(null) }
    val camera = key(state.owner) { rememberCameraPositionState() }
    var mapLoaded by remember(state.owner) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val member = state.member ?: return
    val gapStarts = remember(state.points) { historySegments(state.points).drop(1).mapNotNull { it.firstOrNull()?.eventId }.toSet() }
    LaunchedEffect(state.owner, member, state.date, state.points.isEmpty(), mapLoaded) {
        if (mapLoaded && state.points.isEmpty()) {
            camera.move(CameraUpdateFactory.newLatLngZoom(LatLng(0.0, 0.0), 1f))
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Lịch sử vị trí", style = MaterialTheme.typography.headlineSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = member == state.owner, onClick = { vm.select(state.owner!!, state.date) }, label = { Text("Của tôi") }, enabled = !state.mutationPending) }
            items(state.members, key = { it.ownerId }) { person ->
                FilterChip(selected = member == person.ownerId, onClick = { vm.select(person.ownerId, state.date) }, label = { Text(person.displayName) }, enabled = !state.mutationPending)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.select(member, state.date.minusDays(1)) }, enabled = !state.mutationPending) { Text("Ngày trước") }
            TextButton(onClick = {
                DatePickerDialog(context, { _, year, month, day ->
                    vm.select(member, LocalDate.of(year, month + 1, day))
                }, state.date.year, state.date.monthValue - 1, state.date.dayOfMonth).apply {
                    datePicker.maxDate = System.currentTimeMillis()
                    show()
                }
            }, enabled = !state.mutationPending) { Text(state.date.toString()) }
            OutlinedButton(onClick = { vm.select(member, state.date.plusDays(1)) }, enabled = !state.mutationPending && state.date < LocalDate.now()) { Text("Ngày sau") }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                OutlinedButton(onClick = {
                    TimePickerDialog(context, { _, hour, minute -> vm.selectTimes(hour * 60 + minute, state.endMinute) },
                        state.startMinute / 60, state.startMinute % 60, true).show()
                }, enabled = !state.mutationPending) { Text("Từ ${formatMinute(state.startMinute)}") }
            }
            item {
                OutlinedButton(onClick = {
                    val initial = minOf(state.endMinute, 1439)
                    TimePickerDialog(context, { _, hour, minute -> vm.selectTimes(state.startMinute, hour * 60 + minute) },
                        initial / 60, initial % 60, true).show()
                }, enabled = !state.mutationPending) { Text("Đến ${formatMinute(state.endMinute)}") }
            }
            item { TextButton(onClick = { vm.selectTimes(0, 1440) }, enabled = !state.mutationPending) { Text("Cả ngày") } }
        }
        if (BuildConfig.MAPS_API_KEY.isNotBlank()) {
            GoogleMap(modifier = Modifier.fillMaxWidth().height(220.dp), cameraPositionState = camera, onMapLoaded = { mapLoaded = true }) {
                historySegments(state.points).filter { it.size > 1 }.forEach { segment ->
                    Polyline(points = segment.map { LatLng(it.latitude, it.longitude) })
                }
                selected?.takeIf {
                    it.latitude.isFinite() && it.longitude.isFinite() &&
                        it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
                }?.let { point ->
                    val marker = remember(point.eventId, point.latitude, point.longitude) {
                        MarkerState(position = LatLng(point.latitude, point.longitude))
                    }
                    Marker(state = marker, title = point.recordedAt)
                }
            }
            TextButton(onClick = {
                val point = selected ?: state.points.lastOrNull()
                if (mapLoaded && point != null && point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0) {
                    scope.launch { camera.animate(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), 14f)) }
                }
            }) { Text("Đưa bản đồ tới điểm đã chọn") }
        } else Text("Bản đồ chưa được cấu hình; lịch sử vẫn hiển thị bên dưới.")
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.loading || state.mutationPending) CircularProgressIndicator()
        if (state.points.isEmpty() && !state.loading && state.error == null && !state.mutationPending) Text("Không có điểm lịch sử trong ngày này.")
        LazyColumn(Modifier.weight(1f)) {
            items(state.points, key = { it.eventId }) { point ->
                val text = runCatching { DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.parse(point.recordedAt)) }.getOrDefault(point.recordedAt)
                val gap = if (point.eventId in gapStarts) "Gián đoạn · " else ""
                val delayed = if (point.backfill) " · đồng bộ trễ" else ""
                val mock = if (point.isMock) " · nguồn mô phỏng" else ""
                Text("$gap$text · ±${point.horizontalAccuracyMeters.toInt()} m$delayed$mock", Modifier.fillMaxWidth().clickable { selectedId = point.eventId }.padding(12.dp))
            }
            if (state.nextCursor != null) item { TextButton(onClick = vm::next, enabled = !state.loading) { Text("Trang tiếp theo") } }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { TextButton(onClick = vm::refresh, enabled = !state.loading && !state.mutationPending) { Text("Kiểm tra lại") } }
            if (state.member == state.owner) {
                item { TextButton(onClick = { deletionScope = DeletionScope.RANGE }, enabled = !state.mutationPending) { Text("Xóa khoảng giờ") } }
                item { TextButton(onClick = { deletionScope = DeletionScope.DAY }, enabled = !state.mutationPending) { Text("Xóa ngày") } }
                item { TextButton(onClick = { deletionScope = DeletionScope.ALL }, enabled = !state.mutationPending) { Text("Xóa tất cả") } }
            }
        }
        if (state.member == state.owner) {
            Text("Thời hạn lưu: ${state.effectiveRetentionDays ?: state.retentionDays ?: "—"} ngày")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 7, 30).forEach { days ->
                    FilterChip(selected = state.retentionDays == days, onClick = { vm.changeRetention(days) }, enabled = !state.mutationPending && state.retentionDays != null, label = { Text("$days ngày") })
                }
            }
        }
    }
    deletionScope?.let { scope ->
        AlertDialog(onDismissRequest = { deletionScope = null }, title = { Text("Xóa lịch sử?") },
            text = { Text((when (scope) {
                DeletionScope.ALL -> "Xóa lịch sử đến lúc này."
                DeletionScope.DAY -> "Xóa lịch sử ngày đã chọn."
                DeletionScope.RANGE -> "Xóa lịch sử trong khoảng giờ đã chọn."
            }) + " Để bảo vệ quyền riêng tư, các điểm chưa đồng bộ trên thiết bị khác có thể bị bỏ, kể cả điểm mới ghi khi thiết bị offline.") },
            confirmButton = { Button(onClick = { deletionScope = null; vm.delete(scope == DeletionScope.ALL, scope == DeletionScope.RANGE) }) { Text("Xóa") } },
            dismissButton = { TextButton(onClick = { deletionScope = null }) { Text("Hủy") } })
    }
}
