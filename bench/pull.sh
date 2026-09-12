#!/usr/bin/env bash
# Pulls clips recorded with the in-app REC button into bench/ (skips ones already here).
cd "$(dirname "$0")"
PKG=com.hackathon.calico
for f in $(adb shell run-as $PKG ls files | tr -d '\r' | grep '\.mp4$' | grep -v '^rec_'); do
  [ -f "$f" ] && continue
  adb exec-out run-as $PKG cat "files/$f" > "$f" && echo "pulled $f"
done
