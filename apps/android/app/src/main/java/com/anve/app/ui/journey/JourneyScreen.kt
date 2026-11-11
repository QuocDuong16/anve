package com.anve.app.ui.journey

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anve.app.AppContainer
import com.anve.app.data.api.JourneyDto
import com.anve.app.data.api.PlaceDto
import com.anve.app.data.journey.JourneyAction
import com.anve.app.tracking.MotionState
import com.anve.app.tracking.TrackingStatus
import com.anve.app.ui.theme.AnVeTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun JourneyScreen(container: AppContainer, onBack: () -> Unit, onPlaces: () -> Unit, onLocation: () -> Unit, journeyId: String? = null) {
    val model: JourneyViewModel = viewModel(key = "journey-$journeyId", factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            JourneyViewModel(container.sessionStore, container.journeyRepository, container.placesRepository, container.trackingManager, journeyId) as T
    })
    val state by model.state.collectAsStateWithLifecycle()
    val tracking by container.trackingManager.status.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> model.visible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
        lifecycle.addObserver(observer)
        model.visible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); model.visible(false) }
    }
    key(state.owner) {
        JourneyContent(state, tracking, onBack, model::reload, model::select, model::create, model::action, model::resumePriority, onPlaces, onLocation)
    }
}

private data class Confirmation(val journey: JourneyDto, val action: JourneyAction, val recipient: String? = null, val name: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JourneyContent(
    state: JourneyUiState,
    tracking: TrackingStatus,
    onBack: () -> Unit = {},
    onReload: () -> Unit = {},
    onSelect: (String?) -> Unit = {},
    onCreate: (PlaceDto, Int, Set<String>) -> Unit = { _, _, _ -> },
    onAction: (String, JourneyAction, String?) -> Unit = { _, _, _ -> },
    onResume: (String) -> Unit = {},
    onPlaces: () -> Unit = {},
    onLocation: () -> Unit = {},
    initiallyCreating: Boolean = false,
) {
    var creating by remember(state.owner) { mutableStateOf(initiallyCreating) }
    var confirmation by remember(state.owner) { mutableStateOf<Confirmation?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(10_000) } }
    val selected = state.journeys.firstOrNull { it.id == state.selectedId }
    LaunchedEffect(selected?.id) { if (selected != null) creating = false }
    BackHandler { if (creating) creating = false else onBack() }
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (creating) "Bắt đầu hành trình" else "Hành trình") },
            navigationIcon = { IconButton(onClick = { if (creating) creating = false else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại") } },
            actions = { IconButton(onClick = onReload, enabled = !state.loading && !state.busy) { Icon(Icons.Default.Refresh, "Đọc lại trạng thái") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = if (dark) colors.primaryContainer else colors.primary,
                titleContentColor = if (dark) colors.onPrimaryContainer else colors.onPrimary,
                navigationIconContentColor = if (dark) colors.onPrimaryContainer else colors.onPrimary,
                actionIconContentColor = if (dark) colors.onPrimaryContainer else colors.onPrimary),
        )
    }) { padding ->
        LazyColumn(Modifier.padding(padding).testTag("journey-content"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.loading || state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { error -> item { JourneyNotice(error) { JourneyTextAction("Thử đọc lại", onReload, !state.busy) } } }
            if (!tracking.online && !state.loading && (selected == null || creating)) item {
                JourneyNotice("Mất mạng: dữ liệu có thể cũ. Việc kết thúc cần máy chủ xác nhận.")
            }
            items(state.pending, key = { it.operationId }) { operation ->
                JourneyNotice("Máy chủ chưa xác nhận ${when (operation.action) {
                    JourneyAction.CREATE -> "bắt đầu hành trình"
                    JourneyAction.ARRIVE -> "đã đến nơi"
                    JourneyAction.END -> "kết thúc hành trình"
                    JourneyAction.CANCEL -> "hủy hành trình"
                    JourneyAction.REVOKE_RECIPIENT -> "thu hồi người xem"
                }}. Ưu tiên trên thiết bị không được tự khôi phục.") {
                    JourneyTextAction("Kiểm tra lại", onReload, !state.busy)
                    if (operation.action != JourneyAction.CANCEL) JourneyTextAction("Hủy hành trình đang chờ", { onAction(operation.journeyId, JourneyAction.CANCEL, null) }, !state.busy)
                }
            }
            when {
                creating -> item {
                    JourneyCreateForm(state, tracking, onCreate, onPlaces, onLocation)
                }
                selected != null -> item {
                    JourneyDetails(selected, state.owner == selected.ownerId, tracking, now, state.busy,
                        pending = state.pending.any { it.journeyId == selected.id },
                        onConfirm = { action, recipient, name -> confirmation = Confirmation(selected, action, recipient, name) },
                        onResume = { onResume(selected.id) })
                }
                !state.loading -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Chủ động chia sẻ một hành trình", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                        Text("Chọn điểm đến và người thân được theo dõi. Bạn tự xác nhận khi đã đến nơi.")
                        JourneyPrimaryAction("Bắt đầu hành trình", { creating = true }, !state.busy && state.pending.isEmpty())
                    }
                }
            }
            val remaining = state.journeys.filter { it.id != selected?.id }
            if (remaining.isNotEmpty()) item { Text("Hành trình được phép xem", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) }
            items(remaining, key = { it.id }) { journey ->
                OutlinedCard(onClick = { onSelect(journey.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${if (journey.ownerId == state.owner) "Của bạn" else journey.ownerName} · ${journey.destination.label}", style = MaterialTheme.typography.titleMedium)
                        Text(journeyStateLabel(journey.state), style = MaterialTheme.typography.bodyMedium)
                        Text("Bắt đầu ${journeyTime(journey.startedAt)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (selected != null && selected.state != "ACTIVE" && selected.ownerId == state.owner) item {
                JourneyPrimaryAction("Bắt đầu hành trình mới", { onSelect(null); creating = true }, !state.busy && state.pending.isEmpty())
            }
        }
    }
    confirmation?.let { choice ->
        val title = when (choice.action) {
            JourneyAction.ARRIVE -> "Xác nhận đã đến nơi?"
            JourneyAction.REVOKE_RECIPIENT -> "Ngừng cho ${choice.name} theo dõi?"
            else -> "Kết thúc hành trình?"
        }
        val label = when (choice.action) {
            JourneyAction.ARRIVE -> "Đã đến nơi"
            JourneyAction.REVOKE_RECIPIENT -> "Thu hồi người xem"
            else -> "Kết thúc hành trình"
        }
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(title) },
            text = { Text(when (choice.action) {
                JourneyAction.ARRIVE -> "Bạn xác nhận đã đến ${choice.journey.destination.label}. Những người bạn đã chọn theo dõi sẽ nhận sự kiện đến nơi khi máy chủ ghi nhận."
                JourneyAction.REVOKE_RECIPIENT -> "Quyền theo dõi hành trình này sẽ bị thu hồi khi máy chủ ghi nhận. Quyền xem vị trí thông thường được quản lý riêng."
                else -> "Hành trình sẽ kết thúc mà không xác nhận đã đến nơi. Ưu tiên hành trình dừng trên thiết bị ngay; chia sẻ vị trí thông thường được quản lý riêng."
            }) },
            dismissButton = { JourneyTextAction("Quay lại", { confirmation = null }) },
            confirmButton = { JourneyTextAction(label, { confirmation = null; onAction(choice.journey.id, choice.action, choice.recipient) }, !state.busy) },
        )
    }
}

