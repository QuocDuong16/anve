package com.anve.app.ui.diagnostics

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.anve.app.data.location.QueueDiagnostics
import com.anve.app.tracking.LocationPermissionState
import com.anve.app.tracking.TrackingStatus
import com.anve.app.tracking.diagnostics.DiagnosticsAction
import com.anve.app.tracking.diagnostics.DiagnosticsDetails
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
        compose.onNodeWithText("Tổng lần thử gửi").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Xuất báo cáo").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, exports) }
        capture("phone-light-details")
    }

    @Test
    fun enlargedTextAndDarkThemeKeepActionsReachable() {
        compose.setContent {
            AnVeTheme(darkTheme = true) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                    DiagnosticsContent(state, 1_000_000, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("Mở cài đặt mạng").performScrollTo().assertIsDisplayed()
        capture("phone-dark-font130-overview")
        compose.onNodeWithText("Xuất báo cáo").performScrollTo().assertIsDisplayed()
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("ui-validation"))
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
