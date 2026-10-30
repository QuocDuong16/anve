package com.anve.app.data.sos

import com.anve.app.data.auth.InMemorySessionStorage
import com.anve.app.data.auth.StoredSession
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SosSyncEngineTest {
    private class MemoryOutbox(initial: SosOutboxItem) : SosOutbox {
        var item = initial
        override suspend fun read(owner: String): List<SosOutboxItem> = listOf(item).filter { it.ownerId == owner }
        override suspend fun enqueue(item: SosOutboxItem) { this.item = item }
        override suspend fun replace(owner: String, id: String, expected: SosLocalState, next: SosLocalState, cancelRequestedAtMillis: Long?): Boolean {
            if (item.ownerId != owner || item.eventId != id || item.state != expected) return false
            item = item.copy(state = next, cancelRequestedAtMillis = if (next == SosLocalState.CANCEL_REQUESTED) cancelRequestedAtMillis else null)
            return true
        }
        override suspend fun removeFinished(owner: String, id: String): Boolean = false
        override suspend fun cachedMembers(owner: String): List<SosCachedMember> = emptyList()
        override suspend fun saveMembers(owner: String, members: List<SosCachedMember>) = Unit
    }

    private fun item() = SosOutboxItem("event", "a", "2026-10-30T00:00:00Z", listOf("b"), 1000, SosLocalState.QUEUED)
    private fun session(owner: String) = StoredSession("access", "refresh", "later", "later", owner)

    @Test fun `lost response retains id and retries without claiming success`() = runBlocking {
        val sessions = InMemorySessionStorage().also { it.save(session("a")) }
        val outbox = MemoryOutbox(item())
        val ids = mutableListOf<String>()
        val gateway = object : SosSyncGateway {
            override suspend fun create(owner: String, item: SosOutboxItem) { ids += item.eventId; if (ids.size == 1) throw IOException() }
            override suspend fun cancel(owner: String, eventId: String) = Unit
        }
        val engine = SosSyncEngine(outbox, sessions, gateway, now = { 1000 })
        assertTrue(engine.sync("a"))
        assertEquals(SosLocalState.UNKNOWN, outbox.item.state)
        assertFalse(engine.sync("a"))
        assertEquals(listOf("event", "event"), ids)
        assertEquals(SosLocalState.CONFIRMED, outbox.item.state)
    }

    @Test fun `cancel during an uncertain create never replays create`() = runBlocking {
        val sessions = InMemorySessionStorage().also { it.save(session("a")) }
        val outbox = MemoryOutbox(item())
        val entered = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Unit>()
        var creates = 0
        var cancels = 0
        val gateway = object : SosSyncGateway {
            override suspend fun create(owner: String, item: SosOutboxItem) { creates++; entered.complete(Unit); reply.await() }
            override suspend fun cancel(owner: String, eventId: String) { cancels++ }
        }
        val engine = SosSyncEngine(outbox, sessions, gateway, now = { 1000 })
        val sending = async { engine.sync("a") }
        entered.await()
        assertEquals(SosLocalState.SENDING, outbox.item.state)
        engine.requestCancel("a", "event")
        reply.complete(Unit)
        sending.await()
        assertEquals(SosLocalState.CANCEL_REQUESTED, outbox.item.state)
        engine.sync("a")
        assertEquals(1, creates)
        assertEquals(1, cancels)
        assertEquals(SosLocalState.CANCELLED, outbox.item.state)
    }

    @Test fun `interrupted send recovers as unknown without inventing confirmation`() = runBlocking {
        val sessions = InMemorySessionStorage().also { it.save(session("a")) }
        val outbox = MemoryOutbox(item().copy(state = SosLocalState.SENDING))
        val gateway = object : SosSyncGateway {
            override suspend fun create(owner: String, item: SosOutboxItem) = error("must not send during recovery")
            override suspend fun cancel(owner: String, eventId: String) = error("must not cancel during recovery")
        }
        SosSyncEngine(outbox, sessions, gateway).recoverInterrupted("a")
        assertEquals(SosLocalState.UNKNOWN, outbox.item.state)
    }

    @Test fun `another account cannot send or expire ambiguous requests`() = runBlocking {
        val sessions = InMemorySessionStorage().also { it.save(session("b")) }
        val outbox = MemoryOutbox(item().copy(state = SosLocalState.UNKNOWN))
        val gateway = object : SosSyncGateway {
            override suspend fun create(owner: String, item: SosOutboxItem) = error("must not send")
            override suspend fun cancel(owner: String, eventId: String) = error("must not cancel")
        }
        val engine = SosSyncEngine(outbox, sessions, gateway, now = { SosSyncEngine.MAX_AGE_MILLIS + 1001 })
        assertFalse(engine.sync("a"))
        assertEquals(SosLocalState.UNKNOWN, outbox.item.state)
        sessions.save(session("a"))
        assertFalse(engine.sync("a"))
        assertEquals(SosLocalState.UNKNOWN, outbox.item.state)
    }

    @Test fun `old unconfirmed cancellation is retained without unbounded retry`() = runBlocking {
        val sessions = InMemorySessionStorage().also { it.save(session("a")) }
        val outbox = MemoryOutbox(item().copy(state = SosLocalState.CANCEL_REQUESTED, cancelRequestedAtMillis = 1000))
        val gateway = object : SosSyncGateway {
            override suspend fun create(owner: String, item: SosOutboxItem) = error("must not create")
            override suspend fun cancel(owner: String, eventId: String) = error("must not retry after expiry")
        }
        val engine = SosSyncEngine(outbox, sessions, gateway, now = { 1001 + SosSyncEngine.MAX_AGE_MILLIS })
        assertFalse(engine.sync("a"))
        assertEquals(SosLocalState.CANCEL_REQUESTED, outbox.item.state)
    }
}
