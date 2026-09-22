package dev.ironlog.app.progression

import dev.ironlog.app.data.Exercise
import dev.ironlog.app.data.ExerciseType
import dev.ironlog.app.data.MuscleVolumeTarget
import dev.ironlog.app.data.SetEntry
import dev.ironlog.app.data.Workout
import dev.ironlog.app.metrics.OneRepMax

/**
 * Composes the cited rules (DoubleProgression / StallDetector / TimedProgression / VolumeLandmarks)
 * into a list of suggestion-only [Suggestion]s for the Coach feed.
 *
 * HARD GUARDRAIL: this module (and every file in progression/) only READS numbers and RETURNS
 * suggestions. It has no Room/DAO dependency -- it cannot write a SetEntry or edit a Template.
 * "Accept" (handled in the repository) persists a ProgressionSuggestion verdict and offers the new
 * load as a PREVIOUS-style prefill HINT only; it never mutates logged sets or templates.
 *
 * EVIDENCE DISCIPLINE: every Suggestion carries a non-empty [Suggestion.citation]; practitioner
 * numbers set [Suggestion.practitionerModel] = true so the UI shows the tag. No target is read
 * from his rep distribution.
 *
 * Pure Kotlin / JVM.
 */
object SuggestionEngine {

    enum class Kind { ADD_LOAD, DELOAD, TIMED_PROGRESS, VOLUME_LOW }

    data class Suggestion(
        /** The exercise this concerns; [VOLUME_BY_MUSCLE_ID] (-1) for muscle-volume suggestions. */
        val exerciseId: Long,
        val exerciseName: String,
        val kind: Kind,
        val message: String,
        /** Prefill hint: suggested new working weight (lb), or null. */
        val machineWeightLb: Double? = null,
        /** Prefill hint: suggested new hold (seconds), or null. */
        val machineSeconds: Int? = null,
        val citation: String,
        val practitionerModel: Boolean,
        /** Stable key (kind + subject + rounded value) so accepted/dismissed ones can be suppressed. */
        val dedupKey: String,
    )

    const val VOLUME_BY_MUSCLE_ID = -1L

    /** One logged working set's measurements (warm-ups already excluded by the caller). */
    data class SetPerf(val weightLb: Double?, val reps: Int?, val seconds: Int?)

    /** One past session of a single exercise (its top working set), oldest..newest by caller. */
    data class SessionPerf(val workoutId: Long, val startTime: Long, val topSet: SetPerf?)

    /** All inputs the engine needs for one exercise. */
    data class ExerciseInput(val exercise: Exercise, val sessions: List<SessionPerf>)

    /**
     * Per-exercise suggestions. Deload (recovery) takes priority over add-load; only one weight
     * suggestion per exercise is emitted.
     */
    fun forExercise(input: ExerciseInput, defaultGoal: GoalPreset): List<Suggestion> {
        val ex = input.exercise
        if (!ex.progressionEnabled) return emptyList()

        return when (ex.type) {
            ExerciseType.WEIGHTED, ExerciseType.BODYWEIGHT -> weightedSuggestions(ex, input.sessions, defaultGoal)
            ExerciseType.TIMED -> timedSuggestions(ex, input.sessions)
            ExerciseType.DISTANCE, ExerciseType.CARDIO -> emptyList()  // distance/cardio progression out of scope
        }
    }

