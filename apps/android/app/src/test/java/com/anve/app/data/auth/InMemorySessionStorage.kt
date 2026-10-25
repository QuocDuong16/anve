package com.anve.app.data.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory SessionStorage for JVM tests. */
class InMemorySessionStorage : SessionStorage {

    private val state = MutableStateFlow<StoredSession?>(null)

    override val session: Flow<StoredSession?> = state

    override suspend fun current(): StoredSession? = state.value

    override suspend fun save(session: StoredSession) {
        state.value = session
    }

    override suspend fun clear() {
        state.value = null
    }
}
