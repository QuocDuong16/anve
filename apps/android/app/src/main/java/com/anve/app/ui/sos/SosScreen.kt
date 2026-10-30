package com.anve.app.ui.sos

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anve.app.AppContainer
import com.anve.app.data.sos.FirebaseSetup
import com.anve.app.data.sos.SosLocalState
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@Composable
fun SosScreen(container: AppContainer, eventId: String? = null) {
    val session by container.sessionStore.session.collectAsStateWithLifecycle(initialValue = null)
    val owner = session?.userId
    val vm: SosViewModel = viewModel(factory = SosViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    var permission by remember(owner) { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var explainPermission by remember(owner) { mutableStateOf(false) }
    var pushMessage by remember(owner) { mutableStateOf<String?>(null) }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (permission && owner != null) {
            if (FirebaseSetup.initialize(context)) { container.pushRegistrationScheduler.schedule(owner); pushMessage = "Đang đăng ký thiết bị." }
            else pushMessage = "Dịch vụ thông báo chưa được cấu hình trên bản cài đặt này."
        }
    }
    DisposableEffect(owner, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { vm.activate(owner); permission = NotificationManagerCompat.from(context).areNotificationsEnabled() }
                Lifecycle.Event.ON_STOP -> vm.activate(null)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); vm.activate(null) }
    }
    LaunchedEffect(owner, lifecycle) {
        if (owner != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { delay(10_000); vm.refresh() }
        }
    }
    LaunchedEffect(owner, eventId, state.owner) { if (owner != null && state.owner == owner && eventId != null) vm.select(eventId) }
    if (owner == null || state.owner != owner) return
    SosContent(state, vm, permission, pushMessage, onNotifications = { explainPermission = true }, onSystemSettings = {
        val intent = if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(intent) }.onFailure { pushMessage = "Không thể mở cài đặt; hãy bật thông báo trong cài đặt ứng dụng." }
    })
    if (explainPermission) AlertDialog(
        onDismissRequest = { explainPermission = false },
        title = { Text("Thông báo sự kiện") },
        text = { Text("Cho phép thông báo để thiết bị có thể nhận tín hiệu SOS và sự kiện địa điểm. AnVề không gọi dịch vụ cấp cứu thay bạn.") },
        confirmButton = { Button(onClick = {
            explainPermission = false
            if (Build.VERSION.SDK_INT >= 33 && !permission) permissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            else if (FirebaseSetup.initialize(context)) { container.pushRegistrationScheduler.schedule(owner); pushMessage = "Đang đăng ký thiết bị." }
            else pushMessage = "Dịch vụ thông báo chưa được cấu hình trên bản cài đặt này."
        }) { Text("Tiếp tục") } },
        dismissButton = { TextButton(onClick = { explainPermission = false }) { Text("Để sau") } },
    )
}

