#!/usr/bin/env bash
#
# Drives the app on a booted emulator and captures what it actually renders.
#
# The renderer is hand-written GL, so "it compiled" says very little. This
# installs the debug APK, plays a few moves, and saves both screenshots and the
# GL-relevant logcat, which is the only way a shader link failure or a wrongly
# wound mesh becomes visible.
set -euo pipefail

# Works for either variant. The release build has no .debug suffix, and it is
# the one that matters most here: it is the only variant R8 has touched.
APK=$(find artifact -name '*.apk' | head -n 1)
PKG=${LUMEN_PKG:-com.junaidshahid.lumen.debug}
ACT=com.junaidshahid.lumen.MainActivity
OUT=shots

mkdir -p "$OUT"

echo "== device =="
adb shell getprop ro.build.version.sdk
adb shell dumpsys SurfaceFlinger | grep -i "GLES" | head -n 3 || true

echo "== install $APK =="
adb install -r "$APK"

adb logcat -c
adb shell am start -W -n "$PKG/$ACT"

shot() {
  sleep "$1"
  adb exec-out screencap -p > "$OUT/$2.png"
  echo "captured $2"
}

# First frame after the GL context is up and the aurora has moved a little.
shot 6 01-fresh-board

# A GL failure tends to produce a blank frame rather than a crash, which no
# logcat grep would catch. A rendered scene compresses to roughly a megabyte;
# a flat black one is a few kilobytes, so file size is a cheap, dependency-free
# proxy for "something was actually drawn".
first_frame=$(stat -c%s "$OUT/01-fresh-board.png")
echo "first frame: $first_frame bytes"
if [ "$first_frame" -lt 100000 ]; then
  echo "!! first frame is suspiciously small - the scene is probably not rendering"
  exit 1
fi

# Four swipes, capturing mid-animation on the first so the slide is visible.
swipe() { adb shell input swipe "$@"; }

swipe 540 1400 540 800 90   # up
sleep 0.12
adb exec-out screencap -p > "$OUT/02-mid-slide.png"
shot 1 03-after-up

swipe 300 1200 850 1200 90  # right
shot 1 04-after-right

swipe 540 800 540 1400 90   # down
shot 1 05-after-down

swipe 850 1200 300 1200 90  # left
shot 1 06-after-left

# A longer run, to build up bigger tiles and exercise the number atlas.
for _ in $(seq 1 14); do
  swipe 540 1400 540 800 60;  sleep 0.25
  swipe 300 1200 850 1200 60; sleep 0.25
  swipe 540 800 540 1400 60;  sleep 0.25
  swipe 850 1200 300 1200 60; sleep 0.25
done
shot 1 07-after-many-moves

# Tap the mode chip, which is the left-most control in the bottom row.
adb shell input tap 230 2180
shot 1 08-zen-mode

echo "== logcat (GL / app / crashes) =="
adb logcat -d > "$OUT/logcat-full.txt"
grep -iE "lumen|opengl|glsurface|shader|libEGL|AndroidRuntime|FATAL|eglCreate" \
  "$OUT/logcat-full.txt" | tail -n 120 | tee "$OUT/logcat-filtered.txt" || true

if grep -qE "FATAL EXCEPTION|AndroidRuntime: .*Exception" "$OUT/logcat-full.txt"; then
  echo "!! app crashed — see logcat artifact"
  exit 1
fi
