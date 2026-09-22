package dev.ironlog.app.progression

import kotlin.math.abs

/**
 * Double progression + the NSCA 2-for-2 rule (Citations.DOUBLE_PROGRESSION).
 *
 * 2-for-2: when the top working set clears the top of the rep range by [OVER_TARGET_REPS]
 * reps for [CONSEC_SESSIONS] consecutive sessions at the SAME working weight, add load.
 *
 * The suggested step respects the ACSM [MAX_STEP_PCT] (2-10%) cap: it never exceeds 10% of the
 * current working weight, even if the seeded lift-class increment is larger.
 *
 * This module READS performance numbers and RETURNS a decision. It never writes a SetEntry or
 * touches a Template (M4 hard guardrail) -- it has no Room/DAO dependency at all.
 *
 * Pure Kotlin / JVM.
 */
object DoubleProgression {
    const val OVER_TARGET_REPS = 2
    const val CONSEC_SESSIONS = 2
    const val MAX_STEP_PCT = 0.10  // ACSM 2-10%: cap the step at 10% of working weight

    /** One session's top working set (warm-ups already excluded upstream). */
    data class TopSet(val weightLb: Double, val reps: Int)

    data class Result(
        val fires: Boolean,
        /** Suggested new working weight (lb), or null when the rule does not fire. */
        val newWeightLb: Double?,
        val basis: String,
    )

    /**
     * Does the 2-for-2 rep criterion hold? (rep side only -- weight-agnostic helper for tests/UI.)
     *
     * @param topSets chronological oldest..newest, the top working set per session.
     */
    fun ready(topSets: List<TopSet>, targetRepsHigh: Int): Boolean {
        if (topSets.size < CONSEC_SESSIONS) return false
        val last = topSets.takeLast(CONSEC_SESSIONS)
        val base = last.last().weightLb
        val allOver = last.all { it.reps >= targetRepsHigh + OVER_TARGET_REPS }
        val sameWeight = last.all { abs(it.weightLb - base) < 0.001 }
        return allOver && sameWeight
    }

    /**
     * Evaluate the full 2-for-2 decision including the capped load step.
     *
     * @param increment the seeded lift-class increment in lb (null = machine "next pin": the rule
     *   can still fire by reps, but no numeric new weight is produced).
     */
    fun evaluate(topSets: List<TopSet>, targetRepsHigh: Int, increment: Double?): Result {
        if (!ready(topSets, targetRepsHigh)) {
            return Result(false, null, "needs 2 consecutive sets >= ${targetRepsHigh + OVER_TARGET_REPS} reps")
        }
        val base = topSets.last().weightLb
        if (increment == null) {
            return Result(true, null, "2-for-2 at ${fmt(base)} lb -- go up one machine level")
        }
        val cappedStep = increment.coerceAtMost(base * MAX_STEP_PCT)
        return Result(true, base + cappedStep, "2-for-2 at ${fmt(base)} lb -> +${fmt(cappedStep)} lb (<=10% cap)")
    }

    private fun fmt(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
}
