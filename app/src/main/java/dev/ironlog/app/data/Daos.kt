package dev.ironlog.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseDao {
    @Insert
    fun insert(exercise: Exercise): Long

    /** Insert catalog-only entry; silently skip if a row with the same name already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIgnore(exercise: Exercise): Long

    /** Bulk insert catalog-only entries; ignores name-duplicates. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAllIgnore(exercises: List<Exercise>): List<Long>

    /** All exercises: logged (catalogOnly=0) first, then catalog-only, both alpha-sorted. */
    @Query("SELECT * FROM exercises ORDER BY catalogOnly ASC, name ASC")
    fun all(): List<Exercise>

    /** Flow version of [all]: logged first, catalog-only second, alpha within each group. */
    @Query("SELECT * FROM exercises ORDER BY catalogOnly ASC, name ASC")
    fun allFlow(): Flow<List<Exercise>>

    /** Only the user's logged exercises (not catalog-only seeds). */
    @Query("SELECT * FROM exercises WHERE catalogOnly = 0 ORDER BY name ASC")
    fun loggedFlow(): Flow<List<Exercise>>

    @Query("SELECT * FROM exercises WHERE id = :id")
    fun byId(id: Long): Exercise?

    @Query("SELECT * FROM exercises WHERE freeDbId = :freeDbId LIMIT 1")
    fun byFreeDbId(freeDbId: String): Exercise?

    @Query("SELECT COUNT(*) FROM exercises")
    fun count(): Int

    @Query("SELECT COUNT(*) FROM exercises WHERE catalogOnly = 1")
    fun countCatalogOnly(): Int

    @Query("UPDATE exercises SET restSeconds = :seconds WHERE id = :id")
    fun updateRest(id: Long, seconds: Int?)

    /** Corrects a misclassified measurement type (F1 repair: rest-timer rows skewed majority). */
    @Query("UPDATE exercises SET type = :type WHERE id = :id")
    fun updateType(id: Long, type: ExerciseType)

    @Query("UPDATE exercises SET stickyNote = :note WHERE id = :id")
    fun updateStickyNote(id: Long, note: String?)

    /** M4: persist per-exercise progression config (goal preset / increment / enable toggle). */
    @Query(
        "UPDATE exercises SET goalPreset = :goalPreset, incrementLb = :incrementLb, " +
            "progressionEnabled = :progressionEnabled WHERE id = :id",
    )
    fun updateProgression(
        id: Long,
        goalPreset: String?,
        incrementLb: Double?,
        progressionEnabled: Boolean,
    )

    /** Apply confirmed free-exercise-db metadata to one exercise (reconciliation path). */
    @Query("""UPDATE exercises SET bodyPart = :bodyPart, category = :category,
        equipment = :equipment, mechanic = :mechanic, force = :force,
        instructions = :instructions, primaryMuscles = :primaryMuscles,
        secondaryMuscles = :secondaryMuscles, imageRef = :imageRef, freeDbId = :freeDbId
        WHERE id = :id""")
    fun applyMetadata(
        id: Long,
        bodyPart: String?,
        category: String?,
        equipment: String?,
        mechanic: String?,
        force: String?,
        instructions: String?,       // stored as JSON
        primaryMuscles: String?,     // stored as JSON
        secondaryMuscles: String?,   // stored as JSON
        imageRef: String?,
        freeDbId: String?,
    )

    /** M5: persist algorithmically-derived movement pattern + deltoid head. */
    @Query("UPDATE exercises SET movementPattern = :movementPattern, deltHead = :deltHead WHERE id = :id")
    fun updateGymMetadata(id: Long, movementPattern: String?, deltHead: String?)

    /**
     * F2: persist per-exercise plate-calculator config -- [mode] is null (auto-detect by
     * equipment/name) | "ON" (force show) | "OFF" (force hide); [barWeightLb] is the base/bar
     * weight override (null = global default, 0.0 = no bar, positive = a specific base like a
     * 95 lb machine stack). Supersedes the old bar-weight-only `updateBarWeightLb` (never wired
     * to any UI -- found dead 2026-07-18 alongside F9's AutoBackupWorker).
     */
    @Query("UPDATE exercises SET plateCalcMode = :mode, barWeightLb = :barWeightLb WHERE id = :id")
    fun updatePlateCalcConfig(id: Long, mode: String?, barWeightLb: Double?)

    @Query("DELETE FROM exercises")
    fun deleteAll()
}

