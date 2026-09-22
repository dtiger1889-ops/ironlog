package dev.ironlog.app.gym

/**
 * M5: static helpers for deriving movement pattern and deltoid-head from free-exercise-db
 * fields (name + force + primaryMuscles). No Room/Android dependencies — JVM-testable.
 *
 * These functions run once at exercise-seeding / reconciliation time and write the results
 * into Exercise.movementPattern + Exercise.deltHead. The derivation replaces hand-curation:
 * see specs/decisions/m5-movement-pattern-derivation.md.
 */
object GymMetadata {

    /** All equipment tokens used in the bundled free-exercise-db (exercises.json). */
    val ALL_EQUIPMENT_TOKENS: Set<String> = setOf(
        "bands", "barbell", "body only", "cable", "dumbbell",
        "e-z curl bar", "exercise ball", "foam roll",
        "kettlebells", "machine", "medicine ball", "other",
    )

    /**
     * Equipment that is always available regardless of gym profile.
     * "body only" + "other" + null (no equipment listed) require no dedicated station.
     */
    val ALWAYS_AVAILABLE_EQUIPMENT: Set<String?> = setOf("body only", "other", null)

    /**
     * Derive a movement-pattern token from exercise metadata.
     *
     * Returned tokens (stable — swap-score formula depends on equality):
     *   horizontal-push, incline-push, decline-push, horizontal-fly,
     *   vertical-push, shoulder-abduction,
     *   horizontal-pull, vertical-pull,
     *   elbow-flexion, elbow-extension,
     *   knee-extension, knee-flexion,
     *   hip-hinge, hip-extension, hip-abduction, hip-flexion,
     *   ankle-plantarflexion, spinal-flexion, scapular-elevation,
     *   carry, cardio, stretch
     *
     * Returns null when the exercise can't be classified (edge cases like "battle ropes").
     */
    fun deriveMovementPattern(
        name: String,
        force: String?,
        primaryMuscles: List<String>?,
    ): String? {
        val n = name.lowercase()
        val muscles = primaryMuscles?.map { it.lowercase() } ?: emptyList()

        // Cardio
        if (n.any("treadmill", "cycling", "elliptical", "stationary bike")) return "cardio"
        if (muscles.any("cardiovascular system")) return "cardio"

        // Carry
        if (n.any("farmer", "suitcase carry", "carry")) return "carry"

        // Stretch / static
        if (force == "static" || n.any("stretch", "mobility")) return "stretch"

        // Ankle
        if (muscles.any("calves") || n.any("calf raise", "heel raise", "plantarflexion")) return "ankle-plantarflexion"

        // Hip abduction (lateral-band walks, hip abductor machine, side lunges)
        if (muscles.any("abductors") ||
            n.any("hip abduction", "abductor", "monster walk", "lateral walk", "band walk",
                "clamshell", "side lunge", "lying hip abduction")
        ) return "hip-abduction"

        // Hip hinge vs hip extension — hinge = loading the posterior chain through hip flexion
        if (n.any("deadlift", "romanian", "rdl", "good morning", "45 degree", "back extension")) {
            return if (n.any("hip thrust", "glute bridge")) "hip-extension" else "hip-hinge"
        }
        if (muscles.any("glutes") && n.any("hip thrust", "glute bridge", "glute drive")) return "hip-extension"
        if (n.any("kickback") && muscles.any("glutes")) return "hip-extension"

        // Knee flexion
        if (n.any("leg curl") || (muscles.any("hamstrings") && n.any("curl"))) return "knee-flexion"

        // Knee extension (squats, leg press, lunges, leg extension)
        if (muscles.any("quadriceps") ||
            n.any("squat", "leg press", "lunge", "split squat", "step up", "leg extension", "hack squat")
        ) return "knee-extension"

        // Core — hip-flexion (leg raises) vs spinal-flexion (sit-ups/crunches)
        if (n.any("leg raise", "knee raise", "straight leg raise", "flat leg raise", "hanging knee", "hanging leg")) return "hip-flexion"
        if (muscles.any("abdominals") && n.any("sit up", "situp", "crunch", "ab roll")) return "spinal-flexion"

        // Scapular elevation
        if (n.any("shrug")) return "scapular-elevation"

        // Upper body push — chest primary
        if (force == "push" && muscles.any("chest")) {
            return when {
                n.any("fly", "flye") -> "horizontal-fly"
                n.any("incline") -> "incline-push"
                n.any("decline") -> "decline-push"
                else -> "horizontal-push"
            }
        }

        // Upper body push — shoulder primary
        if (force == "push" && muscles.any("shoulders")) {
            return when {
                n.any("lateral raise", "side lateral", "side raise") -> "shoulder-abduction"
                n.any("front raise") -> "shoulder-abduction"
                n.any("incline") -> "incline-push"
                else -> "vertical-push"
            }
        }

        // Elbow extension — triceps dominant push or isolation
        if (muscles.any("triceps") && (force == "push" || n.any("pushdown", "push-down", "extension", "skull", "kickback", "dip"))) {
            return "elbow-extension"
        }

        // Elbow flexion — biceps dominant
        if (muscles.any("biceps") && (force == "pull" || n.any("curl"))) return "elbow-flexion"

        // Vertical pull — lats primary (pulldowns, pull-ups, chin-ups)
        if (muscles.any("lats")) {
            return if (n.any("pulldown", "pull-down", "pull up", "pull-up", "chin up", "chin-up")) "vertical-pull" else "horizontal-pull"
        }

        // Vertical pull — shoulder-primary upright rows
        if (force == "pull" && muscles.any("shoulders") && n.any("upright row", "upright barbell")) return "vertical-pull"

        // Horizontal pull — shoulder isolation (rear delt / reverse flyes / face pulls)
        if (force == "pull" && muscles.any("shoulders")) {
            return if (n.any("lateral", "side lateral")) "shoulder-abduction" else "horizontal-pull"
        }

        // Horizontal pull — back-primary rows
        if (force == "pull" && muscles.any("middle back", "traps", "lower back")) {
            return if (n.any("upright", "pull up", "pulldown", "pull-up")) "vertical-pull" else "horizontal-pull"
        }

        return null
    }

    /**
     * Derive which deltoid head is primary.
     *
     * Only non-null when [primaryMuscles] contains "shoulders". The three tokens
     * ("front", "lateral", "rear") match the swap-filter vocabulary used by MuscleAssistant.
     */
    fun deriveDeltHead(name: String, primaryMuscles: List<String>?): String? {
        if (primaryMuscles?.map { it.lowercase() }?.contains("shoulders") != true) return null
        val n = name.lowercase()
        return when {
            n.any("lateral raise", "side lateral", "lateral raise", "side raise", "upright row", "upright barbell") -> "lateral"
            n.any("rear", "face pull", "reverse fly", "reverse flye", "bent over", "lying rear", "prone", "low pulley") -> "rear"
            n.any("front raise", "front delt", "front cable") -> "front"
            // Pressing movements (military, overhead, shoulder press) → anterior delt is primary mover
            n.any("press", "military", "overhead") -> "front"
            else -> null
        }
    }

    /** Whether an exercise is available at a gym given the profile's equipment set. */
    fun isAvailable(equipment: String?, availableEquipment: Set<String>): Boolean =
        equipment in ALWAYS_AVAILABLE_EQUIPMENT || equipment in availableEquipment

    // Extension helpers (private)
    private fun String.any(vararg tokens: String) = tokens.any { this.contains(it) }
    private fun List<String>.any(vararg tokens: String) = tokens.any { t -> this.any { it.contains(t) } }
}
