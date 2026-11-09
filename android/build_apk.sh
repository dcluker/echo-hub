#!/bin/bash
# Builds the signed Echo Hub launcher APK -> dist/EchoHub.apk
# The APK carries no Cloudflare credentials (devices pair at runtime). Only the signing-keystore
# password is read from the macOS Keychain at build time; it is never written to disk by this script.
# Server-specific values (hosts, LAN address) come from gradle properties / env / android/local.properties.
#   ./build_apk.sh        signed release -> dist/EchoHub.apk (+ dist/EchoHub.apk.json sidecar)
#   ./build_apk.sh test   debug build "Echo Hub Test" (applicationId .test, side-by-side) -> dist/EchoHub-test.apk
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(pwd)"

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}"
export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"

KS="$ROOT/keystore/echohub.jks"
# Keychain item name: env, else android/local.properties, else a generic default.
KS_SERVICE="${ECHOHUB_KS_KEYCHAIN_SERVICE:-$(sed -n 's/^ECHOHUB_KS_KEYCHAIN_SERVICE=//p' "$ROOT/local.properties" 2>/dev/null | head -1)}"
KS_SERVICE="${KS_SERVICE:-echohub-apk-keystore}"
mkdir -p "$ROOT/keystore" "$ROOT/dist"

if [ ! -f "$KS" ]; then
  echo "[build_apk] creating release keystore"
  NEWPW="$(openssl rand -base64 24 | tr -d '/+=\n' | cut -c1-24)"
  security add-generic-password -a echohub -s "$KS_SERVICE" -U -w "$NEWPW"
  keytool -genkeypair -v -keystore "$KS" -alias echohub -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$NEWPW" -keypass "$NEWPW" -dname "CN=EchoHub, O=EchoHub, C=US" >/dev/null 2>&1
  chmod 600 "$KS"
fi

MYFLIX_KEYSTORE_PASSWORD="$(security find-generic-password -s "$KS_SERVICE" -w)"
export MYFLIX_KEYSTORE_PASSWORD
export MYFLIX_KEYSTORE_PATH="$KS"

if [ "${1:-}" = "test" ]; then
  ./gradlew --no-daemon assembleDebug
  cp app/build/outputs/apk/debug/app-debug.apk "$ROOT/dist/EchoHub-test.apk"
echo "[build_apk] wrote $ROOT/dist/EchoHub-test.apk"
  exit 0
fi

./gradlew --no-daemon assembleRelease

cp app/build/outputs/apk/release/app-release.apk "$ROOT/dist/EchoHub.apk"
# Sidecar read by the landing server alongside the APK.
VC="$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)"
VN="$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)"
MS="$(sed -n 's/.*minSdk = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)"
printf '{"versionCode":%s,"versionName":"%s","minSdk":%s}\n' "$VC" "$VN" "$MS" > "$ROOT/dist/EchoHub.apk.json"
echo "[build_apk] wrote $ROOT/dist/EchoHub.apk ($(du -h "$ROOT/dist/EchoHub.apk" | cut -f1))"
