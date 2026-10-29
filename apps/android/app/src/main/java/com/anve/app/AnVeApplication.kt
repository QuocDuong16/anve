package com.anve.app

import android.app.Application
import android.os.PowerManager
import androidx.room.Room
import com.anve.app.data.api.AnveApi
import com.anve.app.data.api.ApiFactory
import com.anve.app.data.auth.AuthRepository
import com.anve.app.data.auth.ConsentMutationCoordinator
import com.anve.app.data.auth.DataStoreSessionStorage
import com.anve.app.data.auth.DeviceIdentity
import com.anve.app.data.auth.PendingRevocationScheduler
import com.anve.app.data.auth.SessionInterceptor
import com.anve.app.data.auth.SessionNoticeStore
import com.anve.app.data.auth.WorkManagerPendingRevocationScheduler
import com.anve.app.data.circles.CircleRepository
import com.anve.app.data.location.ApiLocationSyncGateway
import com.anve.app.data.location.CaptureGenerationStore
import com.anve.app.data.location.LocationDatabase
import com.anve.app.data.location.LocationQueueRepository
import com.anve.app.data.location.LocationSyncEngine
import com.anve.app.data.location.LocationSyncScheduler
import com.anve.app.data.location.VisibleLocationRepository
import com.anve.app.data.location.WorkManagerLocationSyncScheduler
import com.anve.app.tracking.AndroidConnectivityMonitor
import com.anve.app.tracking.AndroidLocationPermissions
import com.anve.app.tracking.AndroidTrackingServiceController
import com.anve.app.tracking.AppTrackingServerGateway
import com.anve.app.tracking.DataStoreTrackingMetricsStore
import com.anve.app.tracking.DataStoreTrackingStore
import com.anve.app.tracking.FusedLocationProvider
import com.anve.app.tracking.TrackingManager
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

    /** App-scoped work that must outlive any screen. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val deviceIdentity: DeviceIdentity = DeviceIdentity(application)

    val sessionStore: DataStoreSessionStorage = DataStoreSessionStorage(application)

    /** One process-wide generation source for UI, tracking and retry paths. */
    val consentMutationCoordinator = ConsentMutationCoordinator()

    /**
     * App-scoped so a sign-out notice survives the navigation caused by
     * clearing the session, and survives configuration changes.
     */
    val sessionNotices: SessionNoticeStore = SessionNoticeStore()

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

    /**
     * Durable scheduling for undelivered sign-outs. It is app-scoped and
     * independent of any Activity or foreground service: retrying a logout
     * must never require GPS.
     */
    val pendingRevocationScheduler: PendingRevocationScheduler =
        WorkManagerPendingRevocationScheduler(application)

    val authRepository: AuthRepository = AuthRepository(
        api = authApi,
        plainApi = plainApi,
        storage = sessionStore,
        deviceIdentity = deviceIdentity,
        notices = sessionNotices,
        retryScheduler = pendingRevocationScheduler,
        consentMutations = consentMutationCoordinator,
    )

    val circleRepository: CircleRepository = CircleRepository(api = authApi)

    val historyRepository = com.anve.app.data.location.HistoryRepository(authApi)

    /** Location read API calls are screen-scoped; this repository stores no coordinates. */
    val visibleLocationRepository: VisibleLocationRepository = VisibleLocationRepository(api = authApi)

    val placesRepository = com.anve.app.data.location.PlacesRepository(authApi, visibleLocationRepository)

    // ── M3 location engine ───────────────────────────────────────────────

    /** Durable offline queue. No destructive migration: captured points survive updates. */
    private val locationDatabase: LocationDatabase = Room.databaseBuilder(
        application,
        LocationDatabase::class.java,
        "anve_locations.db",
    ).addMigrations(LocationDatabase.MIGRATION_1_2).build()

    private val captureGenerations = CaptureGenerationStore()

    val locationQueue: LocationQueueRepository = LocationQueueRepository(
        dao = locationDatabase.locationQueueDao(),
        captureGeneration = captureGenerations::current,
    )

    val trackingStore: DataStoreTrackingStore = DataStoreTrackingStore(application)

    private val trackingMetrics = DataStoreTrackingMetricsStore(application)

    val locationSyncScheduler: LocationSyncScheduler =
        WorkManagerLocationSyncScheduler(application)

    private val powerManager: PowerManager? = application.getSystemService(PowerManager::class.java)

    /**
     * App-scoped coordinator: location callbacks must not depend on a
     * screen's lifecycle, and the tracking status must be observable from
     * anywhere.
     */
    val trackingManager: TrackingManager = TrackingManager(
        server = AppTrackingServerGateway(authRepository, circleRepository),
        sessions = sessionStore,
        deviceIdentity = deviceIdentity,
        queue = locationQueue,
        store = trackingStore,
        permissions = AndroidLocationPermissions(application),
        provider = FusedLocationProvider(application),
        connectivity = AndroidConnectivityMonitor(application),
        service = AndroidTrackingServiceController(application),
        syncScheduler = locationSyncScheduler,
        scope = applicationScope,
        metrics = trackingMetrics,
        batterySaver = { powerManager?.isPowerSaveMode == true },
        consentMutations = consentMutationCoordinator,
        pendingConsentScheduler = pendingRevocationScheduler,
    )

    val locationSyncEngine: LocationSyncEngine = LocationSyncEngine(
        gateway = ApiLocationSyncGateway(authApi),
        queue = locationQueue,
        store = trackingStore,
        sessions = sessionStore,
        onPolicyRefusal = { code -> trackingManager.onServerPolicyRefusal(code) },
        metrics = trackingMetrics,
        consentMutations = consentMutationCoordinator,
        expectedClientDeviceId = { deviceIdentity.registration().clientDeviceId },
        onCaptureGeneration = captureGenerations::advance,
    )

    companion object {
        /** An interceptor that changes nothing — used by token-free calls. */
        val passThroughInterceptor = Interceptor { chain -> chain.proceed(chain.request()) }
    }
}

class AnVeApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val applicationScope: CoroutineScope
        get() = container.applicationScope

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Finishes every sign-out whose server revocation could not be
        // delivered (offline sign-out). Best-effort and bounded: each queue
        // record expires independently after PENDING_REVOCATION_TTL_MILLIS.
        // Anything still undelivered keeps a network-constrained WorkManager
        // retry scheduled, so recovery does not wait for the next app launch.
        applicationScope.launch {
            if (container.authRepository.retryPendingRevocations()) {
                container.pendingRevocationScheduler.ensureScheduled()
            }
        }
        // A queue left by a previous process continues to drain without
        // waiting for the user to open the tracking screen. An empty queue
        // makes the unique worker exit immediately.
        applicationScope.launch {
            try {
                val local = container.trackingStore.current()
                val consentOperations = container.sessionStore.pendingConsentOperations()
                if (consentOperations.isNotEmpty()) container.pendingRevocationScheduler.ensureScheduled()
                if (container.locationQueue.countAll() > 0 || local.pendingConsentRevocation) {
                    container.locationSyncScheduler.ensureScheduled()
                }
            } catch (_: Exception) {
                // Storage being unavailable must not crash startup; the next
                // enqueue schedules the worker again.
            }
        }
    }
}
