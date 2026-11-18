package com.anve.app.data.sos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.anve.app.AnVeApplication
import com.anve.app.BuildConfig
import com.anve.app.data.api.AnveApi
import com.anve.app.data.api.ApiException
import com.anve.app.data.api.PushTokenDto
import com.anve.app.data.api.orThrow
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Checks both OS channel/app toggle and Android 13+ runtime permission. */
fun areAppNotificationsEnabled(context: Context): Boolean {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

/** Missing Firebase configuration disables registration and analytics instead of crashing CI. */
object FirebaseSetup {
    fun isConfigured(): Boolean = listOf(
        BuildConfig.ANVE_FIREBASE_APP_ID,
        BuildConfig.ANVE_FIREBASE_PROJECT_ID,
        BuildConfig.ANVE_FIREBASE_API_KEY,
        BuildConfig.ANVE_FIREBASE_SENDER_ID,
    ).none { it.isBlank() }

    @Synchronized fun initialize(context: Context): Boolean {
        val appContext = context.applicationContext
        if (!isConfigured()) return false
        if (FirebaseApp.getApps(appContext).isNotEmpty()) return true
        return runCatching {
            val options = FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.ANVE_FIREBASE_APP_ID)
                .setProjectId(BuildConfig.ANVE_FIREBASE_PROJECT_ID)
                .setApiKey(BuildConfig.ANVE_FIREBASE_API_KEY)
                .setGcmSenderId(BuildConfig.ANVE_FIREBASE_SENDER_ID)
                .build()
            FirebaseApp.initializeApp(appContext, options)
            FirebaseAnalytics.getInstance(appContext).setAnalyticsCollectionEnabled(true)
            true
        }.getOrDefault(false)
    }
}

class PushRegistration(private val api: AnveApi) {
    suspend fun register(owner: String, token: String) { api.registerPushToken(owner, PushTokenDto(token)).orThrow() }
    suspend fun unregister(owner: String) { api.unregisterPushToken(owner).orThrow() }
}

interface PushTokenWorkScheduler {
    fun scheduleRegister(owner: String, replace: Boolean = false)
    fun scheduleUnregister(owner: String)
}

/**
 * Coordinates automatic, owner-scoped FCM token synchronization whenever an
 * authenticated account is active and OS notifications are allowed.
 */
class PushRegistrationCoordinator(
    private val notificationsEnabled: () -> Boolean,
    private val isFirebaseConfigured: () -> Boolean,
    private val scheduler: PushTokenWorkScheduler,
) {
    private var activeOwner: String? = null
    private var scheduledForOwner: String? = null
    private var registeredForOwner: String? = null

    @Synchronized fun onSessionChanged(owner: String?) {
        if (owner == null) {
            activeOwner = null
            scheduledForOwner = null
            registeredForOwner = null
            return
        }
        if (activeOwner != owner) {
            activeOwner = owner
            scheduledForOwner = null
            registeredForOwner = null
        }
        syncIfPermitted(owner)
    }

    @Synchronized fun syncIfPermitted(owner: String, force: Boolean = false): Boolean {
        if (activeOwner != owner) {
            activeOwner = owner
            scheduledForOwner = null
            registeredForOwner = null
        }
        if (!notificationsEnabled()) {
            val shouldUnregister = registeredForOwner == owner
            scheduledForOwner = null
            registeredForOwner = null
            if (shouldUnregister) {
                scheduler.scheduleUnregister(owner)
            }
            return false
        }
        if (!isFirebaseConfigured()) return false
        if (force || scheduledForOwner != owner) {
            scheduledForOwner = owner
            scheduler.scheduleRegister(owner, replace = force)
            return true
        }
        return false
    }

    @Synchronized fun onPermissionGranted(owner: String) {
        syncIfPermitted(owner, force = true)
    }

    @Synchronized fun onNewToken(owner: String) {
        if (registeredForOwner == owner) {
            registeredForOwner = null
        }
        syncIfPermitted(owner, force = true)
    }

    @Synchronized fun onWorkerSucceeded(owner: String, registered: Boolean) {
        if (activeOwner == null || activeOwner == owner) {
            activeOwner = owner
            if (registered) {
                scheduledForOwner = owner
                registeredForOwner = owner
            } else {
                scheduledForOwner = null
                registeredForOwner = null
            }
        }
    }

    @Synchronized fun onWorkerPermanentFailure(owner: String) {
        if (scheduledForOwner == owner) {
            scheduledForOwner = null
        }
        if (registeredForOwner == owner) {
            registeredForOwner = null
        }
    }
}

