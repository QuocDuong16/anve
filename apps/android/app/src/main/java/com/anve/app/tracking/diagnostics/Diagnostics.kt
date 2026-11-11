package com.anve.app.tracking.diagnostics

import com.anve.app.data.location.QueueDiagnostics
import com.anve.app.tracking.LocationPermissionState
import com.anve.app.tracking.TrackingProblem
import com.anve.app.tracking.TrackingStatus

data class DiagnosticsDetails(
    val queue: QueueDiagnostics? = null,
    val captureCheckpoint: Boolean = false,
    val batteryOptimizationExempt: Boolean? = null,
    val syncWork: List<SyncWorkStatus>? = null,
    val sosPending: Int? = null,
    val pushRegistered: Boolean? = null,
    val pushConfigured: Boolean? = null,
)

enum class DiagnosticsAction { TRACKING, APP_SETTINGS, LOCATION_SETTINGS, NETWORK_SETTINGS, RETRY_SYNC }

enum class SyncWorkStatus { ENQUEUED, RUNNING, SUCCEEDED, FAILED, BLOCKED, CANCELLED }

data class TrackingDiagnosis(
    val title: String,
    val explanation: String,
    val action: DiagnosticsAction? = null,
    val actionLabel: String? = null,
    val attention: Boolean = false,
)

/** Current admission and OS state take precedence over historical error codes. */
fun diagnoseTracking(status: TrackingStatus): TrackingDiagnosis = when {
    !status.sessionReady -> TrackingDiagnosis(
        "Chưa xác định tài khoản", "Quay lại để đăng nhập hoặc chờ tài khoản được tải.",
        DiagnosticsAction.TRACKING, "Quay lại",
    )
    status.consentConflict -> TrackingDiagnosis(
        "Cần xác nhận quyền chia sẻ", "Trạng thái trên máy chủ đã thay đổi. Theo dõi vẫn dừng trên máy này.",
        DiagnosticsAction.TRACKING, "Xem quyền chia sẻ", true,
    )
    status.consentSyncPending -> TrackingDiagnosis(
        "Chờ xác nhận quyền chia sẻ", "Theo dõi vẫn dừng trên máy này. Máy chủ chưa xác nhận yêu cầu mới nhất.",
        DiagnosticsAction.TRACKING, "Xem trạng thái chia sẻ", true,
    )
    status.lastError == TrackingProblem.SERVICE_STOP_FAILED -> TrackingDiagnosis(
        "Chưa xác nhận dịch vụ đã dừng", "Hệ thống chưa xác nhận dừng dịch vụ. Kiểm tra màn Vị trí và cài đặt ứng dụng.",
        DiagnosticsAction.APP_SETTINGS, "Mở cài đặt ứng dụng", true,
    )
    !status.consentEnabled -> TrackingDiagnosis(
        "Đang tắt chia sẻ", "AnVề không thu thập vị trí khi bạn tắt chia sẻ.",
        DiagnosticsAction.TRACKING, "Về điều khiển vị trí",
    )
    status.permission != LocationPermissionState.GRANTED_PRECISE &&
        status.permission != LocationPermissionState.GRANTED_APPROXIMATE -> TrackingDiagnosis(
        "Chưa có quyền vị trí", "Cấp quyền vị trí cho AnVề rồi chủ động bắt đầu theo dõi.",
        DiagnosticsAction.APP_SETTINGS, "Mở cài đặt ứng dụng", true,
    )
    !status.locationServicesEnabled -> TrackingDiagnosis(
        "Dịch vụ định vị đang tắt", "Bật định vị của thiết bị rồi quay lại màn Vị trí.",
        DiagnosticsAction.LOCATION_SETTINGS, "Mở cài đặt vị trí", true,
    )
    !status.activelyTracking -> TrackingDiagnosis(
        if (status.recoveryRequired) "Cần khôi phục theo dõi" else "Theo dõi chưa hoạt động",
        when (status.lastError) {
            TrackingProblem.CHECKPOINT_UNAVAILABLE -> "Chưa xác nhận được quyền ghi nhận vị trí với máy chủ. Cần có mạng trước khi bắt đầu."
            TrackingProblem.CIRCLE_REQUIRED -> "Bạn cần tham gia một nhóm gia đình trước khi theo dõi."
            TrackingProblem.QUEUE_UNAVAILABLE -> "Không đọc hoặc ghi được hàng đợi. Vị trí chưa được xác nhận lưu trên máy."
            TrackingProblem.PROVIDER_UNAVAILABLE, TrackingProblem.PROVIDER_ERROR -> "Bộ cung cấp vị trí chưa hoạt động. Kiểm tra thiết bị rồi thử bắt đầu lại."
            TrackingProblem.CLOCK_SKEW -> "Đồng hồ thiết bị lệch quá xa. Kiểm tra ngày giờ tự động trong cài đặt hệ thống."
            else -> "Mở màn Vị trí để kiểm tra và chủ động bắt đầu lại. AnVề không tự bật GPS từ trang chẩn đoán."
        },
        DiagnosticsAction.TRACKING, "Về điều khiển vị trí", true,
    )
    !status.online -> TrackingDiagnosis(
        "Đang ghi, chờ có mạng", "Vị trí được xếp hàng trên máy khi ghi thành công. Khi có mạng, AnVề sẽ thử gửi lại.",
        DiagnosticsAction.NETWORK_SETTINGS, "Mở cài đặt mạng", true,
    )
    status.lastFixAtMillis == null -> TrackingDiagnosis(
        "Đang chờ mẫu vị trí", "Dịch vụ đang chạy nhưng chưa ghi nhận mẫu vị trí hợp lệ. Có thể cần ra nơi thoáng hơn.",
        attention = true,
    )
    else -> TrackingDiagnosis(
        "Dịch vụ đang hoạt động", "Kiểm tra thời điểm ghi và gửi gần nhất bên dưới. Dịch vụ đang chạy không bảo đảm luôn nhận được GPS mới.",
    )
}

