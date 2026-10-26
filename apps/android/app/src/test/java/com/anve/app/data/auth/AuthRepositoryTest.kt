package com.anve.app.data.auth

import com.anve.app.data.api.ApiException
import com.anve.app.data.api.ApiFactory
import com.anve.app.data.api.DeviceRegistrationDto
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Repository-level session lifecycle against real HTTP responses: account
 * deletion must never clear the local session unless the server confirmed
 * it, and offline sign-outs must queue one independent revocation record per
 * account instead of pretending the session is gone server-side.
 *
 * These tests use the interceptor-free API on purpose — the 401 refresh
 * machinery has its own suite (SessionInterceptorTest).
 */
class AuthRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var storage: InMemorySessionStorage
    private lateinit var notices: SessionNoticeStore
    private lateinit var scheduler: CountingScheduler
    private lateinit var repository: AuthRepository

    private class CountingScheduler : PendingRevocationScheduler {
        var scheduled = 0
            private set

        override fun ensureScheduled() {
            scheduled++
        }
    }

    private val device = object : DeviceRegistrationProvider {
        override suspend fun registration() = DeviceRegistrationDto("test-device-0001")
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        storage = InMemorySessionStorage()
        notices = SessionNoticeStore()
        scheduler = CountingScheduler()
        repository = repository()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun repository(nowMillis: Long = System.currentTimeMillis()) = AuthRepository(
        api = ApiFactory.api(
            ApiFactory.retrofit(server.url("/").toString(), ApiFactory.okHttp(ApiFactory.passThrough())),
        ),
        plainApi = ApiFactory.api(
            ApiFactory.retrofit(server.url("/").toString(), ApiFactory.okHttp(ApiFactory.passThrough())),
        ),
        storage = storage,
        deviceIdentity = device,
        now = { nowMillis },
        notices = notices,
        retryScheduler = scheduler,
    )

    /** Production wiring: the authenticated client carries SessionInterceptor. */
    private fun wiredRepository(): AuthRepository {
        val plain = ApiFactory.api(
            ApiFactory.retrofit(server.url("/").toString(), ApiFactory.okHttp(ApiFactory.passThrough())),
        )
        val authenticated = ApiFactory.api(
            ApiFactory.retrofit(
                server.url("/").toString(),
                ApiFactory.okHttp(SessionInterceptor(storage, plain)),
            ),
        )
        return AuthRepository(
            api = authenticated,
            plainApi = plain,
            storage = storage,
            deviceIdentity = device,
            notices = notices,
            retryScheduler = scheduler,
        )
    }

    private fun signedIn(refreshToken: String = "refresh-1") = runBlocking {        storage.save(
            StoredSession(
                accessToken = "access-1",
                refreshToken = refreshToken,
                accessExpiresAt = "2026-10-30T00:00:00Z",
                refreshExpiresAt = "2026-11-25T00:00:00Z",
            ),
        )
    }

    private fun errorBody(code: String) = """{"code":"$code","message":"nope"}"""

    private fun sessionJson(access: String, refresh: String) =
        """{"session":{"accessToken":"$access","refreshToken":"$refresh",
           "accessExpiresAt":"2026-10-30T00:00:00Z","refreshExpiresAt":"2026-11-25T00:00:00Z"}}""".replace("\n", "")

    private fun signInJson(access: String, refresh: String) =
        """{"user":{"id":"u-${refresh}","email":"$refresh@example.com","displayName":"User",
           "locationSharingEnabled":false,"createdAt":"2026-10-25T00:00:00Z"},
           "session":{"accessToken":"$access","refreshToken":"$refresh",
           "accessExpiresAt":"2026-10-30T00:00:00Z","refreshExpiresAt":"2026-11-25T00:00:00Z"}}"""
            .replace("\n", "")

    private fun pending(
        token: String,
        recordedAtMillis: Long = System.currentTimeMillis(),
        expiresAtMillis: Long = recordedAtMillis + PENDING_REVOCATION_TTL_MILLIS,
        id: String = "id-$token",
    ) = PendingRevocation(
        id = id,
        refreshToken = token,
        recordedAtMillis = recordedAtMillis,
        expiresAtMillis = expiresAtMillis,
    )

    // ── account deletion ─────────────────────────────────────────────────────

    @Test
    fun `delete success clears the local session`() = runBlocking {
        signedIn()
        server.enqueue(MockResponse().setResponseCode(204))

        repository.deleteAccount()

        assertNull(storage.current())
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/v1/me", request.path)
        assertTrue(request.body.readUtf8().contains("\"confirm\":true"))
    }

    @Test
    fun `delete rejects with an API error and keeps the session`() = runBlocking {
        val cases = listOf(
            400 to errorBody("VALIDATION"),
            401 to errorBody("UNAUTHENTICATED"),
            409 to errorBody("CIRCLE_HAS_MEMBERS"),
            500 to errorBody("INTERNAL"),
        )
        for ((status, body) in cases) {
            signedIn()
            server.enqueue(MockResponse().setResponseCode(status).setBody(body))

            try {
                repository.deleteAccount()
                fail("expected ApiException for HTTP $status")
            } catch (e: ApiException) {
                assertEquals(
                    when (status) {
                        400 -> "VALIDATION"
                        401 -> "UNAUTHENTICATED"
                        409 -> "CIRCLE_HAS_MEMBERS"
                        else -> "INTERNAL"
                    },
                    e.code,
                )
            }
            assertNotNull("HTTP $status must not clear the session", storage.current())
            server.takeRequest()
        }
    }

    @Test
    fun `delete with a transport failure keeps the session`() = runBlocking {
        signedIn()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        try {
            repository.deleteAccount()
            fail("expected IOException")
        } catch (_: IOException) {
            // Unknown outcome: the repository must not guess.
        }
        assertNotNull(storage.current())
    }

    @Test
    fun `concurrent failed deletes keep the session intact`() = runBlocking {
        signedIn()
        repeat(2) { server.enqueue(MockResponse().setResponseCode(500).setBody(errorBody("INTERNAL"))) }

        val results = listOf(
            async { runCatching { repository.deleteAccount() } },
            async { runCatching { repository.deleteAccount() } },
        ).awaitAll()

        assertTrue(results.all { it.isFailure })
        assertEquals("access-1", storage.current()?.accessToken)
    }

    @Test
    fun `double tap with one success clears the session once`() = runBlocking {
        signedIn()
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(500).setBody(errorBody("INTERNAL")))

        repository.deleteAccount()
        runCatching { repository.deleteAccount() }

        assertNull(storage.current())
        assertEquals(2, server.requestCount)
    }

    // ── sign-out ─────────────────────────────────────────────────────────────

    @Test
    fun `sign out confirmed by the server leaves no pending revocation or notice`() = runBlocking {
        signedIn()
        server.enqueue(MockResponse().setResponseCode(204))

        val outcome = repository.signOut()

        assertEquals(SignOutOutcome.ServerConfirmed, outcome)
        assertNull(storage.current())
        assertTrue(storage.pendingRevocations().isEmpty())
        assertNull(notices.notice.value)
    }

    @Test
    fun `sign out with a rejected access token queues the revocation when storage is intact`() = runBlocking {
        signedIn()
        server.enqueue(MockResponse().setResponseCode(401).setBody(errorBody("UNAUTHENTICATED")))

        val outcome = repository.signOut()

        assertEquals(
            "a bare 401 may only mean the access token expired; the session is still stored",
            SignOutOutcome.LocalOnly,
            outcome,
        )
        assertNull(storage.current())
        assertEquals("refresh-1", storage.pendingRevocations().single().refreshToken)
    }

    @Test
    fun `sign out is server-confirmed when the refresh token is rejected too`() = runBlocking {
        signedIn("refresh-dead")
        server.enqueue(MockResponse().setResponseCode(401).setBody(errorBody("UNAUTHENTICATED")))
        server.enqueue(MockResponse().setResponseCode(401).setBody(errorBody("UNAUTHENTICATED")))

        val outcome = wiredRepository().signOut()

        assertEquals(SignOutOutcome.ServerConfirmed, outcome)
        assertNull("the interceptor cleared the dead session", storage.current())
        assertTrue(storage.pendingRevocations().isEmpty())
        assertNull(notices.notice.value)
    }

    @Test
    fun `transient refresh failure during sign out keeps the revocation pending`() = runBlocking {
        signedIn("refresh-live")
        server.enqueue(MockResponse().setResponseCode(401).setBody(errorBody("UNAUTHENTICATED")))
        server.enqueue(MockResponse().setResponseCode(500).setBody(errorBody("INTERNAL")))

        val outcome = wiredRepository().signOut()

        assertEquals(SignOutOutcome.LocalOnly, outcome)
        assertEquals("refresh-live", storage.pendingRevocations().single().refreshToken)
        assertEquals(SessionNoticeKind.LocalSignOutRetryScheduled, notices.notice.value?.kind)
    }

    @Test
    fun `a rotation before a failed sign-out queues the rotated token`() = runBlocking {
        signedIn("refresh-old")
        server.enqueue(MockResponse().setResponseCode(401).setBody(errorBody("UNAUTHENTICATED")))
        server.enqueue(MockResponse().setBody(sessionJson("access-new", "refresh-new")))
        server.enqueue(MockResponse().setResponseCode(500).setBody(errorBody("INTERNAL")))

        val outcome = wiredRepository().signOut()

        assertEquals(SignOutOutcome.LocalOnly, outcome)
        assertEquals(
            "the old token was consumed by rotation; the retry must use the new one",
            "refresh-new",
            storage.pendingRevocations().single().refreshToken,
        )
    }

    @Test
    fun `offline sign out queues a revocation and publishes the notice before clearing`() = runBlocking {
        signedIn(refreshToken = "refresh-offline")
        // A wrapper proves the message is published before the session flow
        // can navigate away from the screen that triggered the sign-out.
        val observedBeforeClear = arrayOf(false)
        val checkingStorage = object : SessionStorage by storage {
            override suspend fun clear() {
                observedBeforeClear[0] = notices.notice.value != null
                storage.clear()
            }
        }
        val checkingRepository = AuthRepository(
            api = ApiFactory.api(
                ApiFactory.retrofit(server.url("/").toString(), ApiFactory.okHttp(ApiFactory.passThrough())),
            ),
            plainApi = ApiFactory.api(
                ApiFactory.retrofit(server.url("/").toString(), ApiFactory.okHttp(ApiFactory.passThrough())),
            ),
            storage = checkingStorage,
            deviceIdentity = device,
            notices = notices,
        )
        server.enqueue(MockResponse().setResponseCode(500).setBody(errorBody("INTERNAL")))

        val outcome = checkingRepository.signOut()

        assertEquals(SignOutOutcome.LocalOnly, outcome)
        assertNull("a local sign-out must always succeed", storage.current())
        assertEquals("refresh-offline", storage.pendingRevocations().single().refreshToken)
        assertTrue("the notice must exist before storage clear", observedBeforeClear[0])
        assertEquals(SessionNoticeKind.LocalSignOutRetryScheduled, notices.notice.value?.kind)
    }

    @Test
    fun `server error on sign out still queues the revocation`() = runBlocking {
        signedIn(refreshToken = "refresh-500")
        server.enqueue(MockResponse().setResponseCode(500).setBody(errorBody("INTERNAL")))

        assertEquals(SignOutOutcome.LocalOnly, repository.signOut())
        assertNull(storage.current())
        assertEquals("refresh-500", storage.pendingRevocations().single().refreshToken)
    }

    @Test
    fun `sign out without a local session does not publish a notice`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody(errorBody("INTERNAL")))

        assertEquals(SignOutOutcome.LocalOnly, repository.signOut())

        assertNull(notices.notice.value)
        assertTrue(storage.pendingRevocations().isEmpty())
    }

    // ── pending revocation queue ────────────────────────────────────────────

    // Test A: two accounts signing out offline must not overwrite each other.
    @Test
    fun `two accounts sign out offline and both revocations survive`() = runBlocking {
        signedIn(refreshToken = "refresh-a")
        server.enqueue(MockResponse().setResponseCode(500).setBody(errorBody("INTERNAL")))
        assertEquals(SignOutOutcome.LocalOnly, repository.signOut())

        server.enqueue(MockResponse().setBody(signInJson("access-b", "refresh-b")))
        repository.signInDev("b@example.com")
        server.enqueue(MockResponse().setResponseCode(500).setBody(errorBody("INTERNAL")))
        assertEquals(SignOutOutcome.LocalOnly, repository.signOut())

        val tokens = storage.pendingRevocations().map { it.refreshToken }.toSet()
        assertEquals(setOf("refresh-a", "refresh-b"), tokens)
        assertEquals(SessionNoticeKind.LocalSignOutRetryScheduled, notices.notice.value?.kind)
    }

    // Test B: one record succeeds, the other fails at the network; only the
    // delivered one leaves the queue.
    @Test
    fun `retry removes only the confirmed record`() = runBlocking {
        val t = System.currentTimeMillis()
        storage.savePendingRevocation(pending("refresh-a", recordedAtMillis = t))
        storage.savePendingRevocation(pending("refresh-b", recordedAtMillis = t + 1))

        server.enqueue(MockResponse().setBody(sessionJson("access-a2", "refresh-a2")))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        repository.retryPendingRevocations()

        val remaining = storage.pendingRevocations()
        assertEquals(listOf("refresh-b"), remaining.map { it.refreshToken })
        assertEquals("the failed record is scheduled for a later retry", 1, remaining.single().attemptCount)
    }

    // Test C: a process restart loads the whole queue from storage.
    @Test
    fun `a new repository instance retries every queued record after a restart`() = runBlocking {
        val t = System.currentTimeMillis()
        storage.savePendingRevocation(pending("refresh-a", recordedAtMillis = t))
        storage.savePendingRevocation(pending("refresh-b", recordedAtMillis = t + 1))
        // Process recreation: a fresh repository over the same storage.
        val restarted = repository()
        server.enqueue(MockResponse().setResponseCode(401).setBody(errorBody("UNAUTHENTICATED")))
        server.enqueue(MockResponse().setResponseCode(401).setBody(errorBody("UNAUTHENTICATED")))

        restarted.retryPendingRevocations()

        assertTrue(storage.pendingRevocations().isEmpty())
        assertEquals("both records were attempted", 2, server.requestCount)
    }

    // Test D: a successful rotation followed by a failed revocation keeps
    // the rotated token, and a later retry continues from it.
    @Test
    fun `rotation failure keeps the rotated token for a later retry`() = runBlocking {
        val start = System.currentTimeMillis()
        storage.savePendingRevocation(pending("refresh-old", recordedAtMillis = start))
        server.enqueue(MockResponse().setBody(sessionJson("access-new", "refresh-new")))
        server.enqueue(MockResponse().setResponseCode(500).setBody(errorBody("INTERNAL")))

        repository(nowMillis = start).retryPendingRevocations()

        val kept = storage.pendingRevocations().single()
        assertEquals("refresh-new", kept.refreshToken)
        assertEquals(1, kept.attemptCount)

        // The next launch happens after the first-retry backoff (30s), not
        // the second one (60s).
        val later = start + retryDelayMillis(0) + 1
        val restarted = repository(nowMillis = later)
        server.enqueue(MockResponse().setBody(sessionJson("access-new2", "refresh-new2")))
        server.enqueue(MockResponse().setResponseCode(204))

        restarted.retryPendingRevocations()

        assertTrue(storage.pendingRevocations().isEmpty())
        server.takeRequest() // first retry: refresh
        server.takeRequest() // first retry: sign-out
        val retryRefresh = server.takeRequest() // second retry: refresh
        assertTrue(
            "the second retry must use the rotated refresh token",
            retryRefresh.body.readUtf8().contains("refresh-new"),
        )
    }

    // Test E: an expired record is dropped while the live one remains.
    @Test
    fun `expired records are dropped without touching valid ones`() = runBlocking {
        val now = System.currentTimeMillis()
        storage.savePendingRevocation(
            pending("refresh-stale", recordedAtMillis = now - 10_000, expiresAtMillis = now - 1),
        )
        storage.savePendingRevocation(
            pending("refresh-live", recordedAtMillis = now - 1_000, expiresAtMillis = now + 60_000),
        )

        assertEquals(listOf("refresh-live"), storage.pendingRevocations().map { it.refreshToken })
        assertEquals(
            "expired records are also removed from the raw queue",
            listOf("refresh-live"),
            storage.rawPendingRevocations().map { it.refreshToken },
        )
    }

    // Test F: revoking A must never clear or rotate B's active session.
    @Test
    fun `retry of a pending revocation leaves another account's session alone`() = runBlocking {
        storage.save(
            StoredSession(
                accessToken = "access-b",
                refreshToken = "refresh-b-session",
                accessExpiresAt = "2026-10-30T00:00:00Z",
                refreshExpiresAt = "2026-11-25T00:00:00Z",
            ),
        )
        storage.savePendingRevocation(pending("refresh-a", recordedAtMillis = System.currentTimeMillis()))
        server.enqueue(MockResponse().setBody(sessionJson("access-a2", "refresh-a2")))
        server.enqueue(MockResponse().setResponseCode(204))

        repository.retryPendingRevocations()

        assertEquals("access-b", storage.current()?.accessToken)
        assertEquals("refresh-b-session", storage.current()?.refreshToken)
        assertTrue(storage.pendingRevocations().isEmpty())
    }

    // Test G: a retry running while another account signs out offline may
    // not remove the new record (or the active session).
    @Test
    fun `a concurrent retry and sign-out never lose the new record`() = runBlocking {
        storage.savePendingRevocation(pending("refresh-a", recordedAtMillis = System.currentTimeMillis()))
        signedIn(refreshToken = "refresh-b")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                return when {
                    request.path == "/api/v1/auth/refresh" && body.contains("refresh-a") ->
                        MockResponse().setResponseCode(401).setBody(errorBody("UNAUTHENTICATED"))
                    request.path == "/api/v1/auth/refresh" ->
                        MockResponse().setResponseCode(500).setBody(errorBody("INTERNAL"))
                    request.path == "/api/v1/auth/signout" ->
                        MockResponse().setResponseCode(500).setBody(errorBody("INTERNAL"))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val retry = async { repository.retryPendingRevocations() }
        val signOut = async { repository.signOut() }
        retry.await()
        signOut.await()

        assertNull("the user's local sign-out still wins", storage.current())
        assertEquals(
            "the new offline revocation must survive the concurrent pass",
            listOf("refresh-b"),
            storage.pendingRevocations().map { it.refreshToken },
        )
    }

    // ── retry engine details ─────────────────────────────────────────────────

    @Test
    fun `retry rotates the old token and revokes the session`() = runBlocking {
        storage.savePendingRevocation(pending("refresh-old"))
        server.enqueue(MockResponse().setBody(sessionJson("access-new", "refresh-new")))
        server.enqueue(MockResponse().setResponseCode(204))

        repository.retryPendingRevocations()

        assertTrue("a confirmed revocation clears the record", storage.pendingRevocations().isEmpty())
        val refresh = server.takeRequest()
        assertEquals("/api/v1/auth/refresh", refresh.path)
        assertTrue(refresh.body.readUtf8().contains("refresh-old"))
        val signOut = server.takeRequest()
        assertEquals("Bearer access-new", signOut.getHeader("Authorization"))
    }

    @Test
    fun `retry of a dead refresh token clears the record`() = runBlocking {
        storage.savePendingRevocation(pending("refresh-dead"))
        server.enqueue(MockResponse().setResponseCode(401).setBody(errorBody("UNAUTHENTICATED")))

        repository.retryPendingRevocations()

        assertTrue(storage.pendingRevocations().isEmpty())
        assertEquals("no sign-out round-trip for a dead token", 1, server.requestCount)
    }

    @Test
    fun `retry keeps the record when the network is down`() = runBlocking {
        storage.savePendingRevocation(pending("refresh-keep"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        repository.retryPendingRevocations()

        val kept = storage.pendingRevocations().single()
        assertEquals("refresh-keep", kept.refreshToken)
        assertTrue("the retry is pushed into the future", kept.nextAttemptAtMillis > 0)
    }

    @Test
    fun `the first failed retry waits 30 seconds and keeps work scheduled`() = runBlocking {
        val start = System.currentTimeMillis()
        storage.savePendingRevocation(pending("refresh-keep", recordedAtMillis = start))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val remaining = repository(nowMillis = start).retryPendingRevocations()

        assertTrue("a failed record remains pending", remaining)
        val kept = storage.pendingRevocations().single()
        assertEquals(start + PENDING_RETRY_BASE_DELAY_MILLIS, kept.nextAttemptAtMillis)
        assertEquals(1, scheduler.scheduled)
    }

    @Test
    fun `a completed retry reports that no work needs to stay scheduled`() = runBlocking {
        storage.savePendingRevocation(pending("refresh-dead"))
        server.enqueue(MockResponse().setResponseCode(401).setBody(errorBody("UNAUTHENTICATED")))

        val remaining = repository.retryPendingRevocations()

        assertFalse("the dead session leaves nothing to retry", remaining)
        assertTrue(storage.pendingRevocations().isEmpty())
    }

    @Test
    fun `an offline sign-out schedules a constrained retry`() = runBlocking {
        signedIn()
        server.enqueue(MockResponse().setResponseCode(500).setBody(errorBody("INTERNAL")))

        repository.signOut()

        assertEquals(1, scheduler.scheduled)
    }

    @Test
    fun `retry without a pending record performs no requests`() = runBlocking {
        repository.retryPendingRevocations()

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a successful sign-in clears a stale sign-out notice`() = runBlocking {
        notices.publish(SessionNoticeKind.LocalSignOutRetryScheduled)
        server.enqueue(MockResponse().setBody(signInJson("access-new", "refresh-new")))

        repository.signInDev("new@example.com")

        assertNull("a new account must not inherit the old message", notices.notice.value)
        assertEquals("access-new", storage.current()?.accessToken)
    }
}
