package dev.ironlog.app.data.importer

/**
 * Minimal RFC-4180-style CSV tokenizer for previous-tracker exports.
 *
 * That export uses ';' as the delimiter and wraps every field in double quotes; quoted fields
 * may themselves contain ';', newlines, or escaped quotes ("").  A naive line/split would
 * corrupt Notes fields, so we tokenize char-by-char respecting quote state.  Pure Kotlin
 * (no Android deps) so it runs in plain JVM unit tests against the real export.
 */
object HistoryCsvParser {

    private const val DELIMITER = ';'
    private const val QUOTE = '"'

    /** Parse the whole document into rows of string fields (row 0 is the header). */
    fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        val n = text.length

        fun endField() {
            row.add(field.toString())
            field.setLength(0)
        }
        fun endRow() {
            endField()
            rows.add(row)
            row = ArrayList()
        }

        while (i < n) {
            val c = text[i]
            if (inQuotes) {
                if (c == QUOTE) {
                    if (i + 1 < n && text[i + 1] == QUOTE) {
                        field.append(QUOTE) // escaped ""
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    QUOTE -> inQuotes = true
                    DELIMITER -> endField()
                    '\n' -> endRow()
                    '\r' -> { /* swallow; CRLF handled by the \n branch */ }
                    else -> field.append(c)
                }
            }
            i++
        }
        // Flush a trailing row that has no terminating newline.
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}
