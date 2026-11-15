#!/bin/bash
# Compila l'app Android senza Gradle, solo con gli strumenti dell'Android SDK.
# Risultato: build/USBTrackpad.apk
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/36.1.0"
JAR="$SDK/platforms/android-36/android.jar"
export JAVA_HOME="${JAVA_HOME:-$HOME/Library/Java/JavaVirtualMachines/jdk-21.0.13+9/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

SRC="$DIR/android"
OUT="$DIR/build/android"
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes"

echo "🎨 Risorse…"
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" --manifest "$SRC/AndroidManifest.xml" \
  --java "$OUT/gen" --min-sdk-version 21 --target-sdk-version 36 \
  --version-code 1 --version-name 1.0 "$OUT/res.zip"

echo "☕ Java…"
javac --release 8 -classpath "$JAR" -Xlint:-options -encoding UTF-8 \
  -d "$OUT/classes" $(find "$SRC/src" "$OUT/gen" -name '*.java')

echo "📦 Dex…"
"$BT/d8" --min-api 21 --release --lib "$JAR" --output "$OUT" $(find "$OUT/classes" -name '*.class')

cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT" && zip -q unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# firma con la chiave di debug standard di Android (la crea se manca)
KS="$HOME/.android/debug.keystore"
if [ ! -f "$KS" ]; then
  mkdir -p "$HOME/.android"
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$DIR/build/USBTrackpad.apk" "$OUT/aligned.apk"
echo "✅ build/USBTrackpad.apk"
