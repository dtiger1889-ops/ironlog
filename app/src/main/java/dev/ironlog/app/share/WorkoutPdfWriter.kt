package dev.ironlog.app.share

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

/**
 * Renders a [WorkoutReport.Document] to a Letter-size PDF with the platform PdfDocument
 * (no third-party library). Plain sans text, simple pagination, "page n of N" footer.
 *
 * The file lands in `cacheDir/share/` — the only directory the app's FileProvider exposes.
 */
object WorkoutPdfWriter {

    // Letter at 72 points per inch.
    private const val PAGE_WIDTH = 612
    private const val PAGE_HEIGHT = 792
    private const val MARGIN_LEFT = 54f
    private const val MARGIN_RIGHT = 54f
    private const val MARGIN_TOP = 64f
    private const val FOOTER_BASELINE = PAGE_HEIGHT - 40f
    private const val CONTENT_BOTTOM = FOOTER_BASELINE - 24f

    private val contentWidth = PAGE_WIDTH - MARGIN_LEFT - MARGIN_RIGHT

    /** One already-wrapped physical line of text plus the space above it. */
    private data class Drawable(
        val text: String,
        val paint: Paint,
        val gapAbove: Float,
        val lineHeight: Float,
        val indent: Float,
    )

    /**
     * Write [document] into `cacheDir/share/<stem>.pdf`, overwriting a previous file of the
     * same name, and return it.
     */
    fun write(context: Context, document: WorkoutReport.Document): File {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "${document.fileNameStem}.pdf")
        FileOutputStream(file).use { out ->
            val pdf = PdfDocument()
            try {
                val pages = paginate(layout(document.lines))
                pages.forEachIndexed { index, pageLines ->
                    val page = pdf.startPage(
                        PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create(),
                    )
                    var y = MARGIN_TOP
                    pageLines.forEach { d ->
                        y += d.gapAbove + d.lineHeight
                        if (d.text.isNotEmpty()) {
                            page.canvas.drawText(d.text, MARGIN_LEFT + d.indent, y, d.paint)
                        }
                    }
                    page.canvas.drawText(
                        "page ${index + 1} of ${pages.size}",
                        PAGE_WIDTH / 2f,
                        FOOTER_BASELINE,
                        footerPaint,
                    )
                    pdf.finishPage(page)
                }
            } finally {
                pdf.writeTo(out)
                pdf.close()
            }
        }
        return file
    }

    // ---------- layout ----------

    private fun paint(size: Float, bold: Boolean = false, italic: Boolean = false) = Paint().apply {
        isAntiAlias = true
        textSize = size
        color = android.graphics.Color.BLACK
        typeface = Typeface.create(
            Typeface.SANS_SERIF,
            when {
                bold -> Typeface.BOLD
                italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            },
        )
    }

    private val titlePaint = paint(20f, bold = true)
    private val subtitlePaint = paint(11f)
    private val headingPaint = paint(14f, bold = true)
    private val setPaint = paint(11.5f)
    private val notePaint = paint(10.5f, italic = true)
    private val sectionPaint = paint(12f, bold = true)
    private val footerPaint = paint(9f).apply { textAlign = Paint.Align.CENTER }

    /** Expand typed lines into wrapped, measured physical lines. */
    private fun layout(lines: List<WorkoutReport.Line>): List<Drawable> {
        val out = mutableListOf<Drawable>()
        lines.forEach { line ->
            when (line) {
                is WorkoutReport.Line.Title ->
                    out += wrap(line.text, titlePaint, gapAbove = 0f, indent = 0f)
                is WorkoutReport.Line.Subtitle ->
                    out += wrap(line.text, subtitlePaint, gapAbove = 4f, indent = 0f)
                is WorkoutReport.Line.ExerciseHeading ->
                    out += wrap(line.text, headingPaint, gapAbove = 10f, indent = 0f)
                is WorkoutReport.Line.SetLine ->
                    out += wrap(line.text, setPaint, gapAbove = 3f, indent = 16f)
                is WorkoutReport.Line.Note ->
                    out += wrap(line.text, notePaint, gapAbove = 3f, indent = 16f)
                is WorkoutReport.Line.SectionHeading ->
                    out += wrap(line.text, sectionPaint, gapAbove = 12f, indent = 0f)
                WorkoutReport.Line.Blank ->
                    out += Drawable("", subtitlePaint, gapAbove = 0f, lineHeight = 8f, indent = 0f)
            }
        }
        return out
    }

    /** Greedy word wrap to the content width. Always returns at least one drawable. */
    private fun wrap(text: String, paint: Paint, gapAbove: Float, indent: Float): List<Drawable> {
        val lineHeight = paint.textSize * 1.25f
        val available = contentWidth - indent
        val words = text.split(" ")
        val rows = mutableListOf<String>()
        var current = StringBuilder()
        words.forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= available || current.isEmpty()) {
                current = StringBuilder(candidate)
            } else {
                rows += current.toString()
                current = StringBuilder(word)
            }
        }
        rows += current.toString()
        return rows.mapIndexed { i, row ->
            Drawable(
                text = row,
                paint = paint,
                gapAbove = if (i == 0) gapAbove else 0f,
                lineHeight = lineHeight,
                indent = indent,
            )
        }
    }

    /** Break the measured lines into pages that fit above the footer. */
    private fun paginate(drawables: List<Drawable>): List<List<Drawable>> {
        val pages = mutableListOf<List<Drawable>>()
        var current = mutableListOf<Drawable>()
        var y = MARGIN_TOP
        drawables.forEach { d ->
            val next = y + d.gapAbove + d.lineHeight
            if (next > CONTENT_BOTTOM && current.isNotEmpty()) {
                pages += current
                current = mutableListOf()
                y = MARGIN_TOP + d.gapAbove + d.lineHeight
            } else {
                y = next
            }
            current += d
        }
        if (current.isNotEmpty() || pages.isEmpty()) pages += current
        return pages
    }
}
