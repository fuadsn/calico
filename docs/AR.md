# AR implementation and investigation

## Why dots appeared without surfaces or a demo

The old scanner only rendered planes surviving a camera-height and lowest-floor
filter. It hid genuine detected geometry. Feature-point count was also presented as
scan progress although it cannot establish a usable plane. Preview created a new
session, losing the scan map, and refused to render a model until a single ray hit a
filtered floor. None of these failures were covered by the original math-only tests.

## Research and decisions

- [ARCore fundamentals](https://developers.google.com/ar/develop/fundamentals):
  feature points support camera tracking; planes require consistent coplanar features.
  Textureless surfaces can remain undetected. Render every tracked plane independently
  of whether it qualifies as a workout area. Show movement/light guidance instead of
  treating point count as proof of completion.
- [Anchors](https://developers.google.com/ar/develop/anchors) and
  [Session lifecycle](https://developers.google.com/ar/reference/java/com/google/ar/core/Session):
  a local map belongs to its session. Share one session across screens and release the
  camera only after its GL thread pauses. Resume relocalization before using anchors.
  Retain the map during in-app navigation and expire it after 30 seconds backgrounded.
- [Raw Depth guide](https://developers.google.com/ar/develop/java/depth/raw-depth)
  and [Google's Raw Depth codelab](https://codelabs.developers.google.com/codelabs/arcore-rawdepthapi):
  use confidence-filtered samples, unsigned 16-bit millimeters, texture intrinsics and
  camera pose to reconstruct geometry. Raw depth is sparse, and repeated timestamps
  are not new measurements. Use RAW_DEPTH_ONLY when supported; release images with
  `use`, respect image strides and expire cached estimates after 750 ms.
- [Instant Placement](https://developers.google.com/ar/develop/java/instant-placement/developer-guide):
  approximate distance can change apparent scale as tracking converges. It is unsuitable
  as evidence of exercise clearance. This implementation uses a clearly separate 3D
  preview while placement is pending instead of inventing a measured floor.

## Performance and cleanup

Raw depth processing is throttled to 4 Hz and capped at 600 fitted samples. Horizontal
consensus uses up to 32 hypotheses, requiring 16 supporting points, sufficient polygon
area and sample density. It is a visual estimate, not room segmentation. Plane polygons
are measured directly from their buffer; no scratch-array copy is needed. Status text
updates at most four times per second. Joint-palette and placement matrix multiplication
reuse output buffers. There is still allocation in the landmark retargeter; no frame-rate
improvement is claimed without profiling a moving scene.

Removed the unrelated block jumping-jack avatar and replaced it with the same selected
exercise's pose skeleton. Shared session setup, model selection and plane measurement.
Made client-array render passes explicitly unbind vertex buffers and avoid unsupported
wide lines. Added GPU initialization fallback and an offscreen-anchor preview.
Camera texture coordinates are initialized for each renderer even when the retained
session reports unchanged display geometry. Preview framing accounts for narrow panels.

## Verification

`./gradlew :roomscan:testDebugUnitTest :app:assembleDebug` checks clips, rig retargeting,
scan stability, depth consensus and build integration.

`./gradlew :roomscan:connectedDebugAndroidTest` uses a real GLES2 context to render
the bundled skinned squat, push-up and jumping-jack models, their skeleton fallbacks,
and a surface grid. It checks for GL errors and a meaningful number of visible pixels.
This test passed on the connected I2501 running Android 16 on 2026-09-12.

The final build passed 44 JVM tests, the GPU test and all 16 clip checks. Android lint
reported no errors and 15 warnings (dependency updates, layout/style, plural strings
and touch accessibility). Device screenshots confirmed visible animated preview geometry
with tracking paused. Logs confirmed one room session created for ScanActivity and reused
by PreviewActivity, with RAW_DEPTH_ONLY supported. During this check ARCore reported
insufficient light, so real-room plane accuracy and a populated-map handoff were not
verified. The debug APK was installed on the device.

Live validation still requires a lit room and physical movement: map floor/walls,
switch scan -> picker -> preview -> back, interrupt tracking, place/move the model,
background/resume, and check devices without Depth support. GPU pixel checks do not
prove real-world plane accuracy, map relocalization quality or correct exercise form.


## Hand/foot articulation and recorded motion (2026-09-12)

The Quaternius renderer now uses two-axis palm and sole frames. Pinky/index
landmarks control palm roll; heel/ankle/toe landmarks control the foot plane.
The GPU palette uses the actual vertex-uniform budget, preserving all 53 skin
joints on capable devices. The GLES2 fallback still collapses excess fingers.
Finger curl is a procedural, exercise-dependent grip (open support palms,
relaxed hands, pull-up grip); it is not measured finger motion. The 33-point
Pose Landmarker does not contain knuckles: detailed capture would require a
separate hand tracker. See Google's [pose landmark guide](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker)
and [hand landmarks](https://ai.google.dev/edge/api/mediapipe/python/mp/tasks/vision/drawing_styles/hand_landmarker/HandLandmark).

Generated demos now contain 60 samples per cycle, wrist flexion/roll, grounded
sole orientation for standing exercises, and ankle motion for lifted feet.
These are explicitly marked `procedural_fk_v2`. They remain authored demos,
not motion capture, biomechanical reference data, or learned animations.
Foot orientation is constrained; full positional contact IK is not implemented.

There are no training videos in this checkout's bench directory. Rep counting
uses `pose_landmarker_lite.task` and angle thresholds in RepCounter.kt, not a
local exercise training dataset. To capture an available benchmark recording
with the exact same detector/results that feed the counter, pass the explicit
`export_pose` flag (live camera capture is not enabled):

```sh
adb push SQUAT-side_1.mp4 /data/local/tmp/SQUAT-side_1.mp4
adb shell run-as com.hackathon.calico mkdir -p files
adb shell run-as com.hackathon.calico cp /data/local/tmp/SQUAT-side_1.mp4 files/
adb shell am start -n com.hackathon.calico/.WorkoutActivity --es exercise SQUAT --es video SQUAT-side_1.mp4 --ez export_pose true
adb logcat -d -s CALICO_POSE CALICO_BENCH
```

After an `Exported SQUAT` log, extract the file as UTF-8 JSON:

```sh
adb exec-out run-as com.hackathon.calico cat files/pose-exports/SQUAT.json > captured-SQUAT.json
```

The redirection example assumes bash/PowerShell 7 (Windows PowerShell 5 may
re-encode output). The exporter preserves source filename and source start/end
times, takes the longest continuously visible segment (at most 60 seconds),
and resamples to 30fps. Missing/low-confidence detections split segments rather
than inventing movement across gaps. Captures are non-looping until reviewed
and trimmed to a complete repetition; copying a reviewed JSON to
`roomscan/src/main/assets/clips/SQUAT.json` and rebuilding uses it in both rig
and skeleton demos. Regenerating all procedural clips overwrites that asset,
so retain the captured original separately. No real recording has yet been
imported or validated end-to-end because the source footage is missing.

Validation: 53 roomscan JVM tests and 9 app JVM tests pass; all 16 procedural
clips retain the counter's required angle range. These angle-range checks do
not prove camera counting accuracy. The actual connected I2501 GLES driver
also passes the rendering smoke test. Render captures are under the ignored
`build/articulation-render` directory.


## Upstream UI and shared benchmark motion integration

Merged origin/main through 9fb92ad, retaining its Compose lavender/lime home,
journey, routine, recording UI, per-side counting, and incline-pushup progression.
The home screen is now the launcher. Scan opens the actual roomscan activity;
each plan row has a View workout action. The earlier ListView picker is removed.

The pull contains code and counting configuration, not video files. Two existing
videos were found next to the checkout and copied into the ignored bench directory:

- `D:/iqoo/vids/video_20260912_141749.mp4` -> `bench/SQUAT-front_NA.mp4`.
- `D:/iqoo/vids/video_20260912_141518.mp4` -> `bench/PUSHUP-front_NA.mp4`.

`tools/extract_motion.py` runs those benchmark videos through the exact
`pose_landmarker_lite.task` bytes used by the app. Python runtime 0.10.31 uses CPU;
the app's Android runtime 0.10.35 uses GPU, so numeric results need not be bit-identical.
Extraction follows the official [MediaPipe video API](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker/python).
The trace retains world positions, normalized counter inputs, confidence, source
video SHA-256, model SHA-256, and timestamps. No detector retraining was performed.

A fully visible squat repetition from 7861 to 10392ms was imported with
`tools/import_motion.py` into `recorded-clips/SQUAT.json`. Its 38 frames retain the
original normalized inputs and confidence; world positions use a circular
three-sample mean to reduce jitter. Both renderers load this shared recorded
asset before considering a procedural fallback. The app's RecordedMotionTest
feeds the normalized samples from this very file through PoseAngles and
RepCounter; live onPose now uses that same selection/orientation function.

The pushup video had no frames where all required body and extremity landmarks
met 0.5 visibility. It remains a benchmark input, but was not promoted into an
unreliable demo. Other exercises still use authored motion; there is no source
footage for them here. All 17 authored clips, including INCLINE_PUSHUP, are
checked against landmark indices and thresholds read directly from RepCounter.kt.
The generator writes only clips/, so it cannot overwrite reviewed recorded-clips/.

Reproduce the recorded squat:

```sh
py tools/extract_motion.py bench/SQUAT-front_NA.mp4 SQUAT --end 15
py tools/import_motion.py build/video-review/SQUAT-pose.json --start 7861 --end 10392
```

The pre-pull local work is retained in the Git stash named
`Before upstream UI and shared motion integration` as a recovery copy.

Merged-build validation: 53 roomscan tests and 14 app tests pass (67 total),
including three counted repetitions from the recorded squat asset. The merged
APK was installed successfully on I2501 and the Compose home screen was inspected.
The phone then disconnected before the updated GPU smoke test could run; the
recorded-squat GPU visual check remains pending.


## Contact constraints, motion quality, and floor alignment

`ContactRig` adds avatar-local support constraints to Quaternius. Squats and
standing upper-body exercises hold both soles; high knees alternate support;
pushups/pike pushups hold palms and toe contact points, and mountain climbers
release the lifted foot. Other exercises retain their existing posing. These
are body IK targets under one room anchor, not separate drifting ARCore anchors.
Contact positions are calibrated against skinned mesh surfaces. Root translation
follows the supporting limbs, then analytic two-bone IK preserves limb lengths
and corrects contact error. Prone support legs also maintain near-full extension.
This is kinematic contact, not a full-body physics simulation or collision solver.

High-knees authored wrist roll now keeps the palms facing inward in a neutral
running grip. Tests check lateral palm planes throughout the cycle, contact
residuals below 5 mm, unchanged bone lengths, pelvis movement, and nearly straight
pushup knees. The lightweight skeleton and Cesium fallback retain their previous
posing; the contact solver targets the selected Quaternius rig.

The data audit found that the squat clip's estimated arm lengths varied by over
60 percent despite high visibility. Visibility alone did not establish reliable
3D motion. `import_motion.py` now verifies source video/model hashes, timing,
visibility, finite coordinates and limb-length variation. Excessive leg variation
rejects a clip. Unreliable arms reject it unless `--arm-fallback` is explicitly
selected. The reviewed squat retains recorded legs and torso, uses authored arms
relative to the recorded shoulders, and preserves original `sourceWorldFrames`,
normalized counter inputs and quality metadata. Reproduce it with:

```sh
py tools/import_motion.py build/video-review/SQUAT-pose.json --start 7861 --end 10392 --arm-fallback
```

No claim of biomechanical ground truth or model retraining is made. Additional
whole-body recordings with reliable 3D arm observations are needed to replace
this explicit fallback. Rep-counter validation still consumes the unchanged
normalized samples from the same source file.

Floor grids now show only stable plausible lowest upward planes, not all walls,
tables, or unconfirmed depth hulls. Candidates require at least 0.25 square metres,
0.55?2.5 m below the camera and agreement with lower depth evidence within 8 cm.
Same-floor patches must be within 4 cm. Users are guided to hold the phone at
waist height; a phone resting on the floor will not establish an automatic floor.
Preview uses the same candidate policy and stability gate. Anchors remain attached
to planes; placement height is projected onto the refined plane, including after
plane subsumption. Tracking loss resets stability. Floor semantics cannot be
proved by ARCore plane geometry alone; elevated surfaces can still be ambiguous
without lower visible evidence. These changes follow ARCore's [anchor guidance](https://developers.google.com/ar/develop/anchors)
and [pose refinement behavior](https://developers.google.com/ar/develop/fundamentals).

Scanner/preview controls now share the main palette, 56dp minimum touch height,
semibold text, and pill-shaped navy/lime primary and white/navy secondary buttons.

Final verification: 70 JVM tests pass; importer rejects the unreliable arms unless
the fallback is explicitly enabled. Android lint reports 0 errors and 9 warnings.
The final GPU smoke test passes on the connected I2501, and updated squat, pushup,
and high-knees renders were inspected. The latest APK is installed. Scanner and
preview pill buttons were checked on the phone; captures are in the ignored
`build/contact-final` directory. Physical floor-height accuracy during a moving
room scan is not established by these software/render checks.


### Detection-feedback regression correction

Detected planes are drawn immediately again, independently of floor recommendation
and the one-second automatic-placement check. Early floor patches (at least 0.04 m?)
can host a preview without being described as enough space for exercise. The camera
can be as low as 15 cm above the plane; depth disagreement tolerates 20 cm and floor
fragments within 8 cm share a level. These are geometric heuristics, not semantic
floor recognition, so a tabletop can still require user repositioning.

Preview no longer detaches a tracked anchor because a transient candidate filter
rejects its plane, nor hides it when a fresh stability check resets. Explicit taps
on eligible detected floor patches bypass the automatic-placement delay. Stopped
planes are still discarded; tracked placements follow the refined plane height.
JVM checks cover low camera positions and early patches. Physical scanning quality
and floor alignment still require a moving-device check in the user's room.


### AR rollback requested by user

Scanning and placement are restored from the pre-floor-alignment snapshot
(`Before upstream UI and shared motion integration`). This supersedes the floor
policy and detection-feedback sections above: original surface/depth visualization,
plane eligibility, preview placement, and anchor-height behavior are back. The
navigation flags and button styling remain. ContactRig hand/foot constraints,
shared motion data, palm corrections, and upstream rep-counter fixes remain.
