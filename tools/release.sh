#!/usr/bin/env bash
# Builds the signed release APK into dist/ with its checksum. Needs keystore.properties (see README); it is never committed.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"; export PATH="$JAVA_HOME/bin:$PATH"
[ -f keystore.properties ] || { echo "keystore.properties is missing: the APK would be unsigned."; exit 1; }
VERSION=$(grep -E 'versionName' app/build.gradle.kts | head -1 | sed -E 's/.*"([^"]+)".*/\1/')
./gradlew :domain:test :app:testDebugUnitTest :app:assembleRelease --console=plain
SDK=$(grep sdk.dir local.properties | cut -d= -f2)
APKSIGNER=$(ls "$SDK"/build-tools/*/apksigner | sort | tail -1)
SRC=app/build/outputs/apk/release/app-release.apk
mkdir -p dist
OUT="dist/MONO-$VERSION.apk"
cp "$SRC" "$OUT"
"$APKSIGNER" verify --print-certs "$OUT" | head -3
(cd dist && shasum -a 256 "MONO-$VERSION.apk" > "MONO-$VERSION.apk.sha256" && cat "MONO-$VERSION.apk.sha256")
echo "Built $OUT"
