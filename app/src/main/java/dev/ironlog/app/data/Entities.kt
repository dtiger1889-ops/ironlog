package dev.ironlog.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Catalog of distinct exercises. Progression-engine fields (rep range, increment) are
 * nullable -- the engine applies sensible defaults when they are unset (v1 spec).
 * M1a adds exercise metadata from free-exercise-db (all nullable, additive AutoMigration).
 */
@Entity(
    tableName = "exercises",
    indices = [Index(value = ["name"], unique = true)],
)
data class Exercise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: ExerciseType,
    val repRangeLow: Int? = null,
    val repRangeHigh: Int? = null,
    val incrementLb: Double? = null,
    /** Per-exercise rest duration in seconds (null -> app default). Remembered per exercise. */
    val restSeconds: Int? = null,

    // M1a: free-exercise-db metadata (all nullable; additive AutoMigration 2->3)
    val bodyPart: String? = null,         // maps to free-db "category" (strength/stretching/…)
    val category: String? = null,         // same field, aliased for UI clarity
    val equipment: String? = null,
    val mechanic: String? = null,         // "compound" | "isolation" | null
    val force: String? = null,            // "push" | "pull" | "static" | null
    val instructions: List<String>? = null,
    val primaryMuscles: List<String>? = null,
    val secondaryMuscles: List<String>? = null,
    val imageRef: String? = null,         // first image path from free-db images array
    val freeDbId: String? = null,         // free-db string id (e.g. "3_4_Sit-Up")
    @ColumnInfo(defaultValue = "0") val isFavorite: Boolean = false,
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
    val notes: String? = null,
    // M2: sticky note shown in the active workout header (persists across sessions)
    val stickyNote: String? = null,

    // M4: progression engine config (all nullable / defaulted -> additive AutoMigration 4->5).
    /** Goal preset name (GoalPreset enum) overriding the global default for this exercise. */
    val goalPreset: String? = null,
    /** Target hold range for TIMED exercises (seconds). */
    val targetSecLow: Int? = null,
    val targetSecHigh: Int? = null,
    /** When false, the progression engine emits no suggestions for this exercise. */
    @ColumnInfo(defaultValue = "1") val progressionEnabled: Boolean = true,

    // M5: gym assistant metadata (nullable → additive AutoMigration 6→7).
    /** High-level movement pattern token for swap ranking (e.g. "horizontal-push", "hip-hinge"). */
    val movementPattern: String? = null,
    /** For shoulder exercises: which delt head is primary ("front" | "lateral" | "rear" | null). */
    val deltHead: String? = null,

    // M3: catalog seeding — exercises from free-exercise-db that the user has NOT logged yet.
    // Logged exercises have catalogOnly = false; catalog-only seed rows = true.
    @ColumnInfo(defaultValue = "0") val catalogOnly: Boolean = false,

    // M6: per-exercise bar weight for the plate calc (null = use global default from Settings).
    // 0.0 = no bar (leg press, machine); positive = specific bar (e.g. 95.0 for seated calf).
    val barWeightLb: Double? = null,

    // F2: per-exercise plate-calculator override (null → additive AutoMigration 12->13).
    // null = auto-detect by equipment/name (barbell → show); "ON" = force show; "OFF" = force hide.
    val plateCalcMode: String? = null,
)

/** One training session. */
@Entity(
    tableName = "workouts",
    indices = [Index("importedWorkoutNumber")],
)
data class Workout(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Epoch millis (parsed UTC for determinism; day-of is preserved). */
    val startTime: Long,
    val durationSec: Int? = null,
    val notes: String? = null,
    /** Provenance: the previous tracker's "Workout #" so imports are traceable / de-dupable. */
    val importedWorkoutNumber: Int? = null,
)

