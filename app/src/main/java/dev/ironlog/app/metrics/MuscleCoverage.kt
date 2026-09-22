package dev.ironlog.app.metrics

import dev.ironlog.app.data.Exercise
import dev.ironlog.app.data.SetEntry
import dev.ironlog.app.data.Workout

/**
 * Muscle coverage-lite: per-muscle "days since last trained" and trailing-7-day
 * direct (1.0) / indirect (0.5) set count.
 *
 * Only exercises with confirmed primaryMuscles metadata contribute.
 * Exercises with null primaryMuscles are counted in "unmapped" and excluded from rollups.
 *
 * NO target line in M1a -- MEV/MAV bands are M4.
 *
 * Pure Kotlin / JVM -- no Android dependencies.
 */
object MuscleCoverage {

    data class MuscleStats(
        val muscle: String,
        /** Epoch millis of the most recent set that trained this muscle (null = never). */
        val lastTrainedMillis: Long?,
        /** Direct sets (primaryMuscle hit) in the trailing 7 days. */
        val directSets7d: Double,
        /** Indirect sets (secondaryMuscle hit) in the trailing 7 days. */
        val indirectSets7d: Double,
        /** Total weighted sets in trailing 7 days (direct * 1.0 + indirect * 0.5). */
        val totalWeighted7d: Double = directSets7d + indirectSets7d * 0.5,
    )

    data class CoverageResult(
        val muscles: List<MuscleStats>,
        /** Count of exercises with null primaryMuscles (excluded from rollups). */
        val unmappedExerciseCount: Int,
    )

    /**
     * Compute muscle coverage from workout history.
     *
     * @param sets All set entries.
     * @param workouts All workouts (for startTime lookup by workoutId).
     * @param exercises All exercises (for muscle metadata).
     * @param nowMillis Current time in epoch millis (for "days since" and 7-day window).
     */
    fun compute(
        sets: List<SetEntry>,
        workouts: List<Workout>,
        exercises: List<Exercise>,
        nowMillis: Long,
    ): CoverageResult {
        val workoutTimeById = workouts.associate { it.id to it.startTime }
        val exerciseById = exercises.associateBy { it.id }

        val sevenDaysAgo = nowMillis - 7L * 24 * 60 * 60 * 1000

        // Track per muscle: lastTrainedMillis, direct7d, indirect7d
        data class Acc(
            var lastMillis: Long? = null,
            var direct7d: Double = 0.0,
            var indirect7d: Double = 0.0,
        )
        val muscleAcc = mutableMapOf<String, Acc>()
        var unmapped = 0

        // Count exercises that have no primary muscles (count distinct exercise IDs with no map)
        val exerciseIdsInSets = sets.map { it.exerciseId }.toSet()
        for (exId in exerciseIdsInSets) {
            val ex = exerciseById[exId] ?: continue
            if (ex.primaryMuscles.isNullOrEmpty()) unmapped++
        }

        for (set in sets) {
            val ex = exerciseById[set.exerciseId] ?: continue
            val primary = ex.primaryMuscles ?: continue
            if (primary.isEmpty()) continue

            val workoutTime = workoutTimeById[set.workoutId] ?: continue
            val inWindow = workoutTime >= sevenDaysAgo

            for (muscle in primary) {
                val acc = muscleAcc.getOrPut(muscle) { Acc() }
                if (acc.lastMillis == null || workoutTime > acc.lastMillis!!) {
                    acc.lastMillis = workoutTime
                }
                if (inWindow) acc.direct7d += 1.0
            }

            val secondary = ex.secondaryMuscles ?: emptyList()
            for (muscle in secondary) {
                val acc = muscleAcc.getOrPut(muscle) { Acc() }
                if (acc.lastMillis == null || workoutTime > acc.lastMillis!!) {
                    acc.lastMillis = workoutTime
                }
                if (inWindow) acc.indirect7d += 1.0
            }
        }

        val stats = muscleAcc.map { (muscle, acc) ->
            MuscleStats(
                muscle = muscle,
                lastTrainedMillis = acc.lastMillis,
                directSets7d = acc.direct7d,
                indirectSets7d = acc.indirect7d,
            )
        }.sortedBy { it.muscle }

        return CoverageResult(muscles = stats, unmappedExerciseCount = unmapped)
    }

    /** Days since [epochMillis], rounded down. Returns null if epochMillis is null. */
    fun daysSince(epochMillis: Long?, nowMillis: Long): Int? {
        epochMillis ?: return null
        val diffMs = nowMillis - epochMillis
        return (diffMs / (24L * 60 * 60 * 1000)).toInt().coerceAtLeast(0)
    }
}
