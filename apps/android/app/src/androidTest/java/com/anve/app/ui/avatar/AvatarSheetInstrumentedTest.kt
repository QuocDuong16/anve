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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.anve.app.data.api.AvatarStateDto
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
            AvatarFrame("An", bitmap, Modifier.size(160.dp))
        } } }
        compose.onNodeWithTag("avatar-choose").performScrollTo().performClick()
        compose.onNodeWithTag("avatar-remove").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, saves); assertEquals(1, chooses); assertEquals(1, removes) }
        captureNativeFixture("avatar-preview-light")
        compose.onNodeWithTag("avatar-save").performClick()
        compose.runOnIdle { assertEquals(1, saves); state.value = ready.copy(removeDraft = true, selection = null) }
        compose.onNodeWithText("Ảnh hiện tại sẽ được xóa sau khi bạn lưu.").performScrollTo().assertIsDisplayed()
        captureNativeFixture("avatar-removal-draft")
    }

    @Test fun loadingOfflineProcessingAndUnknownPreventWriteAndOfferReadOnlyRecovery() {
        val state = mutableStateOf(AvatarUiState(open = true))
        val online = mutableStateOf(false)
        var saves = 0; var checks = 0
        compose.setContent { AnVeTheme { AvatarSheet(state.value, online.value, {}, {}, {}, { saves++ }, { checks++ }, {}) {
            AvatarFrame("An", null, Modifier.size(160.dp))
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
