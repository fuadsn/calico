package com.hackathon.calico

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.Locale
import java.util.concurrent.Executors

/**
 * MODE 2: live pose tracking + rep counting + voice cues.
 * Launch with intent extra "exercise" = "PUSHUP" | "SQUAT" (from the room-scan mode); defaults to PUSHUP.
 */
class WorkoutActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var repsText: TextView
    private lateinit var exerciseText: TextView

    private lateinit var tts: TextToSpeech
    private lateinit var landmarker: PoseLandmarker
    private lateinit var counter: RepCounter
    private val analyzerExecutor = Executors.newSingleThreadExecutor()

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_workout)
        preview = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        repsText = findViewById(R.id.reps)
        exerciseText = findViewById(R.id.exercise)

        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts.language = Locale.US }
        setExercise(Exercise.valueOf(intent.getStringExtra("exercise") ?: "PUSHUP"))
        exerciseText.setOnClickListener {
            setExercise(if (counter.exercise == Exercise.PUSHUP) Exercise.SQUAT else Exercise.PUSHUP)
        }

        landmarker = PoseLandmarker.createFromOptions(
            this,
            PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath("pose_landmarker_lite.task")
                        .setDelegate(Delegate.GPU)
                        .build()
                )
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result, input -> onPose(result, input.width, input.height) }
                .setErrorListener { it.printStackTrace() }
                .build()
        )
        askCamera.launch(Manifest.permission.CAMERA)
    }

    private fun setExercise(e: Exercise) {
        counter = RepCounter(e, onRep = ::onRep, onCue = ::say)
        overlay.highlight = e.left + e.right
        exerciseText.text = "${e.name}  (tap to switch)"
        repsText.text = "0"
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val previewUseCase = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(analyzerExecutor, ::analyze) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, previewUseCase, analysis)
        }, mainExecutor)
    }

    private fun analyze(image: ImageProxy) {
        val bitmap = image.use { it.toBitmap() }
        // rotate to upright and mirror (front camera) so overlay matches the preview
        val m = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()); postScale(-1f, 1f) }
        val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        landmarker.detectAsync(BitmapImageBuilder(upright).build(), SystemClock.uptimeMillis())
    }

    private fun onPose(result: PoseLandmarkerResult, w: Int, h: Int) {
        val pose = result.landmarks().firstOrNull()
        runOnUiThread {
            overlay.update(pose ?: emptyList(), w, h)
        }
        if (pose == null) return

        // pick the side whose joints are more visible
        val e = counter.exercise
        fun vis(idx: IntArray) = idx.minOf { pose[it].visibility().orElse(0f) }
        val j = if (vis(e.left) >= vis(e.right)) e.left else e.right
        if (vis(j) < 0.5f) return
        val a = angle(
            pose[j[0]].x(), pose[j[0]].y(),
            pose[j[1]].x(), pose[j[1]].y(),
            pose[j[2]].x(), pose[j[2]].y(),
        )
        counter.feed(a)
    }

    private fun onRep(n: Int) {
        runOnUiThread { repsText.text = n.toString() }
        say(n.toString())
    }

    private fun say(text: String) = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, text)

    override fun onDestroy() {
        super.onDestroy()
        analyzerExecutor.shutdown()
        landmarker.close()
        tts.shutdown()
    }
}
