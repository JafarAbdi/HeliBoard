#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
TOOLCHAIN=${PIXEL_VOICE_TOOLCHAIN:-/home/juruc/.local/share/pixel-voice-toolchain}
DEVICE=${PIXEL_VOICE_DEVICE:-emulator-5556}
MODE=${PIXEL_VOICE_KEYBOARD_MODE:-keyboard-native}
ADB="$TOOLCHAIN/android-sdk/platform-tools/adb"
TEST_APK="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
PACKAGE=helium314.keyboard.debug
PIXEL_VOICE_IME=helium314.keyboard.debug/helium314.keyboard.latin.LatinIME
RUNNER=helium314.keyboard.debug.test/dev.juruc.pixelvoice.PixelVoiceTestRunner

case "$MODE" in
    keyboard-native)
        markers=(
            'PASS keyboard native JFK insertion at caret'
            'PASS keyboard composing Hello native insertion'
            'PASS keyboard composing Hello Cancel preserves editor'
            'PASS keyboard composing Hello inside-word native insertion'
            'PASS keyboard selection is replaced by native transcript'
            'PASS keyboard Cancel preserves editor'
            'PASS keyboard field change cancels'
            'PASS keyboard editor restart cancels'
            'PASS keyboard letter tap cancels and types'
            'PASS keyboard capture closes before foreground retirement'
            'PASS foreground service loss cancels keyboard capture'
        )
        ;;
    keyboard-web-native)
        markers=('PASS WebView native JFK insertion' 'PASS WebView Cancel preserves editor')
        ;;
    keyboard-defaults)
        markers=('PASS keyboard defaults and saved customization')
        ;;
    keyboard-compose-protocol|keyboard-web-compose-protocol)
        surface=EditText
        [[ "$MODE" == keyboard-web-compose-protocol ]] && surface=Chromium
        markers=(
            "PASS $surface composing revisions and final, editor protocol only"
            "PASS $surface selected-text discard, editor protocol only"
            "PASS $surface composing Hello prefix, editor protocol only"
            "PASS $surface composing Hello inside word, editor protocol only"
        )
        ;;
    keyboard-cancel) markers=('PASS keyboard Cancel preserves editor') ;;
    keyboard-field-change) markers=('PASS keyboard field change cancels') ;;
    keyboard-restart) markers=('PASS keyboard editor restart cancels') ;;
    keyboard-typing) markers=('PASS keyboard letter tap cancels and types') ;;
    keyboard-microphone) markers=('PASS keyboard production AudioRecord starts and cancels') ;;
    keyboard-setup) markers=('PASS keyboard missing permission setup stays idle') ;;
    setup-intent) markers=('PASS setup intent disarms pending permission recording') ;;
    manifest) markers=('PASS manifest ownership') ;;
    no-model) markers=('PASS manifest ownership' 'PASS no-model screen') ;;
    native) markers=('PASS JNI JFK streaming') ;;
    activity) markers=('PASS installed Activity flow') ;;
    installed) markers=('PASS manifest ownership' 'PASS installed Activity flow' 'PASS JNI JFK streaming') ;;
    *) echo "Unsupported PIXEL_VOICE_KEYBOARD_MODE: $MODE" >&2; exit 1 ;;
