package com.anve.app.data.location

import com.anve.app.data.api.ApiFactory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HistoryRepositoryTest {
    @Test
    fun `history and owner settings bind every request to the active account`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val api = ApiFactory.api(ApiFactory.retrofit(server.url("/").toString(), ApiFactory.okHttp(ApiFactory.passThrough())))
            val repository = HistoryRepository(api)
            server.enqueue(MockResponse().setBody("""{"points":[],"nextCursor":"cursor"}"""))
            assertEquals("cursor", repository.page("viewer", "member", "2026-10-28T00:00:00Z", "2026-10-29T00:00:00Z", null).nextCursor)
            val history = server.takeRequest()
            assertEquals("viewer", history.getHeader("X-Anve-Expected-Owner"))
            assertEquals("member", history.requestUrl?.queryParameter("ownerId"))
            server.enqueue(MockResponse().setBody("""{"days":30,"effectiveDays":7}"""))
            assertEquals(7, repository.retention("viewer").effectiveDays)
            assertEquals("viewer", server.takeRequest().getHeader("X-Anve-Expected-Owner"))
            server.enqueue(MockResponse().setBody("""{"days":7,"effectiveDays":7}"""))
            repository.setRetention("viewer", 7)
            val setting = server.takeRequest()
            assertEquals("viewer", setting.getHeader("X-Anve-Expected-Owner"))
            assertFalse(setting.body.readUtf8().contains("effectiveDays"))
            server.enqueue(MockResponse().setResponseCode(204))
            repository.delete("viewer", null, "2026-10-29T00:00:00Z")
            val deletion = server.takeRequest()
            assertEquals("DELETE", deletion.method)
            assertEquals("viewer", deletion.getHeader("X-Anve-Expected-Owner"))
        } finally {
            server.shutdown()
        }
    }
}
