package dev.ironlog.app.session

import dev.ironlog.app.data.Exercise
import dev.ironlog.app.data.TemplateExerciseDetail
import dev.ironlog.app.data.TemplateSetValue

/**
 * Computes the updated per-exercise template details for the OPT-IN "save to template" boxes on
 * the Complete-workout dialog. Pure function: the caller decides whether to persist anything.
 * With both flags false the template is returned untouched — a workout can never edit a template
 * unless the user explicitly ticked a box (Fix 2 stays intact).
 */
object TemplateSync {

    /** Working (non-warm-up) sets are the template unit; warm-ups never enter a template. */
    private fun draftSetValues(ex: DraftExercise): List<TemplateSetValue> =
        ex.sets.filter { !it.isWarmup }.map {
            TemplateSetValue(it.weightLb, it.reps, it.seconds, it.distanceMeters)
        }

    /**
     * @param updateValues  overwrite the template's per-set weight/reps with today's numbers.
     * @param updateStructure  make the template's exercise list + set counts match today's workout.
     * @param exercisesById  catalog lookup for exercises ADDED during the workout (not yet in the
     *   template); an added exercise missing from the map is skipped rather than half-written.
     */
    fun updatedDetails(
        current: List<TemplateExerciseDetail>,
        draft: DraftWorkout,
        exercisesById: Map<Long, Exercise>,
        updateValues: Boolean,
        updateStructure: Boolean,
    ): List<TemplateExerciseDetail> {
        if (!updateValues && !updateStructure) return current
        val currentByExId = current.associateBy { it.exercise.id }
        val draftByExId = draft.exercises.associateBy { it.exerciseId }

        return if (updateStructure) {
            // Template becomes today's exercise list + set counts. Values come from today when
            // updateValues is also ticked (or for slots the template never had); otherwise the
            // template's stored values are kept positionally.
            draft.exercises.mapNotNull { dex ->
                val existing = currentByExId[dex.exerciseId]
                val exercise = existing?.exercise ?: exercisesById[dex.exerciseId]
                    ?: return@mapNotNull null
                val fromDraft = draftSetValues(dex)
                if (fromDraft.isEmpty()) return@mapNotNull null
                val sets = if (updateValues || existing == null) fromDraft
                else fromDraft.indices.map { i -> existing.sets.getOrNull(i) ?: fromDraft[i] }
                TemplateExerciseDetail(
                    exercise = exercise,
                    targetSets = sets.size,
                    targetRepsLow = existing?.targetRepsLow,
                    targetRepsHigh = existing?.targetRepsHigh,
                    targetRestSec = existing?.targetRestSec ?: dex.restSeconds,
                    sets = sets,
                )
            }
        } else {
            // Values only: the template keeps its exercises AND set counts; today's numbers are
            // written positionally. Exercises skipped today (or with no working sets) keep their
            // stored values; extra sets logged today beyond the template's count are ignored.
            current.map { detail ->
                val dex = draftByExId[detail.exercise.id] ?: return@map detail
                val fromDraft = draftSetValues(dex)
                if (fromDraft.isEmpty() || detail.sets.isEmpty()) return@map detail
                detail.copy(
                    sets = detail.sets.mapIndexed { i, old -> fromDraft.getOrNull(i) ?: old },
                )
            }
        }
    }
}
