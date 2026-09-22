package dev.ironlog.app.timer

/**
 * F3: the "no rest timer" state for an exercise.
 *
 * `Exercise.restSeconds == null` already means "inherit the app default" (see
 * `IronlogViewModel.startRest(ex.restSeconds ?: defaultRestSec)`), so null can't also mean "off"
 * without breaking that inherited-default case. `0` is the off sentinel instead: it survives the
 * `?:` elvis untouched (0 is not null) and is unambiguous everywhere restSeconds is read.
 * Pure Kotlin -- no Android deps -- so [isEnabled]/[label] are directly JVM-testable.
 */
object RestTimerConfig {

    const val OFF_SECONDS = 0

    /** True when a rest countdown should actually run (bar + alarm). False = off. */
    fun isEnabled(seconds: Int): Boolean = seconds > OFF_SECONDS

    /** Button/dialog label: "Off" when disabled, otherwise the usual m:ss clock string. */
    fun label(seconds: Int, clock: (Int) -> String): String =
        if (isEnabled(seconds)) clock(seconds) else "Off"
}
