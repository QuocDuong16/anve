package com.anve.app.ui.journey

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.espresso.Espresso.pressBack
import com.anve.app.data.api.JourneyDestinationDto
import com.anve.app.data.api.JourneyDto
import com.anve.app.data.api.PlaceDto
import com.anve.app.data.location.AudienceMember
import com.anve.app.tracking.TrackingStatus
import com.anve.app.ui.captureNativeFixture
import com.anve.app.ui.diagnostics.LargeFontScale
import com.anve.app.ui.theme.AnVeTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class JourneyAutomationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val now = Instant.now()
    private val form = JourneyUiState(owner = "owner", loading = false, consentEnabled = true,
        places = listOf(PlaceDto("home", "owner", "circle", "Nhà", 21.0, 105.0, 100.0, now.toString(), now.toString())),
        members = listOf(AudienceMember("mother", "Mẹ", "member", true)))
    private val tracking = TrackingStatus(ownerUserId = "owner", running = true, serviceRunning = true, providerActive = true, online = true)

    @Test fun optionsOnlyApplyToExplicitJourneyCreation() {
        var chosen: Pair<Boolean, Int>? = null
        compose.setContent { AnVeTheme { JourneyContent(form, tracking, initiallyCreating = true,
            onCreateWithOptions = { _, _, _, automatic, stale -> chosen = automatic to stale }) } }
        compose.onNode(hasText("Nhà") and hasClickAction()).performClick()
        compose.onNodeWithTag("journey-content").performScrollToNode(hasText("Mẹ"))
        compose.onNode(hasText("Mẹ") and hasClickAction()).performClick()
        openOptions()
        compose.onNodeWithTag("automatic-arrival").assertIsOff()
        compose.onNodeWithTag("stale-reminder").assertIsOff().performClick()
        compose.onNode(hasText("10 phút") and hasClickAction()).performClick()
        compose.waitForIdle()
        captureNativeFixture("automation-options-light")
        compose.onNodeWithTag("automatic-arrival").performClick()
        compose.runOnIdle { assertNull(chosen) }
        apply()
        compose.onNodeWithTag("journey-content").performScrollToNode(hasText("Bắt đầu hành trình"))
        compose.onNode(hasText("Bắt đầu hành trình") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(true to 10, chosen) }
    }

    @Test fun backDiscardsUnappliedAutomationDraft() {
        compose.setContent { AnVeTheme { JourneyContent(form, tracking, initiallyCreating = true) } }
        openOptions()
        compose.onNodeWithTag("automatic-arrival").performClick()
        compose.onNodeWithTag("stale-reminder").performClick()
        pressBack()
        compose.waitForIdle()
        openOptions()
        compose.onNodeWithTag("automatic-arrival").assertIsOff()
        compose.onNodeWithTag("stale-reminder").assertIsOff()
        apply()
        compose.onNodeWithText("Đang tắt").assertIsDisplayed()
    }

    @Test fun optionsDarkLargeTextKeepsThresholdAndApplyReachable() {
        var applied: Pair<Boolean, Int>? = null
        compose.setContent { LargeFontScale { AnVeTheme(darkTheme = true) { JourneyOptionsSheet(false, 10, {}, { automatic, stale -> applied = automatic to stale }) } } }
        compose.onNodeWithTag("automatic-arrival").assertIsOff()
        compose.onNodeWithTag("stale-reminder").assertIsOn()
        compose.waitForIdle()
        captureNativeFixture("automation-options-dark-font130")
        compose.onNodeWithTag("journey-options-sheet").performScrollToNode(hasText("30 phút"))
        compose.onNode(hasText("30 phút") and hasClickAction()).performClick()
        apply()
        compose.runOnIdle { assertEquals(false to 30, applied) }
    }

    @Test fun automaticArrivalIsLabelledAsEstimateAndMissingUpdatesRecover() {
        val id = "f93d8873-ac39-470c-9e79-ab694e2c80d5"
        val journey = JourneyDto(id, "other", "An", "ACTIVE", now.minusSeconds(900).toString(), now.plusSeconds(3600).toString(),
            JourneyDestinationDto("Nhà", 21.0, 105.0), automaticArrival = true, staleAfterMinutes = 5,
            staleSince = now.minusSeconds(60).toString(), staleEpisode = 1)
        val current = mutableStateOf(journey)
        compose.setContent { AnVeTheme { JourneyContent(JourneyUiState(owner = "viewer", loading = false, journeys = listOf(current.value), selectedId = id), tracking) } }
        compose.onNodeWithTag("journey-content").performScrollToNode(hasText("Hành trình chưa có cập nhật mới", substring = true))
        compose.onNodeWithText("Hành trình chưa có cập nhật mới", substring = true).assertIsDisplayed()
        captureNativeFixture("automation-stale-viewer-light")
        compose.runOnIdle { current.value = journey.copy(state = "ARRIVED", arrivalSource = "AUTOMATIC", endedAt = now.toString(), staleSince = null, staleEpisode = 0) }
        compose.onNodeWithText("Hành trình chưa có cập nhật mới", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("journey-content").performScrollToNode(hasText("Đến nơi tự động — ước tính"))
        compose.onNodeWithText("Đến nơi tự động — ước tính").assertIsDisplayed()
        compose.onNodeWithText("Chủ hành trình đã xác nhận đến nơi").assertDoesNotExist()
        compose.onNodeWithText("Đã đến nơi").assertDoesNotExist()
        captureNativeFixture("automation-arrival-estimate-light")
    }

    private fun openOptions() {
        compose.onNodeWithTag("journey-content").performScrollToNode(hasText("Tự động và nhắc nhở"))
        compose.onNodeWithTag("journey-options").performClick()
        compose.waitForIdle()
    }
    private fun apply() {
        compose.onNodeWithText("Áp dụng").assertIsDisplayed().performClick()
        compose.waitForIdle()
    }
}
