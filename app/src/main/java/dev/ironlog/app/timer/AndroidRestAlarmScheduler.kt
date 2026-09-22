package dev.ironlog.app.timer

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Android implementation — schedules/cancels the exact alarm via AlarmManager. */
class AndroidRestAlarmScheduler(private val context: Context) : RestAlarmScheduler {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(endTimeMs: Long) {
        dismissNotification() // a new rest supersedes any lingering "Rest over"
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            endTimeMs,
            pendingIntent(PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)!!,
        )
    }

    override fun cancel() {
        pendingIntent(PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            ?.let { alarmManager.cancel(it) }
        dismissNotification() // stopping rest (skip / finish / discard) clears the tray too
    }

    private fun dismissNotification() {
        context.getSystemService(NotificationManager::class.java)
            .cancel(RestAlarmReceiver.NOTIFICATION_ID)
    }

    private fun pendingIntent(flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, RestAlarmReceiver::class.java).setAction(RestAlarmReceiver.ACTION_REST_DONE),
            flags,
        )

    companion object {
        private const val REQUEST_CODE = 1001
    }
}