esac
EXPECTED_TESTS=${#markers[@]}

[[ -x "$ADB" ]] || { echo "Missing adb: $ADB" >&2; exit 1; }
[[ -f "$TEST_APK" ]] || { echo "Missing test APK: $TEST_APK" >&2; exit 1; }
[[ "$("$ADB" -s "$DEVICE" shell getprop ro.build.version.sdk | tr -d '\r')" == 35 ]] || {
    echo "Keyboard verification requires API 35 on $DEVICE" >&2
    exit 1
}
input_methods=$("$ADB" -s "$DEVICE" shell ime list -a)
grep -Fq "$PIXEL_VOICE_IME" <<<"$input_methods" || {
    echo "The installed APK does not expose $PIXEL_VOICE_IME" >&2
    exit 1
}

SETTING_NAMES=(enabled_input_methods default_input_method selected_input_method_subtype show_ime_with_hard_keyboard)
ORIGINAL_SETTINGS=()
for name in "${SETTING_NAMES[@]}"; do
    ORIGINAL_SETTINGS+=("$("$ADB" -s "$DEVICE" shell settings get secure "$name" | tr -d '\r')")
done

put_secure_setting() {
    local name=$1 value=$2 observed
    if [[ "$value" == null ]]; then
        "$ADB" -s "$DEVICE" shell settings delete secure "$name" >/dev/null || return
    else
        "$ADB" -s "$DEVICE" shell "settings put secure $name '$value'" >/dev/null || return
    fi
    observed=$("$ADB" -s "$DEVICE" shell settings get secure "$name" | tr -d '\r') || return
    [[ "$observed" == "$value" ]] || {
        echo "Secure setting readback differs for $name" >&2
        return 1
    }
}

restore_input_methods() {
    local status=$? cleanup_failed=0 i
    trap - EXIT
    "$ADB" -s "$DEVICE" shell am force-stop "$PACKAGE" || cleanup_failed=1
    put_secure_setting enabled_input_methods "${ORIGINAL_SETTINGS[0]}" || cleanup_failed=1
    if [[ "${ORIGINAL_SETTINGS[1]}" != null && -n "${ORIGINAL_SETTINGS[1]}" ]]; then
        "$ADB" -s "$DEVICE" shell ime set "${ORIGINAL_SETTINGS[1]}" >/dev/null || cleanup_failed=1
    fi
    for ((i = 1; i < ${#SETTING_NAMES[@]}; i++)); do
        put_secure_setting "${SETTING_NAMES[i]}" "${ORIGINAL_SETTINGS[i]}" || cleanup_failed=1
    done
    if [[ "$cleanup_failed" == 1 && "$status" == 0 ]]; then
        status=1
    fi
    exit "$status"
}

trap restore_input_methods EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

"$ADB" -s "$DEVICE" install -r "$TEST_APK"
"$ADB" -s "$DEVICE" shell ime reset >/dev/null
bootstrap_ime=$("$ADB" -s "$DEVICE" shell settings get secure default_input_method | tr -d '\r')
[[ -n "$bootstrap_ime" && "$bootstrap_ime" != null && "$bootstrap_ime" != "$PIXEL_VOICE_IME" ]] || {
    echo "Keyboard verification requires a built-in system IME bootstrap; ime reset selected '$bootstrap_ime' on $DEVICE" >&2
    exit 1
}
case "$MODE" in
    keyboard-setup|setup-intent)
        "$ADB" -s "$DEVICE" shell pm revoke "$PACKAGE" android.permission.RECORD_AUDIO
        "$ADB" -s "$DEVICE" shell pm clear-permission-flags "$PACKAGE" android.permission.RECORD_AUDIO user-set user-fixed
        ;;
    keyboard-*|activity|installed)
        "$ADB" -s "$DEVICE" shell pm grant "$PACKAGE" android.permission.RECORD_AUDIO
        ;;
esac

LOG=$(mktemp /tmp/pixel-voice-keyboard-verification.XXXXXX.log)
set +e
timeout 240s "$ADB" -s "$DEVICE" shell am instrument -w -r \
    -e mode "$MODE" "$RUNNER" 2>&1 | tee "$LOG"
command_status=${PIPESTATUS[0]}
set -e
[[ "$command_status" == 0 ]] || { echo "Instrumentation command failed with $command_status. Log: $LOG" >&2; exit 1; }

for marker in "${markers[@]}"; do
    grep -Fq "$marker" "$LOG" || { echo "Missing marker '$marker'. Log: $LOG" >&2; exit 1; }
done
grep -Eq "PASS tests=$EXPECTED_TESTS([[:space:]]|$)" "$LOG" || {
    echo "Expected $EXPECTED_TESTS passing tests. Log: $LOG" >&2
    exit 1
}
if grep -Eq 'FAIL step=|INSTRUMENTATION_FAILED' "$LOG"; then
    echo "Instrumentation reported failure. Log: $LOG" >&2
    exit 1
fi

case "$MODE" in
    keyboard-native|keyboard-web-native|keyboard-cancel|keyboard-field-change|keyboard-restart|keyboard-typing)
        [[ $(grep -c 'LIVE_NATIVE partial=.* observedBeforeStop=true' "$LOG") == "$EXPECTED_TESTS" ]] || {
            echo "Expected $EXPECTED_TESTS native partials observed in the editor before Stop. Log: $LOG" >&2
            exit 1
        }
        ;;
esac

echo "PASS integrated Pixel Voice instrumentation, mode $MODE, tests $EXPECTED_TESTS"
case "$MODE" in
    keyboard-native|keyboard-web-native|keyboard-cancel|keyboard-field-change|keyboard-restart|keyboard-typing|native)
        echo 'Audio source: prerecorded jfk.wav, not a physical microphone' ;;
    keyboard-compose-protocol|keyboard-web-compose-protocol)
        echo 'Transcript source: scripted revisions, editor protocol only, not recognition qualification' ;;
    keyboard-defaults)
        echo 'Preference and physical keyboard controls only, no microphone capture' ;;
    keyboard-microphone)
        echo 'Audio source: production AudioRecord; microphone quality and nonsilenced audio are not qualified by this runner' ;;
esac
echo "Log: $LOG"
