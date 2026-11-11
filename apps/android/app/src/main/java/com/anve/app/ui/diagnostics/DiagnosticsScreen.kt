package com.anve.app.ui.diagnostics

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.repeatOnLifecycle
import com.anve.app.AppContainer
import com.anve.app.BuildConfig
import com.anve.app.data.diagnostics.AndroidDiagnosticsSource
import com.anve.app.tracking.LocationPermissionState
import com.anve.app.tracking.TrackingStatus
import com.anve.app.tracking.diagnostics.DiagnosticsAction
import com.anve.app.tracking.diagnostics.SyncWorkStatus
import com.anve.app.tracking.diagnostics.diagnoseTracking
import com.anve.app.tracking.diagnostics.diagnosticAgeSeconds
import com.anve.app.ui.theme.AnVeTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
fun DiagnosticsScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val source = remember(container) {
        AndroidDiagnosticsSource(
            context, container.locationQueue, container.sosOutbox, container.sosRepository,
            container::hasCaptureCheckpoint, isOnline = { container.trackingManager.status.value.online },
        )
    }
    val model: DiagnosticsViewModel = viewModel(
        factory = DiagnosticsViewModel.factory(container.trackingManager, container.sessionStore, source),
    )
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var export by remember { mutableStateOf<DiagnosticsExport?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(state.owner) { export = null; actionError = null }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                nowMillis = System.currentTimeMillis()
                delay(30_000)
            }
        }
    }
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.activate()
            if (event == Lifecycle.Event.ON_STOP) { model.deactivate(); export = null }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) model.activate()
        onDispose { lifecycle.removeObserver(observer); model.deactivate() }
    }

    fun open(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            actionError = "Thiết bị không mở được trang này. Hãy mở Cài đặt của điện thoại để kiểm tra."
        }
    }

    DiagnosticsContent(
        state = state,
        nowMillis = nowMillis,
        onBack = onBack,
        onRefresh = model::refresh,
        onAction = { action ->
            when (action) {
                DiagnosticsAction.TRACKING -> onBack()
                DiagnosticsAction.APP_SETTINGS -> open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                DiagnosticsAction.LOCATION_SETTINGS -> open(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                DiagnosticsAction.NETWORK_SETTINGS -> open(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                DiagnosticsAction.RETRY_SYNC -> { container.trackingManager.retrySync(); model.refresh() }
            }
        },
        onExport = {
            scope.launch {
                export = model.prepareExport(BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT)
                if (export == null) actionError = "Trạng thái đã thay đổi hoặc chưa đọc xong. Hãy làm mới rồi thử lại."
            }
        },
        actionError = actionError,
    )

    export?.let { report ->
        AlertDialog(
            onDismissRequest = { export = null },
            title = { Text("Kiểm tra báo cáo trước khi gửi") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Báo cáo gồm phiên bản, quyền, trạng thái dịch vụ/mạng/pin, số điểm chờ, tuổi dữ liệu và trạng thái thông báo. Không chứa tọa độ, tên, tài khoản hay thông tin đăng nhập.")
                    SelectionContainer {
                        Text(report.text, Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (model.canShare(report)) {
                            open(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Báo cáo chẩn đoán AnVề")
                                putExtra(Intent.EXTRA_TEXT, report.text)
                            }, "Chọn nơi gửi báo cáo"))
                        } else {
                            actionError = "Tài khoản hoặc trạng thái đã thay đổi. Hãy tạo lại báo cáo."
                        }
                        export = null
                    }
                }) { Text("Chọn nơi gửi") }
            },
            dismissButton = { TextButton(onClick = { export = null }) { Text("Hủy") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsContent(
    state: DiagnosticsUiState,
    nowMillis: Long,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onAction: (DiagnosticsAction) -> Unit,
    onExport: () -> Unit,
    actionError: String? = null,
) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val status = state.status
    val details = state.details
    val ready = state.owner != null && status.ownerUserId == state.owner
    val diagnosis = diagnoseTracking(status)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chẩn đoán vị trí") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại") } },
                actions = { IconButton(onClick = onRefresh, enabled = !state.loading) { Icon(Icons.Default.Refresh, "Làm mới chẩn đoán") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = MaterialTheme.colorScheme.onPrimary, navigationIconContentColor = MaterialTheme.colorScheme.onPrimary, actionIconContentColor = MaterialTheme.colorScheme.onPrimary),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (!ready) {
                item { Text("Đang đọc trạng thái tài khoản. Quay lại màn Vị trí nếu phiên đăng nhập đã thay đổi.") }
                return@LazyColumn
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(Modifier.size(56.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.LocationOn, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Column {
                        Text(diagnosis.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(diagnosis.explanation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("Tổng quan", "Chi tiết").forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = showDetails == (index == 1),
                            onClick = { showDetails = index == 1 },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                            modifier = Modifier.weight(1f),
                            colors = SegmentedButtonDefaults.colors(activeContainerColor = MaterialTheme.colorScheme.primary, activeContentColor = MaterialTheme.colorScheme.onPrimary),
                            icon = {},
                        ) { Text(label) }
                    }
                }
            }
            if (diagnosis.action != null) item {
                Card(colors = CardDefaults.cardColors(containerColor = if (diagnosis.attention) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (diagnosis.action == DiagnosticsAction.NETWORK_SETTINGS) "Mất kết nối mạng" else diagnosis.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { onAction(diagnosis.action) }) { Text(diagnosis.actionLabel.orEmpty()) }
                    }
                }
            }
            if (actionError != null) item { Text(actionError, color = MaterialTheme.colorScheme.error) }
            if (state.readFailed) item { Text("Chưa đọc được chẩn đoán bổ sung. Dùng nút Làm mới để thử lại.", color = MaterialTheme.colorScheme.error) }
            if (!showDetails) {
                item { DiagnosticsSection("Thu thập vị trí", Icons.Default.LocationOn) {
                    DiagnosticRow("Dịch vụ theo dõi", if (status.activelyTracking) "Đang chạy" else if (status.serviceRunning) "Chưa xác nhận thu thập" else "Đã dừng")
                    DiagnosticRow("Lần ghi gần nhất", ageLabel(status.lastFixAtMillis, nowMillis))
                } }
                item { DiagnosticsSection("Đồng bộ", Icons.Default.Sync) {
                    DiagnosticRow("Điểm chờ gửi", details?.queue?.queuedPoints?.let { "$it điểm" } ?: unknownLabel(state.loading))
                    DiagnosticRow("Lần gửi thành công", ageLabel(status.lastUploadAtMillis, nowMillis))
                } }
                item { DiagnosticsSection("Quyền & thiết bị", Icons.Default.Security) {
                    DiagnosticRow("Quyền vị trí", when (status.permission) {
                        LocationPermissionState.GRANTED_PRECISE -> "Chính xác"
                        LocationPermissionState.GRANTED_APPROXIMATE -> "Gần đúng"
                        LocationPermissionState.NOT_REQUESTED -> "Chưa yêu cầu"
                        LocationPermissionState.DENIED -> "Chưa cấp"
                    })
                    DiagnosticRow("Quyền chạy trong nền", yesNo(status.backgroundPermissionGranted, "Đã cấp", "Chưa cấp"))
                    DiagnosticRow("Dịch vụ định vị", yesNo(status.locationServicesEnabled, "Đã bật", "Đang tắt"))
                    DiagnosticRow("Trình tiết kiệm pin", yesNo(status.batterySaver, "Đang bật", "Đang tắt"))
                    if (status.batterySaver || !status.backgroundPermissionGranted) Text("Thiết bị có thể giới hạn cập nhật khi màn hình tắt. Kiểm tra cài đặt và thử nghiệm trên điện thoại thực tế.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                } }
            } else {
                item { DiagnosticsSection("Hàng đợi & phục hồi", Icons.Default.Sync) {
                    DiagnosticRow("Điểm chờ gửi", details?.queue?.queuedPoints?.toString() ?: unknownLabel(state.loading))
                    DiagnosticRow("Điểm chờ lâu nhất", ageLabel(details?.queue?.oldestCreatedAtMillis, nowMillis))
                    DiagnosticRow("Tổng lần thử gửi", details?.queue?.attempts?.toString() ?: unknownLabel(state.loading))
                    DiagnosticRow("Điểm đã thử lại", details?.queue?.retriedPoints?.toString() ?: unknownLabel(state.loading))
                    DiagnosticRow("Xác nhận ghi nhận cục bộ", details?.captureCheckpoint?.let { yesNo(it, "Đã lưu", "Chưa có") } ?: unknownLabel(state.loading))
                    DiagnosticRow("Cần khôi phục", yesNo(status.recoveryRequired, "Có", "Không"))
                    DiagnosticRow("Tác vụ đồng bộ trên máy", details?.syncWork?.let { work -> if (work.isEmpty()) "Không có tác vụ đang chờ" else work.joinToString { workLabel(it) } } ?: unknownLabel(state.loading))
                    if (status.privacyDiscardedPoints) Text("Một số điểm đã bị loại theo chính sách riêng tư; không thể khôi phục bằng cách gửi lại.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { onAction(DiagnosticsAction.RETRY_SYNC) }, enabled = !state.loading && status.online && status.consentEnabled && (details?.queue?.queuedPoints ?: 0) > 0, modifier = Modifier.padding(16.dp)) { Text("Yêu cầu đồng bộ lại") }
                } }
                item { DiagnosticsSection("Thông báo & SOS", Icons.Default.Security) {
                    DiagnosticRow("Quyền thông báo", yesNo(status.notificationsGranted, "Đã cấp", "Chưa cấp"))
                    DiagnosticRow("Đăng ký push trên máy chủ", details?.pushRegistered?.let { yesNo(it, "Đã đăng ký", "Chưa đăng ký") } ?: unknownLabel(state.loading || state.checkingPush))
                    DiagnosticRow("Cấu hình dịch vụ push", details?.pushConfigured?.let { yesNo(it, "Đã cấu hình", "Chưa cấu hình") } ?: unknownLabel(state.loading || state.checkingPush))
                    DiagnosticRow("SOS còn chờ xử lý trên máy", details?.sosPending?.toString() ?: unknownLabel(state.loading))
                    Text("Đăng ký push hoặc cấu hình hợp lệ không chứng minh thông báo đã đến hay người nhận đã đọc.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                } }
                item { DiagnosticsSection("Thiết bị & ứng dụng", Icons.Default.Security) {
                    DiagnosticRow("Miễn tối ưu hóa pin", details?.batteryOptimizationExempt?.let { yesNo(it, "Có", "Không") } ?: unknownLabel(state.loading))
                    DiagnosticRow("Phiên bản AnVề", BuildConfig.VERSION_NAME)
                    DiagnosticRow("Android API", Build.VERSION.SDK_INT.toString())
                    Text("Chưa đo mức pin tiêu thụ thêm. Các trạng thái ở đây không thay thế kiểm thử GPS, OEM và chạy nền trên thiết bị thật.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                } }
            }
            item {
                TextButton(onClick = onExport, enabled = !state.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Default.Description, null, Modifier.padding(end = 12.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                        Text("Xuất báo cáo", style = MaterialTheme.typography.titleMedium)
                        Text("Không chứa tọa độ hay thông tin đăng nhập", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Text("Trạng thái tại lần đọc gần nhất. Làm mới sau khi đổi cài đặt.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun DiagnosticsSection(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun yesNo(value: Boolean, yes: String, no: String) = if (value) yes else no
private fun unknownLabel(loading: Boolean) = if (loading) "Đang đọc…" else "Chưa xác định"

private fun workLabel(status: SyncWorkStatus): String = when (status) {
    SyncWorkStatus.ENQUEUED -> "Đang chờ"
    SyncWorkStatus.RUNNING -> "Đang chạy"
    SyncWorkStatus.BLOCKED -> "Chờ điều kiện"
    SyncWorkStatus.SUCCEEDED -> "Đã xong"
    SyncWorkStatus.FAILED -> "Thất bại"
    SyncWorkStatus.CANCELLED -> "Đã hủy"
}

private fun ageLabel(timestamp: Long?, now: Long): String = diagnosticAgeSeconds(timestamp, now)?.let {
    when {
        it < 60 -> "Dưới 1 phút trước"
        it < 3_600 -> "${it / 60} phút trước"
        it < 86_400 -> "${it / 3_600} giờ trước"
        else -> "${it / 86_400} ngày trước"
    }
} ?: if (timestamp == null) "Chưa có dữ liệu" else "Cần kiểm tra đồng hồ"

@Preview(showBackground = true)
@Composable
private fun DiagnosticsPreview() {
    AnVeTheme {
        DiagnosticsContent(DiagnosticsUiState(owner = "preview", status = TrackingStatus(ownerUserId = "preview", sessionReady = true)), 1_000_000, {}, {}, {}, {})
    }
}