    private fun weightedSuggestions(
        ex: Exercise,
        sessions: List<SessionPerf>,
        defaultGoal: GoalPreset,
    ): List<Suggestion> {
        val tops = sessions.mapNotNull { s -> s.topSet }
        if (tops.isEmpty()) return emptyList()

        // 1) Stall check first (recovery beats progression).
        val perf = tops.map { perfScalar(it) }
        val currentWeight = tops.lastOrNull()?.weightLb
        val stall = StallDetector.evaluate(perf, currentWeight)
        if (stall.stalled) {
            return listOf(
                Suggestion(
                    exerciseId = ex.id,
                    exerciseName = ex.name,
                    kind = Kind.DELOAD,
                    message = "${ex.name}: ${stall.basis}.",
                    machineWeightLb = stall.deloadWeightLb,
                    citation = Citations.DELOAD,
                    practitionerModel = true,  // "2 sessions" count is a flagged heuristic
                    dedupKey = "DELOAD:${ex.id}:${round1(stall.deloadWeightLb)}",
                ),
            )
        }

        // 2) Double progression (needs weighted top sets with reps).
        val targetHigh = resolveRepRangeHigh(ex, defaultGoal)
        val dpSets = tops.mapNotNull { t ->
            val w = t.weightLb ?: return@mapNotNull null
            val r = t.reps ?: return@mapNotNull null
            DoubleProgression.TopSet(w, r)
        }
        val increment = IncrementSeeder.seedIncrementLb(ex)
        val dp = DoubleProgression.evaluate(dpSets, targetHigh, increment)
        if (dp.fires) {
            return listOf(
                Suggestion(
                    exerciseId = ex.id,
                    exerciseName = ex.name,
                    kind = Kind.ADD_LOAD,
                    message = "${ex.name}: ${dp.basis}.",
                    machineWeightLb = dp.newWeightLb,
                    citation = Citations.DOUBLE_PROGRESSION,
                    practitionerModel = false,  // 2-for-2 + ACSM step are cited
                    dedupKey = "ADD_LOAD:${ex.id}:${round1(dp.newWeightLb)}",
                ),
            )
        }
        return emptyList()
    }

    private fun timedSuggestions(ex: Exercise, sessions: List<SessionPerf>): List<Suggestion> {
        val holds = sessions.mapNotNull { it.topSet?.seconds }
        val tp = TimedProgression.evaluate(holds, ex.targetSecHigh)
        if (!tp.fires) return emptyList()
        return listOf(
            Suggestion(
                exerciseId = ex.id,
                exerciseName = ex.name,
                kind = Kind.TIMED_PROGRESS,
                message = "${ex.name}: ${tp.basis}.",
                machineSeconds = tp.newSeconds,
                citation = Citations.TIMED,
                practitionerModel = true,  // +10s step is a flagged analogue
                dedupKey = "TIMED:${ex.id}:${tp.newSeconds}",
            ),
        )
    }

    /**
     * Muscle-volume suggestions: flag any muscle below the cited weekly floor / below MEV.
     *
     * @param targetsByMuscle landmark rows keyed by lowercased muscle token.
     */
    fun volumeSuggestions(
        counts: List<VolumeLandmarks.WeeklySetCount>,
        targetsByMuscle: Map<String, MuscleVolumeTarget>,
    ): List<Suggestion> = counts.mapNotNull { c ->
        val target = targetsByMuscle[c.muscle.lowercase()]
        val band = VolumeLandmarks.classify(c.weightedSets, target)
        val belowFloor = VolumeLandmarks.belowCitedFloor(c.weightedSets)
        // Only nudge when genuinely under-trained: below MEV/MV, or below the cited floor.
        val low = band == VolumeLandmarks.Band.BELOW_MV ||
            band == VolumeLandmarks.Band.MV_TO_MEV ||
            (band == VolumeLandmarks.Band.NO_TARGET && belowFloor)
        if (!low) return@mapNotNull null

        val muscleLabel = c.muscle.replaceFirstChar { it.uppercase() }
        val weighted = round1(c.weightedSets)
        val (msg, cite, practitioner) = when (band) {
            VolumeLandmarks.Band.NO_TARGET ->
                Triple(
                    "$muscleLabel: $weighted sets this week, below the cited >=${VolumeLandmarks.CITED_WEEKLY_FLOOR.toInt()}-set floor.",
                    Citations.WEEKLY_VOLUME_FLOOR,
                    false,
                )
            else ->
                Triple(
                    "$muscleLabel: $weighted weighted sets this week, below MEV (${round1(target!!.mev)}).",
                    Citations.VOLUME_LANDMARKS_PRACTITIONER,
                    true,
                )
        }
        Suggestion(
            exerciseId = VOLUME_BY_MUSCLE_ID,
            exerciseName = muscleLabel,
            kind = Kind.VOLUME_LOW,
            message = msg,
            citation = cite,
            practitionerModel = practitioner,
            dedupKey = "VOLUME_LOW:${c.muscle.lowercase()}",
        )
    }

