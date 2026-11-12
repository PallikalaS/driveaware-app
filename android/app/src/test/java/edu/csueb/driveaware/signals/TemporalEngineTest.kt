package edu.csueb.driveaware.signals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalEngineTest {
    private val base = Baseline(ear = 0.30f, yawDeg = 0f, pitchDeg = 0f)
    private fun frame(tMs: Long, ear: Float = 0.30f, mar: Float = 0.1f, yaw: Float = 0f, pitch: Float = 0f,
                      blink: Float = 0f) =
        FrameSignals(tMs, true, ear, mar, jawOpen = 0f, eyeBlink = blink, pose = HeadPose(yaw, pitch, 0f))

    private fun run(e: TemporalEngine, fromMs: Long, toMs: Long, f: (Long) -> FrameSignals) =
        (fromMs until toMs step 33).flatMap { e.process(f(it)) }

    @Test fun longClosureIsLevel3AtOneSecond() {
        // labeling_guide.md: any eye closure >= 1.0 s is high -> level 3, fired once.
        val e = TemporalEngine(base = base)
        val ev = run(e, 0, 2000) { frame(it, ear = 0.10f) }
        val closures = ev.filter { it.type == EventType.EYE_CLOSURE_LONG }
        assertEquals(1, closures.size)
        assertEquals(3, closures.single().level)
        assertTrue(closures.single().tS in 1.0f..1.1f)
    }

    @Test fun nodAloneIsLevel2() {
        val e = TemporalEngine(base = base)
        val ev = run(e, 0, 1000) { frame(it, pitch = -25f) }
        assertEquals(2, ev.single { it.type == EventType.NOD }.level)
    }

    @Test fun blinksDoNotAlert() {
        val e = TemporalEngine(base = base)
        val ev = run(e, 0, 5000) { t -> frame(t, ear = if (t % 1000 < 150) 0.1f else 0.3f) }
        assertTrue(ev.none { it.type == EventType.EYE_CLOSURE_LONG })
        assertTrue(e.state.blinks >= 4)
    }

    @Test fun mirrorCheckDoesNotAlertButLongGazeDoes() {
        val e = TemporalEngine(base = base)
        assertTrue(run(e, 0, 1000) { frame(it, yaw = 45f) }.isEmpty())
        run(e, 1000, 1500) { frame(it) }
        val ev = run(e, 1500, 4000) { frame(it, yaw = 45f) }
        assertEquals(EventType.GAZE_AWAY, ev.single().type)
    }

    @Test fun yawnAndNod() {
        val e = TemporalEngine(base = base)
        val y = run(e, 0, 2000) { frame(it, mar = 0.8f) }
        assertEquals(EventType.YAWN, y.single().type)
        val n = run(e, 2000, 3000) { frame(it, pitch = -25f) }
        assertEquals(EventType.NOD, n.single().type)
    }

    @Test fun perclosNeedsFullWindow() {
        val e = TemporalEngine(base = base)
        // 20 % closed in 300 ms bursts (below long-closure time) for 70 s
        val ev = run(e, 0, 70_000) { t -> frame(t, ear = if (t % 1500 < 300) 0.1f else 0.3f) }
        val p = ev.filter { it.type == EventType.PERCLOS_HIGH }
        assertTrue(p.isNotEmpty() && p.first().tS >= 54f)
    }

    @Test fun blinkScoreCountsQuickBlinksEarMisses() {
        // EAR barely dips (0.26 > 0.7 * 0.30) but the blink blendshape says shut (Issue #5).
        val e = TemporalEngine(base = base)
        run(e, 0, 5000) { t -> if (t % 1000 < 150) frame(t, ear = 0.26f, blink = 0.8f) else frame(t) }
        assertTrue(e.state.blinks >= 4)
    }

    @Test fun noEyeClosureAlertDuringYawn() {
        // Eyes squeeze shut while the mouth is wide open: a yawn, not an eye-closure alert (Issue #4).
        val e = TemporalEngine(base = base)
        val ev = run(e, 0, 2500) { frame(it, ear = 0.10f, mar = 0.8f) }
        assertTrue(ev.none { it.type == EventType.EYE_CLOSURE_LONG })
        assertEquals(EventType.YAWN, ev.single().type)
    }
}
