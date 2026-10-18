#!/bin/sh
# Builds the OpenGlyph core into a dex and runs tools/device-demo/DeviceDemo on the phone as
# root (app_process), against the real matrix-leds driver.
#
#   tools/device-demo.sh [pulse | charge <pct> | call <s> | smiley <s> | priority | all] [arg] [brightness 0..255]
#
# Env: ADB (default adb), D8 (default: d8 from tools-ext/build-tools next to this project).
set -e
here=$(cd "$(dirname "$0")/.." && pwd)
adb=${ADB:-adb}
d8=${D8:-$(ls -d "$here"/../tools-ext/build-tools/*/d8 2>/dev/null | head -1)}
[ -x "$d8" ] || { echo "d8 not found, set D8=" >&2; exit 1; }

out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
javac --release 17 -d "$out/classes" \
    "$here"/src/org/openglyph/core/*.java \
    "$here"/src/org/openglyph/effects/*.java \
    "$here"/tools/device-demo/org/openglyph/tools/*.java
"$d8" --min-api 35 --output "$out" $(find "$out/classes" -name '*.class')
"$adb" push "$out/classes.dex" /data/local/tmp/openglyph-demo.dex >/dev/null

"$adb" shell "su -c 'CLASSPATH=/data/local/tmp/openglyph-demo.dex app_process /system/bin org.openglyph.tools.DeviceDemo $*'"
