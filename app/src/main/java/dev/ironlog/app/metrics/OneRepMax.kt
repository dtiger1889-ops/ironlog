package dev.ironlog.app.metrics

/**
 * Estimated 1-rep-max formulas.
 *
 * Rule: returns null for reps > 12 -- above that threshold the formulas diverge and produce
 * unreliable results.  This cap is enforced everywhere (roadmap §3 discipline).
 *
 * Practitioner model note: "Hybrid" is a simple average of Epley and Brzycki labeled "app blend"
 * -- it is NOT a published formula.
 *
 * References:
 *   Epley 1985:   w * (1 + r / 30)
 *   Brzycki 1993: w * 36 / (37 - r)
 */
object OneRepMax {

    const val REP_CAP = 12

    /**
     * Epley 1985 formula.
     * Returns null if [reps] > [REP_CAP] or if inputs are non-positive.
     */
    fun epley(weightLb: Double, reps: Int): Double? {
        if (reps > REP_CAP || weightLb <= 0.0 || reps <= 0) return null
        if (reps == 1) return weightLb
        return weightLb * (1.0 + reps / 30.0)
    }

    /**
     * Brzycki 1993 formula.
     * Returns null if [reps] > [REP_CAP] or if inputs are non-positive.
     */
    fun brzycki(weightLb: Double, reps: Int): Double? {
        if (reps > REP_CAP || weightLb <= 0.0 || reps <= 0) return null
        if (reps == 1) return weightLb
        return weightLb * 36.0 / (37.0 - reps)
    }

    /**
     * Hybrid "app blend": simple average of Epley and Brzycki, labeled as a practitioner model.
     * Returns null if either formula returns null.
     */
    fun hybrid(weightLb: Double, reps: Int): Double? {
        val e = epley(weightLb, reps) ?: return null
        val b = brzycki(weightLb, reps) ?: return null
        return (e + b) / 2.0
    }
}