@Composable
private fun JourneyDetails(journey: JourneyDto, owner: Boolean, tracking: TrackingStatus, now: Long, busy: Boolean, pending: Boolean,
    onConfirm: (JourneyAction, String?, String?) -> Unit, onResume: () -> Unit) {
    var people by remember(journey.id) { mutableStateOf(false) }
    val active = journey.state == "ACTIVE"
    val expiredLocally = runCatching { Instant.parse(journey.expiresAt).toEpochMilli() <= now }.getOrDefault(true)
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Default.Home, null, Modifier.padding(14.dp).size(28.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column {
                Text(journey.destination.label, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text("Tự kết thúc ${journeyTime(journey.expiresAt)}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Column {
            JourneyStep("Đã bắt đầu · ${journeyTime(journey.startedAt)}", completed = true)
            JourneyStep(if (active) "Hành trình đang diễn ra" else journeyStateLabel(journey.state), current = active, completed = !active) {
                val progress = journey.progress
                if (progress == null) Text(if (active) "Chưa có vị trí sau khi bắt đầu." else "Ưu tiên hành trình đã kết thúc.", style = MaterialTheme.typography.bodyMedium)
                else {
                    val age = ((now - Instant.parse(progress.recordedAt).toEpochMilli()).coerceAtLeast(0) / 60_000)
                    Text("Vị trí cập nhật ${if (age == 0L) "dưới 1 phút trước" else "$age phút trước"}")
                    Text("Khoảng ${String.format(Locale.forLanguageTag("vi-VN"), "%.1f", progress.distanceMeters / 1000)} km đường chim bay")
                    Text("Độ chính xác khoảng ${progress.accuracyMeters.toInt()} m${if (progress.isMock) " · Vị trí mô phỏng" else ""}", style = MaterialTheme.typography.bodySmall)
                }
                if (active) Text("Khoảng cách không phải thời gian đến.", style = MaterialTheme.typography.bodySmall)
            }
            JourneyStep(if (journey.state == "ARRIVED") "Chủ hành trình đã xác nhận đến nơi" else "Xác nhận đến nơi", last = true, completed = journey.state == "ARRIVED") {
                Text(if (active) "Bạn chủ động xác nhận khi đã đến." else "Không tự suy diễn đến nơi từ GPS.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (expiredLocally && active) JourneyNotice("Đã đến thời hạn trên thiết bị. Đọc lại để xác nhận trạng thái máy chủ; ưu tiên không được kéo dài.")
        if (owner) {
            Column {
                Text("Người theo dõi: ${journey.recipients.joinToString { it.displayName }.ifBlank { "Không còn người xem có quyền" }}")
                JourneyTextAction(if (people) "Thu gọn người xem" else "Quản lý người xem", { people = !people })
                if (people) journey.recipients.forEach { recipient ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(recipient.displayName, Modifier.weight(1f))
                        JourneyTextAction("Thu hồi", { onConfirm(JourneyAction.REVOKE_RECIPIENT, recipient.userId, recipient.displayName) }, !busy && !pending)
                    }
                }
            }
            if (active) {
                if (!tracking.online) JourneyNotice("Mất mạng: dữ liệu có thể cũ. Việc kết thúc cần máy chủ xác nhận.")
                if (tracking.motion != MotionState.JOURNEY && !pending && !expiredLocally) {
                    JourneyTextAction("Tiếp tục ưu tiên hành trình", onResume, !busy && tracking.activelyTracking && tracking.online)
                }
                JourneyPrimaryAction("Đã đến nơi", { onConfirm(JourneyAction.ARRIVE, null, null) }, !busy && !pending && !expiredLocally)
                OutlinedButton(onClick = { onConfirm(JourneyAction.END, null, null) }, enabled = !busy && !pending,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = actionInk()), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Default.Close, null); Spacer(Modifier.width(8.dp)); Text("Kết thúc hành trình")
                }
                Text("Kết thúc hành trình dừng ưu tiên. Bạn quản lý chia sẻ vị trí thông thường ở màn Vị trí.", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            if (!tracking.online) JourneyNotice("Mất mạng: dữ liệu có thể cũ. Đọc lại khi có mạng.")
            Text("Chỉ chủ hành trình xác nhận đến nơi hoặc kết thúc. Dữ liệu cũ không chứng minh người đó gặp sự cố.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun JourneyStep(title: String, current: Boolean = false, completed: Boolean = false, last: Boolean = false, content: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(32.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (completed) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null, Modifier.size(32.dp), tint = actionInk())
            if (!last) VerticalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        }
        Surface(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 24.dp),
            color = if (current) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(if (current) 12.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                content()
            }
        }
    }
}

@Composable
private fun JourneyCreateForm(state: JourneyUiState, tracking: TrackingStatus, onCreate: (PlaceDto, Int, Set<String>) -> Unit, onPlaces: () -> Unit, onLocation: () -> Unit) {
    var destination by remember(state.owner) { mutableStateOf<String?>(null) }
    var minutes by remember(state.owner) { mutableStateOf(120) }
    var recipients by remember(state.owner) { mutableStateOf(emptySet<String>()) }
    val ready = !state.busy && state.pending.isEmpty() && state.consentEnabled && tracking.ownerUserId == state.owner && tracking.activelyTracking && tracking.online
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("1. Chọn điểm đến", style = MaterialTheme.typography.titleMedium)
            if (state.places.isEmpty()) Text("Chưa có địa điểm được phép chọn. Tạo hoặc chia sẻ địa điểm trước khi bắt đầu.")
            state.places.forEach { place ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(destination == place.id, role = Role.RadioButton, onClick = { destination = place.id }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(destination == place.id, onClick = null)
                    Text(place.name, Modifier.padding(start = 8.dp))
                }
            }
            JourneyTextAction("Quản lý địa điểm", onPlaces)
        } }
        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("2. Tự kết thúc sau", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60, 120, 240, 480).forEach { value ->
                    FilterChip(minutes == value, onClick = { minutes = value }, label = { Text(if (value < 60) "$value phút" else "${value / 60} giờ") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer))
                }
            }
            Text("Đây là thời hạn chia sẻ, không phải giờ dự kiến đến.", style = MaterialTheme.typography.bodySmall)
        } }
        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("3. Chọn người theo dõi", style = MaterialTheme.typography.titleMedium)
            Text("Chỉ hiện người bạn đã cấp quyền xem vị trí.", style = MaterialTheme.typography.bodySmall)
            if (state.members.isEmpty()) Text("Chưa có người được cấp quyền. Mở màn Vị trí để chọn người xem trước.")
            state.members.forEach { member ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(member.userId in recipients, role = Role.Checkbox,
                    onClick = { recipients = if (member.userId in recipients) recipients - member.userId else recipients + member.userId }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(member.userId in recipients, onCheckedChange = null)
                    Text(member.displayName, Modifier.padding(start = 8.dp))
                }
            }
        } }
        JourneyNotice("Bạn sẽ tự xác nhận khi đến nơi. Hành trình không tự phát hiện sự cố.")
        if (!state.consentEnabled || !tracking.activelyTracking) {
            JourneyNotice("Cần bật chia sẻ và thu thập vị trí trước khi bắt đầu. Hành trình không tự bật GPS.") { JourneyTextAction("Mở màn Vị trí", onLocation) }
        }
        JourneyPrimaryAction("Bắt đầu hành trình", {
            val place = state.places.firstOrNull { it.id == destination } ?: return@JourneyPrimaryAction
            onCreate(place, minutes, recipients)
        }, ready && destination != null && recipients.isNotEmpty() && recipients.all { id -> state.members.any { it.userId == id } })
        Text("Cần có mạng để bắt đầu.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun JourneyNotice(text: String, actions: @Composable () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    Surface(color = if (dark) colors.secondary else colors.secondaryContainer, contentColor = if (dark) colors.onSecondary else colors.onSecondaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(text); actions() }
    }
}

