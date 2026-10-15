#!/bin/sh
set -eu
TASK_SDK=${ANDROID_SDK_ROOT:-/Users/hongmw/development/android-sdk}
TASK_SRC=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TASK_OUT=$(mktemp -d /tmp/xiaomi-backup-probe.XXXXXX)
mkdir -p "$TASK_OUT/classes" "$TASK_OUT/dex"
javac --release 8 -encoding UTF-8 -classpath "$TASK_SDK/platforms/android-36/android.jar" \
    -d "$TASK_OUT/classes" "$TASK_SRC/BackupProbe.java"
jar cf "$TASK_OUT/classes.jar" -C "$TASK_OUT/classes" .
"$TASK_SDK/build-tools/36.0.0/d8" --min-api 33 --lib "$TASK_SDK/platforms/android-36/android.jar" \
    --output "$TASK_OUT/dex" "$TASK_OUT/classes.jar"
jar cf "$TASK_OUT/probe.jar" -C "$TASK_OUT/dex" classes.dex
printf '%s\n' "$TASK_OUT/probe.jar"
