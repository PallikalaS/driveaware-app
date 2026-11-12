package edu.csueb.driveaware.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import edu.csueb.driveaware.signals.FaceSignals
import edu.csueb.driveaware.signals.FrameSignals
import edu.csueb.driveaware.signals.HeadPose
import edu.csueb.driveaware.signals.Pt

/**
 * Model C — pretrained MediaPipe Face Landmarker (on device, LIVE_STREAM mode).
 * Outputs 478 landmarks, 52 blendshapes and the facial transformation matrix,
 * which we turn into FrameSignals for the TemporalEngine.
 *
 * Delegate: GPU on real phones, CPU on the emulator (its GPU path fails with
 * "Graph has errors"). If the GPU fails at runtime we rebuild on CPU once.
 */
class FaceLandmarkerHelper(
    private val context: Context,
    private val onFrame: (FrameSignals, FaceLandmarkerResult?, Bitmap) -> Unit,
    private val onError: (String) -> Unit = {},
) {
    @Volatile private var landmarker: FaceLandmarker
    @Volatile private var needsCpuRebuild = false
    var delegateName: String = ""; private set
    private var lastBitmap: Bitmap? = null
    private val t0 = SystemClock.uptimeMillis()

    init {
        val gpu = !isEmulator()
        landmarker = build(if (gpu) Delegate.GPU else Delegate.CPU)
    }

    private fun build(delegate: Delegate): FaceLandmarker {
        delegateName = delegate.name
        Log.i(TAG, "Face Landmarker delegate: $delegateName")
        val base = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .setDelegate(delegate)
            .build()
        val opts = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(true)
            .setResultListener(::onResult)
            .setErrorListener { e ->
                Log.e(TAG, "MediaPipe error ($delegateName)", e)
                if (delegateName == Delegate.GPU.name) needsCpuRebuild = true
                onError("Face model error ($delegateName): ${e.message?.lineSequence()?.firstOrNull() ?: "unknown"}")
            }
            .build()
        return FaceLandmarker.createFromOptions(context, opts)
    }

    /** CameraX analyzer entry point (front camera, 640x480, RGBA_8888). Runs on the analyzer thread. */
    fun detect(image: ImageProxy) {
        if (needsCpuRebuild) {
            needsCpuRebuild = false
            runCatching { landmarker.close() }
            landmarker = build(Delegate.CPU)
        }
        val rotation = image.imageInfo.rotationDegrees.toFloat()
        // toBitmap() handles row-stride padding (copyPixelsFromBuffer can garble or throw).
        val bmp = image.use { it.toBitmap() }
        val m = Matrix().apply {
            postRotate(rotation)
            postScale(-1f, 1f) // mirror front camera so left/right match the preview
        }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        lastBitmap = rotated
        try {
            landmarker.detectAsync(BitmapImageBuilder(rotated).build(), SystemClock.uptimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "detectAsync failed", e)
            onError("Face model error: ${e.message?.lineSequence()?.firstOrNull() ?: "unknown"}")
        }
    }

    private fun onResult(result: FaceLandmarkerResult, input: MPImage) {
        val bmp = lastBitmap ?: return
        val t = SystemClock.uptimeMillis() - t0
        val faces = result.faceLandmarks()
        if (faces.isEmpty()) {
            onFrame(FrameSignals(t, faceFound = false), result, bmp); return
        }
        val aspect = input.width.toFloat() / input.height
        val lm = faces[0].map { Pt(it.x(), it.y(), it.z()) }
        val shapes = result.faceBlendshapes().orElse(null)?.firstOrNull()
        fun shape(name: String) = shapes?.firstOrNull { it.categoryName() == name }?.score() ?: 0f
        val jaw = shape("jawOpen")
        val blink = (shape("eyeBlinkLeft") + shape("eyeBlinkRight")) / 2f
        val pose = result.facialTransformationMatrixes().orElse(null)?.firstOrNull()
            ?.let { FaceSignals.headPose(it) } ?: HeadPose(0f, 0f, 0f)
        onFrame(
            FrameSignals(t, true, FaceSignals.meanEar(lm, aspect), FaceSignals.mar(lm, aspect), jaw, blink, pose),
            result, bmp,
        )
    }

    fun close() = runCatching { landmarker.close() }

    companion object {
        private const val TAG = "DriveAware"

        fun isEmulator(): Boolean =
            Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") ||
                Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emulator") ||
                Build.MODEL.contains("sdk_gphone") || Build.PRODUCT.contains("sdk")
    }
}
