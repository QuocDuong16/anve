package com.anve.app.ui.history

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.material3.Surface
import com.anve.app.ui.captureNativeFixture
import com.anve.app.ui.diagnostics.LargeFontScale
import com.anve.app.ui.theme.AnVeTheme
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anve.app.R
import com.anve.app.data.api.HistoryRetentionDto
import com.anve.app.data.api.HistoryPageDto
import com.anve.app.data.api.HistoryPointDto
import com.anve.app.data.location.HistoryMember
import com.anve.app.data.location.HistoryDataSource
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryScreenInstrumentedTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun historyDateNavigationDoesNotNeedMapsKeyOrNetwork() {
        val vm = HistoryViewModel(FakeHistory())
        compose.setContent {
            val state by vm.state.collectAsState()
            HistoryContent(state, vm)
        }
        compose.runOnIdle { vm.activate("owner"); vm.select("owner", LocalDate.of(2020, 3, 8)) }
        compose.onNodeWithText("Lịch sử vị trí").assertIsDisplayed()
        // The date renders in the vi-VN display format, not ISO.
        compose.onNodeWithText("08/03/2020").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ngày trước").performClick()
        compose.onNodeWithText("07/03/2020").assertIsDisplayed()
    }

    @Test
    fun accountSwitchClearsVisibleHistory() {
        val vm = HistoryViewModel(FakeHistory())
        compose.setContent {
            val state by vm.state.collectAsState()
            HistoryContent(state, vm)
        }
        compose.runOnIdle { vm.activate("owner") }
        compose.onNodeWithText("đồng bộ trễ", substring = true).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { vm.activate("other") }
        compose.onNodeWithText("đồng bộ trễ", substring = true).assertDoesNotExist()
    }

    @Test fun lightLayout() = captureLayout(false)
    @Test fun darkLayoutAtLargeFont() = captureLayout(true)

    private fun captureLayout(dark: Boolean) {
        val vm = HistoryViewModel(FakeHistory())
        compose.setContent {
            val state by vm.state.collectAsState()
            LargeFontScale(enabled = dark) {
                AnVeTheme(darkTheme = dark) { Surface(Modifier.systemBarsPadding()) { HistoryContent(state, vm) } }
            }
        }
        compose.runOnIdle { vm.activate("owner") }
        compose.onNodeWithText("Lịch sử vị trí").assertIsDisplayed()
        captureNativeFixture("history-${if (dark) "dark-font130" else "light"}")
        compose.onNodeWithContentDescription("Thao tác lịch sử").performClick()
        compose.onNodeWithText("Xóa ngày này").performClick()
        compose.onNodeWithText("Xóa lịch sử?").assertIsDisplayed()
        compose.onNodeWithText("Hủy").performClick()
        compose.onNodeWithText("đồng bộ trễ", substring = true).performScrollTo().performClick()
        // The point row uses selectable(): the selection lands in semantics,
        // not only in the row's text color.
        compose.onNodeWithText("đồng bộ trễ", substring = true).assertIsSelected()
    }

    @Test
    fun retentionDecreaseConfirmsIncreaseCommitsDirectly() {
        val fake = FakeHistory()
        val vm = HistoryViewModel(fake)
        compose.setContent {
            val state by vm.state.collectAsState()
            HistoryContent(state, vm)
        }
        compose.runOnIdle { vm.activate("owner") }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val oneDayChip = context.getString(R.string.history_retention_chip_days, 1)
        // Shortening the horizon deletes data, so the tap opens the confirm
        // dialog and stores nothing until the user confirms.
        compose.onNodeWithText(oneDayChip).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.history_retention_confirm_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        compose.waitForIdle()
        assertTrue("Cancel must not store a retention change", fake.retentionCalls.isEmpty())
        // The same decrease, confirmed: stored exactly once with the chosen value.
        compose.onNodeWithText(oneDayChip).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.history_retention_confirm)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(1), fake.retentionCalls)
        // The saved snackbar overlays the bottom of the screen and lingers in
        // real time; wait it out so it cannot swallow the next chip tap.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(context.getString(R.string.history_retention_saved, 1))
                .fetchSemanticsNodes().isEmpty()
        }
        // Raising the horizon deletes nothing, so it commits in one tap and
        // never shows the dialog.
        compose.onNodeWithText(context.getString(R.string.history_retention_chip_days, 30)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(1, 30), fake.retentionCalls)
        compose.onNodeWithText(context.getString(R.string.history_retention_confirm_title)).assertDoesNotExist()
    }

    private class FakeHistory : HistoryDataSource {
        // Retention values the screen asked to store, in call order.
        val retentionCalls = mutableListOf<Int>()
        override suspend fun members(owner: String): List<HistoryMember> = emptyList()
        override suspend fun page(owner: String, member: String, from: String, to: String, cursor: String?) =
            HistoryPageDto(if (owner == "owner") listOf(HistoryPointDto("event", 10.0, 106.0, 10.0, "2026-10-29T00:00:00Z", "2026-10-29T01:00:00Z", "fused", false, true, "segment")) else emptyList())
        override suspend fun retention(owner: String) = HistoryRetentionDto(30, 30)
        override suspend fun setRetention(owner: String, days: Int): HistoryRetentionDto {
            retentionCalls += days
            return HistoryRetentionDto(days, days)
        }
        override suspend fun delete(owner: String, from: String?, to: String) = Unit
    }
}
