package com.anve.app.data.sos

import com.anve.app.data.api.ApiFactory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiSosGatewayTest {
    @Test fun `place signal checks the exact authorized event rather than a bounded feed`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val api = ApiFactory.api(ApiFactory.retrofit(server.url("/").toString(), ApiFactory.okHttp(ApiFactory.passThrough())))
            val repository = PushEventRepository(api)
            server.enqueue(MockResponse().setBody("""{"id":"event","placeId":"place","placeName":"Home","ownerId":"owner","ownerName":"An","type":"ENTER","recordedAt":"2026-01-01T00:00:00Z","processedAt":"2026-01-01T00:01:00Z","delayed":false,"confidence":"OBSERVED"}"""))
            assertTrue(repository.authorized("viewer", "PLACE", "event"))
            val request = server.takeRequest()
            assertEquals("/api/v1/places/events/event", request.path)
            assertEquals("viewer", request.getHeader("X-Anve-Expected-Owner"))
            server.enqueue(MockResponse().setResponseCode(404))
            assertFalse(repository.authorized("viewer", "PLACE", "event"))
        } finally { server.shutdown() }
    }

    @Test fun `create and cancel bind the owner and never include coordinates`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val api = ApiFactory.api(ApiFactory.retrofit(server.url("/").toString(), ApiFactory.okHttp(ApiFactory.passThrough())))
            val gateway = ApiSosGateway(api)
            val item = SosOutboxItem("event", "owner", "2026-10-30T00:00:00Z", listOf("recipient"), 1000, SosLocalState.UNKNOWN)
            server.enqueue(MockResponse().setBody("""{"id":"event","ownerId":"owner","recordedAt":"2026-10-30T00:00:00Z","receivedAt":"2026-10-30T00:00:01Z","state":"ACTIVE"}"""))
            gateway.create("owner", item)
            val create = server.takeRequest()
            assertEquals("owner", create.getHeader("X-Anve-Expected-Owner"))
            val body = create.body.readUtf8()
            assertFalse(body.contains("latitude"))
            assertFalse(body.contains("longitude"))
            server.enqueue(MockResponse().setResponseCode(204))
            gateway.cancel("owner", "event")
            val cancel = server.takeRequest()
            assertEquals("owner", cancel.getHeader("X-Anve-Expected-Owner"))
            assertEquals("/api/v1/sos/event/cancel", cancel.path)
        } finally {
            server.shutdown()
        }
    }
}
