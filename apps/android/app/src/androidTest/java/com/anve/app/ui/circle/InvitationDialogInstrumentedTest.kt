package com.anve.app.ui.circle

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.anve.app.data.api.InvitePreviewDto
import com.anve.app.ui.captureNativeFixture
import com.anve.app.ui.diagnostics.LargeFontScale
import com.anve.app.ui.theme.AnVeTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class InvitationDialogInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val ready = InvitationUiState(loading = false, preview = InvitePreviewDto("synthetic-circle", "Nhà mình", Instant.now().plusSeconds(3600).toString()),
        accountLabel = "An · an@example.test")

    @Test fun previewDeferralDoesNotJoinAndConfirmationIsExplicit() {
        var joins = 0
        var dismissals = 0
        compose.setContent { AnVeTheme { InvitationDialog(ready, true, { dismissals++ }, { joins++ }, {}, {}) } }
        compose.onNodeWithText("Nhà mình").assertIsDisplayed()
        compose.onNodeWithText("Tham gia nhóm không tự bật chia sẻ vị trí.").assertIsDisplayed()
        compose.waitForIdle()
        captureNativeFixture("invitation-preview-light")
        compose.onNodeWithText("Để sau").performClick()
        compose.runOnIdle { assertEquals(0, joins); assertEquals(1, dismissals) }
        compose.onNodeWithText("Tham gia nhóm").performClick()
        compose.runOnIdle { assertEquals(1, joins) }
    }

    @Test fun loadingOfflineUnavailableAndUnknownHaveDifferentRecovery() {
        val state = mutableStateOf(InvitationUiState())
        val online = mutableStateOf(false)
        var joins = 0
        var checks = 0
        var retries = 0
        compose.setContent { AnVeTheme { InvitationDialog(state.value, online.value, {}, { joins++ }, { retries++ }, { checks++ }) } }
        compose.onNodeWithText("Đang kiểm tra lời mời với máy chủ…").assertIsDisplayed()
        compose.onNodeWithText("Tham gia nhóm").assertDoesNotExist()
        captureNativeFixture("invitation-loading-offline")
        compose.runOnIdle { state.value = ready }
        compose.onNodeWithText("Tham gia nhóm").assertIsNotEnabled()
        compose.runOnIdle { online.value = true; state.value = InvitationUiState(loading = false, retryable = true, error = "Chưa tải được lời mời. Kiểm tra mạng rồi thử lại.") }
        compose.onNodeWithText("Thử lại").performClick()
        compose.runOnIdle { assertEquals(1, retries); state.value = ready.copy(unknown = true, error = "Máy chủ chưa xác nhận tham gia.") }
        compose.onNodeWithText("Tham gia nhóm").assertDoesNotExist()
        compose.onNodeWithText("Kiểm tra kết quả").performClick()
        compose.runOnIdle { assertEquals(1, checks); assertEquals(0, joins) }
        captureNativeFixture("invitation-unknown")
        compose.runOnIdle { state.value = InvitationUiState(loading = false, error = "Lời mời không còn dùng được. Nhờ người thân gửi lời mời mới.") }
        compose.onNodeWithText("Tham gia nhóm").assertDoesNotExist()
        compose.onNodeWithText("Thử lại").assertDoesNotExist()
        captureNativeFixture("invitation-unavailable")
    }

    @Test fun darkLargeTextRetainsLongGroupAndExplicitConfirmation() {
        val name = "Gia đình An và Mai cùng những người thân yêu"
        var joins = 0
        compose.setContent { LargeFontScale { AnVeTheme(darkTheme = true) { InvitationDialog(ready.copy(preview = ready.preview!!.copy(circleName = name)), true, {}, { joins++ }, {}, {}) } } }
        compose.onNodeWithText(name).assertIsDisplayed()
        compose.waitForIdle()
        captureNativeFixture("invitation-preview-dark-font130")
        compose.onNodeWithText("Đang đăng nhập: An · an@example.test").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Tham gia nhóm").performClick()
        compose.runOnIdle { assertEquals(1, joins) }
    }

    @Test fun existingCircleBlocksJoinWithoutOfferingImplicitLeave() {
        compose.setContent { AnVeTheme { InvitationDialog(ready.copy(blocked = true, error = "Bạn đang ở trong một nhóm gia đình."), true, {}, {}, {}, {}) } }
        compose.onNodeWithText("Tham gia nhóm").assertDoesNotExist()
        compose.onNodeWithText("Bạn đang ở trong một nhóm gia đình.").performScrollTo().assertIsDisplayed()
        captureNativeFixture("invitation-existing-circle")
    }
}
