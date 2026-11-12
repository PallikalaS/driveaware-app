package edu.csueb.driveaware.signals

/**
 * Fatigue score 0–100 (task #20): one number for the trip summary and the overlay,
 * combining the slow drowsiness signals over their windows.
 *
 *   PERCLOS (60 s) relative to the high threshold   40 points
 *   nods in 5 min, 2 = full                          25 points
 *   yawns in 5 min, 3 = full                         20 points
 *   face touches in 5 min, 3 = full                  15 points
 *
 * With sunglasses PERCLOS can't be trusted, so it is left out and the other parts are
 * rescaled to 100. Bands: < 30 alert, 30–60 tiring, > 60 drowsy.
 */
object FatigueScore {
    fun compute(perclos: Float, nods5min: Int, yawns5min: Int, faceTouches5min: Int,
                sunglasses: Boolean, th: Thresholds = Thresholds()): Float {
        fun part(v: Float, full: Float) = (v / full).coerceIn(0f, 1f)
        val parts = mutableListOf(
            25f to part(nods5min.toFloat(), 2f),
            20f to part(yawns5min.toFloat(), 3f),
            15f to part(faceTouches5min.toFloat(), 3f),
        )
        if (!sunglasses) parts += 40f to part(perclos, th.perclosHigh)
        val weight = parts.sumOf { it.first.toDouble() }.toFloat()
        return 100f * parts.sumOf { (w, p) -> (w * p).toDouble() }.toFloat() / weight
    }

    fun band(score: Float) = when { score > 60f -> "drowsy"; score >= 30f -> "tiring"; else -> "alert" }
}
