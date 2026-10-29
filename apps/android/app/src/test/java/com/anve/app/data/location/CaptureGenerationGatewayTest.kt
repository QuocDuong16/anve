package com.anve.app.data.location

import com.anve.app.data.api.ApiFactory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureGenerationGatewayTest {
    @Test fun `checkpoint request binds the expected account`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val api = ApiFactory.api(ApiFactory.retrofit(server.url("/").toString(), ApiFactory.okHttp(ApiFactory.passThrough())))
            server.enqueue(MockResponse().setBody("""{"generation":1}"""))
            assertEquals(1L, ApiLocationSyncGateway(api).captureGeneration("owner-a"))
            val request = server.takeRequest()
            assertEquals("owner-a", request.getHeader("X-Anve-Expected-Owner"))
            assertEquals("GET", request.method)
        } finally {
            server.shutdown()
        }
    }
}
