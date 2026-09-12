# Calico

Offline calisthenics coach for Android. Scans your room, turns furniture into a workout, counts reps with on-device pose tracking.

## Run
Open in Android Studio and run on a device, or:

```
./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Bench clips
On the workout screen tap **REC**, do the reps, tap **STOP**, enter the true count. The clip is saved on the phone as `EXERCISE-live<counted>_<true>.mp4`. Then:

```
./bench/pull.sh   # copy clips from the phone into bench/
./bench/run.sh    # replay every clip through the counter, compare
```

## Workout mode
The Compose home screen shows the plan and journey. Start runs a routine with warm-ups, rest periods and a completion screen. `WorkoutActivity` also accepts `exercise` for an open-ended session.

## AR preview
Every exercise row in the home plan has a **View workout** action. The Scan tab opens room scanning. It opens `PreviewActivity`,
which stands a life-size rigged figure on a detected floor plane and loops the movement, so
you can walk around the rep before trying it. Tap the floor to move the figure. Without
ARCore the same figure is shown on a plain turntable instead of a dead screen.

The default figure is Quaternius's Animated Base Character, a gold-and-purple
mannequin with a 53-joint skeleton. Cesium Man remains the asset-loading fallback.
Model attribution and license links are available under **Model credits** in preview.
The figure is a skinned glTF humanoid posed at runtime, not a baked animation:

- **Clips** (`roomscan/src/main/assets/clips`) hold MediaPipe pose landmarks, one frame at a
  time, in exactly the form `PoseLandmarkerResult.worldLandmarks()` returns. Keeping the
  animation in landmark space rather than in bone rotations is what lets one clip drive any
  rigged humanoid, and it means footage captured by the app is the same kind of file.
- **`HumanRig`** maps those landmarks onto bones by name, covering Blender metarig, Mixamo
  and Khronos sample naming.
- **`PoseRetargeter`** measures where each bone points in the model's own rest pose and
  rotates it onto the direction the landmarks ask for, so no model is assumed to share
  MediaPipe's axes or bind pose.
- **`SkinnedFigure`** draws it with GPU skinning. The joint palette uses the GPU uniform budget, retaining articulated fingers on capable devices and folding excess joints into ancestors on smaller GPUs.

### Regenerating the clips

```
node tools/gen_clips.mjs     # writes one clip per exercise from joint-angle keyframes
node tools/check_clips.mjs   # asserts each clip crosses the thresholds RepCounter uses
```

`check_clips.mjs` reads the thresholds straight out of `RepCounter.kt` and verifies that
the generated landmark angles cross those thresholds. This does not validate exercise
form or guarantee that camera tracking will count the rendered movement.

The `clips/` files are authored demonstrations. The reviewed squat in `recorded-clips/` comes from the same benchmark video and model used to evaluate counting. It stores both world motion and normalized counter inputs; a regression test runs that file through the live counting rules. `MotionAssets` selects the recorded clip for both mannequin and skeleton. Other exercises retain authored fallbacks until suitable footage is available. See [motion import instructions](docs/AR.md#upstream-ui-and-shared-benchmark-motion-integration). The room scanner estimates planes; it does not create a human
mesh or train the rig. Both scanning (a squat loop) and preview now use `DemoFigure` to
load the same humanoid and retarget the clips at runtime.

### Scan and placement behavior

All tracked horizontal and vertical planes appear as grids immediately, including
small patches. Workout recommendations separately consider the lowest upward
surfaces below the camera and require one second of stable tracking. On supported
phones, confident Raw Depth samples also produce blue horizontal surface estimates
at 4 Hz while plane detection catches up. Estimates do not unlock workout clearance.

`RoomSession` preserves the map and selected anchor across scan, picker and preview.
Only the foreground AR screen owns the camera; native resources are released after
30 seconds in the background. Maps do not survive process death. Preview reuses the
scan anchor or tests several screen positions for a visible upward plane. A separate
3D inset keeps the exercise visible during tracking loss, pending placement or when
the anchor is offscreen. If model loading fails, a pose skeleton plays the selected
exercise. Placement uses real planes, not invented floor geometry.

Mapped area is not obstacle clearance: tabletops may still be floor candidates, and
depth hulls can bridge gaps. Furniture segmentation and depth occlusion are not
implemented. Research, architecture and verification notes: [docs/AR.md](docs/AR.md).

Device checks: scan in good/low light, aim at a ceiling and a table before the floor,
interrupt tracking, resume the app, place and move a demo, and walk around it. Repeat
preview on devices with and without Depth support. JVM tests do not verify camera or
GPU behavior.

### Using a different model

See `roomscan/src/main/assets/models/NOTICE.md`.
