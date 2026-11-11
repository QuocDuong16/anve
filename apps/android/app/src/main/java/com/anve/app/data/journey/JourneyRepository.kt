package com.anve.app.data.journey

import com.anve.app.data.api.AnveApi
import com.anve.app.data.api.ApiException
import com.anve.app.data.api.CreateJourneyDto
import com.anve.app.data.api.JourneyDto
import com.anve.app.data.api.orThrow
import com.anve.app.data.auth.SessionStorage
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface JourneyDataSource {
    suspend fun list(owner: String): List<JourneyDto>
    suspend fun get(owner: String, id: String): JourneyDto
    suspend fun pending(owner: String): List<JourneyOperation>
    suspend fun create(owner: String, request: CreateJourneyDto): JourneyDto
    suspend fun request(owner: String, id: String, action: JourneyAction, recipient: String? = null)
    suspend fun reconcile(owner: String, explicit: Boolean = false): Boolean
}

interface JourneyGateway {
    suspend fun list(owner: String): List<JourneyDto>
    suspend fun get(owner: String, id: String): JourneyDto
    suspend fun create(owner: String, request: CreateJourneyDto): JourneyDto
    suspend fun transition(owner: String, id: String, action: String)
    suspend fun revoke(owner: String, id: String, recipient: String)
}

class ApiJourneyGateway(private val api: AnveApi) : JourneyGateway {
    override suspend fun list(owner: String) = api.journeys(owner).orThrow().journeys
    override suspend fun get(owner: String, id: String) = api.journey(owner, id).orThrow()
    override suspend fun create(owner: String, request: CreateJourneyDto) = api.createJourney(owner, request).orThrow()
    override suspend fun transition(owner: String, id: String, action: String) { api.transitionJourney(owner, id, action).orThrow() }
    override suspend fun revoke(owner: String, id: String, recipient: String) { api.revokeJourneyRecipient(owner, id, recipient).orThrow() }
}

/** Durable intent precedes network I/O. The outbox never inherits another owner. */
class JourneyRepository(
    private val gateway: JourneyGateway,
    private val sessions: SessionStorage,
    private val outbox: JourneyOutbox,
    private val schedule: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) : JourneyDataSource {
    private val passLock = Mutex()

    private suspend fun requireOwner(owner: String) {
        check(sessions.current()?.userId == owner) { "Journey owner changed" }
    }

    override suspend fun list(owner: String): List<JourneyDto> {
        requireOwner(owner)
        return gateway.list(owner).also { requireOwner(owner) }
    }

    override suspend fun get(owner: String, id: String): JourneyDto {
        requireOwner(owner)
        return gateway.get(owner, id).also { requireOwner(owner) }
    }

    override suspend fun pending(owner: String): List<JourneyOperation> = outbox.read(owner)

    override suspend fun create(owner: String, request: CreateJourneyDto): JourneyDto {
        requireOwner(owner)
        check(outbox.read(owner).isEmpty()) { "Journey operation still pending" }
        UUID.fromString(request.journeyId)
        val operation = JourneyOperation(owner = owner, journeyId = request.journeyId, action = JourneyAction.CREATE, createdAtMillis = now())
        outbox.put(operation)
        schedule(owner)
        try {
            requireOwner(owner)
            val result = gateway.create(owner, request)
            check(result.ownerId == owner && result.id == request.journeyId)
            outbox.remove(owner, operation.operationId)
            requireOwner(owner)
            return result
        } catch (error: ApiException) {
            // A response to this first POST can definitively reject admission.
            // Transport errors, cancellation, 5xx and storage failures stay unknown.
            if (error.httpStatus == 400 || error.httpStatus == 409) outbox.remove(owner, operation.operationId)
            throw error
        }
    }

    override suspend fun request(owner: String, id: String, action: JourneyAction, recipient: String?) {
        requireOwner(owner)
        require(action != JourneyAction.CREATE)
        outbox.put(JourneyOperation(owner = owner, journeyId = id, action = action, recipient = recipient, createdAtMillis = now()))
        schedule(owner)
        reconcile(owner, explicit = true)
    }

    /** True requests bounded WorkManager retries. Unknown creates are read-only. */
    override suspend fun reconcile(owner: String, explicit: Boolean): Boolean = passLock.withLock {
        if (sessions.current()?.userId != owner) return@withLock false
        var retry = false
        for (item in outbox.read(owner)) {
            if (sessions.current()?.userId != owner) return@withLock false
            if (!explicit && now() - item.createdAtMillis >= RETRY_WINDOW) continue
            // A cancellation written while a create is in flight wins the local
            // generation; its response cannot remove the newer operation ID.
            if (outbox.read(owner).none { it.operationId == item.operationId }) continue
            try {
                when (item.action) {
                    JourneyAction.CREATE -> gateway.get(owner, item.journeyId)
                    JourneyAction.REVOKE_RECIPIENT -> gateway.revoke(owner, item.journeyId, requireNotNull(item.recipient))
                    else -> gateway.transition(owner, item.journeyId, item.action.name.lowercase())
                }
                outbox.remove(owner, item.operationId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiException) {
                if (error.httpStatus == 401) return@withLock false
                if (error.httpStatus == 409 && item.action != JourneyAction.CREATE) {
                    // A competing terminal transition wins at the server. Read
                    // it before retiring this intent, never claim our action won.
                    val state = try {
                        gateway.get(owner, item.journeyId).state
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { null }
                    if (state in TERMINAL) outbox.remove(owner, item.operationId) else retry = true
                } else retry = true
            } catch (_: Exception) {
                retry = true
            }
        }
        retry
    }

    companion object {
        private const val RETRY_WINDOW = 7L * 24 * 60 * 60 * 1000
        private val TERMINAL = setOf("ARRIVED", "ENDED", "CANCELLED", "EXPIRED")
    }
}
