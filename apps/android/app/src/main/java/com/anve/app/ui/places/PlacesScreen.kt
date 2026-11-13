package com.anve.app.ui.places

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anve.app.AppContainer
import com.anve.app.BuildConfig
import com.anve.app.data.api.PlaceDto
import com.anve.app.data.api.PlaceInputDto
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@Composable
fun PlacesScreen(container: AppContainer) {
    val session by container.sessionStore.session.collectAsStateWithLifecycle(initialValue = null)
    val owner = session?.userId
    val vm: PlacesViewModel = viewModel(factory = PlacesViewModel.factory(container.placesRepository))
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
        onDispose { lifecycle.removeObserver(observer); vm.activate(null) }
    }
    LaunchedEffect(owner, lifecycle) {
        if (owner != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(10_000)
                vm.refresh()
            }
        }
    }
    if (owner == null || state.owner != owner) return
    PlacesContent(state, vm)
}

@Composable
internal fun PlacesContent(state: PlacesState, vm: PlacesViewModel) {
    val owner = state.owner ?: return
    val selected = state.places.firstOrNull { it.id == state.selectedId }
    var showDetails by remember(owner) { mutableStateOf(false) }
    val visibleEvents = if (showDetails) state.events.filter { it.placeId == selected?.id } else state.events
    var editing by remember(owner) { mutableStateOf(false) }
    var editingPlace by remember(owner) { mutableStateOf<PlaceDto?>(null) }
    var editConfirmation by remember(owner) { mutableStateOf(state.confirmation) }
    var deleteId by remember(owner) { mutableStateOf<String?>(null) }
    var showActions by remember(selected?.id) { mutableStateOf(false) }
    var recipients by remember(owner, selected?.id, state.monitor) {
        mutableStateOf(state.monitor?.recipientIds?.toSet() ?: emptySet())
    }
    val editorAuthorized = editingPlace == null ||
        (state.error == null && state.places.any { it.id == editingPlace?.id })
    LaunchedEffect(editorAuthorized) {
        if (!editorAuthorized) {
            editing = false
            editingPlace = null
        }
    }
    LaunchedEffect(state.confirmation, owner) {
        if (editing && state.confirmation > editConfirmation) editing = false
    }
    BackHandler(enabled = showDetails || editing) {
        if (editing) {
            editing = false
            editingPlace = null
        } else {
            showDetails = false
        }
    }
    if (editing && editorAuthorized) {
        PlaceEditor(
            editingPlace,
            state.pending,
            state.notice,
            onCancel = { editing = false; editingPlace = null },
            onSave = { input ->
                editConfirmation = state.confirmation
                vm.save(editingPlace?.id, input)
            },
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (showDetails) {
                    IconButton(onClick = { showDetails = false }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Quay lại địa điểm")
                    }
                }
                Text(
                    if (showDetails && selected != null) selected.name else "Địa điểm",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                if (showDetails && selected?.ownerId == owner) {
                    Box {
                        IconButton(onClick = { showActions = true }, enabled = !state.pending) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Thao tác địa điểm")
                        }
                        DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                            DropdownMenuItem(
                                text = { Text("Sửa địa điểm") },
                                onClick = {
                                    showActions = false
                                    editingPlace = selected
                                    editConfirmation = state.confirmation
                                    editing = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Xóa địa điểm") },
                                onClick = { showActions = false; deleteId = selected.id },
                            )
                        }
                    }
                }
            }
        }
        if (!showDetails) {
            item { Text("Theo dõi dùng các mẫu vị trí đã chia sẻ; sự kiện có thể trễ hoặc bị bỏ lỡ.") }
            state.notice?.let { notice -> item { Text(notice, color = MaterialTheme.colorScheme.error) } }
            state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            editingPlace = null
                            editConfirmation = state.confirmation
                            editing = true
                        },
                        enabled = !state.pending,
                    ) { Text("Thêm địa điểm") }
                    TextButton(onClick = vm::refresh, enabled = !state.pending && !state.loading) {
                        Text("Làm mới")
                    }
                }
            }
            if (state.loading || state.pending) item { CircularProgressIndicator() }
            if (state.places.isEmpty() && !state.loading && state.error == null) {
                item {
                    Text(
                        "Chưa có địa điểm nào. Thêm một địa điểm để tự quản lý các vùng đã lưu.",
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            items(state.places, key = { it.id }) { place ->
                val label = if (place.ownerId == owner) {
                    "Của tôi"
                } else {
                    state.sharing.members.firstOrNull { it.userId == place.ownerId }?.displayName ?: "Thành viên"
                }
                ListItem(
                    headlineContent = { Text(place.name) },
                    supportingContent = { Text("$label · bán kính ${place.radiusMeters.toInt()} m") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !state.pending) {
                            vm.select(place.id)
                            showDetails = true
                        },
                    trailingContent = {
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    },
                )
                HorizontalDivider()
            }
        } else if (selected == null) {
            state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            item { Text("Địa điểm không còn khả dụng. Làm mới danh sách để kiểm tra lại.") }
            item {
                TextButton(onClick = { showDetails = false; vm.refresh() }, enabled = !state.loading) {
                    Text("Làm mới danh sách")
                }
            }
        } else {
            item { Text("Bán kính ${selected.radiusMeters.toInt()} m", style = MaterialTheme.typography.titleMedium) }
            if (BuildConfig.MAPS_API_KEY.isNotBlank()) {
                item(key = "preview-${selected.id}") { PlacePreview(selected) }
            }
            state.notice?.let { notice -> item { Text(notice, color = MaterialTheme.colorScheme.error) } }
            state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            if (state.loading || state.pending) item { CircularProgressIndicator() }
            if (selected.ownerId == owner) {
                item { Text("Chia sẻ địa điểm với", style = MaterialTheme.typography.titleMedium) }
                item {
                    Text("Thành viên vẫn phải tự bật theo dõi cho bản thân; chia sẻ địa điểm không cấp quyền xem sự kiện.")
                }
                items(state.sharing.members.filter { it.userId != owner }, key = { "access-${it.userId}" }) { person ->
                    ListItem(
                        headlineContent = { Text(person.displayName) },
                        supportingContent = { Text("Quyền xem địa điểm") },
                        trailingContent = {
                            Switch(
                                checked = person.userId in state.access,
                                enabled = !state.pending && !state.loading,
                                onCheckedChange = { vm.share(selected.id, person.userId, it) },
                            )
                        },
                    )
                }
            }
            item { Text("Theo dõi vị trí của tôi tại đây", style = MaterialTheme.typography.titleMedium) }
            item {
                Text("Chỉ bạn bật theo dõi cho mình. Người nhận cần được bạn cấp quyền vị trí và được chia sẻ địa điểm này.")
            }
            items(
                state.sharing.members.filter { it.userId != owner && it.userId in state.eligibleRecipients },
                key = { "recipient-${it.userId}" },
            ) { person ->
                ListItem(
                    headlineContent = { Text(person.displayName) },
                    supportingContent = { Text("Nhận thông báo sự kiện tại địa điểm này") },
                    trailingContent = {
                        Checkbox(
                            checked = person.userId in recipients,
                            enabled = !state.pending,
                            onCheckedChange = { checked ->
                                recipients = if (checked) recipients + person.userId else recipients - person.userId
                            },
                        )
                    },
                )
            }
            item {
                Text("Bật hoặc đổi quyền theo dõi có thể loại các điểm offline chưa đồng bộ.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.configure(selected.id, recipients.intersect(state.eligibleRecipients)) },
                        enabled = !state.pending && state.sharing.consentEnabled,
                    ) {
                        Text(if (state.monitor == null) "Bật theo dõi" else "Lưu người nhận")
                    }
                    if (state.monitor != null) {
                        TextButton(onClick = { vm.stop(selected.id) }, enabled = !state.pending) { Text("Dừng") }
                    }
                }
            }
        }
        if (!showDetails || selected != null) {
            item { Text("Sự kiện", style = MaterialTheme.typography.titleMedium) }
            if (visibleEvents.isEmpty() && !state.loading && state.error == null) {
                item { Text("Chưa có sự kiện được phép xem.") }
            }
            items(visibleEvents, key = { "event-${it.id}" }) { event ->
                val at = runCatching {
                    DateTimeFormatter.ofPattern("dd/MM HH:mm")
                        .withZone(ZoneId.systemDefault())
                        .format(Instant.parse(event.recordedAt))
                }.getOrDefault(event.recordedAt)
                val transition = when (event.type) {
                    "ENTER" -> "Đến"
                    "EXIT" -> "Rời"
                    else -> "Không rõ"
                }
                val delayed = if (event.delayed) " · xử lý trễ" else ""
                val confidence = if (event.confidence == "OBSERVED") " · nhiều mẫu GPS" else " · độ tin cậy chưa rõ"
                ListItem(
                    headlineContent = { Text("${event.ownerName} · $transition ${event.placeName}") },
                    supportingContent = { Text("$at$delayed$confidence") },
                )
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
    deleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text("Xóa địa điểm?") },
            text = { Text("Các cấu hình theo dõi và sự kiện liên quan sẽ bị xóa.") },
            confirmButton = {
                Button(onClick = {
                    deleteId = null
                    showDetails = false
                    vm.delete(id)
                }) { Text("Xóa") }
            },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Hủy") } },
        )
    }
}

