#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
TOOLCHAIN=${PIXEL_VOICE_TOOLCHAIN:-/home/juruc/.local/share/pixel-voice-toolchain}
export JAVA_HOME="$TOOLCHAIN/jdk17"
export ANDROID_HOME="$TOOLCHAIN/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
ABI=${PIXEL_VOICE_ABI:-arm64-v8a}
export CMAKE_BUILD_PARALLEL_LEVEL=2

for path in "$JAVA_HOME/bin/java" "$ANDROID_HOME/ndk/28.0.13004108" "$ANDROID_HOME/cmake/3.22.1"; do
    if [[ ! -e "$path" ]]; then
        echo "Missing required toolchain path: $path" >&2
        exit 1
    fi
done

cmake -S "$ROOT" -B "$ROOT/build/host" \
    -DCMAKE_BUILD_TYPE=Release \
    -DGGML_NATIVE=OFF
cmake --build "$ROOT/build/host" --target pixel_voice_smoke -j2
"$ROOT/gradlew" --no-daemon --max-workers=2 -Dorg.gradle.parallel=false -p "$ROOT" \
    -PpixelVoiceAbi="$ABI" stageDebugApk

echo "APK: $ROOT/app/build/outputs/pixelVoice/$ABI/pixel-voice-$ABI-debug.apk"