    // ---- Input building + whole-feed generation (shared by the live Coach feed and Backtest) ----

    /**
     * Build one [ExerciseInput] per non-DISTANCE exercise from raw history. Warm-ups are excluded;
     * each session is reduced to its top working set; sessions are oldest..newest.
     */
    fun buildExerciseInputs(
        sets: List<SetEntry>,
        workouts: List<Workout>,
        exercises: List<Exercise>,
    ): List<ExerciseInput> {
        val workoutTimeById = workouts.associate { it.id to it.startTime }
        val byExercise = sets.filter { !it.isWarmup }.groupBy { it.exerciseId }
        return exercises.mapNotNull { ex ->
            if (ex.type == ExerciseType.DISTANCE || ex.type == ExerciseType.CARDIO) return@mapNotNull null
            val exSets = byExercise[ex.id] ?: return@mapNotNull null
            val sessions = exSets
                .groupBy { it.workoutId }
                .mapNotNull { (wId, wSets) ->
                    val time = workoutTimeById[wId] ?: return@mapNotNull null
                    SessionPerf(wId, time, topWorkingSet(wSets))
                }
                .sortedBy { it.startTime }
            if (sessions.isEmpty()) null else ExerciseInput(ex, sessions)
        }
    }

    /**
     * The full live feed: per-exercise suggestions + muscle-volume suggestions, with any
     * already-handled (accepted/dismissed) [Suggestion.dedupKey]s removed.
     */
    fun generateAll(
        inputs: List<ExerciseInput>,
        defaultGoal: GoalPreset,
        volumeCounts: List<VolumeLandmarks.WeeklySetCount>,
        targetsByMuscle: Map<String, MuscleVolumeTarget>,
        handledDedupKeys: Set<String>,
    ): List<Suggestion> {
        val perEx = inputs.flatMap { forExercise(it, defaultGoal) }
        val vol = volumeSuggestions(volumeCounts, targetsByMuscle)
        return (perEx + vol).filter { it.dedupKey !in handledDedupKeys }
    }

    /** Top working set of a session: heaviest weighted set, else longest hold, else most reps. */
    fun topWorkingSet(sets: List<SetEntry>): SetPerf? {
        if (sets.isEmpty()) return null
        val weighted = sets.filter { it.weightLb != null && it.reps != null }
        if (weighted.isNotEmpty()) {
            val top = weighted.maxByOrNull { it.weightLb!! }!!
            return SetPerf(top.weightLb, top.reps, top.seconds)
        }
        val timed = sets.filter { it.seconds != null }
        if (timed.isNotEmpty()) {
            val top = timed.maxByOrNull { it.seconds!! }!!
            return SetPerf(null, top.reps, top.seconds)
        }
        val reps = sets.filter { it.reps != null }
        if (reps.isNotEmpty()) {
            val top = reps.maxByOrNull { it.reps!! }!!
            return SetPerf(null, top.reps, null)
        }
        return null
    }

    /** Resolve the top of the rep range: per-exercise override -> goalPreset -> app default. */
    fun resolveRepRangeHigh(ex: Exercise, defaultGoal: GoalPreset): Int =
        ex.repRangeHigh
            ?: ex.goalPreset?.let { GoalPreset.fromNameOrDefault(it).high }
            ?: defaultGoal.high

    /** Performance scalar for stall detection: est-1RM (<=12-rep cap) else volume else reps else hold. */
    fun perfScalar(s: SetPerf): Double {
        val w = s.weightLb
        val r = s.reps
        if (w != null && r != null) {
            OneRepMax.hybrid(w, r)?.let { return it }  // honors REP_CAP (<=12)
            return w * r  // heavy high-rep set: fall back to volume (1RM unreliable >12)
        }
        if (r != null) return r.toDouble()  // bodyweight: reps are the signal
        if (s.seconds != null) return s.seconds.toDouble()
        return 0.0
    }

    private fun round1(v: Double?): String =
        if (v == null) "?" else if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
}
