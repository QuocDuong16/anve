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
    private val consent = MutableStateFlow<List<PendingConsentOperation>>(emptyList())

    override val session: Flow<StoredSession?> = state

    override suspend fun current(): StoredSession? = state.value

    override suspend fun save(session: StoredSession) {
        state.value = session
    }

    override suspend fun clear() {
        state.value = null
    }

    override suspend fun clearIfMatches(ownerUserId: String?, accessToken: String): Boolean {
        val current = state.value ?: return false
        if (current.userId != ownerUserId || current.accessToken != accessToken) return false
        state.value = null
        return true
    }

    override suspend fun rotateIfMatches(
        ownerUserId: String?,
        accessToken: String,
        session: StoredSession,
    ): Boolean {
        val current = state.value ?: return false
        if (current.userId != ownerUserId || current.accessToken != accessToken) return false
        state.value = session
        return true
    }

    override suspend fun setUserId(userId: String) {
        state.value = state.value?.copy(userId = userId)
    }

    override suspend fun setUserIdIfMatches(accessToken: String, userId: String): Boolean {
        val current = state.value ?: return false
        if (current.accessToken != accessToken || current.userId != null) return false
        state.value = current.copy(userId = userId)
        return true
    }

    override suspend fun updateConsent(ownerUserId: String, enabled: Boolean, version: Long): Boolean {
        val current = state.value ?: return false
        if (current.userId != ownerUserId || version < (current.locationSharingVersion ?: -1L)) return false
        state.value = current.copy(locationSharingEnabled = enabled, locationSharingVersion = version)
        return true
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

    override suspend fun savePendingConsentOperation(operation: PendingConsentOperation) {
        consent.value = mergePendingConsentOperation(consent.value, operation, now())
    }

    override suspend fun pendingConsentOperations(): List<PendingConsentOperation> {
        val records = prunePendingConsentOperations(consent.value, now())
        if (records != consent.value) consent.value = records
        return records
    }

    override suspend fun removePendingConsentOperation(operationId: String) {
        consent.value = consent.value.filterNot { it.operationId == operationId }
    }

    override suspend fun removePendingConsentOperations(ownerUserId: String) {
        consent.value = consent.value.filterNot { it.ownerUserId == ownerUserId }
    }

    /** Test hook: the raw queue, bypassing the expiry filter and bound. */
    suspend fun rawPendingRevocations(): List<PendingRevocation> = pending.value
}
