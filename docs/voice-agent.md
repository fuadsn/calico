# Calico hands-free agent

Turn on **Hey Calico** on Home and allow microphone access. While Calico is in
the foreground, say **Calico**, wait for the listening screen, then speak your
command or coach question. Commands do not require Qwen to be loaded. Questions
use the existing offline model, saved workout context and offline speech output.

Examples:

- `Open workouts`, `open journey`, `open scanner`, `open coach`.
- `Start today's workout`, `start Floor Basics`, `start ten incline pushups`,
  `start plank for thirty seconds`, `show jumping jacks`.
- During a workout, `pause workout`, `resume workout`, `skip exercise` work
  directly, without Calico. After waking: `pause`, `play`, `skip rest`,
  `end workout`, `start recording`, `stop recording`.
- `Tap Journey`, `tap Workout overview`, `tap Model setup` and other registered
  button labels invoke existing callbacks. Native buttons such as scanner
  buttons are rechecked after their screen resumes.
- `Close voice`, `go back`, `stop listening` (disables wake listening).

This operates inside Calico. It does not listen with the app backgrounded or
screen locked, click Android permission/file-picker screens, drag the mannequin,
or simulate arbitrary gestures. Unavailable commands report the limitation.
Use a saved session for multiple exercises. Default single-exercise targets are
ten reps or the exercise's existing hold duration.

## Workout side orb

During a workout, Calico opens a 56 dp orb and short caption on the right edge.
It adds no full-screen window or touch-blocking scrim and does not pause the
workout merely by opening. The pose camera continues running. Workout spoken
rep announcements are muted while the orb owns the microphone, avoiding speech
overlap; counting continues. The orb handles one request per wake word: it closes
once its reply is spoken, after about twelve seconds with nothing said, on error,
or from its close button, and wake listening resumes as it closes. Saying only
"Calico" keeps it listening for the request. Outside workouts, the full
voice screen remains available.

- `Pause`, `start`, `resume`, `stop workout`: keep the workout on screen. Stop
  saves and pauses; it does not discard the session or navigate home.
- `Restart exercise` resets the current count. `Restart workout` starts the
  routine again with a new history session.
- `Change exercise to squats` replaces the current step and preserves paused
  state. `Start ten squats` changes the step and starts it immediately.
- `Set reps to twenty`, `change rep count to fifteen`, `set hold time to forty
  five seconds`: change the target without altering completed reps/held time.
  Targets must exceed the count already achieved, up to 300 reps/seconds.
- `How many reps` reports the current exercise, target and state.

Restart/change attempts get separate history identifiers so they do not erase
earlier attempts. Pose samples from replaced counters are rejected. The device
orb test checks that the workout stays RESUMED, pose frames continue, the orb
starts recognition, edits take effect, and no full-screen voice activity opens.

## Implementation

`WakeDetector` uses sherpa-onnx 1.13.8 and the English GigaSpeech 3.3M streaming
keyword model. One worker owns AudioRecord (16 kHz mono), native model and stream.
It releases the microphone before the on-device Android SpeechRecognizer starts.
Calico/Kalico tokenizations accommodate pronunciation variants. Audio is
processed locally without being saved. There is no AccessibilityService.

`VoiceCommands` handles explicit commands first, with no model involved. When it
cannot read a request, `CoachViewModel.ask` sends it to the local model with
`CoachKnowledge.intentPrompt`: a fixed list of actions, the exercise and session
names, and the open workout's state. The reply is opened for the model as
`{"action":"` and it may only fill in one action and its parameters;
`VoiceCommands.fromIntent` maps that JSON onto the same bounded commands the parser
produces and `VoiceAgent.execute` runs it through the same code. An unknown action,
exercise or `question` falls through to a normal coach answer. The model never
writes text that the app runs. `adjust_target` ("five more reps") is relative to
the current goal and keeps the completed count. Device check: `VoiceIntentDeviceTest`
sends loose phrasings through the NPU and logs the chosen action and latency.
`VoiceAgent` keeps a weak reference to the originating activity. Compose screens
register available callbacks with `VoiceActionBindings`; native buttons need a
unique visible label and must still be enabled after returning to the screen.

Leaving a workout pauses it. Pause clears partial rep/hold timing, preserving
totals. Rest timer generations prevent old timers advancing a new step after
a skip. A paused workout stays paused unless the user requests resume.

AR scanner/session/preview remain at `69fc362`. This feature changes no AR
placement, rig or scanning files.

## Spoken answers start before the model finishes

`CoachViewModel` publishes each answer as a `CoachSpeech(turn, text, done)` while
it is still being generated. `CoachVoice.speakStreaming` keeps a cursor into that
text, hands every newly finished sentence to Android text-to-speech with
`QUEUE_ADD`, and reports `speaking` until both the queue has drained and the turn
is closed, so a drained queue mid-answer does not reopen the microphone.
`CoachReplyPolicy.speakableCut` finds the sentence boundary and ignores a dot
after a digit or a single letter, which keeps list markers and abbreviations from
being read as endings. A stopped turn is remembered, so a repeated state update
cannot restart speech the user just cancelled. The microphone side needs no
streaming: on the iQOO 15 the recognizer's final result lands 1-2 ms after
end-of-speech, and partial words already show while the user talks.

Only model answers stream. Command replies, quick replies and stored answers are
short and still spoken in one call, so `CoachState.speech` stays null for them.
`CoachVoice` logs `firstSentenceMs` (time from the turn opening to the first
utterance being queued) for measuring this on a device.

## Assets and validation

The build downloads the pinned Android AAR and verifies SHA-256
`633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`.
The model assets are bundled (about 6 MB), with no separate user download.
Source archive:
https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2

Use the standard encoder export. The mobile fixed-batch encoder crashed in
native ONNX inference on the iQOO during validation. Decoder and joiner are
identical between these archives. Keywords use uppercase SentencePiece encoding
with the archive's bpe.model and explicit output labels; see assets/wake/keywords.txt.
Licenses are bundled and displayed under About & licenses.

| File | SHA-256 |
| --- | --- |
| decoder.onnx | f61ebd3eed3773a44d088d53dfae92dbb6aec4839f4dcaee2d402414741663a3 |
| encoder.int8.onnx | 1e721676515bcd42a186979733981213c66c80db680e1cc582dfedf3be76e678 |
| joiner.int8.onnx | eae9da0c7e1e6c6a3f4cc42d167899c388f6c6701b94cb96320e4f55df79624c |

Validation includes app/roomscan unit tests; command coverage for all exercises,
spoken targets, ambiguity and pause timing; native on-device keyword detection
using offline synthesized speech for Calico/Hey Calico and three controls plus
seven unrelated phrases; and a device UI test covering wake-to-listening handoff
and routing Tap Journey to the originating screen. Synthetic tests do not
establish accuracy for the user's voice, room noise or distance.

The September 13 correction replaces the mistaken Calivo wake vocabulary with
Calico/Kalico and adjusts its score/threshold to 2.0/0.15. Both Calico and Hey
Calico pass recorded-speech checks. A speaker-to-microphone smoke test is also
available in VoiceAgentDeviceTest; it has shown intermittent detection, so it
must not be treated as a real-world accuracy guarantee. The listening status is
now published only after AudioRecord reports an active recording state.

References: [sherpa keyword spotting](https://k2-fsa.github.io/sherpa/onnx/kws/index.html),
[Android background activity restrictions](https://developer.android.com/guide/components/activities/secure-bal),
[Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer).
