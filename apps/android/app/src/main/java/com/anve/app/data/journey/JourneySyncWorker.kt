package com.anve.app.data.journey

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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class JourneySyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val owner = inputData.getString("owner") ?: return Result.failure()
        val container = (applicationContext as AnVeApplication).container
        return try {
            if (container.journeyRepository.reconcile(owner)) Result.retry() else Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

class JourneySyncScheduler(context: Context) {
    private val manager = WorkManager.getInstance(context.applicationContext)
    fun schedule(owner: String) {
        val work = OneTimeWorkRequestBuilder<JourneySyncWorker>()
            .setInputData(workDataOf("owner" to owner))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        manager.enqueueUniqueWork("journey-sync-$owner", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }
}
