# Offline coach

Current voice routing and the model itself are documented in [Voice and model update](voice-model-update.md). Timings recorded before the NPU move are marked historical where they appear below.

Open **Offline coach → Open coach** on Home. Typed questions run locally after
the model is installed. **Explain my cues** uses the latest saved exercise's
rep/hold count, target, detector cue frequencies and estimated joint-angle range.
No images, camera frames or recordings are passed to the language model.
The bundled explanations cover all 17 exercise types; numerical thresholds come
from the same `Exercise` enum used by the counter.

## Runtime and setup

- Kotlin/Compose UI, one background inference worker, JNI and llama.cpp.
- Qwen3-4B-Instruct-2507 Q4_0, 2,375,773,280 bytes, Apache 2.0. Details and checksum
  in [Voice and model update](voice-model-update.md).
- llama.cpp revision `3057bb66c86c46d5781e50e85462a760ba7d1feb`, pinned archive and
  checksum in `tools/build-llama-snapdragon.py`.
- **Hexagon NPU inference.** Every layer is offloaded to `HTP0`; there is no CPU
  fallback, and a phone without the NPU reports that the coach cannot run. 2,048-token
  context, 512-token batch, flash attention, 192-token response cap, Qwen non-thinking
  chat template and deterministic greedy decoding. No runtime HTTP server or cloud API.
- The loaded model is held for the whole process by `CoachEngine`, not per screen, so
  closing the coach or the workout orb does not pay the several-second load again. It
  holds about 2.5 GB while loaded and is released on `TRIM_MEMORY_RUNNING_LOW` and above.

### Measured on the iQOO I2501 (SM8850, Hexagon v81), 2026-09-13

Qwen3-4B-Instruct-2507, `llama-bench`, tokens per second:

| Backend | Quant | pp512 | tg64 |
| --- | --- | ---: | ---: |
| CPU, six threads (previous runtime) | Q4_K_M | 42 | 15 |
| CPU, six threads | Q4_0 | 58 | 18 |
| Adreno 840, OpenCL | Q4_0 | 292 | 22 |
| Hexagon NPU, batch 512 | Q4_0 | 1484 | 17-19 |

A real 419-token coach prompt on the NPU took 0.34 s to prefill. These are single
observations on one phone, not latency guarantees.

The model is the user's file, not the app's. **Download offline coach** asks where to
save it (a Storage Access Framework document, typically in Downloads) and streams the
pinned GGUF there; **Locate model file** references an existing copy in place without
copying it. Either way Calico keeps only a persisted URI grant, so uninstalling,
reinstalling or clearing the app's data leaves the 2.4 GB file untouched. After a
reinstall the grant is gone, so the setup card comes back and one **Locate model file**
tap restores the coach. Length and SHA-256 are checked once per file and the result is
remembered until size or modification time changes. Linking a document deletes any
older copy under the app's private storage. Keep the screen open during setup; a
stopped download is deleted and restarts from the beginning.

llama.cpp opens the document through its descriptor as `fd:N`. Scoped storage refuses
to reopen a document by path, even through `/proc/self/fd`, so
`tools/build-llama-snapdragon.py` patches `ggml_fopen` in the pinned source to adopt
an already-open descriptor. Every model open, the GGUF header and the weights, goes
through that one function.

Chat and bounded per-workout history are saved privately and excluded from backup.
Clear removes chat; Forget removes workout history and chat. See
[Voice, workout context and AR proposals](coach-context-and-ar.md) for the current
context and room-JSON workflow. Leaving the coach screen cancels generation but keeps
the model loaded, so only the first question of a session waits for loading. The **Voice** tab now accepts speech through Android on-device English recognition,
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

The Hexagon skels need Qualcomm's hexagon-clang, which the Android NDK does not ship,
so llama.cpp is cross-compiled once in the pinned `snapdragon-toolchain` container. With
Docker Desktop running:

```powershell
python tools/build-llama-snapdragon.py
```

That writes the prebuilt libraries into `app/src/main/jniLibs/arm64-v8a` (ignored by git;
about 10 GB of image download and 15 minutes the first time). Gradle then builds normally
with JDK 17, Android SDK, NDK 29.0.13846066 and CMake 3.22.1:

```powershell
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

Gradle fails with a pointer to the script if those libraries are missing. The APK contains
the native runtime; the large model is not committed or packaged in the APK. Native
libraries are packaged uncompressed (`useLegacyPackaging`) because the NPU loader opens the
Hexagon skel by file path.

`CoachKnowledgeTest` checks exercise coverage, saved data, reference selection
and bounded prompt history. `OfflineCoachTest` requires the pinned model to be
available to the app (linked or sideloaded) and exercises real model answers and
cancellation. It writes synthetic answers and timing measurements to
`files/coach-test-result.json`, with no real user workout data.

Validated on the connected iQOO I2501 (SM8850, Hexagon v81) on 2026-09-13 after the
NPU move: unit tests pass, `OfflineCoachTest`, `CoachContextDeviceTest` and
`CoachEngineDeviceTest` pass on the device, and logcat confirms
`Hexagon Arch version v81`, `offloaded 37/37 layers` and a 1,955 MiB HTP0 model buffer.
Time to the first token across the five synthetic answers was 418, 387, 406, 300 and
311 ms, against 8.8 to 17.7 seconds on the previous CPU runtime. Model load was about
7 seconds cold. A 304 MiB token-embedding tensor stays on the CPU; that is expected.
Answers were read, not just checked for a pass. These are observations on one phone,
not latency guarantees.

The device tests use whatever model the app has: a linked document, or a copy
sideloaded into `files/models`. `gradle.properties` sets
`android.injected.androidTest.leaveApksInstalledAfterRun=true` so
`connectedDebugAndroidTest` no longer uninstalls the app, and with it the app's
private data, after a run.

The earlier CPU-runtime measurement, kept for comparison: 12.0 seconds for a squat
answer and 43.5 seconds for a pushup answer, with process PSS about 1.6 GB while
loaded. The model is already installed on this development phone; a fresh
installation on another phone still needs setup.

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
