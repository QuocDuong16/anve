package com.anve.app.data.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory SessionStorage for JVM tests. It applies the same merge, expiry
 * and bound rules as the DataStore implementation; only encryption and
 * process persistence are absent (those are platform concerns).
 */
class InMemorySessionStorage(
    private val now: () -> Long = System::currentTimeMillis,
) : SessionStorage {

    private val state = MutableStateFlow<StoredSession?>(null)
    private val pending = MutableStateFlow<List<PendingRevocation>>(emptyList())

    override val session: Flow<StoredSession?> = state

    override suspend fun current(): StoredSession? = state.value

    override suspend fun save(session: StoredSession) {
        state.value = session
    }

    override suspend fun clear() {
        state.value = null
    }

    override suspend fun savePendingRevocation(pending: PendingRevocation) {
        this.pending.value = mergePendingRevocation(
            records = this.pending.value,
            pending = pending,
            nowMillis = now(),
        )
    }

    override suspend fun pendingRevocations(): List<PendingRevocation> {
        val records = prunePendingRevocations(pending.value, now())
        if (records != pending.value) pending.value = records
        return records
    }

    override suspend fun removePendingRevocation(id: String) {
        pending.value = prunePendingRevocations(
            pending.value.filterNot { it.id == id },
            now(),
        )
    }

    /** Test hook: the raw queue, bypassing the expiry filter and bound. */
    suspend fun rawPendingRevocations(): List<PendingRevocation> = pending.value
}
