package com.anve.app.ui.circle

import com.anve.app.data.api.ApiFactory
import com.anve.app.data.circles.CircleRepository
import com.anve.app.ui.awaitUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CircleViewModel against the real repository + MockWebServer: setup
 * (create/join), member list rendering, invite creation and the Vietnamese
 * error mapping for the contract's 409 codes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CircleViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var repository: CircleRepository
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        server = MockWebServer()
        server.start()
        repository = CircleRepository(
            ApiFactory.api(
                ApiFactory.retrofit(
                    server.url("/").toString(),
                    ApiFactory.okHttp(ApiFactory.passThrough()),
                ),
            ),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun enqueueMyCircle(name: String? = "Gia đình", role: String = "owner") {
        val circleJson = if (name != null) {
            """{"id":"c1","name":"$name","createdBy":"u1","createdAt":"2026-10-25T00:00:00Z"}"""
        } else {
            "null"
        }
        val membershipJson = if (name != null) {
            """{"circleId":"c1","userId":"u2","role":"$role","joinedAt":"2026-10-25T00:00:00Z"}"""
        } else {
            "null"
        }
        server.enqueue(
            MockResponse().setBody("""{"circle":$circleJson,"membership":$membershipJson}"""),
        )
    }

    @Test
    fun `empty state shows the setup screen`() = runBlocking {
        enqueueMyCircle(name = null)

        val vm = CircleViewModel(repository, scope)
        awaitUntil { !vm.state.value.loading }

        assertNull(vm.state.value.circle)
    }

    @Test
    fun `active circle loads members and owner invites`() = runBlocking {
        enqueueMyCircle()
        server.enqueue(
            MockResponse().setBody(
                """{"members":[
                     {"userId":"u1","displayName":"Mai","role":"owner","joinedAt":"2026-10-25T00:00:00Z"},
                     {"userId":"u2","displayName":"Bố","role":"member","joinedAt":"2026-10-25T01:00:00Z"}]}""".replace("\n", ""),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"invites":[{"id":"i1","circleId":"c1","code":"ABCD234567","createdBy":"u1",
                     "expiresAt":"2026-10-27T00:00:00Z","createdAt":"2026-10-25T00:00:00Z"}]}""".replace("\n", ""),
            ),
        )

        val vm = CircleViewModel(repository, scope)
        awaitUntil { vm.state.value.members.isNotEmpty() && vm.state.value.invites.isNotEmpty() }

        assertEquals("owner", vm.state.value.myRole)
        assertTrue(vm.state.value.isOwner)
        assertEquals(2, vm.state.value.members.size)
        assertEquals("ABCD234567", vm.state.value.invites.first().code)
    }

    @Test
    fun `member does not request invites`() = runBlocking {
        enqueueMyCircle(role = "member")
        server.enqueue(MockResponse().setBody("""{"members":[]}"""))

        val vm = CircleViewModel(repository, scope)
        awaitUntil { server.requestCount >= 2 }

        assertEquals("only my-circle and members are called", 2, server.requestCount)
        assertEquals(false, vm.state.value.isOwner)
    }

    @Test
    fun `join maps INVITE_UNAVAILABLE to a friendly message`() = runBlocking {
        enqueueMyCircle(name = null)

        val vm = CircleViewModel(repository, scope)
        awaitUntil { !vm.state.value.loading }

        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"code":"INVITE_UNAVAILABLE","message":"used"}""",
            ),
        )
        vm.join("USEDCODE123")
        awaitUntil { vm.state.value.error != null }

        assertEquals(
            "Mã mời không tồn tại, đã hết hạn hoặc đã được dùng.",
            vm.state.value.error,
        )
        assertNull(vm.state.value.circle)
    }

    @Test
    fun `create sends the trimmed name`() = runBlocking {
        enqueueMyCircle(name = null)

        val vm = CircleViewModel(repository, scope)
        awaitUntil { !vm.state.value.loading }

        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"circle":{"id":"c9","name":"Nhà mình","createdBy":"u2","createdAt":"2026-10-25T00:00:00Z"},
                     "membership":{"circleId":"c9","userId":"u2","role":"owner","joinedAt":"2026-10-25T00:00:00Z"}}""".replace("\n", ""),
            ),
        )
        enqueueMyCircle()
        server.enqueue(MockResponse().setBody("""{"members":[]}"""))

        vm.create("  Nhà mình  ")
        awaitUntil { vm.state.value.circle != null }

        val initial = server.takeRequest()
        val create = server.takeRequest()
        assertEquals("/api/v1/circles/mine", initial.path)
        assertEquals("/api/v1/circles", create.path)
        assertTrue(create.body.readUtf8().contains("Nhà mình"))
        assertNotNull(vm.state.value.circle)
    }

    @Test
    fun `leave dissolves as owner`() = runBlocking {
        enqueueMyCircle()
        server.enqueue(MockResponse().setBody("""{"members":[]}"""))
        server.enqueue(MockResponse().setBody("""{"invites":[]}"""))

        val vm = CircleViewModel(repository, scope)
        awaitUntil { server.requestCount >= 3 }

        server.enqueue(MockResponse().setResponseCode(204))
        vm.leave(true)
        awaitUntil { vm.state.value.message != null }

        val myCircle = server.takeRequest()
        val members = server.takeRequest()
        val invites = server.takeRequest()
        val leave = server.takeRequest()
        assertEquals("/api/v1/circles/mine", myCircle.path)
        assertEquals("/api/v1/circles/c1/members", members.path)
        assertEquals("/api/v1/circles/c1/invites", invites.path)
        assertEquals("/api/v1/circles/c1/leave", leave.path)
        assertTrue(leave.body.readUtf8().contains("true"))
    }
}
