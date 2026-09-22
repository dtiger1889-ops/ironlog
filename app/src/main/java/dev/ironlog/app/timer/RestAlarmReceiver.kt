package dev.ironlog.app.timer

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import dev.ironlog.app.R
import dev.ironlog.app.data.AppSettings
import dev.ironlog.app.data.RestSoundPref
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Receives the AlarmManager exact alarm and posts the rest-done notification + haptic. */
class RestAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REST_DONE) return

        // Direct vibration — runs because this is a system-dispatched receiver, not in-process code.
        vibrator(context).run {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrate(VIBRATION_PATTERN, -1)
            }
        }

        // Tapping the notification opens (or foregrounds) the app straight to the workout.
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        // F6: CHANNEL_ID's own sound is permanently silent (see RestNotificationHelper) --
        // .setVibrate below still drives the channel's vibration path as before; the audible
        // alert itself is played manually just below, from the DataStore-picked sound.
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Rest over")
            .setContentText("Time to lift!")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVibrate(VIBRATION_PATTERN)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            // Self-dismiss: a stale "Rest over" is noise 90s later; never needs manual clearing.
            .setTimeoutAfter(90_000L)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notif)

        // F6: DataStore reads are suspend -- goAsync() so the receiver isn't torn down before
        // the coroutine reads the pref and fires the sound (vibration/notification above are
        // already done synchronously and don't wait on this).
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val stored = AppSettings(context.applicationContext).restSoundUri.first()
                playRestSound(context.applicationContext, stored)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun playRestSound(context: Context, stored: String?) {
        if (RestSoundPref.isSilent(stored)) return
        val uri: Uri = if (RestSoundPref.isSystemDefault(stored)) {
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION)
        } else {
            runCatching { Uri.parse(stored) }.getOrNull()
        } ?: return
        // Fire-and-forget, same spirit as the direct vibration call above -- a short alert
        // sound, not something we need to hold a reference to stop early.
        runCatching { RingtoneManager.getRingtone(context, uri)?.play() }
    }

    private fun vibrator(context: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    companion object {
        const val ACTION_REST_DONE = "dev.ironlog.app.ACTION_REST_DONE"
        // F6: bumped from "ironlog_rest" -- see RestNotificationHelper.LEGACY_CHANNEL_ID.
        const val CHANNEL_ID = "ironlog_rest_v2"
        const val NOTIFICATION_ID = 1001

        // Mario-Kart GO pattern: three escalating pulses (100ms→150ms→250ms) then long GO (400ms).
        val VIBRATION_PATTERN = longArrayOf(0, 100, 80, 150, 80, 250, 80, 400)
    }
}
