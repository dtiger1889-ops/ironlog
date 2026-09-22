package dev.ironlog.app.session

import dev.ironlog.app.data.ExerciseType
import dev.ironlog.app.data.SetEntry
import dev.ironlog.app.data.Workout

/**
 * An in-progress workout held entirely in memory.  Nothing is written to the database until
 * [toWorkout]/[toSetEntries] are persisted on Finish -- which is exactly what makes Fix 1
 * (instant discard) trivial: discarding is just dropping this object, with no junk rows to
 * clean up the way the previous tracker forces.  Pure Kotlin (no Android) so the logging rules are unit
 * tested on the JVM.
 */
data class DraftWorkout(
    val name: String,
    val startedAtMillis: Long,
    val templateId: Long? = null,
    val exercises: List<DraftExercise> = emptyList(),
    val notes: String? = null,
    /** Notes queued by "Increase sets next session" — shown for review after finish. */
    val pendingTemplateNotes: List<String> = emptyList(),
    /** When non-null, this draft is an EDIT of an existing workout (M3 edit-past-workout). */
    val editingWorkoutId: Long? = null,
) {
    fun addExercise(exercise: DraftExercise): DraftWorkout =
        copy(exercises = exercises + exercise)

    /** Skipping an exercise in the session only edits THIS draft -- never a template (Fix 2). */
    fun removeExercise(index: Int): DraftWorkout =
        copy(exercises = exercises.filterIndexed { i, _ -> i != index })

    fun updateExercise(index: Int, transform: (DraftExercise) -> DraftExercise): DraftWorkout =
        copy(exercises = exercises.mapIndexed { i, e -> if (i == index) transform(e) else e })

    /** Replace the exercise at [index] with [newExercise], keeping any already-logged sets. */
    fun replaceExercise(index: Int, newExercise: DraftExercise): DraftWorkout =
        copy(exercises = exercises.mapIndexed { i, e ->
            if (i == index) newExercise.copy(sets = e.sets) else e
        })

    /** Move exercise from [from] to [to], preserving all sets on all exercises. */
    fun reorderExercise(from: Int, to: Int): DraftWorkout {
        val list = exercises.toMutableList()
        val item = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), item)
        return copy(exercises = list)
    }

    /** Assign [exIndices] to the same superset group (or null to clear). */
    fun setSuperset(exIndices: List<Int>, group: Int?): DraftWorkout =
        copy(exercises = exercises.mapIndexed { i, e ->
            if (i in exIndices) e.copy(supersetGroup = group) else e
        })

    fun markWarmup(exIdx: Int, setIdx: Int, on: Boolean): DraftWorkout =
        updateExercise(exIdx) { ex ->
            ex.copy(sets = ex.sets.mapIndexed { i, s -> if (i == setIdx) s.copy(isWarmup = on) else s })
        }

    /** Append a new set that copies the last row's values (weight, reps, etc.) so the user
     *  doesn't have to re-enter data. Resets done/warmup so it starts unchecked. */
    fun addSet(exerciseIndex: Int): DraftWorkout =
        updateExercise(exerciseIndex) { ex ->
            val template = ex.sets.lastOrNull()?.copy(done = false, isWarmup = false) ?: DraftSet()
            ex.copy(sets = ex.sets + template.copy(id = ex.nextSetId()))
        }

    /** Insert calculator-generated warm-up rows (weight, reps) ABOVE the working sets. */
    fun addWarmupSets(exerciseIndex: Int, ramp: List<Pair<Double, Int>>): DraftWorkout =
        updateExercise(exerciseIndex) { ex ->
            var nextId = ex.nextSetId()
            val warmups = ramp.map { (lb, reps) ->
                DraftSet(weightLb = lb, reps = reps, isWarmup = true, id = nextId++)
            }
            ex.copy(sets = warmups + ex.sets)
        }

    /** Propagate set [setIndex]'s data values to all sets that follow it in the exercise. */
    fun normalizeFromSet(exerciseIndex: Int, setIndex: Int): DraftWorkout =
        updateExercise(exerciseIndex) { ex ->
            val source = ex.sets.getOrNull(setIndex) ?: return@updateExercise ex
            ex.copy(sets = ex.sets.mapIndexed { i, s ->
                if (i > setIndex) s.copy(
                    weightLb = source.weightLb,
                    reps = source.reps,
                    seconds = source.seconds,
                    distanceMeters = source.distanceMeters,
                ) else s
            })
        }

    /** Queue a next-session template note (shown for review after finish; nothing auto-changes). */
    fun addNextTimeNote(note: String): DraftWorkout =
        copy(pendingTemplateNotes = pendingTemplateNotes + note)

    /** Names of exercises with at least one unchecked set that has logged data (unfinished work). */
    fun unfinishedExerciseNames(): List<String> =
        exercises.filter { ex ->
            ex.sets.any { s ->
                !s.done && (s.weightLb != null || s.reps != null ||
                    s.seconds != null || s.distanceMeters != null)
            }
        }.map { it.name }

    fun updateSet(exerciseIndex: Int, setIndex: Int, transform: (DraftSet) -> DraftSet): DraftWorkout =
        updateExercise(exerciseIndex) { ex ->
            ex.copy(sets = ex.sets.mapIndexed { i, s -> if (i == setIndex) transform(s) else s })
        }

    fun removeSet(exerciseIndex: Int, setIndex: Int): DraftWorkout =
        updateExercise(exerciseIndex) { ex ->
            ex.copy(sets = ex.sets.filterIndexed { i, _ -> i != setIndex })
        }

    /** Remove a set by its stable id (swipe-to-delete; index-safe across recomposition). */
    fun removeSetById(exerciseIndex: Int, setId: Long): DraftWorkout =
        updateExercise(exerciseIndex) { ex ->
            ex.copy(sets = ex.sets.filter { it.id != setId })
        }

    /** True when nothing worth saving has been logged -- finishing is then a no-op/blocked. */
    fun hasNoLoggedData(): Boolean = exercises.none { ex -> ex.sets.any { it.hasData() } }

    /** Working sets: excludes warm-ups from PR/volume/1RM math (M2 load-bearing contract). */
    fun workingSets(): List<DraftSet> = exercises.flatMap { ex -> ex.sets.filter { !it.isWarmup } }

    fun toWorkout(elapsedSec: Int? = null): Workout = Workout(
        name = name.ifBlank { "Workout" },
        startTime = startedAtMillis,
        durationSec = elapsedSec,
        notes = notes,
    )

    /** Only sets with real data are persisted; setOrder is recomputed per exercise. */
    fun toSetEntries(workoutId: Long): List<SetEntry> =
        exercises.flatMap { ex ->
            ex.sets.filter { it.hasData() }.mapIndexed { i, s ->
                SetEntry(
                    workoutId = workoutId,
                    exerciseId = ex.exerciseId,
                    setOrder = i + 1,
                    weightLb = s.weightLb,
                    reps = s.reps,
                    seconds = s.seconds,
                    distanceMeters = s.distanceMeters,
                    rpe = null,
                    notes = null,
                    isWarmup = s.isWarmup,
                    setTag = s.setTag,
                    supersetGroup = ex.supersetGroup,
                )
            }
        }
}

