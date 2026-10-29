package com.anve.app.ui.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        compose.onNodeWithText("2020-03-08").assertIsDisplayed()
        compose.onNodeWithText("Ngày trước").performClick()
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
        compose.onNodeWithText("đồng bộ trễ", substring = true).assertIsDisplayed()
        compose.runOnIdle { vm.activate("other") }
        compose.onNodeWithText("đồng bộ trễ", substring = true).assertDoesNotExist()
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
