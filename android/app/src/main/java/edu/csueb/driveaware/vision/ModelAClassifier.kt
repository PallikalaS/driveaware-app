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
import edu.csueb.driveaware.signals.Box
import edu.csueb.driveaware.signals.CropGeometry
import edu.csueb.driveaware.signals.ModelAOutput
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Model A on the phone (task #25): the fine-tuned activity classifier, run on every 3rd frame on
 * the same upper-body crop it was trained on (CropGeometry = crop.py).
 *
 * Assets (app/src/main/assets/, copied from Drive Models/):
 *   model_a_v1.tflite      the model
 *   model_a_labels.txt     one class name per line, in the model's output order
 *
 * Two ways to run it:
 *  - MediaPipe Image Classifier when the .tflite carries metadata (Model Maker exports).
 *  - Plain TFLite interpreter + model_a_labels.txt when it doesn't (the Keras MobileNetV3-Small
 *    export on Colab's Python 3.13, run02). That model takes raw 0–255 RGB pixels.
 * If the model is missing the app runs without Model A; the Test options switch still works.
 */
class ModelAClassifier private constructor(
    private val mp: ImageClassifier?,
    private val tfl: Interpreter?,
    private val labels: List<String>,
) {
    val backend get() = if (mp != null) "mediapipe" else "tflite"

    fun classify(tMs: Long, frame: Bitmap, landmarks: List<NormalizedLandmark>): ModelAOutput? {
        if (landmarks.isEmpty()) return null
        val face = CropGeometry.faceBox(landmarks.map { it.x() }, landmarks.map { it.y() }, frame.width, frame.height)
        val crop = squareCrop(frame, CropGeometry.upperBodyBox(face, frame.width, frame.height))
        val scores: Map<String, Float> = if (mp != null) {
            val cats = mp.classify(BitmapImageBuilder(crop).build()).classificationResult()
                .classifications().firstOrNull()?.categories() ?: return null
            cats.associate { it.categoryName() to it.score() }
        } else runInterpreter(crop)
        val (cls, conf) = CropGeometry.topAppClass(scores) ?: return null
        return ModelAOutput(tMs, cls, conf)
    }

    private val input: ByteBuffer by lazy {
        ByteBuffer.allocateDirect(4 * S * S * 3).order(ByteOrder.nativeOrder())
    }
    private val pixels = IntArray(S * S)

    private fun runInterpreter(crop: Bitmap): Map<String, Float> {
        val it = tfl ?: return emptyMap()
        crop.getPixels(pixels, 0, S, 0, 0, S, S)
        input.rewind()
        for (p in pixels) {   // raw 0-255 RGB floats (MobileNetV3 has its own preprocessing layer)
            input.putFloat(((p shr 16) and 0xFF).toFloat())
            input.putFloat(((p shr 8) and 0xFF).toFloat())
            input.putFloat((p and 0xFF).toFloat())
        }
        val out = Array(1) { FloatArray(labels.size) }
        it.run(input, out)
        return labels.indices.associate { i -> labels[i] to out[0][i] }
    }

    fun close() { mp?.close(); tfl?.close() }

    /** crop → pad to square with black (pad_to_square) → 224 px. */
    private fun squareCrop(src: Bitmap, b: Box): Bitmap {
        val s = maxOf(b.w, b.h)
        val out = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        val scale = S.toFloat() / s
        val left = ((s - b.w) / 2f * scale).toInt(); val top = ((s - b.h) / 2f * scale).toInt()
        c.drawBitmap(src, Rect(b.x, b.y, b.x + b.w, b.y + b.h),
            Rect(left, top, left + (b.w * scale).toInt(), top + (b.h * scale).toInt()), null)
        return out
    }

    companion object {
        const val ASSET = "model_a_v1.tflite"
        const val LABELS = "model_a_labels.txt"
        private const val S = CropGeometry.OUT_SIZE

        /** (classifier, error): classifier is null when the asset is missing or can't be loaded. */
        fun create(context: Context): Pair<ModelAClassifier?, String?> {
            val assets = context.assets.list("") ?: emptyArray()
            if (ASSET !in assets) return null to null
            // 1) model with metadata → MediaPipe Image Classifier
            val mpErr = try {
                val opts = ImageClassifier.ImageClassifierOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(ASSET).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setMaxResults(-1)   // all labels: subclass scores are summed per app class
                    .build()
                return ModelAClassifier(ImageClassifier.createFromOptions(context, opts), null, emptyList()) to null
            } catch (e: Exception) { e.message }
            // 2) no metadata → plain interpreter + labels file
            if (LABELS !in assets) return null to "Model A has no metadata and $LABELS is missing ($mpErr)"
            return try {
                val labels = context.assets.open(LABELS).bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
                val fd = context.assets.openFd(ASSET)
                val buf = FileInputStream(fd.fileDescriptor).channel
                    .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                val interp = Interpreter(buf, Interpreter.Options().setNumThreads(2))
                val outN = interp.getOutputTensor(0).shape().last()
                if (outN != labels.size) return null to "Model A outputs $outN classes but $LABELS has ${labels.size}"
                ModelAClassifier(null, interp, labels) to null
            } catch (e: Exception) {
                null to "Model A failed to load: ${e.message}"
            }
        }
    }
}
