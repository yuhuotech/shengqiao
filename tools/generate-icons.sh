#!/bin/sh
set -eu
TASK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
mkdir -p "$TASK_ROOT/app/src/main/res/drawable-nodpi"
sips -z 512 512 "$TASK_ROOT/assets/brand/shengqiao-logo.png" \
    --out "$TASK_ROOT/app/src/main/res/drawable-nodpi/logo_source.png" >/dev/null
for TASK_SPEC in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
    TASK_DENSITY=${TASK_SPEC%:*}
    TASK_SIZE=${TASK_SPEC#*:}
    mkdir -p "$TASK_ROOT/app/src/main/res/mipmap-$TASK_DENSITY"
    sips -z "$TASK_SIZE" "$TASK_SIZE" "$TASK_ROOT/assets/brand/shengqiao-logo.png" \
        --out "$TASK_ROOT/app/src/main/res/mipmap-$TASK_DENSITY/ic_launcher.png" >/dev/null
done