@Composable
private fun PlacePreview(place: PlaceDto) {
    val point = LatLng(place.latitude, place.longitude)
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(point, 14f) }
    GoogleMap(modifier = Modifier.fillMaxWidth().height(200.dp), cameraPositionState = camera) {
        val marker = remember(place.id, place.latitude, place.longitude) { MarkerState(position = point) }
        Marker(state = marker, title = place.name)
        Circle(center = point, radius = place.radiusMeters, strokeColor = Color(0xff3563d9), fillColor = Color(0x223563d9))
    }
}

@Composable
private fun PlaceEditor(place: PlaceDto?, pending: Boolean, notice: String?, onCancel: () -> Unit, onSave: (PlaceInputDto) -> Unit) {
    var name by remember(place?.id) { mutableStateOf(place?.name ?: "") }
    var latitude by remember(place?.id) { mutableStateOf(place?.latitude?.toString() ?: "") }
    var longitude by remember(place?.id) { mutableStateOf(place?.longitude?.toString() ?: "") }
    var radius by remember(place?.id) { mutableDoubleStateOf(place?.radiusMeters ?: 150.0) }
    val lat = latitude.toDoubleOrNull()
    val lon = longitude.toDoubleOrNull()
    val valid = validPlaceInput(name, lat, lon, radius)
    val point = if (lat != null && lon != null && lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) LatLng(lat, lon) else null
    val camera = rememberCameraPositionState {
        position = if (point == null) CameraPosition.fromLatLngZoom(LatLng(0.0, 0.0), 1f) else CameraPosition.fromLatLngZoom(point, 14f)
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(if (place == null) "Thêm địa điểm" else "Sửa địa điểm", style = MaterialTheme.typography.headlineSmall) }
        item { OutlinedTextField(name, { name = it }, label = { Text("Tên địa điểm") }, enabled = !pending, modifier = Modifier.fillMaxWidth()) }
        if (BuildConfig.MAPS_API_KEY.isNotBlank()) {
            item {
                GoogleMap(modifier = Modifier.fillMaxWidth().height(260.dp), cameraPositionState = camera, onMapClick = {
                    if (!pending) { latitude = it.latitude.toString(); longitude = it.longitude.toString() }
                }) {
                    point?.let {
                        val marker = remember(it.latitude, it.longitude) { MarkerState(position = it) }
                        Marker(state = marker, title = name)
                        Circle(center = it, radius = radius, strokeColor = Color(0xff3563d9), fillColor = Color(0x223563d9))
                    }
                }
            }
            item { Text("Chạm bản đồ để chọn vị trí.") }
        } else item { Text("Bản đồ chưa được cấu hình; nhập tọa độ bên dưới.") }
        item { OutlinedTextField(latitude, { latitude = it }, label = { Text("Vĩ độ") }, enabled = !pending, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(longitude, { longitude = it }, label = { Text("Kinh độ") }, enabled = !pending, modifier = Modifier.fillMaxWidth()) }
        item { Text("Bán kính ${radius.toInt()} m") }
        item { Slider(value = radius.toFloat(), onValueChange = { radius = it.toDouble() }, valueRange = 50f..5000f, enabled = !pending) }
        if (place != null) item { Text("Thay đổi địa điểm sẽ dừng các cấu hình theo dõi hiện tại; mỗi người cần bật lại.") }
        notice?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (pending) item { CircularProgressIndicator() }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (valid && lat != null && lon != null) onSave(PlaceInputDto(name.trim(), lat, lon, radius)) }, enabled = valid && !pending) { Text("Lưu") }
                TextButton(onClick = onCancel, enabled = !pending) { Text("Hủy") }
            }
        }
    }
}
