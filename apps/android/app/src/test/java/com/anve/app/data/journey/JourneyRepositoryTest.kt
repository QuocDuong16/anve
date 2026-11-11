package com.anve.app.data.journey

import com.anve.app.data.api.ApiException
import com.anve.app.data.api.CreateJourneyDto
import com.anve.app.data.api.JourneyDestinationDto
import com.anve.app.data.api.JourneyDto
import com.anve.app.data.auth.InMemorySessionStorage
import com.anve.app.data.auth.StoredSession
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class MemoryJourneyOutbox : JourneyOutbox {
    val items = mutableListOf<JourneyOperation>()
    override suspend fun read(owner: String) = items.filter { it.owner == owner }
    override suspend fun put(operation: JourneyOperation) {
        items.removeAll { it.owner == operation.owner && it.journeyId == operation.journeyId }
        items += operation
    }
    override suspend fun remove(owner: String, operationId: String) { items.removeAll { it.owner == owner && it.operationId == operationId } }
}

internal class FakeJourneyGateway : JourneyGateway {
    var creates = 0
    var reads = 0
    var transitions = mutableListOf<String>()
    var onGet: suspend (String, String) -> JourneyDto = { owner, id -> syntheticJourney(owner, id) }
    var onCreate: suspend (String, CreateJourneyDto) -> JourneyDto = { owner, request -> syntheticJourney(owner, request.journeyId) }
    var onTransition: suspend () -> Unit = {}
    override suspend fun list(owner: String) = emptyList<JourneyDto>()
    override suspend fun get(owner: String, id: String): JourneyDto { reads++; return onGet(owner, id) }
    override suspend fun create(owner: String, request: CreateJourneyDto): JourneyDto { creates++; return onCreate(owner, request) }
    override suspend fun transition(owner: String, id: String, action: String) { transitions += action; onTransition() }
    override suspend fun revoke(owner: String, id: String, recipient: String) = Unit
}

internal fun syntheticJourney(owner: String, id: String = UUID.randomUUID().toString()) = JourneyDto(id, owner, "Synthetic", "ACTIVE",
    "2026-11-11T10:00:00Z", "2026-11-11T12:00:00Z", JourneyDestinationDto("Nhà", 21.0, 105.0))

class JourneyRepositoryTest {
    private val owner = UUID.randomUUID().toString()
    private fun session(id: String) = StoredSession("access", "refresh", "later", "later", id)
    private fun request() = CreateJourneyDto(UUID.randomUUID().toString(), Instant.now().toString(), 120, JourneyDestinationDto("Nhà", 21.0, 105.0), listOf(UUID.randomUUID().toString()))

    @Test fun `unknown create is read only across restart and never receives a fresh ID`() = runBlocking {
        val sessions = InMemorySessionStorage().also { it.save(session(owner)) }
        val outbox = MemoryJourneyOutbox()
        val gateway = FakeJourneyGateway()
        gateway.onCreate = { _, _ -> assertEquals(JourneyAction.CREATE, outbox.read(owner).single().action); throw IOException() }
        val repository = JourneyRepository(gateway, sessions, outbox, {})
        val input = request()
        assertTrue(runCatching { repository.create(owner, input) }.exceptionOrNull() is IOException)
        assertEquals(input.journeyId, outbox.read(owner).single().journeyId)
        gateway.onGet = { _, _ -> throw ApiException("JOURNEY_UNAVAILABLE", "unavailable", null, 404) }
        val restarted = JourneyRepository(gateway, sessions, outbox, {})
        assertTrue(restarted.reconcile(owner))
        assertEquals(1, gateway.creates)
        assertEquals(1, outbox.read(owner).size)
        gateway.onGet = { account, id -> syntheticJourney(account, id) }
        assertFalse(restarted.reconcile(owner))
        assertTrue(outbox.read(owner).isEmpty())
        assertEquals(1, gateway.creates)
    }

