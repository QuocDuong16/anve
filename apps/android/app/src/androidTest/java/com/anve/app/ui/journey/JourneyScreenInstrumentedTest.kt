package com.anve.app.ui.journey

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.graphics.HardwareRendererCompat
import com.anve.app.R
import com.anve.app.data.api.JourneyDestinationDto
import com.anve.app.data.api.JourneyDto
import com.anve.app.data.api.JourneyProgressDto
import com.anve.app.data.api.JourneyRecipientDto
import com.anve.app.data.api.PlaceDto
import com.anve.app.data.journey.JourneyAction
import com.anve.app.data.journey.JourneyOperation
import com.anve.app.data.location.AudienceMember
import com.anve.app.tracking.MotionState
import com.anve.app.tracking.TrackingStatus
import com.anve.app.ui.diagnostics.LargeFontScale
import com.anve.app.ui.theme.AnVeTheme
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** Synthetic UI fixtures verify controls and rendering, not GPS or Firebase. */
class JourneyScreenInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.now()
    private val journey = JourneyDto("synthetic-journey", "owner", "An", "ACTIVE", now.minusSeconds(600).toString(), now.plusSeconds(6600).toString(),
        JourneyDestinationDto("Về nhà", 21.0, 105.0), recipients = listOf(JourneyRecipientDto("mother", "Mẹ"), JourneyRecipientDto("father", "Bố")),
        progress = JourneyProgressDto(now.minusSeconds(120).toString(), 3200.0, 18.0, true))
    private val state = JourneyUiState(owner = "owner", journeys = listOf(journey), selectedId = journey.id, consentEnabled = true, loading = false)
    private val tracking = TrackingStatus(ownerUserId = "owner", running = true, serviceRunning = true, providerActive = true, online = false, motion = MotionState.JOURNEY)

    @Test fun ownerActionsRequireConfirmationAndKeepManualArrivalSeparateFromEnd() {
        var action: JourneyAction? = null
        compose.setContent { AnVeTheme { JourneyContent(state, tracking, onAction = { _, selected, _ -> action = selected }) } }
        capture("active-light")
        scrollTo("Đã đến nơi")
        compose.onNode(hasText("Đã đến nơi") and hasClickAction()).performClick()
        compose.onNodeWithText("Xác nhận đã đến nơi?").assertIsDisplayed()
        capture("arrival-dialog-light")
        // The confirmation dialog follows the shared destructive-confirm
        // convention: a neutral "Hủy" dismiss, resolved from resources.
        compose.onNodeWithText(
            ApplicationProvider.getApplicationContext<Context>().getString(R.string.action_cancel),
        ).performClick()
        compose.runOnIdle { assertNull(action) }
        compose.onNode(hasText("Đã đến nơi") and hasClickAction()).performClick()
        compose.onAllNodes(hasText("Đã đến nơi") and hasClickAction())[1].performClick()
        compose.runOnIdle { assertEquals(JourneyAction.ARRIVE, action) }
        scrollTo("Kết thúc hành trình")
        compose.onNode(hasText("Kết thúc hành trình") and hasClickAction()).performClick()
        compose.onNodeWithText("Kết thúc hành trình?").assertIsDisplayed()
        compose.onAllNodes(hasText("Kết thúc hành trình") and hasClickAction())[1].performClick()
        compose.runOnIdle { assertEquals(JourneyAction.END, action) }
    }

    @Test fun viewerNeverGetsOwnerMutationControlsOrOtherRecipients() {
        val viewerJourney = journey.copy(recipients = emptyList())
        compose.setContent { AnVeTheme { JourneyContent(state.copy(owner = "viewer", journeys = listOf(viewerJourney)), tracking.copy(ownerUserId = "viewer")) } }
        compose.onNodeWithText("Đã đến nơi").assertDoesNotExist()
        compose.onNodeWithText("Kết thúc hành trình").assertDoesNotExist()
        compose.onNodeWithText("Quản lý người xem").assertDoesNotExist()
        compose.onNodeWithText("Hành trình của An").assertIsDisplayed()
        capture("viewer-light")
    }

    @Test fun creationSelectsExplicitPlaceDurationAndRecipients() {
        var chosen: Triple<String, Int, Set<String>>? = null
        val form = formState()
        compose.setContent { AnVeTheme { JourneyContent(form, tracking.copy(online = true), initiallyCreating = true,
            onCreate = { place, minutes, recipients -> chosen = Triple(place.id, minutes, recipients) }) } }
        compose.onNode(hasText("Nhà") and hasClickAction()).performClick()
        compose.onNode(hasText("1 giờ") and hasClickAction()).performClick()
        scrollTo("Mẹ")
        compose.onNode(hasText("Mẹ") and hasClickAction()).performClick()
        capture("create-light")
        scrollTo("Bắt đầu hành trình")
        compose.onNode(hasText("Bắt đầu hành trình") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(Triple("home", 60, setOf("mother")), chosen) }
        capture("create-light-actions")
    }

    @Test fun missingConsentAndTrackingExplainRecoveryAndDisableStart() {
        compose.setContent { AnVeTheme { JourneyContent(formState().copy(consentEnabled = false), TrackingStatus(ownerUserId = "owner"), initiallyCreating = true) } }
        scrollTo("Mở màn Vị trí")
        compose.onNodeWithText("Mở màn Vị trí").assertIsDisplayed()
        scrollTo("Bắt đầu hành trình")
        compose.onNode(hasText("Bắt đầu hành trình") and hasClickAction()).assertIsNotEnabled()
        capture("consent-tracking-required")
    }

    @Test fun unknownCreateBlocksDuplicateStartAndOffersSameIdCancellation() {
        var cancelled: String? = null
        val pending = JourneyOperation(owner = "owner", journeyId = "same-stable-id", action = JourneyAction.CREATE, createdAtMillis = 0)
        compose.setContent { AnVeTheme { JourneyContent(JourneyUiState(owner = "owner", pending = listOf(pending), loading = false), tracking.copy(online = true),
            onAction = { id, action, _ -> if (action == JourneyAction.CANCEL) cancelled = id }) } }
        compose.onNode(hasText("Bắt đầu hành trình") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("Hủy hành trình đang chờ").performClick()
        compose.runOnIdle { assertEquals("same-stable-id", cancelled) }
        capture("unknown-create")
    }

    @Test fun darkLargeTextPreservesArrivalDialogAndEndAction() {
        compose.setContent { LargeFontScale { AnVeTheme(darkTheme = true) { JourneyContent(state, tracking) } } }
        capture("active-dark-font130")
        scrollTo("Kết thúc hành trình")
        compose.onNode(hasText("Kết thúc hành trình") and hasClickAction()).assertIsDisplayed()
        scrollTo("Đã đến nơi")
        compose.onNode(hasText("Đã đến nơi") and hasClickAction()).performClick()
        compose.onNodeWithText("Xác nhận đã đến nơi?").assertIsDisplayed()
        compose.onNodeWithText(
            ApplicationProvider.getApplicationContext<Context>().getString(R.string.action_cancel),
        ).assertIsDisplayed()
        capture("arrival-dialog-dark-font130")
    }

    @Test fun emptyStateOffersCreationWithoutInventingProgress() {
        compose.setContent { AnVeTheme { JourneyContent(JourneyUiState(owner = "owner", loading = false), tracking.copy(online = true)) } }
        compose.onNodeWithText("Chủ động chia sẻ một hành trình").assertIsDisplayed()
        capture("empty-light")
    }

    @Test fun loadingDoesNotPretendAnEmptyJourneyWasConfirmed() {
        compose.setContent { AnVeTheme { JourneyContent(JourneyUiState(owner = "owner"), tracking) } }
        compose.onNodeWithText("Chủ động chia sẻ một hành trình").assertDoesNotExist()
        capture("loading-light")
    }

    @Test fun terminalArrivalRemovesOwnerActions() {
        compose.setContent { AnVeTheme { JourneyContent(state.copy(journeys = listOf(journey.copy(state = "ARRIVED", progress = null))), tracking.copy(online = true)) } }
        compose.onNodeWithText("Đã đến nơi").assertDoesNotExist()
        compose.onNodeWithText("Kết thúc hành trình").assertDoesNotExist()
        capture("arrived-light")
    }

    @Test fun selectedRecipientRemovalHasItsOwnConfirmation() {
        var removed: String? = null
        compose.setContent { AnVeTheme { JourneyContent(state, tracking, onAction = { _, action, recipient ->
            if (action == JourneyAction.REVOKE_RECIPIENT) removed = recipient
        }) } }
        scrollTo("Quản lý người xem")
        compose.onNodeWithText("Quản lý người xem").performClick()
        scrollTo("Thu hồi")
        capture("recipients-light")
        compose.onAllNodes(hasText("Thu hồi") and hasClickAction())[0].performClick()
        compose.onNodeWithText("Ngừng cho Mẹ theo dõi?").assertIsDisplayed()
        compose.onNodeWithText("Thu hồi người xem").performClick()
        compose.runOnIdle { assertEquals("mother", removed) }
    }

    @Test fun losingNetworkDisablesAnOtherwiseValidStart() {
        val online = mutableStateOf(true)
        compose.setContent { AnVeTheme { JourneyContent(formState(), tracking.copy(online = online.value), initiallyCreating = true) } }
        compose.onNode(hasText("Nhà") and hasClickAction()).performClick()
        scrollTo("Mẹ")
        compose.onNode(hasText("Mẹ") and hasClickAction()).performClick()
        scrollTo("Bắt đầu hành trình")
        compose.onNode(hasText("Bắt đầu hành trình") and hasClickAction()).assertIsEnabled()
        compose.runOnIdle { online.value = false }
        compose.onNode(hasText("Bắt đầu hành trình") and hasClickAction()).assertIsNotEnabled()
        capture("create-offline")
    }

    @Test fun localExpiryNeverConfirmsArrivalWhileWaitingForServer() {
        val old = journey.copy(expiresAt = now.minusSeconds(1).toString())
        compose.setContent { AnVeTheme { JourneyContent(state.copy(journeys = listOf(old)), tracking.copy(online = true)) } }
        scrollTo("Đã đến nơi")
        compose.onNode(hasText("Đã đến nơi") and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasText("Kết thúc hành trình") and hasClickAction()).assertIsEnabled()
        capture("expiry-awaiting-server")
    }

    private fun formState() = JourneyUiState(owner = "owner", loading = false, consentEnabled = true,
        places = listOf(PlaceDto("home", "owner", "circle", "Nhà", 21.0, 105.0, 100.0, now.toString(), now.toString())),
        members = listOf(AudienceMember("mother", "Mẹ", "member", true), AudienceMember("father", "Bố", "member", true)))

    private fun scrollTo(text: String) { compose.onNodeWithTag("journey-content").performScrollToNode(hasText(text)) }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir("ui-validation"))
        File(directory, "journey-$name-font-scale.txt").writeText("androidFontScale=${instrumentation.targetContext.resources.configuration.fontScale}\n")
        val roots = compose.onAllNodes(isRoot())
        val bitmap = roots[0].captureToImage().asAndroidBitmap()
        File(directory, "journey-$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        if (roots.fetchSemanticsNodes().size > 1) {
            val previous = HardwareRendererCompat.isDrawingEnabled()
            try {
                HardwareRendererCompat.setDrawingEnabled(true)
                compose.waitForIdle()
                instrumentation.uiAutomation.waitForIdle(200, 5_000)
                File(directory, "journey-dialog-$name.png").outputStream().use {
                    check(requireNotNull(instrumentation.uiAutomation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { HardwareRendererCompat.setDrawingEnabled(previous) }
        }
    }
}
