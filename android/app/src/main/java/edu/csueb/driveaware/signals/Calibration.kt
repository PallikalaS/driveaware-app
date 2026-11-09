package edu.csueb.driveaware.signals

/**
 * 10-second calibration: driver looks at the road normally, eyes open.
 * Uses medians so a blink or two doesn't skew the EAR baseline.
 */
class Calibration(private val durationMs: Long = 10_000) {
    private val ears = mutableListOf<Float>()
    private val yaws = mutableListOf<Float>()
    private val pitches = mutableListOf<Float>()
    private var startMs: Long? = null
    var faceFrames = 0; private set
    var totalFrames = 0; private set

    fun add(f: FrameSignals) {
        if (startMs == null) startMs = f.tMs
        totalFrames++
        if (!f.faceFound) return
        faceFrames++
        ears += f.ear; yaws += f.pose.yawDeg; pitches += f.pose.pitchDeg
    }

    fun progress(nowMs: Long) = startMs?.let { ((nowMs - it).toFloat() / durationMs).coerceIn(0f, 1f) } ?: 0f
    fun done(nowMs: Long) = progress(nowMs) >= 1f

    /** null = not enough face frames (show "Face not found — adjust the mount"). */
    fun result(): Baseline? {
        if (totalFrames == 0 || faceFrames < totalFrames * 0.6) return null
        // upper-half median of EAR ignores blinks
        val openEars = ears.sorted().drop(ears.size / 4)
        return Baseline(ear = median(openEars), yawDeg = median(yaws), pitchDeg = median(pitches))
    }

    private fun median(v: List<Float>): Float = v.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
}
