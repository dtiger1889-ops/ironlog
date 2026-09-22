package dev.ironlog.app.share

import dev.ironlog.app.data.Exercise
import dev.ironlog.app.data.ExerciseType
import dev.ironlog.app.data.SetEntry
import dev.ironlog.app.metrics.CardioFormat
import dev.ironlog.app.data.Workout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * M6 (data ownership / share): the shareable document model for ONE finished workout.
 *
 * Turns a workout row + its exercises + sets + notes into an ordered list of typed lines.
 * Deliberately pure Kotlin — no Android imports — so the whole document can be asserted in
 * JVM unit tests and the renderer ([WorkoutPdfWriter]) only has to draw text.
 *
 * Set wording is the SAME wording the workout-detail screen shows (HistoryScreen delegates to
 * [setSummary]), so a shared PDF never disagrees with what the user sees in the app.
 */
object WorkoutReport {

    /** One line of the report. The renderer maps each type to a font size / weight. */
    sealed class Line {
        /** Workout / template name. */
        data class Title(val text: String) : Line()

        /** Date, duration — the small lines under the title. */
        data class Subtitle(val text: String) : Line()

        /** One exercise name. */
        data class ExerciseHeading(val text: String) : Line()

        /** One logged set, already numbered and formatted. */
        data class SetLine(val text: String) : Line()

        /** A note (per-exercise or workout-level), or a plain informational line. */
        data class Note(val text: String) : Line()

        /** A section label, e.g. "Workout notes". */
        data class SectionHeading(val text: String) : Line()

        /** Vertical breathing room. */
        object Blank : Line()
    }

    /** The finished document: the lines to render plus a file-name stem for the PDF. */
    data class Document(
        val fileNameStem: String,
        val lines: List<Line>,
    )

    /**
     * Build the document for one workout.
     *
     * @param sets every set of this workout, in logged order.
     * @param exercisesById the exercise catalog rows the sets point at (for name + type).
     */
    fun build(
        workout: Workout,
        sets: List<SetEntry>,
        exercisesById: Map<Long, Exercise>,
    ): Document {
        val lines = mutableListOf<Line>()

        lines += Line.Title(workout.name.ifBlank { "Workout" })
        lines += Line.Subtitle(formatDay(workout.startTime))
        workout.durationSec?.let { lines += Line.Subtitle("Duration: ${it / 60} min") }
        lines += Line.Blank

        if (sets.isEmpty()) {
            lines += Line.Note("No sets recorded for this workout.")
        } else {
            sets.groupBy { it.exerciseId }.forEach { (exerciseId, exerciseSets) ->
                val exercise = exercisesById[exerciseId]
                lines += Line.ExerciseHeading(exercise?.name ?: "Exercise #$exerciseId")
                exerciseSets.forEachIndexed { i, s ->
                    lines += Line.SetLine("${i + 1}.  ${setLabel(s, exercise?.type)}")
                }
                // Per-exercise notes for this session live on the sets; show each distinct one once.
                exerciseSets.mapNotNull { it.notes?.trim()?.ifBlank { null } }
                    .distinct()
                    .forEach { lines += Line.Note("Note: $it") }
                lines += Line.Blank
            }
        }

        val workoutNotes = workout.notes?.trim()?.ifBlank { null }
        if (workoutNotes != null) {
            lines += Line.SectionHeading("Workout notes")
            workoutNotes.lines().forEach { lines += Line.Note(it.trim()) }
        }

        return Document(fileNameStem = fileNameStem(workout), lines = lines.toList())
    }

    /**
     * The set summary the app shows everywhere: "135 lb × 8 reps", "45 sec", "800 m", "12 reps".
     * Warm-up and PR tags are appended by [setLabel]; this is the bare value wording.
     */
    fun setSummary(s: SetEntry, type: ExerciseType?): String = when (type) {
        ExerciseType.TIMED -> CardioFormat.formatDurationLabel(s.seconds)
        ExerciseType.CARDIO -> CardioFormat.cardioSummary(s.distanceMeters, s.seconds)
        ExerciseType.DISTANCE -> "${trimNum(s.distanceMeters)} m"
        ExerciseType.BODYWEIGHT -> "${s.reps ?: 0} reps"
        else -> buildString {
            if (s.weightLb != null) append("${trimNum(s.weightLb)} lb")
            if (s.weightLb != null && s.reps != null) append(" × ")
            if (s.reps != null) append("${s.reps} reps")
            if (s.weightLb == null && s.reps == null) append("—")
        }
    }

    /** [setSummary] plus the warm-up / PR tags. */
    fun setLabel(s: SetEntry, type: ExerciseType?): String = buildString {
        append(setSummary(s, type))
        if (s.isWarmup) append(" (warm-up)")
        if (s.isPR) append(" PR")
    }

    /** e.g. "push-day-2026-07-03" — safe on every filesystem, readable in a mail attachment list. */
    fun fileNameStem(workout: Workout): String {
        val name = workout.name.ifBlank { "workout" }
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "workout" }
        return "$name-${stampFormat().format(Date(workout.startTime))}"
    }

    private fun formatDay(epochMillis: Long): String =
        SimpleDateFormat("EEE, MMM d, yyyy", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(epochMillis))

    private fun stampFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun trimNum(v: Double?): String =
        v?.let { if (it % 1.0 == 0.0) it.toLong().toString() else "%.1f".format(it) } ?: "0"
}
