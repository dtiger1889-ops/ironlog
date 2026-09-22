package dev.ironlog.app.data.importer

/**
 * The previous tracker stores weight as kg floats even though this app logs in pounds, producing ugly
 * values like 34.01942775 kg (== exactly 75 lb).  We convert back to pounds and round to
 * the nearest 0.5 lb so the float noise never surfaces, while still preserving any genuine
 * microplate (1.25/2.5 lb) entry.  ironlog's canonical unit is pounds.
 */
object WeightConversion {

    /** Exact international pound. */
    const val KG_PER_LB: Double = 0.45359237

    fun kgToLbRaw(kg: Double): Double = kg / KG_PER_LB

    /** Convert lb -> kg (exact). Used by the Settings lb<->kg converter display. */
    fun lbToKg(lb: Double): Double = lb * KG_PER_LB

    /** Convert kg -> lb and snap to the nearest half pound. */
    fun kgToLbRounded(kg: Double): Double = Math.round(kgToLbRaw(kg) * 2.0) / 2.0
}
