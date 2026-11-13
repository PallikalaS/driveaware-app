package edu.csueb.driveaware.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
import edu.csueb.driveaware.signals.CropGeometry
import edu.csueb.driveaware.signals.ModelAOutput

/**
 * Model A on the phone (task #25): the fine-tuned activity classifier, run on every 3rd
 * frame on the same upper-body crop it was trained on (CropGeometry = crop.py).
 *
 * Put model_a_v1.tflite (from Drive Models/) in app/src/main/assets/. It must carry
 * TFLite metadata (labels + normalization): Model Maker exports do; the Keras fallback
 * in the notebook adds it. If the file is missing the app runs without Model A, and the
 * Test options "force Model A output" switch still works.
 */
class ModelAClassifier private constructor(private val clf: ImageClassifier) {

    fun classify(tMs: Long, frame: Bitmap, landmarks: List<NormalizedLandmark>): ModelAOutput? {
        if (landmarks.isEmpty()) return null
        val face = CropGeometry.faceBox(landmarks.map { it.x() }, landmarks.map { it.y() }, frame.width, frame.height)
        val crop = squareCrop(frame, CropGeometry.upperBodyBox(face, frame.width, frame.height))
        val cats = clf.classify(BitmapImageBuilder(crop).build()).classificationResult()
            .classifications().firstOrNull()?.categories() ?: return null
        val (cls, conf) = CropGeometry.topAppClass(cats.associate { it.categoryName() to it.score() }) ?: return null
        return ModelAOutput(tMs, cls, conf)
    }

    fun close() = clf.close()

    /** crop → pad to square with black (pad_to_square) → 224 px. */
    private fun squareCrop(src: Bitmap, b: edu.csueb.driveaware.signals.Box): Bitmap {
        val s = maxOf(b.w, b.h)
        val out = Bitmap.createBitmap(CropGeometry.OUT_SIZE, CropGeometry.OUT_SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        val scale = CropGeometry.OUT_SIZE.toFloat() / s
        val left = ((s - b.w) / 2f * scale).toInt(); val top = ((s - b.h) / 2f * scale).toInt()
        c.drawBitmap(src, Rect(b.x, b.y, b.x + b.w, b.y + b.h),
            Rect(left, top, left + (b.w * scale).toInt(), top + (b.h * scale).toInt()), null)
        return out
    }

    companion object {
        const val ASSET = "model_a_v1.tflite"

        /** null when the asset isn't bundled or can't be loaded (the error is returned for the overlay). */
        fun create(context: Context): Pair<ModelAClassifier?, String?> {
            if (context.assets.list("")?.contains(ASSET) != true) return null to null
            return try {
                val opts = ImageClassifier.ImageClassifierOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(ASSET).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setMaxResults(-1)   // all labels: subclass scores are summed per app class
                    .build()
                ModelAClassifier(ImageClassifier.createFromOptions(context, opts)) to null
            } catch (e: Exception) {
                null to "Model A failed to load: ${e.message}"
            }
        }
    }
}
