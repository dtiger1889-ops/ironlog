package dev.ironlog.app.healthconnect

/**
 * Pure-Kotlin interface for Health Connect operations. All parameter and return types are
 * primitive/Kotlin so tests can run on the JVM using [NoOpHealthConnectGateway].
 * The real impl ([RealHealthConnectGateway]) is Android-bound and not called in JVM tests.
 */
interface HealthConnectGateway {

    /** True if HC is installed and the user has granted the required permissions. */
    suspend fun isAvailable(): Boolean

    /**
     * Write a bodyweight reading to HC. Returns the HC record ID, or null on failure.
     * [lb] is converted to kg internally before the write.
     */
    suspend fun writeWeightLb(lb: Double, timestampMs: Long): String?

    /** Write a body-fat percentage reading. Returns the HC record ID, or null on failure. */
    suspend fun writeBodyFatPct(pct: Double, timestampMs: Long): String?

    /**
     * Write one ExerciseSessionRecord (STRENGTH_TRAINING) after a workout finishes.
     * Requires M2's durationSec. Returns the HC record ID, or null on failure.
     */
    suspend fun writeExerciseSession(name: String, startMs: Long, durationSec: Int): String?

    /**
     * Read all bodyweight records since [sinceMs]. Used to merge HC data into the Measure tab
     * without duplicating rows already imported via [writeWeightLb].
     */
    suspend fun readWeightLbSince(sinceMs: Long): List<HcWeightReading>
}

/** One bodyweight reading pulled from Health Connect. */
data class HcWeightReading(
    val lb: Double,
    val timestampMs: Long,
    val hcRecordId: String,
)
