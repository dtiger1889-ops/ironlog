package dev.ironlog.app.data.importer

/**
 * Pure matching logic for the F1 one-time DB repair: identify the phantom SetEntry rows a
 * pre-fix import created from the previous tracker's "Rest Timer"/"Note" metadata rows, without ever
 * touching a real set.  Android-free so a JVM test can prove the matching rules.
 */
object ImportedMetadataRepair {

    /** Minimal projection of a stored set row, enough to fingerprint junk. */
    data class StoredSet(
        val id: Long,
        val setOrder: Int,
        val weightLb: Double?,
        val reps: Int?,
        val seconds: Int?,
        val distanceMeters: Double?,
    )

    /**
     * Returns the ids of stored sets (one workout+exercise pairing) that are import junk for
     * [metadataRows].
     *
     * Matching rules:
     * - A candidate must be value-empty (no weight, no reps, no distance) — real sets always
     *   carry at least one value.
     * - For a NON-timed exercise ([timedExercise] = false), any value-empty seconds-only row is
     *   junk by definition (a seconds-only set is meaningless on a weighted/bodyweight/distance
     *   exercise), so all of them are returned regardless of setOrder — this also catches rows
     *   whose fabricated setOrder was later rewritten by an in-app edit.
     * - For a TIMED exercise, real sets are ALSO seconds-only, so only the exact fingerprint is
     *   trusted: setOrder equals the legacy fabricated order AND seconds equal the metadata
     *   row's seconds.  Each stored row can satisfy at most one metadata row.
     * - Note rows (seconds == null) are matched by exact fingerprint only (all-null row at the
     *   fabricated setOrder), for either exercise kind.
     */
    fun junkSetIds(
        metadataRows: List<ParsedMetadataRow>,
        stored: List<StoredSet>,
        timedExercise: Boolean,
    ): List<Long> {
        val valueEmpty = stored.filter {
            it.weightLb == null && it.reps == null && it.distanceMeters == null
        }
        val junk = LinkedHashSet<Long>()

        if (!timedExercise) {
            valueEmpty.filter { it.seconds != null }.forEach { junk += it.id }
        } else {
            val pool = valueEmpty.filter { it.seconds != null }.toMutableList()
            metadataRows.filter { it.seconds != null }.forEach { meta ->
                val idx = pool.indexOfFirst {
                    it.setOrder == meta.legacySetOrder && it.seconds == meta.seconds
                }
                if (idx >= 0) junk += pool.removeAt(idx).id
            }
        }

        // Note rows: all-null shape at the exact fabricated order.
        val notePool = valueEmpty.filter { it.seconds == null }.toMutableList()
        metadataRows.filter { it.seconds == null }.forEach { meta ->
            val idx = notePool.indexOfFirst { it.setOrder == meta.legacySetOrder }
            if (idx >= 0) junk += notePool.removeAt(idx).id
        }

        return junk.toList()
    }
}
