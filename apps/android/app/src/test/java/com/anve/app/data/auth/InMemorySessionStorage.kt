package com.anve.app.data.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory SessionStorage for JVM tests. */
class InMemorySessionStorage : SessionStorage {

    private val state = MutableStateFlow<StoredSession?>(null)
    private val pending = MutableStateFlow<PendingRevocation?>(null)

    override val session: Flow<StoredSession?> = state

    override suspend fun current(): StoredSession? = state.value

    override suspend fun save(session: StoredSession) {
        state.value = session
    }

    override suspend fun clear() {
        state.value = null
    }

    override suspend fun savePendingRevocation(pending: PendingRevocation) {
        this.pending.value = pending
    }

    override suspend fun pendingRevocation(): PendingRevocation? {
        val record = pending.value ?: return null
        if (isExpired(record, System.currentTimeMillis())) {
            pending.value = null
            return null
        }
        return record
    }

    override suspend fun clearPendingRevocation() {
        pending.value = null
    }

    /** Test hook: the raw pending record, bypassing the expiry filter. */
    suspend fun rawPendingRevocation(): PendingRevocation? = pending.value
}
