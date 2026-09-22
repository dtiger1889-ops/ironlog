package dev.ironlog.app.session

/**
 * Tracks elapsed wall-clock time for an active workout session.
 * Pure Kotlin -- no Android dependencies; elapsed is computed lazily on demand.
 */
class DurationTracker(private val startedAtMillis: Long) {

    /** Returns elapsed time in whole seconds since the workout started. */
    fun elapsedSec(nowMillis: Long): Int =
        ((nowMillis - startedAtMillis) / 1000L).toInt().coerceAtLeast(0)
}
