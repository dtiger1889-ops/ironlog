package dev.ironlog.app.coach

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NudgeNotificationHelper {
    const val CHANNEL_ID = "ironlog_nudges"
    private const val NOTIFY_ID_NEGLECT = 1001
    private const val NOTIFY_ID_RESTDAY = 1002

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Coach nudges",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Reminders for neglected muscles and rest days"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Returns true if the notification was posted, false if permission is denied. */
    fun postNeglectNudge(context: Context, muscles: List<String>): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val body = when {
            muscles.size == 1 -> "${muscles[0].replaceFirstChar { it.uppercase() }} hasn't been trained recently."
            muscles.size <= 3 -> muscles.joinToString(", ") { it.replaceFirstChar { c -> c.uppercase() } } + " haven't been trained recently."
            else -> "${muscles.size} muscles haven't been trained recently."
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("ironlog — muscle check")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFY_ID_NEGLECT, notification)
        return true
    }

    /** Returns true if the notification was posted, false if permission is denied. */
    fun postRestDayNudge(context: Context, streakDays: Int): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val body = "You've trained $streakDays days in a row. Consider taking a rest day to recover."
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("ironlog — rest day?")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFY_ID_RESTDAY, notification)
        return true
    }
}
