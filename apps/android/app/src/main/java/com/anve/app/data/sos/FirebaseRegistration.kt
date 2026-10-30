package com.anve.app.data.sos

import android.content.Context
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
import com.anve.app.data.api.PushTokenDto
import com.anve.app.data.api.orThrow
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Missing Firebase configuration disables registration instead of crashing CI. */
object FirebaseSetup {
    @Synchronized fun initialize(context: Context): Boolean {
        if (FirebaseApp.getApps(context).isNotEmpty()) return true
        if (listOf(BuildConfig.ANVE_FIREBASE_APP_ID, BuildConfig.ANVE_FIREBASE_PROJECT_ID,
                BuildConfig.ANVE_FIREBASE_API_KEY, BuildConfig.ANVE_FIREBASE_SENDER_ID).any { it.isBlank() }) return false
        return runCatching {
            val options = FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.ANVE_FIREBASE_APP_ID)
                .setProjectId(BuildConfig.ANVE_FIREBASE_PROJECT_ID)
                .setApiKey(BuildConfig.ANVE_FIREBASE_API_KEY)
                .setGcmSenderId(BuildConfig.ANVE_FIREBASE_SENDER_ID)
                .build()
            FirebaseApp.initializeApp(context.applicationContext, options)
            true
        }.getOrDefault(false)
    }
}

class PushRegistration(private val api: AnveApi) {
    suspend fun register(owner: String, token: String) { api.registerPushToken(owner, PushTokenDto(token)).orThrow() }
    suspend fun unregister(owner: String) { api.unregisterPushToken(owner).orThrow() }
}

class PushRegistrationScheduler(context: Context) {
    private val app = context.applicationContext
    fun schedule(owner: String) {
        val key = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString("") { "%02x".format(it) }
        val work = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
            .setInputData(workDataOf("owner" to owner))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(app).enqueueUniqueWork("push-register-$key", ExistingWorkPolicy.REPLACE, work)
    }
}

class PushRegistrationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val owner = inputData.getString("owner") ?: return Result.failure()
        val app = applicationContext as? AnVeApplication ?: return Result.failure()
        if (app.container.sessionStore.current()?.userId != owner) return Result.success()
        return try {
            if (!FirebaseSetup.initialize(applicationContext)) return Result.success()
            @Suppress("DEPRECATION")
            val token = withContext(Dispatchers.IO) { Tasks.await(FirebaseMessaging.getInstance().token, 15, TimeUnit.SECONDS) }
            if (app.container.sessionStore.current()?.userId != owner) return Result.success()
            app.container.pushRegistration.register(owner, token)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

/** Token values never leave the SDK callback through WorkManager input or logs. */
class AnveFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val app = applicationContext as? AnVeApplication ?: return
        app.container.applicationScope.launch {
            app.container.sessionStore.current()?.userId?.let { app.container.pushRegistrationScheduler.schedule(it) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val kind = message.data["kind"] ?: return
        val eventId = message.data["eventId"] ?: return
        PushSignalScheduler(applicationContext).schedule(kind, eventId)
    }
}
