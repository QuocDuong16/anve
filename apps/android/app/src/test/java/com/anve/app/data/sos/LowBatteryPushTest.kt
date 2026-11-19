package com.anve.app.data.sos

import com.anve.app.data.api.ApiFactory
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LowBatteryPushTest {

    @Test
    fun `validPushSignal and pushDestination validate LOW_BATTERY kind, uuid, episode and owner`() {
        val id = UUID.randomUUID().toString()
        assertTrue(validPushSignal("LOW_BATTERY", id, episode = 0))
        assertFalse(validPushSignal("LOW_BATTERY", id, episode = 1))
        assertFalse(validPushSignal("LOW_BATTERY", "not-a-uuid", episode = 0))

        assertEquals(
            PushDestination("LOW_BATTERY", id),
            pushDestination("LOW_BATTERY", id, intentOwner = "viewer-1", currentOwner = "viewer-1"),
        )
        assertNull(pushDestination("LOW_BATTERY", id, intentOwner = "viewer-1", currentOwner = "viewer-2"))
    }

    @Test
    fun `resolveAuthorized matches saved place privately, distinguishes 5 percent vs 15 percent, and suppresses resolved alerts`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                val api = ApiFactory.api(
                    ApiFactory.retrofit(server.url("/").toString(), ApiFactory.okHttp(ApiFactory.passThrough())),
                )
                val repo = PushEventRepository(api)
                val alertId = UUID.randomUUID().toString()

                // 1. Inside saved place at 5% -> urgent with matched place name.
                server.enqueue(
                    MockResponse().setBody(
                        """{"id":"$alertId","ownerId":"u1","ownerName":"Bé Minh","scope":"SHARING","threshold":5,"batteryPercent":5,"recordedAt":"2026-11-19T07:00:00Z","receivedAt":"2026-11-19T07:00:01Z","recipientCount":1,"location":{"latitude":10.7769,"longitude":106.7009,"horizontalAccuracyMeters":15.0,"recordedAt":"2026-11-19T06:59:55Z"}}""",
                    ),
                )
                server.enqueue(
                    MockResponse().setBody(
                        """{"places":[{"id":"p1","ownerId":"u1","circleId":"c1","name":"Trường THCS","latitude":10.7769,"longitude":106.7009,"radiusMeters":150,"createdAt":"2026-11-01T00:00:00Z","updatedAt":"2026-11-01T00:00:00Z","sharedWithCircle":true}]}""",
                    ),
                )
                val details5 = repo.resolveAuthorized("viewer-1", "LOW_BATTERY", alertId)
                assertNotNull(details5)
                assertTrue(details5!!.urgent)
                assertTrue(details5.title.contains("Bé Minh"))
                assertTrue(details5.title.contains("5%"))
                assertTrue(details5.body.contains("tại Trường THCS"))
                assertTrue(details5.body.contains("mất kết nối do hết pin"))

                // 2. Nearby place (<2km) at 15% -> non-urgent with calm warning copy.
                server.enqueue(
                    MockResponse().setBody(
                        """{"id":"$alertId","ownerId":"u1","ownerName":"Bé Minh","scope":"JOURNEY","journeyId":"${UUID.randomUUID()}","threshold":15,"batteryPercent":15,"recordedAt":"2026-11-19T07:00:00Z","receivedAt":"2026-11-19T07:00:01Z","recipientCount":1,"location":{"latitude":10.7810,"longitude":106.7009,"horizontalAccuracyMeters":15.0,"recordedAt":"2026-11-19T06:59:55Z"}}""",
                    ),
                )
                server.enqueue(
                    MockResponse().setBody(
                        """{"places":[{"id":"p1","ownerId":"u1","circleId":"c1","name":"Nhà","latitude":10.7769,"longitude":106.7009,"radiusMeters":100,"createdAt":"2026-11-01T00:00:00Z","updatedAt":"2026-11-01T00:00:00Z","sharedWithCircle":true}]}""",
                    ),
                )
                val details15 = repo.resolveAuthorized("viewer-1", "LOW_BATTERY", alertId)
                assertNotNull(details15)
                assertFalse(details15!!.urgent)
                assertTrue(details15.body.contains("gần Nhà"))

                // 3. Coordinates without saved place -> calm saved-coordinates fallback; no coordinates -> no location phrase.
                server.enqueue(
                    MockResponse().setBody(
                        """{"id":"$alertId","ownerId":"u1","ownerName":"Bé Minh","scope":"SHARING","threshold":5,"batteryPercent":5,"recordedAt":"2026-11-19T07:00:00Z","receivedAt":"2026-11-19T07:00:01Z","recipientCount":1,"location":{"latitude":10.7769,"longitude":106.7009,"horizontalAccuracyMeters":15.0,"recordedAt":"2026-11-19T06:59:55Z"}}""",
                    ),
                )
                server.enqueue(MockResponse().setBody("""{"places":[]}"""))
                val noPlace = repo.resolveAuthorized("viewer-1", "LOW_BATTERY", alertId)
                assertTrue(noPlace!!.body.contains("tọa độ cuối"))

                server.enqueue(
                    MockResponse().setBody(
                        """{"id":"$alertId","ownerId":"u1","ownerName":"Bé Minh","scope":"SHARING","threshold":5,"batteryPercent":5,"recordedAt":"2026-11-19T07:00:00Z","receivedAt":"2026-11-19T07:00:01Z","recipientCount":1}""",
                    ),
                )
                val noLocation = repo.resolveAuthorized("viewer-1", "LOW_BATTERY", alertId)
                assertNotNull(noLocation)
                assertFalse(noLocation!!.body.contains("tọa độ cuối"))

                // 4. Already resolved alert or 404 -> suppressed.
                server.enqueue(
                    MockResponse().setBody(
                        """{"id":"$alertId","ownerId":"u1","ownerName":"Bé Minh","scope":"SHARING","threshold":5,"batteryPercent":5,"recordedAt":"2026-11-19T07:00:00Z","receivedAt":"2026-11-19T07:00:01Z","resolvedAt":"2026-11-19T07:10:00Z","recipientCount":1}""",
                    ),
                )
                assertNull(repo.resolveAuthorized("viewer-1", "LOW_BATTERY", alertId))

                server.enqueue(MockResponse().setResponseCode(404))
                assertNull(repo.resolveAuthorized("viewer-1", "LOW_BATTERY", alertId))
            } finally {
                server.shutdown()
            }
        }
}
