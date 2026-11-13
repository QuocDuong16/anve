package com.anve.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.anve.app.ui.diagnostics.SystemFontScaleRule
import com.anve.app.ui.signin.SignInContent
import com.anve.app.ui.signin.SignInUiState
import com.anve.app.ui.splash.SplashScreen
import com.anve.app.ui.theme.AnVeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BrandingScreenInstrumentedTest {
    @get:Rule(order = 0) val fontScale = SystemFontScaleRule("darkBrandingAtLargeFont", 1.3f)
    @get:Rule(order = 1) val compose = createComposeRule()

    @Test fun lightBranding() = captureBranding(false)
    @Test fun darkBrandingAtLargeFont() = captureBranding(true)

    private fun captureBranding(dark: Boolean) {
        var showSignIn by mutableStateOf(false)
        var attempts = 0
        compose.setContent {
            AnVeTheme(darkTheme = dark) {
                if (showSignIn) {
                    SignInContent(
                        state = SignInUiState.Ready(googleSignIn = true, devLogin = false),
                        error = "Đăng nhập chưa thành công. Vui lòng thử lại.",
                        busy = false,
                        notice = null,
                        onDismissNotice = {},
                        onGoogleSignIn = { attempts++ },
                        onDevSignIn = {},
                        onRetry = {},
                    )
                } else {
                    SplashScreen("0.1.0")
                }
            }
        }
        val suffix = if (dark) "dark-font130" else "light"
        compose.onNodeWithText("AnVề").assertIsDisplayed()
        captureNativeFixture("splash-$suffix")
        compose.runOnIdle { showSignIn = true }
        compose.onNodeWithText("Đăng nhập chưa thành công. Vui lòng thử lại.").assertIsDisplayed()
        captureNativeFixture("signin-error-$suffix")
        compose.onNodeWithText("Thử lại").performClick()
        compose.runOnIdle { assertEquals(1, attempts) }
    }
}
