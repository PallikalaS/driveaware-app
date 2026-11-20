package edu.csueb.driveaware.signals

/**
 * Sunglasses check (task #18, v0.9). Dark lenses make the eye region much darker than the
 * cheeks, while a dark room darkens both. So the test is the eye/cheek brightness ratio
 * (<= [Thresholds.sunglassesRatio]); it must hold for [Thresholds.sunglassesHoldS] to switch
 * on and clear for the same time to switch off, so a blink or a passing shadow doesn't flip
 * the mode. When the cheeks are too dark to judge (< [Thresholds.sunglassesMinCheek]) the
 * current state is kept.
 *
 * While on, the app runs in degraded mode: eye-based events (long closure, PERCLOS)
 * are not raised, because the landmark model is guessing the eyelids. Yawns, nods,
 * gaze and Model A activities still work, and the backend gets sunglasses=yes.
 */
object EyeRegion {
    /** Mean gray and 4-neighbour Laplacian variance of an ARGB pixel block. */
    fun stats(argb: IntArray, w: Int, h: Int): Pair<Float, Float> {
        if (w < 3 || h < 3 || argb.size < w * h) return -1f to -1f
        val g = FloatArray(w * h) { i ->
            val p = argb[i]
            0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
        }
        val mean = g.average().toFloat()
        var s = 0.0; var s2 = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val lap = (g[i - 1] + g[i + 1] + g[i - w] + g[i + w] - 4 * g[i]).toDouble()
            s += lap; s2 += lap * lap; n++
        }
        val m = s / n
        return mean to (s2 / n - m * m).toFloat()
    }
}

class SunglassesDetector(var th: Thresholds = Thresholds()) {
    var active = false; private set
    private var candidateSince: Long? = null

    var lastRatio = -1f; private set

    /**
     * Sunglasses = eye area much darker than the cheeks (eye/cheek < sunglassesRatio for
     * sunglassesHoldS). Comparing with the cheeks makes it work in any light; when the cheeks
     * themselves are too dark to read (dark room) or nothing was measured (no face), the state is
     * kept instead of guessing. The old "dark and flat" rule was removed: in a dark room every
     * face is dark and flat, so it said YES without sunglasses.
     */
    fun update(tMs: Long, brightness: Float, sharpness: Float, cheek: Float = -1f): Boolean {
        if (brightness < 0f || cheek < th.sunglassesMinCheek) {
            lastRatio = if (cheek > 0f && brightness >= 0f) brightness / cheek else -1f
            candidateSince = null; return active
        }
        lastRatio = brightness / cheek
        val looksDark = lastRatio <= th.sunglassesRatio
        if (looksDark != active) {
            val since = candidateSince ?: tMs.also { candidateSince = it }
            if (tMs - since >= (th.sunglassesHoldS * 1000).toLong()) { active = looksDark; candidateSince = null }
        } else candidateSince = null
        return active
    }

    fun reset() { active = false; candidateSince = null }
}
