package dev.ironlog.app.export

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.ironlog.app.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * WorkManager weekly periodic worker that writes a full JSON backup to the user-chosen SAF
 * folder via [BackupRunner]. Offline/local only. Scheduled/cancelled by [schedule]/[cancel],
 * driven by the Settings "Auto-backup (weekly)" toggle.
 *
 * F9 fix (found dead 2026-07-18 by the MoodLog backup build): this worker previously existed
 * but nothing ever called a scheduler for it, and its write path assumed the stored URI was a
 * single document when it's actually a folder tree -- the toggle stored a preference with zero
 * effect. Now: never crashes or wedges silently -- every path either records a status line via
 * [AppSettings.setLastBackupStatus] or is a clean no-op (backup turned off / no folder chosen
 * yet). A thrown exception from [BackupRunner] is recorded as a failure status and turned into
 * [androidx.work.ListenableWorker.Result.retry] so WorkManager's backoff policy takes another
 * pass rather than silently never running again.
 */
class AutoBackupWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settings = AppSettings(applicationContext)
        val enabled = settings.autoBackupEnabled.first()
        val folderUri = settings.autoBackupUri.first()
        if (!enabled || folderUri == null) {
            // Nothing to do -- not a failure, just a no-op (e.g. toggled off since the last
            // schedule, or work fired once before a folder was ever picked).
            return@withContext Result.success()
        }

        return@withContext try {
            val name = BackupRunner.runBackup(applicationContext, folderUri)
            settings.setLastBackupStatus("Backed up $name (${nowLabel()})")
            Result.success()
        } catch (e: Exception) {
            settings.setLastBackupStatus("Backup failed: ${e.message ?: e.javaClass.simpleName}")
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "ironlog_auto_backup"

        /** Enqueue/re-affirm the weekly backup. Idempotent -- [ExistingPeriodicWorkPolicy.UPDATE]
         *  makes repeated calls (e.g. every time the toggle flips on) a cheap no-op reschedule
         *  rather than stacking duplicate work. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(7, java.util.concurrent.TimeUnit.DAYS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

private val STATUS_TIME_FORMAT = DateTimeFormatter.ofPattern("MMM d, h:mm a")
private fun nowLabel(): String = LocalDateTime.now().format(STATUS_TIME_FORMAT)
