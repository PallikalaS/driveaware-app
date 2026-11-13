package edu.csueb.driveaware.signals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CropGeometryTest {
    @Test fun matchesPythonUpperBodyBox() {
        // driveaware_data/crop.py upper_body_box(Box(300, 100, 100, 120), 1280, 720) = Box(150, 28, 400, 612)
        assertEquals(Box(150, 28, 400, 612), CropGeometry.upperBodyBox(Box(300, 100, 100, 120), 1280, 720))
        // clipped at the image edges
        assertEquals(Box(0, 0, 250, 560), CropGeometry.upperBodyBox(Box(0, 20, 100, 120), 640, 600))
    }

    @Test fun subclassScoresSumIntoAppClasses() {
        val (c, s) = CropGeometry.topAppClass(mapOf("texting" to 0.4f, "phone_ear" to 0.3f, "safe" to 0.3f))!!
        assertEquals("phone_use", c); assertEquals(0.7f, s, 1e-6f)
        assertEquals("drinking", CropGeometry.topAppClass(mapOf("drinking" to 0.8f, "safe_driving" to 0.2f))!!.first)
        assertNull(CropGeometry.topAppClass(mapOf("radio" to 1f)))
    }
}
