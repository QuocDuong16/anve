package com.anve.app.ui.sos

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.anve.app.data.api.MemberDto
import com.anve.app.data.api.SosEventDto
import com.anve.app.data.api.SosRecipientDto
import com.anve.app.data.auth.DataStoreSessionStorage
import com.anve.app.data.sos.SosCachedMember
import com.anve.app.data.sos.SosDataSource
import com.anve.app.data.sos.SosLocalState
import com.anve.app.data.sos.SosOutbox
import com.anve.app.data.sos.SosOutboxItem
import com.anve.app.data.sos.SosSyncEngine
import com.anve.app.data.sos.SosSyncGateway
import com.anve.app.data.sos.SosSyncScheduler
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SosScreenInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun explicitRecipientAndConfirmationAreRequired() {
        val box = FakeBox()
        val source = FakeSource()
        val gateway = object : SosSyncGateway {
            override suspend fun create(owner: String, item: SosOutboxItem) = Unit
            override suspend fun cancel(owner: String, eventId: String) = Unit
        }
        val vm = SosViewModel(source, box, SosSyncEngine(box, DataStoreSessionStorage(ApplicationProvider.getApplicationContext()), gateway),
            object : SosSyncScheduler { override fun schedule(owner: String) = Unit })
        val state = mutableStateOf(SosState(owner = "owner", members = listOf(SosCachedMember("owner", "member", "An", 0))))
        compose.setContent { SosContent(state.value, vm, false, null, {}) }
        compose.onNodeWithText("Gửi yêu cầu SOS").assertIsNotEnabled()
        compose.onNodeWithText("An").assertIsDisplayed()
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.onNodeWithText("Gửi yêu cầu SOS").performClick()
        compose.onNodeWithText("Gửi yêu cầu hỗ trợ?").assertIsDisplayed()
        compose.onNodeWithText("Xác nhận gửi").assertIsDisplayed()
        compose.onNodeWithText("Quay lại").performClick()
        compose.runOnIdle { state.value = SosState(owner = "other") }
        compose.onNodeWithText("Gửi yêu cầu SOS").assertIsNotEnabled()
        compose.onNodeWithText("An").assertDoesNotExist()
    }

    private class FakeSource : SosDataSource {
        override suspend fun members(owner: String): List<MemberDto> = emptyList()
        override suspend fun events(owner: String): List<SosEventDto> = emptyList()
        override suspend fun event(owner: String, id: String): SosEventDto = error("unused")
        override suspend fun recipients(owner: String, id: String): List<SosRecipientDto> = emptyList()
        override suspend fun revokeRecipient(owner: String, id: String, recipient: String) = Unit
        override suspend fun acknowledge(owner: String, id: String) = Unit
        override suspend fun resolve(owner: String, id: String) = Unit
        override suspend fun cancel(owner: String, id: String) = Unit
        override suspend fun pushRegistered(owner: String): Boolean = false
        override suspend fun pushConfigured(owner: String): Boolean = false
    }

    private class FakeBox : SosOutbox {
        override suspend fun read(owner: String): List<SosOutboxItem> = emptyList()
        override suspend fun enqueue(item: SosOutboxItem) = Unit
        override suspend fun replace(owner: String, id: String, expected: SosLocalState, next: SosLocalState, cancelRequestedAtMillis: Long?) = false
        override suspend fun removeFinished(owner: String, id: String) = false
        override suspend fun cachedMembers(owner: String): List<SosCachedMember> = emptyList()
        override suspend fun saveMembers(owner: String, members: List<SosCachedMember>) = Unit
    }
}
