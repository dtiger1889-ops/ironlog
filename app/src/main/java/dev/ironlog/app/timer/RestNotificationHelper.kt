package dev.ironlog.app.timer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

object RestNotificationHelper {
    // Pre-F6 channel id. Its sound was never set explicitly, so Android auto-assigned the
    // device's default notification sound at creation time -- and channel properties (sound
    // included) freeze permanently once created. Delete it so it doesn't linger as a duplicate
    // entry in system Settings > App notifications now that CHANNEL_ID has moved to _v2.
    private const val LEGACY_CHANNEL_ID = "ironlog_rest"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            RestAlarmReceiver.CHANNEL_ID,
            "Rest timer",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Fires when your rest interval ends"
            vibrationPattern = RestAlarmReceiver.VIBRATION_PATTERN
            enableVibration(true)
            // F5: visible on the lock screen without unlocking, same as the ongoing workout channel.
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            // F6: the channel itself is permanently silent -- RestAlarmReceiver plays the picked
            // sound (or the device default, or nothing) manually via Ringtone every time it
            // fires. That's what lets the user's sound PREFERENCE change freely without ever
            // needing another channel version bump: only this one-time move off the legacy
            // channel (which had an OS-assigned default sound baked in) needed a version bump.
            setSound(null, null)
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
        nm.deleteNotificationChannel(LEGACY_CHANNEL_ID)
    }
}
