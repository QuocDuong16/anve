package com.anve.app.ui.onboarding

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.anve.app.R
import com.anve.app.data.api.CircleDto
import com.anve.app.data.api.InviteDto
import com.anve.app.tracking.LocationPermissionState
import com.anve.app.tracking.PermissionSnapshot
import com.anve.app.ui.captureNativeFixture
import com.anve.app.ui.diagnostics.LargeFontScale
import com.anve.app.ui.theme.AnVeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OnboardingScreenInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun onboardingStepsRenderControlsAndAllowSkippingWithoutBlocking() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val state = mutableStateOf(OnboardingUiState(step = OnboardingStep.WELCOME))
        var skippedAll = false
        var skippedPerms = false
        var skippedPlace = false
        var finished = false

        compose.setContent {
            AnVeTheme {
                OnboardingContent(
                    state = state.value,
                    onStartGuided = { state.value = state.value.copy(step = OnboardingStep.FAMILY) },
                    onSkipAllToHome = { skippedAll = true },
                    onContinueFromFamily = { state.value = state.value.copy(step = OnboardingStep.PERMISSIONS) },
                    onSkipFamilyStep = { state.value = state.value.copy(step = OnboardingStep.PERMISSIONS) },
                    onSkipPermissionsStep = {
                        skippedPerms = true
                        state.value = state.value.copy(step = OnboardingStep.AUDIENCE_AND_PLACE)
                    },
                    onSkipPlaceAndContinue = {
                        skippedPlace = true
                        state.value = state.value.copy(step = OnboardingStep.COMPLETE)
                    },
                    onEnterApp = { finished = true },
                )
            }
        }

        // Step 1: Welcome
        compose.onNodeWithText(context.getString(R.string.onboarding_welcome_title)).assertIsDisplayed()
        compose.onNodeWithTag("onboarding-welcome-skip").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(skippedAll) }
        captureNativeFixture("onboarding-welcome-light")

        // Step 2: Family & Invite Link
        compose.onNodeWithTag("onboarding-welcome-start").performClick()
        compose.onNodeWithText(context.getString(R.string.onboarding_family_title)).assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(
                circle = CircleDto("c1", "Gia đình mình", "u1", "now"),
                isOwner = true,
                createdInvite = InviteDto("i1", "c1", "ABCD234567", "u1", "later", "now"),
            )
        }
        compose.onNodeWithText("ABCD234567").performScrollTo().assertIsDisplayed()
        captureNativeFixture("onboarding-family-light")
        compose.onNodeWithTag("onboarding-family-continue").performScrollTo().performClick()

        // Step 3: Permissions & Prominent Disclosure (skip remains enabled)
        compose.onNodeWithText(context.getString(R.string.onboarding_disclosure_title)).assertIsDisplayed()
        captureNativeFixture("onboarding-permissions-light")
        compose.onNodeWithTag("onboarding-perm-skip").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(skippedPerms) }

        // Step 4: Audience waiting + Place skip (scroll to confirm Place skip is reachable)
        compose.onNodeWithText(context.getString(R.string.onboarding_audience_waiting)).assertIsDisplayed()
        captureNativeFixture("onboarding-place-light")
        compose.onNodeWithTag("onboarding-place-skip").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(skippedPlace) }

        // Step 5: Truthful summary (permissions not enabled -> does NOT claim granted)
        compose.onNodeWithText(context.getString(R.string.onboarding_done_loc_none), substring = true).assertIsDisplayed()
        captureNativeFixture("onboarding-complete-light")
        compose.onNodeWithTag("onboarding-complete-enter").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(finished) }
    }

    @Test
    fun darkLargeFontPermissionsAndCompleteStayAccessible() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val state = mutableStateOf(
            OnboardingUiState(
                step = OnboardingStep.PERMISSIONS,
                permissions = PermissionSnapshot(
                    location = LocationPermissionState.GRANTED_PRECISE,
                    backgroundLocation = false,
                    notifications = true,
                    locationServicesEnabled = true,
                ),
                consentEnabled = true,
            ),
        )
        compose.setContent {
            LargeFontScale {
                AnVeTheme(darkTheme = true) {
                    OnboardingContent(state = state.value)
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.onboarding_disclosure_body)).assertIsDisplayed()
        compose.onNodeWithTag("onboarding-perm-skip").performScrollTo().assertIsDisplayed()
        captureNativeFixture("onboarding-permissions-dark-font130")
    }
}
