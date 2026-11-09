package edu.csueb.driveaware.signals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalEngineTest {
    private val base = Baseline(ear = 0.30f, yawDeg = 0f, pitchDeg = 0f)
    private fun frame(tMs: Long, ear: Float = 0.30f, mar: Float = 0.1f, yaw: Float = 0f, pitch: Float = 0f) =
        FrameSignals(tMs, true, ear, mar, 0f, HeadPose(yaw, pitch, 0f))

    private fun run(e: TemporalEngine, fromMs: Long, toMs: Long, f: (Long) -> FrameSignals) =
        (fromMs until toMs step 33).flatMap { e.process(f(it)) }

    @Test fun longClosureFiresOnceAndEscalates() {
        val e = TemporalEngine(base = base)
        val ev = run(e, 0, 2000) { frame(it, ear = 0.10f) }
        val closures = ev.filter { it.type == EventType.EYE_CLOSURE_LONG }
        assertEquals(2, closures.size)           // level 2 at 1.0 s, level 3 at 1.5 s
        assertEquals(3, closures.last().level)
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
}
