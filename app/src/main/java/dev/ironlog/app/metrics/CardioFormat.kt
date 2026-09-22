package dev.ironlog.app.metrics

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Distance + duration formatting for cardio and timed work.
 *
 * Storage is always metric-neutral (meters + whole seconds) so the database never carries a
 * unit assumption; the UI reads and writes MILES and m:ss because that is how the log is
 * kept.  Pure Kotlin (no Android) so every parse/format rule is unit tested on the JVM.
 */
object CardioFormat {

    /** Exact international mile. */
    const val METERS_PER_MILE = 1609.344

    /** Below this, a duration reads better as a bare second count ("30 s" for a plank). */
    const val CLOCK_THRESHOLD_SECONDS = 60

    fun milesToMeters(miles: Double): Double = miles * METERS_PER_MILE

    fun metersToMiles(meters: Double): Double = meters / METERS_PER_MILE

    /** Miles to 2 dp for display/editing ("1.00"); blank when there is no distance. */
    fun formatMiles(meters: Double?): String =
        meters?.let { String.format(Locale.US, "%.2f", metersToMiles(it)) } ?: ""

    /**
     * Lenient miles entry -> meters.  Accepts "1", "1.25", " 1.25 " and a stray "mi" suffix.
     * Returns null for blank or unparseable text (the field then stays empty).
     */
    fun parseMilesToMeters(text: String): Double? {
        val cleaned = text.trim().removeSuffix("mi").removeSuffix("MI").trim()
        val miles = cleaned.toDoubleOrNull() ?: return null
        if (miles < 0) return null
        return milesToMeters(miles)
    }

    /** Whole seconds as m:ss ("11:00", "1:05:30" past an hour); blank when there is no time. */
    fun formatClock(seconds: Int?): String {
        if (seconds == null) return ""
        val s = if (seconds < 0) 0 else seconds
        val hours = s / 3600
        val minutes = (s % 3600) / 60
        val secs = s % 60
        return if (hours > 0) {
            "$hours:${minutes.pad()}:${secs.pad()}"
        } else {
            "$minutes:${secs.pad()}"
        }
    }

    /**
     * Lenient duration entry -> whole seconds.
     *
     * Colon forms are unambiguous: "11:00" = 11 min, "1:05:30" = 1 h 5 min 30 s.  A bare number
     * is read as MINUTES when [bareIsMinutes] (cardio: "11" and "11.5" mean 11:00 and 11:30)
     * and as SECONDS otherwise (a timed hold: "30" means 30 s).
     */
    fun parseClockToSeconds(text: String, bareIsMinutes: Boolean): Int? {
        val cleaned = text.trim().removeSuffix("s").trim()
        if (cleaned.isEmpty()) return null
        if (cleaned.contains(':')) {
            val parts = cleaned.split(':')
            if (parts.size > 3) return null
            val nums = parts.map { it.trim().toDoubleOrNull() ?: return null }
            if (nums.any { it < 0 }) return null
            val total = when (nums.size) {
                2 -> nums[0] * 60 + nums[1]
                else -> nums[0] * 3600 + nums[1] * 60 + nums[2]
            }
            return total.roundToInt()
        }
        val value = cleaned.toDoubleOrNull() ?: return null
        if (value < 0) return null
        return if (bareIsMinutes) (value * 60).roundToInt() else value.roundToInt()
    }

    /**
     * A duration in a read-only summary: a short hold stays a plain second count ("30 s"),
     * anything a minute or longer becomes a clock ("11:00").
     */
    fun formatDurationLabel(seconds: Int?): String {
        if (seconds == null) return "—"
        return if (seconds < CLOCK_THRESHOLD_SECONDS) "$seconds s" else formatClock(seconds)
    }

    /** Pace per mile ("11:00 /mi"), or null when either half is missing or zero. */
    fun paceLabel(meters: Double?, seconds: Int?): String? {
        if (meters == null || seconds == null) return null
        val miles = metersToMiles(meters)
        if (miles <= 0.0 || seconds <= 0) return null
        val secondsPerMile = (seconds / miles).roundToInt()
        return "${formatClock(secondsPerMile)} /mi"
    }

    /**
     * One cardio set as text: "1.00 mi · 11:00 · 11:00 /mi".  Whichever halves exist are shown;
     * pace is appended only when both distance and time are present.
     */
    fun cardioSummary(meters: Double?, seconds: Int?): String {
        val parts = mutableListOf<String>()
        if (meters != null) parts += "${formatMiles(meters)} mi"
        if (seconds != null) parts += formatClock(seconds)
        paceLabel(meters, seconds)?.let { parts += it }
        return if (parts.isEmpty()) "—" else parts.joinToString(" · ")
    }

    private fun Int.pad(): String = toString().padStart(2, '0')
}
