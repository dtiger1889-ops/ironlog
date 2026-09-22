package dev.ironlog.app.data.importer

import dev.ironlog.app.data.ExerciseType
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** A distinct exercise discovered in the export, with its inferred measurement type. */
data class ParsedExercise(
    val name: String,
    val type: ExerciseType,
)

/** A distinct training session. */
data class ParsedWorkout(
    val importedWorkoutNumber: Int?,
    val name: String,
    /** Raw "yyyy-MM-dd HH:mm:ss" from the export (kept for traceable date assertions). */
    val dateString: String,
    val startTimeMillis: Long,
    val durationSec: Int?,
    val notes: String?,
)

/** One logged set, still keyed by the export's natural identifiers (mapped to row ids on insert). */
data class ParsedSet(
    val importedWorkoutNumber: Int?,
    val exerciseName: String,
    val setOrder: Int,
    /** Already converted to pounds and rounded; null when the source weight was 0. */
    val weightLb: Double?,
    val reps: Int?,
    val seconds: Int?,
    val distanceMeters: Double?,
    val rpe: Double?,
    val notes: String?,
)

/**
 * A non-set metadata row from the export ("Rest Timer" / "Note").  These are per-exercise
 * settings/annotations the previous tracker interleaves between set rows -- never logged sets.
 * [legacySetOrder] is the setOrder the pre-fix importer fabricated for this row
 * (`sets.size + 1` over ALL rows parsed so far), kept so a DB repair can fingerprint the
 * exact junk rows an earlier import created.
 */
data class ParsedMetadataRow(
    val importedWorkoutNumber: Int?,
    val exerciseName: String,
    val legacySetOrder: Int,
    val seconds: Int?,
    val notes: String?,
)

/** The fully structured result of parsing a previous-tracker export. */
data class CsvHistoryImport(
    val exercises: List<ParsedExercise>,
    val workouts: List<ParsedWorkout>,
    val sets: List<ParsedSet>,
    val metadataRows: List<ParsedMetadataRow> = emptyList(),
)

/**
 * Pure (no Android) transform of a previous-tracker CSV export into structured, lb-native domain
 * objects.  Keeping this Android-free lets a plain JVM unit test prove the real 13,727-set
 * export loads cleanly without an emulator.
 */
object CsvHistoryImporter {

    // Header names exactly as they appear in the export (column order is read by name).
    private const val COL_WORKOUT_NUM = "Workout #"
    private const val COL_DATE = "Date"
    private const val COL_WORKOUT_NAME = "Workout Name"
    private const val COL_DURATION = "Duration (sec)"
    private const val COL_EXERCISE = "Exercise Name"
    private const val COL_SET_ORDER = "Set Order"
    private const val COL_WEIGHT = "Weight (kg)"
    private const val COL_REPS = "Reps"
    private const val COL_RPE = "RPE"
    private const val COL_DISTANCE = "Distance (meters)"
    private const val COL_SECONDS = "Seconds"
    private const val COL_NOTES = "Notes"
    private const val COL_WORKOUT_NOTES = "Workout Notes"

    private fun dateFormat(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }

