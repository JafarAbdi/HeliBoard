# HeliBoard with local dictation

This fork combines HeliBoard and local English dictation in one Android app. It uses Parakeet Unified EN 0.6B Q8_0 on the CPU. The standalone recorder provides preview, Stop, Cancel, editable text, and Copy.

There is no cloud inference, saved user audio, waveform display, voice activity detection, GPU inference, or automatic microphone start. The app requires Android 13 or later. It needs no Accessibility grant, app overlay, external voice provider, or keyboard handback.

## Install and set up

Version 4.1, code 4101, matching the upstream base, is available at `dist/pixel-voice.apk`. It uses upstream's debug identity, `helium314.keyboard.debug`, with the original icon and **HeliBoard debug** label:

```sh
adb install -r dist/pixel-voice.apk
```

This is a new install relative to the former Pixel Voice package. Download or import the model again; no settings or model migration is performed. The release HeliBoard package remains independent.

1. Open HeliBoard debug and download the model or import its Q8_0 GGUF through the system file picker. The app checks its 731357568-byte size and pinned SHA-256 before loading.
2. Tap **Allow microphone**.
3. Tap **Enable HeliBoard keyboard** and enable **HeliBoard debug** once in system settings.
4. Tap **Choose keyboard** and select **HeliBoard debug**.
5. Use **Open keyboard settings** for the original HeliBoard settings interface. You can optionally export a backup from HeliBoard and import it here. Settings are not copied automatically.

Model download and import are explicit actions. The model is not bundled in the APK. Acquisition uses a private temporary file and installs it only after verification.

## Keyboard defaults

English US and standard Arabic are enabled, with English selected initially. Voice input is pinned on the collapsed toolbar. The globe key switches between the keyboard languages.

Saved preferences override these defaults. Arabic is available for typing; dictation remains English-only.

## Dictate in a text field

1. Tap the keyboard's microphone key. Expand the toolbar if needed.
2. Wait for **Listening** before speaking. Letter keys remain visible and usable, with controls above them.
3. Recognized words appear directly in the field and can change as recognition improves. No separate preview pane.
4. Tap **Stop** to finalize the current dictation once.
5. Tap **Cancel** to discard the safely owned dictation draft, including during processing. Any selected text it replaced is restored.

Dictation preserves the pre-existing composing word, adds no automatic space, and never appends the final transcript a second time. Typing stops dictation, keeps the visible words, and performs the typed edit. Moving the caret, changing fields, restarting or hiding the editor, locking the screen, losing microphone permission, or service teardown stops capture and keeps visible text. Once editor ownership is lost, Cancel cannot safely remove that text.

Password fields and fields that opt out of microphone input do not accept dictation. Missing model or permission opens setup without starting capture. Finish setup, return to your app, and tap the microphone again.

**Check insertion** means the editor outcome is uncertain. Use **Copy**, **Edit**, or **Review last keyboard dictation** to recover the transcript. The app never automatically repeats an uncertain write.

## Use the standalone recorder

Tap **Record**, then **Stop** to finish the transcript or **Cancel** to discard the recording result. Edit the text and tap **Copy**. Leaving the recorder stops capture and finishes queued audio. The recorder shares the keyboard's model and CPU engine.

## Verification and limits

API 35 emulator checks cover live native recognition in Android and Chromium fields before Stop, finalization without duplication, Cancel, composing words, selections, usable typing keys, setup safety, microphone capture and teardown, and retained model storage. The suite includes 80 JVM cases, 13 prerecorded native keyboard cases, and eight scripted editor-protocol cases. Scripted revisions test editing mechanics, not recognition quality.

The model remains warm until process death. There is no recording cap or idle unload timer. Audio buffers grow with recording length. Sustained performance, long-session memory, heat, battery use, speech quality, and physical Pixel 10 behavior on GrapheneOS remain unverified for this integrated fork. Prerecorded test audio does not qualify microphone capture.

Third-party attribution and model terms are in [NOTICE](NOTICE). Fork commits, source provenance, and update procedures are in [EXTERNAL_SOURCES.md](EXTERNAL_SOURCES.md). Build ownership, architecture, and reproducible checks are in [AGENTS.md](AGENTS.md).
