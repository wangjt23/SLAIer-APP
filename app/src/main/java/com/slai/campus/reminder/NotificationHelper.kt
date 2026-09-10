package com.slai.campus.reminder

import android.Manifest
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
import com.slai.campus.MainActivity
import com.slai.campus.R
import com.slai.campus.core.common.AppLog

/**
 * Notification plumbing.
 *
 * Android 13+ requires the `POST_NOTIFICATIONS` runtime permission, which is requested from
 * `MainActivity` at the moment reminders are first enabled. When it is denied the app keeps working
 * and simply does not post: no crash, no silent failure loop.
 */
object NotificationHelper {

    const val CHANNEL_REMINDER = "class_reminder"
    const val CHANNEL_SYNC = "background_sync"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDER,
                context.getString(R.string.notification_channel_reminder),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_reminder_desc)
                enableVibration(true)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SYNC,
                context.getString(R.string.notification_channel_sync),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_sync_desc)
            }
        )
    }

    fun canPostNotifications(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    /** Posts a class reminder. Silently returns when notifications are not permitted. */
    fun showClassReminder(
        context: Context,
        occurrenceId: String,
        courseName: String,
        location: String?,
        startTime: String?
    ) {
        if (!canPostNotifications(context)) {
            AppLog.i("reminder skipped: notifications not permitted")
            return
        }
        ensureChannels(context)

        val contentIntent = PendingIntent.getActivity(
            context,
            occurrenceId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val body = listOfNotNull(location?.takeIf { it.isNotBlank() }, startTime?.takeIf { it.isNotBlank() })
            .joinToString(" · ")

        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_reminder_title, courseName))
            .setContentText(
                if (body.isBlank()) {
                    context.getString(R.string.app_name)
                } else {
                    context.getString(R.string.notification_reminder_body, body, courseName)
                }
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(occurrenceId.hashCode() and 0x0FFFFFFF, notification)
        }.onFailure { AppLog.w("notification failed: ${it.javaClass.simpleName}") }
    }
}
