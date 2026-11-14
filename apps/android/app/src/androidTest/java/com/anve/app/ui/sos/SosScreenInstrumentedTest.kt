package com.anve.app.ui.sos

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.anve.app.R
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
import org.junit.Assert.assertEquals
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
        compose.onNodeWithText("Hủy").performClick()
        compose.runOnIdle { state.value = SosState(owner = "other") }
        compose.onNodeWithText("Gửi yêu cầu SOS").assertIsNotEnabled()
        compose.onNodeWithText("An").assertDoesNotExist()
    }

    @Test fun errorNoticeCarriesAdjacentRetryThatRefreshes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var refreshes = 0
        val source = object : FakeSource() {
            override suspend fun members(owner: String): List<MemberDto> { refreshes++; return emptyList() }
        }
        val gateway = object : SosSyncGateway {
            override suspend fun create(owner: String, item: SosOutboxItem) = Unit
            override suspend fun cancel(owner: String, eventId: String) = Unit
        }
        val vm = SosViewModel(source, FakeBox(), SosSyncEngine(FakeBox(), DataStoreSessionStorage(context), gateway),
            object : SosSyncScheduler { override fun schedule(owner: String) = Unit })
        // refresh() reads the owner from the view model's own state, so the
        // model is activated once and its initial load is drained before the
        // retry click is counted.
        compose.setContent { SosContent(SosState(owner = "owner", errorRes = R.string.sos_error_read_failed), vm, false, null, {}) }
        compose.runOnIdle { vm.activate("owner") }
        compose.waitUntil(5_000) { refreshes > 0 }
        compose.runOnIdle { refreshes = 0 }
        compose.onNodeWithText(context.getString(R.string.sos_error_read_failed)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.action_retry)).assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { refreshes > 0 }
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    // The raw ISO string never surfaces as copy when a timestamp fails to
    // parse — the HistoryScreen rule must hold on the highest-stakes surface
    // too, so a broken recordedAt shows the unknown-time fallback instead.
    @Test fun unparseableRecordedAtFallsBackToUnknownTimeNotRawIso() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val event = SosEventDto("event-iso", "owner", "not-a-timestamp", "not-a-timestamp", "ACTIVE")
        compose.setContent { SosContent(SosState(owner = "viewer", events = listOf(event)), vmFor(FakeSource()), false, null, {}) }
        compose.onNode(hasScrollAction()).performScrollToNode(
            hasText(context.getString(R.string.journey_time_unknown), substring = true),
        )
        compose.onNodeWithText(context.getString(R.string.journey_time_unknown), substring = true).assertIsDisplayed()
        compose.onNodeWithText("not-a-timestamp").assertDoesNotExist()
    }

    // Acknowledge is a positive one-way confirmation: the dialog confirm keeps
    // its neutral copy and the action still reaches the model. The color split
    // against cancel/resolve is a code-review decision, not something
    // semantics assertions can see.
    @Test fun acknowledgeDialogConfirmReachesTheModel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var acknowledged = 0
        val source = object : FakeSource() {
            override suspend fun acknowledge(owner: String, id: String) { acknowledged++ }
        }
        val vm = vmFor(source)
        val event = SosEventDto("event-1", "opener", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", "ACTIVE")
        compose.setContent { SosContent(SosState(owner = "viewer", events = listOf(event), selected = event), vm, false, null, {}) }
        compose.runOnIdle { vm.activate("viewer") }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(context.getString(R.string.sos_acknowledge)))
        compose.onNodeWithText(context.getString(R.string.sos_acknowledge)).assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.sos_action_acknowledge_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.sos_confirm)).assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { acknowledged > 0 }
        compose.runOnIdle { assertEquals(1, acknowledged) }
    }

    // Cancel is destructive, so the dialog keeps the destructive-confirm
    // pattern; the confirm still routes to the model.
    @Test fun cancelDialogConfirmReachesTheModel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var cancelled = 0
        val source = object : FakeSource() {
            override suspend fun cancel(owner: String, id: String) { cancelled++ }
        }
        val vm = vmFor(source)
        val event = SosEventDto("event-1", "owner", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", "ACTIVE")
        compose.setContent { SosContent(SosState(owner = "owner", selected = event), vm, false, null, {}) }
        compose.runOnIdle { vm.activate("owner") }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(context.getString(R.string.sos_cancel)))
        compose.onNodeWithText(context.getString(R.string.sos_cancel)).assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.sos_action_cancel_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.sos_confirm)).assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { cancelled > 0 }
        compose.runOnIdle { assertEquals(1, cancelled) }
    }

    private fun vmFor(source: SosDataSource): SosViewModel {
        val box = FakeBox()
        val gateway = object : SosSyncGateway {
            override suspend fun create(owner: String, item: SosOutboxItem) = Unit
            override suspend fun cancel(owner: String, eventId: String) = Unit
        }
        return SosViewModel(source, box, SosSyncEngine(box, DataStoreSessionStorage(ApplicationProvider.getApplicationContext()), gateway),
            object : SosSyncScheduler { override fun schedule(owner: String) = Unit })
    }

    // open so the tests' anonymous overrides (retry counting, injected
    // failures) can subclass it; Kotlin classes are final by default.
    private open class FakeSource : SosDataSource {
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
