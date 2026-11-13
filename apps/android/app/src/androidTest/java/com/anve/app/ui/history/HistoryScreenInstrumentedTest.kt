package com.anve.app.ui.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.material3.Surface
import com.anve.app.ui.captureNativeFixture
import com.anve.app.ui.diagnostics.SystemFontScaleRule
import com.anve.app.ui.theme.AnVeTheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anve.app.data.api.HistoryRetentionDto
import com.anve.app.data.api.HistoryPageDto
import com.anve.app.data.api.HistoryPointDto
import com.anve.app.data.location.HistoryMember
import com.anve.app.data.location.HistoryDataSource
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryScreenInstrumentedTest {
    @get:Rule(order = 0) val fontScale: org.junit.rules.TestRule = SystemFontScaleRule("darkLayoutAtLargeFont", 1.3f)
    @get:Rule(order = 1) val compose = createComposeRule()

    @Test
    fun historyDateNavigationDoesNotNeedMapsKeyOrNetwork() {
        val vm = HistoryViewModel(FakeHistory())
        compose.setContent {
            val state by vm.state.collectAsState()
            HistoryContent(state, vm)
        }
        compose.runOnIdle { vm.activate("owner"); vm.select("owner", LocalDate.of(2020, 3, 8)) }
        compose.onNodeWithText("Lịch sử vị trí").assertIsDisplayed()
        compose.onNodeWithText("2020-03-08").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ngày trước").performClick()
        compose.onNodeWithText("2020-03-07").assertIsDisplayed()
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
            AnVeTheme(darkTheme = dark) { Surface { HistoryContent(state, vm) } }
        }
        compose.runOnIdle { vm.activate("owner") }
        compose.onNodeWithText("Lịch sử vị trí").assertIsDisplayed()
        captureNativeFixture("history-${if (dark) "dark-font130" else "light"}")
        compose.onNodeWithContentDescription("Thao tác lịch sử").performClick()
        compose.onNodeWithText("Xóa ngày này").performClick()
        compose.onNodeWithText("Xóa lịch sử?").assertIsDisplayed()
        compose.onNodeWithText("Hủy").performClick()
        compose.onNodeWithText("đồng bộ trễ", substring = true).performScrollTo().performClick()
    }

    private class FakeHistory : HistoryDataSource {
        override suspend fun members(owner: String): List<HistoryMember> = emptyList()
        override suspend fun page(owner: String, member: String, from: String, to: String, cursor: String?) =
            HistoryPageDto(if (owner == "owner") listOf(HistoryPointDto("event", 10.0, 106.0, 10.0, "2026-10-29T00:00:00Z", "2026-10-29T01:00:00Z", "fused", false, true, "segment")) else emptyList())
        override suspend fun retention(owner: String) = HistoryRetentionDto(30, 30)
        override suspend fun setRetention(owner: String, days: Int) = HistoryRetentionDto(days, days)
        override suspend fun delete(owner: String, from: String?, to: String) = Unit
    }
}
