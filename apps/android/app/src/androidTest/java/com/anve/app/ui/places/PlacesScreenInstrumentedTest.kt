package com.anve.app.ui.places

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
    @get:Rule(order = 0) val fontScale: org.junit.rules.TestRule = SystemFontScaleRule("darkLayoutAtLargeFont", 1.3f)
    @get:Rule(order = 1) val compose = createComposeRule()

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
        compose.onNodeWithText("xử lý trễ", substring = true).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { vm.activate("other") }
        compose.onNodeWithText("xử lý trễ", substring = true).assertDoesNotExist()
    }

    @Test fun editorClosesWhenAuthorizationCannotBeRechecked() {
        val vm = PlacesViewModel(FakePlaces())
        val place = PlaceDto("place", "owner", "circle", "Nhà", 10.0, 106.0, 150.0, "", "")
        val state = mutableStateOf(PlacesState(owner = "owner", places = listOf(place), selectedId = place.id))
        compose.setContent { PlacesContent(state.value, vm) }
        compose.onNodeWithText("Nhà").performClick()
        compose.onNodeWithContentDescription("Thao tác địa điểm").performClick()
        compose.onNodeWithText("Sửa địa điểm").performClick()
        compose.onNodeWithText("Sửa địa điểm").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(places = emptyList(), selectedId = null, error = "Không thể kiểm tra quyền") }
        compose.onNodeWithText("Sửa địa điểm").assertDoesNotExist()
        compose.onNodeWithText("Không thể kiểm tra quyền").assertIsDisplayed()
    }

    @Test fun lightLayout() = captureLayout(false)
    @Test fun darkLayoutAtLargeFont() = captureLayout(true)

    private fun captureLayout(dark: Boolean) {
        val vm = PlacesViewModel(FakePlaces())
        compose.setContent {
            val state by vm.state.collectAsState()
            AnVeTheme(darkTheme = dark) { Surface { PlacesContent(state, vm) } }
        }
        compose.runOnIdle { vm.activate("owner") }
        compose.onNodeWithText("Nhà").assertIsDisplayed()
        val suffix = if (dark) "dark-font130" else "light"
        captureNativeFixture("places-list-$suffix")
        compose.onNodeWithText("Nhà").performClick()
        compose.onNodeWithText("Theo dõi vị trí của tôi tại đây").performScrollTo().assertIsDisplayed()
        captureNativeFixture("places-detail-$suffix")
        compose.onNodeWithContentDescription("Quay lại địa điểm").performScrollTo().performClick()
        compose.onNodeWithText("Thêm địa điểm").assertIsDisplayed()
    }

    private class FakePlaces : PlacesDataSource {
        override suspend fun places(owner: String): List<PlaceDto> = if (owner == "owner") listOf(PlaceDto("place", "owner", "circle", "Nhà", 10.0, 106.0, 150.0, "", "")) else emptyList()
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
