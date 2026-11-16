package com.anve.app.ui.update

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.anve.app.R
import com.anve.app.ui.diagnostics.LargeFontScale
import com.anve.app.ui.theme.AnVeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ForceUpdateDialogInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun updateModalOffersUpdateAndExitActions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var confirmClicks = 0
        var exitClicks = 0
        compose.setContent {
            AnVeTheme(darkTheme = false) {
                ForceUpdateDialog(
                    state = AppUpdateUiState(modalVisible = true, availableVersionCode = 25),
                    onConfirmUpdate = { confirmClicks++ },
                    onOpenPlayStore = {},
                    onExitApp = { exitClicks++ },
                )
            }
        }

        compose.onNodeWithTag("force-update-dialog").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.app_update_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.app_update_body)).assertIsDisplayed()

        compose.onNodeWithTag("force-update-confirm").performClick()
        compose.onNodeWithTag("force-update-later").performClick()
        compose.runOnIdle {
            assertEquals(1, confirmClicks)
            assertEquals(1, exitClicks)
        }
    }

    @Test
    fun errorStateShowsStoreFallbackActionInDarkLargeFont() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var openStoreClicks = 0
        compose.setContent {
            LargeFontScale {
                AnVeTheme(darkTheme = true) {
                    ForceUpdateDialog(
                        state = AppUpdateUiState(
                            modalVisible = true,
                            availableVersionCode = 25,
                            errorRes = R.string.app_update_launch_error,
                        ),
                        onConfirmUpdate = {},
                        onOpenPlayStore = { openStoreClicks++ },
                        onExitApp = {},
                    )
                }
            }
        }

        compose.onNodeWithText(context.getString(R.string.app_update_launch_error)).assertIsDisplayed()
        compose.onNodeWithTag("force-update-open-play").performClick()
        compose.runOnIdle { assertEquals(1, openStoreClicks) }
    }
}
