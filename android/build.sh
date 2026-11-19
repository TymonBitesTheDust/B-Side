#!/usr/bin/env bash
# Builds B-Side.apk without Gradle: aapt2 → ecj → d8 → zipalign → apksigner.
# Needs: TOOLS dir with aapt2, android.jar (API 34), ecj.jar, d8.jar, apksigner.jar; a JDK 17+.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TOOLS="${TOOLS:?set TOOLS to the folder holding aapt2, android.jar, ecj.jar, d8.jar, apksigner.jar}"
OUT="${OUT:-$HERE/build}"
KEYSTORE="${KEYSTORE:-$HERE/bside-release.jks}"
KS_PASS="${KS_PASS:?set KS_PASS to your signing key password (keep it out of the repository)}"
# Optional: TEST_PACKAGE=app.bside.music.test builds a test copy called "B-Side Test" under that package name.
# It installs next to B-Side instead of over it, so it can be tried without touching your library.
TEST_PACKAGE="${TEST_PACKAGE:-}"

rm -rf "$OUT"; mkdir -p "$OUT"/{gen,classes,dex,assets}
cp "$HERE/../web/index.html" "$OUT/assets/index.html"

echo "▸ compiling resources"
"$TOOLS/aapt2" compile --dir "$HERE/res" -o "$OUT/res.zip"
[ -d "$HERE/res-gen" ] || { echo "res-gen missing: run python3 gen_icons.py first"; exit 1; }
"$TOOLS/aapt2" compile --dir "$HERE/res-gen" -o "$OUT/res-gen.zip"
TEST_ARGS=()
if [ -n "$TEST_PACKAGE" ]; then
  echo "▸ test copy: $TEST_PACKAGE"
  mkdir -p "$OUT/res-test/values"
  printf '<?xml version="1.0" encoding="utf-8"?>\n<resources><string name="app_name">B-Side Test</string></resources>\n' \
    > "$OUT/res-test/values/strings.xml"
  "$TOOLS/aapt2" compile --dir "$OUT/res-test" -o "$OUT/res-test.zip"
  # the code keeps its package (app.bside.music); only the installed name changes
  TEST_ARGS=(--rename-manifest-package "$TEST_PACKAGE" --custom-package app.bside.music -R "$OUT/res-test.zip")
fi
"$TOOLS/aapt2" link -o "$OUT/base.apk" -I "$TOOLS/android.jar" \
  --manifest "$HERE/AndroidManifest.xml" -A "$OUT/assets" --java "$OUT/gen" \
  --min-sdk-version 24 --target-sdk-version 34 --version-code 17 --version-name 1.10.0 \
  "$OUT/res.zip" "$OUT/res-gen.zip" ${TEST_ARGS[@]+"${TEST_ARGS[@]}"}

echo "▸ compiling java"
# Eclipse compiler (ecj): JDK 21's javac emits MethodParameters entries that d8 8.2 rejects.
java -jar "$TOOLS/ecj.jar" -8 -nowarn -encoding UTF-8 -bootclasspath "$TOOLS/android.jar" \
  -d "$OUT/classes" $(find "$OUT/gen" "$HERE/src" -name '*.java')

echo "▸ dexing"
java -cp "$TOOLS/d8.jar" com.android.tools.r8.D8 --release --min-api 24 \
  --lib "$TOOLS/android.jar" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "▸ packaging"
cp "$OUT/base.apk" "$OUT/unaligned.apk"
(cd "$OUT/dex" && zip -q -9 "$OUT/unaligned.apk" classes.dex)
python3 "$HERE/zipalign.py" "$OUT/unaligned.apk" "$OUT/aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
  echo "▸ creating signing key $KEYSTORE"
  keytool -genkeypair -keystore "$KEYSTORE" -alias bside -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" -dname "CN=B-Side, O=B-Side" >/dev/null 2>&1
fi

echo "▸ signing"
java -jar "$TOOLS/apksigner.jar" sign --ks "$KEYSTORE" --ks-key-alias bside \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" --out "$OUT/B-Side.apk" "$OUT/aligned.apk"
java -jar "$TOOLS/apksigner.jar" verify --verbose "$OUT/B-Side.apk" | head -8
ls -la "$OUT/B-Side.apk"
