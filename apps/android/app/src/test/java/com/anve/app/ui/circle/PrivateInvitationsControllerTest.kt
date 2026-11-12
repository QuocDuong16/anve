package com.anve.app.ui.circle

import com.anve.app.data.api.InviteDto
import com.anve.app.data.api.PrivateInvitationRequestDto
import com.anve.app.data.api.ReceivedInvitationDto
import com.anve.app.data.auth.InMemorySessionStorage
import com.anve.app.data.auth.StoredSession
import com.anve.app.data.circles.PrivateInvitationGateway
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateInvitationsControllerTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val sessions = InMemorySessionStorage()
    private suspend fun login(owner: String = "owner") { sessions.save(StoredSession("a", "r", "later", "later", owner)) }
    private val expiry = Instant.now().plusSeconds(3600).toString()
    private val entry = ReceivedInvitationDto("invite", "circle", "Nhà", "Mai", expiry)

    @Test fun `creation is explicit single admission and unknown uses readback without POST replay`() = runTest(dispatcher) {
        login(); val gateway = Gateway(); val reply = CompletableDeferred<Unit>()
        gateway.write = { reply.await(); throw IOException("private timeout") }
        var confirmed = 0
        val controller = PrivateInvitationsController("owner", sessions, gateway, backgroundScope, { confirmed++ }, {})
        controller.open(); controller.email("invalid"); controller.create("circle", true)
        assertEquals(0, gateway.creates)
        controller.email("Mai@example.test"); controller.create("circle", false)
        assertEquals(0, gateway.creates)
        controller.create("circle", true); controller.create("circle", true)
        assertEquals(1, gateway.creates)
        reply.complete(Unit)
        assertTrue(controller.state.value.unknown)
        controller.create("circle", true); controller.checkAgain(true)
        assertEquals(1, gateway.creates)
        gateway.read = { gateway.result() }
        controller.checkAgain(true)
        assertFalse(controller.state.value.unknown)
        assertEquals(1, confirmed); assertEquals(1, gateway.creates)
        assertEquals("mai@example.test", gateway.request?.recipientEmail)
        assertEquals("", controller.state.value.email)
        controller.dispose()
    }

    @Test fun `late account response cannot restore inbox or draft or open a code`() = runTest(dispatcher) {
        login(); val gateway = Gateway(); val late = CompletableDeferred<List<ReceivedInvitationDto>>()
        gateway.inbox = { withContext(NonCancellable) { late.await() } }
        var reviews = 0
        val controller = PrivateInvitationsController("owner", sessions, gateway, backgroundScope, {}, { reviews++ })
        controller.open(); controller.email("person@example.test")
        login("other"); late.complete(listOf(entry))
        assertTrue(controller.state.value.received.isEmpty()); assertEquals("", controller.state.value.email)
        controller.review("invite"); controller.create("circle", true)
        assertEquals(0, reviews); assertEquals(0, gateway.creates)
        controller.dispose()
    }

    @Test fun `hide invalidates older inbox fetch and review never joins`() = runTest(dispatcher) {
        login(); val gateway = Gateway(); gateway.inbox = { listOf(entry) }
        var code: String? = null
        val controller = PrivateInvitationsController("owner", sessions, gateway, backgroundScope, {}, { code = it })
        val late = CompletableDeferred<List<ReceivedInvitationDto>>()
        gateway.inbox = { withContext(NonCancellable) { late.await() } }
        controller.refresh(); controller.review("invite", hide = true)
        late.complete(listOf(entry))
        assertTrue(controller.state.value.received.isEmpty())
        assertEquals(1, gateway.hides)
        controller.review("invite")
        assertEquals("ABCD234567", code)
        assertEquals(0, gateway.creates)
        controller.dispose()
    }

    private inner class Gateway : PrivateInvitationGateway {
        var creates = 0; var hides = 0
        var request: PrivateInvitationRequestDto? = null
        var write: suspend () -> Unit = {}
        var read: suspend () -> InviteDto = { throw IOException("unknown") }
        var inbox: suspend () -> List<ReceivedInvitationDto> = { emptyList() }
        fun result() = InviteDto(request!!.requestId, "circle", "ABCD234567", "owner", expiry, "now", request!!.mode == "EMAIL")
        override suspend fun create(owner: String, circle: String, request: PrivateInvitationRequestDto): InviteDto {
            creates++; this.request = request; write(); return result()
        }
        override suspend fun created(owner: String, circle: String, requestId: String) = read()
        override suspend fun received(owner: String) = inbox()
        override suspend fun detail(owner: String, id: String) = entry.copy(code = "ABCD234567")
        override suspend fun hide(owner: String, id: String) { hides++ }
    }
}
