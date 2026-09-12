#!/usr/bin/env bash
# Runs every bench/*.mp4 through the app on the connected phone and compares rep counts.
# Name clips  <EXERCISE>_<expected reps>.mp4   e.g. PUSHUP_10.mp4  SQUAT_8.mp4
set -e
cd "$(dirname "$0")"
PKG=com.hackathon.calico
printf "%-20s %8s %8s %6s\n" clip expected got cues
for f in *.mp4; do
  name=${f%.mp4}; exercise=${name%_*}; expected=${name##*_}
  adb push -q "$f" /sdcard/Download/$f
  adb logcat -c
  adb shell am force-stop $PKG
  adb shell am start -W -n $PKG/.WorkoutActivity --es exercise "$exercise" --es video /sdcard/Download/$f >/dev/null
  for _ in $(seq 1 120); do
    line=$(adb logcat -d -s CALICO_BENCH | grep -m1 "exercise=" || true)
    [ -n "$line" ] && break
    sleep 1
  done
  got=$(echo "$line" | sed -n 's/.*reps=\([0-9]*\).*/\1/p'); cues=$(echo "$line" | sed -n 's/.*cues=\([0-9]*\).*/\1/p')
  mark=$([ "$got" = "$expected" ] && echo "ok" || echo "MISS")
  printf "%-20s %8s %8s %6s  %s\n" "$name" "$expected" "${got:-timeout}" "${cues:--}" "$mark"
done
