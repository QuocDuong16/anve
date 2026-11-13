package com.anve.app.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.anve.app.AnVeApplication
import com.anve.app.ui.home.HomeScreen
import com.anve.app.ui.theme.AnVeTheme
import org.junit.Rule
import org.junit.Test

/** Exercises navigation without a session; it does not send an SOS or request GPS. */
class HomeNavigationInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fourTabsAndSecondaryScreensHaveReturnRoutes() {
        val container = ApplicationProvider.getApplicationContext<AnVeApplication>().container
        compose.setContent { AnVeTheme { HomeScreen(container) } }
        compose.onAllNodes(isSelectable()).assertCountEquals(4)
        compose.onNodeWithText("Cài đặt chia sẻ").assertIsDisplayed()
        compose.onNodeWithText("Tài khoản").performClick()
        compose.onNodeWithContentDescription("Quay lại").performClick()
        compose.onNodeWithText("Cài đặt chia sẻ").assertIsDisplayed()
        compose.onNodeWithText("SOS").performClick()
        compose.onNodeWithContentDescription("Quay lại").performClick()
        compose.onNodeWithText("Cài đặt chia sẻ").assertIsDisplayed()
    }
}