/** One logged set. weightLb is null for bodyweight/timed sets; canonical unit is pounds. */
@Entity(
    tableName = "set_entries",
    foreignKeys = [
        ForeignKey(
            entity = Workout::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Exercise::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("workoutId"), Index("exerciseId")],
)
data class SetEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val exerciseId: Long,
    val setOrder: Int,
    val weightLb: Double? = null,
    val reps: Int? = null,
    val seconds: Int? = null,
    val distanceMeters: Double? = null,
    val rpe: Double? = null,
    val notes: String? = null,
    // M1a: PR flag computed by PrEngine on finish
    @ColumnInfo(defaultValue = "0") val isPR: Boolean = false,
    // M2: warm-up tag (excluded from PR/volume/1RM math — load-bearing contract)
    @ColumnInfo(defaultValue = "0") val isWarmup: Boolean = false,
    val setTag: String? = null,          // "failure" | "drop" — column only; analytics in future milestone
    val supersetGroup: Int? = null,      // exercises sharing the same group int are a superset
    val restTakenSec: Int? = null,       // actual rest taken after this set (informational)
)

/** A reusable workout template. Non-destructive editing is enforced at the logging layer (v1 Fix 2). */
@Entity(tableName = "templates")
data class Template(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val note: String? = null,           // M2: optional note shown at workout start
    /** Manual sort order on the Workout tab (v12). 0-default keeps legacy id-order stable. */
    @ColumnInfo(defaultValue = "0") val position: Int = 0,
)

/** Exercises that belong to a template, in order. */
@Entity(
    tableName = "template_exercises",
    foreignKeys = [
        ForeignKey(
            entity = Template::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Exercise::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("templateId"), Index("exerciseId")],
)
data class TemplateExercise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val templateId: Long,
    val exerciseId: Long,
    val position: Int,
    // M2: optional target rep range / rest carried from the template into the active workout header
    val targetSets: Int? = null,
    val targetRepsLow: Int? = null,
    val targetRepsHigh: Int? = null,
    val targetRestSec: Int? = null,
)

/**
 * Per-set target (weight + reps) for a template exercise — so a routine stores the same grid a
 * workout shows, instead of only a set count + one rep range. `position` orders the sets within
 * the exercise. Value fields mirror SetEntry so every exercise type round-trips. Cascade-deleted
 * with its TemplateExercise (schema v11).
 */
@Entity(
    tableName = "template_sets",
    foreignKeys = [
        ForeignKey(
            entity = TemplateExercise::class,
            parentColumns = ["id"],
            childColumns = ["templateExerciseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("templateExerciseId")],
)
data class TemplateSet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val templateExerciseId: Long,
    val position: Int,
    val weightLb: Double? = null,
    val reps: Int? = null,
    val seconds: Int? = null,
    val distanceMeters: Double? = null,
)

/** Plate inventory for the plate calculator. Bar weight is stored in DataStore (see Settings.kt).
 *  Each row represents a plate denomination and the number of pairs the user owns. */
@Entity(tableName = "plate_inventory")
data class PlateInventory(
    @PrimaryKey val plateLb: Double,    // e.g. 45.0, 35.0, 25.0, 10.0, 5.0, 2.5
    val pairCount: Int,
)

/**
 * M4: per-muscle weekly volume landmarks (sets/week). Seeded ONCE from the bundled
 * assets/volume_landmarks.json -- a labeled practitioner model (Israetel / RP), hand-editable,
 * EXPLICITLY NOT derived from the user's logged history. Consumed by VolumeLandmarks.classify().
 *
 *   mv  = maintenance volume      (below -> losing ground)
 *   mev = minimum effective vol.  (growth floor)
 *   mav = maximum adaptive vol.   (productive ceiling)
 *   mrv = maximum recoverable vol.(beyond -> junk volume)
 */
@Entity(tableName = "muscle_volume_targets")
data class MuscleVolumeTarget(
    @PrimaryKey val muscle: String,  // lowercased free-exercise-db token (e.g. "chest")
    val mv: Double,
    val mev: Double,
    val mav: Double,
    val mrv: Double,
)

/**
 * M5: a named equipment configuration representing one gym location.
 * Exactly one profile is active at a time (isActive flag). A new profile defaults to all
 * equipment ON (the DAO seeds one row per equipment token on creation).
 */
@Entity(tableName = "gym_profiles")
data class GymProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "0") val isActive: Boolean = false,
)

/**
 * M5: equipment tokens available at a gym profile. A missing row means the equipment is OFF.
 * Tokens mirror the `equipment` column in the Exercise catalog (free-exercise-db vocabulary:
 * "barbell", "cable", "dumbbell", "e-z curl bar", "exercise ball", "foam roll", "kettlebell",
 * "machine", "medicine ball", "other", "resistance band", "roller", "trap bar").
 */