@Composable
internal fun SosContent(state: SosState, vm: SosViewModel, notificationPermission: Boolean, pushMessage: String?, onNotifications: () -> Unit, onSystemSettings: () -> Unit = {}) {
    val owner = state.owner ?: return
    var selected by remember(owner) { mutableStateOf(emptySet<String>()) }
    var confirmSend by remember(owner) { mutableStateOf(false) }
    var action by remember(owner) { mutableStateOf<Pair<String, String>?>(null) }
    var revokeRecipient by remember(owner) { mutableStateOf<Pair<String, String>?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Yêu cầu hỗ trợ", style = MaterialTheme.typography.headlineSmall) }
        item { Text("AnVề không tự gọi 113, 114 hoặc 115. Hãy gọi dịch vụ cấp cứu nếu cần.") }
        item { Text(when (state.pushConfigured) {
            true -> "Dịch vụ push đã được cấu hình; việc này không xác nhận thông báo đã đến người nhận."
            false -> "Máy chủ chưa cấu hình thông báo push. Hãy liên hệ trực tiếp với người cần hỗ trợ."
            null -> "Chưa kiểm tra được dịch vụ push. Hãy liên hệ trực tiếp nếu cần hỗ trợ khẩn cấp."
        }) }
        if (state.loading || state.pending) item { CircularProgressIndicator() }
        state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        if (state.offline) item { Text("Đang dùng danh sách đã lưu; quyền thành viên sẽ được kiểm tra khi kết nối lại.") }
        item { Text("Chọn người nhận", style = MaterialTheme.typography.titleMedium) }
        if (state.members.isEmpty()) item { Text("Không có danh sách người nhận. Hãy kết nối để tải thành viên Circle.") }
        items(state.members, key = { it.userId }) { member ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(member.displayName, Modifier.weight(1f))
                Checkbox(checked = member.userId in selected, enabled = !state.pending,
                    onCheckedChange = { checked -> selected = if (checked) selected + member.userId else selected - member.userId })
            }
        }
        item {
            Button(onClick = { confirmSend = true }, enabled = !state.pending && selected.isNotEmpty()) { Text("Gửi yêu cầu SOS") }
        }
        item { Text("Lời nhắn không đính kèm vị trí và không bật GPS.") }
        item { Text("Thông báo", style = MaterialTheme.typography.titleMedium) }
        item { Text(if (!notificationPermission) "Thông báo đang tắt hoặc chưa được cấp quyền." else when (state.pushRegistered) {
            true -> "Thiết bị đã đăng ký nhận thông báo."
            false -> "Thiết bị chưa đăng ký nhận thông báo."
            null -> "Chưa kiểm tra được đăng ký thiết bị."
        }) }
        pushMessage?.let { message -> item { Text(message) } }
        item { OutlinedButton(onClick = onNotifications) { Text("Thiết lập thông báo") } }
        if (!notificationPermission) item { TextButton(onClick = onSystemSettings) { Text("Mở cài đặt thông báo") } }
        item { Text("Yêu cầu trên thiết bị", style = MaterialTheme.typography.titleMedium) }
        items(state.outbox, key = { "local-${it.eventId}" }) { item ->
            Column {
                Text(when (item.state) {
                    SosLocalState.QUEUED -> "Đang chờ mạng"
                    SosLocalState.SENDING -> "Đang gửi"
                    SosLocalState.UNKNOWN -> "Chưa biết kết quả gửi"
                    SosLocalState.CONFIRMED -> "Máy chủ đã ghi nhận"
                    SosLocalState.CANCEL_REQUESTED -> "Chưa xác nhận được việc hủy"
                    SosLocalState.CANCELLED -> "Đã hủy"
                    SosLocalState.RESOLVED -> "Đã kết thúc"
                    SosLocalState.EXPIRED -> "Yêu cầu chưa gửi đã hết hạn"
                    SosLocalState.SERVER_EXPIRED -> "Sự kiện đã hết hạn trên máy chủ"
                })
                if (item.state in setOf(SosLocalState.QUEUED, SosLocalState.SENDING, SosLocalState.UNKNOWN, SosLocalState.CONFIRMED)) {
                    TextButton(onClick = { action = item.eventId to "cancel" }, enabled = !state.pending) { Text("Hủy yêu cầu") }
                }
                if (item.state == SosLocalState.CANCEL_REQUESTED) {
                    TextButton(onClick = { vm.recheckCancel(item.eventId) }, enabled = !state.pending) { Text("Kiểm tra lại việc hủy") }
                }
                if (item.state == SosLocalState.UNKNOWN) {
                    TextButton(onClick = { vm.recheckUnknown(item.eventId) }, enabled = !state.pending) { Text("Kiểm tra lại yêu cầu") }
                }
                if (item.state in setOf(SosLocalState.CANCELLED, SosLocalState.RESOLVED, SosLocalState.EXPIRED, SosLocalState.SERVER_EXPIRED)) {
                    TextButton(onClick = { vm.clearFinished(item.eventId) }, enabled = !state.pending) { Text("Xóa khỏi thiết bị") }
                }
            }
        }
        item { Text("Sự kiện", style = MaterialTheme.typography.titleMedium) }
        if (state.events.isEmpty() && !state.loading) item { Text("Chưa có sự kiện có thể xem.") }
        items(state.events, key = { "event-${it.id}" }) { event ->
            val person = if (event.ownerId == owner) "Bạn" else state.members.firstOrNull { it.userId == event.ownerId }?.displayName ?: "Thành viên"
            Text("$person · ${sosTime(event.recordedAt)} · ${sosState(event.state)}", Modifier.fillMaxWidth().clickable { vm.select(event.id) }.padding(vertical = 8.dp))
        }
        state.selected?.let { event ->
            item { Text("Chi tiết sự kiện", style = MaterialTheme.typography.titleLarge) }
            item {
                val sender = if (event.ownerId == owner) "Bạn" else state.members.firstOrNull { it.userId == event.ownerId }?.displayName ?: "Thành viên (chưa tải tên)"
                Text("Người gửi: $sender")
            }
            item { Text("Thời điểm yêu cầu: ${sosTime(event.recordedAt)} (${sosElapsed(event.recordedAt)})\nMáy chủ ghi nhận: ${sosTime(event.receivedAt)}\nTrạng thái: ${sosState(event.state)}") }
            if (event.ownerId == owner) {
                items(state.recipients, key = { "status-${it.userId}" }) { recipient ->
                    val person = state.members.firstOrNull { it.userId == recipient.userId }?.displayName ?: "Thành viên"
                    Column {
                        val push = when (recipient.pushState) {
                            "QUEUED" -> if (recipient.acceptedAt != null) "Push đã được chấp nhận trên ít nhất một thiết bị; còn lượt đang chờ" else "Đang chờ gửi push"
                            "ACCEPTED" -> "Dịch vụ push đã chấp nhận"
                            "SUPPRESSED" -> if (recipient.acceptedAt != null) "Đã dừng gửi; một lượt trước đã được chấp nhận" else "Đã dừng gửi push"
                            "EXPIRED" -> if (recipient.acceptedAt != null) "Push hết hạn; một lượt trước đã được chấp nhận" else "Push đã hết hạn"
                            else -> "Chưa rõ trạng thái push"
                        }
                        Text("$person · $push · ${if (recipient.acknowledgedAt != null) "Đã xác nhận" else "Chưa xác nhận"}")
                        TextButton(onClick = { revokeRecipient = event.id to recipient.userId }, enabled = !state.pending) { Text("Thu hồi quyền xem của $person") }
                    }
                }
                if (event.state == "ACTIVE") item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { action = event.id to "cancel" }) { Text("Hủy") }
                        TextButton(onClick = { action = event.id to "resolve" }) { Text("Kết thúc") }
                    }
                }
            } else if (event.state == "ACTIVE" && event.acknowledgedAt == null) item {
                Button(onClick = { action = event.id to "acknowledge" }) { Text("Tôi đã biết") }
            }
        }
        item { TextButton(onClick = { vm.refresh() }, enabled = !state.loading && !state.pending) { Text("Kiểm tra lại") } }
    }
    if (confirmSend) AlertDialog(onDismissRequest = { confirmSend = false }, title = { Text("Gửi yêu cầu hỗ trợ?") },
        text = { Text("Yêu cầu sẽ được gửi đến ${selected.size} người đã chọn khi có kết nối. Không đính kèm vị trí.") },
        confirmButton = { Button(onClick = { confirmSend = false; vm.send(selected); selected = emptySet() }) { Text("Xác nhận gửi") } },
        dismissButton = { TextButton(onClick = { confirmSend = false }) { Text("Quay lại") } })
    action?.let { (id, kind) -> AlertDialog(onDismissRequest = { action = null }, title = { Text(when (kind) { "cancel" -> "Hủy yêu cầu?"; "resolve" -> "Kết thúc yêu cầu?"; else -> "Xác nhận đã biết?" }) },
        text = { Text("Trạng thái sẽ được xác nhận với máy chủ khi có kết nối.") },
        confirmButton = { Button(onClick = { action = null; when (kind) { "cancel" -> vm.cancel(id); "resolve" -> vm.resolve(id); else -> vm.acknowledge(id) } }) { Text("Xác nhận") } },
        dismissButton = { TextButton(onClick = { action = null }) { Text("Quay lại") } }) }
    revokeRecipient?.let { (event, recipient) -> AlertDialog(
        onDismissRequest = { revokeRecipient = null },
        title = { Text("Thu hồi quyền xem?") },
        text = { Text("Người này sẽ không xem được sự kiện hoặc nhận thông báo chưa gửi. Thông báo đã gửi không thể thu hồi.") },
        confirmButton = { Button(onClick = { revokeRecipient = null; vm.revokeRecipient(event, recipient) }) { Text("Thu hồi") } },
        dismissButton = { TextButton(onClick = { revokeRecipient = null }) { Text("Quay lại") } },
    ) }
}

private fun sosTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)

private fun sosElapsed(value: String): String = runCatching {
    val minutes = Duration.between(Instant.parse(value), Instant.now()).toMinutes().coerceAtLeast(0)
    when {
        minutes < 1 -> "vừa gửi"
        minutes < 60 -> "$minutes phút trước"
        minutes < 1440 -> "${minutes / 60} giờ trước"
        else -> "${minutes / 1440} ngày trước"
    }
}.getOrDefault("không rõ thời gian")

private fun sosState(value: String): String = when (value) {
    "ACTIVE" -> "Đang mở"
    "CANCELLED" -> "Đã hủy"
    "RESOLVED" -> "Đã kết thúc"
    "EXPIRED" -> "Đã hết hạn"
    else -> "Không rõ"
}
