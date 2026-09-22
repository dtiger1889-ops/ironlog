package dev.ironlog.app.metrics

import dev.ironlog.app.data.Workout
import java.util.Calendar
import java.util.TimeZone

/**
 * Pure-Kotlin (JVM-testable) analytics over the workout history list.
 * All date math is in UTC — same convention as the importer.
 */
object WorkoutStats {

    data class StreakResult(
        val currentStreak: Int,
        val longestStreak: Int,
    )

    data class WeekBucket(
        /** ISO week start (Monday), epoch millis UTC midnight. */
        val weekStartMs: Long,
        val count: Int,
    )

    /**
     * Current and longest consecutive-day streaks from [workouts].
     * A "day" is a UTC calendar day. Multiple workouts on the same day count as one.
     * [nowMs] is used to decide whether today has a workout (otherwise a streak extending
     * to yesterday is still "current").
     */
    fun streaks(workouts: List<Workout>, nowMs: Long): StreakResult {
        if (workouts.isEmpty()) return StreakResult(0, 0)

        val days = workouts.map { utcDay(it.startTime) }.toSortedSet()
        val today = utcDay(nowMs)
        val yesterday = today - 1

        // Walk backwards from today or yesterday to count current streak.
        val anchorDay = if (today in days) today else yesterday
        var current = 0
        var d = anchorDay
        while (d in days) {
            current++
            d--
        }
        // If anchor was yesterday and today is not in days, the streak is current only if
        // the user trained yesterday (current already computed correctly above).

        // Longest streak: walk all days in order.
        var longest = 0
        var run = 0
        var prev: Long? = null
        for (day in days) {
            run = if (prev == null || day == prev + 1) run + 1 else 1
            if (run > longest) longest = run
            prev = day
        }

        return StreakResult(
            currentStreak = current,
            longestStreak = longest,
        )
    }

    /**
     * Workouts per week for the last [weeks] calendar weeks (most-recent last).
     * Week starts on Monday (ISO). Each bucket reports the Monday epoch-ms and count.
     */
    fun workoutsPerWeek(workouts: List<Workout>, nowMs: Long, weeks: Int = 12): List<WeekBucket> {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = nowMs
        // Roll back to Monday of the current week.
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) // 1=Sun .. 7=Sat
        val offsetToMonday = (dayOfWeek + 5) % 7      // days to subtract to reach Monday
        cal.add(Calendar.DAY_OF_YEAR, -offsetToMonday)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        // Move to the start of the window.
        val currentWeekStart = cal.timeInMillis
        val windowStart = currentWeekStart - (weeks - 1) * 7L * 86_400_000L

        val buckets = (0 until weeks).map { i ->
            windowStart + i * 7L * 86_400_000L
        }

        val countByBucket = LongArray(weeks)
        for (workout in workouts) {
            val t = workout.startTime
            if (t < windowStart) continue
            val bucketIdx = ((t - windowStart) / (7L * 86_400_000L)).toInt()
            if (bucketIdx < weeks) countByBucket[bucketIdx]++
        }

        return buckets.mapIndexed { i, weekMs ->
            WeekBucket(weekStartMs = weekMs, count = countByBucket[i].toInt())
        }
    }

    /**
     * Which UTC calendar days (as epoch-day integers, same as [utcDay]) in the given
     * [yearMonth] (1-indexed month) had at least one workout.
     */
    fun workoutDaysInMonth(workouts: List<Workout>, year: Int, month: Int): Set<Int> {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        return workouts.mapNotNull { wo ->
            cal.timeInMillis = wo.startTime
            if (cal.get(Calendar.YEAR) == year && cal.get(Calendar.MONTH) + 1 == month)
                cal.get(Calendar.DAY_OF_MONTH)
            else null
        }.toSet()
    }

    /** UTC calendar-day index (days since epoch). Used for streak arithmetic. */
    fun utcDay(epochMs: Long): Long = epochMs / 86_400_000L
}
