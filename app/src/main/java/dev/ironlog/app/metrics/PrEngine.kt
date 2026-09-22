package dev.ironlog.app.metrics

import dev.ironlog.app.data.SetEntry

/**
 * PR (personal record) detection engine.
 *
 * On finish, for each set in the draft, determines if it is a PR by comparing against all
 * previously logged sets for that exercise.
 *
 * A set is a PR when its estimated 1RM (hybrid) exceeds the best historical 1RM across all
 * rep counts 1..12 for that exercise.  This covers "same weight, more reps" and "more weight,
 * same reps" in one number.
 *
 * Non-weighted sets (no weightLb or no reps) and warm-up sets (isWarmup=true) are never PRs.
 *
 * Pure Kotlin / JVM -- no Android dependencies.
 */
object PrEngine {

    /**
     * Tag [newSets] with isPR based on [historicalSets] (all sets for the same exercise
     * persisted BEFORE this workout).
     *
     * Warm-up sets (isWarmup=true) are always isPR=false and excluded from the session best,
     * so they cannot raise the bar for working sets either.
     *
     * Returns a new list with isPR set correctly on each element.
     */
    fun tagPRs(
        newSets: List<SetEntry>,
        historicalSets: List<SetEntry>,
    ): List<SetEntry> {
        // Exclude warm-ups from historical baseline too
        val workingHistory = historicalSets.filter { !it.isWarmup }
        val historicalAggs = ExerciseAggregates.compute(workingHistory)
        val bestHistorical1RM = historicalAggs.best1RMByReps.values.maxOrNull()

        // Track the running best 1RM within the current session so sets within
        // the same workout can PR against each other (first set to hit that 1RM is the PR)
        var sessionBest1RM = bestHistorical1RM

        return newSets.map { set ->
            val w = set.weightLb
            val r = set.reps
            if (set.isWarmup || w == null || r == null || r > OneRepMax.REP_CAP) {
                set.copy(isPR = false)
            } else {
                val est1RM = OneRepMax.hybrid(w, r)
                val isPR = if (est1RM != null) {
                    val baseline = sessionBest1RM
                    val isNew = baseline == null || est1RM > baseline
                    if (isNew) sessionBest1RM = est1RM
                    isNew
                } else {
                    false
                }
                set.copy(isPR = isPR)
            }
        }
    }
}
