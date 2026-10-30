package com.anve.app.data.sos

import com.anve.app.data.auth.SessionStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** HTTP implementation must bind the expected owner on every operation. */
interface SosSyncGateway {
    suspend fun create(owner: String, item: SosOutboxItem)
    suspend fun cancel(owner: String, eventId: String)
}

/**
 * One owner-scoped pass. SENDING is persisted before network admission and is
 * recovered as UNKNOWN after interruption; cancellation uses the server fence.
 */
class SosSyncEngine(
    private val outbox: SosOutbox,
    private val sessions: SessionStorage,
    private val gateway: SosSyncGateway,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val passLock = Mutex()

    suspend fun requestCancel(owner: String, eventId: String) = lock.withLock {
        val item = outbox.read(owner).firstOrNull { it.eventId == eventId } ?: return@withLock
        when (item.state) {
            SosLocalState.QUEUED -> outbox.replace(owner, eventId, SosLocalState.QUEUED, SosLocalState.CANCELLED)
            SosLocalState.SENDING, SosLocalState.UNKNOWN, SosLocalState.CONFIRMED -> outbox.replace(owner, eventId, item.state, SosLocalState.CANCEL_REQUESTED, now())
            else -> Unit
        }
    }

    /** True requests bounded WorkManager backoff; a signed-out owner remains parked. */
    suspend fun sync(owner: String): Boolean = passLock.withLock { syncPass(owner) }

    /** A process interruption cannot establish whether an in-flight request committed. */
    suspend fun recoverInterrupted(owner: String) = passLock.withLock { recoverSending(owner) }

    private suspend fun recoverSending(owner: String) {
        outbox.read(owner).filter { it.state == SosLocalState.SENDING }.forEach {
            outbox.replace(owner, it.eventId, SosLocalState.SENDING, SosLocalState.UNKNOWN)
        }
    }

    private suspend fun syncPass(owner: String): Boolean {
        if (sessions.current()?.userId != owner) return false
        recoverSending(owner)
        val items = outbox.read(owner)
        for (item in items) {
            if (sessions.current()?.userId != owner) return false
            if (item.state == SosLocalState.QUEUED && now() - item.createdAtMillis >= MAX_AGE_MILLIS) {
                outbox.replace(owner, item.eventId, SosLocalState.QUEUED, SosLocalState.EXPIRED)
                continue
            }
            // An old ambiguous create is retained for explicit reconciliation;
            // expiration must not be reported as proof it never committed.
            if (item.state == SosLocalState.UNKNOWN && now() - item.createdAtMillis >= MAX_AGE_MILLIS) continue
            if (item.state == SosLocalState.CANCEL_REQUESTED && now() - (item.cancelRequestedAtMillis ?: item.createdAtMillis) >= MAX_AGE_MILLIS) continue
            val action = lock.withLock {
                val current = outbox.read(owner).firstOrNull { it.eventId == item.eventId } ?: return@withLock null
                when (current.state) {
                    SosLocalState.QUEUED, SosLocalState.UNKNOWN -> if (outbox.replace(owner, current.eventId, current.state, SosLocalState.SENDING)) current.copy(state = SosLocalState.SENDING) else null
                    SosLocalState.CANCEL_REQUESTED -> current
                    else -> null
                }
            } ?: continue
            try {
                if (action.state == SosLocalState.CANCEL_REQUESTED) {
                    gateway.cancel(owner, action.eventId)
                    outbox.replace(owner, action.eventId, SosLocalState.CANCEL_REQUESTED, SosLocalState.CANCELLED)
                } else {
                    gateway.create(owner, action)
                    outbox.replace(owner, action.eventId, SosLocalState.SENDING, SosLocalState.CONFIRMED)
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { outbox.replace(owner, action.eventId, SosLocalState.SENDING, SosLocalState.UNKNOWN) }
                throw e
            } catch (_: SosCancelledException) {
                outbox.replace(owner, action.eventId, SosLocalState.SENDING, SosLocalState.CANCELLED)
            } catch (_: Exception) {
                outbox.replace(owner, action.eventId, SosLocalState.SENDING, SosLocalState.UNKNOWN)
                return true
            }
        }
        if (sessions.current()?.userId != owner) return false
        return outbox.read(owner).any {
            it.state == SosLocalState.QUEUED ||
                (it.state == SosLocalState.CANCEL_REQUESTED && now() - (it.cancelRequestedAtMillis ?: it.createdAtMillis) < MAX_AGE_MILLIS) ||
                (it.state == SosLocalState.UNKNOWN && now() - it.createdAtMillis < MAX_AGE_MILLIS)
        }
    }

    companion object { const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000 }
}