class PushRegistrationScheduler(context: Context) : PushTokenWorkScheduler {
    private val app = context.applicationContext

    fun schedule(owner: String) {
        scheduleRegister(owner, replace = true)
    }

    override fun scheduleRegister(owner: String, replace: Boolean) {
        enqueue(owner, ACTION_REGISTER, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP)
    }

    override fun scheduleUnregister(owner: String) {
        enqueue(owner, ACTION_UNREGISTER, ExistingWorkPolicy.REPLACE)
    }

    private fun enqueue(owner: String, action: String, policy: ExistingWorkPolicy) {
        val key = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString("") { "%02x".format(it) }
        val work = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
            .setInputData(workDataOf(KEY_OWNER to owner, KEY_ACTION to action))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(app).enqueueUniqueWork("push-register-$key", policy, work)
    }

    companion object {
        const val KEY_OWNER = "owner"
        const val KEY_ACTION = "action"
        const val ACTION_REGISTER = "register"
        const val ACTION_UNREGISTER = "unregister"
    }
}

class PushRegistrationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val owner = inputData.getString(PushRegistrationScheduler.KEY_OWNER) ?: return Result.failure()
        val action = inputData.getString(PushRegistrationScheduler.KEY_ACTION) ?: PushRegistrationScheduler.ACTION_REGISTER
        val app = applicationContext as? AnVeApplication ?: return Result.failure()
        if (app.container.sessionStore.current()?.userId != owner) return Result.success()
        if (action == PushRegistrationScheduler.ACTION_UNREGISTER) {
            if (areAppNotificationsEnabled(applicationContext)) return Result.success()
            return executeTokenMutation(app, owner, register = false) {
                app.container.pushRegistration.unregister(owner)
                true
            }
        }
        if (!areAppNotificationsEnabled(applicationContext)) {
            app.container.pushRegistrationCoordinator.onWorkerPermanentFailure(owner)
            return Result.success()
        }
        return executeTokenMutation(app, owner, register = true) {
            if (!FirebaseSetup.initialize(applicationContext)) {
                app.container.pushRegistrationCoordinator.onWorkerPermanentFailure(owner)
                return@executeTokenMutation false
            }
            @Suppress("DEPRECATION")
            val token = withContext(Dispatchers.IO) { Tasks.await(FirebaseMessaging.getInstance().token, 15, TimeUnit.SECONDS) }
            if (app.container.sessionStore.current()?.userId != owner) return@executeTokenMutation false
            if (!areAppNotificationsEnabled(applicationContext)) {
                app.container.pushRegistrationCoordinator.onWorkerPermanentFailure(owner)
                return@executeTokenMutation false
            }
            app.container.pushRegistration.register(owner, token)
            true
        }
    }

    private suspend fun executeTokenMutation(
        app: AnVeApplication,
        owner: String,
        register: Boolean,
        block: suspend () -> Boolean,
    ): Result = try {
        val completed = block()
        if (completed) {
            app.container.pushRegistrationCoordinator.onWorkerSucceeded(owner, registered = register)
        }
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        when (e.httpStatus) {
            400, 401, 403, 404 -> {
                app.container.pushRegistrationCoordinator.onWorkerPermanentFailure(owner)
                Result.failure()
            }
            409 -> {
                if (runAttemptCount < MAX_CONFLICT_RETRIES) {
                    Result.retry()
                } else {
                    app.container.pushRegistrationCoordinator.onWorkerPermanentFailure(owner)
                    Result.failure()
                }
            }
            else -> Result.retry()
        }
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        private const val MAX_CONFLICT_RETRIES = 5
    }
}

/** Token values never leave the SDK callback through WorkManager input or logs. */
class AnveFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val app = applicationContext as? AnVeApplication ?: return
        app.container.applicationScope.launch {
            app.container.sessionStore.current()?.userId?.let {
                app.container.pushRegistrationCoordinator.onNewToken(it)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val kind = message.data["kind"] ?: return
        val eventId = message.data["eventId"] ?: return
        val episode = message.data["episode"]?.toIntOrNull() ?: if ("episode" in message.data) return else 0
        PushSignalScheduler(applicationContext).schedule(kind, eventId, episode)
    }
}
