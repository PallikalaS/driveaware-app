package edu.csueb.driveaware.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
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
 */
class FaceLandmarkerHelper(
    context: Context,
    private val onFrame: (FrameSignals, FaceLandmarkerResult?, Bitmap) -> Unit,
    private val onError: (String) -> Unit = {},
    useGpu: Boolean = true,
) {
    private val landmarker: FaceLandmarker
    private var lastBitmap: Bitmap? = null
    private val t0 = SystemClock.uptimeMillis()

    init {
        val base = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .setDelegate(if (useGpu) Delegate.GPU else Delegate.CPU)
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
            .setErrorListener { e -> onError(e.message ?: "landmarker error") }
            .build()
        landmarker = FaceLandmarker.createFromOptions(context, opts)
    }

    /** CameraX analyzer entry point (front camera, 640x480, RGBA_8888). */
    fun detect(image: ImageProxy) {
        val bmp = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        image.use { bmp.copyPixelsFromBuffer(it.planes[0].buffer) }
        val m = Matrix().apply {
            postRotate(image.imageInfo.rotationDegrees.toFloat())
            postScale(-1f, 1f, image.width / 2f, image.height / 2f) // mirror front camera
        }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        lastBitmap = rotated
        landmarker.detectAsync(BitmapImageBuilder(rotated).build(), SystemClock.uptimeMillis())
    }

    private fun onResult(result: FaceLandmarkerResult, input: com.google.mediapipe.framework.image.MPImage) {
        val bmp = lastBitmap ?: return
        val t = SystemClock.uptimeMillis() - t0
        val faces = result.faceLandmarks()
        if (faces.isEmpty()) {
            onFrame(FrameSignals(t, faceFound = false), result, bmp); return
        }
        val aspect = input.width.toFloat() / input.height
        val lm = faces[0].map { Pt(it.x(), it.y(), it.z()) }
        val jaw = result.faceBlendshapes().orElse(null)?.firstOrNull()
            ?.firstOrNull { it.categoryName() == "jawOpen" }?.score() ?: 0f
        val pose = result.facialTransformationMatrixes().orElse(null)?.firstOrNull()
            ?.let { FaceSignals.headPose(it) } ?: HeadPose(0f, 0f, 0f)
        onFrame(
            FrameSignals(t, true, FaceSignals.meanEar(lm, aspect), FaceSignals.mar(lm, aspect), jaw, pose),
            result, bmp,
        )
    }

    fun close() = landmarker.close()
}
