#!/usr/bin/env bash
# Build signed release artifacts for shengqiao.
# Reads signing passwords from SHENGQIAO_STORE_PASSWORD / SHENGQIAO_KEY_PASSWORD
# env vars or ~/.gradle/gradle.properties; the keystore is in keystore/release.keystore.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
export ANDROID_HOME="${ANDROID_HOME:-/Users/hongmw/development/android-sdk}"
GRADLE="$HOME/.gradle/wrapper/dists/gradle-9.3.1-all/9ot9r568e8zfvvd4mn8rbu1j0/gradle-9.3.1/bin/gradle"
APKSIGNER="$ANDROID_HOME/build-tools/36.0.0/apksigner"
if [[ ! -f keystore/release.keystore ]]; then
  echo "Missing keystore/release.keystore. Run tools/init-keystore.sh first." >&2
  exit 1
fi
"$GRADLE" :app:assembleRelease :app:bundleRelease
OUT=app/build/outputs
APK="$OUT/apk/release/app-release.apk"
AAB="$OUT/bundle/release/app-release.aab"
"$APKSIGNER" verify --verbose "$APK" | head -8
echo "APK SHA-256: $(shasum -a 256 "$APK" | awk '{print $1}')"
echo "AAB SHA-256: $(shasum -a 256 "$AAB" | awk '{print $1}')"
echo "Outputs in $OUT/{apk,bundle}/release/"
