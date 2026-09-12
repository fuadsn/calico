# Calico

Offline calisthenics coach for Android. Scans your room, turns furniture into a workout, counts reps with on-device pose tracking.

## Run
Open in Android Studio and run on a device, or:

```
./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Workout mode
`WorkoutActivity` takes an intent extra `exercise` = `PUSHUP` | `SQUAT`. Tap the label to switch.
