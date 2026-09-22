package dev.ironlog.app.metrics

import kotlin.math.roundToInt

/**
 * Warm-up ramp generator. Given the first working weight, produce a short
 * ascending ramp of warm-up sets (tagged isWarmup, so they stay out of PR/volume math).
 *
 * Barbell: empty bar ×10, then 50% ×5, 70% ×3, 90% ×1 (each rounded to the nearest 5 lb,
 * skipping steps at/below the bar or at/above the working weight, deduped).
 * Non-barbell (machine/dumbbell/cable): 50% ×8, 75% ×5 with the same rounding/skip rules.
 */
object WarmupCalculator {

    data class WarmupSet(val weightLb: Double, val reps: Int)

    private fun round5(lb: Double): Double = ((lb / 5.0).roundToInt() * 5).toDouble()

    fun ramp(workingLb: Double, isBarbell: Boolean, barLb: Double = 45.0): List<WarmupSet> {
        if (workingLb <= 0.0) return emptyList()
        val steps = if (isBarbell) {
            listOf(WarmupSet(barLb, 10)) + listOf(0.5 to 5, 0.7 to 3, 0.9 to 1).map { (pct, reps) ->
                WarmupSet(round5(workingLb * pct), reps)
            }
        } else {
            listOf(0.5 to 8, 0.75 to 5).map { (pct, reps) ->
                WarmupSet(round5(workingLb * pct), reps)
            }
        }
        val floor = if (isBarbell) barLb else 0.0
        return steps
            .filter { it.weightLb >= floor && it.weightLb < workingLb }
            .distinctBy { it.weightLb }
    }
}