    fun parse(csvText: String): CsvHistoryImport {
        val rows = HistoryCsvParser.parse(csvText)
        require(rows.isNotEmpty()) { "Empty CSV" }

        val header = rows.first().map { it.trim() }
        val idx = header.withIndex().associate { (i, name) -> name to i }
        fun required(name: String): Int =
            idx[name] ?: error("Missing expected column: '$name' (header=$header)")

        val cWorkoutNum = required(COL_WORKOUT_NUM)
        val cDate = required(COL_DATE)
        val cWorkoutName = required(COL_WORKOUT_NAME)
        val cDuration = required(COL_DURATION)
        val cExercise = required(COL_EXERCISE)
        val cSetOrder = required(COL_SET_ORDER)
        val cWeight = required(COL_WEIGHT)
        val cReps = required(COL_REPS)
        val cRpe = required(COL_RPE)
        val cDistance = required(COL_DISTANCE)
        val cSeconds = required(COL_SECONDS)
        val cNotes = required(COL_NOTES)
        val cWorkoutNotes = required(COL_WORKOUT_NOTES)

        val fmt = dateFormat()

        // Drop the header and any fully blank trailing rows.
        val dataRows = rows.drop(1).filter { r -> r.any { it.isNotBlank() } }

        val sets = ArrayList<ParsedSet>(dataRows.size)
        val metadataRows = ArrayList<ParsedMetadataRow>()
        // Replicates the pre-fix `sets.size` (that list swallowed EVERY data row, metadata
        // included) so metadata rows can record the setOrder the old importer fabricated.
        var legacyRowCount = 0
        // Preserve first-seen order for workouts; collect their header fields.
        val workoutOrder = LinkedHashMap<Int?, ParsedWorkout>()
        // Group raw measurement signals per exercise for type classification.
        val exerciseSeen = LinkedHashMap<String, ExerciseSignals>()

        for (r in dataRows) {
            fun str(col: Int): String = r.getOrNull(col)?.trim().orEmpty()
            fun strOrNull(col: Int): String? = str(col).takeIf { it.isNotEmpty() }
            fun intOrNull(col: Int): Int? = strOrNull(col)?.toDoubleOrNull()?.toInt()
            fun doubleOrNull(col: Int): Double? = strOrNull(col)?.toDoubleOrNull()

            val workoutNum = intOrNull(cWorkoutNum)
            val exerciseName = str(cExercise)
            val weightKg = doubleOrNull(cWeight) ?: 0.0
            val reps = intOrNull(cReps)
            val seconds = intOrNull(cSeconds)
            val distance = doubleOrNull(cDistance)
            val weightLb = if (weightKg > 0.0) WeightConversion.kgToLbRounded(weightKg) else null

            // First row of a workout number defines its header fields.
            if (!workoutOrder.containsKey(workoutNum)) {
                val dateStr = str(cDate)
                workoutOrder[workoutNum] = ParsedWorkout(
                    importedWorkoutNumber = workoutNum,
                    name = str(cWorkoutName),
                    dateString = dateStr,
                    startTimeMillis = runCatching { fmt.parse(dateStr)?.time ?: 0L }.getOrDefault(0L),
                    durationSec = intOrNull(cDuration),
                    notes = strOrNull(cWorkoutNotes),
                )
            }

            // The previous tracker interleaves non-set metadata rows ("Rest Timer" = the exercise's rest-timer
            // SETTING, "Note" = an exercise annotation) between real sets; their Set Order column
            // is the marker text, not a number.  They must NOT become sets (F1: 1,495 rest-timer
            // rows imported as phantom "60s" sets) and must NOT feed classification (Chest Dip
            // was tied into TIMED by its own rest-timer rows).
            val setOrder = intOrNull(cSetOrder)
            if (setOrder == null) {
                metadataRows += ParsedMetadataRow(
                    importedWorkoutNumber = workoutNum,
                    exerciseName = exerciseName,
                    legacySetOrder = legacyRowCount + 1,
                    seconds = seconds,
                    notes = strOrNull(cNotes),
                )
                legacyRowCount++
                continue
            }

            sets += ParsedSet(
                importedWorkoutNumber = workoutNum,
                exerciseName = exerciseName,
                setOrder = setOrder,
                weightLb = weightLb,
                reps = reps,
                seconds = seconds,
                distanceMeters = distance,
                rpe = doubleOrNull(cRpe),
                notes = strOrNull(cNotes),
            )
            legacyRowCount++

            // Tally each set by its single primary signal; the exercise type is the majority.
            exerciseSeen.getOrPut(exerciseName) { ExerciseSignals() }
                .observe(weightKg = weightKg, seconds = seconds, distance = distance, reps = reps)
        }

        val exercises = exerciseSeen.map { (name, sig) ->
            ParsedExercise(name, sig.classify())
        }

        return CsvHistoryImport(
            exercises = exercises,
            workouts = workoutOrder.values.toList(),
            sets = sets,
            metadataRows = metadataRows,
        )
    }

    private class ExerciseSignals {
        var weighted = 0
        var cardio = 0
        var timed = 0
        var distance = 0
        var bodyweight = 0

        /**
         * Classify each set by ONE primary signal, weight first.  In the previous tracker's export the
         * Seconds column is not a "timed exercise" marker -- it's sporadically populated on
         * ordinary lifts (e.g. 157 of 917 Bench sets), so a real load must outrank it.  A
         * load-free set carrying BOTH a distance and a duration is a cardio set (treadmill,
         * bike); seconds alone is a timed hold; distance alone is a carry.
         */
        fun observe(weightKg: Double, seconds: Int?, distance: Double?, reps: Int?) {
            when {
                weightKg > 0.0 -> weighted++
                (seconds ?: 0) > 0 && (distance ?: 0.0) > 0.0 -> cardio++
                (seconds ?: 0) > 0 -> timed++
                (distance ?: 0.0) > 0.0 -> this.distance++
                (reps ?: 0) > 0 -> bodyweight++
                else -> Unit // empty set contributes no signal
            }
        }

        /**
         * Majority signal wins; ties prefer weighted > cardio > timed > distance > bodyweight.
         * Cardio outranks timed and distance because a distance+time set genuinely carries both
         * and either single-column reading would drop half of it.
         */
        fun classify(): ExerciseType = when {
            weighted >= cardio && weighted >= timed && weighted >= distance &&
                weighted >= bodyweight && weighted > 0 -> ExerciseType.WEIGHTED
            cardio >= timed && cardio >= distance && cardio >= bodyweight && cardio > 0 ->
                ExerciseType.CARDIO
            timed >= distance && timed >= bodyweight && timed > 0 -> ExerciseType.TIMED
            distance >= bodyweight && distance > 0 -> ExerciseType.DISTANCE
            else -> ExerciseType.BODYWEIGHT
        }
    }
}
