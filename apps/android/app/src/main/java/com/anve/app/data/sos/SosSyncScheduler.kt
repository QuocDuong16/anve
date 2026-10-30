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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

interface SosSyncScheduler { fun schedule(owner: String) }

/** Unique per-owner, network-constrained work; it never carries credentials. */
class WorkManagerSosSyncScheduler(context: Context) : SosSyncScheduler {
    private val app = context.applicationContext
    override fun schedule(owner: String) {
        val key = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString("") { "%02x".format(it) }
        val request = OneTimeWorkRequestBuilder<SosSyncWorker>()
            .setInputData(workDataOf("owner" to owner))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork("sos-sync-$key", ExistingWorkPolicy.KEEP, request)
    }
}

class SosSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val owner = inputData.getString("owner") ?: return Result.failure()
        val app = applicationContext as? AnVeApplication ?: return Result.failure()
        return try {
            if (app.container.sosSyncEngine.sync(owner)) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
