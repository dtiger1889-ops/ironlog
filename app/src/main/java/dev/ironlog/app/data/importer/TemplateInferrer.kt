package dev.ironlog.app.data.importer

/** A routine reconstructed from history: a name and its core exercises, in order. */
data class InferredTemplate(
    val name: String,
    val exerciseNames: List<String>,
)

/**
 * The previous tracker's CSV export contains no template/routine definitions -- only the set log.  So we
 * reconstruct the user's current routines from history: group workouts by their name, keep the
 * ones still run, and take the exercises that show up in most of the recent sessions.
 * Pure Kotlin so it's unit-tested against the real export.
 */
object TemplateInferrer {

    fun infer(
        import: CsvHistoryImport,
        recentWindow: Int = 8,
        maxAgeDays: Long = 365,
        minSessions: Int = 3,
    ): List<InferredTemplate> {
        if (import.workouts.isEmpty()) return emptyList()

        val latest = import.workouts.maxOf { it.startTimeMillis }
        val cutoff = latest - maxAgeDays * MILLIS_PER_DAY

        // Exercises per workout, in first-appearance (performed) order.
        val exercisesByWorkout: Map<Int?, List<String>> = import.sets
            .groupBy { it.importedWorkoutNumber }
            .mapValues { (_, sets) -> sets.map { it.exerciseName }.distinct() }

        val byName = import.workouts.filter { it.name.isNotBlank() }.groupBy { it.name }

        val templates = ArrayList<InferredTemplate>()
        for ((name, sessions) in byName) {
            // Drop one-offs and routines he no longer runs.
            if (sessions.size < minSessions) continue
            if (sessions.none { it.startTimeMillis >= cutoff }) continue

            val recent = sessions.sortedByDescending { it.startTimeMillis }.take(recentWindow)
            val n = recent.size
            val threshold = (n + 1) / 2 // appears in at least half of recent sessions

            val freq = HashMap<String, Int>()
            for (w in recent) {
                exercisesByWorkout[w.importedWorkoutNumber]?.toSet()?.forEach {
                    freq[it] = (freq[it] ?: 0) + 1
                }
            }
            val core = freq.filterValues { it >= threshold }.keys
            if (core.isEmpty()) continue

            // Order by the most recent session's performed order; append any remaining core.
            val ordered = ArrayList<String>()
            exercisesByWorkout[recent.first().importedWorkoutNumber].orEmpty().forEach {
                if (it in core && it !in ordered) ordered.add(it)
            }
            core.sortedByDescending { freq[it] }.forEach { if (it !in ordered) ordered.add(it) }

            templates.add(InferredTemplate(name, ordered))
        }

        // Most recently used routines first.
        return templates.sortedByDescending { t -> byName.getValue(t.name).maxOf { it.startTimeMillis } }
    }

    private const val MILLIS_PER_DAY = 86_400_000L
}
