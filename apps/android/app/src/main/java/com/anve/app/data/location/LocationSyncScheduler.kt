package com.anve.app.data.location

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.anve.app.AnVeApplication
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Schedules durable batch uploads. Acquisition and upload are separate
 * concerns: WorkManager never wakes the GPS, it only drains an already
 * durable queue when connectivity exists.
 */
interface LocationSyncScheduler {
    /** Idempotent: keeps the single unique work item. */
    fun ensureScheduled()

    /**
     * Idempotent delayed scheduling for batching: keeps an already-pending
     * work item so the initial delay window does not reset or chain.
     */
    fun ensureScheduledAfter(delayMillis: Long) = ensureScheduled()

    /** Appends a retry after the server's Retry-After delay, when provided. */
    fun enqueueAfter(delayMillis: Long) = ensureScheduled()

    /** User-initiated "retry now": replaces a waiting item with no backoff. */
    fun enqueueImmediate()

    /** Best-effort cancellation; an already-sent HTTP request may still commit. */
    fun cancel() = Unit
}

class WorkManagerLocationSyncScheduler(context: Context) : LocationSyncScheduler {

    private val appContext = context.applicationContext

    override fun ensureScheduled() = enqueue(ExistingWorkPolicy.KEEP)

    override fun ensureScheduledAfter(delayMillis: Long) =
        enqueue(ExistingWorkPolicy.KEEP, delayMillis.coerceAtLeast(0))

    override fun enqueueAfter(delayMillis: Long) =
        enqueue(ExistingWorkPolicy.APPEND_OR_REPLACE, delayMillis.coerceAtLeast(0))

    override fun enqueueImmediate() = enqueue(ExistingWorkPolicy.REPLACE)

    override fun cancel() {
        WorkManager.getInstance(appContext).cancelUniqueWork(WORK_NAME)
    }

    private fun enqueue(policy: ExistingWorkPolicy, delayMillis: Long = 0) {
        val request = OneTimeWorkRequestBuilder<LocationSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(WORK_NAME, policy, request)
    }

    companion object {
        const val WORK_NAME = "location-sync"
        const val WORK_TAG = "location-sync"
    }
}

/**
 * One bounded sync pass. The engine deletes only acknowledged rows, so a
 * killed worker simply retries the same event ids, which the server treats
 * as duplicates.
 */
class LocationSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val application = applicationContext as? AnVeApplication ?: return Result.success()
        application.container.trackingManager.onSyncWorkerStarted()
        return try {
            val outcome = application.container.locationSyncEngine.sync()
            when {
                outcome.retryAfterMillis != null -> {
                    application.container.locationSyncScheduler.enqueueAfter(outcome.retryAfterMillis)
                    Result.success()
                }
                outcome.retry -> Result.retry()
                outcome.disposition == SyncDisposition.COMPLETE && outcome.errorCode == null -> {
                    // A fix inserted while this worker was RUNNING cannot enqueue via
                    // KEEP; append a follow-up batch job so it is never orphaned.
                    val remaining = application.container.locationQueue.countAll()
                    if (remaining > 0) {
                        val delay = application.container.trackingManager.batchDelayForPendingCount(remaining)
                        application.container.trackingManager.onDelayedSyncScheduled(delay)
                        application.container.locationSyncScheduler.enqueueAfter(delay)
                    }
                    Result.success()
                }
                else -> Result.success()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
