package com.hackathon.calico

import android.Manifest
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.Log
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
 * MODE 2: live pose tracking, rep counting, voice cues, one routine step after another.
 * Intent extras (first match wins):
 *   routine  = "PUSHUP:10,SQUAT:15,PLANK:30"  (see Step.encode)
 *   exercise = a single Exercise name, open-ended count
 *   video    = mp4 path (absolute, or a filename in filesDir): bench mode, replays the file
 *              and logs "CALICO_BENCH exercise=.. reps=.. cues=.. frames=.."
 */
class WorkoutActivity : ComponentActivity() {
    private enum class Phase { RUNNING, REST, DONE }

    // camera + model
    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var landmarker: PoseLandmarker
    private lateinit var tts: TextToSpeech
    private lateinit var counter: RepCounter
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    // routine
    private lateinit var steps: List<Step>
    private val bench get() = intent.hasExtra("video")
    private val startedAt = SystemClock.uptimeMillis()

    // observable UI state
    private var phase by mutableStateOf(Phase.RUNNING)
    private var stepIndex by mutableIntStateOf(0)
    private var count by mutableIntStateOf(0)
    private var cue by mutableStateOf<Pair<String, Long>?>(null)   // text + id so the same cue can re-fire
    private var restLeft by mutableIntStateOf(0)
    private var recordingNow by mutableStateOf(false)
    private var benchFrame by mutableStateOf<Bitmap?>(null)
    private var benchDone by mutableStateOf<String?>(null)
    private val results = mutableStateListOf<Pair<Step, Int>>()
    private var streakBefore = 0
    private var streakAfter = 0

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        previewView = PreviewView(this)
        overlay = OverlayView(this, null)
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts.language = Locale.US }

        val single = intent.getStringExtra("exercise")?.let(Exercise::valueOf)
        steps = when {
            intent.hasExtra("routine") -> decodeSteps(intent.getStringExtra("routine")!!)
            single != null -> listOf(Step(single, if (single.holdSec > 0) single.holdSec else Int.MAX_VALUE))
            else -> Progress(this).todayPlan
        }
        setStep(0)

        val video = intent.getStringExtra("video")
        landmarker = createLandmarker(if (video != null) RunningMode.VIDEO else RunningMode.LIVE_STREAM)
        setContent { CalicoTheme { Screen() } }
        if (video != null) runVideo(video) else askCamera.launch(Manifest.permission.CAMERA)
    }

    // ---------- routine flow ----------

    private fun setStep(i: Int) {
        stepIndex = i
        count = 0
        val s = steps[i]
        counter = RepCounter(s.exercise, onRep = ::onRep, onCue = ::onCue, holdSec = if (s.exercise.holdSec > 0) s.target else 0)
        overlay.highlight = s.exercise.left + s.exercise.right
        phase = Phase.RUNNING
    }

    private fun onRep(n: Int) {
        count = n
        if (n >= steps[stepIndex].target) advance() else say(n.toString())
    }

    private fun onCue(text: String) { cue = text to SystemClock.uptimeMillis(); say(text) }

    private fun advance() {
        if (phase != Phase.RUNNING) return
        results += steps[stepIndex] to count
        if (stepIndex == steps.lastIndex) {
            phase = Phase.DONE
            cameraProvider?.unbindAll()   // camera + model idle on the summary screen
            if (!bench) {
                val p = Progress(this)
                streakBefore = p.streak; p.recordWorkout(); streakAfter = p.streak
            }
            say("Workout complete")
            return
        }
        phase = Phase.REST
        restLeft = 5
        say("Nice. Next up, ${steps[stepIndex + 1].exercise.label}")
        fun tick() {
            if (phase != Phase.REST) return
            if (restLeft <= 1) setStep(stepIndex + 1) else { restLeft--; ui.postDelayed(::tick, 1000) }
        }
        ui.postDelayed(::tick, 1000)
    }

    private fun say(text: String) = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, text)

    // ---------- UI ----------

    @Composable
    private fun Screen() {
        Box(Modifier.fillMaxSize().background(Ink)) {
            if (phase == Phase.DONE && !bench) { Complete(); return@Box }
            if (bench) benchFrame?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            else AndroidView({ previewView }, Modifier.fillMaxSize())
            AndroidView({ overlay }, Modifier.fillMaxSize())
            Hud()
            if (phase == Phase.REST) Rest()
        }
    }

    @Composable
    private fun Hud() {
        val step = steps[stepIndex]
        val hold = step.exercise.holdSec > 0
        val target = step.target
        val progress by animateFloatAsState(
            if (target == Int.MAX_VALUE) 0f else (count.toFloat() / target).coerceIn(0f, 1f), tween(250), label = "ring",
        )
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
            // header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (steps.size > 1) "STEP ${stepIndex + 1} OF ${steps.size}" else "FREE SET",
                        style = MaterialTheme.typography.labelSmall, color = Lime,
                    )
                    Text(step.exercise.label, style = MaterialTheme.typography.headlineLarge, color = Snow)
                }
                if (steps.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    steps.indices.forEach { i ->
                        Box(Modifier.size(width = 18.dp, height = 6.dp).background(if (i <= stepIndex) Lime else Surface2, CircleShape))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // counter ring
            Box(Modifier.size(180.dp).align(Alignment.CenterHorizontally), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 12.dp.toPx()
                    val inset = stroke / 2
                    val arc = Size(size.width - stroke, size.height - stroke)
                    drawArc(Surface2.copy(alpha = 0.8f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                    drawArc(Lime, -90f, 360f * progress, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (hold) "${count}s" else "$count", style = MaterialTheme.typography.displayMedium, color = Snow)
                    if (target != Int.MAX_VALUE) Text(
                        if (hold) "of ${target}s" else "of $target",
                        style = MaterialTheme.typography.titleMedium, color = Fog,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            // form cue
            val c = cue
            var visible by androidx.compose.runtime.remember { mutableStateOf(false) }
            LaunchedEffect(c?.second) { if (c != null) { visible = true; kotlinx.coroutines.delay(1600); visible = false } }
            AnimatedVisibility(visible, Modifier.align(Alignment.CenterHorizontally), enter = fadeIn(), exit = fadeOut()) {
                Surface(shape = RoundedCornerShape(16.dp), color = Ember) {
                    Text(c?.first ?: "", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.headlineSmall, color = Ink)
                }
            }
            Spacer(Modifier.height(16.dp))
            benchDone?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Lime) }
            // footer
            if (!bench) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = ::toggleRecord) { Text(if (recordingNow) "■ STOP" else "● REC", color = if (recordingNow) Ember else Fog) }
                Spacer(Modifier.weight(1f))
                if (target != Int.MAX_VALUE) TextButton(onClick = ::advance) {
                    Text(if (stepIndex == steps.lastIndex) "FINISH" else "SKIP", style = MaterialTheme.typography.labelLarge, color = Snow)
                }
            }
        }
    }

    @Composable
    private fun Rest() {
        val next = steps[stepIndex + 1]
        Box(Modifier.fillMaxSize().background(Ink.copy(alpha = 0.88f)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("REST", style = MaterialTheme.typography.labelSmall, color = Fog)
                Text("$restLeft", style = MaterialTheme.typography.displayLarge, color = Lime)
                Spacer(Modifier.height(24.dp))
                Text("NEXT UP", style = MaterialTheme.typography.labelSmall, color = Fog)
                Text(next.exercise.label, style = MaterialTheme.typography.headlineLarge, color = Snow)
                Text(
                    if (next.exercise.holdSec > 0) "hold ${next.target}s" else "×${next.target}",
                    style = MaterialTheme.typography.headlineSmall, color = Lime,
                )
            }
        }
    }

    @Composable
    private fun Complete() {
        val streak by animateIntAsState(streakAfter, tween(1200, delayMillis = 400), label = "streak")
        val secs = (SystemClock.uptimeMillis() - startedAt) / 1000
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))
            Text("WORKOUT COMPLETE", style = MaterialTheme.typography.labelSmall, color = Lime)
            Spacer(Modifier.height(16.dp))
            Text("🔥", style = MaterialTheme.typography.displayMedium)
            Text("${if (streak == 0) streakBefore else streak}", style = MaterialTheme.typography.displayLarge, color = Snow)
            Text(
                if (streakAfter > streakBefore) "day streak  •  +1" else "day streak",
                style = MaterialTheme.typography.titleMedium, color = Fog,
            )
            Spacer(Modifier.height(32.dp))
            Surface(shape = RoundedCornerShape(20.dp), color = Surface1, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    results.forEach { (s, got) ->
                        Row {
                            Text(s.exercise.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            val unit = if (s.exercise.holdSec > 0) "s" else ""
                            Text("$got$unit / ${s.target}$unit", style = MaterialTheme.typography.titleMedium, color = if (got >= s.target) Lime else Ember)
                        }
                    }
                    Row {
                        Text("Time", style = MaterialTheme.typography.titleMedium, color = Fog, modifier = Modifier.weight(1f))
                        Text("${secs / 60}m ${secs % 60}s", style = MaterialTheme.typography.titleMedium, color = Fog)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = ::finish,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink),
            ) { Text("BACK HOME", style = MaterialTheme.typography.labelLarge) }
        }
    }

    // ---------- camera ----------

    private fun createLandmarker(mode: RunningMode): PoseLandmarker {
        val opts = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").setDelegate(Delegate.GPU).build())
            .setRunningMode(mode)
            .setErrorListener { it.printStackTrace() }
        if (mode == RunningMode.LIVE_STREAM) opts.setResultListener { r, img -> onPose(r, img.width, img.height) }
        return PoseLandmarker.createFromOptions(this, opts.build())
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get().also { cameraProvider = it }
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build().also { it.setAnalyzer(analyzerExecutor, ::analyze) }
            val vc = VideoCapture.withOutput(Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build())
            provider.unbindAll()
            try {
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis, vc)
                videoCapture = vc
            } catch (e: Exception) {   // device can't run preview + analysis + video together
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            }
        }, mainExecutor)
    }

    private fun analyze(image: ImageProxy) {
        val bitmap = image.use { it.toBitmap() }
        // rotate to upright and mirror (front camera) so the overlay matches the preview
        val m = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()); postScale(-1f, 1f) }
        val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        landmarker.detectAsync(BitmapImageBuilder(upright).build(), SystemClock.uptimeMillis())
    }

    // ---------- bench: replay a video file ----------

    private fun runVideo(path: String) = thread {
        val file = if (path.startsWith("/")) File(path) else File(filesDir, path)
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
            benchFrame = bmp
            onPose(result, bmp.width, bmp.height)
            analysed++
            i += step
        }
        r.release()
        Log.i("CALICO_BENCH", "exercise=${counter.exercise} reps=${counter.count} cues=${counter.cues} frames=$analysed")
        benchDone = "DONE  reps=${counter.count} cues=${counter.cues}"
    }

    // ---------- dev: record labelled bench clips ----------

    private fun toggleRecord() {
        val vc = videoCapture ?: return
        recording?.let { it.stop(); recording = null; return }
        setStep(stepIndex)   // reset the live count so it matches the clip
        val file = File(filesDir, "rec_${System.currentTimeMillis()}.mp4")
        recording = vc.output.prepareRecording(this, FileOutputOptions.Builder(file).build())
            .start(mainExecutor) { ev ->
                if (ev is VideoRecordEvent.Finalize) {
                    recordingNow = false
                    if (ev.hasError()) file.delete() else labelClip(file, counter.count)
                }
            }
        recordingNow = true
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
        overlay.update(pose ?: emptyList(), w, h)
        if (pose == null || phase != Phase.RUNNING || counter.done || !orientationOk(pose)) return

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
        ui.post { counter.feed(a, result.timestampMs()) }   // state writes on the main thread
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

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        recording?.stop()
        analyzerExecutor.shutdown()
        landmarker.close()
        tts.shutdown()
    }
}
