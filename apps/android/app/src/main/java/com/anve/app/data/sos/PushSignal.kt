package com.anve.app.data.sos

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
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
import com.anve.app.MainActivity
import com.anve.app.R
import com.anve.app.data.api.AnveApi
import com.anve.app.data.api.ApiException
import com.anve.app.data.api.JourneyDto
import com.anve.app.data.api.orThrow
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class PushEventRepository(private val api: AnveApi) {
    suspend fun authorized(owner: String, kind: String, id: String, episode: Int = 0): Boolean = try {
        when (kind) {
            "SOS" -> api.getSos(owner, id).orThrow().let { it.state == "ACTIVE" && it.acknowledgedAt == null }
            "PLACE" -> api.placeEvent(owner, id).orThrow().id == id
            else -> if (kind in JOURNEY_PUSH_KINDS) authorizedJourneySignal(api.journey(owner, id).orThrow(), kind, id, episode) else false
        }
    } catch (e: ApiException) {
        if (e.httpStatus in setOf(401, 403, 404)) false else throw e
    }
}

fun authorizedJourneySignal(journey: JourneyDto, kind: String, id: String, episode: Int): Boolean {
    if (!validPushSignal(kind, id, episode) || journey.id != id) return false
    if (kind != "JOURNEY_STALE") return journey.state == kind.removePrefix("JOURNEY_")
    if (journey.state != "ACTIVE" || journey.staleEpisode != episode || journey.staleAfterMinutes <= 0) return false
    val stale = journey.staleSince?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
    // A new live progress sample can precede the maintenance pass that clears staleSince.
    val progress = journey.progress
    val newerRealSample = progress != null && !progress.isMock &&
        runCatching { Instant.parse(progress.recordedAt).isAfter(stale) }.getOrDefault(true)
    return !newerRealSample
}

/** Incoming messages contain only opaque IDs; authorization is rechecked online. */
class PushSignalScheduler(context: Context) {
    private val app = context.applicationContext
    fun schedule(kind: String, eventId: String, episode: Int = 0) {
        if (!validPushSignal(kind, eventId, episode)) return
        val work = OneTimeWorkRequestBuilder<PushSignalWorker>()
            .setInputData(workDataOf("kind" to kind, "eventId" to eventId, "episode" to episode))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        val workName = "push-signal-$kind-$eventId" + if (kind == "JOURNEY_STALE") "-$episode" else ""
        WorkManager.getInstance(app).enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, work)
    }
}

class PushSignalWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val kind = inputData.getString("kind") ?: return Result.failure()
        val eventId = inputData.getString("eventId") ?: return Result.failure()
        val episode = inputData.getInt("episode", 0)
        if (!validPushSignal(kind, eventId, episode)) return Result.failure()
        val app = applicationContext as? AnVeApplication ?: return Result.failure()
        val owner = app.container.sessionStore.current()?.userId ?: return Result.success()
        return try {
            if (!app.container.pushEventRepository.authorized(owner, kind, eventId, episode)) return Result.success()
            if (app.container.sessionStore.current()?.userId != owner) return Result.success()
            val manager = NotificationManagerCompat.from(applicationContext)
            if (!manager.areNotificationsEnabled()) return Result.success()
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
            val hash = MessageDigest.getInstance("SHA-256").digest("$owner:${pushSignalIdentity(kind, eventId, episode)}".toByteArray())
            val key = hash.joinToString("") { "%02x".format(it) }
            val prefs = applicationContext.getSharedPreferences("anve_push_dedup", Context.MODE_PRIVATE)
            if (prefs.contains(key)) return Result.success()
            val id = ((hash[0].toInt() and 255) shl 24) or ((hash[1].toInt() and 255) shl 16) or
                ((hash[2].toInt() and 255) shl 8) or (hash[3].toInt() and 255)
            if (Build.VERSION.SDK_INT >= 26) {
                val channel = NotificationChannel("anve_events", "Sự kiện gia đình", NotificationManager.IMPORTANCE_HIGH)
                applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
            val intent = Intent(applicationContext, MainActivity::class.java)
                .putExtra("anve_event_kind", kind).putExtra("anve_event_id", eventId).putExtra("anve_event_owner", owner)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pending = PendingIntent.getActivity(applicationContext, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(applicationContext, "anve_events")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(when { kind == "SOS" -> "Có yêu cầu hỗ trợ"; kind in JOURNEY_PUSH_KINDS -> "Có cập nhật hành trình"; else -> "Có sự kiện địa điểm" })
                .setContentText("Mở AnVề để xem thông tin được phép.")
                .setContentIntent(pending).setAutoCancel(true).build()
            manager.notify(id, notification)
            val now = System.currentTimeMillis()
            val edit = prefs.edit()
            val entries = prefs.all.entries
            entries.filter { (_, value) -> value !is Long || now - value > 7L * 24 * 60 * 60 * 1000 }
                .forEach { edit.remove(it.key) }
            entries.filter { it.value is Long && now - (it.value as Long) <= 7L * 24 * 60 * 60 * 1000 }
                .sortedBy { it.value as Long }.dropLast(511).forEach { edit.remove(it.key) }
            edit.putLong(key, now).apply()
            Result.success()
        } catch (e: CancellationException) { throw e } catch (_: Exception) { Result.retry() }
    }
}
