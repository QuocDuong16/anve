package com.anve.app.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.anve.app.AnVeApplication
import com.anve.app.MainActivity
import com.anve.app.R

/**
 * Foreground service of type `location`. Its only jobs are to keep the
 * process eligible for location callbacks and to show a persistent,
 * unambiguous notification while sharing is on. Acquisition itself lives in
 * [TrackingManager]; the service never hides what is happening.
 *
 * The service is deliberately `START_NOT_STICKY`: Android may kill it, and
 * pretending tracking resumed after a process death would be false. The
 * user's intent is persisted and the UI offers an explicit resume.
 */
class TrackingService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            manager()?.stopFromNotification()
            stopSelf()
            return START_NOT_STICKY
        }
        return try {
            startForeground(NOTIFICATION_ID, buildNotification(this))
            manager()?.onServiceStarted()
            START_NOT_STICKY
        } catch (e: SecurityException) {
            // Missing foreground-service/location permission: fail closed
            // and tell the coordinator instead of running invisibly.
            manager()?.onServiceStartFailed()
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        manager()?.onServiceDestroyed()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun manager(): TrackingManager? =
        (application as? AnVeApplication)?.container?.trackingManager

    companion object {
        const val ACTION_STOP = "com.anve.app.action.STOP_TRACKING"
        const val NOTIFICATION_CHANNEL_ID = "anve_location_tracking"
        const val NOTIFICATION_ID = 1001

        fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.tracking_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.tracking_notification_channel_description)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        fun buildNotification(context: Context): Notification {
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val stop = PendingIntent.getService(
                context,
                1,
                Intent(context, TrackingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tracking_notification)
                .setContentTitle(context.getString(R.string.tracking_notification_title))
                .setContentText(context.getString(R.string.tracking_notification_text))
                .setContentIntent(open)
                .addAction(
                    0,
                    context.getString(R.string.tracking_notification_stop),
                    stop,
                )
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
        }
    }
}

/** Starts/stops the foreground service and reports whether the OS accepted the start. */
interface TrackingServiceController {
    fun start(): Boolean
    fun stop(): Boolean
}

class AndroidTrackingServiceController(context: Context) : TrackingServiceController {

    private val appContext = context.applicationContext

    override fun start(): Boolean = try {
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, TrackingService::class.java),
        )
        true
    } catch (_: RuntimeException) {
        // ForegroundServiceStartNotAllowedException (API 31+) is the normal
        // case when the OS forbids a background start; SecurityException
        // covers a missing permission. Both mean "do not claim tracking".
        false
    }

    override fun stop(): Boolean = appContext.stopService(Intent(appContext, TrackingService::class.java))
}
