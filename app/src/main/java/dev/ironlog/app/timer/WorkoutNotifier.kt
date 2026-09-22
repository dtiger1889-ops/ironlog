package dev.ironlog.app.timer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import dev.ironlog.app.R

/**
 * The slim "workout in progress" tray notification: workout name + live rest countdown /
 * next-set line. Interface so the ViewModel stays JVM-testable (null in tests).
 */
interface WorkoutNotifier {
    /**
     * [restEndTimeMs] non-null while resting: renders a system-driven countdown Chronometer
     * (F5) so the lock screen keeps counting down even while the app isn't posting per-second
     * updates (see AndroidWorkoutNotifier.show for why that matters). Null when idle.
     */
    fun show(title: String, text: String, restEndTimeMs: Long? = null)
    fun cancel()
}

class AndroidWorkoutNotifier(private val context: Context) : WorkoutNotifier {

    override fun show(title: String, text: String, restEndTimeMs: Long?) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                context, 1, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            // F5: full text on the lock screen, not just a redacted placeholder.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Silent + alert-once: only re-posted on set-completion / rest start-stop, never
            // every second (the rest ALARM owns the buzz; the Chronometer below owns the tick).
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
        if (restEndTimeMs != null) {
            // F5: a real Chronometer counting down to restEndTimeMs. Android's own SystemUI
            // renders and ticks this every second FROM THE VALUE ALONE -- no per-second
            // notify() call needed, so it keeps counting on the lock screen with the screen
            // off, which a viewModelScope coroutine loop (tied to process/Doze scheduling,
            // no foreground service here) cannot reliably guarantee.
            builder.setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(restEndTimeMs)
        } else {
            builder.setUsesChronometer(false)
        }
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, builder.build())
    }

    override fun cancel() {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    companion object {
        const val CHANNEL_ID = "ironlog_workout"
        const val NOTIFICATION_ID = 1002

        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Workout in progress",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Slim status while a workout is running (rest countdown, next set)"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