@Dao
interface WorkoutDao {
    @Insert
    fun insert(workout: Workout): Long

    @Query("SELECT * FROM workouts ORDER BY startTime DESC, id DESC")
    fun recentFlow(): Flow<List<Workout>>

    @Query("SELECT * FROM workouts ORDER BY startTime, id")
    fun all(): List<Workout>

    @Query("SELECT * FROM workouts WHERE id = :id")
    fun byId(id: Long): Workout?

    @Query("SELECT COUNT(*) FROM workouts")
    fun count(): Int

    @Query("UPDATE workouts SET notes = :notes WHERE id = :id")
    fun updateNotes(id: Long, notes: String?)

    @Query("DELETE FROM workouts WHERE id = :id")
    fun deleteById(id: Long)

    @Query("DELETE FROM workouts")
    fun deleteAll()
}

@Dao
interface SetEntryDao {
    @Insert
    fun insert(setEntry: SetEntry): Long

    @Insert
    fun insertAll(setEntries: List<SetEntry>): List<Long>

    @Query("SELECT * FROM set_entries WHERE workoutId = :workoutId ORDER BY id")
    fun forWorkout(workoutId: Long): List<SetEntry>

    /** Most recent logged set for an exercise -- used to prefill weight/reps on a new set. */
    @Query("SELECT * FROM set_entries WHERE exerciseId = :exerciseId ORDER BY id DESC LIMIT 1")
    fun lastSetFor(exerciseId: Long): SetEntry?

    /** All sets from the most recent workout that included this exercise (the PREVIOUS column). */
    @Query(
        "SELECT * FROM set_entries WHERE exerciseId = :exerciseId AND workoutId = (" +
            "SELECT s.workoutId FROM set_entries s JOIN workouts w ON w.id = s.workoutId " +
            "WHERE s.exerciseId = :exerciseId ORDER BY w.startTime DESC, w.id DESC LIMIT 1" +
            ") ORDER BY setOrder",
    )
    fun lastSessionSets(exerciseId: Long): List<SetEntry>

    /** All historical sets for an exercise across all workouts (for PR computation). */
    @Query("SELECT * FROM set_entries WHERE exerciseId = :exerciseId ORDER BY id")
    fun allForExercise(exerciseId: Long): List<SetEntry>

    /** All sets across all workouts (for muscle-coverage computation). */
    @Query("SELECT * FROM set_entries ORDER BY workoutId, id")
    fun all(): List<SetEntry>

    @Query("SELECT COUNT(*) FROM set_entries")
    fun count(): Int

    /** Targeted delete for the F1 imported rest-timer/note junk repair. */
    @Query("DELETE FROM set_entries WHERE id IN (:ids)")
    fun deleteByIds(ids: List<Long>)

    /** Returns all sets for an exercise with the workout's startTime for charting.
     *  Result ordered by workout start time, then set order. */
    @Query(
        "SELECT s.*, w.startTime as workoutStartTime FROM set_entries s " +
            "JOIN workouts w ON w.id = s.workoutId " +
            "WHERE s.exerciseId = :exerciseId AND s.isWarmup = 0 " +
            "ORDER BY w.startTime ASC, s.setOrder ASC",
    )
    fun setsWithWorkoutTimeForExercise(exerciseId: Long): List<SetWithWorkoutTime>

    @Query("DELETE FROM set_entries WHERE workoutId = :workoutId")
    fun deleteForWorkout(workoutId: Long)

    @Query("DELETE FROM set_entries")
    fun deleteAll()
}

/** A [SetEntry] plus the startTime of the workout it belongs to (for charting). */
data class SetWithWorkoutTime(
    val id: Long,
    val workoutId: Long,
    val exerciseId: Long,
    val setOrder: Int,
    val weightLb: Double?,
    val reps: Int?,
    val seconds: Int?,
    val distanceMeters: Double?,
    val rpe: Double?,
    val notes: String?,
    val isPR: Boolean,
    val isWarmup: Boolean,
    val setTag: String?,
    val supersetGroup: Int?,
    val restTakenSec: Int?,
    val workoutStartTime: Long,
)

@Dao
interface TemplateDao {
    @Insert
    fun insert(template: Template): Long

