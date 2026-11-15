package edu.csueb.driveaware.signals

/**
 * Every tunable number in one place. Vanessa tunes these in Settings on
 * VALIDATION clips only and commits thresholds.csv (starting + final values).
 * Defaults are the starting values.
 */
data class Thresholds(
    // Final values from tune_thresholds.py on V01-V04 (Vanessa #15, Nov 14): earClosedRatio,
    // perclosHigh, yawnMar, yawnMinS, nodPitchDeg. Start values are in thresholds.csv.
    val earClosedRatio: Float = 0.60f,     // closed when EAR < baselineEar * ratio …
    val blinkScoreClosed: Float = 0.50f,   // … or eyeBlink blendshape >= this (catches quick blinks)
    val longClosureS: Float = 1.0f,        // eye_closure_long
    val severeClosureS: Float = 1.0f,      // -> level 3 (labeling_guide: any closure >= 1.0 s is high)
    val blinkMaxS: Float = 0.5f,
    val perclosWindowS: Float = 60f,
    val perclosHigh: Float = 0.20f,
    val yawnMar: Float = 0.45f,
    val yawnJawOpen: Float = 0.55f,
    val yawnMinS: Float = 0.8f,
    val nodPitchDeg: Float = 20f,          // head drop below baseline
    val nodMinS: Float = 0.4f,
    val gazeYawDeg: Float = 30f,
    val gazePitchDeg: Float = 20f,
    val gazeMaxS: Float = 2.0f,            // mirror checks are shorter → no alert
    val activityConf: Float = 0.6f,        // Model A
    val activityFrac: Float = 0.7f,        // of frames over activityWindowS
    val activityWindowS: Float = 2.0f,
    // Below this cheek brightness the face is too dark to judge sunglasses, so the state is kept
    // (Vanessa: the old "dark and flat" rule said YES for every face in a dark room).
    val sunglassesMinCheek: Float = 35f,
    val sunglassesHoldS: Float = 3f,
    // Issue #60: frames and reflections give sunglasses sharp edges, so dark+flat missed them on a
    // real phone. Main test: eye area much darker than the cheeks (works in any light).
    val sunglassesRatio: Float = 0.6f,     // eye-area brightness / cheek brightness below this = sunglasses
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
