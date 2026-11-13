package com.anve.app.data.avatar

import com.anve.app.data.api.ApiFactory
import com.anve.app.data.auth.InMemorySessionStorage
import com.anve.app.data.auth.StoredSession
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarRepositoryTest {
    @Test fun `binary upload metadata readback owner headers and current identity are enforced`() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val sessions = InMemorySessionStorage(); sessions.save(StoredSession("a", "r", "later", "later", "owner"))
            val api = ApiFactory.api(ApiFactory.retrofit(server.url("/").toString(), ApiFactory.okHttp(ApiFactory.passThrough())))
            val repository = AvatarRepository(api, sessions)
            val operation = "db02d778-f923-4c1a-8edb-5a21b5386fd9"
            server.enqueue(MockResponse().setBody("""{"revision":1,"operationId":"$operation","imageId":"$operation"}"""))
            repository.replace("owner", operation, 0, byteArrayOf(1, 2, 3))
            val upload = server.takeRequest()
            assertEquals("PUT", upload.method); assertEquals("owner", upload.getHeader("X-Anve-Expected-Owner"))
            assertEquals(operation, upload.getHeader("X-Anve-Operation-ID")); assertEquals("0", upload.getHeader("X-Anve-Expected-Revision"))
            assertEquals("image/jpeg", upload.getHeader("Content-Type")); assertEquals(3, upload.body.size.toInt())
            server.enqueue(MockResponse().setBody("""{"revision":0}"""))
            assertEquals(0L, repository.state("owner").revision)
            assertEquals("GET", server.takeRequest().method)
            sessions.clear()
            assertTrue(runCatching { repository.state("owner") }.isFailure)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        } finally { server.shutdown() }
    }
}
