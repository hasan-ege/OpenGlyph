#!/bin/sh
# Builds tools/test-notify under a given package name (one APK per fake app).
#   sh tools/test-notify/build.sh com.example.chat out.apk
set -e
here=$(cd "$(dirname "$0")" && pwd)
og=$(cd "$here/../.." && pwd)
bt=$(ls -d "$og"/../tools-ext/build-tools/*/ | head -1)
jar=${ANDROID_JAR:-/home/hasan/android/crdroid/prebuilts/sdk/36/public/android.jar}
pkg=$1
out=${2:-$here/$pkg.apk}
w=$(mktemp -d)
trap 'rm -rf "$w"' EXIT
mkdir -p "$w/cls"
"$bt/aapt2" link -I "$jar" --manifest "$here/AndroidManifest.xml" \
    --rename-manifest-package "$pkg" -o "$w/unsigned.apk"
javac --release 17 -nowarn -cp "$jar" -d "$w/cls" "$here"/src/org/openglyph/testnotify/*.java
"$bt/d8" --min-api 33 --lib "$jar" --output "$w" $(find "$w/cls" -name '*.class')
(cd "$w" && zip -q unsigned.apk classes.dex)
"$bt/zipalign" -f 4 "$w/unsigned.apk" "$w/aligned.apk"
ks="$here/../test-toy/debug.keystore"
"$bt/apksigner" sign --ks "$ks" --ks-pass pass:android --out "$out" "$w/aligned.apk"
echo "$out"
