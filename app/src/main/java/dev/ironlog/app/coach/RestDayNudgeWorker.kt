package dev.ironlog.app.coach

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.ironlog.app.data.IronlogDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * M7: periodic WorkManager worker that nudges the user to take a rest day after a long
 * training streak. Runs at most once per day (inexact — no USE_EXACT_ALARM). Skips if the
 * toggle is off. Fires only when the user has logged a workout on each of the last
 * [STREAK_THRESHOLD] consecutive days up to and including today (so a day already rested
 * produces a streak of 0 and no nudge).
 */
class RestDayNudgeWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = IronlogDatabase.get(applicationContext)
        val config = db.reminderConfigDao().get() ?: return@withContext Result.success()
        if (!config.restDayNudgesEnabled) return@withContext Result.success()

        val workoutDays = db.workoutDao().all().map { epochDay(it.startTime) }.toSet()
        val streak = currentStreak(workoutDays, epochDay(System.currentTimeMillis()))
        if (streak >= STREAK_THRESHOLD) {
            NudgeNotificationHelper.postRestDayNudge(applicationContext, streak)
        }
        Result.success()
    }

    companion object {
        const val WORK_NAME = "ironlog_rest_day_nudge"
        const val STREAK_THRESHOLD = 6

        /** UTC day index (matches the History screen's UTC day grouping). */
        fun epochDay(timestampMs: Long): Long = timestampMs / 86_400_000L

        /**
         * Length of the run of consecutive trained days ending at [today]. Returns 0 if the
         * user did not train today (they've already started resting). Pure for testability.
         */
        fun currentStreak(workoutDays: Set<Long>, today: Long): Int {
            if (today !in workoutDays) return 0
            var streak = 0
            var day = today
            while (day in workoutDays) {
                streak++
                day--
            }
            return streak
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RestDayNudgeWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
