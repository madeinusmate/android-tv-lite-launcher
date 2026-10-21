#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
BT="${ANDROID_BUILD_TOOLS:-$HOME/Library/Android/sdk/build-tools/35.0.1}"
JAR="${ANDROID_JAR:-$HOME/Library/Android/sdk/platforms/android-34/android.jar}"
KEYSTORE="${KEYSTORE:-$ROOT/debug.keystore}"

if [[ ! -f "$ROOT/res/values/tmdb.xml" ]]; then
  echo "Missing res/values/tmdb.xml — copy res/values/tmdb.xml.example and add your TMDB API key." >&2
  exit 1
fi

if [[ ! -f "$KEYSTORE" ]]; then
  KEYSTORE="${HOME}/.android/debug.keystore"
fi

cd "$ROOT"
rm -rf build
mkdir -p build/gen build/classes build/dex

TMDB_EXAMPLE=res/values/tmdb.xml.example
if [[ -f "$TMDB_EXAMPLE" ]]; then
  mv "$TMDB_EXAMPLE" build/tmdb.xml.example
fi

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml --java build/gen \
  -o build/linked.apk --min-sdk-version 28 --target-sdk-version 34 build/res.zip

find src build/gen -name '*.java' > build/sources.txt
javac --release 17 -classpath "$JAR" -d build/classes @build/sources.txt
"$BT/d8" --min-api 28 --lib "$JAR" --output build/dex \
  $(find build/classes -name '*.class')

cp build/linked.apk build/with-dex.apk
(cd build/dex && zip -j -q ../with-dex.apk classes.dex)
"$BT/zipalign" -f -p 4 build/with-dex.apk build/aligned.apk
"$BT/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias androiddebugkey \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out build/lite-home.apk build/aligned.apk

if [[ -f build/tmdb.xml.example ]]; then
  mv build/tmdb.xml.example "$TMDB_EXAMPLE"
fi

echo "Built build/lite-home.apk"
