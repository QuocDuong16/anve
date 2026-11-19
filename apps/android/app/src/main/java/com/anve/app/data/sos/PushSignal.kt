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
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.anve.app.AnVeApplication
import com.anve.app.MainActivity
import com.anve.app.R
import com.anve.app.data.api.AnveApi
import com.anve.app.data.api.ApiException
import com.anve.app.data.api.JourneyDto
import com.anve.app.data.api.LowBatteryAlertDto
import com.anve.app.data.api.PlaceEventDto
import com.anve.app.data.api.ReceivedInvitationDto
import com.anve.app.data.api.SosEventDto
import com.anve.app.data.api.orThrow
import com.anve.app.ui.map.findMatchingPlace
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

data class PushSignalDetails(
    val title: String,
    val body: String,
    val urgent: Boolean,
)

class PushEventRepository(
    private val api: AnveApi,
    private val cachedMembers: suspend (String) -> List<SosCachedMember> = { emptyList() },
) {
    suspend fun authorized(owner: String, kind: String, id: String, episode: Int = 0): Boolean =
        resolveAuthorized(owner, kind, id, episode) != null

    suspend fun resolveAuthorized(owner: String, kind: String, id: String, episode: Int = 0): PushSignalDetails? = try {
        when (kind) {
            "CIRCLE_INVITE" -> formatInvitation(api.receivedInvitation(owner, id).orThrow(), id)
            "SOS" -> formatSos(owner, api.getSos(owner, id).orThrow())
            "PLACE" -> formatPlace(api.placeEvent(owner, id).orThrow(), id)
            "LOW_BATTERY" -> formatLowBattery(owner, api.batteryAlert(owner, id).orThrow(), id)
            else -> if (kind in JOURNEY_PUSH_KINDS) {
                formatJourney(api.journey(owner, id).orThrow(), kind, id, episode)
            } else {
                null
            }
        }
    } catch (e: ApiException) {
        if (e.httpStatus in setOf(401, 403, 404)) null else throw e
    }

    private fun formatInvitation(inv: ReceivedInvitationDto, id: String): PushSignalDetails? {
        if (inv.id != id || !Instant.parse(inv.expiresAt).isAfter(Instant.now())) return null
        val sender = inv.inviterDisplayName.trim()
        val circle = inv.circleName.trim()
        val title = if (sender.isNotBlank()) "Lời mời gia đình từ $sender" else "Có lời mời gia đình"
        val body = if (circle.isNotBlank()) {
            "Tham gia nhóm \"$circle\". Mở AnVề để xem và xác nhận."
        } else {
            "Mở AnVề để xem thông tin được phép."
        }
        return PushSignalDetails(title = title, body = body, urgent = false)
    }

    private suspend fun formatSos(owner: String, event: SosEventDto): PushSignalDetails? {
        if (event.state != "ACTIVE" || event.acknowledgedAt != null) return null
        val sender = event.ownerName?.trim()?.takeIf { it.isNotBlank() }
            ?: runCatching { cachedMembers(owner) }.getOrDefault(emptyList())
                .firstOrNull { it.userId == event.ownerId }?.displayName?.trim()?.takeIf { it.isNotBlank() }
            ?: runCatching {
                val circle = api.myCircle(owner).orThrow().circle ?: return@runCatching null
                api.members(circle.id, owner).orThrow().members
                    .firstOrNull { it.userId == event.ownerId }?.displayName
            }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
        val title = if (sender != null) {
            "$sender cần hỗ trợ khẩn cấp (SOS)"
        } else {
            "Có yêu cầu hỗ trợ khẩn cấp (SOS)"
        }
        val details = buildList {
            event.batteryPercent?.let { add("Pin $it%") }
            if (event.location != null) add("Có vị trí đính kèm")
        }
        val body = if (details.isNotEmpty()) {
            "${details.joinToString(" · ")}. Mở AnVề để xem ngay."
        } else {
            "Mở AnVề để xem thông tin được phép."
        }
        return PushSignalDetails(title = title, body = body, urgent = true)
    }

    private fun formatPlace(event: PlaceEventDto, id: String): PushSignalDetails? {
        if (event.id != id) return null
        val member = event.ownerName.trim()
        val place = event.placeName.trim()
        val action = when (event.type) {
            "ENTER" -> "đã đến"
            "EXIT" -> "đã rời"
            else -> "cập nhật tại"
        }
        val title = when {
            member.isNotBlank() && place.isNotBlank() -> "$member $action $place"
            place.isNotBlank() -> "Cập nhật tại $place"
            else -> "Có sự kiện địa điểm"
        }
        val body = if (event.delayed) {
            "Ghi nhận từ dữ liệu đồng bộ bù. Mở AnVề để xem chi tiết."
        } else {
            "Mở AnVề để xem thông tin được phép."
        }
        return PushSignalDetails(title = title, body = body, urgent = true)
    }

    private fun formatJourney(journey: JourneyDto, kind: String, id: String, episode: Int): PushSignalDetails? {
        if (!authorizedJourneySignal(journey, kind, id, episode)) return null
        val member = journey.ownerName.trim()
        val destination = journey.destination.label.trim()
        val subject = if (member.isNotBlank()) member else "Thành viên"
        val placeSuffix = if (destination.isNotBlank()) " đến $destination" else ""
        val title = when (kind) {
            "JOURNEY_ACTIVE" -> "$subject bắt đầu hành trình$placeSuffix"
            "JOURNEY_ARRIVED" -> "$subject đã đến${if (destination.isNotBlank()) " $destination" else " nơi"}"
            "JOURNEY_ENDED" -> "$subject đã kết thúc hành trình$placeSuffix"
            "JOURNEY_CANCELLED" -> "$subject đã hủy hành trình$placeSuffix"
            "JOURNEY_EXPIRED" -> "Hành trình của $subject$placeSuffix đã hết hạn"
            "JOURNEY_STALE" -> "Hành trình của $subject$placeSuffix chưa có cập nhật mới"
            else -> "Có cập nhật hành trình"
        }
        val body = if (kind == "JOURNEY_STALE") {
            "Hãy liên hệ. Mở AnVề để kiểm tra trạng thái hiện tại."
        } else {
            "Mở AnVề để xem thông tin được phép."
        }
        return PushSignalDetails(title = title, body = body, urgent = true)
    }

    private suspend fun formatLowBattery(owner: String, alert: LowBatteryAlertDto, id: String): PushSignalDetails? {
        if (alert.id != id || alert.resolvedAt != null) return null
        val member = alert.ownerName.trim().ifBlank { "Thành viên" }
        val isCritical = alert.threshold <= 5 || alert.batteryPercent <= 5
        val placeMatch = alert.location?.let { loc ->
            val places = runCatching { api.places(owner).orThrow().places }.getOrDefault(emptyList())
            findMatchingPlace(loc.latitude, loc.longitude, places)
        }
        val locationText = when {
            placeMatch != null && placeMatch.isInside -> " tại ${placeMatch.place.name}"
            placeMatch != null -> " gần ${placeMatch.place.name}"
            alert.location != null -> " (đã lưu tọa độ cuối)"
            else -> ""
        }
        val title = if (isCritical) {
            "Điện thoại của $member sắp hết pin (${alert.batteryPercent}%)"
        } else {
            "Điện thoại của $member còn ${alert.batteryPercent}% pin"
        }
        val body = if (isCritical) {
            "Pin còn ${alert.batteryPercent}%$locationText — có thể sắp mất kết nối do hết pin."
        } else {
            "Pin còn ${alert.batteryPercent}%$locationText. Mở AnVề để xem vị trí và trạng thái chia sẻ."
        }
        return PushSignalDetails(title = title, body = body, urgent = isCritical)
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

object PushNotificationDisplay {
    private val dedupLock = Any()

    fun show(
        context: Context,
        owner: String,
        kind: String,
        eventId: String,
        episode: Int,
        details: PushSignalDetails,
    ): Boolean {
        val appContext = context.applicationContext
        if (!areAppNotificationsEnabled(appContext)) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("$owner:${pushSignalIdentity(kind, eventId, episode)}".toByteArray())
        val key = hash.joinToString("") { "%02x".format(it) }
        val id = ((hash[0].toInt() and 255) shl 24) or ((hash[1].toInt() and 255) shl 16) or
            ((hash[2].toInt() and 255) shl 8) or (hash[3].toInt() and 255)

        val invitation = kind == "CIRCLE_INVITE"
        val quietBattery = kind == "LOW_BATTERY" && !details.urgent
        val channelId = when {
            invitation -> "anve_invitations"
            quietBattery -> "anve_battery_info"
            else -> "anve_events"
        }
        ensureChannel(appContext, channelId, invitation)

        val intent = Intent(appContext, MainActivity::class.java)
            .putExtra("anve_event_kind", kind)
            .putExtra("anve_event_id", eventId)
            .putExtra("anve_event_owner", owner)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            appContext,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(details.title)
            .setContentText(details.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details.body))
            .setPriority(if (details.urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (kind == "SOS") NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        synchronized(dedupLock) {
            val prefs = appContext.getSharedPreferences("anve_push_dedup", Context.MODE_PRIVATE)
            if (prefs.contains(key)) return false
            NotificationManagerCompat.from(appContext).notify(id, notification)
            val now = System.currentTimeMillis()
            val edit = prefs.edit()
            val entries = prefs.all.entries
            entries.filter { (_, value) -> value !is Long || now - value > 7L * 24 * 60 * 60 * 1000 }
                .forEach { edit.remove(it.key) }
            entries.filter { it.value is Long && now - (it.value as Long) <= 7L * 24 * 60 * 60 * 1000 }
                .sortedBy { it.value as Long }.dropLast(511).forEach { edit.remove(it.key) }
            edit.putLong(key, now).apply()
        }
        return true
    }

    fun ensureChannel(context: Context, channelId: String, invitation: Boolean) {
        if (Build.VERSION.SDK_INT < 26) return
        val quietBattery = channelId == "anve_battery_info"
        val title = when {
            invitation -> "Lời mời gia đình"
            quietBattery -> "Thông báo pin gia đình"
            else -> "Sự kiện gia đình"
        }
        val importance = if (invitation || quietBattery) {
            NotificationManager.IMPORTANCE_DEFAULT
        } else {
            NotificationManager.IMPORTANCE_HIGH
        }
        val channel = NotificationChannel(channelId, title, importance)
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}

/** Incoming messages contain only opaque IDs; authorization is rechecked online. */
class PushSignalScheduler(context: Context) {
    private val app = context.applicationContext
    fun schedule(kind: String, eventId: String, episode: Int = 0) {
        if (!validPushSignal(kind, eventId, episode)) return
        val work = OneTimeWorkRequestBuilder<PushSignalWorker>()
            .setInputData(workDataOf("kind" to kind, "eventId" to eventId, "episode" to episode))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        val workName = "push-signal-$kind-$eventId" + if (kind == "JOURNEY_STALE") "-$episode" else ""
        WorkManager.getInstance(app).enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, work)
    }
}

class PushSignalWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        PushNotificationDisplay.ensureChannel(applicationContext, "anve_events", invitation = false)
        val notification = NotificationCompat.Builder(applicationContext, "anve_events")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Đang kiểm tra thông báo gia đình")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification)
    }

    override suspend fun doWork(): Result {
        val kind = inputData.getString("kind") ?: return Result.failure()
        val eventId = inputData.getString("eventId") ?: return Result.failure()
        val episode = inputData.getInt("episode", 0)
        if (!validPushSignal(kind, eventId, episode)) return Result.failure()
        val app = applicationContext as? AnVeApplication ?: return Result.failure()
        val owner = app.container.sessionStore.current()?.userId ?: return Result.success()
        return try {
            val details = app.container.pushEventRepository.resolveAuthorized(owner, kind, eventId, episode)
                ?: return Result.success()
            if (app.container.sessionStore.current()?.userId != owner) return Result.success()
            PushNotificationDisplay.show(applicationContext, owner, kind, eventId, episode, details)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private companion object {
        const val FOREGROUND_NOTIFICATION_ID = 1003
    }
}
