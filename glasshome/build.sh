#!/usr/bin/env bash
# Build Glass Home without Gradle: aapt2, javac, d8, zipalign and apksigner from the Android SDK,
# nothing downloaded. Needs a JDK (17 works) and any build-tools and platform. Output: glasshome/build/glasshome.apk
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
[ -d "$SDK/build-tools" ] || SDK=/opt/homebrew/share/android-commandlinetools
[ -d "$SDK" ] || SDK="$HOME/Android/Sdk"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
JAR="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar"
[ -x "$BT/aapt2" ] && [ -f "$JAR" ] || { echo "no Android SDK build-tools/platform under $SDK"; exit 1; }

rm -rf build && mkdir -p build/gen build/classes build/dex
"$BT/aapt2" compile --dir res -o build/res.zip
# minSdk 22 is Fire OS 5 (Android 5.1), the oldest Fire TV that still takes ADB from a computer
"$BT/aapt2" link -o build/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml --java build/gen \
  --min-sdk-version 22 --target-sdk-version 25 --version-code 1 --version-name 1.0 build/res.zip
# -parameters: JDK 21+ writes parameter metadata with no names for inner-class constructors, which this d8 crashes on
javac -nowarn --release 8 -parameters -classpath "$JAR" -d build/classes $(find src build/gen -name '*.java')
"$BT/d8" --min-api 22 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

KS="$HOME/.android/debug.keystore"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -alias androiddebugkey -keypass android \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out build/glasshome.apk build/aligned.apk
echo "built $(pwd)/build/glasshome.apk ($(wc -c < build/glasshome.apk | tr -d ' ') bytes)"
