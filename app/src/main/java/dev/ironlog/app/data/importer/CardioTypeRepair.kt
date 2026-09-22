package dev.ironlog.app.data.importer

/**
 * One-time repair for databases imported before CARDIO existed.
 *
 * The old majority rule had no distance+time category, so treadmill/bike exercises landed on
 * TIMED and the app showed only a seconds column -- the distance was in the rows the whole
 * time but had nowhere to appear.  This decides, from an exercise's own logged sets, whether
 * it should be re-typed CARDIO.  Pure Kotlin (no Room, no Android) so it is unit tested.
 */
object CardioTypeRepair {

    /** The only fields the decision needs, read straight off the stored set rows. */
    data class StoredSet(
        val weightLb: Double? = null,
        val reps: Int? = null,
        val seconds: Int? = null,
        val distanceMeters: Double? = null,
    )

    /**
     * True when a strict majority of the exercise's logged sets carry BOTH a distance and a
     * duration and none of them carry a load.  The load guard keeps a weighted carry (walking
     * lunges, loaded sled) out of cardio, and the strict majority keeps a lift with one stray
     * distance-and-seconds row where it is.
     */
    fun shouldBeCardio(sets: List<StoredSet>): Boolean {
        if (sets.isEmpty()) return false
        if (sets.any { (it.weightLb ?: 0.0) > 0.0 }) return false
        val cardioSets = sets.count {
            (it.seconds ?: 0) > 0 && (it.distanceMeters ?: 0.0) > 0.0
        }
        return cardioSets * 2 > sets.size
    }
}
