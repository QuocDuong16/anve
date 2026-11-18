package com.anve.app.ui.common

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anve.app.R
import com.anve.app.ui.theme.AnVeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SosHoldButtonInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun shortTapShowsHintWithoutTriggeringAndFullHoldTriggers() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var triggered = 0
        compose.setContent {
            AnVeTheme {
                AnVeSosHoldButton(onTriggered = { triggered++ })
            }
        }

        compose.onNodeWithContentDescription("SOS").performTouchInput {
            down(center)
            advanceEventTime(200)
            up()
        }
        compose.runOnIdle { assertEquals(0, triggered) }
        compose.onNodeWithText(context.getString(R.string.map_sos_hold_hint)).assertIsDisplayed()

        compose.onNodeWithContentDescription("SOS").performTouchInput {
            down(center)
        }
        compose.waitUntil(3_000) { triggered == 1 }
        compose.onNodeWithContentDescription("SOS").performTouchInput {
            up()
        }
        compose.runOnIdle { assertEquals(1, triggered) }
    }

    @Test
    fun accessibilityClickTriggersDirectly() {
        var triggered = 0
        compose.setContent {
            AnVeTheme {
                AnVeSosHoldButton(onTriggered = { triggered++ })
            }
        }
        compose.onNodeWithContentDescription("SOS").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(1, triggered) }
    }
}
