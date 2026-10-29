package com.anve.app.data.location

import com.anve.app.data.api.ApiFactory
import com.anve.app.data.api.PlaceInputDto
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlacesRepositoryTest {
    @Test fun `place reads and explicit monitoring mutations are owner bound`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val api = ApiFactory.api(ApiFactory.retrofit(server.url("/").toString(), ApiFactory.okHttp(ApiFactory.passThrough())))
            val repository = PlacesRepository(api, VisibleLocationRepository(api))
            server.enqueue(MockResponse().setBody("""{"places":[]}"""))
            assertEquals(emptyList<Any>(), repository.places("owner"))
            assertEquals("owner", server.takeRequest().getHeader("X-Anve-Expected-Owner"))
            server.enqueue(MockResponse().setResponseCode(404))
            assertNull(repository.monitor("owner", "place"))
            assertEquals("/api/v1/places/place/monitor", server.takeRequest().path)
            server.enqueue(MockResponse().setBody("""{"memberIds":["viewer"]}"""))
            assertEquals(listOf("viewer"), repository.eligibleRecipients("owner", "place"))
            assertEquals("owner", server.takeRequest().getHeader("X-Anve-Expected-Owner"))
            server.enqueue(MockResponse().setBody("""{"placeId":"place","ownerId":"owner","recipientIds":["viewer"],"active":true}"""))
            assertEquals(listOf("viewer"), repository.configure("owner", "place", listOf("viewer")).recipientIds)
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("owner", request.getHeader("X-Anve-Expected-Owner"))
            assertEquals("""{"recipientIds":["viewer"]}""", request.body.readUtf8())
            server.enqueue(MockResponse().setBody("""{"id":"p","ownerId":"owner","circleId":"c","name":"Nhà","latitude":10,"longitude":106,"radiusMeters":150,"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}"""))
            repository.create("owner", PlaceInputDto("Nhà", 10.0, 106.0, 150.0))
            assertEquals("owner", server.takeRequest().getHeader("X-Anve-Expected-Owner"))
        } finally {
            server.shutdown()
        }
    }
}
