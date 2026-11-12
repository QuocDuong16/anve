package com.anve.app.data.circles

import com.anve.app.data.api.ApiFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InvitationConfirmationTest {
    private val joined = """{"circle":{"id":"circle","name":"Nhà","createdBy":"other","createdAt":"2026-11-12T00:00:00Z"},"membership":{"circleId":"circle","userId":"owner","role":"member","joinedAt":"2026-11-12T00:00:00Z"}}"""

    @Test fun `uncertain response uses account-bound readback and never replays POST`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val repository = CircleRepository(ApiFactory.api(ApiFactory.retrofit(server.url("/").toString(), ApiFactory.okHttp(ApiFactory.passThrough()))))
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody(joined))
            assertTrue(repository.confirmInvitation("owner", "ABCD234567", "circle") is InvitationConfirmation.Joined)
            val post = server.takeRequest()
            val get = server.takeRequest()
            assertEquals("POST", post.method)
            assertEquals("/api/v1/invites/redeem", post.path)
            assertEquals("owner", post.getHeader("X-Anve-Expected-Owner"))
            assertEquals("GET", get.method)
            assertEquals("owner", get.getHeader("X-Anve-Expected-Owner"))
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        } finally { server.shutdown() }
    }

    @Test fun `empty readback is unknown and check again remains read only`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val repository = CircleRepository(ApiFactory.api(ApiFactory.retrofit(server.url("/").toString(), ApiFactory.okHttp(ApiFactory.passThrough()))))
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("""{"circle":null,"membership":null}"""))
            assertEquals(InvitationConfirmation.Unknown, repository.confirmInvitation("owner", "ABCD234567", "circle"))
            server.takeRequest(); server.takeRequest()
            server.enqueue(MockResponse().setBody(joined))
            assertTrue(repository.reconcileInvitation("owner", "circle") is InvitationConfirmation.Joined)
            assertEquals("GET", server.takeRequest().method)
        } finally { server.shutdown() }
    }
}
