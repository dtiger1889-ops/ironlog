package dev.ironlog.app.share

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands one finished workout to whatever the user wants to send it with (mail, messaging,
 * a cloud drive) as a PDF attachment. Nothing leaves the phone unless the user picks a target.
 */
object WorkoutShare {

    private const val MIME_PDF = "application/pdf"
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    /** Render [document] and open the system share sheet for the resulting PDF. */
    fun sharePdf(context: Context, document: WorkoutReport.Document) {
        pruneOldFiles(context)
        val file = WorkoutPdfWriter.write(context, document)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_PDF
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, document.fileNameStem)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Send this workout").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
    }

    /** Shared PDFs are throwaway copies; clear anything older than a day on each share. */
    private fun pruneOldFiles(context: Context) {
        val dir = File(context.cacheDir, "share")
        if (!dir.isDirectory) return
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < cutoff) f.delete()
        }
    }
}
