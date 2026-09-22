package dev.ironlog.app.metrics

/**
 * F2: per-exercise plate-calculator gating + base-weight resolution.
 *
 * Historically the plate calculator was barbell-only (`equipment == "barbell"` or the name
 * contains "barbell"), which regressed a real exercise (seated calf raise, a machine with a
 * fixed 95 kg base) that used to have a calculator. `Exercise.plateCalcMode` lets a person force
 * it on for a machine or off for a barbell lift; `Exercise.barWeightLb` supplies that machine's
 * base weight instead of the bar. Pure Kotlin / JVM -- no Android/Room deps.
 */
object PlateCalcConfig {

    const val MODE_ON = "ON"
    const val MODE_OFF = "OFF"

    /**
     * Whether the plate calculator should be shown for this exercise. An explicit [mode]
     * (`"ON"`/`"OFF"`) always wins; `null` falls back to the auto-detect heuristic (barbell
     * equipment or a name containing "barbell").
     */
    fun shouldShow(mode: String?, equipment: String?, name: String): Boolean = when (mode) {
        MODE_ON -> true
        MODE_OFF -> false
        else -> equipment.equals("barbell", ignoreCase = true) ||
            name.contains("barbell", ignoreCase = true)
    }

    /**
     * The base/bar weight to feed the calculator: the exercise's own override beats the global
     * default -- including `0.0`, which means "no bar" (leg press, a plate-loaded machine with no
     * bar weight of its own), so it must NOT be treated as "unset" the way `null` is.
     */
    fun effectiveBarWeight(barWeightLb: Double?, defaultBarLb: Double): Double =
        barWeightLb ?: defaultBarLb
}