    @Query("SELECT * FROM templates ORDER BY position, id")
    fun allFlow(): Flow<List<Template>>

    @Query("SELECT * FROM templates ORDER BY position, id")
    fun allList(): List<Template>

    @Query("UPDATE templates SET position = :position WHERE id = :id")
    fun updatePosition(id: Long, position: Int)

    @Query("SELECT * FROM templates WHERE id = :id")
    fun byId(id: Long): Template?

    @Query("SELECT COUNT(*) FROM templates")
    fun count(): Int

    @Query("UPDATE templates SET name = :name WHERE id = :id")
    fun updateName(id: Long, name: String)

    @Query("UPDATE templates SET name = :name, note = :note WHERE id = :id")
    fun updateNameAndNote(id: Long, name: String, note: String?)

    @Query("DELETE FROM templates WHERE id = :id")
    fun deleteById(id: Long)

    @Query("DELETE FROM templates")
    fun deleteAll()
}

@Dao
interface TemplateExerciseDao {
    @Insert
    fun insert(templateExercise: TemplateExercise): Long

    /** The exercises of a template, joined to the catalog, in template order. */
    @Query(
        "SELECT e.* FROM exercises e " +
            "JOIN template_exercises te ON te.exerciseId = e.id " +
            "WHERE te.templateId = :templateId ORDER BY te.position",
    )
    fun exercisesForTemplate(templateId: Long): List<Exercise>

    /** Full TemplateExercise rows for a template (with target fields), in order. */
    @Query("SELECT * FROM template_exercises WHERE templateId = :templateId ORDER BY position")
    fun rowsForTemplate(templateId: Long): List<TemplateExercise>

    @Query("DELETE FROM template_exercises WHERE templateId = :templateId")
    fun deleteForTemplate(templateId: Long)

    @Query("DELETE FROM template_exercises")
    fun deleteAll()
}

@Dao
interface TemplateSetDao {
    @Insert
    fun insert(set: TemplateSet): Long

    /** A template exercise's per-set targets, in order. */
    @Query("SELECT * FROM template_sets WHERE templateExerciseId = :templateExerciseId ORDER BY position")
    fun forTemplateExercise(templateExerciseId: Long): List<TemplateSet>

    @Query("DELETE FROM template_sets WHERE templateExerciseId = :templateExerciseId")
    fun deleteForTemplateExercise(templateExerciseId: Long)

    /** Targeted delete for the F1 repair (junk seconds-only targets snapshotted from junk sets). */
    @Query("DELETE FROM template_sets WHERE id IN (:ids)")
    fun deleteByIds(ids: List<Long>)
}

@Dao
interface PlateInventoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(plate: PlateInventory)

    @Query("SELECT * FROM plate_inventory ORDER BY plateLb DESC")
    fun all(): List<PlateInventory>

    @Query("DELETE FROM plate_inventory")
    fun deleteAll()
}

@Dao
interface MuscleVolumeTargetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(targets: List<MuscleVolumeTarget>)

    @Query("SELECT * FROM muscle_volume_targets")
    fun all(): List<MuscleVolumeTarget>

    @Query("SELECT COUNT(*) FROM muscle_volume_targets")
    fun count(): Int

    @Query("DELETE FROM muscle_volume_targets")
    fun deleteAll()
}

@Dao
interface GymProfileDao {
    @Insert
    fun insert(profile: GymProfile): Long

    @Query("SELECT * FROM gym_profiles ORDER BY id")
    fun all(): List<GymProfile>

    @Query("SELECT * FROM gym_profiles ORDER BY id")
    fun allFlow(): Flow<List<GymProfile>>

    @Query("SELECT * FROM gym_profiles WHERE isActive = 1 LIMIT 1")
    fun activeProfile(): GymProfile?

    @Query("UPDATE gym_profiles SET isActive = 0")
    fun clearActive()

    @Query("UPDATE gym_profiles SET isActive = 1 WHERE id = :id")
    fun setActive(id: Long)

    @Query("UPDATE gym_profiles SET name = :name WHERE id = :id")
    fun rename(id: Long, name: String)

    @Query("DELETE FROM gym_profiles WHERE id = :id")
    fun deleteById(id: Long)
}

