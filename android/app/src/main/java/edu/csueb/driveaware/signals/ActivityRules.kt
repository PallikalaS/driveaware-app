package edu.csueb.driveaware.signals

/**
 * Model A activity rules (task #26). Model A runs on every 3rd frame and returns one of
 * the 5 app classes with a confidence. An activity event fires when, over the last
 * [Thresholds.activityWindowS], at least [Thresholds.activityFrac] of the predictions
 * are that class with confidence > [Thresholds.activityConf]. One stray frame can't alert.
 *
 * hand_occupied = phone, drink or cigarette seen in the last 2 s (sent to Gemini).
 * Face touches are counted over 5 minutes for the fatigue score.
 */
data class ModelAOutput(val tMs: Long, val appClass: String, val conf: Float)

class ActivityRules(var th: Thresholds = Thresholds()) {
    private val window = ArrayDeque<ModelAOutput>()
    private val lastFired = mutableMapOf<String, Long>()
    private val faceTouches = ArrayDeque<Long>()
    private var lastHandBusyMs: Long? = null
    var latest: ModelAOutput? = null; private set

    fun process(o: ModelAOutput): DetectedEvent? {
        latest = o
        window.addLast(o)
        val winMs = (th.activityWindowS * 1000).toLong()
        while (window.isNotEmpty() && o.tMs - window.first().tMs > winMs) window.removeFirst()
        while (faceTouches.isNotEmpty() && o.tMs - faceTouches.first() > 300_000) faceTouches.removeFirst()
        if (o.appClass in HAND_BUSY && o.conf > th.activityConf) lastHandBusyMs = o.tMs

        val type = CLASS_TO_EVENT[o.appClass] ?: return null          // safe_driving
        val covered = o.tMs - window.first().tMs >= winMs * 0.9       // need a (nearly) full window
        val frac = window.count { it.appClass == o.appClass && it.conf > th.activityConf }.toFloat() / window.size
        if (!covered || frac < th.activityFrac) return null
        val last = lastFired[o.appClass]
        if (last != null && o.tMs - last < (th.refractoryS * 1000).toLong()) return null
        lastFired[o.appClass] = o.tMs
        if (type == EventType.FACE_TOUCH) faceTouches.addLast(o.tMs)
        // labeling_guide section 4: phone is medium/high (level 2), drink/smoke/face touch low (level 1)
        val level = if (type == EventType.PHONE_USE) 2 else 1
        return DetectedEvent(type, o.tMs / 1000f, level, "${o.appClass} %.2f x%.0f%%".format(o.conf, frac * 100))
    }

    fun handOccupied(nowMs: Long) = lastHandBusyMs?.let { nowMs - it <= 2000 } ?: false
    fun faceTouches5min() = faceTouches.size

    fun reset() { window.clear(); lastFired.clear(); faceTouches.clear(); lastHandBusyMs = null; latest = null }

    companion object {
        val APP_CLASSES = listOf("safe_driving", "phone_use", "drinking", "smoking", "face_touch")
        private val HAND_BUSY = setOf("phone_use", "drinking", "smoking")
        private val CLASS_TO_EVENT = mapOf(
            "phone_use" to EventType.PHONE_USE, "drinking" to EventType.DRINKING,
            "smoking" to EventType.SMOKING, "face_touch" to EventType.FACE_TOUCH,
        )
    }
}
