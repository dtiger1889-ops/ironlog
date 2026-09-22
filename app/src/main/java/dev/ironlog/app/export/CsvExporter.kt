package dev.ironlog.app.export

import dev.ironlog.app.data.Exercise
import dev.ironlog.app.data.ExerciseType
import dev.ironlog.app.data.SetEntry
import dev.ironlog.app.data.Workout
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Exports ironlog data as an import-compatible CSV so it round-trips through [CsvHistoryImporter].
 *
 * Column order and format match the previous tracker's export exactly:
 *   Workout #; Date; Workout Name; Duration (sec); Exercise Name; Set Order;
 *   Weight (kg); Reps; Distance (meters); Seconds; Notes; Workout Notes; RPE
 *
 * Weights are stored lb-native; the export converts to kg so CsvHistoryImporter converts back.
 * The round-trip preserves data within the 0.5-lb rounding tolerance used by the importer.
 */
object CsvExporter {

    private const val SEP = ";"
    private val HEADER = listOf(
        "Workout #", "Date", "Workout Name", "Duration (sec)", "Exercise Name",
        "Set Order", "Weight (kg)", "Reps", "Distance (meters)", "Seconds",
        "Notes", "Workout Notes", "RPE",
    )

    private fun dateFormat(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    /** Wrap a field in double quotes, escaping any internal double quotes as "". */
    private fun q(s: String?): String = "\"${(s ?: "").replace("\"", "\"\"")}\""

    /**
     * Export all workouts and their sets as an import-format CSV string.
     *
     * @param workouts All workouts, ordered by startTime ascending (oldest first).
     * @param setsByWorkout Map of workoutId → ordered set list.
     * @param exercisesById Map of exerciseId → Exercise.
     */
    fun exportCsv(
        workouts: List<Workout>,
        setsByWorkout: Map<Long, List<SetEntry>>,
        exercisesById: Map<Long, Exercise>,
    ): String {
        val sb = StringBuilder()
        val df = dateFormat()

        // Header row
        sb.append(HEADER.joinToString(SEP) { q(it) })
        sb.append("\n")

        workouts.forEachIndexed { wIndex, workout ->
            val sets = setsByWorkout[workout.id] ?: return@forEachIndexed
            val workoutNum = workout.importedWorkoutNumber ?: (wIndex + 1)
            val dateStr = df.format(java.util.Date(workout.startTime))

            sets.groupBy { it.exerciseId }.forEach { (exId, exSets) ->
                val exercise = exercisesById[exId] ?: return@forEach
                exSets.forEachIndexed { setIdx, set ->
                    val weightKg = set.weightLb?.let { it / 2.20462 }
                    sb.append(
                        listOf(
                            q(workoutNum.toString()),
                            q(dateStr),
                            q(workout.name),
                            q(workout.durationSec?.toString() ?: ""),
                            q(exercise.name),
                            q((setIdx + 1).toString()),
                            q(weightKg?.let { "%.6f".format(it) } ?: ""),
                            q(set.reps?.toString() ?: ""),
                            q(set.distanceMeters?.toString() ?: ""),
                            q(set.seconds?.toString() ?: ""),
                            q(set.notes ?: ""),
                            q(workout.notes ?: ""),
                            q(set.rpe?.toString() ?: ""),
                        ).joinToString(SEP),
                    )
                    sb.append("\n")
                }
            }
        }

        return sb.toString()
    }

    /** Convenience: lb → kg rounded to match the export format's precision. */
    fun lbToKg(lb: Double): Double = lb / 2.20462
}
