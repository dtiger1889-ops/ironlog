package dev.ironlog.app.progression

/**
 * M4 evidence layer: cited rep-range presets + the citation strings every suggestion carries.
 *
 * DISCIPLINE (roadmap §3, M4 guardrails): targets are EVIDENCE-BASED, never self-derived.
 * Nothing in this file (or anywhere in progression/) reads the user's rep distribution to set a
 * target. Rep ranges come from the cited literature; the default goal is a deliberate APP CHOICE.
 *
 * Pure Kotlin / JVM -- no Android dependencies.
 */

/** Goal-based rep-range presets (roadmap §8 citations). */
enum class GoalPreset(val low: Int, val high: Int, val citation: String) {
    /** Strength: heavy, low reps. */
    STRENGTH(3, 6, Citations.REP_RANGES),

    /** Hypertrophy: the deliberate app default (a stated choice, NOT inferred from his clustering). */
    HYPERTROPHY(6, 12, Citations.REP_RANGES),

    /** Muscular endurance: light, high reps. */
    ENDURANCE(15, 20, Citations.REP_RANGES);

    companion object {
        /** The app's stated default goal (roadmap M4 step 12 — surfaced via a chooser, never silent). */
        val DEFAULT = HYPERTROPHY

        fun fromNameOrDefault(name: String?): GoalPreset =
            name?.let { runCatching { valueOf(it) }.getOrNull() } ?: DEFAULT
    }
}

/**
 * Citation strings. Cited numbers carry a §8 reference; practitioner-model numbers carry the
 * literal "practitioner model, not peer-reviewed" tag (rendered visibly in the UI).
 */
object Citations {
    const val PRACTITIONER_TAG = "practitioner model, not peer-reviewed"

    const val REP_RANGES =
        "ACSM 2009 Progression Models (Med Sci Sports Exerc 41:687-708); " +
            "Schoenfeld & Grgic 2021 (PMC7927075)"

    const val WEEKLY_VOLUME_FLOOR =
        "Schoenfeld, Ogborn & Krieger 2017 (J Sports Sci 35:1073-1082) -- >=10 sets/muscle/wk"

    const val VOLUME_LANDMARKS_PRACTITIONER =
        "MEV/MAV/MRV: Israetel / Renaissance Periodization ($PRACTITIONER_TAG)"

    const val DOUBLE_PROGRESSION =
        "NSCA/Baechle, Essentials of S&C (2-for-2 rule); ACSM 2009 (2-10% load step)"

    /** The "2 sessions" stall count is a flagged heuristic; the deload magnitude is cited. */
    const val DELOAD =
        "Bell et al. 2023, A Practical Approach to Deloading (shura.shu.ac.uk/35313); " +
            "stall=2 sessions is a $PRACTITIONER_TAG"

    const val TIMED =
        "ACSM flexibility standard (static hold 10-30s); time step +10s is a $PRACTITIONER_TAG"
}
