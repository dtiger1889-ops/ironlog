package dev.ironlog.app.progression

/**
 * Stall / deload detection (Citations.DELOAD).
 *
 * Heuristic: [REGRESSING_SESSIONS] consecutive regressing sessions -> suggest a deload.
 * "Regressing" = the per-session performance scalar (est-1RM of the top working set, computed
 * upstream with the <=12-rep cap) strictly decreased session-over-session.
 *
 * The "2 sessions" count is a flagged practitioner heuristic; the deload magnitude (~10% load,
 * or 40-50% volume for 5-7d) is from Bell 2023. This module produces the load-deload number.
 *
 * Reads numbers, returns a decision. No Room/DAO dependency. Pure Kotlin / JVM.
 */
object StallDetector {
    const val REGRESSING_SESSIONS = 2   // practitioner heuristic
    const val DELOAD_PCT = 0.10         // Bell 2023: ~10% load reduction

    data class Result(
        val stalled: Boolean,
        /** Suggested deload working weight (lb), or null when not stalled / no current weight. */
        val deloadWeightLb: Double?,
        val basis: String,
    )

    /**
     * @param perf chronological oldest..newest, one performance scalar per session.
     * @param currentWorkingWeight the latest working weight (for the deload target); null for
     *   bodyweight/timed where a load deload is not meaningful.
     */
    fun evaluate(perf: List<Double>, currentWorkingWeight: Double?): Result {
        // Need REGRESSING_SESSIONS deltas -> REGRESSING_SESSIONS + 1 points.
        if (perf.size < REGRESSING_SESSIONS + 1) {
            return Result(false, null, "needs ${REGRESSING_SESSIONS + 1} sessions of data")
        }
        val window = perf.takeLast(REGRESSING_SESSIONS + 1)
        val strictlyDecreasing = window.zipWithNext().all { (a, b) -> b < a }
        if (!strictlyDecreasing) {
            return Result(false, null, "no sustained regression")
        }
        val deload = currentWorkingWeight?.let { it * (1.0 - DELOAD_PCT) }
        return Result(true, deload, "$REGRESSING_SESSIONS regressing sessions -> deload ~${(DELOAD_PCT * 100).toInt()}%")
    }
}
