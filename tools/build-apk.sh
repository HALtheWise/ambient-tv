#!/usr/bin/env bash
# Build an app directory (AndroidManifest.xml, res/, src/, optional assets/) into build/<name>.apk
# with only the SDK command-line tools (no Gradle).
#   tools/build-apk.sh launcher tvhome
set -euo pipefail
cd "$(dirname "$0")/.."
DIR=$1
NAME=$2
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-34/android.jar
OUT=build/$NAME
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen"

ASSETS=()
[ -d "$DIR/assets" ] && ASSETS=(-A "$DIR/assets")
"$BT/aapt2" compile --dir "$DIR/res" -o "$OUT/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest "$DIR/AndroidManifest.xml" --java "$OUT/gen" "${ASSETS[@]}" \
    -o "$OUT/unsigned.apk" "$OUT/res.zip"
javac -source 11 -target 11 -Xlint:-options -classpath "$JAR" -d "$OUT/classes" \
    $(find "$DIR/src" "$OUT/gen" -name '*.java')
"$BT/d8" --min-api 31 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# Both apps must carry the key already on the TV: Android refuses an update signed with another,
# and bot.tvowner cannot be reinstalled without losing device ownership. The key is not in git;
# get build/debug.keystore from whoever set the TV up. NEW_KEY=1 makes one, for a fresh TV only.
KS=build/debug.keystore
if [ ! -f "$KS" ]; then
    if [ "${NEW_KEY:-}" != 1 ]; then
        echo "No $KS. Copy the TV's key there (see README, Changing the setup)." >&2
        echo "For a TV that has never had these apps: NEW_KEY=1 $0 $*" >&2
        exit 1
    fi
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias debug \
        -dname "CN=146TV" -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out "build/$NAME.apk" "$OUT/aligned.apk"
echo "built build/$NAME.apk"
