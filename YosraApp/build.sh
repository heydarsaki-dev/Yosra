#!/data/data/com.termux/files/usr/bin/bash
set -e

PROJ="$(cd "$(dirname "$0")" && pwd)"
BUILD="$PROJ/build"
ANDROID_JAR="$HOME/android/android-34/android.jar"
KOTLINC="$HOME/opt/kotlinc/bin/kotlinc"

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/dex"

echo "==> 1/5 کامپایل منابع (aapt2)"
aapt2 compile --dir "$PROJ/res" -o "$BUILD/res.zip"
aapt2 link \
    -o "$BUILD/base.apk" \
    -I "$ANDROID_JAR" \
    -A "$PROJ/assets" \
    --manifest "$PROJ/AndroidManifest.xml" \
    --min-sdk-version 24 \
    --target-sdk-version 29 \
    --version-code 43 \
    --version-name "1.0.0" \
    --java "$BUILD/gen" \
    "$BUILD/res.zip"

echo "==> 2/5 کامپایل Kotlin"
RJAVA=$(find "$BUILD/gen" -name "R.java")
KTFILES=$(find "$PROJ/src" -name "*.kt")
bash "$KOTLINC" \
    -J-Xmx1200m \
    -J-Djava.awt.headless=true \
    -classpath "$ANDROID_JAR" \
    -jvm-target 1.8 \
    -d "$BUILD/classes.jar" \
    $KTFILES "$RJAVA"

echo "==> 3/5 تبدیل به DEX (d8)"
d8 --release \
    --min-api 24 \
    --lib "$ANDROID_JAR" \
    --output "$BUILD/dex" \
    "$BUILD/classes.jar" \
    "$HOME/opt/kotlinc/lib/kotlin-stdlib.jar" \
    "$HOME/opt/kotlinc/lib/annotations-13.0.jar"

echo "==> 4/5 بسته‌بندی APK"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
(cd "$BUILD/dex" && zip -q -j "$BUILD/unsigned.apk" classes.dex)

echo "==> 5/5 امضای APK"
KS="$PROJ/debug.keystore"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -alias yosra \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass yosra12345 -keypass yosra12345 \
    -dname "CN=Yosra, O=Yosra, C=IR"

apksigner sign \
    --ks "$KS" \
    --ks-pass pass:yosra12345 \
    --key-pass pass:yosra12345 \
    --out "$PROJ/Yosra-v1.0.0.apk" \
    "$BUILD/unsigned.apk"

apksigner verify --print-certs "$PROJ/Yosra-v1.0.0.apk" | head -3
echo ""
echo "✅ APK آماده شد: $PROJ/Yosra-v1.0.0.apk"
ls -lh "$PROJ/Yosra-v1.0.0.apk"