@Composable
private fun actionInk() = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary

@Composable
private fun JourneyTextAction(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick, enabled = enabled, colors = ButtonDefaults.textButtonColors(contentColor = actionInk()), modifier = Modifier.heightIn(min = 48.dp)) { Text(text) }
}

@Composable
private fun JourneyPrimaryAction(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    Button(onClick, enabled = enabled, colors = ButtonDefaults.buttonColors(containerColor = if (dark) colors.primaryContainer else colors.primary,
        contentColor = if (dark) colors.onPrimaryContainer else colors.onPrimary), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(text)
    }
}

private fun journeyTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("HH:mm · dd/MM").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault("chưa rõ thời điểm")

private fun journeyStateLabel(state: String): String = when (state) {
    "ACTIVE" -> "Đang trong hành trình"
    "ARRIVED" -> "Chủ hành trình đã xác nhận đến nơi"
    "ENDED" -> "Đã kết thúc, chưa xác nhận đến nơi"
    "CANCELLED" -> "Đã hủy hoặc không còn người xem có quyền"
    "EXPIRED" -> "Đã hết thời hạn"
    else -> "Cần đọc lại trạng thái"
}

@Preview
@Composable
private fun JourneyEmptyPreview() { AnVeTheme { JourneyContent(JourneyUiState(owner = "preview", loading = false), TrackingStatus()) } }
