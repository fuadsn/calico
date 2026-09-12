# Voice, workout context and AR proposals

The microphone uses the app palette. One tap starts recording; a second deliberate
tap finishes the message. Finish is idempotent, rapid repeated mic/send taps are
ignored for 600 ms, and new recognition cannot start while a result is pending.
An 8-second finishing timeout restores retry controls. Close cancels listening,
generation and playback. These transitions follow Android's requirement to await
onResults/onError after stopListening:
https://developer.android.com/reference/android/speech/SpeechRecognizer

## Saved workout context

Each workout has a session ID and each exercise step has its own summary. Updates
replace that step, rather than recording every rep as another workout. Up to 200
step summaries and the latest 20 chat messages are stored privately. Existing
single-summary data remains readable. Voice and typed chat share this store.
Prompts retrieve the current workout plus recent matching exercises, within a
bounded context budget; they do not send every historical frame to Qwen.

Completion screens and Coach > Workout overview show each exercise's count,
repeated detector cues and tracking coverage. Coverage is accepted pose samples
out of received pose results, not model confidence or a form accuracy percentage.
No cues is not proof of perfect form. Historical data can only start accumulating
from this update; unavailable earlier measurements cannot be reconstructed.
Forget removes workout history and chat. Clear removes chat only.

## AR JSON round trip

ScanActivity publishes a versioned JSON snapshot about every 3 seconds, plus
stability transitions. It contains up to six measured horizontal zones, dimensions
in metres, polygon area, relative height, rating and the selected stable floor.
It excludes camera images, point clouds and reusable world-space transforms.

Voice > Room advice or Coach > Analyze room asks Qwen to return exactly:

```json
{"zoneId":"zone-1","exercise":"SQUAT","reason":"A short explanation"}
```

The app validates this against the saved scene, checks freshness (5 minutes),
recomputes the space rating from measured dimensions, and restricts choices to
known floor/standing demos. Raised-support and hanging exercises are excluded
from this initial proposal path because a floor snapshot cannot certify a support.
The UI offers **Preview proposal in AR** after a valid answer. On activation the
app requires the same active room-map revision; the renderer rechecks the actual
plane's tracking, subsumption and measured size before selecting its anchor.
A paused plane waits for tracking. Lost/expired maps require a fresh scan.

The existing rig and motion clips render the approved exercise. Qwen does not
create meshes, edit animation data, invent support coordinates or certify that a
space is obstacle-free. This uses ARCore trackable/anchor semantics:
https://developers.google.com/ar/reference/java/com/google/ar/core/Plane
https://developers.google.com/ar/develop/anchors

Room JSON, chat and workout summaries are excluded from cloud/device backups.
**Model setup > Forget saved room** removes the saved scene and chat. Processing
is local after models are installed. The room analyzer receives structured
measurements, not live vision.

## Validation

Unit tests cover duplicate gestures, snapshot round trips, stale/unknown/unstable
AR proposals, unauthorized fields, incompatible exercises and falsified ratings.
An isolated on-device test saves multiple workout steps, reloads chat, asks the
actual model for a saved count, and validates a model-generated proposal against
a synthetic scene. Synthetic scene tests do not establish real-room safety or
replace an on-device scan/placement check.

Latest checks: 93 app/roomscan unit tests passed. On the connected iQOO the
context test correctly answered ?You did six? from isolated saved pushup data
and returned a schema-valid zone-1/SQUAT proposal from synthetic room JSON.
Offline speech start/stop and TTS completion also passed. The mic palette was
visually inspected on-device. A fresh physical floor scan and approved placement
still require real-room verification; no recorded room snapshot was available
during automated validation.
