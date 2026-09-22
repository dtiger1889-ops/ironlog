package dev.ironlog.app.data.importer

import android.content.Context
import androidx.room.withTransaction
import dev.ironlog.app.data.Exercise
import dev.ironlog.app.data.IronlogDatabase
import dev.ironlog.app.data.SetEntry
import dev.ironlog.app.data.Template
import dev.ironlog.app.data.TemplateExercise
import dev.ironlog.app.data.Workout

/** Counts returned after an import, for surfacing in the UI / verifying on-device. */
data class ImportResult(
    val exercises: Int,
    val workouts: Int,
    val sets: Int,
)

/**
 * Reads the bundled previous-tracker export from assets and loads it into Room.  The parse/convert
 * work lives in the pure [CsvHistoryImporter]; this layer only maps natural keys to row ids and
 * writes them in a single transaction.
 */
object BundledHistoryImporter {

    const val ASSET_NAME = "history_export.csv"

    /** Imports the bundled CSV. Safe to call once on an empty DB. Returns row counts. */
    suspend fun importFromAssets(
        context: Context,
        db: IronlogDatabase,
        assetName: String = ASSET_NAME,
    ): ImportResult {
        val csv = context.assets.open(assetName).bufferedReader().use { it.readText() }
        val parsed = CsvHistoryImporter.parse(csv)

        // The owner's exact templates take precedence; inference fills in any others (e.g. cardio).
        val inferred = TemplateInferrer.infer(parsed)
        val templates = CanonicalTemplates.LIST + inferred.filter { it.name !in CanonicalTemplates.NAMES }

        return db.withTransaction {
            // Replace semantics: clear prior data so a re-import reflects the latest parsing
            // (e.g. corrected exercise types) instead of hitting the unique-name constraint.
            // FK-safe order: child tables (referencing exercises with RESTRICT) go first.
            db.setEntryDao().deleteAll()
            db.templateExerciseDao().deleteAll()
            db.workoutDao().deleteAll()
            db.templateDao().deleteAll()
            db.exerciseDao().deleteAll()

            val exerciseIdByName = HashMap<String, Long>(parsed.exercises.size * 2)
            for (e in parsed.exercises) {
                val id = db.exerciseDao().insert(Exercise(name = e.name, type = e.type))
                exerciseIdByName[e.name] = id
            }

            // Seed templates reconstructed from history (the export has no routines).
            for (t in templates) {
                val templateId = db.templateDao().insert(Template(name = t.name))
                t.exerciseNames.forEachIndexed { position, exName ->
                    val exId = exerciseIdByName[exName] ?: return@forEachIndexed
                    db.templateExerciseDao().insert(
                        TemplateExercise(templateId = templateId, exerciseId = exId, position = position),
                    )
                }
            }

            val workoutIdByNumber = HashMap<Int?, Long>(parsed.workouts.size * 2)
            for (w in parsed.workouts) {
                val id = db.workoutDao().insert(
                    Workout(
                        name = w.name,
                        startTime = w.startTimeMillis,
                        durationSec = w.durationSec,
                        notes = w.notes,
                        importedWorkoutNumber = w.importedWorkoutNumber,
                    ),
                )
                workoutIdByNumber[w.importedWorkoutNumber] = id
            }

            val setEntries = parsed.sets.mapNotNull { s ->
                val workoutId = workoutIdByNumber[s.importedWorkoutNumber] ?: return@mapNotNull null
                val exerciseId = exerciseIdByName[s.exerciseName] ?: return@mapNotNull null
                SetEntry(
                    workoutId = workoutId,
                    exerciseId = exerciseId,
                    setOrder = s.setOrder,
                    weightLb = s.weightLb,
                    reps = s.reps,
                    seconds = s.seconds,
                    distanceMeters = s.distanceMeters,
                    rpe = s.rpe,
                    notes = s.notes,
                )
            }
            db.setEntryDao().insertAll(setEntries)

            ImportResult(
                exercises = db.exerciseDao().count(),
                workouts = db.workoutDao().count(),
                sets = db.setEntryDao().count(),
            )
        }
    }
}
