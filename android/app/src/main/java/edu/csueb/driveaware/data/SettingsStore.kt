package edu.csueb.driveaware.data

import android.content.Context
import edu.csueb.driveaware.signals.Baseline
import edu.csueb.driveaware.signals.Thresholds

/** Thresholds + calibration baseline, saved on device (SharedPreferences). */
class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("driveaware", Context.MODE_PRIVATE)
    private val defaults = Thresholds()

    fun thresholds(): Thresholds {
        val d = defaults
        return Thresholds(
            earClosedRatio = p.getFloat("earClosedRatio", d.earClosedRatio),
            blinkScoreClosed = p.getFloat("blinkScoreClosed", d.blinkScoreClosed),
            longClosureS = p.getFloat("longClosureS", d.longClosureS),
            severeClosureS = p.getFloat("severeClosureS", d.severeClosureS),
            perclosHigh = p.getFloat("perclosHigh", d.perclosHigh),
            yawnMar = p.getFloat("yawnMar", d.yawnMar),
            yawnMinS = p.getFloat("yawnMinS", d.yawnMinS),
            nodPitchDeg = p.getFloat("nodPitchDeg", d.nodPitchDeg),
            gazeYawDeg = p.getFloat("gazeYawDeg", d.gazeYawDeg),
            gazeMaxS = p.getFloat("gazeMaxS", d.gazeMaxS),
            activityConf = p.getFloat("activityConf", d.activityConf),
            activityFrac = p.getFloat("activityFrac", d.activityFrac),
            sunglassesHoldS = p.getFloat("sunglassesHoldS", d.sunglassesHoldS),
            sunglassesRatio = p.getFloat("sunglassesRatio", d.sunglassesRatio),
            sunglassesBrightness = p.getFloat("sunglassesBrightness", d.sunglassesBrightness),
            sunglassesLaplacian = p.getFloat("sunglassesLaplacian", d.sunglassesLaplacian),
        )
    }

    fun setFloat(key: String, v: Float) = p.edit().putFloat(key, v).apply()

    /** One line about the last trip, shown on Home (proposal screen 1). */
    fun lastTrip(): String? = p.getString("lastTrip", null)
    fun saveLastTrip(s: String) = p.edit().putString("lastTrip", s).apply()

    fun baseline(): Baseline? =
        if (!p.contains("baseEar")) null
        else Baseline(p.getFloat("baseEar", 0.28f), p.getFloat("baseYaw", 0f), p.getFloat("basePitch", 0f))

    fun saveBaseline(b: Baseline) = p.edit()
        .putFloat("baseEar", b.ear).putFloat("baseYaw", b.yawDeg).putFloat("basePitch", b.pitchDeg).apply()

    /** Editable in the Settings screen: key, label, min, max. */
    val editable = listOf(
        Triple("earClosedRatio", "Eye closed ratio (× baseline EAR)", 0.5f to 0.9f),
        Triple("blinkScoreClosed", "Eye closed: blink score ≥", 0.2f to 0.9f),
        Triple("longClosureS", "Long eye closure (s)", 0.5f to 2.5f),
        Triple("severeClosureS", "Severe closure → level 3 (s)", 1.0f to 3.0f),
        Triple("perclosHigh", "PERCLOS high (fraction)", 0.05f to 0.40f),
        Triple("yawnMar", "Yawn mouth ratio", 0.3f to 1.0f),
        Triple("yawnMinS", "Yawn min duration (s)", 0.5f to 4f),
        Triple("nodPitchDeg", "Nod head drop (°)", 5f to 35f),
        Triple("gazeYawDeg", "Gaze away yaw (°)", 15f to 60f),
        Triple("gazeMaxS", "Gaze away time (s)", 1f to 5f),
        Triple("activityConf", "Model A confidence", 0.3f to 0.95f),
        Triple("activityFrac", "Model A: share of frames over 2 s", 0.4f to 1.0f),
        Triple("sunglassesBrightness", "Sunglasses: eye brightness", 10f to 120f),
        Triple("sunglassesLaplacian", "Sunglasses: eye sharpness", 5f to 100f),
        Triple("sunglassesHoldS", "Sunglasses: hold time (s)", 1f to 6f),
        Triple("sunglassesRatio", "Sunglasses: eye/cheek brightness below", 0.3f to 0.9f),
    )
}
