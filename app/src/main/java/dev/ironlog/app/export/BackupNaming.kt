package dev.ironlog.app.export

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Filenames for the automated/manual backup JSON files this app writes into the user-picked
 * SAF folder. Pure (no Android) so it's directly JVM-testable. Mirrors MoodLog's
 * `data.exporter.ExportFileNaming` (`mood-tracker/app/.../backup/`), the proven donor pattern
 * for F9 (ironlog's auto-backup was dead code -- nothing ever scheduled the worker).
 */
object ExportFileNaming {
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy_MM_dd")

    const val BACKUP_PREFIX = "ironlog_backup_"

    fun backupFileName(date: LocalDate = LocalDate.now()): String =
        "$BACKUP_PREFIX${date.format(DATE_FMT)}.json"
}

/**
 * Pure selection logic for which rotated-out backup files to delete after writing a new one --
 * kept Android/DocumentFile-free so it's directly JVM-testable. The actual folder listing and
 * deletion happens in [BackupRunner] (Android-only, not unit tested).
 */
object BackupRotation {
    /**
     * Given the backup filenames already sitting in the folder (any order, before the new one
     * was added) plus the name just written, returns exactly the names to DELETE so only the
     * newest [keep] remain (the new one always survives). Backup filenames sort
     * lexicographically == chronologically (`ironlog_backup_yyyy_MM_dd.json`), so a plain string
     * sort is a correct chronological order without parsing any dates back out.
     */
    fun namesToDelete(existingNames: List<String>, newName: String, keep: Int = 8): List<String> {
        val all = (existingNames + newName).distinct().sorted()
        val overflow = all.size - keep
        if (overflow <= 0) return emptyList()
        return all.take(overflow).filter { it != newName }
    }
}
