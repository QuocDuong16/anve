package com.anve.app.data.location

import com.anve.app.data.api.ApiFactory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
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
        val initial = server.takeRequest()
        assertEquals("/api/v1/locations/visible/latest", initial.path)
        assertEquals("viewer-1", initial.getHeader("X-Anve-Expected-Owner"))
        val conditional = server.takeRequest()
        assertEquals("/api/v1/locations/visible/latest", conditional.path)
        assertEquals("\"snapshot-1\"", conditional.getHeader("If-None-Match"))
        assertEquals("viewer-1", conditional.getHeader("X-Anve-Expected-Owner"))
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
        enqueueSharingSnapshot(granted = true)
        server.enqueue(MockResponse().setResponseCode(204))
        enqueueSharingSnapshot(granted = false)

        val management = repository.sharingManagement("owner-1")
        assertTrue(management.consentEnabled)
        assertTrue(management.members.single { it.userId == "viewer-1" }.canViewLocation.not())
        assertTrue(repository.setViewerGrant("owner-1", "viewer-1", true) is AudienceMutationOutcome.Confirmed)
        assertTrue(repository.setViewerGrant("owner-1", "viewer-1", false) is AudienceMutationOutcome.Confirmed)

        listOf(
            "/api/v1/me",
            "/api/v1/circles/mine",
            "/api/v1/circles/circle-1/members",
            "/api/v1/location-sharing/audience",
        ).forEach { path ->
            val request = server.takeRequest()
            assertEquals(path, request.path)
            assertEquals("owner-1", request.getHeader("X-Anve-Expected-Owner"))
        }
        val grant = server.takeRequest()
        assertEquals("PUT", grant.method)
        assertEquals("/api/v1/location-sharing/audience/viewer-1", grant.path)
        assertEquals("owner-1", grant.getHeader("X-Anve-Expected-Owner"))
        repeat(4) {
            assertEquals("owner-1", server.takeRequest().getHeader("X-Anve-Expected-Owner"))
        }
        val revoke = server.takeRequest()
        assertEquals("DELETE", revoke.method)
        assertEquals("/api/v1/location-sharing/audience/viewer-1", revoke.path)
        assertEquals("owner-1", revoke.getHeader("X-Anve-Expected-Owner"))
        repeat(4) {
            assertEquals("owner-1", server.takeRequest().getHeader("X-Anve-Expected-Owner"))
        }
    }

    @Test
    fun `grant committed but response lost reconciles as confirmed without replay`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        enqueueSharingSnapshot(granted = true)

        val outcome = repository.setViewerGrant("owner-1", "viewer-1", true)

        assertTrue(outcome is AudienceMutationOutcome.Confirmed)
        assertEquals(5, server.requestCount)
        assertEquals("PUT", server.takeRequest().method)
        repeat(4) {
            assertEquals("owner-1", server.takeRequest().getHeader("X-Anve-Expected-Owner"))
        }
    }

    @Test
    fun `revoke committed but response lost reconciles as confirmed without replay`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        enqueueSharingSnapshot(granted = false)

        val outcome = repository.setViewerGrant("owner-1", "viewer-1", false)

        assertTrue(outcome is AudienceMutationOutcome.Confirmed)
        assertEquals(5, server.requestCount)
        assertEquals("DELETE", server.takeRequest().method)
        repeat(4) {
            assertEquals("owner-1", server.takeRequest().getHeader("X-Anve-Expected-Owner"))
        }
    }

    @Test
    fun `clear client rejection is rejected and current audience is reconciled`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"code":"FORBIDDEN","message":"not allowed"}"""),
        )
        enqueueSharingSnapshot(granted = false)

        val outcome = repository.setViewerGrant("owner-1", "viewer-1", true)

        assertTrue(outcome is AudienceMutationOutcome.Rejected)
        assertEquals(5, server.requestCount)
        assertEquals("PUT", server.takeRequest().method)
        repeat(4) { server.takeRequest() }
    }

    @Test
    fun `mutation timeout and failed reconciliation remains unknown without retry`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(
            MockResponse().setResponseCode(503)
                .setBody("""{"code":"SERVICE_UNAVAILABLE","message":"offline"}"""),
        )

        val outcome = repository.setViewerGrant("owner-1", "viewer-1", false)

        assertTrue(outcome is AudienceMutationOutcome.UnknownOutcome)
        assertEquals(2, server.requestCount)
        assertEquals("DELETE", server.takeRequest().method)
        val reconciliation = server.takeRequest()
        assertEquals("/api/v1/me", reconciliation.path)
        assertEquals("owner-1", reconciliation.getHeader("X-Anve-Expected-Owner"))
    }

    @Test
    fun `account switch during reconciliation is not read as the previous owner`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(
            MockResponse().setBody(
                """{"id":"owner-2","email":"other@example.com","displayName":"Other",
                    "avatarUrl":null,"locationSharingEnabled":true,"createdAt":"2026-10-27T00:00:00Z"}"""
                    .replace("\n", ""),
            ),
        )

        val outcome = repository.setViewerGrant("owner-1", "viewer-1", true)

        assertTrue(outcome is AudienceMutationOutcome.Confirmed)
        assertNull((outcome as AudienceMutationOutcome.Confirmed).snapshot)
        assertEquals(2, server.requestCount)
        val mutation = server.takeRequest()
        assertEquals("PUT", mutation.method)
        assertEquals("owner-1", mutation.getHeader("X-Anve-Expected-Owner"))
        val reconciliation = server.takeRequest()
        assertEquals("/api/v1/me", reconciliation.path)
        assertEquals("owner-1", reconciliation.getHeader("X-Anve-Expected-Owner"))
    }

    private fun enqueueSharingSnapshot(granted: Boolean) {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"owner-1","email":"owner@example.com","displayName":"Mai",
                    "avatarUrl":null,"locationSharingEnabled":true,"createdAt":"2026-10-27T00:00:00Z"}"""
                    .replace("\n", ""),
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
        val grants = if (granted) {
            """{"grants":[{"id":"grant-1","viewerId":"viewer-1","displayName":"Bố",
                "circleId":"circle-1","generation":1,"visibleFrom":"2026-10-27T00:00:00Z",
                "createdAt":"2026-10-27T00:00:00Z"}]}""".replace("\n", "")
        } else {
            """{"grants":[]}"""
        }
        server.enqueue(MockResponse().setBody(grants))
    }
}
