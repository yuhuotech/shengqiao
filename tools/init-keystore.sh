#!/usr/bin/env bash
# Create a release keystore that lives in the repo (do not commit).
# Passwords are taken from env vars; if absent a placeholder is used and you
# must replace them in ~/.gradle/gradle.properties.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mkdir -p keystore
STORE=keystore/release.keystore
ALIAS="${SHENGQIAO_KEY_ALIAS:-shengqiao}"
STORE_PW="${SHENGQIAO_STORE_PASSWORD:-shengqiao2026}"
KEY_PW="${SHENGQIAO_KEY_PASSWORD:-$STORE_PW}"
keytool -genkeypair -v -keystore "$STORE" -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 9125 \
  -dname "CN=ShengQiao, OU=Apps, O=YuHuo, L=Hangzhou, S=Zhejiang, C=CN" \
  -storepass "$STORE_PW" -keypass "$KEY_PW"
echo "Created $STORE (alias $ALIAS)."
echo "Add to ~/.gradle/gradle.properties:"
echo "SHENGQIAO_STORE_PASSWORD=$STORE_PW"
echo "SHENGQIAO_KEY_ALIAS=$ALIAS"
echo "SHENGQIAO_KEY_PASSWORD=$KEY_PW"
