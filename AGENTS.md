# HeliBoard dictation architecture

## Repository and application ownership

`app` contains the HeliBoard fork and owns the only APK. Application identity follows upstream: `helium314.keyboard`, with `.debug` for the debug build. Internal dictation classes remain in `dev.juruc.pixelvoice`; implementation namespaces are not APK identities. `dictation` is an internal Android library, not a second installable app. [EXTERNAL_SOURCES.md](EXTERNAL_SOURCES.md) is the source of truth for upstream revisions, the local fork stack, patch inventory, and update procedures. `NOTICE` owns attribution and licenses.

The single Application is `helium314.keyboard.latin.App`, extending `VoiceApplication`. Keyboard initialization stays in `App`. Lazy runtime and recovery-text ownership stay in `VoiceApplication`. Model storage uses the Application's credential-protected `getFilesDir`, independently of upstream device-protected keyboard preferences. `KeyboardDictation` rejects before-unlock starts before accessing model storage or the runtime.

`LatinIME` owns the host adapter and `DictationControlsView`. Its microphone action calls `KeyboardDictation` directly. The adapter reads the current input connection, never one retained across lifecycle changes. `InputLogic.applyDictationEdit` owns composing, cursor-cache, and spacing mechanics. The inline transaction supplies typed editor commands. Beginning finishes a typed composing word once; revisions and finalization replace the verified ASR span.

## Session and capture invariants

`KeyboardDictation` groups one `InlineDictation.Session`, exclusive `VoiceRuntime.Lease`, and `MicrophoneForegroundService.Ticket`. `InlineDictation` owns the editor epoch, original selection/text, confirmed draft, pending full revision, expected acknowledgement, terminal intent, and outcome. Native operation generations remain owned by `VoiceRuntime`.

Raw input start, restart, finish, unbind, hide, and destruction invalidate ownership before deferred keyboard callbacks. Ordinary key, text, gesture, suggestion, and hardware edits interrupt and keep the draft before their normal edit. Explicit Cancel is a separate discard intent. Selection and composing-range callbacks, including candidate-only updates, feed the transaction before Heli recorrection. Suggestion resumption cannot act as a second composing writer while ASR owns the editor.

One edit may be in flight. Later full-transcript revisions coalesce. Ownership uses framework-observed caret/composing geometry plus fresh exact draft text and any reported absolute offset. Offset -1 is valid metadata. Same-length edits may emit no selection callback and require fresh-text proof with unchanged acknowledged geometry. `VERIFIED` needs external evidence. `UNKNOWN` never retries. Lost ownership keeps visible words rather than deleting them. Editor preflight does not make a cross-process read and edit atomic. Obsolete one-shot target/retry APIs were deleted; `DictationTarget` now contains only the editor privacy filter.

Controls must finish a visible draw before foreground readiness. Foreground readiness must precede capture. Normal ticket retirement uses `Lease.closeAfterCapture`, not READY. `RecordingController.captureClosure(generation)` survives session clearing and completes after source-close cleanup. Cancel overrides queued Finish. Intentional aborts must not hide real errors. Unexpected foreground-service destruction requests cancellation but cannot guarantee coverage after the OS removes the service.

`SystemBroadcastReceiver` must not terminate the process when another keyboard is selected. The same process owns standalone setup and recording. Android manages its process lifetime.

`VoiceRuntime` keeps one warm model and routes callbacks by operation generation and lease owner. Preemption disarms pending starts and cancels prior work. `RecordingController` serializes native load, feed, finish, reset, and destroy while atomic cancellation can run concurrently. `MainActivity` owns standalone capture and permission intent. Setup CLEAR_TOP disarms pending Record and never arms capture. `ModelStore` verifies generation-tagged acquisition before installation and excludes acquisition from capture.

## Native boundary

`third_party/transcribe.cpp` is a pinned Git submodule recorded in [EXTERNAL_SOURCES.md](EXTERNAL_SOURCES.md). Read its `AGENTS.md` and `CONTRIBUTING.md` before changing it. Keep the submodule clean.

Root CMake builds `dictation/src/main/cpp` and explicitly disables accelerators. `Engine::load(path)` requests CPU. Java and JNI expose one model-load path. JNI contains exceptions and preserves UTF-8. The model pin stays in `ModelStore.PRODUCTION_ARTIFACT`. Its private path remains `files/models/parakeet-unified-en-0.6b-Q8_0.gguf`.

Keyboard JNI stays in upstream ndk-build as `libjni_latinime.so`. Inference stays in `libpixelvoice_jni.so`. AndroidX UI rendering also bundles `libandroidx.graphics.path.so`. All three must have 16 KiB ZIP and ELF alignment. Do not reintroduce Accessibility capture, overlay coordination, shortcut handoff, fallback voice panels, or a public recognition-provider API.

## Reproducible verification

The parent's latest 0.7 qualification evidence is in ignored `.audit/streaming/verification.md`. Historical 0.6 evidence remains in `.audit/heliboard-fork/verification.md`. These artifact records predate the committed fork stack and do not define current Git history. Do not infer physical-device behavior from fixture results.

The toolchain default is `/home/juruc/.local/share/pixel-voice-toolchain`, overridable with `PIXEL_VOICE_TOOLCHAIN`. Current sources require JDK 17, Gradle 8.14, AGP 8.13.2, Android SDK 36, NDK 28.0.13004108, and CMake 3.22.1. Scripts inspect APKs with build-tools 35.0.0.

