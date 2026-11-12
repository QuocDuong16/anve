package com.anve.app.data.api

import java.util.concurrent.TimeUnit
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrivateMutationTransportTest {
    @Test fun `503 retry-after zero does not replay upload creation or bodyless deletion`() {
        for (method in listOf("PUT", "POST", "DELETE")) {
            val server = MockWebServer(); server.start()
            try {
                server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
                server.enqueue(MockResponse().setResponseCode(200))
                val client = ApiFactory.privateMutations(ApiFactory.okHttp(ApiFactory.passThrough()))
                val body = if (method == "DELETE") null else "synthetic private content".toRequestBody()
                client.newCall(Request.Builder().url(server.url("/private")).method(method, body)
                    .header("X-Anve-Expected-Owner", "owner").build()).execute().use { assertEquals(503, it.code) }
                assertEquals(method, server.takeRequest().method)
                assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
            } finally { server.shutdown() }
        }
    }

    @Test fun `redirect does not forward image content or owner headers to another endpoint`() {
        val source = MockWebServer(); val target = MockWebServer()
        source.start(); target.start()
        try {
            source.enqueue(MockResponse().setResponseCode(307).setHeader("Location", target.url("/unexpected")))
            target.enqueue(MockResponse().setResponseCode(200))
            val client = ApiFactory.privateMutations(ApiFactory.okHttp(ApiFactory.passThrough()))
            client.newCall(Request.Builder().url(source.url("/avatar")).put("synthetic image".toRequestBody())
                .header("X-Anve-Expected-Owner", "owner").build()).execute().use { assertEquals(307, it.code) }
            assertEquals(1, source.requestCount)
            assertNull(target.takeRequest(100, TimeUnit.MILLISECONDS))
        } finally { source.shutdown(); target.shutdown() }
    }
}