@Dao
interface GymProfileEquipmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(tokens: List<GymProfileEquipment>)

    @Query("SELECT equipmentToken FROM gym_profile_equipment WHERE profileId = :profileId")
    fun tokensForProfile(profileId: Long): List<String>

    @Query("DELETE FROM gym_profile_equipment WHERE profileId = :profileId AND equipmentToken = :token")
    fun removeToken(profileId: Long, token: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun addToken(token: GymProfileEquipment)

    @Query("DELETE FROM gym_profile_equipment WHERE profileId = :profileId")
    fun clearForProfile(profileId: Long)
}

@Dao
interface MeasurementDao {
    @Insert
    fun insert(m: Measurement): Long

    @Query("SELECT * FROM measurements ORDER BY timestamp DESC")
    fun allFlow(): Flow<List<Measurement>>

    @Query("SELECT * FROM measurements WHERE type = :type ORDER BY timestamp DESC")
    fun flowByType(type: String): Flow<List<Measurement>>

    @Query("SELECT * FROM measurements ORDER BY timestamp DESC")
    fun all(): List<Measurement>

    /** Latest single measurement of a given type (null = never logged). */
    @Query("SELECT * FROM measurements WHERE type = :type ORDER BY timestamp DESC LIMIT 1")
    fun latestOfType(type: String): Measurement?

    /** Find an existing HC-synced row by its record id to avoid duplicates. */
    @Query("SELECT * FROM measurements WHERE hcRecordId = :hcRecordId LIMIT 1")
    fun findByHcId(hcRecordId: String): Measurement?

    /** All rows of a given type more recent than [sinceMs] (for HC dedup window). */
    @Query("SELECT * FROM measurements WHERE type = :type AND timestamp > :sinceMs ORDER BY timestamp DESC")
    fun sinceMs(type: String, sinceMs: Long): List<Measurement>

    @Query("DELETE FROM measurements WHERE id = :id")
    fun deleteById(id: Long)

    @Query("DELETE FROM measurements")
    fun deleteAll()
}

@Dao
interface ProgressionSuggestionDao {
    @Insert
    fun insert(suggestion: ProgressionSuggestion): Long

    @Query("SELECT * FROM progression_suggestions ORDER BY createdAt DESC")
    fun all(): List<ProgressionSuggestion>

    /** Dedup keys still actively handled (ACCEPTED/DISMISSED) -> suppress in the feed.
     *  CONSUMED keys (already applied in a finished workout) are NOT suppressed -> re-evaluated. */
    @Query("SELECT dedupKey FROM progression_suggestions WHERE status IN ('ACCEPTED', 'DISMISSED')")
    fun handledDedupKeys(): List<String>

    /** The most recent still-pending ACCEPTED suggestion for an exercise (its prefill hint). */
    @Query(
        "SELECT * FROM progression_suggestions WHERE exerciseId = :exerciseId AND status = 'ACCEPTED' " +
            "ORDER BY createdAt DESC LIMIT 1",
    )
    fun latestAcceptedFor(exerciseId: Long): ProgressionSuggestion?

    /** Mark an exercise's accepted suggestions CONSUMED once it has been logged in a finished workout. */
    @Query("UPDATE progression_suggestions SET status = 'CONSUMED' WHERE exerciseId = :exerciseId AND status = 'ACCEPTED'")
    fun markConsumedFor(exerciseId: Long)

    @Query("DELETE FROM progression_suggestions")
    fun deleteAll()
}

@Dao
interface CoachSummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(summary: CoachSummary)

    @Query("SELECT * FROM coach_summaries ORDER BY createdAt DESC LIMIT 1")
    fun latest(): CoachSummary?

    @Query("SELECT * FROM coach_summaries WHERE workoutId = :workoutId LIMIT 1")
    fun forWorkout(workoutId: Long): CoachSummary?
}

@Dao
interface ReminderConfigDao {
    @Query("SELECT * FROM reminder_config WHERE id = 1 LIMIT 1")
    fun get(): ReminderConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(config: ReminderConfig)
}

@Dao
interface PlanEntryDao {
    @Query("SELECT * FROM plan_entries ORDER BY exerciseId ASC, position ASC")
    fun all(): List<PlanEntry>

    @Query("SELECT * FROM plan_entries WHERE exerciseId = :exerciseId ORDER BY position ASC")
    fun forExercise(exerciseId: Long): List<PlanEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entry: PlanEntry): Long

    @Query("DELETE FROM plan_entries WHERE id = :id")
    fun deleteById(id: Long)
}
