package com.anve.app.data.auth

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
 * Keeps a constrained background retry scheduled while an undelivered
 * sign-out exists. The retry is deliberately independent of any Activity:
 * a user who signs out offline and closes the app must still have the
 * revocation delivered when connectivity returns.
 */
fun interface PendingRevocationScheduler {
    fun ensureScheduled()
}

/** Default for tests and for code paths that do not own a scheduler. */
val NoOpPendingRevocationScheduler = PendingRevocationScheduler { }

/**
 * WorkManager-backed scheduler. `KEEP` makes the schedule idempotent: the
 * same unique work is never duplicated, and a running worker keeps its own
 * retry/backoff instead of being replaced. WorkManager persists the request
 * across process death and app updates, so an offline sign-out survives a
 * restart without depending on the app being opened again.
 */
class WorkManagerPendingRevocationScheduler(context: Context) : PendingRevocationScheduler {

    private val appContext = context.applicationContext

    override fun ensureScheduled() {
        val request = OneTimeWorkRequestBuilder<PendingRevocationWorker>()
            .setConstraints(
                Constraints.Builder()
                    // The worker is pointless without a network; the
                    // constraint also lets the OS batch it with other work.
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            // WorkManager enforces a 10s minimum; 30s matches the first
            // in-process retry delay and never tight-loops.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val WORK_NAME = "pending-session-revocation"
        const val WORK_TAG = "auth-revocation"
    }
}

/**
 * Runs one revocation pass. Returning `retry` lets WorkManager apply its
 * exponential backoff; the repository's own per-record schedule still
 * decides which records are actually due, so the two mechanisms cannot
 * rotate a token twice or retry a record past its expiry.
 */
class PendingRevocationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val application = applicationContext as? AnVeApplication ?: return Result.success()
        return try {
            val remaining = application.container.authRepository.retryPendingRevocations()
            if (remaining) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
