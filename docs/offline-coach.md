# Offline coach

Current voice routing and the Qwen3-4B upgrade are documented in [Voice and model update](voice-model-update.md). The 1.7B configuration and timings below are historical and remain relevant to the fallback model.

Open **Offline coach → Open coach** on Home. Typed questions run locally after
the model is installed. **Explain my cues** uses the latest saved exercise's
rep/hold count, target, detector cue frequencies and estimated joint-angle range.
No images, camera frames or recordings are passed to the language model.
The bundled explanations cover all 17 exercise types; numerical thresholds come
from the same `Exercise` enum used by the counter.

## Runtime and setup

- Kotlin/Compose UI, one background inference worker, JNI and llama.cpp.
- Qwen3-1.7B Q4_K_M, 1,282,439,264 bytes, Apache 2.0.
- Model revision `daeb8e2d528a760970442092f6bf1e55c3b659eb` from
  <https://huggingface.co/ggml-org/Qwen3-1.7B-GGUF>.
- SHA-256 `d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5`.
- llama.cpp revision `3057bb66c86c46d5781e50e85462a760ba7d1feb`, pinned archive
  and checksum in `app/src/main/cpp/CMakeLists.txt`; KleidiAI CPU kernels enabled.
- ARM64 CPU inference, four threads, 2,048-token context, 192-token response cap,
  Qwen non-thinking chat template and deterministic greedy decoding. No runtime
  HTTP server or cloud API.

The setup screen downloads the pinned model over HTTPS or imports the same GGUF
through Android's file picker. It checks length and SHA-256 before replacing the
private model file. Keep the screen open during setup. Stopped downloads restart
from the beginning. Use **Model setup** to replace a damaged model. At least
1.5 GB free storage is recommended for initial setup (replacement needs space
for both files). The model is excluded from Android backup.

Chat and bounded per-workout history are saved privately and excluded from backup.
Clear removes chat; Forget removes workout history and chat. See
[Voice, workout context and AR proposals](coach-context-and-ar.md) for the current
context and room-JSON workflow. Inference
is cancelled and the model released when the coach screen stops. Initial loading
and file verification can take longer than subsequent questions. A successful
checksum is reused within the same coach instance while file size and modification
time remain unchanged; replacement invalidates that cached result. The **Voice** tab now accepts speech through Android on-device English recognition,
shows the transcript and generated reply, and reads completed answers using an
installed English offline TTS voice. Tap **Talk to coach** to start; **Stop** cancels
listening, generation and playback. Backgrounding or closing the sheet stops all
three. No always-on microphone or cloud speech fallback is used. Typed questions
remain available if a device lacks offline speech services. Replies are prompted
to use concise, practical replies (usually 30?55 words), with up to 90 words for
explicit explanations, steps, or comparisons.

Speech APIs: <https://developer.android.com/reference/android/speech/SpeechRecognizer>
and <https://developer.android.com/reference/android/speech/tts/Voice>. The iQOO
reports English (en-US) recognition installed. Offline TTS completion and microphone
start/stop passed the device test with networking disabled. This service test does
not measure acoustic transcription accuracy with a real speaker.

## Build and validation

Use JDK 17, Android SDK, NDK 29.0.13846066 and CMake 3.22.1:

```powershell
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

The initial native build fetches pinned upstream sources. The APK contains the
native runtime; the large model is not committed or packaged in the APK.

`CoachKnowledgeTest` checks exercise coverage, saved data, reference selection
and bounded prompt history. `OfflineCoachTest` requires the pinned model under
the target app's `files/models/` directory and exercises real model answers and
cancellation. It writes synthetic answers and timing measurements to
`files/coach-test-result.json`, with no real user workout data.

Validated on the connected iQOO I2501 (SM8850) on 2026-09-12: debug APK installed,
18 app unit tests passed, and device inference/cancellation passed with Wi-Fi and
mobile data disabled (both restored afterward). Manually reviewed generated
pushup instructions and the squat detector-rule explanation. The final synthetic
test took 12.0 seconds for the squat answer and 43.5 seconds for the pushup answer;
background throttling affected timings. A foreground UI pushup answer completed
in 9.8 seconds in an earlier run. These are observations, not latency guarantees.
Process PSS was about 1.6 GB while the model was loaded and about 123 MB after
leaving the coach during a new request. The model is already installed on this
development phone; a fresh installation on another phone still needs setup.

## Harness tuning

Simple exact greetings and acknowledgements return immediately without loading
Qwen. Substantive questions always use the model. Prompts retrieve the specific
exercise (including spoken hyphen/space aliases), keep the previous exercise for
follow-ups, and exclude unrelated saved workouts. Plain exercise questions omit
counter thresholds; detector questions retain the actual rules. Easier-pushup
follow-ups explicitly retrieve the documented wall press-up option. UI token
updates are coalesced to at most roughly 12 per second, with a final flush before
TTS reads the completed reply. Ordinary replies stop after three complete
sentences; explicit detail requests allow five. Generic closing offers are
removed. Cancellation and errors do not trigger TTS.

`BaselineCoachPrompt` is a test-only historical prompt policy, not another runtime.
The device benchmark runs old and new squat/pushup questions, then an easier
pushup follow-up. Pass instrumentation argument `newOnly=true` to check only the
current policy after a wording change. These are small regression probes, not a
comprehensive assessment of exercise advice.

## Limits

This is a small generative model: answers can still be inaccurate. Detector cues
and 2D angle estimates are not verified form faults or injury diagnoses. The
coach has no live camera view and does not change rep counting, rig animation
or training data. A validated room proposal can select a measured floor and an
existing AR demo after the user chooses Preview. Prompts request short grounded explanations;
these instructions are not a guarantee of correctness. General movement notes
draw on <https://www.nhs.uk/live-well/exercise/strength-exercises/>; app-specific
explanations describe the actual counter rules. Model and runtime licenses are
accessible under **About & licenses**.

Final tuning check: squat cue prompt 2,199 ? 1,823 characters; basic pushup
prompt 2,262 ? 1,333 characters (about 17% and 41% less input respectively).
The first comparison reduced first-token time from about 9 seconds to 5?7 seconds.
A later final-policy run, interrupted by foreground/background switches, took
11?23 seconds to the first token; latency is not guaranteed. Final outputs used
33 tokens for the cue explanation, 58 for pushup steps and 12 for the easier
wall-press-up follow-up. Reviewed the actual text, not only test pass/fail.
