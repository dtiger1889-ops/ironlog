package dev.ironlog.app.gym

import dev.ironlog.app.data.Exercise
import dev.ironlog.app.data.MuscleVolumeTarget
import dev.ironlog.app.data.SetEntry
import dev.ironlog.app.data.Workout
import dev.ironlog.app.metrics.MuscleCoverage
import dev.ironlog.app.progression.VolumeLandmarks

/**
 * M5: Gym & Muscle Assistant — four pure-Kotlin computations.
 *
 * (b) findForMuscle   — catalog filtered to a target muscle + active gym equipment
 * (c) rankAlternatives — busy-station swap scored by the M5 formula
 * (d) coverageStatus  — per-muscle neglect classification (consumes M4 VolumeLandmarks)
 *
 * No Room/Android dependencies. All four are JVM-testable.
 * Coverage thresholds come exclusively from MuscleVolumeTarget (practitioner model) —
 * never derived from logged rep counts (M4 hard guardrail).
 */
object MuscleAssistant {

    // ── (b) Muscle-target finder ─────────────────────────────────────────────

    /**
     * Exercises whose [Exercise.primaryMuscles] intersect [targetMuscle], filtered to those
     * available at the active gym, sorted: in-profile > compound > logged-frequency.
     *
     * @param availableEquipment tokens enabled in the active gym profile.
     * @param exerciseFrequency  exerciseId → total logged set count (sort tie-break only).
     */
    fun findForMuscle(
        exercises: List<Exercise>,
        targetMuscle: String,
        availableEquipment: Set<String>,
        exerciseFrequency: Map<Long, Int> = emptyMap(),
    ): List<Exercise> {
        val target = targetMuscle.lowercase()
        return exercises
            .filter { ex ->
                ex.primaryMuscles?.any { it.lowercase() == target } == true &&
                    GymMetadata.isAvailable(ex.equipment, availableEquipment)
            }
            .sortedWith(
                compareByDescending<Exercise> { ex ->
                    // In-profile equipment scores higher than always-available (body only / null)
                    if (ex.equipment != null && ex.equipment !in GymMetadata.ALWAYS_AVAILABLE_EQUIPMENT) 1 else 0
                }
                    .thenByDescending { ex ->
                        // Compound before isolation
                        if (ex.mechanic?.lowercase() == "compound") 1 else 0
                    }
                    .thenByDescending { ex -> exerciseFrequency[ex.id] ?: 0 },
            )
    }

    // ── (c) Busy-station swap ────────────────────────────────────────────────

    data class ScoredAlternative(val exercise: Exercise, val score: Double)

    /**
     * Rank exercises that can substitute for [source] when its station is busy.
     *
     * Score = 3·primaryJaccard + 2·sameMovementPattern + 1·sameForce + 1·sameMechanic
     *         + 0.5·secondaryOverlap
     * Tie-break: logged frequency (descending).
     *
     * Excludes: [source] itself, exercises on the same equipment as [source] (that station
     * is busy), and exercises not available at the active gym.
     */
    fun rankAlternatives(
        source: Exercise,
        candidates: List<Exercise>,
        availableEquipment: Set<String>,
        exerciseFrequency: Map<Long, Int> = emptyMap(),
    ): List<ScoredAlternative> {
        val sourcePrimary = source.primaryMuscles?.map { it.lowercase() }?.toSet() ?: emptySet()
        val sourceSecondary = source.secondaryMuscles?.map { it.lowercase() }?.toSet() ?: emptySet()

        return candidates
            .filter { ex ->
                ex.id != source.id &&
                    ex.equipment != source.equipment &&   // different station
                    GymMetadata.isAvailable(ex.equipment, availableEquipment) &&
                    // A real substitute must train the same primary muscle -- otherwise the swap
                    // list balloons to every available exercise (most scoring ~0).
                    ex.primaryMuscles?.any { it.lowercase() in sourcePrimary } == true
            }
            .map { ex ->
                val exPrimary = ex.primaryMuscles?.map { it.lowercase() }?.toSet() ?: emptySet()
                val exSecondary = ex.secondaryMuscles?.map { it.lowercase() }?.toSet() ?: emptySet()

                val primaryJaccard = jaccard(sourcePrimary, exPrimary)
                val sameMovement = if (
                    source.movementPattern != null &&
                    ex.movementPattern != null &&
                    source.movementPattern == ex.movementPattern
                ) 1.0 else 0.0
                val sameForce = if (
                    source.force != null &&
                    ex.force != null &&
                    source.force.lowercase() == ex.force.lowercase()
                ) 1.0 else 0.0
                val sameMechanic = if (
                    source.mechanic != null &&
                    ex.mechanic != null &&
                    source.mechanic.lowercase() == ex.mechanic.lowercase()
                ) 1.0 else 0.0
                val secondaryOverlap = jaccardPartial(sourceSecondary, exSecondary)

                val score = 3.0 * primaryJaccard +
                    2.0 * sameMovement +
                    1.0 * sameForce +
                    1.0 * sameMechanic +
                    0.5 * secondaryOverlap

                ScoredAlternative(ex, score)
            }
            .sortedWith(
                compareByDescending<ScoredAlternative> { it.score }
                    .thenByDescending { exerciseFrequency[it.exercise.id] ?: 0 },
            )
    }

