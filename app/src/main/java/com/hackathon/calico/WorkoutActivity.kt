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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
    private enum class Phase { RUNNING, PAUSED, REST, DONE }

    // camera + model
    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var landmarker: PoseLandmarker
    private lateinit var tts: TextToSpeech
    private lateinit var counter: RepCounter          // single side, or the left side in SUM mode
    private var counterR: RepCounter? = null          // SUM mode: the right side; totals reported via total()
    private fun total() = counter.count + (counterR?.count ?: 0)
    private fun totalCues() = counter.cues + (counterR?.cues ?: 0)
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
    private var benchLog: java.io.PrintWriter? = null   // bench trace + result, read by bench/run.sh via run-as
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
        val hold = if (s.exercise.holdSec > 0) s.target else 0
        counter = RepCounter(s.exercise, onRep = { onRep(total()) }, onCue = ::onCue, holdSec = hold)
        counterR = if (s.exercise.sides == Sides.SUM) RepCounter(s.exercise, onRep = { onRep(total()) }, onCue = ::onCue, holdSec = hold) else null
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
        Box(Modifier.fillMaxSize().background(Navy)) {
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
        val open = target == Int.MAX_VALUE
        // elapsed clock
        var elapsed by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) { while (true) { delay(1000); elapsed = ((SystemClock.uptimeMillis() - startedAt) / 1000).toInt() } }

        Column(Modifier.fillMaxSize()) {
            // top: title + pause, side stat pill
            Row(Modifier.statusBarsPadding().padding(20.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("Your Workout", style = MaterialTheme.typography.headlineLarge, color = Snow)
                    Text(
                        (if (step.warmup) "Warm-up · " else "") + step.exercise.label,
                        style = MaterialTheme.typography.titleMedium, color = Snow.copy(0.85f),
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(52.dp).background(Snow, CircleShape).clickable { phase = if (phase == Phase.PAUSED) Phase.RUNNING else Phase.PAUSED },
                        contentAlignment = Alignment.Center,
                    ) { Icon(if (phase == Phase.PAUSED) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, "pause", tint = Navy) }
                    Spacer(Modifier.height(14.dp))
                    Column(Modifier.background(Snow, Pill).padding(horizontal = 14.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (open) "$count" else "$count / $target", style = MaterialTheme.typography.titleMedium, color = Ink)
                        Text(if (hold) "seconds" else "reps", style = MaterialTheme.typography.labelSmall, color = Muted)
                        if (steps.size > 1) {
                            Spacer(Modifier.height(8.dp))
                            steps.indices.forEach { i ->
                                Box(Modifier.padding(vertical = 2.dp).size(width = 34.dp, height = 8.dp).background(if (i <= stepIndex) Purple else PurpleSoft, Pill))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            // form cue
            val c = cue
            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(c?.second) { if (c != null) { visible = true; delay(1600); visible = false } }
            AnimatedVisibility(visible, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp), enter = fadeIn(), exit = fadeOut()) {
                Text(c?.first ?: "", Modifier.background(Lime, Pill).padding(horizontal = 22.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge, color = Navy)
            }
            benchDone?.let { Text(it, Modifier.align(Alignment.CenterHorizontally).padding(8.dp), style = MaterialTheme.typography.titleMedium, color = Lime) }
            // bottom sheet
            Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)).padding(24.dp).navigationBarsPadding()) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 4.dp).background(Line, Pill))
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Elapsed", style = MaterialTheme.typography.labelMedium, color = Muted)
                        Text("%02d:%02d".format(elapsed / 60, elapsed % 60), style = MaterialTheme.typography.titleLarge, color = Ink)
                    }
                    Text(
                        if (hold) "${count}s" else "$count",
                        style = MaterialTheme.typography.displayLarge, color = Navy,
                        modifier = Modifier.background(Navy.copy(0.06f), TileShape).padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text("Step", style = MaterialTheme.typography.labelMedium, color = Muted)
                        Text(if (steps.size > 1) "${stepIndex + 1}/${steps.size}" else "free", style = MaterialTheme.typography.titleLarge, color = Ink)
                    }
                }
                if (!bench) {
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (recordingNow) "■ STOP REC" else "● REC",
                            Modifier.clickable(onClick = ::toggleRecord).padding(8.dp),
                            style = MaterialTheme.typography.labelMedium, color = if (recordingNow) Purple else Muted,
                        )
                        Spacer(Modifier.weight(1f))
                        if (!open) Text(
                            if (stepIndex == steps.lastIndex) "FINISH" else "SKIP",
                            Modifier.background(Navy, Pill).clickable(onClick = ::advance).padding(horizontal = 26.dp, vertical = 14.dp),
                            style = MaterialTheme.typography.labelLarge, color = Lime,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun Rest() {
        val next = steps[stepIndex + 1]
        Box(Modifier.fillMaxSize().background(Navy.copy(alpha = 0.86f)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("REST", style = MaterialTheme.typography.labelMedium, color = Snow.copy(0.7f))
                Text("$restLeft", style = MaterialTheme.typography.displayLarge, color = Lime)
                Spacer(Modifier.height(24.dp))
                Text("NEXT UP", style = MaterialTheme.typography.labelMedium, color = Snow.copy(0.7f))
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
        val hit = results.count { (s, got) -> got >= s.target }
        val pct = if (results.isEmpty()) 0 else hit * 100 / results.size
        val gauge by animateFloatAsState(pct / 100f, tween(1200, delayMillis = 300), label = "gauge")
        val streak by animateIntAsState(streakAfter, tween(900, delayMillis = 900), label = "streak")
        val secs = (SystemClock.uptimeMillis() - startedAt) / 1000
        Column(
            Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Text("Great Work!", style = MaterialTheme.typography.bodyLarge, color = Muted)
            Text("Workout Complete 🔥", style = MaterialTheme.typography.displayMedium, color = Ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            // semicircle gauge
            Box(Modifier.size(260.dp, 150.dp), contentAlignment = Alignment.BottomCenter) {
                Canvas(Modifier.fillMaxSize()) {
                    val s = 34.dp.toPx(); val d = size.width - s
                    val tl = Offset(s / 2, s / 2); val sz = Size(d, d)
                    drawArc(Line, 180f, 180f, false, tl, sz, style = Stroke(s, cap = StrokeCap.Round))
                    drawArc(Purple, 180f, 180f * gauge, false, tl, sz, style = Stroke(s, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${(gauge * 100).toInt()}", style = MaterialTheme.typography.displayLarge, color = Ink)
                        Text("%", style = MaterialTheme.typography.titleLarge, color = Ink, modifier = Modifier.padding(bottom = 12.dp))
                    }
                    Text("Targets hit", style = MaterialTheme.typography.labelMedium, color = Muted)
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth().background(Lime, Pill).padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🔥", fontSize = 22.sp)
                Spacer(Modifier.width(12.dp))
                Text(
                    "${if (streak == 0) streakBefore else streak} day streak" + if (streakAfter > streakBefore) "  ·  +1" else "",
                    style = MaterialTheme.typography.titleMedium, color = Ink,
                )
                Spacer(Modifier.weight(1f))
                Text("${secs / 60}m ${secs % 60}s", style = MaterialTheme.typography.labelMedium, color = Ink.copy(0.7f))
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth()) { Text("Today Stats", style = MaterialTheme.typography.headlineSmall, color = Ink) }
            Spacer(Modifier.height(12.dp))
            results.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEachIndexed { j, (s, got) ->
                        val ok = got >= s.target
                        Column(Modifier.weight(1f).background(Card, TileShape).padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).background(if (ok) Purple else Pink, CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text(s.exercise.label, style = MaterialTheme.typography.labelMedium, color = Muted)
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text("$got", style = MaterialTheme.typography.headlineLarge, color = Ink)
                                Spacer(Modifier.width(6.dp))
                                Text("/ ${s.target}${if (s.exercise.holdSec > 0) "s" else ""}", style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(bottom = 6.dp))
                            }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "BACK HOME",
                Modifier.fillMaxWidth().background(Navy, Pill).clickable(onClick = ::finish).padding(vertical = 20.dp),
                style = MaterialTheme.typography.labelLarge, color = Lime, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
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
        benchLog = File(filesDir, "bench.log").printWriter()
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
        val line = "exercise=${counter.exercise} reps=${total()} cues=${totalCues()} frames=$analysed"
        Log.i("CALICO_BENCH", line)
        benchLog?.println("RESULT $line"); benchLog?.close(); benchLog = null
        benchDone = "DONE  reps=${total()} cues=${totalCues()}"
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
                    if (ev.hasError()) file.delete() else labelClip(file, total())
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

        val e = counter.exercise
        fun vis(idx: IntArray) = idx.minOf { pose[it].visibility().orElse(0f) }
        fun ang(j: IntArray) = angle(pose[j[0]].x(), pose[j[0]].y(), pose[j[1]].x(), pose[j[1]].y(), pose[j[2]].x(), pose[j[2]].y())
        val lOk = vis(e.left) >= 0.5f; val rOk = vis(e.right) >= 0.5f
        val t = result.timestampMs()
        if (e.sides == Sides.SUM) {   // each leg has its own counter
            val l = if (lOk) ang(e.left) else null; val r = if (rOk) ang(e.right) else null
            benchLog?.println("t=$t l=${l?.toInt() ?: "-"} r=${r?.toInt() ?: "-"}")
            ui.post { l?.let { counter.feed(it, t) }; r?.let { counterR?.feed(it, t) } }
            return
        }
        val a = when {
            e.sides == Sides.EITHER && lOk && rOk -> maxOf(ang(e.left), ang(e.right))
            lOk && (!rOk || vis(e.left) >= vis(e.right)) -> ang(e.left)   // the more visible side
            rOk -> ang(e.right)
            else -> return
        }
        benchLog?.println("t=$t a=${"%.0f".format(a)} l=${if (lOk) "%.0f".format(ang(e.left)) else "-"} r=${if (rOk) "%.0f".format(ang(e.right)) else "-"} vis=${"%.2f/%.2f".format(vis(e.left), vis(e.right))}")
        ui.post { counter.feed(a, t) }   // state writes on the main thread
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
