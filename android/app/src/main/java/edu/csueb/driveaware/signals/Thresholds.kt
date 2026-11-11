package edu.csueb.driveaware.signals

/**
 * Every tunable number in one place. Vanessa tunes these in Settings on
 * VALIDATION clips only and commits thresholds.csv (starting + final values).
 * Defaults are the starting values.
 */
data class Thresholds(
    val earClosedRatio: Float = 0.70f,     // closed when EAR < baselineEar * ratio
    val longClosureS: Float = 1.0f,        // eye_closure_long
    val severeClosureS: Float = 1.0f,      // -> level 3 (labeling_guide: any closure >= 1.0 s is high)
    val blinkMaxS: Float = 0.5f,
    val perclosWindowS: Float = 60f,
    val perclosHigh: Float = 0.15f,
    val yawnMar: Float = 0.60f,
    val yawnJawOpen: Float = 0.55f,
    val yawnMinS: Float = 1.5f,
    val nodPitchDeg: Float = 15f,          // head drop below baseline
    val nodMinS: Float = 0.4f,
    val gazeYawDeg: Float = 30f,
    val gazePitchDeg: Float = 20f,
    val gazeMaxS: Float = 2.0f,            // mirror checks are shorter → no alert
    val activityConf: Float = 0.6f,        // Model A
    val activityFrac: Float = 0.7f,        // of frames over activityWindowS
    val activityWindowS: Float = 2.0f,
    val sunglassesBrightness: Float = 45f, // eye-region mean gray
    val sunglassesLaplacian: Float = 25f,
    val sunglassesHoldS: Float = 3f,
    val refractoryS: Float = 10f,          // same event can't re-fire within this
) {
    fun toCsvRows(): List<Pair<String, String>> =
        this::class.java.declaredFields.filter { !it.isSynthetic }.map {
            it.isAccessible = true; it.name to it.get(this).toString()
        }
}

/** Per-driver baseline from the 10 s calibration screen. */
data class Baseline(
    val ear: Float = 0.28f,
    val yawDeg: Float = 0f,
    val pitchDeg: Float = 0f,
)