    // ── (d) Muscle coverage / neglect ────────────────────────────────────────

    data class MuscleStatus(
        val muscle: String,
        /** Weekly volume band vs MEV/MAV/MRV (from MuscleVolumeTarget, not from history). */
        val band: VolumeLandmarks.Band,
        /** Days since any set trained this muscle (null = never in history). */
        val daysSince: Int?,
        /**
         * True when the muscle has ONLY indirect (secondary) hits and zero direct hits
         * over [windowDays]. Not about reps — about whether it ever appeared as a primary
         * muscle in this window.
         */
        val neverIsolated: Boolean,
        /** True when daysSince > STALE_THRESHOLD_DAYS (any training, direct or indirect). */
        val stale: Boolean,
    )

    const val NEVER_ISOLATED_WINDOW_DAYS = 28L
    const val STALE_THRESHOLD_DAYS = 10

    /**
     * Per-muscle coverage status using the trailing 7-day window for volume bands and a
     * longer [NEVER_ISOLATED_WINDOW_DAYS]-day window for the never-isolated flag.
     *
     * @param targets map of muscle → MuscleVolumeTarget (from the practitioner model seed).
     */
    fun coverageStatus(
        sets: List<SetEntry>,
        workouts: List<Workout>,
        exercises: List<Exercise>,
        targets: Map<String, MuscleVolumeTarget>,
        nowMillis: Long,
    ): List<MuscleStatus> {
        // Weekly volume (7-day window) via M4 VolumeLandmarks — the single authoritative source
        val weeklyCounts = VolumeLandmarks.weeklySets(sets, workouts, exercises, nowMillis)
            .associateBy { it.muscle }

        // Recency via M1 MuscleCoverage
        val coverage = MuscleCoverage.compute(sets, workouts, exercises, nowMillis)
        val recencyByMuscle = coverage.muscles.associateBy { it.muscle }

        // Never-isolated check: 28-day window, zero direct sets = never isolated
        val neverIsolatedWindow = nowMillis - NEVER_ISOLATED_WINDOW_DAYS * 24L * 60 * 60 * 1000
        val directSets28d = directSetsInWindow(sets, workouts, exercises, neverIsolatedWindow)

        val allMuscles = (weeklyCounts.keys + recencyByMuscle.keys + directSets28d.keys).toSortedSet()

        return allMuscles.map { muscle ->
            val weekly = weeklyCounts[muscle]
            val weightedSets = weekly?.weightedSets ?: 0.0
            val target = targets[muscle]
            val band = VolumeLandmarks.classify(weightedSets, target)

            val recency = recencyByMuscle[muscle]
            val daysSince = MuscleCoverage.daysSince(recency?.lastTrainedMillis, nowMillis)
            val stale = daysSince != null && daysSince > STALE_THRESHOLD_DAYS

            val directCount = directSets28d[muscle] ?: 0
            val hasAnyActivity = (recency?.lastTrainedMillis ?: 0L) >= neverIsolatedWindow
            val neverIsolated = hasAnyActivity && directCount == 0

            MuscleStatus(
                muscle = muscle,
                band = band,
                daysSince = daysSince,
                neverIsolated = neverIsolated,
                stale = stale,
            )
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        val intersection = (a intersect b).size.toDouble()
        val union = (a union b).size.toDouble()
        return if (union == 0.0) 0.0 else intersection / union
    }

    /** Partial Jaccard: intersection / size-of-smaller-set (avoids penalizing large secondary lists). */
    private fun jaccardPartial(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = (a intersect b).size.toDouble()
        val denom = minOf(a.size, b.size).toDouble()
        return if (denom == 0.0) 0.0 else intersection / denom
    }

    /** Count direct (primary-muscle) sets per muscle within the windowStart to nowMillis range. */
    private fun directSetsInWindow(
        sets: List<SetEntry>,
        workouts: List<Workout>,
        exercises: List<Exercise>,
        windowStart: Long,
    ): Map<String, Int> {
        val workoutTimeById = workouts.associate { it.id to it.startTime }
        val exerciseById = exercises.associateBy { it.id }
        val counts = mutableMapOf<String, Int>()
        for (set in sets) {
            if (set.isWarmup) continue
            val time = workoutTimeById[set.workoutId] ?: continue
            if (time < windowStart) continue
            val ex = exerciseById[set.exerciseId] ?: continue
            val primary = ex.primaryMuscles ?: continue
            for (m in primary) {
                counts[m.lowercase()] = (counts[m.lowercase()] ?: 0) + 1
            }
        }
        return counts
    }
}
