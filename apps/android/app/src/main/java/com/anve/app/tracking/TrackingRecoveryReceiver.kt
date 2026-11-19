package com.anve.app.tracking

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.anve.app.AnVeApplication
import com.anve.app.MainActivity
import com.anve.app.R
import com.anve.app.data.sos.areAppNotificationsEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Displays a user-visible reminder when active location sharing was halted by
 * a reboot, unexpected system service shutdown, or an app update that cannot
 * resume automatically in the background due to Android OS restrictions.
 */
object TrackingRecoveryNotifier {
    const val CHANNEL_ID = "anve_tracking_recovery"
    const val NOTIFICATION_ID = 1002
    const val EXTRA_OPEN_SHARING = "anve_open_sharing"
    const val EXTRA_OPEN_SHARING_OWNER = "anve_open_sharing_owner"

    enum class Reason {
        REBOOT,
        APP_UPDATED,
        INTERRUPTED,
    }

    fun notify(context: Context, ownerUserId: String, reason: Reason) {
        val appContext = context.applicationContext
        if (!areAppNotificationsEnabled(appContext)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createChannel(appContext)
        val launchIntent = Intent(appContext, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_SHARING, true)
            .putExtra(EXTRA_OPEN_SHARING_OWNER, ownerUserId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            appContext,
            NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val bodyText = when (reason) {
            Reason.REBOOT -> appContext.getString(R.string.tracking_recovery_text_reboot)
            Reason.APP_UPDATED -> appContext.getString(R.string.tracking_recovery_text_updated)
            Reason.INTERRUPTED -> appContext.getString(R.string.tracking_recovery_text_interrupted)
        }
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tracking_notification)
            .setContentTitle(appContext.getString(R.string.tracking_recovery_title))
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.tracking_recovery_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.tracking_recovery_channel_description)
        }
        manager.createNotificationChannel(channel)
    }
}

class TrackingRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as? AnVeApplication ?: return
        val pendingResult = goAsync()
        app.container.applicationScope.launch {
            try {
                if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    val outcome = app.container.trackingManager.recoverAfterAppUpdate()
                    if (outcome is UpdateRecoveryOutcome.RequiresUserAction) {
                        TrackingRecoveryNotifier.notify(
                            app,
                            outcome.ownerUserId,
                            TrackingRecoveryNotifier.Reason.APP_UPDATED,
                        )
                    }
                    return@launch
                }
                val session = app.container.sessionStore.current()
                val owner = session?.userId ?: return@launch
                val local = app.container.trackingStore.current()
                val pendingConsent = app.container.sessionStore.pendingConsentOperations()
                    .any { it.ownerUserId == owner }
                if (local.trackingRequested &&
                    local.consentOwnerUserId == owner &&
                    local.localConsentConfirmed &&
                    !local.pendingConsentRevocation &&
                    !pendingConsent
                ) {
                    TrackingRecoveryNotifier.notify(
                        app,
                        owner,
                        TrackingRecoveryNotifier.Reason.REBOOT,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    runCatching {
                        val session = app.container.sessionStore.current()
                        val owner = session?.userId
                        val local = app.container.trackingStore.current()
                        val pendingConsent = owner != null &&
                            app.container.sessionStore.pendingConsentOperations()
                                .any { it.ownerUserId == owner }
                        if (owner != null &&
                            local.trackingRequested &&
                            local.consentOwnerUserId == owner &&
                            local.localConsentConfirmed &&
                            !local.pendingConsentRevocation &&
                            !pendingConsent
                        ) {
                            TrackingRecoveryNotifier.notify(
                                app,
                                owner,
                                TrackingRecoveryNotifier.Reason.APP_UPDATED,
                            )
                        }
                    }
                }
                // Recovery is best-effort; foreground update recovery or TrackingManager still flags recoveryRequired on launch.
            } finally {
                pendingResult.finish()
            }
        }
    }
}
