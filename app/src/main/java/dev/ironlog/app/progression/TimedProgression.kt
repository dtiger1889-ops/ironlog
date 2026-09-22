package dev.ironlog.app.progression

/**
 * Time-based progression for TIMED / PT holds (Citations.TIMED).
 *
 * When the top hold meets the target-high hold duration for [CONSEC_SESSIONS] consecutive
 * sessions, suggest progressing the hold by [TIME_STEP_SEC] (a flagged practitioner analogue of
 * the double-progression rule; the ACSM 10-30s range is the cited basis).
 *
 * Reads numbers, returns a decision. No Room/DAO dependency. Pure Kotlin / JVM.
 */
object TimedProgression {
    const val CONSEC_SESSIONS = 2
    const val TIME_STEP_SEC = 10  // practitioner analogue (+10s)

    data class Result(
        val fires: Boolean,
        val newSeconds: Int?,
        val basis: String,
    )

    /**
     * @param topHolds chronological oldest..newest, the longest hold (seconds) per session.
     * @param targetSecHigh top of the target hold range; null = no target set -> never fires.
     */
    fun evaluate(topHolds: List<Int>, targetSecHigh: Int?, stepSec: Int = TIME_STEP_SEC): Result {
        val target = targetSecHigh ?: return Result(false, null, "no target hold set")
        if (topHolds.size < CONSEC_SESSIONS) {
            return Result(false, null, "needs $CONSEC_SESSIONS sessions")
        }
        val last = topHolds.takeLast(CONSEC_SESSIONS)
        return if (last.all { it >= target }) {
            Result(true, target + stepSec, "held >=${target}s for $CONSEC_SESSIONS sessions -> +${stepSec}s")
        } else {
            Result(false, null, "target hold not yet met twice")
        }
    }
}
