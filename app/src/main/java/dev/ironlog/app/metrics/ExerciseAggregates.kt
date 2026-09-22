package dev.ironlog.app.metrics

import dev.ironlog.app.data.SetEntry

/**
 * Per-exercise aggregate statistics computed from historical set entries.
 * Pure Kotlin / JVM -- no Android dependencies.
 */
data class ExerciseAggregates(
    /** Heaviest weight ever logged for this exercise (null = no weighted sets). */
    val maxWeightLb: Double?,
    /** Highest single-set volume (weightLb * reps) ever logged (null = no weighted sets). */
    val maxVolumeLb: Double?,
    /**
     * Best estimated 1-RM per rep count 1..12, keyed by rep count.
     * Computed using OneRepMax.hybrid().  Rep counts > 12 are excluded per the cap rule.
     * A rep count with no logged data is absent from the map.
     */
    val best1RMByReps: Map<Int, Double>,
) {
    companion object {
        /**
         * Compute aggregates from a list of [SetEntry] for one exercise.
         * Non-weighted sets (null weightLb) are excluded from weight/volume/1RM calcs.
         * TODO(M2): exclude warm-up sets once isWarmup column lands.
         */
        fun compute(sets: List<SetEntry>): ExerciseAggregates {
            val weightedSets = sets.filter { it.weightLb != null && it.reps != null }

            val maxWeightLb = weightedSets.maxOfOrNull { it.weightLb!! }
            val maxVolumeLb = weightedSets.maxOfOrNull { (it.weightLb!! * (it.reps ?: 0)) }

            // Best 1RM estimate per rep count 1..12
            val best1RMByReps = mutableMapOf<Int, Double>()
            for (set in weightedSets) {
                val r = set.reps ?: continue
                val w = set.weightLb ?: continue
                val est = OneRepMax.hybrid(w, r) ?: continue
                val current = best1RMByReps[r]
                if (current == null || est > current) {
                    best1RMByReps[r] = est
                }
            }

            return ExerciseAggregates(
                maxWeightLb = maxWeightLb,
                maxVolumeLb = maxVolumeLb,
                best1RMByReps = best1RMByReps,
            )
        }
    }
}