    @Test fun `late create acknowledgement cannot remove a newer cancellation`() = runBlocking {
        val sessions = InMemorySessionStorage().also { it.save(session(owner)) }
        val outbox = MemoryJourneyOutbox()
        val gateway = FakeJourneyGateway()
        val entered = CompletableDeferred<Unit>(); val finishCreate = CompletableDeferred<Unit>()
        val cancelling = CompletableDeferred<Unit>(); val finishCancel = CompletableDeferred<Unit>()
        gateway.onCreate = { account, input -> entered.complete(Unit); finishCreate.await(); syntheticJourney(account, input.journeyId) }
        gateway.onTransition = { cancelling.complete(Unit); finishCancel.await() }
        val repository = JourneyRepository(gateway, sessions, outbox, {})
        val input = request()
        val create = async { repository.create(owner, input) }; entered.await()
        val cancel = async { repository.request(owner, input.journeyId, JourneyAction.CANCEL) }; cancelling.await()
        finishCreate.complete(Unit); create.await()
        assertEquals(JourneyAction.CANCEL, outbox.read(owner).single().action)
        finishCancel.complete(Unit); cancel.await()
        assertTrue(outbox.read(owner).isEmpty())
        assertEquals(listOf("cancel"), gateway.transitions)
    }

    @Test fun `signed out and other accounts never execute the previous owner queue`() = runBlocking {
        val sessions = InMemorySessionStorage().also { it.save(session("other")) }
        val outbox = MemoryJourneyOutbox()
        outbox.put(JourneyOperation(owner = owner, journeyId = UUID.randomUUID().toString(), action = JourneyAction.CANCEL, createdAtMillis = 1000))
        val gateway = FakeJourneyGateway()
        val repository = JourneyRepository(gateway, sessions, outbox, {}, now = { 2000 })
        assertFalse(repository.reconcile(owner)); assertTrue(gateway.transitions.isEmpty())
        assertFalse(repository.reconcile("other")); assertEquals(1, outbox.read(owner).size)
        sessions.clear(); assertFalse(repository.reconcile(owner))
        sessions.save(session(owner)); assertFalse(repository.reconcile(owner)); assertEquals(listOf("cancel"), gateway.transitions)
    }

    @Test fun `an authorized incoming journey cannot confirm another owners unknown create`() = runBlocking {
        val sessions = InMemorySessionStorage().also { it.save(session(owner)) }
        val outbox = MemoryJourneyOutbox()
        val item = JourneyOperation(owner = owner, journeyId = UUID.randomUUID().toString(), action = JourneyAction.CREATE, createdAtMillis = 1000)
        outbox.put(item)
        val gateway = FakeJourneyGateway().also { it.onGet = { _, id -> syntheticJourney("another-owner", id) } }
        val repository = JourneyRepository(gateway, sessions, outbox, {}, now = { 2000 })
        assertTrue(repository.reconcile(owner))
        assertEquals(item, outbox.read(owner).single())
    }

    @Test fun `automatic retry window ends without silently declaring an unknown stop successful`() = runBlocking {
        val sessions = InMemorySessionStorage().also { it.save(session(owner)) }
        val outbox = MemoryJourneyOutbox(); val gateway = FakeJourneyGateway()
        outbox.put(JourneyOperation(owner = owner, journeyId = UUID.randomUUID().toString(), action = JourneyAction.END, createdAtMillis = 0))
        val repository = JourneyRepository(gateway, sessions, outbox, {}, now = { 8L * 24 * 60 * 60 * 1000 })
        assertFalse(repository.reconcile(owner)); assertTrue(gateway.transitions.isEmpty()); assertEquals(1, outbox.read(owner).size)
        assertFalse(repository.reconcile(owner, explicit = true)); assertTrue(outbox.read(owner).isEmpty()); assertEquals(listOf("end"), gateway.transitions)
    }

    @Test fun `cancellation and server failure preserve admitted intent while definitive rejection removes it`() = runBlocking {
        val sessions = InMemorySessionStorage().also { it.save(session(owner)) }
        val outbox = MemoryJourneyOutbox(); val gateway = FakeJourneyGateway()
        val repository = JourneyRepository(gateway, sessions, outbox, {})
        for (error in listOf(IOException(), CancellationException(), ApiException("INTERNAL", "failure", null, 500))) {
            gateway.onCreate = { _, _ -> throw error }
            assertTrue(runCatching { repository.create(owner, request()) }.isFailure)
            assertEquals(1, outbox.read(owner).size)
            outbox.items.clear()
        }
        gateway.onCreate = { _, _ -> throw ApiException("JOURNEY_INVALID", "invalid", null, 400) }
        assertTrue(runCatching { repository.create(owner, request()) }.isFailure)
        assertTrue(outbox.read(owner).isEmpty())
    }
}
