package dev.ironlog.app.data

/**
 * How an exercise is measured. First-class so PT/mobility and timed work are real types,
 * not weight-and-reps workarounds (ironlog v1 spec).
 */
enum class ExerciseType {
    /** Load x reps (barbell, dumbbell, machine, cable). */
    WEIGHTED,

    /** Reps only, no external load (PT moves, planks-by-reps, calisthenics). */
    BODYWEIGHT,

    /** Held/performed for time (the Seconds column on import) -- planks, dead hangs, stretches. */
    TIMED,

    /** Distance-based (carries logged in the Distance column on import). */
    DISTANCE,

    /**
     * Distance AND time together -- treadmill, bike, rower, row-erg intervals.  These sets
     * carry both a distance and a duration, so a single column (seconds alone) throws away
     * half the set and can't show pace.
     */
    CARDIO,
}
