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
`WorkoutActivity` takes an intent extra `exercise` = `PUSHUP` | `SQUAT`. Tap the label to switch.
