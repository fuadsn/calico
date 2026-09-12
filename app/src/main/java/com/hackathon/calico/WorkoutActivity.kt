package com.hackathon.calico

import android.Manifest
import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * MODE 2: live pose tracking + rep counting + voice cues.
 * Intent extras:
 *   exercise = any [Exercise] name (default PUSHUP)
 *   video    = absolute path of an mp4, or a filename inside filesDir (see bench/run.sh); runs the file instead of the camera
 *              and logs "CALICO_BENCH exercise=.. reps=.. cues=.. frames=.." when done.
 */
class WorkoutActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var frame: ImageView
    private lateinit var overlay: OverlayView
    private lateinit var repsText: TextView
    private lateinit var exerciseText: TextView
    private lateinit var recBtn: TextView
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

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
        frame = findViewById(R.id.frame)
        overlay = findViewById(R.id.overlay)
        repsText = findViewById(R.id.reps)
        exerciseText = findViewById(R.id.exercise)
        recBtn = findViewById(R.id.rec)
        recBtn.setOnClickListener { toggleRecord() }

        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts.language = Locale.US }
        setExercise(Exercise.valueOf(intent.getStringExtra("exercise") ?: "PUSHUP"))
        exerciseText.setOnClickListener {
            val all = Exercise.values()
            setExercise(all[(counter.exercise.ordinal + 1) % all.size])
        }

        val video = intent.getStringExtra("video")
        landmarker = createLandmarker(if (video != null) RunningMode.VIDEO else RunningMode.LIVE_STREAM)
        if (video != null) runVideo(video) else askCamera.launch(Manifest.permission.CAMERA)
    }

    private fun createLandmarker(mode: RunningMode): PoseLandmarker {
        val opts = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath("pose_landmarker_lite.task")
                    .setDelegate(Delegate.GPU)
                    .build()
            )
            .setRunningMode(mode)
            .setErrorListener { it.printStackTrace() }
        if (mode == RunningMode.LIVE_STREAM) opts.setResultListener { r, img -> onPose(r, img.width, img.height) }
        return PoseLandmarker.createFromOptions(this, opts.build())
    }

    private fun setExercise(e: Exercise) {
        counter = RepCounter(e, onRep = ::onRep, onCue = ::say)
        overlay.highlight = e.left + e.right
        exerciseText.text = "${e.name.replace('_', ' ')}  (tap to switch)"
        repsText.text = "0"
    }

    // ---------- live camera ----------

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
            val vc = VideoCapture.withOutput(Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build())
            provider.unbindAll()
            try {
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, previewUseCase, analysis, vc)
                videoCapture = vc
            } catch (e: Exception) {   // device can't run preview + analysis + video together
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, previewUseCase, analysis)
                recBtn.visibility = View.GONE
            }
        }, mainExecutor)
    }

    private fun analyze(image: ImageProxy) {
        val bitmap = image.use { it.toBitmap() }
        // rotate to upright and mirror (front camera) so overlay matches the preview
        val m = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()); postScale(-1f, 1f) }
        val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        landmarker.detectAsync(BitmapImageBuilder(upright).build(), SystemClock.uptimeMillis())
    }

    // ---------- video file (bench) ----------

    private fun runVideo(path: String) = thread {
        runOnUiThread { preview.visibility = View.GONE; recBtn.visibility = View.GONE }
        val file = if (path.startsWith("/")) java.io.File(path) else java.io.File(filesDir, path)
        val r = MediaMetadataRetriever().apply { setDataSource(file.absolutePath) }
        val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        val frames = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toInt() ?: (durationMs * 30 / 1000).toInt()
        val fps = if (durationMs > 0) frames * 1000f / durationMs else 30f
        val step = maxOf(1, Math.round(fps / 15f))   // ~15 analysed frames per second is plenty
        var analysed = 0
        var i = 0
        while (i < frames) {
            val bmp = runCatching { r.getFrameAtIndex(i) }.getOrNull() ?: break
            val ts = (i * 1000f / fps).toLong()
            val result = landmarker.detectForVideo(BitmapImageBuilder(bmp).build(), ts)
            runOnUiThread { frame.setImageBitmap(bmp) }
            onPose(result, bmp.width, bmp.height)
            analysed++
            i += step
        }
        r.release()
        Log.i("CALICO_BENCH", "exercise=${counter.exercise} reps=${counter.count} cues=${counter.cues} frames=$analysed")
        runOnUiThread { exerciseText.text = "DONE  ${counter.exercise}  reps=${counter.count} cues=${counter.cues}" }
    }

    // ---------- dev: record labelled bench clips ----------

    private fun toggleRecord() {
        val vc = videoCapture ?: return
        recording?.let { it.stop(); recording = null; return }
        setExercise(counter.exercise)   // reset the live count so it matches the clip
        val file = File(filesDir, "rec_${System.currentTimeMillis()}.mp4")
        recording = vc.output.prepareRecording(this, FileOutputOptions.Builder(file).build())
            .start(mainExecutor) { ev ->
                if (ev is VideoRecordEvent.Finalize) {
                    recBtn.text = "● REC"
                    if (ev.hasError()) file.delete() else labelClip(file, counter.count)
                }
            }
        recBtn.text = "■ STOP"
    }

    /** Ask for the true count and save as <EXERCISE>-live<N>_<truth>.mp4, the bench naming scheme. */
    private fun labelClip(file: File, live: Int) {
        val hold = counter.exercise.holdSec > 0
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = if (hold) "seconds you actually held" else "reps you actually did"
        }
        AlertDialog.Builder(this)
            .setTitle("Label clip").setMessage("Live count was $live").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val truth = input.text.toString().ifBlank { "NA" }
                file.renameTo(File(filesDir, "${counter.exercise}-live${live}_$truth.mp4"))
            }
            .setNegativeButton("Discard") { _, _ -> file.delete() }
            .setCancelable(false).show()
    }

    // ---------- shared ----------

    private fun onPose(result: PoseLandmarkerResult, w: Int, h: Int) {
        val pose = result.landmarks().firstOrNull()
        runOnUiThread { overlay.update(pose ?: emptyList(), w, h) }
        if (pose == null || !orientationOk(pose)) return

        // pick the side whose joints are more visible
        val e = counter.exercise
        if (counter.done) return
        fun vis(idx: IntArray) = idx.minOf { pose[it].visibility().orElse(0f) }
        val j = if (vis(e.left) >= vis(e.right)) e.left else e.right
        if (vis(j) < 0.5f) return
        val a = angle(
            pose[j[0]].x(), pose[j[0]].y(),
            pose[j[1]].x(), pose[j[1]].y(),
            pose[j[2]].x(), pose[j[2]].y(),
        )
        counter.feed(a, result.timestampMs())
    }

    /** Torso vector mid-hip -> mid-shoulder; upright if it's more vertical than horizontal. */
    private fun orientationOk(p: List<NormalizedLandmark>): Boolean {
        val dx = (p[11].x() + p[12].x() - p[23].x() - p[24].x()) / 2f
        val dy = (p[11].y() + p[12].y() - p[23].y() - p[24].y()) / 2f
        return when (counter.exercise.orientation) {
            Orientation.UPRIGHT -> abs(dy) > abs(dx)
            Orientation.HORIZONTAL -> abs(dx) > abs(dy)
            Orientation.ANY -> true
        }
    }

    private fun onRep(n: Int) {
        val hold = counter.exercise.holdSec > 0
        runOnUiThread { repsText.text = if (hold) "${n}s" else n.toString() }
        say(if (counter.done) "Done" else n.toString())
    }

    private fun say(text: String) = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, text)

    override fun onDestroy() {
        super.onDestroy()
        recording?.stop()
        analyzerExecutor.shutdown()
        landmarker.close()
        tts.shutdown()
    }
}