/** Unknown and future timestamps stay unknown instead of becoming a false zero age. */
fun diagnosticAgeSeconds(timestampMillis: Long?, nowMillis: Long): Long? =
    timestampMillis?.takeIf { it > 0 && it <= nowMillis }?.let { (nowMillis - it) / 1_000 }

/** Explicit allowlist: never serialize TrackingStatus, exceptions, outbox items or API DTOs. */
fun safeDiagnosticsReport(
    status: TrackingStatus,
    details: DiagnosticsDetails?,
    version: String,
    androidApi: Int,
    nowMillis: Long,
): String = buildString {
    appendLine("AnVề diagnostics v1")
    appendLine("Contains device state, queue counts and relative ages. No coordinates, identities or credentials.")
    appendLine("appVersion=${version.take(64).replace(Regex("[^A-Za-z0-9.+_-]"), "_")}")
    appendLine("androidApi=$androidApi")
    appendLine("consentEnabled=${status.consentEnabled}")
    appendLine("serverConsentEnabled=${status.serverConsentEnabled}")
    appendLine("consentSyncPending=${status.consentSyncPending}")
    appendLine("consentConflict=${status.consentConflict}")
    appendLine("permission=${status.permission}")
    appendLine("backgroundPermission=${status.backgroundPermissionGranted}")
    appendLine("notificationsPermission=${status.notificationsGranted}")
    appendLine("locationServices=${status.locationServicesEnabled}")
    appendLine("serviceRunning=${status.serviceRunning}")
    appendLine("providerActive=${status.providerActive}")
    appendLine("activelyTracking=${status.activelyTracking}")
    appendLine("online=${status.online}")
    appendLine("batterySaver=${status.batterySaver}")
    appendLine("batteryOptimizationExempt=${details?.batteryOptimizationExempt}")
    appendLine("lastFixAgeSeconds=${diagnosticAgeSeconds(status.lastFixAtMillis, nowMillis)}")
    appendLine("lastUploadAgeSeconds=${diagnosticAgeSeconds(status.lastUploadAtMillis, nowMillis)}")
    appendLine("queuedPoints=${details?.queue?.queuedPoints}")
    appendLine("oldestQueueAgeSeconds=${diagnosticAgeSeconds(details?.queue?.oldestCreatedAtMillis, nowMillis)}")
    appendLine("uploadAttempts=${details?.queue?.attempts}")
    appendLine("retriedPoints=${details?.queue?.retriedPoints}")
    appendLine("recoveryRequired=${status.recoveryRequired}")
    appendLine("problem=${status.lastError}")
    appendLine("privacyDiscardedPoints=${status.privacyDiscardedPoints}")
    appendLine("captureCheckpoint=${details?.captureCheckpoint}")
    appendLine("syncWork=${details?.syncWork?.joinToString()}")
    appendLine("sosPending=${details?.sosPending}")
    appendLine("pushRegistered=${details?.pushRegistered}")
    appendLine("pushProviderConfigured=${details?.pushConfigured}")
    appendLine("Battery overhead and notification delivery latency have not been measured by this report.")
}
