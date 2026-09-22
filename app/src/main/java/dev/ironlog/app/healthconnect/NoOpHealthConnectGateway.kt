package dev.ironlog.app.healthconnect

/**
 * No-op gateway used when Health Connect is not installed or on devices below the required
 * SDK level. The app runs identically; the Measure tab shows "Health Connect not available."
 */
class NoOpHealthConnectGateway : HealthConnectGateway {
    override suspend fun isAvailable(): Boolean = false
    override suspend fun writeWeightLb(lb: Double, timestampMs: Long): String? = null
    override suspend fun writeBodyFatPct(pct: Double, timestampMs: Long): String? = null
    override suspend fun writeExerciseSession(name: String, startMs: Long, durationSec: Int): String? = null
    override suspend fun readWeightLbSince(sinceMs: Long): List<HcWeightReading> = emptyList()
}
