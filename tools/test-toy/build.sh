#!/bin/sh
# Builds tools/test-toy into test-toy.apk with the SDK build-tools (no Gradle, no ROM build).
#   sh tools/test-toy/build.sh [out.apk]
set -e
here=$(cd "$(dirname "$0")" && pwd)
og=$(cd "$here/../.." && pwd)
bt=$(ls -d "$og"/../tools-ext/build-tools/*/ | head -1)
jar=${ANDROID_JAR:-/home/hasan/android/crdroid/prebuilts/sdk/36/public/android.jar}
aidl=${AIDL:-/home/hasan/android/crdroid/out/host/linux-x86/bin/aidl}
out=${1:-$here/test-toy.apk}
w=$(mktemp -d)
trap 'rm -rf "$w"' EXIT

mkdir -p "$w/gen" "$w/cls" "$w/res"
"$aidl" --lang=java -I"$og/compat/aidl" -o "$w/gen" \
    "$og/compat/aidl/com/nothing/thirdparty/IGlyphService.aidl"
"$bt/aapt2" compile --dir "$here/res" -o "$w/res.zip"
"$bt/aapt2" link -I "$jar" --manifest "$here/AndroidManifest.xml" "$w/res.zip" \
    --java "$w/gen" -o "$w/unsigned.apk"
find "$here/src" "$w/gen" -name '*.java' | sed 's/.*/"&"/' > "$w/srcs.txt"
javac --release 17 -nowarn -cp "$jar" -d "$w/cls" @"$w/srcs.txt"
"$bt/d8" --min-api 33 --lib "$jar" --output "$w" $(find "$w/cls" -name '*.class')
(cd "$w" && zip -q unsigned.apk classes.dex)
"$bt/zipalign" -f 4 "$w/unsigned.apk" "$w/aligned.apk"
ks="$here/debug.keystore"
[ -f "$ks" ] || keytool -genkeypair -keystore "$ks" -storepass android -keypass android \
    -alias test -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=OpenGlyph test toy" >/dev/null 2>&1
"$bt/apksigner" sign --ks "$ks" --ks-pass pass:android --out "$out" "$w/aligned.apk"
echo "$out"
