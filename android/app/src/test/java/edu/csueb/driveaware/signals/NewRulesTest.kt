package edu.csueb.driveaware.signals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SunglassesTest {
    @Test fun darkFlatEyesTurnOnAfterHoldAndOffAfterHold() {
        val d = SunglassesDetector()
        assertFalse(d.update(0, 20f, 10f))
        assertFalse(d.update(2900, 20f, 10f))        // not yet 3 s
        assertTrue(d.update(3000, 20f, 10f))
        assertTrue(d.update(4000, 90f, 60f))         // glasses off, hold again
        assertFalse(d.update(7000, 90f, 60f))
    }

    @Test fun aBlinkOrShadowDoesNotFlip() {
        val d = SunglassesDetector()
        for (t in 0L..2000L step 100) d.update(t, 20f, 10f)
        d.update(2100, 90f, 60f)                     // one bright frame resets the hold
        assertFalse(d.update(3500, 20f, 10f))
        assertFalse(d.update(6000, 30f, 50f))        // dark but sharp: real eyes in low light
    }

    @Test fun eyeRegionStatsSeparateFlatFromTextured() {
        val flat = IntArray(20 * 10) { 0xFF202020.toInt() }
        val (b, lap) = EyeRegion.stats(flat, 20, 10)
        assertEquals(32f, b, 0.5f); assertEquals(0f, lap, 0.01f)
        val stripes = IntArray(20 * 10) { i -> if ((i % 20) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        assertTrue(EyeRegion.stats(stripes, 20, 10).second > 1000f)
    }

    @Test fun degradedModeSkipsEyeEventsButKeepsNods() {
        val e = TemporalEngine(base = Baseline(0.30f))
        e.sunglasses = true
        val ev = (0L until 3000L step 33).flatMap {
            e.process(FrameSignals(it, true, ear = 0.10f, eyeBlink = 0.9f, pose = HeadPose(0f, -25f, 0f)))
        }
        assertTrue(ev.none { it.type == EventType.EYE_CLOSURE_LONG || it.type == EventType.PERCLOS_HIGH })
        assertEquals(2, ev.single { it.type == EventType.NOD }.level)
        assertTrue(e.state.sunglasses)
    }
}

class ActivityRulesTest {
    private fun feed(r: ActivityRules, from: Long, to: Long, cls: (Long) -> String, conf: Float = 0.9f) =
        (from until to step 100).mapNotNull { r.process(ModelAOutput(it, cls(it), conf)) }

    @Test fun phoneHeldTwoSecondsFiresOnceAtLevel2() {
        val r = ActivityRules()
        val ev = feed(r, 0, 5000, { "phone_use" })
        assertEquals(1, ev.size)
        assertEquals(EventType.PHONE_USE, ev[0].type); assertEquals(2, ev[0].level)
        assertTrue(ev[0].tS in 1.8f..2.1f)
        assertTrue(r.handOccupied(4900))
        assertFalse(r.handOccupied(7500))
    }

    @Test fun flickeringOrLowConfidenceDoesNotFire() {
        val r = ActivityRules()
        assertTrue(feed(r, 0, 5000, { t -> if ((t / 100) % 2 == 0L) "phone_use" else "safe_driving" }).isEmpty())
        assertTrue(feed(ActivityRules(), 0, 5000, { "drinking" }, conf = 0.5f).isEmpty())
        assertTrue(feed(ActivityRules(), 0, 5000, { "safe_driving" }).isEmpty())
    }

    @Test fun faceTouchesAreCounted() {
        val r = ActivityRules()
        feed(r, 0, 3000, { "face_touch" }); feed(r, 3000, 15000, { "safe_driving" }); feed(r, 15000, 18000, { "face_touch" })
        assertEquals(2, r.faceTouches5min())
        assertNull(r.process(ModelAOutput(18100, "safe_driving", 0.9f)))
    }
}

class FatigueScoreTest {
    @Test fun scoreAndBands() {
        assertEquals(0f, FatigueScore.compute(0f, 0, 0, 0, false), 0.01f)
        assertEquals(100f, FatigueScore.compute(0.30f, 2, 3, 3, false), 0.01f)
        assertEquals(40f, FatigueScore.compute(0.15f, 0, 0, 0, false), 0.01f)
        assertEquals("alert", FatigueScore.band(10f)); assertEquals("tiring", FatigueScore.band(40f))
        assertEquals("drowsy", FatigueScore.band(70f))
    }

    @Test fun sunglassesIgnorePerclosAndRescale() {
        assertEquals(0f, FatigueScore.compute(0.9f, 0, 0, 0, true), 0.01f)
        assertEquals(100f * 25f / 60f, FatigueScore.compute(0f, 2, 0, 0, true), 0.01f)
    }
}

class Issue60And11Test {
    @Test fun sunglassesByEyeCheekRatioEvenWithSharpFrames() {
        val d = SunglassesDetector()
        // sharp edges (frames, reflections) but the eye area is much darker than the cheeks
        for (t in 0L..3000L step 100) d.update(t, 40f, 400f, cheek = 110f)
        assertTrue(d.active)
        val n = SunglassesDetector()   // normal eyes: a bit darker than cheeks, not much
        for (t in 0L..5000L step 100) n.update(t, 85f, 380f, cheek = 110f)
        assertFalse(n.active)
    }

    @Test fun yawnWithEyesClosedDoesNotFireClosureWhenItEnds() {
        val e = TemporalEngine(base = Baseline(0.30f))
        val ev = mutableListOf<DetectedEvent>()
        // 2.5 s yawn with eyes squeezed shut, then eyes reopen 0.3 s after the mouth closes
        for (t in 0L until 2500L step 33) ev += e.process(FrameSignals(t, true, ear = 0.10f, mar = 0.8f, jawOpen = 0.8f, eyeBlink = 0.9f))
        for (t in 2500L until 2800L step 33) ev += e.process(FrameSignals(t, true, ear = 0.10f, eyeBlink = 0.9f))
        for (t in 2800L until 4000L step 33) ev += e.process(FrameSignals(t, true, ear = 0.30f))
        assertTrue(ev.any { it.type == EventType.YAWN })
        assertTrue(ev.none { it.type == EventType.EYE_CLOSURE_LONG })
    }

    @Test fun eyesStayingClosedAfterAYawnStillAlert() {
        val e = TemporalEngine(base = Baseline(0.30f))
        val ev = mutableListOf<DetectedEvent>()
        for (t in 0L until 2000L step 33) ev += e.process(FrameSignals(t, true, ear = 0.10f, mar = 0.8f, eyeBlink = 0.9f))
        for (t in 2000L until 3500L step 33) ev += e.process(FrameSignals(t, true, ear = 0.10f, eyeBlink = 0.9f))
        val c = ev.filter { it.type == EventType.EYE_CLOSURE_LONG }
        assertTrue(c.isNotEmpty() && c.first().tS >= 2.9f)   // counted from when the mouth closed
    }
}
