package dev.ironlog.app.export

import dev.ironlog.app.data.Exercise
import dev.ironlog.app.data.ExerciseType
import dev.ironlog.app.data.Measurement
import dev.ironlog.app.data.SetEntry
import dev.ironlog.app.data.Workout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * JSON export / import for full ironlog data ownership (backup + restore).
 *
 * The export format is versioned so future schema changes can be migrated on restore.
 * Restore is a NON-DESTRUCTIVE MERGE: existing rows are kept; imported rows are inserted
 * only when they don't already exist (keyed by importedWorkoutNumber for workouts, by name for
 * exercises, and by (type+timestamp) for measurements).
 *
 * Pure Kotlin — no Android or Room deps — fully JVM testable.
 */
object ExportManager {

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }

    // ---------- Export DTOs (decoupled from Room entities) ----------

    @Serializable
    data class ExerciseDto(
        val id: Long,
        val name: String,
        val type: String,
        val repRangeLow: Int? = null,
        val repRangeHigh: Int? = null,
        val incrementLb: Double? = null,
        val restSeconds: Int? = null,
        val bodyPart: String? = null,
        val equipment: String? = null,
        val mechanic: String? = null,
        val force: String? = null,
        val primaryMuscles: List<String>? = null,
        val secondaryMuscles: List<String>? = null,
        val notes: String? = null,
        val goalPreset: String? = null,
        val barWeightLb: Double? = null,
        val catalogOnly: Boolean = false,
        val plateCalcMode: String? = null,
    )

    @Serializable
    data class WorkoutDto(
        val id: Long,
        val name: String,
        val startTime: Long,
        val durationSec: Int? = null,
        val notes: String? = null,
        val importedWorkoutNumber: Int? = null,
    )

    @Serializable
    data class SetEntryDto(
        val id: Long,
        val workoutId: Long,
        val exerciseId: Long,
        val setOrder: Int,
        val weightLb: Double? = null,
        val reps: Int? = null,
        val seconds: Int? = null,
        val distanceMeters: Double? = null,
        val notes: String? = null,
        val isPR: Boolean = false,
        val isWarmup: Boolean = false,
        val supersetGroup: Int? = null,
    )

    @Serializable
    data class MeasurementDto(
        val id: Long,
        val type: String,
        val value: Double,
        val unit: String,
        val timestamp: Long,
        val source: String,
        val hcRecordId: String? = null,
        val notes: String? = null,
    )

    @Serializable
    data class IronlogExport(
        val version: Int = 1,
        val exportedAt: Long,
        val exercises: List<ExerciseDto>,
        val workouts: List<WorkoutDto>,
        val sets: List<SetEntryDto>,
        val measurements: List<MeasurementDto>,
    )

    // ---------- Serialize ----------

    fun exportJson(
        exercises: List<Exercise>,
        workouts: List<Workout>,
        sets: List<SetEntry>,
        measurements: List<Measurement>,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val export = IronlogExport(
            exportedAt = nowMs,
            exercises = exercises.map { it.toDto() },
            workouts = workouts.map { it.toDto() },
            sets = sets.map { it.toDto() },
            measurements = measurements.map { it.toDto() },
        )
        return json.encodeToString(export)
    }

    // ---------- Deserialize ----------

    fun importJson(jsonText: String): IronlogExport = json.decodeFromString(jsonText)

    // ---------- Domain → DTO ----------

    private fun Exercise.toDto() = ExerciseDto(
        id = id, name = name, type = type.name,
        repRangeLow = repRangeLow, repRangeHigh = repRangeHigh,
        incrementLb = incrementLb, restSeconds = restSeconds,
        bodyPart = bodyPart, equipment = equipment, mechanic = mechanic, force = force,
        primaryMuscles = primaryMuscles, secondaryMuscles = secondaryMuscles,
        notes = notes, goalPreset = goalPreset,
        barWeightLb = barWeightLb, catalogOnly = catalogOnly,
        plateCalcMode = plateCalcMode,
    )

    private fun Workout.toDto() = WorkoutDto(
        id = id, name = name, startTime = startTime,
        durationSec = durationSec, notes = notes,
        importedWorkoutNumber = importedWorkoutNumber,
    )

    private fun SetEntry.toDto() = SetEntryDto(
        id = id, workoutId = workoutId, exerciseId = exerciseId, setOrder = setOrder,
        weightLb = weightLb, reps = reps, seconds = seconds,
        distanceMeters = distanceMeters, notes = notes, isPR = isPR, isWarmup = isWarmup,
        supersetGroup = supersetGroup,
    )

    private fun Measurement.toDto() = MeasurementDto(
        id = id, type = type, value = value, unit = unit,
        timestamp = timestamp, source = source, hcRecordId = hcRecordId, notes = notes,
    )

    // ---------- DTO → Domain (for restore) ----------

    fun ExerciseDto.toDomain(): Exercise = Exercise(
        id = id, name = name,
        type = ExerciseType.valueOf(type),
        repRangeLow = repRangeLow, repRangeHigh = repRangeHigh,
        incrementLb = incrementLb, restSeconds = restSeconds,
        bodyPart = bodyPart, equipment = equipment, mechanic = mechanic, force = force,
        primaryMuscles = primaryMuscles, secondaryMuscles = secondaryMuscles,
        notes = notes, goalPreset = goalPreset,
        barWeightLb = barWeightLb, catalogOnly = catalogOnly,
        plateCalcMode = plateCalcMode,
    )

    fun WorkoutDto.toDomain(): Workout = Workout(
        id = id, name = name, startTime = startTime,
        durationSec = durationSec, notes = notes,
        importedWorkoutNumber = importedWorkoutNumber,
    )

    fun SetEntryDto.toDomain(): SetEntry = SetEntry(
        id = id, workoutId = workoutId, exerciseId = exerciseId, setOrder = setOrder,
        weightLb = weightLb, reps = reps, seconds = seconds,
        distanceMeters = distanceMeters, notes = notes, isPR = isPR, isWarmup = isWarmup,
        supersetGroup = supersetGroup,
    )

    fun MeasurementDto.toDomain(): Measurement = Measurement(
        id = id, type = type, value = value, unit = unit,
        timestamp = timestamp, source = source, hcRecordId = hcRecordId, notes = notes,
    )
}
