package edu.csueb.driveaware.signals

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** Plain-Kotlin geometry on MediaPipe Face Landmarker output (478 normalized landmarks). */
data class Pt(val x: Float, val y: Float, val z: Float = 0f)

data class HeadPose(val yawDeg: Float, val pitchDeg: Float, val rollDeg: Float)

/** One processed camera frame. */
data class FrameSignals(
    val tMs: Long,
    val faceFound: Boolean,
    val ear: Float = 0f,          // mean of both eyes
    val mar: Float = 0f,
    val jawOpen: Float = 0f,      // blendshape 0..1
    val pose: HeadPose = HeadPose(0f, 0f, 0f),
)

object FaceSignals {
    // p1..p6 order for EAR = (|p2-p6| + |p3-p5|) / (2|p1-p4|)
    val LEFT_EYE = intArrayOf(33, 160, 158, 133, 153, 144)
    val RIGHT_EYE = intArrayOf(362, 385, 387, 263, 373, 380)
    // inner lips (13 upper, 14 lower) and mouth corners (78, 308)
    const val LIP_UP = 13; const val LIP_LOW = 14; const val MOUTH_L = 78; const val MOUTH_R = 308

    /** Landmarks are normalized; scale x by aspect (w/h) so distances are isotropic. */
    private fun d(a: Pt, b: Pt, aspect: Float) = hypot((a.x - b.x) * aspect, a.y - b.y)

    fun ear(lm: List<Pt>, idx: IntArray, aspect: Float = 4f / 3f): Float {
        val p = idx.map { lm[it] }
        val horiz = d(p[0], p[3], aspect)
        if (horiz < 1e-6f) return 0f
        return (d(p[1], p[5], aspect) + d(p[2], p[4], aspect)) / (2f * horiz)
    }

    fun meanEar(lm: List<Pt>, aspect: Float = 4f / 3f) =
        (ear(lm, LEFT_EYE, aspect) + ear(lm, RIGHT_EYE, aspect)) / 2f

    fun mar(lm: List<Pt>, aspect: Float = 4f / 3f): Float {
        val w = d(lm[MOUTH_L], lm[MOUTH_R], aspect)
        return if (w < 1e-6f) 0f else d(lm[LIP_UP], lm[LIP_LOW], aspect) / w
    }

    /**
     * Head pose from MediaPipe's facial transformation matrix (4x4, column-major).
     * Absolute signs are calibrated away: the engine uses deltas from the driver's baseline.
     * Check the sign of pitch for "head down" on the debug overlay once on the test phone.
     */
    fun headPose(m: FloatArray): HeadPose {
        fun r(row: Int, col: Int) = m[col * 4 + row]
        val sy = sqrt(r(0, 0) * r(0, 0) + r(1, 0) * r(1, 0))
        val pitch = Math.toDegrees(atan2(r(2, 1), r(2, 2)).toDouble()).toFloat()
        val yaw = Math.toDegrees(atan2(-r(2, 0), sy).toDouble()).toFloat()
        val roll = Math.toDegrees(atan2(r(1, 0), r(0, 0)).toDouble()).toFloat()
        return HeadPose(yaw, pitch, roll)
    }

    @Suppress("unused") private fun clampAsin(v: Float) = asin(v.coerceIn(-1f, 1f))
}
