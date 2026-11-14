package com.anve.app.ui.signin

import androidx.credentials.exceptions.GetCredentialCancellationException
import com.anve.app.R
import com.anve.app.data.api.ApiException
import com.anve.app.data.api.ApiFactory
import com.anve.app.data.api.DeviceRegistrationDto
import com.anve.app.data.auth.AuthRepository
import com.anve.app.data.auth.DeviceRegistrationProvider
import com.anve.app.data.auth.InMemorySessionStorage
import com.anve.app.ui.awaitUntil
import kotlinx.coroutines.CompletableDeferred
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

@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var storage: InMemorySessionStorage
    private lateinit var repository: AuthRepository
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + dispatcher)
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
        scope.cancel()
        server.shutdown()
    }

    private fun api() = ApiFactory.api(
        ApiFactory.retrofit(server.url("/").toString(), ApiFactory.okHttp(ApiFactory.passThrough())),
    )

    @Test
    fun `provider cancellation stays silent when classified as cancel and never reaches the API`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"googleSignIn":true,"devLogin":false}"""))
        val vm = SignInViewModel(repository, scope)
        awaitUntil { vm.state.value is SignInUiState.Ready }
        val pending = CompletableDeferred<Result<String>>()
        var attempts = 0
        // The screen classifies GetCredentialCancellationException as the
        // user's own dismiss (null = nothing surfaced); the VM must honor
        // that classification instead of forcing an error card.
        vm.signInWithGoogle({ attempts++; pending.await() }, { null })
        assertTrue(vm.busy.value)
        vm.signInWithGoogle({ attempts++; Result.success("duplicate") }, { R.string.signin_generic_error })
        assertEquals(1, attempts)
        pending.complete(Result.failure(GetCredentialCancellationException("provider stopped")))
        awaitUntil { !vm.busy.value }
        assertNull(vm.error.value?.id)
        assertTrue(vm.state.value is SignInUiState.Ready)
        assertEquals(1, server.requestCount)
        assertNull(storage.current())
    }

    @Test
    fun `Google token exchange rejection is visible and permits another attempt`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"googleSignIn":true,"devLogin":false}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"UNAUTHENTICATED","message":"rejected"}"""))
        val vm = SignInViewModel(repository, scope)
        awaitUntil { vm.state.value is SignInUiState.Ready }
        vm.signInWithGoogleToken("test-only-id-token")
        awaitUntil { vm.error.value != null && !vm.busy.value }
        assertEquals(R.string.signin_unauthenticated, vm.error.value?.id)
        assertNull(storage.current())
        server.takeRequest()
        assertEquals("/api/v1/auth/google", server.takeRequest().path)
        vm.signInWithGoogle({ Result.failure(IllegalStateException("provider failed")) }, { R.string.signin_retry })
        awaitUntil { vm.error.value?.id == R.string.signin_retry && !vm.busy.value }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `loads the server auth config`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"googleSignIn":true,"devLogin":false}"""),
        )
        val vm = SignInViewModel(repository, scope)
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
        val vm = SignInViewModel(repository, scope)
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
        val vm = SignInViewModel(repository, scope)
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
        val vm = SignInViewModel(repository, scope)
        awaitUntil { vm.state.value is SignInUiState.Ready && vm.error.value != null }

        val state = vm.state.value
        assertTrue(state is SignInUiState.Ready)
        state as SignInUiState.Ready
        assertEquals(false, state.googleSignIn)
        assertNotNull(vm.error.value)
    }

    @Test
    fun `unknown server error hides the raw server detail`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"googleSignIn":false,"devLogin":false}"""))
        val vm = SignInViewModel(repository, scope)
        awaitUntil { vm.state.value is SignInUiState.Ready }

        val message = vm.friendly(ApiException("SOMETHING_ELSE", "pg: chi tiết kỹ thuật", null))
        assertEquals(R.string.signin_server_unexpected, message.id)
        assertTrue("release copy must not embed the server message", message.args.isEmpty())
    }

    @Test
    fun `dev sign-in keeps the raw server detail`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"googleSignIn":false,"devLogin":false}"""))
        val vm = SignInViewModel(repository, scope)
        awaitUntil { vm.state.value is SignInUiState.Ready }

        val message = vm.friendly(
            ApiException("SOMETHING_ELSE", "pg: chi tiết kỹ thuật", null),
            keepServerDetail = true,
        )
        assertEquals(R.string.signin_server_error, message.id)
        assertEquals(listOf("pg: chi tiết kỹ thuật"), message.args)
    }
}
