package com.anve.app.ui.circle

import androidx.lifecycle.viewModelScope
import com.anve.app.data.api.CircleDto
import com.anve.app.data.api.CircleMembershipDto
import com.anve.app.data.api.InvitePreviewDto
import com.anve.app.data.api.MembershipDto
import com.anve.app.data.auth.InMemorySessionStorage
import com.anve.app.data.auth.SessionStorage
import com.anve.app.data.auth.StoredSession
import com.anve.app.data.circles.InvitationConfirmation
import com.anve.app.data.circles.InvitationInbox
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InvitationViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val sessions = InMemorySessionStorage()
    private val inbox = InvitationInbox()
    private val gateway = Gateway()
    @Before fun mainDispatcher() { Dispatchers.setMain(dispatcher) }
    @After fun restoreDispatcher() { Dispatchers.resetMain() }
    private fun session(owner: String) = StoredSession("access-$owner", "refresh-$owner", "later", "later", owner)
    private suspend fun prepare(code: String = "ABCD234567") {
        sessions.save(session("owner"))
        inbox.accountChanged("owner")
        inbox.offer("anve://invite/$code")
    }
    private fun model(storage: SessionStorage = sessions) = InvitationViewModel("owner", inbox.invitation.value!!.id, inbox, storage, gateway)

    @Test fun `opening previews without joining and double confirm has one admission`() = runTest(dispatcher) {
        prepare()
        val reply = CompletableDeferred<InvitationConfirmation>()
        gateway.confirm = { reply.await() }
        val model = model()
        try {
            assertFalse(model.state.value.loading)
            assertEquals("Nhà mình", model.state.value.preview?.circleName)
            assertEquals(0, gateway.confirms)
            model.confirm(); model.confirm()
            assertEquals(1, gateway.confirms)
            reply.complete(InvitationConfirmation.Unknown)
            assertTrue(model.state.value.unknown)
            model.confirm(); model.load()
            assertEquals(1, gateway.confirms)
            model.checkAgain()
            assertEquals(1, gateway.reconciles)
            assertTrue(model.state.value.joined)
            assertEquals(1, gateway.confirms)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun `late account response and new link never restore old private preview`() = runTest(dispatcher) {
        prepare()
        val reply = CompletableDeferred<InvitePreviewDto>()
        gateway.read = { withContext(NonCancellable) { reply.await() } }
        val model = model()
        sessions.save(session("other"))
        inbox.accountChanged("other")
        reply.complete(gateway.preview)
        assertNull(model.state.value.preview)
        assertEquals("", model.state.value.accountLabel)
        model.confirm()
        assertEquals(0, gateway.confirms)
        model.viewModelScope.cancel()

        prepare()
        val newerReply = CompletableDeferred<InvitePreviewDto>()
        gateway.read = { withContext(NonCancellable) { newerReply.await() } }
        val oldModel = model()
        inbox.offer("anve://invite/ABCD234568")
        newerReply.complete(gateway.preview)
        assertNull(oldModel.state.value.preview)
        oldModel.confirm()
        assertEquals(0, gateway.confirms)
        oldModel.viewModelScope.cancel()
    }

    @Test fun `expired invalid and already in circle cannot confirm`() = runTest(dispatcher) {
        prepare("invalid")
        var model = model()
        assertFalse(model.state.value.loading)
        assertNull(model.state.value.preview)
        model.confirm()
        assertEquals(0, gateway.confirms)
        model.viewModelScope.cancel()
        prepare()
        gateway.read = { gateway.preview.copy(expiresAt = Instant.now().minusSeconds(1).toString()) }
        model = model()
        assertTrue(model.state.value.blocked)
        model.confirm()
        assertEquals(0, gateway.confirms)
        model.viewModelScope.cancel()
        gateway.read = { gateway.preview }
        gateway.membership = gateway.joined
        model = model()
        assertTrue(model.state.value.blocked)
        model.confirm()
        assertEquals(0, gateway.confirms)
        model.viewModelScope.cancel()
    }

    @Test fun `unreadable session hides preview and provides a static recovery message`() = runTest(dispatcher) {
        prepare()
        val unavailable = object : SessionStorage by sessions { override suspend fun current(): StoredSession? = throw IOException("private failure") }
        val model = model(unavailable)
        try {
            assertFalse(model.state.value.loading)
            assertNull(model.state.value.preview)
            assertTrue(model.state.value.blocked)
            assertFalse(model.state.value.error.orEmpty().contains("private failure"))
            model.confirm()
            assertEquals(0, gateway.confirms)
        } finally { model.viewModelScope.cancel() }
    }

    private class Gateway : InvitationGateway {
        val preview = InvitePreviewDto("circle", "Nhà mình", Instant.now().plusSeconds(3600).toString())
        val joined = CircleMembershipDto(CircleDto("circle", "Nhà mình", "other", "now"), MembershipDto("circle", "owner", "member", "now"))
        var membership = CircleMembershipDto(null, null)
        var read: suspend () -> InvitePreviewDto = { preview }
        var confirm: suspend () -> InvitationConfirmation = { InvitationConfirmation.Joined(joined) }
        var confirms = 0
        var reconciles = 0
        override suspend fun accountLabel(owner: String) = "Owner · owner@example.test"
        override suspend fun preview(owner: String, code: String) = read()
        override suspend fun mine(owner: String) = membership
        override suspend fun confirm(owner: String, code: String, circle: String): InvitationConfirmation { confirms++; return confirm() }
        override suspend fun reconcile(owner: String, circle: String): InvitationConfirmation { reconciles++; return InvitationConfirmation.Joined(joined) }
    }
}
