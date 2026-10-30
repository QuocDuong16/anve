package com.anve.app.ui.sos

import com.anve.app.data.api.MemberDto
import com.anve.app.data.api.SosEventDto
import com.anve.app.data.api.SosRecipientDto
import com.anve.app.data.auth.InMemorySessionStorage
import com.anve.app.data.auth.StoredSession
import com.anve.app.data.sos.SosCachedMember
import com.anve.app.data.sos.SosDataSource
import com.anve.app.data.sos.SosLocalState
import com.anve.app.data.sos.SosOutbox
import com.anve.app.data.sos.SosOutboxItem
import com.anve.app.data.sos.SosSyncEngine
import com.anve.app.data.sos.SosSyncGateway
import com.anve.app.data.sos.SosSyncScheduler
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SosViewModelTest {
    private val source = FakeSource()
    private val box = FakeBox()
    private val sessions = InMemorySessionStorage()
    private var scheduled = 0
    private val scheduler = object : SosSyncScheduler { override fun schedule(owner: String) { scheduled++ } }
    private val gateway = object : SosSyncGateway {
        override suspend fun create(owner: String, item: SosOutboxItem) = Unit
        override suspend fun cancel(owner: String, eventId: String) = Unit
    }
    private lateinit var vm: SosViewModel

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        runBlocking { sessions.save(StoredSession("access", "refresh", "later", "later", "a")) }
        vm = SosViewModel(source, box, SosSyncEngine(box, sessions, gateway, now = { 1000 }), scheduler, now = { 1000 })
    }
    @After fun cleanup() { vm.activate(null); Dispatchers.resetMain() }

    @Test fun `explicit send queues but does not claim server confirmation`() {
        vm.activate("a")
        vm.send(setOf("b"))
        assertEquals(1, scheduled)
        assertEquals(SosLocalState.QUEUED, box.items.single().state)
        assertEquals(listOf("b"), box.items.single().recipientIds)
    }

    @Test fun `storage failure does not claim the SOS was queued`() {
        box.failEnqueue = true
        vm.activate("a")
        vm.send(setOf("b"))
        assertTrue(box.items.isEmpty())
        assertEquals(0, scheduled)
        assertTrue(vm.state.value.error?.contains("Không xác nhận được việc lưu SOS") == true)
    }

    @Test fun `offline uses owner scoped cached members and clears server events`() {
        vm.activate("a")
        source.offline = true
        vm.refresh()
        assertTrue(vm.state.value.offline)
        assertEquals("b", vm.state.value.members.single().userId)
        assertTrue(vm.state.value.events.isEmpty())
    }

    @Test fun `old account response cannot overwrite new account`() {
        val old = CompletableDeferred<List<MemberDto>>()
        source.memberLoad = { owner -> if (owner == "a") withContext(NonCancellable) { old.await() } else emptyList() }
        vm.activate("a")
        vm.activate("b")
        old.complete(listOf(MemberDto("b", "B", "member", "today")))
        assertEquals("b", vm.state.value.owner)
        assertTrue(vm.state.value.members.isEmpty())
        assertTrue(vm.state.value.events.isEmpty())
    }

    @Test fun `authoritative owner event reconciles an unknown create without replay`() {
        box.items = listOf(SosOutboxItem("event", "a", "2026-01-01T00:00:00Z", listOf("b"), 1000, SosLocalState.UNKNOWN))
        source.serverEvents = listOf(SosEventDto("event", "a", "2026-01-01T00:00:00Z", "2026-01-01T00:01:00Z", "ACTIVE"))
        vm.activate("a")
        assertEquals(SosLocalState.CONFIRMED, box.items.single().state)
        assertEquals(0, scheduled)
    }

    @Test fun `explicit recheck retains unknown when event is unavailable`() {
        box.items = listOf(SosOutboxItem("missing", "a", "2026-01-01T00:00:00Z", listOf("b"), 1000, SosLocalState.UNKNOWN))
        vm.activate("a")
        vm.recheckUnknown("missing")
        assertEquals(SosLocalState.UNKNOWN, box.items.single().state)
        assertEquals(0, scheduled)
        assertTrue(vm.state.value.error != null)
    }

    @Test fun `server expiry is distinct from an unsent local request`() {
        box.items = listOf(SosOutboxItem("event", "a", "2026-01-01T00:00:00Z", listOf("b"), 1000, SosLocalState.CONFIRMED))
        source.serverEvents = listOf(SosEventDto("event", "a", "2026-01-01T00:00:00Z", "2026-01-01T00:01:00Z", "EXPIRED"))
        vm.activate("a")
        assertEquals(SosLocalState.SERVER_EXPIRED, box.items.single().state)
    }

    @Test fun `explicit recipient revocation is not replayed after uncertain response`() {
        source.failRevoke = true
        vm.activate("a")
        vm.revokeRecipient("event", "b")
        assertEquals(1, source.revokeCalls)
        assertTrue(vm.state.value.error != null)
    }

    private class FakeSource : SosDataSource {
        var offline = false
        var failRevoke = false
        var revokeCalls = 0
        var serverEvents = emptyList<SosEventDto>()
        var memberLoad: suspend (String) -> List<MemberDto> = { listOf(MemberDto("b", "B", "member", "today")) }
        override suspend fun members(owner: String): List<MemberDto> { if (offline) throw IOException(); return memberLoad(owner) }
        override suspend fun events(owner: String): List<SosEventDto> { if (offline) throw IOException(); return serverEvents }
        override suspend fun event(owner: String, id: String): SosEventDto = error("unused")
        override suspend fun recipients(owner: String, id: String): List<SosRecipientDto> = emptyList()
        override suspend fun revokeRecipient(owner: String, id: String, recipient: String) { revokeCalls++; if (failRevoke) throw IOException() }
        override suspend fun acknowledge(owner: String, id: String) = Unit
        override suspend fun resolve(owner: String, id: String) = Unit
        override suspend fun cancel(owner: String, id: String) = Unit
        override suspend fun pushRegistered(owner: String): Boolean = false
        override suspend fun pushConfigured(owner: String): Boolean = false
    }

    private class FakeBox : SosOutbox {
        var items = emptyList<SosOutboxItem>()
        var failEnqueue = false
        var members = emptyList<SosCachedMember>()
        override suspend fun read(owner: String) = items.filter { it.ownerId == owner }
        override suspend fun enqueue(item: SosOutboxItem) { if (failEnqueue) throw IOException(); items += item }
        override suspend fun replace(owner: String, id: String, expected: SosLocalState, next: SosLocalState, cancelRequestedAtMillis: Long?): Boolean {
            val current = items.firstOrNull { it.ownerId == owner && it.eventId == id && it.state == expected } ?: return false
            items = items.map { if (it == current) it.copy(state = next, cancelRequestedAtMillis = if (next == SosLocalState.CANCEL_REQUESTED) cancelRequestedAtMillis else null) else it }; return true
        }
        override suspend fun removeFinished(owner: String, id: String): Boolean = false
        override suspend fun cachedMembers(owner: String) = members.filter { it.ownerId == owner }
        override suspend fun saveMembers(owner: String, members: List<SosCachedMember>) { this.members = this.members.filter { it.ownerId != owner } + members }
    }
}
