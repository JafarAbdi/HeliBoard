#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
TOOLCHAIN=${PIXEL_VOICE_TOOLCHAIN:-/home/juruc/.local/share/pixel-voice-toolchain}
MODEL=${PIXEL_VOICE_MODEL:-/home/juruc/.cache/huggingface/hub/models--handy-computer--parakeet-unified-en-0.6b-gguf/snapshots/7e948f21b7bdbac698d3318db9d350f1096f3b6c/parakeet-unified-en-0.6b-Q8_0.gguf}
EXPECTED_SIZE=731357568
EXPECTED_SHA=4b50b6dd862bf6e346929aaf4f5eaacec003bfa3f56462d6c874b41ef2f38795
EXPECTED_SIGNER_SHA=b2d584c45179eb72caceabe41379eb4e043284b11a5d3fcad951f52a66a89d1d

export JAVA_HOME="$TOOLCHAIN/jdk17"
export ANDROID_HOME="$TOOLCHAIN/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

PIXEL_VOICE_ABI=arm64-v8a PIXEL_VOICE_BUILD_TYPE=release "$ROOT/scripts/build.sh"

actual_size=$(stat -Lc %s "$MODEL")
actual_sha=$(sha256sum "$MODEL" | awk '{print $1}')
[[ "$actual_size" == "$EXPECTED_SIZE" ]] || { echo "Model size mismatch: $actual_size" >&2; exit 1; }
[[ "$actual_sha" == "$EXPECTED_SHA" ]] || { echo "Model SHA-256 mismatch: $actual_sha" >&2; exit 1; }

"$ROOT/build/host/pixel_voice_smoke" "$MODEL" "$ROOT/third_party/transcribe.cpp/samples/jfk.wav"
"$ROOT/gradlew" --no-daemon --max-workers=2 -Dorg.gradle.parallel=false -p "$ROOT" \
    -PpixelVoiceAbi=arm64-v8a --rerun-tasks :dictation:testReleaseUnitTest
unit_tests=$(awk '/<testsuite / { for (i = 1; i <= NF; i++) if ($i ~ /^tests="/) { gsub(/[^0-9]/, "", $i); count += $i } } END { print count+0 }' \
    "$ROOT"/dictation/build/test-results/testReleaseUnitTest/TEST-*.xml)
[[ "$unit_tests" == 80 ]] || { echo "Expected 80 dictation unit tests, got $unit_tests" >&2; exit 1; }
"$ROOT/gradlew" --no-daemon --max-workers=2 -Dorg.gradle.parallel=false -p "$ROOT" \
    -PpixelVoiceAbi=arm64-v8a :app:lintRelease :dictation:lintRelease

APK="$ROOT/app/build/outputs/pixelVoice/arm64-v8a/pixel-voice-arm64-v8a-release.apk"
AAPT2="$ANDROID_HOME/build-tools/35.0.0/aapt2"
ZIPALIGN="$ANDROID_HOME/build-tools/35.0.0/zipalign"
APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
READELF="$ANDROID_HOME/ndk/28.0.13004108/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
unzip -Z1 "$APK" > "$tmp/entries"

permissions=$("$AAPT2" dump permissions "$APK")
actual_permissions=$(sed -n "s/^uses-permission[^:]*: name='\([^']*\)'.*$/\1/p" <<<"$permissions" | sort)
expected_permissions=$'android.permission.FOREGROUND_SERVICE\nandroid.permission.FOREGROUND_SERVICE_MICROPHONE\nandroid.permission.INTERNET\nandroid.permission.READ_CONTACTS\nandroid.permission.READ_USER_DICTIONARY\nandroid.permission.RECEIVE_BOOT_COMPLETED\nandroid.permission.RECORD_AUDIO\nandroid.permission.VIBRATE\nandroid.permission.WRITE_USER_DICTIONARY\nhelium314.keyboard.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
if [[ "$actual_permissions" != "$expected_permissions" ]]; then
    echo "Unexpected APK permission set:" >&2
    printf '%s\n' "$actual_permissions" >&2
    exit 1
fi

badging=$("$AAPT2" dump badging "$APK")
grep -Fq "package: name='helium314.keyboard' versionCode='4101' versionName='4.1'" <<<"$badging"
grep -Fxq "application-label:'HeliBoard'" <<<"$badging"
if grep -q '^application-debuggable' <<<"$badging"; then
    echo "The production APK must not be debuggable" >&2
    exit 1
