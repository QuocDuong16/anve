package com.anve.app.ui.home

import com.anve.app.data.api.AnveApi
import com.anve.app.data.api.ApiFactory
import com.anve.app.data.api.DeviceRegistrationDto
import com.anve.app.data.auth.AuthRepository
import com.anve.app.data.auth.DeviceRegistrationProvider
import com.anve.app.data.auth.InMemorySessionStorage
import com.anve.app.data.auth.SessionInterceptor
import com.anve.app.data.auth.SessionNoticeKind
import com.anve.app.data.auth.SessionNoticeStore
import com.anve.app.data.auth.StoredSession
import com.anve.app.tracking.ConsentController
import com.anve.app.tracking.ConsentResult
import com.anve.app.ui.awaitUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * HomeViewModel against a repository wired to MockWebServer: consent
 * toggling, rename, device revocation, sign-out and account deletion all go
 * through real HTTP + JSON parsing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var storage: InMemorySessionStorage
    private lateinit var notices: SessionNoticeStore
    private lateinit var repository: AuthRepository
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        server = MockWebServer()
        server.start()
        storage = InMemorySessionStorage()
        notices = SessionNoticeStore()
        repository = AuthRepository(
            api = api(),
            plainApi = api(),
            storage = storage,
            deviceIdentity = device(),
            notices = notices,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun viewModel(repo: AuthRepository = repository): HomeViewModel =
        HomeViewModel(repo, FakeConsentController(repo), scope)

    private fun device() = object : DeviceRegistrationProvider {
        override suspend fun registration() = DeviceRegistrationDto("test-device-0001")
    }

    private fun api() = ApiFactory.api(
        ApiFactory.retrofit(server.url("/").toString(), ApiFactory.okHttp(ApiFactory.passThrough())),
    )

    private fun signedIn() = runBlocking {
        storage.save(
            StoredSession(
                accessToken = "access-1",
                refreshToken = "refresh-1",
                accessExpiresAt = "2026-10-30T00:00:00Z",
                refreshExpiresAt = "2026-11-25T00:00:00Z",
                userId = "u1",
                locationSharingEnabled = false,
                locationSharingVersion = 0,
            ),
        )
    }

    private fun userJson(sharing: Boolean, name: String = "Mai", version: Int = 0) =
        """{"id":"u1","email":"a@b.co","displayName":"$name",
           "locationSharingEnabled":$sharing,"createdAt":"2026-10-25T00:00:00Z",
           "locationSharingVersion":$version}""".replace("\n", "")

    private fun enqueueHome(userJson: String = userJson(sharing = false)) {
        server.enqueue(MockResponse().setBody(userJson))
        server.enqueue(MockResponse().setBody("""{"devices":[]}"""))
    }

    @Test
    fun `refresh loads user and devices`() = runBlocking {
        server.enqueue(MockResponse().setBody(userJson(sharing = false)))
        server.enqueue(
            MockResponse().setBody("""{"devices":[{"id":"d1","name":"Pixel","platform":"android",
                "createdAt":"2026-10-25T00:00:00Z","lastSeenAt":"2026-10-25T00:00:00Z"}]}""".replace("\n", "")),
        )
        val vm = viewModel()
        awaitUntil { !vm.state.value.loading }

        assertEquals(false, vm.state.value.loading)
        assertEquals("Mai", vm.state.value.user?.displayName)
        assertTrue(vm.state.value.devices.isNotEmpty())
    }

    @Test
    fun `a failed refresh keeps the user and offers a retry`() = runBlocking {
        enqueueHome()
        val vm = viewModel()
        awaitUntil { !vm.state.value.loading }

        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"code":"INTERNAL","message":"x"}"""))
        vm.refresh()
        awaitUntil { !vm.state.value.loading && vm.state.value.error != null }

        assertEquals("screen data survives an API failure", "Mai", vm.state.value.user?.displayName)
        assertNotNull(vm.state.value.error)
    }

    @Test
    fun `consent toggle sends the request and applies the server state`() = runBlocking {
        enqueueHome()
        val vm = viewModel()
        awaitUntil { !vm.state.value.loading }
        signedIn()

        server.enqueue(MockResponse().setBody(userJson(sharing = true, version = 1)))
        vm.setLocationSharing(true)
        awaitUntil { !vm.state.value.consentBusy }
        awaitUntil { server.requestCount >= 3 }

        assertTrue(vm.state.value.user!!.locationSharingEnabled)
        val me = server.takeRequest()
        val devices = server.takeRequest()
        val consent = server.takeRequest()
        val body = consent.body.readUtf8()
        assertEquals("/api/v1/me", me.path)
        assertEquals("/api/v1/devices", devices.path)
        assertEquals("PUT", consent.method)
        assertEquals("/api/v1/me/location-sharing", consent.path)
        assertTrue(body.contains("\"enabled\":true"))
        assertTrue(body.contains("\"expectedVersion\":0"))
    }

    @Test
    fun `failed consent toggle rolls back`() = runBlocking {
        enqueueHome()
        val vm = viewModel()
        awaitUntil { !vm.state.value.loading }
        consumeInitRequests()
        signedIn()

        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"code":"SERVICE_UNAVAILABLE","message":"no db"}"""))
        vm.setLocationSharing(true)
        awaitUntil { vm.state.value.message != null }

        assertFalse(vm.state.value.user!!.locationSharingEnabled)
        assertTrue("the pending intent is shown separately from server confirmation", vm.state.value.locationSharingRequested!!)
        assertTrue(vm.state.value.consentSyncPending)
        assertTrue(
            "the UI must explain that the consent write was not confirmed",
            vm.state.value.message.orEmpty().contains("Không bật"),
        )
    }

    @Test
    fun `rapid consent toggles settle on the last server-confirmed state`() = runBlocking {
        enqueueHome()
        val vm = viewModel()
        awaitUntil { !vm.state.value.loading }
        consumeInitRequests()
        signedIn()

        // The app serializes local writes and the server uses consent CAS.
        server.enqueue(MockResponse().setBody(userJson(sharing = true, version = 1)))
        server.enqueue(MockResponse().setBody(userJson(sharing = false, version = 2)))
        server.enqueue(MockResponse().setBody(userJson(sharing = true, name = "Settled", version = 3)))
        vm.setLocationSharing(true)
        vm.setLocationSharing(false)
        vm.setLocationSharing(true)

        // The third answer carries a marker name: seeing it proves every
        // queued response has been applied.
        awaitUntil { vm.state.value.user?.displayName == "Settled" }

        assertTrue("the newest intent is confirmed", vm.state.value.user!!.locationSharingEnabled)
        assertTrue(vm.state.value.locationSharingRequested!!)
        val bodies = (1..3).map { server.takeRequest().body.readUtf8() }
        assertEquals(
            "requests reach the server in tap order",
            listOf(true, false, true),
            bodies.map { it.contains("\"enabled\":true") },
        )
    }

    @Test
    fun `a failure in the middle of a rapid sequence rolls back to the confirmed state`() = runBlocking {
        enqueueHome()
        val vm = viewModel()
        awaitUntil { !vm.state.value.loading }
        consumeInitRequests()
        signedIn()

        server.enqueue(MockResponse().setBody(userJson(sharing = true, version = 1)))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"code":"INTERNAL","message":"x"}"""))
        vm.setLocationSharing(true)
        vm.setLocationSharing(false)

        awaitUntil { vm.state.value.message != null }
        assertTrue("the server-confirmed snapshot stays ON", vm.state.value.user!!.locationSharingEnabled)
        assertFalse("the latest OFF remains the local requested state", vm.state.value.locationSharingRequested!!)
        assertTrue(vm.state.value.consentSyncPending)
    }

    /** Consumes the two requests the ViewModel init issues. */
    private fun consumeInitRequests() {
        server.takeRequest()
        server.takeRequest()
    }

    /** Mirrors the tracking coordinator's consent semantics over real HTTP. */
    private class FakeConsentController(
        private val repository: AuthRepository,
    ) : ConsentController {
        private var confirmed = false

        override suspend fun setConsent(enabled: Boolean): ConsentResult = try {
            val user = repository.setLocationSharing(enabled, expectedOwnerUserId = "u1")
            confirmed = user.locationSharingEnabled
            ConsentResult(
                enabled = user.locationSharingEnabled,
                serverConfirmed = true,
                user = user,
            )
        } catch (e: Exception) {
            ConsentResult(
                enabled = enabled,
                serverConfirmed = false,
                message = if (enabled) "Không bật được chia sẻ vị trí." else "Chưa xác nhận với máy chủ.",
            )
        }

        override suspend fun recordServerConsent(enabled: Boolean) {
            confirmed = enabled
        }
    }

    @Test
    fun `sign out clears the local session without a notice`() = runBlocking {
        enqueueHome()
        val vm = viewModel()
        awaitUntil { !vm.state.value.loading }
        signedIn()

        server.enqueue(MockResponse().setResponseCode(204))
        vm.signOut()
        awaitUntil { storage.current() == null }

        assertFalse(vm.state.value.busy)
        assertNull(notices.notice.value)
    }

    @Test
    fun `offline sign out clears locally and publishes the unconfirmed notice`() = runBlocking {
        enqueueHome()
        val vm = viewModel()
        awaitUntil { !vm.state.value.loading }
        signedIn()

        // Unreachable server: a connection failure, not an HTTP level.
        server.shutdown()
        vm.signOut()
        awaitUntil { storage.current() == null }

        assertEquals(1, storage.rawPendingRevocations().size)
        assertEquals(
            "the destination screen must be able to tell the user the server was not reached",
            SessionNoticeKind.LocalSignOutRetryScheduled,
            notices.notice.value?.kind,
        )
        assertNull(
            "the message must not live on the disposed Home screen",
            vm.state.value.message,
        )
    }

    @Test
    fun `delete success clears the session`() = runBlocking {
        enqueueHome()
        val vm = viewModel()
        awaitUntil { !vm.state.value.loading }
        signedIn()

        server.enqueue(MockResponse().setResponseCode(204))
        vm.deleteAccount()
        awaitUntil { storage.current() == null }

        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `delete refused by circle membership keeps the session and explains`() = runBlocking {
        enqueueHome()
        val vm = viewModel()
        awaitUntil { !vm.state.value.loading }
        signedIn()

        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"code":"CIRCLE_HAS_MEMBERS","message":"dissolve first"}"""),
        )
        vm.deleteAccount()
        awaitUntil { vm.state.value.error != null }

        assertNotNull("a refused deletion must not sign the user out", storage.current())
        assertTrue(vm.state.value.error.orEmpty().contains("giải tán nhóm"))
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `delete failure of any HTTP kind keeps the session`() = runBlocking {
        enqueueHome()
        val vm = viewModel()
        awaitUntil { !vm.state.value.loading }
        signedIn()

        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"code":"INTERNAL","message":"x"}"""))
        vm.deleteAccount()
        awaitUntil { vm.state.value.error != null }

        assertNotNull(storage.current())
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `unknown deletion outcome reconciles and signs out when the account is gone`() = runBlocking {
        // Production wiring: the 401-triggered refresh chain is what clears
        // the session once the server no longer knows the account. The
        // DELETE itself fails at the transport layer (lost request).
        val plain = api()
        val flakyDelete = Interceptor { chain ->
            if (chain.request().method == "DELETE") throw IOException("connection lost")
            chain.proceed(chain.request())
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(flakyDelete)
            .addInterceptor(SessionInterceptor(storage, plain))
            .build()
        val wired = AuthRepository(
            api = ApiFactory.api(ApiFactory.retrofit(server.url("/").toString(), client)),
            plainApi = plain,
            storage = storage,
            deviceIdentity = device(),
        )
        enqueueHome()
        val vm = viewModel(wired)
        awaitUntil { !vm.state.value.loading }
        signedIn()

        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"UNAUTHENTICATED","message":"x"}""")) // reconciling /me
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"UNAUTHENTICATED","message":"x"}""")) // refresh
        vm.deleteAccount()
        awaitUntil { storage.current() == null }

        assertNull("the account is gone: the local session must go too", storage.current())
    }

    @Test
    fun `double tap delete sends one request`() = runBlocking {
        enqueueHome()
        val vm = viewModel()
        awaitUntil { !vm.state.value.loading }
        signedIn()
        val before = server.requestCount

        server.enqueue(MockResponse().setResponseCode(204))
        vm.deleteAccount()
        vm.deleteAccount()
        awaitUntil { storage.current() == null }

        assertEquals(1, server.requestCount - before)
    }
}
