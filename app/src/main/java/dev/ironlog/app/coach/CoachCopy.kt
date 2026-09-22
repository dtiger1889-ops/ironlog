package dev.ironlog.app.coach

/**
 * M7: plain-language template strings for the Coach feed.
 * No invented codes/acronyms — clear English only. Numbers come from cited engine outputs
 * (M3 PRs / M4 suggestions / M5 coverage) — CoachCopy never computes new values.
 */
object CoachCopy {

    fun pr(name: String, weightLb: Double?, reps: Int?, seconds: Int?): String = when {
        weightLb != null && reps != null ->
            "New PR on $name: ${fmtLb(weightLb)} × $reps reps!"
        reps != null ->
            "New PR on $name: $reps reps!"
        seconds != null ->
            "New PR on $name: ${seconds}s hold!"
        else ->
            "New PR on $name!"
    }

    fun neglected(muscle: String, daysSince: Int): String =
        "${muscle.replaceFirstChar { it.uppercase() }} hasn't been trained in $daysSince days."

    /** #23: same recency fact, but the next planned session already schedules it — so reassure
     *  instead of nagging (the plan is an explicit selection, never an inferred schedule). */
    fun neglectedButPlanned(muscle: String, daysSince: Int): String =
        "${muscle.replaceFirstChar { it.uppercase() }} hasn't been trained in $daysSince days — your next plan already covers it."

    fun neverIsolated(muscle: String): String =
        "${muscle.replaceFirstChar { it.uppercase() }} has only had indirect work in the last 4 weeks — no direct sets."

    /** #23: never-isolated, but the next planned session adds direct work for it. */
    fun neverIsolatedButPlanned(muscle: String): String =
        "${muscle.replaceFirstChar { it.uppercase() }} has only had indirect work lately — your next plan adds direct sets."

    fun flagFree(): String = "Good session — everything's on track."

    private fun fmtLb(lb: Double): String =
        if (lb == lb.toLong().toDouble()) "${lb.toLong()} lb" else "${"%.1f".format(lb)} lb"
}