fi
"$AAPT2" dump xmltree "$APK" --file AndroidManifest.xml > "$tmp/manifest"
awk '
    /E: / {
        in_permission = ($2 == "permission")
        if (in_permission) ++declarations
    }
    in_permission && /:name\(/ {
        split($0, parts, "\"")
        if (parts[2] == "helium314.keyboard.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") ++names
    }
    in_permission && /:protectionLevel\(/ {
        if ($0 ~ /=0x00000002[[:space:]]*$/) ++signatures
    }
    END { exit !(declarations == 1 && names == 1 && signatures == 1) }
' "$tmp/manifest" || {
    echo "Expected one app-private receiver permission with the app-owned name and signature protection" >&2
    exit 1
}
[[ $(grep -c 'android.permission.BIND_INPUT_METHOD' "$tmp/manifest") == 1 ]]
grep -Fq 'helium314.keyboard.latin.LatinIME' "$tmp/manifest"
if grep -E 'android.permission.BIND_ACCESSIBILITY_SERVICE|android.speech.RecognitionService|DictationAccessibilityService|VoiceInputMethodService' "$tmp/manifest"; then
    echo "Obsolete dictation service or recognition provider in APK" >&2
    exit 1
fi
unzip -q "$APK" 'assets/*' -d "$tmp"
for asset_root in "$ROOT/app/src/main/assets" "$ROOT/dictation/src/main/assets"; do
    while IFS= read -r -d '' source; do
        asset="assets/${source#"$asset_root/"}"
        grep -Fxq "$asset" "$tmp/entries" || { echo "Missing keyboard or notice asset: $asset" >&2; exit 1; }
        cmp "$source" "$tmp/$asset"
    done < <(find "$asset_root" -type f -print0)
done
if grep -Ei '\.(gguf|wav)$' "$tmp/entries"; then
    echo "The production APK must not contain a model or audio fixture" >&2
    exit 1
fi
while IFS= read -r -d '' asset; do
    magic=$(od -An -tx1 -N4 "$asset" | tr -d ' \n')
    if [[ "$magic" == 47475546 ]] || \
            [[ "$magic" == 52494646 && $(od -An -tx1 -j8 -N4 "$asset" | tr -d ' \n') == 57415645 ]]; then
        echo "Model or WAV binary asset in production APK: ${asset#"$tmp/"}" >&2
        exit 1
    fi
done < <(find "$tmp/assets" -type f -print0)

native_libraries=$(grep -E '^lib/.*\.so$' "$tmp/entries" | sort)
[[ "$native_libraries" == $'lib/arm64-v8a/libandroidx.graphics.path.so\nlib/arm64-v8a/libjni_latinime.so\nlib/arm64-v8a/libpixelvoice_jni.so' ]] || {
    echo "Unexpected native libraries: $native_libraries" >&2
    exit 1
}
"$APKSIGNER" verify --print-certs "$APK" > "$tmp/signature"
signer_sha=$(grep 'certificate SHA-256 digest:' "$tmp/signature")
[[ "$signer_sha" == "Signer #1 certificate SHA-256 digest: $EXPECTED_SIGNER_SHA" ]] || {
    echo "Unexpected APK signer certificate SHA-256: $signer_sha" >&2
    exit 1
}
"$ZIPALIGN" -c -P 16 4 "$APK"
for library in libandroidx.graphics.path.so libjni_latinime.so libpixelvoice_jni.so; do
    unzip -p "$APK" "lib/arm64-v8a/$library" > "$tmp/$library"
    dynamic=$("$READELF" -d "$tmp/$library")
    if grep -Fq 'libvulkan.so' <<<"$dynamic"; then
        echo "CPU APK must not link Vulkan: $library" >&2
        exit 1
    fi
    load_alignments=$("$READELF" -lW "$tmp/$library" | awk '$1 == "LOAD" { print $NF }')
    [[ -n "$load_alignments" ]] || { echo "No ELF LOAD segments found: $library" >&2; exit 1; }
    while read -r alignment; do
        (( alignment >= 16384 && (alignment & (alignment - 1)) == 0 )) || {
            echo "ELF LOAD segment is not 16 KiB aligned: $library $alignment" >&2
            exit 1
        }
    done <<<"$load_alignments"
done

echo "PASS CPU release APK signature, version, HeliBoard label, non-debuggable application, nine platform permissions plus app-private signature permission, keyboard assets, model/audio exclusion, rebuilt native libraries, native CPU behavior, no Vulkan linkage, ZIP alignment, and all three ELF alignments"
echo "APK: $APK"
