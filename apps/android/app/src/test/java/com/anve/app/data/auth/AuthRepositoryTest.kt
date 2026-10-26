package com.anve.app.data.auth

import com.anve.app.data.api.ApiException
import com.anve.app.data.api.ApiFactory
import com.anve.app.data.api.DeviceRegistrationDto
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
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
 * it, and an offline sign-out must leave a sealed pending-revocation record
 * instead of pretending the session is gone server-side.
 *
 * These tests use the interceptor-free API on purpose — the 401 refresh
 * machinery has its own suite (SessionInterceptorTest).
 */
class AuthRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var storage: InMemorySessionStorage
    private lateinit var repository: AuthRepository

    private val device = object : DeviceRegistrationProvider {
        override suspend fun registration() = DeviceRegistrationDto("test-device-0001")
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        storage = InMemorySessionStorage()
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
    )

    private fun signedIn(refreshToken: String = "refresh-1") = runBlocking {
        storage.save(
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
    fun `sign out confirmed by the server leaves no pending revocation`() = runBlocking {
        signedIn()
        server.enqueue(MockResponse().setResponseCode(204))

        val outcome = repository.signOut()

        assertEquals(SignOutOutcome.ServerConfirmed, outcome)
        assertNull(storage.current())
        assertNull(storage.pendingRevocation())
    }

    @Test
    fun `sign out of an already dead session counts as confirmed`() = runBlocking {
        signedIn()
        server.enqueue(MockResponse().setResponseCode(401).setBody(errorBody("UNAUTHENTICATED")))

        val outcome = repository.signOut()

        assertEquals(SignOutOutcome.ServerConfirmed, outcome)
        assertNull(storage.current())
        assertNull(storage.pendingRevocation())
    }

    @Test
    fun `offline sign out clears locally and records a pending revocation`() = runBlocking {
        signedIn(refreshToken = "refresh-offline")
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val outcome = repository.signOut()

        assertEquals(SignOutOutcome.LocalOnly, outcome)
        assertNull("a local sign-out must always succeed", storage.current())
        assertEquals("refresh-offline", storage.rawPendingRevocation()?.refreshToken)
    }

    @Test
    fun `server error on sign out still keeps a pending revocation`() = runBlocking {
        signedIn(refreshToken = "refresh-500")
        server.enqueue(MockResponse().setResponseCode(500).setBody(errorBody("INTERNAL")))

        assertEquals(SignOutOutcome.LocalOnly, repository.signOut())
        assertNull(storage.current())
        assertEquals("refresh-500", storage.rawPendingRevocation()?.refreshToken)
    }

    @Test
    fun `expired pending revocation is ignored`() = runBlocking {
        val old = System.currentTimeMillis() - PENDING_REVOCATION_TTL_MILLIS - 1
        storage.savePendingRevocation(PendingRevocation("refresh-stale", recordedAtMillis = old))

        assertNull(storage.pendingRevocation())
        assertNull("an expired record is dropped on read", storage.rawPendingRevocation())
    }

    // ── pending revocation retry ─────────────────────────────────────────────

    @Test
    fun `retry rotates the old token and revokes the session`() = runBlocking {
        storage.savePendingRevocation(PendingRevocation("refresh-old", recordedAtMillis = System.currentTimeMillis()))
        server.enqueue(MockResponse().setBody(sessionJson("access-new", "refresh-new")))
        server.enqueue(MockResponse().setResponseCode(204))

        repository.retryPendingRevocation()

        assertNull("a confirmed revocation clears the record", storage.pendingRevocation())
        val refresh = server.takeRequest()
        assertEquals("/api/v1/auth/refresh", refresh.path)
        assertTrue(refresh.body.readUtf8().contains("refresh-old"))
        val signOut = server.takeRequest()
        assertEquals("Bearer access-new", signOut.getHeader("Authorization"))
    }

    @Test
    fun `retry of a dead refresh token clears the record`() = runBlocking {
        storage.savePendingRevocation(PendingRevocation("refresh-dead", recordedAtMillis = System.currentTimeMillis()))
        server.enqueue(MockResponse().setResponseCode(401).setBody(errorBody("UNAUTHENTICATED")))

        repository.retryPendingRevocation()

        assertNull(storage.pendingRevocation())
        assertEquals("no sign-out round-trip for a dead token", 1, server.requestCount)
    }

    @Test
    fun `retry keeps the record when the network is down`() = runBlocking {
        storage.savePendingRevocation(PendingRevocation("refresh-keep", recordedAtMillis = System.currentTimeMillis()))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        repository.retryPendingRevocation()

        assertEquals("refresh-keep", storage.rawPendingRevocation()?.refreshToken)
    }

    @Test
    fun `failed revocation after a rotation keeps the rotated token`() = runBlocking {
        storage.savePendingRevocation(PendingRevocation("refresh-old", recordedAtMillis = System.currentTimeMillis()))
        server.enqueue(MockResponse().setBody(sessionJson("access-new", "refresh-new")))
        server.enqueue(MockResponse().setResponseCode(500).setBody(errorBody("INTERNAL")))

        repository.retryPendingRevocation()

        assertEquals(
            "the record follows the rotation so a later retry still works",
            "refresh-new",
            storage.rawPendingRevocation()?.refreshToken,
        )
    }

    @Test
    fun `a new repository instance resumes the pending revocation after a restart`() = runBlocking {
        storage.savePendingRevocation(PendingRevocation("refresh-restart", recordedAtMillis = System.currentTimeMillis()))
        // Process recreation: a fresh repository over the same storage.
        val restarted = repository()
        server.enqueue(MockResponse().setResponseCode(401).setBody(errorBody("UNAUTHENTICATED")))

        restarted.retryPendingRevocation()

        assertNull(storage.pendingRevocation())
    }

    @Test
    fun `retry without a pending record performs no requests`() = runBlocking {
        repository.retryPendingRevocation()

        assertEquals(0, server.requestCount)
    }
}