@Entity(
    tableName = "gym_profile_equipment",
    foreignKeys = [
        ForeignKey(
            entity = GymProfile::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profileId")],
)
data class GymProfileEquipment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    /** One equipment token (lowercased free-exercise-db value). */
    val equipmentToken: String,
)

/**
 * M6: a body measurement logged by the user (bodyweight, body-fat %, girths).
 * source = "manual" | "health_connect". hcRecordId links to a HC record for dedup so repeated
 * opens of the HC sync don't create duplicate local rows.
 */
@Entity(tableName = "measurements")
data class Measurement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** MeasurementType token: "bodyweight" | "bodyfat" | "chest" | "waist" | "hips" |
     *  "thigh" | "arm" | "calf" | "shoulder" | "neck" | "forearm". */
    val type: String,
    val value: Double,
    /** "lb" for bodyweight, "%" for bodyfat, "in" for all girths. */
    val unit: String,
    /** Epoch millis — used for the Vico chart X-axis and HC dedup window. */
    val timestamp: Long,
    val source: String,
    val hcRecordId: String? = null,
    val notes: String? = null,
)

/**
 * M7: end-of-workout coach summary (1:1 with Workout, cascade-delete).
 * Stores grouped plain-language lines produced by CoachEngine.summarize().
 * Each list is null when the group has nothing to say (caller interprets null as empty).
 */
@Entity(
    tableName = "coach_summaries",
    foreignKeys = [
        ForeignKey(
            entity = Workout::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["workoutId"], unique = true)],
)
data class CoachSummary(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val prLines: List<String>? = null,
    val progressionLines: List<String>? = null,
    val volumeLines: List<String>? = null,
    val neglectLines: List<String>? = null,
    val createdAt: Long,
)

/**
 * M7: singleton reminder configuration (id always = 1).
 * All nudges default OFF — never auto-enable without an explicit user action.
 */
@Entity(tableName = "reminder_config")
data class ReminderConfig(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(defaultValue = "0") val neglectNudgesEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "0") val restDayNudgesEnabled: Boolean = false,
)

/**
 * M7: one line of an external coach's plan (the "domen's sheet" overlay).
 * Each row maps one exercise to a plan text (e.g., "4×8 @ 185 lb").
 * Shown as a gray "Plan: …" hint beside PREVIOUS in the active workout; NEVER prefilled.
 */
@Entity(
    tableName = "plan_entries",
    foreignKeys = [
        ForeignKey(
            entity = Exercise::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("exerciseId")],
)
data class PlanEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: Long,
    /** Source label shown to user, e.g. "Coach" or "Domen". */
    val label: String = "Coach",
    /** Plain-language plan text, e.g. "4 × 8 @ 185 lb". */
    val planText: String,
    val position: Int = 0,
)

/**
 * M4: the persisted accept/dismiss feed for progression suggestions. Suggestions are computed
 * live by SuggestionEngine; a row is written only when the user acts on one (ACCEPTED / DISMISSED),
 * so the engine can suppress ones already handled (by [dedupKey]).
 *
 * "ACCEPTED" offers the new load as a PREVIOUS-style prefill HINT only -- it NEVER writes a
 * SetEntry or edits a Template (M4 hard guardrail).
 */
@Entity(tableName = "progression_suggestions")
data class ProgressionSuggestion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Exercise id, or -1 for muscle-volume suggestions. */
    val exerciseId: Long,
    val kind: String,           // SuggestionEngine.Kind name
    val payload: String,        // the human-readable suggestion message at action time
    val citation: String,
    val status: String,         // "ACCEPTED" | "DISMISSED" | "CONSUMED" (applied in a finished workout)
    /** Stable key (kind + subject + rounded value) used to suppress re-showing handled suggestions. */
    val dedupKey: String,
    val createdAt: Long,
    /** Accepted prefill hint: the suggested working weight (lb), surfaced on the next draft. */
    val hintWeightLb: Double? = null,
    /** Accepted prefill hint: the suggested hold (seconds), for TIMED exercises. */
    val hintSeconds: Int? = null,
)
