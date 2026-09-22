package dev.ironlog.app.metrics

/**
 * Plate calculator: given a target weight, a bar weight, and available plates,
 * computes the closest achievable per-side breakdown.
 *
 * Pure Kotlin / JVM -- no Android dependencies.
 */
object PlateMath {

    /**
     * One plate denomination: how heavy it is and how many pairs are available.
     */
    data class PlateStock(val plateLb: Double, val pairCount: Int)

    /**
     * Result of a plate calculation.
     *
     * [platesPerSide] is an ordered list (heaviest first) of plates to load on each side.
     * [achievedLb] is the total weight that would result (bar + 2 * sum of platesPerSide).
     * [deltaLb] is achievedLb - targetLb (0 = exact; positive = slightly heavy; negative = under).
     */
    data class PlateResult(
        val platesPerSide: List<Double>,
        val achievedLb: Double,
        val deltaLb: Double,
    )

    /**
     * Greedy per-side calculation. Uses [barLb] as the fixed bar weight.
     * [plates] is a list of available plate denominations and pair counts (sorted by caller or here).
     * Returns the closest achievable breakdown; if no plates are available the bar alone is returned.
     */
    fun calculate(
        targetLb: Double,
        barLb: Double,
        plates: List<PlateStock>,
    ): PlateResult {
        val neededPerSide = (targetLb - barLb) / 2.0
        if (neededPerSide <= 0.0) {
            return PlateResult(emptyList(), barLb, barLb - targetLb)
        }

        // Sort heaviest first for greedy
        val sorted = plates.sortedByDescending { it.plateLb }
        var remaining = neededPerSide
        val chosen = mutableListOf<Double>()

        for (stock in sorted) {
            var usable = stock.pairCount
            while (usable > 0 && remaining >= stock.plateLb - 0.001) {
                chosen.add(stock.plateLb)
                remaining -= stock.plateLb
                usable--
            }
        }

        val sumPerSide = chosen.sum()
        val achieved = barLb + sumPerSide * 2.0
        return PlateResult(
            platesPerSide = chosen,
            achievedLb = achieved,
            deltaLb = achieved - targetLb,
        )
    }

    /** Default plate set matching a typical commercial gym setup (pairs). */
    val DEFAULT_PLATES = listOf(
        PlateStock(45.0, 4),
        PlateStock(35.0, 2),
        PlateStock(25.0, 2),
        PlateStock(10.0, 4),
        PlateStock(5.0, 4),
        PlateStock(2.5, 2),
    )
}
