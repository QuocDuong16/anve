package com.anve.app

import android.app.Application
import com.anve.app.data.api.ApiFactory
import com.anve.app.data.api.AnveApi
import com.anve.app.data.auth.AuthRepository
import com.anve.app.data.auth.DataStoreSessionStorage
import com.anve.app.data.auth.DeviceIdentity
import com.anve.app.data.auth.SessionInterceptor
import com.anve.app.data.circles.CircleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Interceptor

/**
 * Hand-wired dependency container. The app is small enough that a DI
 * framework would be ceremony; every dependency stays constructible and
 * replaceable in tests.
 */
class AppContainer(application: Application) {

    val deviceIdentity: DeviceIdentity = DeviceIdentity(application)

    val sessionStore: DataStoreSessionStorage = DataStoreSessionStorage(application)

    /** Token-free client: auth config, sign-in endpoints and refresh. */
    private val plainApi: AnveApi = ApiFactory.api(
        ApiFactory.retrofit(BuildConfig.API_BASE_URL, ApiFactory.okHttp(passThroughInterceptor)),
    )

    /** Session-aware client: bearer header, one refresh-retry on 401. */
    private val authApi: AnveApi = ApiFactory.api(
        ApiFactory.retrofit(
            BuildConfig.API_BASE_URL,
            ApiFactory.okHttp(SessionInterceptor(sessionStore, plainApi)),
        ),
    )

    val authRepository: AuthRepository = AuthRepository(
        api = authApi,
        plainApi = plainApi,
        storage = sessionStore,
        deviceIdentity = deviceIdentity,
    )

    val circleRepository: CircleRepository = CircleRepository(api = authApi)

    companion object {
        /** An interceptor that changes nothing — used by token-free calls. */
        val passThroughInterceptor = Interceptor { chain -> chain.proceed(chain.request()) }
    }
}

class AnVeApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Finishes a sign-out whose server revocation could not be delivered
        // (offline sign-out). Best-effort and bounded: the record expires
        // after PENDING_REVOCATION_TTL_MILLIS.
        applicationScope.launch {
            container.authRepository.retryPendingRevocation()
        }
    }
}
