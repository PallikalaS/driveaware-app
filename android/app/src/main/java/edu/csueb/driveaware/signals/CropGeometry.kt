package edu.csueb.driveaware.signals

/**
 * Model A input crop: same geometry as driveaware-models driveaware_data/crop.py
 * (upper_body_box + pad_to_square + 224 px), so the phone feeds Model A the same kind
 * of image it was trained on: head, shoulders, hands and lap, not just the face.
 */
data class Box(val x: Int, val y: Int, val w: Int, val h: Int)

object CropGeometry {
    const val WIDTH_FACTOR = 4.0f   // crop width  = 4.0 * face width
    const val TOP_FACTOR = 0.6f     // starts 0.6 * face height above the face top
    const val BOTTOM_FACTOR = 4.5f  // ends 4.5 * face height below the face top (lap)
    const val OUT_SIZE = 224

    fun upperBodyBox(face: Box, imgW: Int, imgH: Int): Box {
        val cx = face.x + face.w / 2f
        val bw = WIDTH_FACTOR * face.w
        val x0 = maxOf(0f, cx - bw / 2).toInt()
        val x1 = minOf(imgW.toFloat(), cx + bw / 2).toInt()
        val y0 = maxOf(0f, face.y - TOP_FACTOR * face.h).toInt()
        val y1 = minOf(imgH.toFloat(), face.y + BOTTOM_FACTOR * face.h).toInt()
        return Box(x0, y0, x1 - x0, y1 - y0)
    }

    /** Face box from normalized landmark x/y lists (the phone has landmarks, not a detector box). */
    fun faceBox(xs: List<Float>, ys: List<Float>, imgW: Int, imgH: Int): Box {
        val x0 = (xs.min() * imgW).toInt().coerceIn(0, imgW - 1)
        val y0 = (ys.min() * imgH).toInt().coerceIn(0, imgH - 1)
        val x1 = (xs.max() * imgW).toInt().coerceIn(x0 + 1, imgW)
        val y1 = (ys.max() * imgH).toInt().coerceIn(y0 + 1, imgH)
        return Box(x0, y0, x1 - x0, y1 - y0)
    }

    /** Subclass labels (model trained with LABEL_LEVEL=subclass) map to the 5 app classes. */
    val SUBCLASS_TO_APP = mapOf(
        "safe" to "safe_driving",
        "phone_ear" to "phone_use", "texting" to "phone_use", "phone_in_hand" to "phone_use",
        "drinking_cup" to "drinking", "drinking_bottle" to "drinking",
        "smoking" to "smoking",
        "eye_rub" to "face_touch", "face_scratch" to "face_touch", "hand_forehead" to "face_touch",
    )

    /** Sum label scores into app-class scores and return the top (class, score). */
    fun topAppClass(scores: Map<String, Float>): Pair<String, Float>? {
        val app = mutableMapOf<String, Float>()
        for ((label, s) in scores) {
            val c = if (label in ActivityRules.APP_CLASSES) label else SUBCLASS_TO_APP[label] ?: continue
            app[c] = (app[c] ?: 0f) + s
        }
        return app.maxByOrNull { it.value }?.toPair()
    }
}
