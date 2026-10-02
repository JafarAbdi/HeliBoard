#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
TOOLCHAIN=${PIXEL_VOICE_TOOLCHAIN:-/home/juruc/.local/share/pixel-voice-toolchain}
CACHE=${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1
JUNIT=$(find "$CACHE/junit/junit/4.13.2" -name 'junit-4.13.2.jar' -print -quit)
HAMCREST=$(find "$CACHE/org.hamcrest/hamcrest-core/1.3" -name 'hamcrest-core-1.3.jar' -print -quit)
ANDROID_JAR="$TOOLCHAIN/android-sdk/platforms/android-35/android.jar"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
CP="$JUNIT:$HAMCREST:$ANDROID_JAR"
SOURCE="$ROOT/dictation/src/main/java/dev/juruc/pixelvoice"
SOURCES=()
for name in RecordingController VoiceRuntime ModelStore DictationTarget InlineDictation; do
    SOURCES+=("$SOURCE/$name.java")
done
TESTS=()
CLASSES=()
for name in RecordingControllerTest VoiceRuntimeTest ModelStoreTest DictationTargetTest \
        VoiceCaptureRetirementTest InlineDictationTest InlineDictationSnapshotTest; do
    TESTS+=("$ROOT/dictation/src/test/java/dev/juruc/pixelvoice/$name.java")
    CLASSES+=("dev.juruc.pixelvoice.$name")
done
"$TOOLCHAIN/jdk17/bin/javac" --release 17 -cp "$CP" -d "$TMP" "${SOURCES[@]}" "${TESTS[@]}"
"$TOOLCHAIN/jdk17/bin/java" -cp "$TMP:$CP" org.junit.runner.JUnitCore "${CLASSES[@]}" | tee "$TMP/junit.log"
grep -Fxq 'OK (80 tests)' "$TMP/junit.log"
echo 'PASS 80 Java tests: 26 core, 9 capture retirement, 45 streaming transaction cases'
