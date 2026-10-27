package com.anve.app.data.auth

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure rules of the durable revocation queue and the app-scoped notices.
 * DataStore durability/encryption are platform behaviour; these tests pin
 * the ownership, expiry, bound and backoff logic that surrounds it.
 */
class PendingRevocationQueueTest {

    private fun record(
        id: String,
        recordedAt: Long,
        ttl: Long = PENDING_REVOCATION_TTL_MILLIS,
    ) = PendingRevocation(
        id = id,
        refreshToken = "token-$id",
        recordedAtMillis = recordedAt,
        expiresAtMillis = recordedAt + ttl,
    )

    @Test
    fun `expiry is exclusive at the deadline`() {
        val record = record("a", recordedAt = 1_000)
        assertFalse(isExpired(record, 1_000 + PENDING_REVOCATION_TTL_MILLIS - 1))
        assertTrue(isExpired(record, 1_000 + PENDING_REVOCATION_TTL_MILLIS))
    }

    @Test
    fun `pruning drops expired records and keeps the newest within the bound`() {
        val now = 10_000_000_000L
        val expired = record("expired", recordedAt = 0, ttl = 1)
        val valid = (1..MAX_PENDING_REVOCATIONS + 2).map { index ->
            record("r$index", recordedAt = now - 10_000 + index)
        }

        val pruned = prunePendingRevocations(listOf(expired) + valid, now)

        assertEquals(MAX_PENDING_REVOCATIONS, pruned.size)
        assertEquals("the oldest valid records are evicted first", "r3", pruned.first().id)
        assertEquals("the newest record survives", "r${MAX_PENDING_REVOCATIONS + 2}", pruned.last().id)
    }

    @Test
    fun `merge replaces the same id instead of duplicating it`() {
        val now = 50_000L
        val original = record("same", recordedAt = now)
        val rotated = original.copy(refreshToken = "token-rotated", attemptCount = 3)

        val merged = mergePendingRevocation(listOf(original), rotated, now)

        assertEquals(1, merged.size)
        assertEquals("token-rotated", merged.single().refreshToken)
        assertEquals(3, merged.single().attemptCount)
    }

    @Test
    fun `consent outbox keeps latest generation per owner without crossing accounts`() {
        val now = 50_000L
        val firstA = newPendingConsentOperation("owner-a", false, 3, 1, now, "a-1")
        val pendingB = newPendingConsentOperation("owner-b", true, 7, 1, now + 1, "b-1")
        val latestA = firstA.copy(operationId = "a-2", generation = 2, expectedVersion = 4)

        val merged = mergePendingConsentOperation(listOf(firstA, pendingB), latestA, now)

        assertEquals(2, merged.size)
        assertEquals("a-2", merged.single { it.ownerUserId == "owner-a" }.operationId)
        assertEquals(4L, merged.single { it.ownerUserId == "owner-a" }.expectedVersion)
        assertEquals("b-1", merged.single { it.ownerUserId == "owner-b" }.operationId)
    }

    @Test
    fun `expired credential is removed while the owner consent intent remains`() {
        val operation = PendingConsentOperation(
            operationId = "off-1",
            ownerUserId = "owner-a",
            desiredEnabled = false,
            expectedVersion = 4,
            generation = 2,
            createdAtMillis = 1_000,
            expiresAtMillis = 100_000,
            refreshToken = "sealed-refresh",
            credentialExpiresAtMillis = 2_000,
            signOutAfterConsent = true,
        )

        val kept = prunePendingConsentOperations(listOf(operation), nowMillis = 2_000).single()

        assertEquals("owner-a", kept.ownerUserId)
        assertFalse(kept.desiredEnabled)
        assertNull(kept.refreshToken)
        assertTrue(kept.requiresOwnerAuthentication)
        assertEquals("REVOCATION_CREDENTIAL_EXPIRED", kept.lastErrorCode)
    }

    @Test
    fun `expired consent operation remains explicitly unresolved without credentials`() {
        val operation = newPendingConsentOperation("owner-a", false, 2, 3, 1_000, "off-3")
            .copy(refreshToken = "sealed-refresh", credentialExpiresAtMillis = 5_000)

        val kept = prunePendingConsentOperations(
            listOf(operation),
            nowMillis = operation.expiresAtMillis,
        ).single()

        assertEquals("off-3", kept.operationId)
        assertNull(kept.refreshToken)
        assertTrue(kept.requiresOwnerAuthentication)
        assertEquals("CONSENT_OPERATION_EXPIRED", kept.lastErrorCode)
    }

    @Test
    fun `concurrent inserts never drop another record`() = runBlocking {
        val storage = InMemorySessionStorage()
        val now = System.currentTimeMillis()

        (1..20).map { index ->
            async {
                storage.savePendingRevocation(
                    PendingRevocation(
                        id = "id-$index",
                        refreshToken = "token-$index",
                        recordedAtMillis = now + index,
                        expiresAtMillis = now + PENDING_REVOCATION_TTL_MILLIS,
                    ),
                )
            }
        }.awaitAll()

        val tokens = storage.rawPendingRevocations().map { it.refreshToken }.toSet()
        // The queue is bounded, so the oldest inserts may be evicted — but
        // never overwritten by an unrelated record and never duplicated.
        assertTrue(tokens.isNotEmpty())
        assertEquals(storage.rawPendingRevocations().size, tokens.size)
        assertTrue("the newest insert always survives", tokens.contains("token-20"))
    }

    @Test
    fun `removing one record leaves the others and the active session intact`() = runBlocking {
        val storage = InMemorySessionStorage()
        val now = System.currentTimeMillis()
        storage.save(StoredSession("access", "refresh-active", "later", "later"))
        storage.savePendingRevocation(record("a", now))
        storage.savePendingRevocation(record("b", now + 1))

        storage.removePendingRevocation("a")

        assertEquals(listOf("token-b"), storage.pendingRevocations().map { it.refreshToken })
        assertEquals("refresh-active", storage.current()?.refreshToken)
    }

    @Test
    fun `retry backoff starts at 30 seconds and grows exponentially to the cap`() {
        // Index 0 is the first retry after the first failure (ADR 0005 §3):
        // 30s, not the 60s an off-by-one used to schedule.
        assertEquals(PENDING_RETRY_BASE_DELAY_MILLIS, retryDelayMillis(0))
        assertEquals(PENDING_RETRY_BASE_DELAY_MILLIS * 2, retryDelayMillis(1))
        assertEquals(PENDING_RETRY_BASE_DELAY_MILLIS * 8, retryDelayMillis(3))
        assertEquals(PENDING_RETRY_MAX_DELAY_MILLIS, retryDelayMillis(20))
        assertEquals(PENDING_RETRY_MAX_DELAY_MILLIS, retryDelayMillis(Int.MAX_VALUE))
    }

    @Test
    fun `notice dismissal only clears the matching notice`() {
        val store = SessionNoticeStore()
        store.publish(SessionNoticeKind.LocalSignOutRetryScheduled)
        val first = store.notice.value!!
        store.publish(SessionNoticeKind.LocalSignOutRetryUnavailable)
        val second = store.notice.value!!

        store.dismiss(first.id)

        assertEquals("a stale dismissal must not hide the newer notice", second, store.notice.value)
        store.dismiss(second.id)
        assertNull(store.notice.value)
    }
}
