package edu.csueb.driveaware.signals

import kotlin.math.abs

/** Event words match the event CSV / match_events.py exactly. */
enum class EventType(val csv: String) {
    EYE_CLOSURE_LONG("eye_closure_long"), PERCLOS_HIGH("perclos_high"), YAWN("yawn"),
    NOD("nod"), GAZE_AWAY("gaze_away"), PHONE_USE("phone_use"), DRINKING("drinking"),
    SMOKING("smoking"), FACE_TOUCH("face_touch");

    val measured get() = this == EYE_CLOSURE_LONG || this == PERCLOS_HIGH || this == NOD
}

data class DetectedEvent(val type: EventType, val tS: Float, val level: Int, val detail: String = "")

/** Rolling state the debug overlay and the backend summary read from. */
data class EngineState(
    val faceFound: Boolean = false,
    val eyesClosed: Boolean = false,
    val closureS: Float = 0f,
    val perclos: Float = 0f,
    val blinks: Int = 0,
    val longestClosureS: Float = 0f,
    val yawns5min: Int = 0,
    val nods5min: Int = 0,
    val gazeAwayS: Float = 0f,
    val yawDelta: Float = 0f,
    val pitchDelta: Float = 0f,
)

/**
 * Frame-by-frame temporal rules (Model C output → events).
 * Pure Kotlin: unit-tested in app/src/test.
 */
class TemporalEngine(var th: Thresholds = Thresholds(), var base: Baseline = Baseline()) {
    private val perclosWin = ArrayDeque<Pair<Long, Boolean>>()
    private val yawnTimes = ArrayDeque<Long>()
    private val nodTimes = ArrayDeque<Long>()
    private val blinkTimes = ArrayDeque<Long>()
    private val lastFired = mutableMapOf<EventType, Long>()

    private var closeStart: Long? = null
    private var closureFired = false
    private var longestClosureMs = 0L
    private var yawnStart: Long? = null
    private var yawnFired = false
    private var nodStart: Long? = null
    private var nodFired = false
    private var gazeStart: Long? = null
    private var gazeFired = false

    var state = EngineState(); private set

    fun reset() {
        perclosWin.clear(); yawnTimes.clear(); nodTimes.clear(); blinkTimes.clear(); lastFired.clear()
        closeStart = null; yawnStart = null; nodStart = null; gazeStart = null
        longestClosureMs = 0; state = EngineState()
    }

