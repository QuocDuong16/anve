package com.anve.app.data.location

import com.anve.app.data.api.ApiFactory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VisibleLocationRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: VisibleLocationRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = ApiFactory.api(
            ApiFactory.retrofit(
                server.url("/").toString(),
                ApiFactory.okHttp(ApiFactory.passThrough()),
            ),
        )
        repository = VisibleLocationRepository(
            api = api,
            nowMillis = { 1_000L },
            elapsedMillis = { 50_000L },
            pollIntervalMillis = 1L,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `snapshot is conditional and 304 preserves the authorized in-memory data`() = runBlocking {
        server.enqueue(
            MockResponse()
                .addHeader("ETag", "\"snapshot-1\"")
                .setBody(
                    """{"locations":[{"ownerId":"owner-1","displayName":"Mai","latitude":10.7,
                        "longitude":106.6,"horizontalAccuracyMeters":12,"recordedAt":"2026-10-27T10:00:00Z",
                        "receivedAt":"2026-10-27T10:00:02Z","ageMillis":60000,"confidence":"FRESH"}]}"""
                        .replace("\n", ""),
                ),
        )
        server.enqueue(MockResponse().setResponseCode(304).addHeader("ETag", "\"snapshot-1\""))

        val snapshots = withTimeout(3_000) {
            repository.observeLatest("viewer-1").take(3).toList()
        }

        assertTrue(snapshots.first().loading)
        assertEquals(listOf("owner-1"), snapshots[1].locations.map { it.ownerId })
        assertTrue(snapshots[1].connected)
        assertEquals(50_000L, snapshots[1].dataObservedElapsedMillis)
        assertEquals(snapshots[1].locations, snapshots[2].locations)
        assertTrue(snapshots[2].connected)
        assertEquals(50_000L, snapshots[2].dataObservedElapsedMillis)
        assertEquals("/api/v1/locations/visible/latest", server.takeRequest().path)
        val conditional = server.takeRequest()
        assertEquals("/api/v1/locations/visible/latest", conditional.path)
        assertEquals("\"snapshot-1\"", conditional.getHeader("If-None-Match"))
    }

    @Test
    fun `authentication failure clears cached protected coordinates`() = runBlocking {
        server.enqueue(
            MockResponse()
                .addHeader("ETag", "\"snapshot-1\"")
                .setBody(
                    """{"locations":[{"ownerId":"owner-1","displayName":"Mai","latitude":10.7,
                        "longitude":106.6,"horizontalAccuracyMeters":12,"recordedAt":"2026-10-27T10:00:00Z",
                        "receivedAt":"2026-10-27T10:00:02Z","ageMillis":60000,"confidence":"FRESH"}]}"""
                        .replace("\n", ""),
                ),
        )
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"code":"UNAUTHENTICATED","message":"expired"}"""),
        )

        val snapshots = withTimeout(3_000) {
            repository.observeLatest("viewer-1").take(3).toList()
        }

        assertEquals(1, snapshots[1].locations.size)
        assertFalse(snapshots[2].connected)
        assertTrue(snapshots[2].locations.isEmpty())
        assertTrue(snapshots[2].error.orEmpty().lowercase().contains("đăng nhập lại"))
        assertNull(snapshots[2].lastSuccessfulPollMillis)
    }

    @Test
    fun `temporary server failure retains last authorized point as disconnected`() = runBlocking {
        server.enqueue(
            MockResponse()
                .addHeader("ETag", "\"snapshot-1\"")
                .setBody(
                    """{"locations":[{"ownerId":"owner-1","displayName":"Mai","latitude":10.7,
                        "longitude":106.6,"horizontalAccuracyMeters":12,"recordedAt":"2026-10-27T10:00:00Z",
                        "receivedAt":"2026-10-27T10:00:02Z","ageMillis":60000,"confidence":"FRESH"}]}"""
                        .replace("\n", ""),
                ),
        )
        server.enqueue(
            MockResponse().setResponseCode(503)
                .setBody("""{"code":"SERVICE_UNAVAILABLE","message":"try later"}"""),
        )

        val snapshots = withTimeout(3_000) {
            repository.observeLatest("viewer-1").take(3).toList()
        }

        assertTrue(snapshots[2].locations.isNotEmpty())
        assertFalse(snapshots[2].connected)
        assertEquals(50_000L, snapshots[2].dataObservedElapsedMillis)
        assertEquals(1_000L, snapshots[2].lastSuccessfulPollMillis)
    }

    @Test
    fun `audience controls use owner scoped authenticated endpoints and server responses`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"owner-1","email":"owner@example.com","displayName":"Mai",
                    "avatarUrl":null,"locationSharingEnabled":true,"createdAt":"2026-10-27T00:00:00Z",
                    "locationSharingVersion":2}""".replace("\n", ""),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"circle":{"id":"circle-1","name":"Gia đình","createdBy":"owner-1",
                    "createdAt":"2026-10-27T00:00:00Z"},"membership":{"circleId":"circle-1",
                    "userId":"owner-1","role":"owner","joinedAt":"2026-10-27T00:00:00Z"}}"""
                    .replace("\n", ""),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"members":[{"userId":"owner-1","displayName":"Mai","role":"owner",
                    "joinedAt":"2026-10-27T00:00:00Z"},{"userId":"viewer-1","displayName":"Bố",
                    "role":"member","joinedAt":"2026-10-27T00:00:00Z"}]}""".replace("\n", ""),
            ),
        )
        server.enqueue(MockResponse().setBody("""{"grants":[]}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"id":"grant-1","viewerId":"viewer-1","displayName":"Bố","circleId":"circle-1",
                    "generation":1,"visibleFrom":"2026-10-27T00:00:00Z","createdAt":"2026-10-27T00:00:00Z"}"""
                    .replace("\n", ""),
            ),
        )
        server.enqueue(MockResponse().setResponseCode(204))

        val management = repository.sharingManagement()
        assertTrue(management.consentEnabled)
        assertTrue(management.members.single { it.userId == "viewer-1" }.canViewLocation.not())
        repository.setViewerGrant("owner-1", "viewer-1", true)
        repository.setViewerGrant("owner-1", "viewer-1", false)

        assertEquals("/api/v1/me", server.takeRequest().path)
        assertEquals("/api/v1/circles/mine", server.takeRequest().path)
        assertEquals("/api/v1/circles/circle-1/members", server.takeRequest().path)
        assertEquals("/api/v1/location-sharing/audience", server.takeRequest().path)
        val grant = server.takeRequest()
        assertEquals("PUT", grant.method)
        assertEquals("/api/v1/location-sharing/audience/viewer-1", grant.path)
        assertEquals("owner-1", grant.getHeader("X-Anve-Expected-Owner"))
        val revoke = server.takeRequest()
        assertEquals("DELETE", revoke.method)
        assertEquals("/api/v1/location-sharing/audience/viewer-1", revoke.path)
        assertEquals("owner-1", revoke.getHeader("X-Anve-Expected-Owner"))
    }
}
