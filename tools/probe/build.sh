#!/bin/sh
set -eu
TASK_SDK=${ANDROID_SDK_ROOT:-/Users/hongmw/development/android-sdk}
TASK_BUILD_TOOLS="$TASK_SDK/build-tools/36.0.0"
TASK_SRC=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TASK_OUT=$(mktemp -d /tmp/xiaomi-probe-build.XXXXXX)
mkdir -p "$TASK_OUT/classes" "$TASK_OUT/dex"
javac --release 8 -encoding UTF-8 -classpath "$TASK_SDK/platforms/android-36/android.jar" \
    -d "$TASK_OUT/classes" "$TASK_SRC/ProbeActivity.java"
jar cf "$TASK_OUT/classes.jar" -C "$TASK_OUT/classes" .
"$TASK_BUILD_TOOLS/d8" --min-api 33 --lib "$TASK_SDK/platforms/android-36/android.jar" \
    --output "$TASK_OUT/dex" "$TASK_OUT/classes.jar"
"$TASK_BUILD_TOOLS/aapt2" link -I "$TASK_SDK/platforms/android-36/android.jar" \
    --manifest "$TASK_SRC/AndroidManifest.xml" -o "$TASK_OUT/probe-unsigned.apk"
(cd "$TASK_OUT/dex" && zip -q "$TASK_OUT/probe-unsigned.apk" classes.dex)
TASK_KEYSTORE=${PROBE_KEYSTORE:-"$TASK_OUT/probe.keystore"}
if [ ! -f "$TASK_KEYSTORE" ]; then
    keytool -genkeypair -keystore "$TASK_KEYSTORE" -storepass android -keypass android \
        -alias probe -keyalg RSA -keysize 2048 -validity 30 -dname "CN=Local Read Only Probe" >/dev/null 2>&1
fi
"$TASK_BUILD_TOOLS/zipalign" -f 4 "$TASK_OUT/probe-unsigned.apk" "$TASK_OUT/probe-aligned.apk"
"$TASK_BUILD_TOOLS/apksigner" sign --ks "$TASK_KEYSTORE" --ks-pass pass:android \
    --out "$TASK_OUT/probe.apk" "$TASK_OUT/probe-aligned.apk"
"$TASK_BUILD_TOOLS/apksigner" verify "$TASK_OUT/probe.apk"
printf '%s\n' "$TASK_OUT/probe.apk"
