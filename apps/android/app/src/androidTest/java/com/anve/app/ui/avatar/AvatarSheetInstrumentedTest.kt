package com.anve.app.ui.avatar

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.anve.app.data.api.AvatarStateDto
import com.anve.app.data.avatar.AvatarGateway
import java.io.ByteArrayOutputStream
import java.io.IOException
import com.anve.app.ui.captureNativeFixture
import com.anve.app.ui.diagnostics.SystemFontScaleRule
import com.anve.app.ui.theme.AnVeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule

class AvatarSheetInstrumentedTest {
    @get:Rule(order = 0) val systemFontScale: TestRule = SystemFontScaleRule("darkLargeTextKeepsControlsReachable", 1.3f)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()
    private val ready = AvatarUiState(open = true, loading = false, metadata = AvatarStateDto(0), selection = byteArrayOf(1))
    private val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.rgb(18, 87, 92))
        for (x in 0..127) for (y in 0..255) setPixel(x, y, Color.rgb(245, 195, 112))
    }.asImageBitmap()

    @Test fun previewAndSaveAreSeparateAndRemovalRequiresSave() {
        var saves = 0; var chooses = 0; var removes = 0
        val state = mutableStateOf(ready)
        compose.setContent { AnVeTheme { AvatarSheet(state.value, true, {}, { chooses++ }, { removes++ }, { saves++ }, {}, {}) {
            AvatarFrame("An", if (state.value.removeDraft) null else bitmap, Modifier.size(160.dp))
        } } }
        compose.onNodeWithTag("avatar-choose").performScrollTo().performClick()
        compose.onNodeWithTag("avatar-remove").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, saves); assertEquals(1, chooses); assertEquals(1, removes) }
        captureNativeFixture("avatar-preview-light")
        compose.onNodeWithTag("avatar-save").performClick()
        compose.runOnIdle { assertEquals(1, saves); state.value = ready.copy(removeDraft = true, selection = null,
            metadata = AvatarStateDto(1, "b1a64ffd-bf07-476a-8f3d-1f7755fb03d1", "b1a64ffd-bf07-476a-8f3d-1f7755fb03d1")) }
        compose.onNodeWithText("Ảnh hiện tại sẽ được xóa sau khi bạn lưu.").performScrollTo().assertIsDisplayed()
        captureNativeFixture("avatar-removal-draft")
    }

    @Test fun loadingOfflineProcessingAndUnknownPreventWriteAndOfferReadOnlyRecovery() {
        val state = mutableStateOf(AvatarUiState(open = true))
        val online = mutableStateOf(false)
        var saves = 0; var checks = 0
        compose.setContent { AnVeTheme { AvatarSheet(state.value, online.value, {}, {}, {}, { saves++ }, { checks++ }, {}) {
            AvatarFrame("An", if (state.value.selection != null) bitmap else null, Modifier.size(160.dp))
        } } }
        compose.onNodeWithTag("avatar-save").assertIsNotEnabled()
        compose.runOnIdle { state.value = ready }
        compose.onNodeWithTag("avatar-save").assertIsNotEnabled()
        captureNativeFixture("avatar-offline")
        compose.runOnIdle { online.value = true; state.value = ready.copy(processing = true) }
        compose.onNodeWithTag("avatar-save").assertIsNotEnabled()
        compose.onNodeWithText("Đang xử lý ảnh…").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = ready.copy(unknown = true, error = "Chưa rõ kết quả lưu ảnh.") }
        compose.onNodeWithTag("avatar-save").assertDoesNotExist()
        compose.onNodeWithTag("avatar-check").performClick()
        compose.runOnIdle { assertEquals(0, saves); assertEquals(1, checks) }
        captureNativeFixture("avatar-unknown")
    }

    @Test fun failedCurrentImageHasAnExplicitReadOnlyRetry() {
        var requests = 0
        val pixels = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val bytes = ByteArrayOutputStream().use { pixels.compress(Bitmap.CompressFormat.JPEG, 90, it); it.toByteArray() }
        pixels.recycle()
        val gateway = object : AvatarGateway {
            override suspend fun state(owner: String): AvatarStateDto = error("unused")
            override suspend fun replace(owner: String, operation: String, revision: Long, image: ByteArray): AvatarStateDto = error("must not write")
            override suspend fun remove(owner: String, operation: String, revision: Long): AvatarStateDto = error("must not write")
            override suspend fun image(owner: String, subject: String, imageId: String): ByteArray {
                requests++; if (requests == 1) throw IOException("synthetic transport failure"); return bytes
            }
        }
        compose.setContent { AnVeTheme { AvatarSheet(ready.copy(selection = null), true, {}, {}, {}, {}, {}, {}) {
            AvatarImage("owner", "owner", "synthetic-current-image", "An", gateway, Modifier.size(160.dp), retryable = true)
        } } }
        compose.waitUntil(5_000) { requests == 1 }
        compose.onNodeWithContentDescription("Tải lại ảnh đại diện").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("avatar-sheet").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        captureNativeFixture("avatar-image-error")
        compose.onNodeWithContentDescription("Tải lại ảnh đại diện").performClick()
        compose.waitUntil(5_000) { requests == 2 }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Tải lại ảnh đại diện").assertDoesNotExist()
    }

    @Test fun darkLargeTextKeepsControlsReachable() {
        compose.setContent { AnVeTheme(darkTheme = true) { AvatarSheet(ready, true, {}, {}, {}, {}, {}, {}) {
            AvatarFrame("An", bitmap, Modifier.size(160.dp))
        } } }
        assertTrue(InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale >= 1.29f)
        compose.onNodeWithTag("avatar-save").assertIsDisplayed()
        compose.onNodeWithTag("avatar-choose").performScrollTo().assertIsDisplayed()
        captureNativeFixture("avatar-preview-dark-font130")
        compose.onNodeWithText("Chỉ ảnh bạn chọn được gửi lên AnVề.").performScrollTo().assertIsDisplayed()
    }
}
