package dev.ironlog.app.progression

import dev.ironlog.app.data.Exercise
import dev.ironlog.app.data.SetEntry
import dev.ironlog.app.data.Workout

/**
 * Read-only replay of the progression rules over the full logged history (roadmap §M4).
 *
 * Walks each exercise's sessions oldest..newest and, at each step, asks the same rules the live
 * Coach uses how often they WOULD have fired. This is INSIGHT ("the 2-for-2 rule would have
 * triggered N times on Bench over 4 years"), never target-tuning -- it reads his history but feeds
 * nothing back into the targets.
 *
 * HARD GUARDRAIL: writes NOTHING. No Room/DAO dependency; takes plain lists, returns a summary.
 * Shares its session/top-set construction with the live feed (SuggestionEngine.buildExerciseInputs).
 *
 * Pure Kotlin / JVM.
 */
object Backtest {

    data class ExerciseBacktest(
        val exerciseId: Long,
        val exerciseName: String,
        val sessionsAnalyzed: Int,
        val addLoadSignals: Int,
        val stallSignals: Int,
    )

    data class Result(
        val perExercise: List<ExerciseBacktest>,
        val totalSessions: Int,
        val totalAddLoadSignals: Int,
        val totalStallSignals: Int,
    )

    /**
     * @param defaultGoal app default rep range (per-exercise override still wins).
     */
    fun run(
        sets: List<SetEntry>,
        workouts: List<Workout>,
        exercises: List<Exercise>,
        defaultGoal: GoalPreset = GoalPreset.DEFAULT,
    ): Result {
        val inputs = SuggestionEngine.buildExerciseInputs(sets, workouts, exercises)

        val perExercise = mutableListOf<ExerciseBacktest>()
        var totalSessions = 0
        var totalAdd = 0
        var totalStall = 0

        for (input in inputs) {
            val ex = input.exercise
            val topSets = input.sessions.mapNotNull { it.topSet }
            if (topSets.size < 2) continue

            val targetHigh = SuggestionEngine.resolveRepRangeHigh(ex, defaultGoal)
            val increment = IncrementSeeder.seedIncrementLb(ex)

            var addSignals = 0
            var stallSignals = 0

            // Replay: at each session i (>=1), evaluate the rules on the window ending at i.
            for (i in 1 until topSets.size) {
                val window = topSets.subList(0, i + 1)

                val dpSets = window.mapNotNull { t ->
                    val w = t.weightLb ?: return@mapNotNull null
                    val r = t.reps ?: return@mapNotNull null
                    DoubleProgression.TopSet(w, r)
                }
                if (DoubleProgression.evaluate(dpSets, targetHigh, increment).fires) addSignals++

                val perf = window.map { SuggestionEngine.perfScalar(it) }
                if (StallDetector.evaluate(perf, window.last().weightLb).stalled) stallSignals++
            }

            perExercise += ExerciseBacktest(
                exerciseId = ex.id,
                exerciseName = ex.name,
                sessionsAnalyzed = topSets.size,
                addLoadSignals = addSignals,
                stallSignals = stallSignals,
            )
            totalSessions += topSets.size
            totalAdd += addSignals
            totalStall += stallSignals
        }

        return Result(
            perExercise = perExercise.sortedByDescending { it.sessionsAnalyzed },
            totalSessions = totalSessions,
            totalAddLoadSignals = totalAdd,
            totalStallSignals = totalStall,
        )
    }
}
