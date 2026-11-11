package com.anve.app.ui.diagnostics

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.anve.app.data.location.QueueDiagnostics
import com.anve.app.tracking.LocationPermissionState
import com.anve.app.tracking.TrackingStatus
import com.anve.app.tracking.diagnostics.DiagnosticsAction
import com.anve.app.tracking.diagnostics.DiagnosticsDetails
import com.anve.app.tracking.diagnostics.safeDiagnosticsReport
import com.anve.app.ui.theme.AnVeTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DiagnosticsScreenInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val state = DiagnosticsUiState(
        owner = "synthetic-owner",
        status = TrackingStatus(
            ownerUserId = "synthetic-owner",
            sessionReady = true,
            consentEnabled = true,
            running = true,
            serviceRunning = true,
            providerActive = true,
            online = false,
            permission = LocationPermissionState.GRANTED_PRECISE,
            backgroundPermissionGranted = true,
            lastFixAtMillis = 940_000,
            lastUploadAtMillis = 520_000,
        ),
        details = DiagnosticsDetails(queue = QueueDiagnostics(12, 500_000, 3, 1)),
    )

    @Test
    fun overviewAndDetailsHaveWorkingRecoveryAndExportControls() {
        var selectedAction: DiagnosticsAction? = null
        var exports = 0
        compose.setContent {
            AnVeTheme {
                DiagnosticsContent(state, 1_000_000, {}, {}, { selectedAction = it }, { exports++ })
            }
        }
        compose.onNodeWithText("Đang ghi, chờ có mạng").assertIsDisplayed()
        compose.onNodeWithText("Mở cài đặt mạng").performClick()
        compose.runOnIdle { assertEquals(DiagnosticsAction.NETWORK_SETTINGS, selectedAction) }
        capture("phone-light-overview")

        compose.onNodeWithText("Chi tiết").performClick()
        capture("phone-light-details-top")
        scrollTo("Tổng lần thử gửi")
        compose.onNodeWithText("Tổng lần thử gửi").assertIsDisplayed()
        scrollTo("Xuất báo cáo")
        compose.onNodeWithText("Xuất báo cáo").performClick()
        compose.runOnIdle { assertEquals(1, exports) }
        capture("phone-light-details")
    }

    @Test
    fun enlargedTextAndDarkThemeKeepActionsReachable() {
        val show = mutableStateOf(false)
        val report = safeDiagnosticsReport(state.status, state.details, "synthetic-debug", 35, 1_000_000)
        compose.setContent {
            AnVeTheme(darkTheme = true) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                    DiagnosticsContent(state, 1_000_000, {}, {}, {}, { show.value = true })
                    if (show.value) DiagnosticsExportReview(report, { show.value = false }, {})
                }
            }
        }
        scrollTo("Mở cài đặt mạng")
        compose.onNodeWithText("Mở cài đặt mạng").assertIsDisplayed()
        capture("phone-dark-font130-overview")
        scrollTo("Xuất báo cáo")
        compose.onNodeWithText("Xuất báo cáo").assertIsDisplayed()
        compose.onNodeWithText("Xuất báo cáo").performClick()
        compose.onNodeWithText("Hủy").assertIsDisplayed()
        capture("phone-dark-font130-export-preview")
    }

    @Test
    fun emptyQueueWithoutAFixDoesNotInventALocation() {
        val empty = state.copy(
            status = state.status.copy(online = true, lastFixAtMillis = null, lastUploadAtMillis = null),
            details = DiagnosticsDetails(queue = QueueDiagnostics(0, null, 0, 0)),
        )
        compose.setContent { AnVeTheme { DiagnosticsContent(empty, 1_000_000, {}, {}, {}, {}) } }
        compose.onNodeWithText("Đang chờ mẫu vị trí").assertIsDisplayed()
        capture("phone-no-fix-empty-queue")
        scrollTo("0 điểm")
        compose.onNodeWithText("0 điểm").assertIsDisplayed()
    }

    @Test
    fun permissionDenialProvidesAnAppSettingsAction() {
        var selected: DiagnosticsAction? = null
        val denied = state.copy(status = state.status.copy(
            online = true,
            running = false,
            serviceRunning = false,
            providerActive = false,
            permission = LocationPermissionState.DENIED,
        ))
        compose.setContent { AnVeTheme { DiagnosticsContent(denied, 1_000_000, {}, {}, { selected = it }, {}) } }
        compose.onNodeWithText("Mở cài đặt ứng dụng").performClick()
        compose.runOnIdle { assertEquals(DiagnosticsAction.APP_SETTINGS, selected) }
        capture("phone-permission-denied")
    }

    @Test
    fun pendingConsentDoesNotClaimServerConfirmation() {
        val pending = state.copy(status = state.status.copy(
            consentEnabled = false,
            consentSyncPending = true,
            serverConsentEnabled = true,
            running = false,
            serviceRunning = false,
            providerActive = false,
        ))
        compose.setContent { AnVeTheme { DiagnosticsContent(pending, 1_000_000, {}, {}, {}, {}) } }
        compose.onNodeWithText("Xem trạng thái chia sẻ").assertIsDisplayed()
        capture("phone-consent-pending")
    }

    @Test
    fun unreadableDetailsAndExportFailureRemainVisible() {
        val error = "Tài khoản hoặc trạng thái đã thay đổi. Hãy tạo lại báo cáo."
        val failed = state.copy(details = null, readFailed = true)
        compose.setContent { AnVeTheme { DiagnosticsContent(failed, 1_000_000, {}, {}, {}, {}, actionError = error) } }
        scrollTo(error)
        compose.onNodeWithText(error).assertIsDisplayed()
        capture("phone-read-and-export-error")
    }

    @Test
    fun loadingDoesNotOfferAnIncompleteExport() {
        compose.setContent { AnVeTheme { DiagnosticsContent(state.copy(loading = true, details = null), 1_000_000, {}, {}, {}, {}) } }
        capture("phone-loading")
        scrollTo("Xuất báo cáo")
        compose.onNodeWithText("Xuất báo cáo").assertIsNotEnabled()
    }

    @Test
    fun exportPreviewCanBeCancelledWithoutSharing() {
        val show = mutableStateOf(false)
        var shared = 0
        val report = safeDiagnosticsReport(state.status, state.details, "synthetic-debug", 35, 1_000_000)
        compose.setContent {
            AnVeTheme {
                DiagnosticsContent(state, 1_000_000, {}, {}, {}, { show.value = true })
                if (show.value) DiagnosticsExportReview(report, { show.value = false }, { shared++ })
            }
        }
        scrollTo("Xuất báo cáo")
        compose.onNodeWithText("Xuất báo cáo").performClick()
        compose.onNodeWithText("Kiểm tra báo cáo trước khi gửi").assertIsDisplayed()
        compose.onNodeWithText("Chọn nơi gửi").assertIsDisplayed()
        capture("phone-export-preview")
        compose.onNodeWithText("Hủy").performClick()
        compose.onNodeWithText("Kiểm tra báo cáo trước khi gửi").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, shared) }
    }

    private fun scrollTo(text: String) {
        compose.onNodeWithTag("diagnostics-list").performScrollToNode(hasText(text))
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        // Compose capture forces drawing; semantic idleness alone can leave stale pixels.
        val windows = compose.onAllNodes(isRoot())
        val titleBounds = compose.onNodeWithText("Chẩn đoán vị trí").fetchSemanticsNode().boundsInRoot
        var readyBitmap: Bitmap? = null
        compose.waitUntil(timeoutMillis = 5_000) {
            val candidate = windows[0].captureToImage().asAndroidBitmap()
            if (hasRenderedText(candidate, titleBounds)) {
                readyBitmap = candidate
                true
            } else {
                candidate.recycle()
                false
            }
        }
        saveCapture("compose-$name-window-0", requireNotNull(readyBitmap))
        windows.fetchSemanticsNodes().indices.forEach { index ->
            if (index == 0) return@forEach
            val bitmap = windows[index].captureToImage().asAndroidBitmap()
            saveCapture("compose-$name-window-$index", bitmap)
        }
    }

    private fun hasRenderedText(bitmap: Bitmap, bounds: Rect): Boolean {
        val left = bounds.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = bounds.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = bounds.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = bounds.bottom.toInt().coerceIn(top + 1, bitmap.height)
        val background = bitmap.getPixel(left, top)
        for (y in top until bottom) {
            for (x in left until right) {
                if (bitmap.getPixel(x, y) != background) return true
            }
        }
        return false
    }

    private fun saveCapture(name: String, bitmap: Bitmap) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir("ui-validation"))
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
