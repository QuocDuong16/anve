package com.anve.app.data.auth

import com.anve.app.data.api.ApiFactory
import com.anve.app.data.api.AnveApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The 401 refresh-retry contract of ADR 0002, exercised against a real
 * HTTP stack pointed at MockWebServer:
 *
 *  - an expired access token triggers exactly one refresh and one retry;
 *  - a rejected refresh clears the local session;
 *  - a transport failure during refresh keeps the session for later.
 */
class SessionInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var storage: InMemorySessionStorage
    private lateinit var api: AnveApi

    private val accessToken = "access-token-1"
    private val refreshToken = "refresh-token-1"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        storage = InMemorySessionStorage()
        runBlocking { storage.save(sessionOf(accessToken, refreshToken)) }

        val plainApi = ApiFactory.api(
            ApiFactory.retrofit(server.url("/").toString(), ApiFactory.okHttp(ApiFactory.passThrough())),
        )
        api = ApiFactory.api(
            ApiFactory.retrofit(
                server.url("/").toString(),
                ApiFactory.okHttp(SessionInterceptor(storage, plainApi)),
            ),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `expired access token refreshes once and retries`() = runBlocking {
        // 1st call: 401 with the stale token. 2nd (refresh): new pair.
        // 3rd (retry): 200.
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"code":"UNAUTHENTICATED","message":"expired"}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"session":{"accessToken":"access-token-2","refreshToken":"refresh-token-2",
                   "accessExpiresAt":"2026-10-26T00:30:00Z","refreshExpiresAt":"2026-11-25T00:00:00Z"}}""".replace("\n", ""),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"u1","email":"a@b.co","displayName":"A",
                   "locationSharingEnabled":false,"createdAt":"2026-10-25T00:00:00Z"}""".replace("\n", ""),
            ),
        )

        val me = api.me()

        assertTrue(me.isSuccessful)
        val stored = storage.current()
        assertNotNull(stored)
        assertEquals("access-token-2", stored!!.accessToken)
        assertEquals("refresh-token-2", stored.refreshToken)

        // Exactly three requests: original, refresh, retry — in arrival order.
        assertEquals(3, server.requestCount)
        val original = server.takeRequest()
        val refresh = server.takeRequest()
        val retry = server.takeRequest()
        assertEquals("/api/v1/me", original.path)
        assertEquals("/api/v1/auth/refresh", refresh.path)
        assertEquals("/api/v1/me", retry.path)
        assertEquals("Bearer access-token-1", original.getHeader("Authorization"))
        assertEquals("Bearer access-token-2", retry.getHeader("Authorization"))
        assertTrue(refresh.body.readUtf8().contains("refresh-token-1"))
    }

    @Test
    fun `rejected refresh clears the session`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"code":"UNAUTHENTICATED","message":"expired"}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"code":"UNAUTHENTICATED","message":"revoked"}""",
            ),
        )

        val me = api.me()

        assertEquals(401, me.code())
        assertNull("a dead refresh token means signed out", storage.current())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `transport failure during refresh keeps the session`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"code":"UNAUTHENTICATED","message":"expired"}""",
            ),
        )
        // The refresh call dies mid-flight (no response).
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))

        val me = api.me()

        assertEquals(401, me.code())
        assertNotNull("offline does not sign the user out", storage.current())
        assertEquals("refresh-token-1", storage.current()!!.refreshToken)
    }

    @Test
    fun `rotation-aware refresh does not rotate twice`() {
        val plainApi = ApiFactory.api(
            ApiFactory.retrofit(server.url("/").toString(), ApiFactory.okHttp(ApiFactory.passThrough())),
        )
        val interceptor = SessionInterceptor(storage, plainApi)

        // A concurrent caller already replaced the token the server rejected.
        runBlocking {
            storage.save(sessionOf("access-token-2", "refresh-token-2"))
        }
        val fresh = interceptor.refreshSync("access-token-1")

        assertEquals("access-token-2", fresh)
        assertEquals("no refresh round-trip was needed", 0, server.requestCount.toLong())
    }

    private fun sessionOf(access: String, refresh: String) = StoredSession(
        accessToken = access,
        refreshToken = refresh,
        accessExpiresAt = "2026-10-25T00:30:00Z",
        refreshExpiresAt = "2026-11-24T00:00:00Z",
    )
}
