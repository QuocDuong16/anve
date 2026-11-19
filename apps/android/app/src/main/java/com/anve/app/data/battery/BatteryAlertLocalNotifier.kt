package com.anve.app.data.battery

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
import com.anve.app.MainActivity
import com.anve.app.R
import com.anve.app.data.sos.areAppNotificationsEnabled

interface BatteryAlertLocalNotifier {
    fun notifySent(ownerUserId: String, threshold: Int, batteryPercent: Int, recipientCount: Int)
}

object NoOpBatteryAlertLocalNotifier : BatteryAlertLocalNotifier {
    override fun notifySent(ownerUserId: String, threshold: Int, batteryPercent: Int, recipientCount: Int) = Unit
}

/**
 * Quiet local confirmation for the sender once the server accepts a low-battery
 * alert and notifies at least one family recipient.
 */
class AndroidBatteryAlertLocalNotifier(context: Context) : BatteryAlertLocalNotifier {
    private val appContext = context.applicationContext

    override fun notifySent(ownerUserId: String, threshold: Int, batteryPercent: Int, recipientCount: Int) {
        if (recipientCount <= 0) return
        if (!areAppNotificationsEnabled(appContext)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                appContext,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureChannel()
        val intent = Intent(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            NOTIFICATION_BASE_ID + threshold,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Đã gửi cảnh báo pin $batteryPercent%")
            .setContentText(
                "AnVề đã báo cho $recipientCount người thân về mức pin yếu để cả nhà yên tâm.",
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(appContext)
            .notify(NOTIFICATION_BASE_ID + threshold, notification)
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Thông báo pin yếu",
            NotificationManager.IMPORTANCE_LOW,
        )
        appContext.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID: String = "anve_battery_status"
        private const val NOTIFICATION_BASE_ID: Int = 2200
    }
}