    fun process(f: FrameSignals): List<DetectedEvent> {
        val out = mutableListOf<DetectedEvent>()
        val t = f.tMs
        val tS = t / 1000f
        if (!f.faceFound) {
            // no face: freeze timers so a turned-away head isn't counted as closed eyes
            closeStart = null; yawnStart = null; nodStart = null
            state = state.copy(faceFound = false)
            return out
        }

        // ---------------- eyes: blink, long closure, PERCLOS
        val closed = f.ear < base.ear * th.earClosedRatio
        if (closed) {
            if (closeStart == null) {
                closeStart = t; closureFired = false
                lastLevel.remove(EventType.EYE_CLOSURE_LONG)
            }
            val dur = (t - closeStart!!) / 1000f
            if (!closureFired && dur >= th.longClosureS) {
                closureFired = true
                val lvl = if (dur >= th.severeClosureS) 3 else 2
                fire(EventType.EYE_CLOSURE_LONG, t, lvl, "closed %.1fs".format(dur))?.let(out::add)
            }
            // escalate to level 3 while still closed
            if (closureFired && dur >= th.severeClosureS && (lastLevel[EventType.EYE_CLOSURE_LONG] ?: 0) < 3) {
                lastLevel[EventType.EYE_CLOSURE_LONG] = 3
                out += DetectedEvent(EventType.EYE_CLOSURE_LONG, tS, 3, "closed %.1fs".format(dur))
            }
        } else closeStart?.let { start ->
            val durMs = t - start
            longestClosureMs = maxOf(longestClosureMs, durMs)
            if (durMs / 1000f <= th.blinkMaxS) blinkTimes.addLast(t)
            closeStart = null
        }
        perclosWin.addLast(t to closed)
        val winMs = (th.perclosWindowS * 1000).toLong()
        while (perclosWin.isNotEmpty() && t - perclosWin.first().first > winMs) perclosWin.removeFirst()
        prune(blinkTimes, t, 10_000)
        val perclos = perclosWin.count { it.second }.toFloat() / perclosWin.size
        val windowFull = perclosWin.size > 1 && (t - perclosWin.first().first) >= winMs * 0.9
        if (windowFull && perclos >= th.perclosHigh) {
            fire(EventType.PERCLOS_HIGH, t, 2, "perclos %.2f".format(perclos), refractoryS = 30f)?.let(out::add)
        }

        // ---------------- yawn: MAR or jawOpen blendshape held
        val mouthOpen = f.mar >= th.yawnMar || f.jawOpen >= th.yawnJawOpen
        if (mouthOpen) {
            if (yawnStart == null) { yawnStart = t; yawnFired = false }
            if (!yawnFired && (t - yawnStart!!) / 1000f >= th.yawnMinS) {
                yawnFired = true
                yawnTimes.addLast(t)
                prune(yawnTimes, t, 300_000)
                val lvl = if (yawnTimes.size >= 3) 2 else 1
                fire(EventType.YAWN, t, lvl, "yawns5min=${yawnTimes.size}", refractoryS = 3f)?.let(out::add)
            }
        } else yawnStart = null

        // ---------------- head: nod (drop) and gaze away
        val pitchDelta = f.pose.pitchDeg - base.pitchDeg
        val yawDelta = f.pose.yawDeg - base.yawDeg
        if (pitchDelta <= -th.nodPitchDeg) {
            if (nodStart == null) { nodStart = t; nodFired = false }
            if (!nodFired && (t - nodStart!!) / 1000f >= th.nodMinS) {
                nodFired = true
                nodTimes.addLast(t); prune(nodTimes, t, 300_000)
                // labeling_guide: nod alone -> medium (level 2); nod while PERCLOS is high -> high (level 3).
                val lvl = if (windowFull && perclos >= th.perclosHigh) 3 else 2
                fire(EventType.NOD, t, lvl, "pitch %.0f".format(pitchDelta), refractoryS = 3f)?.let(out::add)
            }
        } else nodStart = null

        val away = abs(yawDelta) >= th.gazeYawDeg || pitchDelta >= th.gazePitchDeg
        var gazeAwayS = 0f
        if (away) {
            if (gazeStart == null) { gazeStart = t; gazeFired = false }
            gazeAwayS = (t - gazeStart!!) / 1000f
            if (!gazeFired && gazeAwayS >= th.gazeMaxS) {
                gazeFired = true
                fire(EventType.GAZE_AWAY, t, 1, "yaw %.0f".format(yawDelta))?.let(out::add)
            }
        } else gazeStart = null

        prune(yawnTimes, t, 300_000); prune(nodTimes, t, 300_000)
        state = EngineState(
            faceFound = true, eyesClosed = closed,
            closureS = closeStart?.let { (t - it) / 1000f } ?: 0f,
            perclos = perclos, blinks = blinkTimes.size,
            longestClosureS = maxOf(longestClosureMs, closeStart?.let { t - it } ?: 0L) / 1000f,
            yawns5min = yawnTimes.size, nods5min = nodTimes.size, gazeAwayS = gazeAwayS,
            yawDelta = yawDelta, pitchDelta = pitchDelta,
        )
        return out
    }

    /** Start a fresh 10 s window for the longest-closure stat (called after each backend call). */
    fun resetWindowStats() { longestClosureMs = 0 }

    private val lastLevel = mutableMapOf<EventType, Int>()

    private fun fire(type: EventType, t: Long, level: Int, detail: String,
                     refractoryS: Float = th.refractoryS): DetectedEvent? {
        val last = lastFired[type]
        if (last != null && t - last < (refractoryS * 1000).toLong()) return null
        lastFired[type] = t
        lastLevel[type] = level
        return DetectedEvent(type, t / 1000f, level, detail)
    }

    private fun prune(q: ArrayDeque<Long>, now: Long, keepMs: Long) {
        while (q.isNotEmpty() && now - q.first() > keepMs) q.removeFirst()
    }
}
