package dev.ironlog.app.progression

import dev.ironlog.app.data.Exercise
import dev.ironlog.app.data.MuscleVolumeTarget
import dev.ironlog.app.data.SetEntry
import dev.ironlog.app.data.Workout

/**
 * THE single source of the trailing-window per-muscle weekly-set math (roadmap §3 de-dup rule).
 *
 * Direct (primaryMuscle hit) sets count [DIRECT_WEIGHT]; indirect (secondaryMuscle hit) sets
 * count [INDIRECT_WEIGHT]. M3's weekly-volume card and M5's coverage tracker CONSUME this module;
 * no other file re-implements the direct-1.0 / indirect-0.5 weighting. (M1's MuscleCoverage owns
 * only the orthogonal "days-since-trained" recency, not the weighting.)
 *
 * Warm-up sets are excluded (M2 contract). Exercises with no primaryMuscles are excluded.
 *
 * EVIDENCE DISCIPLINE: the cited >=10-set weekly floor (Citations.WEEKLY_VOLUME_FLOOR) is the
 * peer-reviewed line; the MEV/MAV/MRV/MV band comes from MuscleVolumeTarget, seeded from the
 * bundled volume_landmarks.json (a labeled practitioner model), NEVER from his rep distribution.
 *
 * Because each qualifying set contributes a fixed 1.0 / 0.5 regardless of its rep count, shuffling
 * his reps cannot change any weekly-set count or band -- the self-derivation guard holds by
 * construction (and is asserted by VolumeLandmarksTest).
 *
 * Pure Kotlin / JVM -- no Android dependencies.
 */
object VolumeLandmarks {
    const val DIRECT_WEIGHT = 1.0
    const val INDIRECT_WEIGHT = 0.5

    /** Schoenfeld 2017 cited floor: >=10 sets/muscle/week. */
    const val CITED_WEEKLY_FLOOR = 10.0

    const val DEFAULT_WINDOW_DAYS = 7L

    data class WeeklySetCount(
        val muscle: String,
        val directSets: Double,
        val indirectSets: Double,
    ) {
        val weightedSets: Double get() = directSets * DIRECT_WEIGHT + indirectSets * INDIRECT_WEIGHT
    }

    /** Where a muscle's weekly weighted-set count sits relative to its landmarks. */
    enum class Band {
        BELOW_MV,        // under maintenance volume -- losing ground
        MV_TO_MEV,       // maintaining but below the minimum effective (growth) volume
        MEV_TO_MAV,      // productive growth range
        MAV_TO_MRV,      // high but tolerable
        ABOVE_MRV,       // beyond recoverable -- likely junk volume
        NO_TARGET,       // no landmark row for this muscle
    }

    /**
     * Trailing-[windowDays] per-muscle weighted set counts.
     *
     * @param nowMillis current time (the window is [nowMillis - windowDays, nowMillis]).
     */
    fun weeklySets(
        sets: List<SetEntry>,
        workouts: List<Workout>,
        exercises: List<Exercise>,
        nowMillis: Long,
        windowDays: Long = DEFAULT_WINDOW_DAYS,
    ): List<WeeklySetCount> {
        val workoutTimeById = workouts.associate { it.id to it.startTime }
        val exerciseById = exercises.associateBy { it.id }
        val windowStart = nowMillis - windowDays * 24L * 60 * 60 * 1000

        val direct = mutableMapOf<String, Double>()
        val indirect = mutableMapOf<String, Double>()

        for (set in sets) {
            if (set.isWarmup) continue  // M2 contract: warm-ups never count toward volume
            val ex = exerciseById[set.exerciseId] ?: continue
            val primary = ex.primaryMuscles ?: continue
            if (primary.isEmpty()) continue
            val time = workoutTimeById[set.workoutId] ?: continue
            if (time < windowStart) continue

            for (m in primary) direct[m] = (direct[m] ?: 0.0) + 1.0
            for (m in (ex.secondaryMuscles ?: emptyList())) {
                indirect[m] = (indirect[m] ?: 0.0) + 1.0
            }
        }

        val muscles = (direct.keys + indirect.keys).toSortedSet()
        return muscles.map { m ->
            WeeklySetCount(
                muscle = m,
                directSets = direct[m] ?: 0.0,
                indirectSets = indirect[m] ?: 0.0,
            )
        }
    }

    /** Classify a weighted weekly-set count against a muscle's landmark row. */
    fun classify(weightedSets: Double, target: MuscleVolumeTarget?): Band {
        if (target == null) return Band.NO_TARGET
        return when {
            weightedSets < target.mv -> Band.BELOW_MV
            weightedSets < target.mev -> Band.MV_TO_MEV
            weightedSets < target.mav -> Band.MEV_TO_MAV
            weightedSets < target.mrv -> Band.MAV_TO_MRV
            else -> Band.ABOVE_MRV
        }
    }

    /** True when the muscle is below the cited peer-reviewed weekly floor. */
    fun belowCitedFloor(weightedSets: Double): Boolean = weightedSets < CITED_WEEKLY_FLOOR
}
