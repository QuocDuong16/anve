package com.anve.app.ui.places

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anve.app.data.api.PlaceDto
import com.anve.app.data.api.PlaceEventDto
import com.anve.app.data.api.PlaceInputDto
import com.anve.app.data.api.PlaceMonitorDto
import com.anve.app.data.location.PlacesDataSource
import com.anve.app.data.location.SharingManagementSnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlacesScreenInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editorWorksWithoutMapsCredentials() {
        val vm = PlacesViewModel(FakePlaces())
        compose.setContent { val state by vm.state.collectAsState(); PlacesContent(state, vm) }
        compose.runOnIdle { vm.activate("owner") }
        compose.onNodeWithText("Thêm địa điểm").performClick()
        compose.onNodeWithText("Bản đồ chưa được cấu hình", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Vĩ độ").assertIsDisplayed()
    }

    @Test fun accountSwitchClearsEventFeed() {
        val vm = PlacesViewModel(FakePlaces())
        compose.setContent { val state by vm.state.collectAsState(); PlacesContent(state, vm) }
        compose.runOnIdle { vm.activate("owner") }
        compose.onNodeWithText("xử lý trễ", substring = true).assertIsDisplayed()
        compose.runOnIdle { vm.activate("other") }
        compose.onNodeWithText("xử lý trễ", substring = true).assertDoesNotExist()
    }

    @Test fun editorClosesWhenAuthorizationCannotBeRechecked() {
        val vm = PlacesViewModel(FakePlaces())
        val place = PlaceDto("place", "owner", "circle", "Nhà", 10.0, 106.0, 150.0, "", "")
        val state = mutableStateOf(PlacesState(owner = "owner", places = listOf(place), selectedId = place.id))
        compose.setContent { PlacesContent(state.value, vm) }
        compose.onNodeWithText("Sửa").performClick()
        compose.onNodeWithText("Sửa địa điểm").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(places = emptyList(), selectedId = null, error = "Không thể kiểm tra quyền") }
        compose.onNodeWithText("Sửa địa điểm").assertDoesNotExist()
        compose.onNodeWithText("Không thể kiểm tra quyền").assertIsDisplayed()
    }

    private class FakePlaces : PlacesDataSource {
        override suspend fun places(owner: String): List<PlaceDto> = emptyList()
        override suspend fun events(owner: String) = if (owner == "owner") listOf(
            PlaceEventDto("event", "place", "Nhà", "owner", "An", "ENTER", "2026-01-01T00:00:00Z", "2026-01-01T00:10:00Z", true, "OBSERVED"),
        ) else emptyList()
        override suspend fun members(owner: String) = SharingManagementSnapshot()
        override suspend fun access(owner: String, place: String) = emptyList<String>()
        override suspend fun eligibleRecipients(owner: String, place: String) = emptyList<String>()
        override suspend fun monitor(owner: String, place: String): PlaceMonitorDto? = null
        override suspend fun create(owner: String, input: PlaceInputDto): PlaceDto = error("unused")
        override suspend fun update(owner: String, place: String, input: PlaceInputDto): PlaceDto = error("unused")
        override suspend fun delete(owner: String, place: String) = Unit
        override suspend fun share(owner: String, place: String, member: String, enabled: Boolean) = Unit
        override suspend fun configure(owner: String, place: String, recipients: List<String>): PlaceMonitorDto = error("unused")
        override suspend fun stop(owner: String, place: String) = Unit
    }
}
