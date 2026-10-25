package com.anve.app.ui.home

import com.anve.app.data.api.ApiFactory
import com.anve.app.data.api.DeviceRegistrationDto
import com.anve.app.data.auth.AuthRepository
import com.anve.app.data.auth.DeviceRegistrationProvider
import com.anve.app.data.auth.InMemorySessionStorage
import com.anve.app.ui.awaitUntil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * HomeViewModel against a repository wired to MockWebServer: consent
 * toggling, rename, device revocation and sign-out all go through real
 * HTTP + JSON parsing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var storage: InMemorySessionStorage
    private lateinit var repository: AuthRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        server = MockWebServer()
        server.start()
        storage = InMemorySessionStorage()
        val device = object : DeviceRegistrationProvider {
            override suspend fun registration() = DeviceRegistrationDto("test-device-0001")
        }
        repository = AuthRepository(
            api = api(),
            plainApi = api(),
            storage = storage,
            deviceIdentity = device,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun api() = ApiFactory.api(
        ApiFactory.retrofit(server.url("/").toString(), ApiFactory.okHttp(ApiFactory.passThrough())),
    )

    private fun userJson(sharing: Boolean) =
        """{"id":"u1","email":"a@b.co","displayName":"Mai",
           "locationSharingEnabled":$sharing,"createdAt":"2026-10-25T00:00:00Z"}""".replace("\n", "")

    @Test
    fun `refresh loads user and devices`() = runBlocking {
        server.enqueue(MockResponse().setBody(userJson(sharing = false)))
        server.enqueue(
            MockResponse().setBody("""{"devices":[{"id":"d1","name":"Pixel","platform":"android",
                "createdAt":"2026-10-25T00:00:00Z","lastSeenAt":"2026-10-25T00:00:00Z"}]}""".replace("\n", "")),
        )

        val vm = HomeViewModel(repository)
        awaitUntil { !vm.state.value.loading && vm.state.value.devices.isNotEmpty() }

        assertEquals(false, vm.state.value.loading)
        assertEquals("Mai", vm.state.value.user?.displayName)
        assertEquals(1, vm.state.value.devices.size)
    }

    @Test
    fun `consent toggle sends the request and applies the server state`() = runBlocking {
        server.enqueue(MockResponse().setBody(userJson(sharing = false)))
        server.enqueue(MockResponse().setBody("""{"devices":[]}"""))
        val vm = HomeViewModel(repository)
        awaitUntil { !vm.state.value.loading }

        server.enqueue(MockResponse().setBody(userJson(sharing = true)))
        vm.setLocationSharing(true)
        awaitUntil { vm.state.value.user?.locationSharingEnabled == true }

        assertTrue(vm.state.value.user!!.locationSharingEnabled)
        val me = server.takeRequest()
        val devices = server.takeRequest()
        val consent = server.takeRequest()
        assertEquals("/api/v1/me", me.path)
        assertEquals("/api/v1/devices", devices.path)
        assertEquals("PUT", consent.method)
        assertEquals("/api/v1/me/location-sharing", consent.path)
        assertTrue(consent.body.readUtf8().contains("true"))
    }

    @Test
    fun `failed consent toggle rolls back`() = runBlocking {
        server.enqueue(MockResponse().setBody(userJson(sharing = false)))
        server.enqueue(MockResponse().setBody("""{"devices":[]}"""))
        val vm = HomeViewModel(repository)
        awaitUntil { !vm.state.value.loading }

        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"code":"SERVICE_UNAVAILABLE","message":"no db"}"""))
        vm.setLocationSharing(true)
        awaitUntil { vm.state.value.error != null }

        assertFalse(vm.state.value.user!!.locationSharingEnabled)
        assertNotNull(vm.state.value.error)
    }

    @Test
    fun `sign out clears the local session`() = runBlocking {
        server.enqueue(MockResponse().setBody(userJson(sharing = false)))
        server.enqueue(MockResponse().setBody("""{"devices":[]}"""))
        val vm = HomeViewModel(repository)
        awaitUntil { !vm.state.value.loading }

        server.enqueue(MockResponse().setResponseCode(204))
        var signedOut = false
        vm.signOut { signedOut = true }
        awaitUntil { signedOut }

        assertTrue(signedOut)
        assertEquals(null, storage.current())
        assertTrue(vm.state.value.signedOut)
    }
}
