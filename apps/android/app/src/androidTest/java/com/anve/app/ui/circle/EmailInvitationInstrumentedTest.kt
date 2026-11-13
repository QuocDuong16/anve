package com.anve.app.ui.circle

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Scaffold
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.anve.app.data.api.ReceivedInvitationDto
import com.anve.app.ui.captureNativeFixture
import com.anve.app.ui.diagnostics.SystemFontScaleRule
import com.anve.app.ui.theme.AnVeTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule

class EmailInvitationInstrumentedTest {
    @get:Rule(order = 0) val systemFontScale: TestRule = SystemFontScaleRule("darkLargeTextKeepsEmailAndExpiryAccessible", 1.3f)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun emailLinkModesAndUnknownCreationHaveExplicitActions() {
        val state = mutableStateOf(PrivateInvitationsState(open = true))
        val online = mutableStateOf(true)
        var creates = 0; var checks = 0
        compose.setContent { AnVeTheme { EmailInvitationSheet(state.value, online.value, {},
            { state.value = state.value.copy(mode = it) }, { state.value = state.value.copy(email = it) },
            { state.value = state.value.copy(hours = it) }, { creates++ }, { checks++ }) } }
        compose.onNodeWithTag("invite-submit").assertIsNotEnabled()
        compose.onNodeWithTag("invite-email").performScrollTo().performTextInput("mai@example.test")
        compose.onNodeWithTag("invite-email").performImeAction()
        compose.waitForIdle()
        captureNativeFixture("email-invitation-light")
        compose.onNodeWithTag("invite-submit").performClick()
        compose.runOnIdle { assertEquals(1, creates); online.value = false }
        compose.onNodeWithTag("invite-submit").assertIsNotEnabled()
        compose.runOnIdle { online.value = true; state.value = state.value.copy(unknown = true, error = "Chưa rõ kết quả tạo lời mời.") }
        compose.onNodeWithTag("invite-submit").performClick()
        compose.runOnIdle { assertEquals(1, checks); assertEquals(1, creates) }
        captureNativeFixture("email-invitation-unknown")
        compose.runOnIdle { state.value = PrivateInvitationsState(open = true) }
        compose.onNodeWithText("Chỉ tạo link/mã").performScrollTo().performClick()
        compose.onNodeWithTag("invite-email").assertDoesNotExist()
        captureNativeFixture("email-invitation-link-mode")
    }
    @Test fun darkLargeTextKeepsEmailAndExpiryAccessible() {
        compose.setContent { AnVeTheme(darkTheme = true) { EmailInvitationSheet(PrivateInvitationsState(open = true,
            email = "mai@example.test"), true, {}, {}, {}, {}, {}, {}) } }
        assertTrue(InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale >= 1.29f)
        compose.onNodeWithTag("invite-submit").assertIsDisplayed()
        compose.onNodeWithTag("invite-email").performScrollTo().assertIsDisplayed()
        captureNativeFixture("email-invitation-dark-font130")
        compose.onNodeWithText("Lời mời dùng một lần · 48 giờ").performScrollTo().performClick()
        compose.onNodeWithText("7 ngày").assertIsDisplayed()
    }
    @Test fun receivedContextExposesInviterAndPrivateHideWithoutJoining() {
        var reviews = 0; var hides = 0
        compose.runOnIdle {
            WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
        val state = PrivateInvitationsState(loading = false, received = listOf(ReceivedInvitationDto(
            "synthetic-invite", "synthetic-circle", "Nhà mình", "Mai", Instant.now().plusSeconds(3600).toString())))
        compose.setContent { AnVeTheme { Scaffold { insets ->
            Column(Modifier.padding(insets).padding(16.dp)) { ReceivedInvitations(state, {}, { reviews++ }, { hides++ }) }
        } } }
        compose.onNodeWithText("Mai mời bạn tham gia").assertIsDisplayed()
        compose.onNodeWithText("Xem lời mời").performClick()
        compose.onNodeWithText("Ẩn với tôi").performClick()
        compose.runOnIdle { assertEquals(1, reviews); assertEquals(1, hides) }
        captureNativeFixture("received-invitation-context")
    }
}
