package dev.ironlog.app.coach

import dev.ironlog.app.data.Exercise
import dev.ironlog.app.data.SetEntry
import dev.ironlog.app.gym.MuscleAssistant
import dev.ironlog.app.progression.SuggestionEngine

/**
 * M7: compose M3 PRs + M4 progression/volume suggestions + M5 neglect into a CoachSummaryData.
 *
 * HARD GUARDRAIL: this object only READS engine outputs and FORMATS them. It never computes
 * new numbers — every value it surfaces already came from a cited engine (M4) or a logged PR (M3).
 * No Room/Android dependencies — fully JVM-testable.
 */
object CoachEngine {

    /**
     * Grouped plain-language lines ready for display.
     * Each group is only non-empty when there is something to say.
     * [hasContent] is false on a flag-free day → the caller shows [CoachCopy.flagFree()] instead.
     */
    data class CoachSummaryData(
        val prLines: List<String>,
        val progressionLines: List<String>,
        val volumeLines: List<String>,
        val neglectLines: List<String>,
    ) {
        val hasContent: Boolean
            get() = prLines.isNotEmpty() || progressionLines.isNotEmpty() ||
                volumeLines.isNotEmpty() || neglectLines.isNotEmpty()
    }

    /**
     * Build a [CoachSummaryData] for one finished workout.
     *
     * @param prSets      working sets with [SetEntry.isPR] = true from the just-finished workout
     * @param exercises   full exercise catalog (id → Exercise) for name lookup
     * @param suggestions current SuggestionEngine feed (M4) — messages are quoted verbatim;
     *                    this engine never re-derives numbers
     * @param neglect     MuscleAssistant.coverageStatus output (M5) — only stale/neverIsolated
     *                    entries generate lines
     * @param plannedMuscles #23: lowercase muscle tokens the explicitly-selected next plan/template
     *                    already schedules (from PlanEntry exercises' primaryMuscles). A neglect
     *                    line for a planned muscle is reframed as "your next plan covers it" rather
     *                    than nagging. Empty = no plan selected → plain neglect lines (unchanged).
     *                    Only explicit selections feed this; the engine never infers a schedule.
     */
    fun summarize(
        prSets: List<SetEntry>,
        exercises: Map<Long, Exercise>,
        suggestions: List<SuggestionEngine.Suggestion>,
        neglect: List<MuscleAssistant.MuscleStatus>,
        plannedMuscles: Set<String> = emptySet(),
    ): CoachSummaryData {
        val prLines = buildPrLines(prSets, exercises)
        val progressionLines = suggestions
            .filter {
                it.kind == SuggestionEngine.Kind.ADD_LOAD ||
                    it.kind == SuggestionEngine.Kind.DELOAD ||
                    it.kind == SuggestionEngine.Kind.TIMED_PROGRESS
            }
            .map { it.message }  // SuggestionEngine messages are already plain-language
        val volumeLines = suggestions
            .filter { it.kind == SuggestionEngine.Kind.VOLUME_LOW }
            .map { it.message }
        val neglectLines = buildNeglectLines(neglect, plannedMuscles)

        return CoachSummaryData(prLines, progressionLines, volumeLines, neglectLines)
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun buildPrLines(prSets: List<SetEntry>, exercises: Map<Long, Exercise>): List<String> =
        prSets
            .filter { !it.isWarmup }
            .groupBy { it.exerciseId }
            .mapNotNull { (exId, sets) ->
                val name = exercises[exId]?.name ?: return@mapNotNull null
                // Pick the best set: heaviest weight → most reps → longest hold
                val best = sets.maxWithOrNull(
                    compareBy(
                        { it.weightLb ?: 0.0 },
                        { it.reps ?: 0 },
                        { it.seconds ?: 0 },
                    ),
                ) ?: return@mapNotNull null
                CoachCopy.pr(name, best.weightLb, best.reps, best.seconds)
            }

    private fun buildNeglectLines(
        statuses: List<MuscleAssistant.MuscleStatus>,
        plannedMuscles: Set<String>,
    ): List<String> =
        statuses
            .filter { it.stale || it.neverIsolated }
            .mapNotNull { ms ->
                // #23: a muscle the next plan already schedules gets a reassuring, plan-aware line
                // instead of a nag. Match on the same lowercase token vocabulary as coverageStatus.
                val planned = ms.muscle.lowercase() in plannedMuscles
                when {
                    ms.neverIsolated ->
                        if (planned) CoachCopy.neverIsolatedButPlanned(ms.muscle)
                        else CoachCopy.neverIsolated(ms.muscle)
                    ms.stale && ms.daysSince != null ->
                        if (planned) CoachCopy.neglectedButPlanned(ms.muscle, ms.daysSince)
                        else CoachCopy.neglected(ms.muscle, ms.daysSince)
                    else -> null
                }
            }
}