```sh
scripts/build.sh
scripts/check-java.sh
scripts/check.sh
PIXEL_VOICE_ABI=x86_64 scripts/build.sh
./gradlew -PpixelVoiceAbi=x86_64 :app:assembleDebugAndroidTest
scripts/check-keyboard.sh
PIXEL_VOICE_KEYBOARD_MODE=keyboard-web-native scripts/check-keyboard.sh
PIXEL_VOICE_KEYBOARD_MODE=keyboard-microphone scripts/check-keyboard.sh
PIXEL_VOICE_KEYBOARD_MODE=keyboard-setup scripts/check-keyboard.sh
PIXEL_VOICE_KEYBOARD_MODE=setup-intent scripts/check-keyboard.sh
```

`scripts/check-java.sh` expects 80 pure tests across seven dictation classes, comprising 26 core, nine capture-retirement, and 45 streaming-transaction cases. Seven obsolete one-shot-target cases were removed with their unused API. `scripts/check.sh` runs `:dictation:testDebugUnitTest`, `:app:lintDebug`, and `:dictation:lintDebug`. The app lint baseline covers only 68 inherited missing translations in the unchanged upstream talkback resource. Do not blanket-suppress new issues.

The ARM64 APK stages at `app/build/outputs/pixelVoice/arm64-v8a/pixel-voice-arm64-v8a-debug.apk`. The artifact checker requires exactly nine platform permissions plus the AndroidX-generated app-private `helium314.keyboard.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, with signature protection. It also requires source keyboard assets and packaged notice, no GGUF or WAV assets, exactly three native libraries, no Vulkan linkage, and valid signature and alignment. It requires the exact public signer certificate SHA-256 pinned in `scripts/check.sh`, as recorded in the qualified 0.6 and 0.7 artifact evidence. It needs no historical APK and permits no replacement signer. Native CPU behavior, not library byte identity across platform and source-path changes, is the contract.

Keyboard instrumentation uses an owned API 35 emulator, default `emulator-5556`, selected with `PIXEL_VOICE_DEVICE`. Install the x86_64 app and pinned model first, except for `no-model`. The runner component is `helium314.keyboard.debug.test/dev.juruc.pixelvoice.PixelVoiceTestRunner`, independent of the app's internal keyboard namespace.

`keyboard-defaults` expects one case for resource-subtype resolution, pinned-control visibility, physical language toggles, and saved-preference preservation. Its seven targeted fixture preferences use durable commits and exact restoration. After a theme-triggered hide/show during customization, the fixture re-establishes its editor through the existing host window-focus soft-input request before checking visible keys. Physical taps await a committed rendered frame and use live view/key coordinates. The two affected `SubtypeTest` cases run on API 35 with the existing JDK 17 toolchain.

`keyboard-compose-protocol` and `keyboard-web-compose-protocol` each expect four scripted editor cases for revisions, finalization, selected-text restoration, and composing-word preservation. They test production editing primitives, not recognition. `keyboard-web-native` expects two real Chromium WebView cases for native streaming and Cancel. Default `keyboard-native` expects 11 cases. These cover caret, composing word, inside-word, selection replacement, discard, field/restart interruption, actual letter tap, capture retirement, and service loss. Native keyboard modes must observe actual recognized words in the host field before Stop; the checker requires one `LIVE_NATIVE` marker per case. Separate `keyboard-cancel`, `keyboard-field-change`, `keyboard-restart`, `keyboard-typing`, and `keyboard-microphone` modes each expect one case. `keyboard-setup` and `setup-intent` each expect one no-auto-capture permission case. The checker also supports `manifest` with one, `no-model` with two, `native` with one, `activity` with one, and `installed` with three cases.

The checker requires a built-in system IME and runs `ime reset` before instrumentation to avoid force-stopping the selected target keyboard at launch. For keyboard modes, the runner enables and selects the integrated IME after fixture initialization. The checker restores the original enabled IME list, default IME, selected subtype, and hardware-keyboard visibility setting on exit. Accessibility settings stay unchanged. Microphone grant or revocation affects only the owned app. Native fixtures establish literal delivery, not production microphone eligibility. `keyboard-microphone` checks production AudioRecord start and Cancel, not nonsilenced speech. Separate noninstrumented capture evidence must establish visible keys, AudioRecord configuration, nonsilenced capture, AppOps, foreground type, and Stop/Cancel teardown.

## Release workflow

`.github/workflows/build-debug-apk.yml` runs the existing `scripts/check.sh` ARM64 qualification on manual dispatch and `dictation-*` tag pushes. Its build job has read-only repository permission, pinned action revisions, the pinned engine submodule, and an existing signing key supplied through a repository secret. Only the tag-triggered publication job gets `contents: write`; it downloads the checked APK/checksum artifact and publishes after both assets are uploaded. No key or model is included in the artifact. This does not qualify device behavior or the other inherited upstream workflow.

## Work boundaries

The parent owns `.audit`, builds, native qualification, toolchains, model artifacts, devices, and `dist`. Source workers use disjoint files and do not run builds or instrumentation without ownership transfer. Preserve prior work and archived artifacts. Python commands use `uv run` and require the applicable Python skills before edits.

Root `LICENSE`, `LICENSE-Apache-2.0`, and `LICENSE-CC-BY-SA-4.0` retain canonical upstream license texts. The pre-fork dictation MIT notice is preserved in `dictation/LICENSE`. Preserve imported copyright and license headers, keyboard assets, and the packaged notice. Do not infer model redistribution rights.

All work stays local. Do not open upstream PRs, issues, or comments. Do not commit or push without authorization. Physical GrapheneOS, speech quality, and sustained performance qualification remain outside the available evidence.
