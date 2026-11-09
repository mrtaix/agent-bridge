#!/bin/sh
# Gradle-less APK build for the Agent Bridge app: aapt2 + javac + d8 + apksigner.
set -eu
cd "$(dirname "$0")"

ANDROID_JAR=${ANDROID_JAR:-$HOME/android-sdk/platforms/android-35/android.jar}
PKG=com.harness.agentbridge
MIN_SDK=${MIN_SDK:-26}
TARGET_SDK=${TARGET_SDK:-28}
KEYSTORE=${KEYSTORE:-$HOME/.android/debug.keystore}
KS_ALIAS=${KS_ALIAS:-androiddebugkey}
KS_PASS=${KS_PASS:-android}
export KS_PASS

[ -f "$ANDROID_JAR" ] || { echo "missing $ANDROID_JAR"; exit 1; }
[ -s assets/agent-browser ] || sh ./fetch-agent-browser.sh

rm -rf build
mkdir -p build/classes build/dex build/gen

aapt2 link -I "$ANDROID_JAR" --manifest AndroidManifest.xml -A assets \
    --java build/gen --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
    --version-code 1 --version-name 1.0 -o build/base.apk

find src build/gen -name '*.java' > build/sources.txt
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -Xlint:-options \
    -d build/classes @build/sources.txt

jar cf build/classes.jar -C build/classes .
d8 --min-api "$MIN_SDK" --lib "$ANDROID_JAR" --output build/dex build/classes.jar

cp build/base.apk build/unsigned.apk
for dex in build/dex/classes*.dex; do
    jar --update --file build/unsigned.apk -C build/dex "$(basename "$dex")"
done

if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US"
fi

apksigner sign --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" \
    --ks-pass env:KS_PASS --key-pass env:KS_PASS --out "build/$PKG.apk" build/unsigned.apk
apksigner verify "build/$PKG.apk"
ls -la "build/$PKG.apk"
