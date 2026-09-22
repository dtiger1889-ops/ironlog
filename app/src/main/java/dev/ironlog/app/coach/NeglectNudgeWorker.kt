package dev.ironlog.app.coach

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.ironlog.app.data.IronlogDatabase
import dev.ironlog.app.gym.MuscleAssistant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * M7: periodic WorkManager worker that checks for neglected muscles and posts a nudge.
 * Runs at most once per day (inexact — no USE_EXACT_ALARM). Skips if nudges are disabled.
 * Dedup: WorkManager enqueues with KEEP policy — a second schedule call is a no-op while
 * the existing work is still running/scheduled.
 */
class NeglectNudgeWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = IronlogDatabase.get(applicationContext)
        val config = db.reminderConfigDao().get() ?: return@withContext Result.success()
        if (!config.neglectNudgesEnabled) return@withContext Result.success()

        val exercises = db.exerciseDao().all()
        val workouts = db.workoutDao().all()
        val sets = db.setEntryDao().all()
        val targets = db.muscleVolumeTargetDao().all().associateBy { it.muscle }

        val statuses = MuscleAssistant.coverageStatus(
            sets = sets,
            workouts = workouts,
            exercises = exercises,
            targets = targets,
            nowMillis = System.currentTimeMillis(),
        )
        val neglected = statuses.filter { it.stale || it.neverIsolated }.map { it.muscle }
        if (neglected.isEmpty()) return@withContext Result.success()

        NudgeNotificationHelper.postNeglectNudge(applicationContext, neglected)
        Result.success()
    }

    companion object {
        const val WORK_NAME = "ironlog_neglect_nudge"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NeglectNudgeWorker>(1, TimeUnit.DAYS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