data class DraftExercise(
    val exerciseId: Long,
    val name: String,
    val type: ExerciseType,
    val sets: List<DraftSet> = emptyList(),
    /** Rest seconds to start when a set is checked off (null -> app default). */
    val restSeconds: Int? = null,
    /** Last session's set results, formatted per index, shown gray (the PREVIOUS column). */
    val previous: List<String> = emptyList(),
    /** Which superset group this exercise belongs to (null = not in a superset). */
    val supersetGroup: Int? = null,
    /** Per-exercise note for this session (not persisted beyond finish). */
    val note: String? = null,
    /** Sticky note from the Exercise catalog (shown and editable during workout). */
    val stickyNote: String? = null,
    /** Target rep range from the template (shown as a hint, never auto-filled). */
    val targetSets: Int? = null,
    val targetRepsLow: Int? = null,
    val targetRepsHigh: Int? = null,
    /** Coach prefill hint from an accepted progression suggestion (e.g. "try 190 lb"); never auto-filled. */
    val coachHint: String? = null,
) {
    /** Next unused set id within this exercise (ids are unique per-exercise, stable per session). */
    fun nextSetId(): Long = (sets.maxOfOrNull { it.id } ?: 0L) + 1
}

data class DraftSet(
    val weightLb: Double? = null,
    val reps: Int? = null,
    val seconds: Int? = null,
    val distanceMeters: Double? = null,
    val done: Boolean = false,
    val isWarmup: Boolean = false,
    val setTag: String? = null,
    /** Stable per-session id, unique within its exercise -- the key for swipe-to-delete. */
    val id: Long = 0,
) {
    /** A set is worth saving only when explicitly checked off by the user.
     *  Prefilled values from last session (weight/reps, done=false) must NOT be auto-saved. */
    fun hasData(): Boolean = done
}
