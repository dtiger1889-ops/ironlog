package dev.ironlog.app.timer

/** Schedules / cancels the end-of-rest alarm that fires even when the app is backgrounded. */
interface RestAlarmScheduler {
    fun schedule(endTimeMs: Long)
    fun cancel()
}
