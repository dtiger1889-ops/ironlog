package dev.ironlog.app.progression

import dev.ironlog.app.data.Exercise

/**
 * Lift-class load increments (roadmap §M4 / §8).
 *
 * These seed values are a PRACTITIONER MODEL anchored to the ACSM 2-10% load-step rule
 * (Citations.DOUBLE_PROGRESSION). They are NOT derived from the user's history.
 *
 * Classification uses free-exercise-db metadata only (mechanic + equipment + primaryMuscles),
 * never his logged loads.
 *
 * Pure Kotlin / JVM -- no Android dependencies.
 */
object IncrementSeeder {
    const val COMPOUND_LOWER_LB = 10.0
    const val COMPOUND_UPPER_LB = 5.0
    const val ISOLATION_LB = 5.0

    /** Lower-body muscle tokens (free-exercise-db vocabulary). */
    val LOWER_MUSCLES = setOf(
        "quadriceps", "hamstrings", "glutes", "calves", "abductors", "adductors",
    )

    /**
     * The seeded numeric increment in lb, or null = "machine: go up one pin/level"
     * (no clean numeric step on a pin stack).
     *
     * An explicit per-exercise [Exercise.incrementLb] override always wins.
     */
    fun seedIncrementLb(exercise: Exercise): Double? {
        exercise.incrementLb?.let { return it }

        val equip = exercise.equipment?.lowercase().orEmpty()
        if (equip.contains("machine")) return null  // pin stack -> "next pin"

        val lower = exercise.primaryMuscles?.any { it.lowercase() in LOWER_MUSCLES } == true
        return when {
            exercise.mechanic == "compound" && lower -> COMPOUND_LOWER_LB
            exercise.mechanic == "compound" -> COMPOUND_UPPER_LB
            else -> ISOLATION_LB  // isolation or unknown mechanic -> small step (+ microload)
        }
    }
}
