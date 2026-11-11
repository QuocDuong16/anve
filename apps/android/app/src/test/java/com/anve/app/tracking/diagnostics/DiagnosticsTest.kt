package com.anve.app.tracking.diagnostics

import com.anve.app.data.location.QueueDiagnostics
import com.anve.app.tracking.LocationPermissionState
import com.anve.app.tracking.TrackingProblem
import com.anve.app.tracking.TrackingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    private val running = TrackingStatus(
        ownerUserId = "private-owner-sentinel",
        sessionReady = true,
        consentEnabled = true,
        permission = LocationPermissionState.GRANTED_PRECISE,
        running = true,
        serviceRunning = true,
        providerActive = true,
        lastFixAtMillis = 10_000,
    )

    @Test
    fun `offline distinguishes recording from delivery without claiming tracking stopped`() {
        val result = diagnoseTracking(running.copy(online = false))
        assertEquals("Đang ghi, chờ có mạng", result.title)
        assertEquals(DiagnosticsAction.NETWORK_SETTINGS, result.action)
    }

    @Test
    fun `disabled consent is respected instead of recommending a permission grant`() {
        val result = diagnoseTracking(running.copy(consentEnabled = false, permission = LocationPermissionState.DENIED))
        assertEquals("Đang tắt chia sẻ", result.title)
        assertEquals(DiagnosticsAction.TRACKING, result.action)
    }

    @Test
    fun `pending consent never presents a confirmed server OFF`() {
        val result = diagnoseTracking(running.copy(consentEnabled = false, consentSyncPending = true, serverConsentEnabled = true))
        assertEquals("Chờ xác nhận quyền chia sẻ", result.title)
        assertTrue(result.explanation.contains("chưa xác nhận"))
    }

    @Test
    fun `a failed service stop remains unresolved even with local consent OFF`() {
        val result = diagnoseTracking(running.copy(consentEnabled = false, lastError = TrackingProblem.SERVICE_STOP_FAILED))
        assertEquals("Chưa xác nhận dịch vụ đã dừng", result.title)
    }

    @Test
    fun `service presence without provider delivery never becomes a running claim`() {
        val result = diagnoseTracking(running.copy(providerActive = false))
        assertEquals("Theo dõi chưa hoạt động", result.title)
        assertEquals(DiagnosticsAction.TRACKING, result.action)
    }

    @Test
    fun `a started service without a valid fix is explicitly waiting`() {
        assertEquals("Đang chờ mẫu vị trí", diagnoseTracking(running.copy(lastFixAtMillis = null)).title)
    }

    @Test
    fun `missing and future timestamps stay unknown`() {
        assertNull(diagnosticAgeSeconds(null, 20_000))
        assertNull(diagnosticAgeSeconds(0, 20_000))
        assertNull(diagnosticAgeSeconds(30_000, 20_000))
        assertEquals(10L, diagnosticAgeSeconds(10_000, 20_000))
    }

    @Test
    fun `export is an allowlist with relative ages and no owner or coordinate fields`() {
        val report = safeDiagnosticsReport(
            running.copy(lastAccuracyMeters = 37.123456, lastUploadAtMillis = 12_000),
            DiagnosticsDetails(queue = QueueDiagnostics(12, 11_000, 4, 1)),
            "0.2.0-debug", 35, 20_000,
        )
        assertTrue(report.contains("queuedPoints=12"))
        assertTrue(report.contains("lastFixAgeSeconds=10"))
        assertTrue(report.contains("lastUploadAgeSeconds=8"))
        assertTrue(report.contains("oldestQueueAgeSeconds=9"))
        listOf("private-owner-sentinel", "37.123456", "ownerUserId", "latitude", "longitude", "accessToken", "recipientIds").forEach {
            assertFalse("report must omit $it", report.contains(it))
        }
    }

    @Test
    fun `failed reads remain unknown rather than empty in the export`() {
        val report = safeDiagnosticsReport(running, DiagnosticsDetails(), "0.2.0", 35, 20_000)
        assertTrue(report.contains("queuedPoints=null"))
        assertTrue(report.contains("sosPending=null"))
        assertTrue(report.contains("pushRegistered=null"))
    }
}
