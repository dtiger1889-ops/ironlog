package dev.ironlog.app.export

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.ironlog.app.data.IronlogDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Android-only glue that actually writes a backup JSON into the SAF folder the user picked --
 * shared by the immediate "Back up now" button (SettingsScreen) and [AutoBackupWorker]'s weekly
 * run so the write + rotation logic exists in exactly one place. Not unit tested (DocumentFile
 * needs a real ContentResolver); the pure parts it calls into (ExportManager, [BackupRotation])
 * are. Mirrors MoodLog's `backup.BackupRunner`, the proven donor pattern for F9.
 *
 * Historically the stored `autoBackupUri` was a folder (`ActivityResultContracts.OpenDocumentTree`
 * picks a TREE uri) but the dead worker's write path (`openOutputStream(uri, "wt")`) treated it as
 * a single document -- that mismatch, on top of nothing ever scheduling the worker, is why
 * auto-backup silently did nothing. [DocumentFile.fromTreeUri] is the correct read for a tree uri.
 */
object BackupRunner {

    private const val KEEP_NEWEST = 8

    /**
     * Writes today's backup into the picked folder and deletes rotated-out old backups (keeps
     * the newest 8, including the one just written). Returns the new file's actual display name
     * on success. Throws on any failure; callers ([AutoBackupWorker] / the Settings "Back up now"
     * button) decide how to surface that -- this function never swallows an error into a return
     * value.
     */
    suspend fun runBackup(context: Context, folderUriString: String): String = withContext(Dispatchers.IO) {
        val treeUri = Uri.parse(folderUriString)
        val dir = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Backup folder is no longer accessible")
        if (!dir.isDirectory || !dir.canWrite()) error("Backup folder is not writable")

        val db = IronlogDatabase.get(context)
        // Exclude catalog-only seed rows (not user data; re-seeds on install) -- same filter the
        // manual JSON export uses.
        val exercises = db.exerciseDao().all().filter { !it.catalogOnly }
        val workouts = db.workoutDao().all()
        val sets = db.setEntryDao().all()
        val measurements = db.measurementDao().all()
        val json = ExportManager.exportJson(exercises, workouts, sets, measurements)

        val requestedName = ExportFileNaming.backupFileName(LocalDate.now())
        val existingFiles = dir.listFiles().toList()
        val existingBackupNames = existingFiles.mapNotNull { it.name }
            .filter { it.startsWith(ExportFileNaming.BACKUP_PREFIX) }

        val newFile = dir.createFile("application/json", requestedName)
            ?: error("Could not create the backup file")
        context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
        } ?: error("Could not open the backup file for writing")

        val actualName = newFile.name ?: requestedName
        val toDelete = BackupRotation.namesToDelete(existingBackupNames, actualName, keep = KEEP_NEWEST)
        for (name in toDelete) {
            existingFiles.firstOrNull { it.name == name }?.delete()
        }

        actualName
    }
}
