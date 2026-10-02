# Maintain the fork

`main` is unmodified HeliBoard v4.1 at `9f5bb635c2e8609dcd95dc7506c0c58fba82a52c`. Keep changes on `pixel-voice`. `origin` is [the public fork](https://github.com/JafarAbdi/HeliBoard). `upstream` is [HeliBoard](https://github.com/HeliBorg/HeliBoard), with pushing disabled.

## Update HeliBoard

Start with a clean checkout and save the current branch before rebasing.

```sh
git fetch upstream
old_base=$(git rev-parse main)
git switch main
git merge --ff-only upstream/main
git switch pixel-voice
git rebase --onto main "$old_base"
git submodule update --init --recursive
```

Resolve conflicts in the keyboard integration and build files. Keep upstream names, icons, repository files, and version metadata. Update the selected base above, `app/build.gradle.kts`, and the version check in `scripts/check.sh` together.

## Update the native engine

`third_party/transcribe.cpp` is an unpatched [submodule](https://github.com/handy-computer/transcribe.cpp) pinned to `c1fee503219ea82dc2e42917150198a31b2ecc2a`. Read its contribution rules before changing it.

```sh
git -C third_party/transcribe.cpp fetch origin
git -C third_party/transcribe.cpp checkout --detach <commit>
git add third_party/transcribe.cpp
```

Adapt `dictation/src/main/cpp/` and `CMakeLists.txt` if the engine API changes. Update the pin above. Keep CPU inference, credential-protected model storage, capture closure, and safe editor ownership intact. If the model changes, update `ModelStore.PRODUCTION_ARTIFACT`, `scripts/check.sh`, and the workflow download URL together. Never commit weights, recordings, or signing keys.

## Verify an update

Use JDK 17 and the SDK, NDK, and CMake versions pinned in the Gradle files and `.github/workflows/build-debug-apk.yml`. If those versions change, update the workflow and script tool paths too.

```sh
export PIXEL_VOICE_TOOLCHAIN=/home/juruc/.local/share/pixel-voice-toolchain
export JAVA_HOME="$PIXEL_VOICE_TOOLCHAIN/jdk17"
export ANDROID_HOME="$PIXEL_VOICE_TOOLCHAIN/android-sdk"
export PIXEL_VOICE_MODEL=/path/to/parakeet-unified-en-0.6b-Q8_0.gguf
scripts/check.sh
```

This builds and checks the ARM64 APK, signer, native inference, unit tests, lint, permissions, bundled assets, and alignment. Keep the existing signing key and checks. Confirm any changed permission, native-library, or lint-baseline expectation against the actual APK.

For editor checks, use an owned API 35 emulator. Build and install the x86_64 APK and verified model before running the fixtures.

```sh
PIXEL_VOICE_ABI=x86_64 scripts/build.sh
./gradlew -PpixelVoiceAbi=x86_64 :app:assembleDebugAndroidTest
export PIXEL_VOICE_DEVICE=emulator-5556
for mode in keyboard-native keyboard-web-native keyboard-compose-protocol keyboard-web-compose-protocol keyboard-defaults; do
	PIXEL_VOICE_KEYBOARD_MODE="$mode" scripts/check-keyboard.sh
done
```

The APKs are under `app/build/outputs/pixelVoice/<abi>/`. Emulator results do not establish physical-device speech quality or performance.

## Publish a release

Keep `ANDROID_DEBUG_KEYSTORE_BASE64` in the fork's Actions secrets. It contains the existing `~/.android/debug.keystore`, encoded as base64. The workflow checks its pinned signer and uploads only the APK and checksum. Pushes and manual dispatch on `pixel-voice` build, check, and update the existing `continuous` prerelease. Native compilation and Gradle task caches are enabled.

Use the permanent [APK download](https://github.com/JafarAbdi/HeliBoard/releases/download/continuous/HeliBoard.apk). The single `continuous` tag moves to the verified build commit; do not create a tag per build. Publication drafts the release before replacing its assets. An interrupted publication leaves a draft; rerun the workflow to complete the update.

Use `git absorb --base main --and-rebase` for staged fixes to existing commits. Get approval before force-pushing published history. Preserve [licenses and attribution](NOTICE). Handy is [reference-only](https://github.com/cjpais/Handy), observed at `29bd2c0d6b4b705df5fd6d2f387e5db5ecc27480`, and is not built or shipped.
