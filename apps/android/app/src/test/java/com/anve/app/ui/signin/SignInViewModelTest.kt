package com.anve.app.ui.signin

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {

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

    @Test
    fun `loads the server auth config`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"googleSignIn":true,"devLogin":false}"""),
        )
        val vm = SignInViewModel(repository)
        awaitUntil { vm.state.value is SignInUiState.Ready && vm.error.value == null }

        val state = vm.state.value
        assertTrue(state is SignInUiState.Ready)
        state as SignInUiState.Ready
        assertTrue(state.googleSignIn)
        assertEquals(false, state.devLogin)
    }

    @Test
    fun `dev sign-in stores the session`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"googleSignIn":false,"devLogin":true}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"user":{"id":"u1","email":"dev@x.co","displayName":"Dev",
                   "locationSharingEnabled":false,"createdAt":"2026-10-25T00:00:00Z"},
                   "session":{"accessToken":"at","refreshToken":"rt",
                   "accessExpiresAt":"2026-10-25T00:30:00Z","refreshExpiresAt":"2026-11-24T00:00:00Z"}}""".replace("\n", ""),
            ),
        )
        val vm = SignInViewModel(repository)
        awaitUntil { vm.state.value is SignInUiState.Ready }

        vm.signInDev("dev@x.co")
        awaitUntil { vm.state.value is SignInUiState.SignedIn }

        val state = vm.state.value
        assertTrue("state was $state", state is SignInUiState.SignedIn)
        assertEquals("Dev", (state as SignInUiState.SignedIn).displayName)
        assertEquals("at", storage.current()?.accessToken)

        val config = server.takeRequest()
        val login = server.takeRequest()
        assertEquals("/api/v1/auth/config", config.path)
        assertEquals("/api/v1/auth/dev-login", login.path)
        val body = login.body.readUtf8()
        assertTrue(body.contains("dev@x.co"))
        assertTrue("the device registration travels with sign-in", body.contains("test-device-0001"))
    }

    @Test
    fun `server rejection surfaces a friendly error`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"googleSignIn":false,"devLogin":true}"""))
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"code":"UNAUTHENTICATED","message":"rejected"}""",
            ),
        )
        val vm = SignInViewModel(repository)
        awaitUntil { vm.state.value is SignInUiState.Ready }

        vm.signInDev("wrong@example.com")
        awaitUntil { vm.error.value != null }

        assertTrue(vm.state.value is SignInUiState.Ready)
        assertNotNull(vm.error.value)
        assertNull(storage.current())
    }

    @Test
    fun `unreachable server still shows the screen with an error`() = runBlocking {
        server.shutdown()
        val vm = SignInViewModel(repository)
        awaitUntil { vm.state.value is SignInUiState.Ready && vm.error.value != null }

        val state = vm.state.value
        assertTrue(state is SignInUiState.Ready)
        state as SignInUiState.Ready
        assertEquals(false, state.googleSignIn)
        assertNotNull(vm.error.value)
    }
}
