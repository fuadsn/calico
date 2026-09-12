#!/usr/bin/env bash
# Runs every bench/*.mp4 through the app on the connected phone and compares rep counts.
# Name clips  <EXERCISE>-<variant>_<expected reps or NA>.mp4   e.g. PUSHUP-side_10.mp4  HIGH_KNEES-front_NA.mp4
set -e
cd "$(dirname "$0")"
PKG=com.hackathon.calico
printf "%-20s %8s %8s %6s\n" clip expected got cues
for f in *.mp4; do
  name=${f%.mp4}; exercise=${name%%-*}; expected=${name##*_}
  adb push -q "$f" /data/local/tmp/$f
  adb shell run-as $PKG mkdir -p files
  adb shell run-as $PKG cp /data/local/tmp/$f files/
  adb logcat -c
  adb shell am force-stop $PKG
  adb shell am start -W -n $PKG/.WorkoutActivity --es exercise "$exercise" --es video "$f" >/dev/null
  for _ in $(seq 1 400); do
    line=$(adb logcat -d -s CALICO_BENCH | grep -m1 "exercise=" || true)
    [ -n "$line" ] && break
    sleep 1
  done
  got=$(echo "$line" | sed -n 's/.*reps=\([0-9]*\).*/\1/p'); cues=$(echo "$line" | sed -n 's/.*cues=\([0-9]*\).*/\1/p')
  case $expected in *[!0-9]*) mark="?";; *) mark=$([ "$got" = "$expected" ] && echo ok || echo MISS);; esac
  printf "%-20s %8s %8s %6s  %s\n" "$name" "$expected" "${got:-timeout}" "${cues:--}" "$mark"
done
