package dev.juruc.pixelvoice;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.media.AudioRecord;
import android.os.Bundle;
import android.os.Looper;
import android.os.MessageQueue;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.SurroundingText;
import android.widget.Button;
import android.widget.EditText;

import helium314.keyboard.keyboard.Keyboard;
import helium314.keyboard.keyboard.KeyboardSwitcher;
import helium314.keyboard.keyboard.MainKeyboardView;
import helium314.keyboard.keyboard.internal.KeyboardIconsSet;
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode;
import helium314.keyboard.latin.LatinIME;
import helium314.keyboard.latin.RichInputMethodManager;
import helium314.keyboard.latin.WordComposer;
import helium314.keyboard.latin.common.Constants;
import helium314.keyboard.latin.settings.Defaults;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.settings.SettingsSubtype;
import helium314.keyboard.latin.utils.KtxKt;
import helium314.keyboard.latin.utils.SubtypeSettings;
import helium314.keyboard.latin.utils.ToolbarKey;
import helium314.keyboard.latin.utils.ToolbarMode;
import helium314.keyboard.latin.utils.ToolbarUtilsKt;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class PixelVoiceTestRunner extends Instrumentation {
    private static final long UI_TRANSITION_TIMEOUT_SECONDS = 120;
    private static final String EXPECTED_TRANSCRIPT =
            "And so, my fellow Americans, ask not what your country can do for you. "
                    + "Ask what you can do for your country";
    private static final String PACKAGE = "helium314.keyboard.debug";
    private static final String TOOLBAR_EXPAND_VIEW = PACKAGE + ":id/suggestions_strip_toolbar_key";
    private static final String KEYBOARD_VIEW = PACKAGE + ":id/keyboard_view";
    private static final String MICROPHONE_BUTTON_VIEW = PACKAGE + ":id/microphoneButton";
    private static final String PERMISSION_ALLOW_FOREGROUND_VIEW =
            ":id/permission_allow_foreground_only_button";
    private static final String DICTATION_STATUS_VIEW = PACKAGE + ":id/dictation_status";
    private static final String DICTATION_STOP_VIEW = PACKAGE + ":id/dictation_stop";
    private static final String DICTATION_CANCEL_VIEW = PACKAGE + ":id/dictation_cancel";
    private static final String DICTATION_IDLE = "phase=NONE";

    private Bundle arguments;
    private int testsRun;
    private String currentStep = "runner startup";

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments;
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        StringBuilder report = new StringBuilder();
        int resultCode = Activity.RESULT_OK;
        try {
            String mode = arguments.getString("mode", "installed");
            switch (mode) {
                case "keyboard-native" -> verifyKeyboardNative(report);
                case "keyboard-defaults" -> runTest(report,
                        "keyboard defaults and saved customization", this::verifyKeyboardDefaults);
                case "keyboard-compose-protocol" -> verifyCompositionProtocol(report, false);
                case "keyboard-web-compose-protocol" -> verifyCompositionProtocol(report, true);
                case "keyboard-cancel" -> {
                    KeyboardFixture fixture = prepareKeyboardFixture();
                    runTest(report, "keyboard Cancel preserves editor",
                            () -> verifyKeyboardCancel(fixture));
                }
                case "keyboard-field-change" -> {
                    KeyboardFixture fixture = prepareKeyboardFixture();
                    runTest(report, "keyboard field change cancels",
                            () -> verifyKeyboardFieldChange(fixture));
                }
                case "keyboard-restart" -> {
                    KeyboardFixture fixture = prepareKeyboardFixture();
                    runTest(report, "keyboard editor restart cancels",
                            () -> verifyKeyboardRestart(fixture));
                }
                case "keyboard-typing" -> {
                    KeyboardFixture fixture = prepareKeyboardFixture();
                    runTest(report, "keyboard letter tap cancels and types",
                            () -> verifyKeyboardTyping(fixture));
                }
                case "keyboard-microphone" -> runTest(
                        report,
                        "keyboard production AudioRecord starts and cancels",
                        this::verifyKeyboardProductionMicrophone);
                case "keyboard-setup" -> runTest(
                        report,
                        "keyboard missing permission setup stays idle",
                        this::verifyKeyboardPermissionSetup);
                case "manifest" -> runTest(report, "manifest ownership", this::verifyManifest);
                case "no-model" -> {
                    runTest(report, "manifest ownership", this::verifyManifest);
                    runTest(report, "no-model screen", this::verifyNoModelScreen);
                }
                case "native" -> runTest(report, "JNI JFK streaming", this::verifyNativeStreaming);
                case "activity" ->
                        runTest(report, "installed Activity flow", this::verifyInstalledActivity);
                case "setup-intent" -> runTest(
                        report,
                        "setup intent disarms pending permission recording",
                        this::verifySetupIntentDisarmsRecording);
                case "keyboard-web-native" -> {
                    KeyboardFixture fixture = prepareWebViewFixture();
                    runTest(report, "WebView native JFK insertion",
                            () -> verifyWebViewInsertion(fixture));
                    runTest(report, "WebView Cancel preserves editor",
                            () -> verifyWebViewCancel(fixture));
                }
                case "installed" -> {
                    runTest(report, "manifest ownership", this::verifyManifest);
                    runTest(report, "installed Activity flow", this::verifyInstalledActivity);
                    runTest(report, "JNI JFK streaming", this::verifyNativeStreaming);
                }
                default -> throw new IllegalArgumentException("Unknown test mode: " + mode);
            }
            report.append("PASS tests=").append(testsRun).append('\n');
        } catch (Throwable error) {
            report.append("FAIL step=").append(currentStep).append('\n');
            StringWriter stackTrace = new StringWriter();
            error.printStackTrace(new PrintWriter(stackTrace));
            report.append(stackTrace);
            resultCode = Activity.RESULT_CANCELED;
        }
        result.putString("stream", report.toString());
        finish(resultCode, result);
    }

    private void runTest(StringBuilder report, String name, CheckedRunnable test) throws Throwable {
        markStep(name);
        test.run();
        ++testsRun;
        report.append("PASS ").append(name).append('\n');
    }


    private void verifyManifest() throws Exception {
        Context context = getTargetContext();
        PackageManager packages = context.getPackageManager();
        ActivityInfo activity = packages.getActivityInfo(
                new ComponentName(context, MainActivity.class),
                0);
        int required = ActivityInfo.CONFIG_ORIENTATION
                | ActivityInfo.CONFIG_SCREEN_LAYOUT
                | ActivityInfo.CONFIG_SCREEN_SIZE
                | ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE;
        checkEquals(required, activity.configChanges & required, "rotation configChanges");
        PackageInfo info = packages.getPackageInfo(context.getPackageName(),
                PackageManager.GET_PERMISSIONS | PackageManager.GET_SERVICES
                        | PackageManager.GET_PROVIDERS);
        String receiverPermission = PACKAGE + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION";
        checkEquals(Set.of(Manifest.permission.RECORD_AUDIO, Manifest.permission.INTERNET,
                        Manifest.permission.FOREGROUND_SERVICE,
                        Manifest.permission.FOREGROUND_SERVICE_MICROPHONE,
                        Manifest.permission.VIBRATE, Manifest.permission.READ_CONTACTS,
                        Manifest.permission.RECEIVE_BOOT_COMPLETED,
                        "android.permission.READ_USER_DICTIONARY",
                        "android.permission.WRITE_USER_DICTIONARY",
                        receiverPermission),
                Set.of(info.requestedPermissions), "approved permissions");
        PermissionInfo receiver = packages.getPermissionInfo(receiverPermission, 0);
        checkEquals(receiverPermission, receiver.name, "app-private permission name");
        checkEquals(PACKAGE, receiver.packageName, "app-private permission owner");
        checkEquals(PermissionInfo.PROTECTION_SIGNATURE, receiver.protectionLevel,
                "app-private permission protection level");
        int inputMethods = 0;
        for (ServiceInfo service : info.services) {
            check(!Manifest.permission.BIND_ACCESSIBILITY_SERVICE.equals(service.permission),
                    "Accessibility service is declared: " + service.name);
            if (Manifest.permission.BIND_INPUT_METHOD.equals(service.permission)) {
                ++inputMethods;
            }
        }
        checkEquals(1, inputMethods, "input method services");
        if (info.providers != null) {
            for (ProviderInfo provider : info.providers) {
                check(!provider.exported, "Provider is exported: " + provider.name);
            }
        }
        List<ResolveInfo> launchers = packages.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setPackage(context.getPackageName()), 0);
        checkEquals(1, launchers.size(), "launcher activities");
        checkEquals(MainActivity.class.getName(), launchers.get(0).activityInfo.name,
                "launcher activity");
    }

    private void verifyNoModelScreen() throws Throwable {
        File model = installedModel();
        check(!model.exists(), "Run no-model mode before installing the model");
        MainActivity activity = launchActivity();
        try {
            Button importButton = activity.findViewById(R.id.importButton);
            Button recordButton = activity.findViewById(R.id.recordButton);
            awaitView(
                    activity,
                    importButton,
                    "no-model screen",
                    "import button enabled",
                    View::isEnabled);
            check(!recordButton.isEnabled(), "Record was enabled without a model");
            checkEquals(View.VISIBLE, importButton.getVisibility(), "import visibility");
        } finally {
            finishActivity(activity);
        }
    }

    private void verifyInstalledActivity() throws Throwable {
        assertInstalledModel();
        grantMicrophonePermission();
        markStep("installed Activity flow | launch");
        MainActivity activity = launchActivity();
        try {
            Button record = activity.findViewById(R.id.recordButton);
            Button stop = activity.findViewById(R.id.stopButton);
            Button cancel = activity.findViewById(R.id.cancelButton);
            EditText transcript = activity.findViewById(R.id.transcript);
            awaitView(
                    activity,
                    record,
                    "installed Activity flow | model load",
                    "record button enabled",
                    View::isEnabled);

            checkEquals(View.GONE, activity.findViewById(R.id.downloadButton).getVisibility(),
                    "download visibility");
            checkEquals(View.GONE, activity.findViewById(R.id.importButton).getVisibility(),
                    "import visibility");

            click(record);
            awaitView(
                    activity,
                    stop,
                    "installed Activity flow | start first recording",
                    "stop button enabled",
                    View::isEnabled);
            check(activity.getWindow().getDecorView().getKeepScreenOn(), "screen was not kept awake");

            VoiceRuntime rotationRuntime = runtimeOf(activity);
            int currentOrientation = activity.getResources().getConfiguration().orientation;
            int requestedOrientation = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
                    ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            int expectedOrientation = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
                    ? Configuration.ORIENTATION_PORTRAIT
                    : Configuration.ORIENTATION_LANDSCAPE;
            MainActivity rotationOwner = activity;
            runOnMainSync(() -> rotationOwner.setRequestedOrientation(requestedOrientation));
            awaitView(
                    activity,
                    activity.getWindow().getDecorView(),
                    "installed Activity flow | rotate while recording",
                    "orientation " + expectedOrientation,
                    view -> rotationOwner.getResources().getConfiguration().orientation
                            == expectedOrientation);
            check(!activity.isDestroyed(), "Rotation destroyed the session owner");
            check(runtimeOf(activity) == rotationRuntime, "Rotation replaced the voice runtime");
            checkEquals(VoiceRuntime.Stage.RECORDING, stateOf(activity),
                    "recording stage after rotation");

            click(stop);
            awaitView(
                    activity,
                    record,
                    "installed Activity flow | finish first recording",
                    "record button enabled",
                    View::isEnabled);
            check(!activity.getWindow().getDecorView().getKeepScreenOn(), "screen stayed awake after stop");

            click(record);
            awaitView(
                    activity,
                    cancel,
                    "installed Activity flow | start cancellation recording",
                    "cancel button enabled",
                    View::isEnabled);
            click(cancel);
            awaitView(
                    activity,
                    record,
                    "installed Activity flow | cancel recording",
                    "record button enabled",
                    View::isEnabled);

            markStep("installed Activity flow | copy transcript");
            runOnMainSync(() -> transcript.setText("Editable transcript"));
            click(activity.findViewById(R.id.copyButton));
            ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
            checkEquals(
                    "Editable transcript",
                    clipboard.getPrimaryClip().getItemAt(0).getText().toString(),
                    "clipboard text");

            click(record);
            awaitView(
                    activity,
                    stop,
                    "installed Activity flow | start background recording",
                    "stop button enabled",
                    View::isEnabled);
            Application application = (Application) getTargetContext().getApplicationContext();
            ResumeObserver resumeObserver = new ResumeObserver();
            application.registerActivityLifecycleCallbacks(resumeObserver);
            try {
                markStep("installed Activity flow | wait for background stop");
                check(activity.moveTaskToBack(true), "Activity task did not enter the background");
                check(resumeObserver.awaitStop(), "Activity background stop timed out");
                Intent returnIntent = activityIntent();
                returnIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                getTargetContext().startActivity(returnIntent);
                check(resumeObserver.awaitResume(), "Activity resume timed out");
                check(resumeObserver.owner() == activity, "Backgrounding replaced the session owner");
            } finally {
                application.unregisterActivityLifecycleCallbacks(resumeObserver);
            }
            awaitView(
                    activity,
                    record,
                    "installed Activity flow | return from background",
                    "record button enabled",
                    View::isEnabled);
            check(!activity.getWindow().getDecorView().getKeepScreenOn(),
                    "screen stayed awake after background stop");

            markStep("installed Activity flow | recreate activity");
            runOnMainSync(() -> transcript.setText("Preserved after recreation"));
            ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
            runOnMainSync(activity::recreate);
            Activity recreated = waitForMonitorWithTimeout(
                    monitor, TimeUnit.SECONDS.toMillis(UI_TRANSITION_TIMEOUT_SECONDS));
            removeMonitor(monitor);
            check(recreated != null, "Activity recreation timed out");
            EditText restored = recreated.findViewById(R.id.transcript);
            checkEquals("Preserved after recreation", restored.getText().toString(),
                    "restored transcript");
            activity = (MainActivity) recreated;
        } finally {
            finishActivity(activity);
        }
    }

    private void verifyNativeStreaming() throws Exception {
        File model = assertInstalledModel();
        Path utf8Model = model.toPath().resolveSibling("parakeet-语音.gguf");
        Files.deleteIfExists(utf8Model);
        Files.createSymbolicLink(utf8Model, model.toPath().getFileName());
        short[] pcm = readWavPcm16(getContext().getAssets(), "jfk.wav");
        try (NativeEngine engine = new NativeEngine()) {
            engine.load(utf8Model.toString());
            engine.start();
            for (int offset = 0; offset < pcm.length; offset += 1600) {
                int count = Math.min(1600, pcm.length - offset);
                short[] block = new short[count];
                System.arraycopy(pcm, offset, block, 0, count);
                engine.feed(block);
            }
            checkEquals(EXPECTED_TRANSCRIPT, engine.finish(), "JNI transcript");
        } finally {
            Files.deleteIfExists(utf8Model);
        }
    }


    private void verifyKeyboardNative(StringBuilder report) throws Throwable {
        markStep("keyboard fixture setup");
        KeyboardFixture fixture = prepareKeyboardFixture();
        runTest(report, "keyboard native JFK insertion at caret",
                () -> verifyKeyboardInsertion(fixture));
        runTest(report, "keyboard composing Hello native insertion",
                () -> verifyKeyboardWordInsertion(fixture, 5, "Hello" + EXPECTED_TRANSCRIPT,
                        "keyboard composing Hello insertion"));
        runTest(report, "keyboard composing Hello Cancel preserves editor",
                () -> verifyKeyboardWordCancel(fixture));
        runTest(report, "keyboard composing Hello inside-word native insertion",
                () -> verifyKeyboardWordInsertion(fixture, 3, "Hel" + EXPECTED_TRANSCRIPT + "lo",
                        "keyboard composing Hello inside word"));
        runTest(report, "keyboard selection is replaced by native transcript",
                () -> verifyKeyboardSelectionReplace(fixture));
        runTest(report, "keyboard Cancel preserves editor", () -> verifyKeyboardCancel(fixture));
        runTest(report, "keyboard field change cancels", () -> verifyKeyboardFieldChange(fixture));
        runTest(report, "keyboard editor restart cancels", () -> verifyKeyboardRestart(fixture));
        runTest(report, "keyboard letter tap cancels and types",
                () -> verifyKeyboardTyping(fixture));
        runTest(report, "keyboard capture closes before foreground retirement",
                () -> verifyKeyboardForegroundRetirement(fixture));
        runTest(report, "foreground service loss cancels keyboard capture",
                () -> verifyKeyboardForegroundLoss(fixture));
    }

    private UiAutomation automation() {
        return getUiAutomation();
    }

    private String keyboardIme() {
        return new ComponentName(getTargetContext(), LatinIME.class).flattenToShortString();
    }

    private void verifyKeyboardDefaults() throws Throwable {
        SharedPreferences prefs = KtxKt.prefs(getTargetContext());
        Map<String, Object> saved = new LinkedHashMap<>();
        Map<String, ?> original = prefs.getAll();
        for (String key : List.of(Settings.PREF_ENABLED_SUBTYPES, Settings.PREF_SELECTED_SUBTYPE,
                Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY, Settings.PREF_LANGUAGE_SWITCH_KEY,
                Settings.PREF_PINNED_TOOLBAR_KEYS, Settings.PREF_TOOLBAR_KEYS,
                Settings.PREF_TOOLBAR_MODE)) {
            saved.put(key, original.get(key));
        }
        Throwable primaryFailure = null;
        try {
            check(!keyboardIme().equals(currentInputMethod()),
                    "keyboard-defaults requires the checker's built-in IME bootstrap");
            Map<String, Object> removed = new LinkedHashMap<>();
            saved.keySet().forEach(key -> removed.put(key, null));
            setKeyboardDefaultsFixturePrefs(prefs, removed);
            assertOnMain(() -> {
                var enabled = SubtypeSettings.INSTANCE.getEnabledSubtypes(false);
                checkEquals(List.of("en-US", "ar"),
                        enabled.stream().map(subtype -> subtype.getLanguageTag()).toList(),
                        "default resource languages");
                checkEquals(0xc9194f98, enabled.get(0).hashCode(), "English US resource subtype");
                checkEquals(0x590dde40, enabled.get(1).hashCode(), "standard Arabic resource subtype");
                checkEquals("MAIN:arabic|SYMBOLS:symbols_arabic",
                        enabled.get(1).getExtraValueOf(Constants.Subtype.ExtraValue.KEYBOARD_LAYOUT_SET),
                        "Arabic resource symbol layout");
                check(enabled.get(1).containsExtraValueKey(Constants.Subtype.ExtraValue.NO_SHIFT_KEY),
                        "Arabic resource lost NoShiftKey");
                checkEquals(SettingsSubtype.Companion.toSettingsSubtype(enabled.get(0)),
                        SettingsSubtype.Companion.toSettingsSubtype(
                                prefs.getString(Settings.PREF_SELECTED_SUBTYPE, Defaults.PREF_SELECTED_SUBTYPE)),
                        "default selected metadata matches the English resource");
                checkEquals(enabled.get(0), SubtypeSettings.INSTANCE.getSelectedSubtype(prefs),
                        "default selected subtype");
                checkEquals(List.of(ToolbarKey.VOICE), ToolbarUtilsKt.getPinnedToolbarKeys(prefs),
                        "default pinned keys");
                checkEquals(ToolbarMode.EXPANDABLE, Settings.readToolbarMode(prefs),
                        "default toolbar mode");
                saved.keySet().forEach(key -> check(!prefs.contains(key),
                        "Default loading wrote " + key));
            });

            configureAccessibility();
            runShell("ime enable " + keyboardIme());
            runShell("settings put secure show_ime_with_hard_keyboard 1");
            runShell("ime set " + keyboardIme());
            launchKeyboardDefaultsEditor();
            awaitKeyboardDefaultsLayout("en-US", "qwerty", "q");
            String voice = KeyboardSwitcher.getInstance().getMainKeyboardView().getContext()
                    .getString(helium314.keyboard.latin.R.string.voice);
            AccessibilityNodeInfo microphone = awaitNode("defaults pinned voice without expansion",
                    node -> packageIs(node, PACKAGE) && node.refresh() && hasDescription(node, voice)
                            && node.isVisibleToUser() && node.isClickable());
            try {
                Rect bounds = new Rect();
                microphone.getBoundsInScreen(bounds);
                check(!bounds.isEmpty(), "Pinned voice has no physical bounds");
            } finally {
                microphone.recycle();
            }
            assertOnMain(() -> {
                View root = KeyboardSwitcher.getInstance().getMainKeyboardView().getRootView();
                View pinned = root.findViewById(helium314.keyboard.latin.R.id.pinned_keys)
                        .findViewWithTag(ToolbarKey.VOICE);
                check(pinned != null && pinned.isShown(), "Pinned voice view is hidden");
                check(!root.findViewById(helium314.keyboard.latin.R.id.toolbar_container).isShown(),
                        "Default toolbar was expanded");
                check(Settings.getValues().isLanguageSwitchKeyEnabled(), "Default globe is disabled");
                check(Settings.getValues().mLanguageSwitchKeyToOtherSubtypes,
                        "Default globe does not switch internally");
                check(!Settings.getValues().mLanguageSwitchKeyToOtherImes,
                        "Default globe switches to other IMEs");
            });
            tapKeyboardDefaultsGlobe();
            awaitKeyboardDefaultsLayout("ar", "arabic", "ض");
            tapKeyboardDefaultsGlobe();
            awaitKeyboardDefaultsLayout("en-US", "qwerty", "q");
            assertEditorText(KeyboardTestActivity.FIRST_EDITOR, KeyboardTestActivity.ORIGINAL_TEXT);
            check(!foregroundRunning(), "Defaults fixture started microphone capture");

            var arabic = SubtypeSettings.INSTANCE.getResourceSubtypesForLocale(
                    Locale.forLanguageTag("ar")).get(0);
            String arabicPref = SettingsSubtype.Companion.toSettingsSubtype(arabic).toPref();
            Map<String, Object> customized = Map.of(
                    Settings.PREF_ENABLED_SUBTYPES, arabicPref,
                    Settings.PREF_SELECTED_SUBTYPE, arabicPref,
                    Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY, false,
                    Settings.PREF_LANGUAGE_SWITCH_KEY, "input_method",
                    Settings.PREF_PINNED_TOOLBAR_KEYS, "CLIPBOARD:true|VOICE:false",
                    Settings.PREF_TOOLBAR_KEYS, "CLIPBOARD:true|VOICE:false",
                    Settings.PREF_TOOLBAR_MODE, "TOOLBAR_KEYS");
            setKeyboardDefaultsFixturePrefs(prefs, customized);
            launchKeyboardDefaultsEditor();
            awaitKeyboardDefaultsLayout("ar", "arabic", "ض");
            assertOnMain(() -> {
                checkEquals(List.of(arabic), SubtypeSettings.INSTANCE.getEnabledSubtypes(false),
                        "saved enabled subtype");
                checkEquals(arabic, SubtypeSettings.INSTANCE.getSelectedSubtype(prefs),
                        "saved selected subtype");
                checkEquals(List.of(ToolbarKey.CLIPBOARD), ToolbarUtilsKt.getPinnedToolbarKeys(prefs),
                        "saved pinned keys");
                checkEquals(List.of(ToolbarKey.CLIPBOARD), ToolbarUtilsKt.getEnabledToolbarKeys(prefs),
                        "saved toolbar keys");
                checkEquals(ToolbarMode.TOOLBAR_KEYS, Settings.getValues().mToolbarMode,
                        "saved toolbar mode");
                check(!Settings.getValues().isLanguageSwitchKeyEnabled(), "Saved hidden globe was ignored");
                check(!Settings.getValues().mLanguageSwitchKeyToOtherSubtypes
                                && Settings.getValues().mLanguageSwitchKeyToOtherImes,
                        "Saved language-key behavior was ignored");
                customized.forEach((key, value) -> checkEquals(value, prefs.getAll().get(key),
                        "saved preference unchanged " + key));
            });
            setKeyboardDefaultsFixturePrefs(prefs, Map.of(
                    Settings.PREF_ENABLED_SUBTYPES, "", Settings.PREF_PINNED_TOOLBAR_KEYS, ""));
            assertOnMain(() -> {
                checkEquals(List.of(), SubtypeSettings.INSTANCE.getEnabledSubtypes(false),
                        "explicit empty enabled subtypes");
                checkEquals(List.of(), ToolbarUtilsKt.getPinnedToolbarKeys(prefs),
                        "explicit empty pinned keys");
                checkEquals("", prefs.getString(Settings.PREF_ENABLED_SUBTYPES, null),
                        "stored empty enabled subtypes unchanged");
                checkEquals("", prefs.getString(Settings.PREF_PINNED_TOOLBAR_KEYS, null),
                        "stored empty pinned keys unchanged");
            });
        } catch (Throwable error) {
            primaryFailure = error;
            throw error;
        } finally {
            String stepBeforeCleanup = currentStep;
            try {
                markStep("defaults restored keyboard settings");
                setKeyboardDefaultsFixturePrefs(prefs, saved);
                assertOnMain(() -> {
                    saved.forEach((key, value) -> {
                        if (value == null) {
                            check(!prefs.contains(key), "restored absent preference " + key);
                        } else {
                            checkEquals(value, prefs.getAll().get(key), "restored preference " + key);
                        }
                    });
                    var keyboard = KeyboardSwitcher.getInstance().getKeyboard();
                    if (keyboard != null) {
                        var selected = SubtypeSettings.INSTANCE.getSelectedSubtype(prefs);
                        checkEquals(selected, keyboard.mId.getSubtype().getRawSubtype(),
                                "restored main keyboard subtype");
                        checkEquals(RichInputMethodManager.getInstance().getCurrentSubtypeLocale(),
                                keyboard.mId.getLocale(), "restored main keyboard locale");
                        checkEquals(keyboard.mId.getLocale(), Settings.getValues().mLocale,
                                "restored settings locale");
                        checkEquals(Settings.readToolbarMode(prefs), Settings.getValues().mToolbarMode,
                                "restored toolbar mode");
                    }
                });
            } catch (Throwable cleanupError) {
                if (primaryFailure == null) {
                    throw cleanupError;
                }
                primaryFailure.addSuppressed(cleanupError);
            } finally {
                if (primaryFailure != null) {
                    currentStep = stepBeforeCleanup;
                }
            }
        }
    }

    private void launchKeyboardDefaultsEditor() throws Throwable {
        String hostPackage = getContext().getPackageName();
        getContext().startActivity(new Intent()
                .setComponent(new ComponentName(hostPackage, KeyboardTestActivity.class.getName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        awaitNode("defaults normal editor", node -> hasDescription(node,
                KeyboardTestActivity.FIRST_EDITOR) && node.refresh() && node.isFocused()).recycle();
    }

    private void setKeyboardDefaultsFixturePrefs(SharedPreferences prefs, Map<String, Object> values)
            throws Throwable {
        assertOnMain(() -> {
            SharedPreferences.Editor editor = prefs.edit();
            values.forEach((key, value) -> {
                if (value == null) {
                    editor.remove(key);
                } else if (value instanceof Boolean flag) {
                    editor.putBoolean(key, flag);
                } else {
                    editor.putString(key, (String) value);
                }
            });
            check(editor.commit(), "Keyboard defaults fixture preference commit failed");
            SubtypeSettings.INSTANCE.reloadEnabledSubtypes(getTargetContext());
            var switcher = KeyboardSwitcher.getInstance();
            boolean liveKeyboard = switcher.getKeyboard() != null;
            if (liveKeyboard) {
                Settings.getInstance().loadSettings(getTargetContext(),
                        RichInputMethodManager.getInstance().getCurrentSubtypeLocale(),
                        Settings.getValues().mInputAttributes);
            } else {
                Settings.getInstance().onSharedPreferenceChanged(prefs, null);
            }
            switcher.setThemeNeedsReload();
            if (liveKeyboard) {
                switcher.reloadMainKeyboard();
            }
        });
    }

    private void awaitKeyboardDefaultsLayout(String language, String layout, String letter)
            throws Throwable {
        awaitMain("defaults actual " + language + " " + layout + " layout", () -> {
            var keyboard = KeyboardSwitcher.getInstance().getKeyboard();
            return keyboard != null && language.equals(keyboard.mId.getLocale().toLanguageTag())
                    && layout.equals(keyboard.mId.getSubtype().getMainLayoutName());
        });
        awaitNode("defaults visible " + language + " letter", node -> packageIs(node, PACKAGE)
                && node.refresh() && letter.equalsIgnoreCase(stringOf(node.getContentDescription()))
                && node.isVisibleToUser()).recycle();
    }

    private void tapKeyboardDefaultsGlobe() throws Throwable {
        AtomicReference<MainKeyboardView> currentView = new AtomicReference<>();
        runOnMainSync(() -> currentView.set(KeyboardSwitcher.getInstance().getMainKeyboardView()));
        MainKeyboardView view = currentView.get();
        check(view != null, "Globe has no live keyboard view");
        String description = view.getContext()
                .getString(helium314.keyboard.latin.R.string.spoken_description_language_switch);
        AccessibilityNodeInfo globe = awaitNode("defaults physical globe", node -> packageIs(node, PACKAGE)
                && node.refresh() && hasDescription(node, description)
                && node.isVisibleToUser() && node.isEnabled());
        Rect accessibilityBounds = new Rect();
        try {
            globe.getBoundsInScreen(accessibilityBounds);
        } finally {
            globe.recycle();
        }

        CountDownLatch rendered = new CountDownLatch(1);
        AtomicReference<Keyboard> renderedKeyboard = new AtomicReference<>();
        AtomicBoolean frameRequested = new AtomicBoolean();
        Runnable frameCommitted = rendered::countDown;
        ViewTreeObserver.OnPreDrawListener listener = () -> {
            var keyboard = view.getKeyboard();
            if (keyboard != null && view.isShown() && view.isLaidOut()
                    && !view.isLayoutRequested() && !view.getRootView().isLayoutRequested()
                    && view.getWidth() == keyboard.mOccupiedWidth + view.getPaddingLeft() + view.getPaddingRight()
                    && view.getHeight() == keyboard.mOccupiedHeight + view.getPaddingTop() + view.getPaddingBottom()
                    && frameRequested.compareAndSet(false, true)) {
                renderedKeyboard.set(keyboard);
                view.getViewTreeObserver().registerFrameCommitCallback(frameCommitted);
            }
            return true;
        };
        markStep("defaults globe awaits laid-out committed keyboard frame");
        try {
            assertOnMain(() -> {
                check(view.isAttachedToWindow() && view.isHardwareAccelerated(),
                        "Globe fixture requires an attached hardware-rendered keyboard");
                view.getViewTreeObserver().addOnPreDrawListener(listener);
                view.invalidate();
            });
            check(rendered.await(UI_TRANSITION_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Globe keyboard frame did not commit");
        } finally {
            runOnMainSync(() -> {
                if (view.getViewTreeObserver().isAlive()) {
                    view.getViewTreeObserver().removeOnPreDrawListener(listener);
                    view.getViewTreeObserver().unregisterFrameCommitCallback(frameCommitted);
                }
            });
        }

        Rect bounds = new Rect();
        AtomicReference<String> measured = new AtomicReference<>();
        assertOnMain(() -> {
            check(view == KeyboardSwitcher.getInstance().getMainKeyboardView()
                            && view.isShown() && !view.isLayoutRequested()
                            && !view.getRootView().isLayoutRequested(),
                    "Globe keyboard view changed after frame commit");
            var keyboard = view.getKeyboard();
            check(keyboard != null && keyboard == renderedKeyboard.get(),
                    "Globe keyboard changed after frame commit");
            checkEquals(RichInputMethodManager.getInstance().getCurrentSubtype().getRawSubtype(),
                    keyboard.mId.getSubtype().getRawSubtype(), "Globe rendered subtype matches current subtype");
            var key = keyboard.getKey(KeyCode.LANGUAGE_SWITCH);
            check(key != null && key.isEnabled() && !key.isSpacer(),
                    "Live keyboard has no enabled language-switch key");
            checkEquals(KeyboardIconsSet.NAME_LANGUAGE_SWITCH_KEY, key.getIconName(),
                    "Live globe icon");
            check(key.getIcon(keyboard.mIconsSet, Constants.Color.ALPHA_OPAQUE) != null,
                    "Live globe has no drawable");
            Rect localBounds = new Rect(key.getX(), key.getY(),
                    key.getX() + key.getWidth(), key.getY() + key.getHeight());
            localBounds.offset(view.getPaddingLeft(), view.getPaddingTop());
            Rect visible = new Rect();
            check(!localBounds.isEmpty() && view.getLocalVisibleRect(visible)
                            && visible.contains(localBounds),
                    "Live globe is outside the visible keyboard | key=" + localBounds + " visible=" + visible);
            int touchX = view.getKeyX(localBounds.centerX());
            int touchY = view.getKeyY(localBounds.centerY());
            check(key.getHitBox().contains(touchX, touchY),
                    "Globe center misses the corrected touch hit box");
            int[] origin = new int[2];
            view.getLocationOnScreen(origin);
            bounds.set(localBounds);
            bounds.offset(origin[0], origin[1]);
            measured.set("defaults globe tap locale=" + keyboard.mId.getLocale().toLanguageTag()
                    + " subtype=" + keyboard.mId.getSubtype().getRawSubtype().hashCode()
                    + " layout=" + keyboard.mId.getSubtype().getMainLayoutName()
                    + " key=" + key.getHitBox() + " local=" + localBounds
                    + " viewOrigin=" + origin[0] + "," + origin[1]
                    + " viewSize=" + view.getWidth() + "x" + view.getHeight()
                    + " correctedTouch=" + touchX + "," + touchY
                    + " absolute=" + bounds + " accessibility=" + accessibilityBounds);
        });
        markStep(measured.get());
        runShell("input tap " + bounds.centerX() + " " + bounds.centerY());
    }

    private KeyboardFixture prepareKeyboardFixture() throws Throwable {
        assertInstalledModel();
        grantMicrophonePermission();
        RecordedJfkAudioFactory audioFactory = new RecordedJfkAudioFactory(
                readWavPcm16(getContext().getAssets(), "jfk.wav"), this::foregroundRunning);
        VoiceRuntime runtime = new VoiceRuntime(
                getTargetContext().getFilesDir().toPath(),
                ModelStore.PRODUCTION_ARTIFACT,
                new PreviewNativeEngine(audioFactory),
                audioFactory,
                getTargetContext().getMainExecutor());
        VoiceApplication application = (VoiceApplication) getTargetContext().getApplicationContext();
        Field runtimeField = VoiceApplication.class.getDeclaredField("runtime");
        runtimeField.setAccessible(true);
        check(runtimeField.get(application) == null, "Voice runtime was initialized before fixture injection");
        runtimeField.set(application, runtime);
        configureKeyboardHost();
        return new KeyboardFixture(audioFactory);
    }

    private void configureKeyboardHost() throws Throwable {
        check(getContext().getApplicationInfo().uid != getTargetContext().getApplicationInfo().uid,
                "Editor host must have a different UID from the keyboard");
        String hostPackage = getContext().getPackageName();
        String hostPackageState = runShell("dumpsys package " + hostPackage);
        check(!hostPackageState.contains(Manifest.permission.RECORD_AUDIO),
                "Editor host unexpectedly requests RECORD_AUDIO");

        configureAccessibility();
        runShell("ime enable " + keyboardIme());
        runShell("settings put secure show_ime_with_hard_keyboard 1");
        runShell("ime set " + keyboardIme());
        checkEquals(
                keyboardIme(),
                runShell("settings get secure default_input_method").trim(),
                "default input method");

        Intent intent = new Intent()
                .setComponent(new ComponentName(hostPackage, KeyboardTestActivity.class.getName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        getContext().startActivity(intent);
        awaitNode(
                "keyboard fixture first editor",
                node -> hasDescription(node, KeyboardTestActivity.FIRST_EDITOR));
        awaitMicrophoneKey("keyboard fixture microphone key");
    }

    private void configureAccessibility() {
        AccessibilityServiceInfo serviceInfo = automation().getServiceInfo();
        serviceInfo.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation().setServiceInfo(serviceInfo);
    }

    private LatinIME keyboard() throws ReflectiveOperationException {
        Field field = KeyboardSwitcher.class.getDeclaredField("mLatinIME");
        field.setAccessible(true);
        LatinIME keyboard = (LatinIME) field.get(KeyboardSwitcher.getInstance());
        check(keyboard != null, "The keyboard service is not running in the instrumented process");
        return keyboard;
    }

    private KeyboardDictation keyboardDictation() throws ReflectiveOperationException {
        Field field = LatinIME.class.getDeclaredField("mDictation");
        field.setAccessible(true);
        return (KeyboardDictation) field.get(keyboard());
    }

    private InlineDictation.Session inlineSession() throws ReflectiveOperationException {
        Field field = KeyboardDictation.class.getDeclaredField("dictation");
        field.setAccessible(true);
        return ((InlineDictation) field.get(keyboardDictation())).session();
    }

    private String dictationDump() throws ReflectiveOperationException {
        StringWriter output = new StringWriter();
        keyboardDictation().dump(new PrintWriter(output, true));
        return output.toString();
    }

    private String composingWord() throws ReflectiveOperationException {
        Field logicField = LatinIME.class.getDeclaredField("mInputLogic");
        logicField.setAccessible(true);
        Object logic = logicField.get(keyboard());
        Field composerField = logic.getClass().getDeclaredField("mWordComposer");
        composerField.setAccessible(true);
        WordComposer composer = (WordComposer) composerField.get(logic);
        return composer.isComposingWord() ? composer.getTypedWord() : "";
    }

    private boolean foregroundRunning() {
        Context context = getTargetContext();
        ComponentName foreground = new ComponentName(context, MicrophoneForegroundService.class);
        return context.getSystemService(ActivityManager.class).getRunningServices(Integer.MAX_VALUE)
                .stream().anyMatch(service -> foreground.equals(service.service));
    }

    /**
     * Waits for keyboard-process state without sleeping. Dictation, runtime callbacks and service
     * lifecycle all run on the main thread, so the condition is rechecked whenever it goes idle.
     */
    private void awaitMain(String step, CheckedCondition condition) throws Throwable {
        markStep(step);
        CountDownLatch reached = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean abandoned = new AtomicBoolean();
        MessageQueue.IdleHandler handler = () -> {
            if (abandoned.get()) {
                return false;
            }
            try {
                if (!condition.matches()) {
                    return true;
                }
            } catch (Throwable error) {
                failure.set(error);
            }
            reached.countDown();
            return false;
        };
        runOnMainSync(() -> Looper.myQueue().addIdleHandler(handler));
        boolean matched = reached.await(UI_TRANSITION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        abandoned.set(true);
        if (failure.get() != null) {
            throw failure.get();
        }
        if (!matched) {
            AtomicReference<String> diagnostics = new AtomicReference<>("unavailable");
            runOnMainSync(() -> {
                try {
                    diagnostics.set(dictationDump().trim() + " composing=" + composingWord()
                            + " foregroundRunning=" + foregroundRunning());
                } catch (Throwable error) {
                    diagnostics.set("unavailable(" + error + ")");
                }
            });
            throw new AssertionError(step + " timed out | " + diagnostics.get());
        }
    }

    private void awaitDictationIdle(String step) throws Throwable {
        awaitMain(step + " dictation and foreground retired", () -> {
            String dump = dictationDump();
            return dump.contains(DICTATION_IDLE) && dump.contains("controlsVisible=false")
                    && !foregroundRunning();
        });
    }

    private RecordedJfkAudioSource startDictation(KeyboardFixture fixture, String step) throws Throwable {
        clickMicrophone(step);
        awaitNode(step + " listening", node -> hasViewId(node, DICTATION_STATUS_VIEW)
                && Objects.equals("Listening…", stringOf(node.getText()))).recycle();
        RecordedJfkAudioSource source = fixture.audioFactory().awaitSource();
        check(source.awaitFirstBlock(), step + " fixture did not publish audio");
        assertKeysUsable(step);
        return source;
    }

    private RecordedJfkAudioSource startDefault(KeyboardFixture fixture, String step) throws Throwable {
        prepareProtocolEditor(false, "Original: ");
        return startDictation(fixture, step);
    }

    private RecordedJfkAudioSource startWord(KeyboardFixture fixture, int cursor, String step) throws Throwable {
        prepareProtocolEditor(false, "Hello");
        selectProtocolEditor(false, "Hello", cursor, cursor);
        awaitMain(step + " keyboard composes the word", () -> "Hello".equals(composingWord()));
        RecordedJfkAudioSource source = startDictation(fixture, step);
        assertOnMain(() -> {
            checkEquals("", composingWord(), step + " pretyped composer reset while listening");
            check(dictationDump().contains("phase=LISTENING"),
                    step + " composition cancelled the session | " + dictationDump());
        });
        return source;
    }

    private void assertKeysUsable(String step) throws Throwable {
        checkEquals(keyboardIme(), currentInputMethod(), step + " input method");
        boolean keyboardWindow = false;
        for (AccessibilityWindowInfo window : automation().getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null) {
                keyboardWindow |= window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                        && packageIs(root, PACKAGE) && root.isVisibleToUser();
                root.recycle();
            }
            window.recycle();
        }
        check(keyboardWindow, step + " had no visible keyboard input-method window");
        AccessibilityNodeInfo stop = awaitNode(step + " Stop control",
                node -> hasViewId(node, DICTATION_STOP_VIEW) && node.isVisibleToUser());
        AccessibilityNodeInfo cancel = null;
        AccessibilityNodeInfo keys = null;
        try {
            cancel = awaitNode(step + " Cancel control",
                    node -> hasViewId(node, DICTATION_CANCEL_VIEW) && node.isVisibleToUser());
            keys = awaitNode(step + " letter key view", PixelVoiceTestRunner::isLetterKeyView);
            Rect keyBounds = new Rect();
            keys.getBoundsInScreen(keyBounds);
            for (AccessibilityNodeInfo control : List.of(stop, cancel)) {
                Rect controlBounds = new Rect();
                control.getBoundsInScreen(controlBounds);
                check(!controlBounds.isEmpty(), step + " control has empty bounds");
                check(!control.isFocusable(), step + " control is focusable");
                check(controlBounds.bottom <= keyBounds.top,
                        step + " control overlaps letter keys | control=" + controlBounds
                                + " keys=" + keyBounds);
            }
            letterAKeyBounds(keys);
        } finally {
            stop.recycle();
            if (cancel != null) {
                cancel.recycle();
            }
            if (keys != null) {
                keys.recycle();
            }
        }
    }

    private static boolean isLetterKeyView(AccessibilityNodeInfo node) {
        if (!hasViewId(node, KEYBOARD_VIEW) || !node.isVisibleToUser() || !node.isEnabled()) {
            return false;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) {
            return false;
        }
        AccessibilityNodeInfo letter = letterAKey(node);
        if (letter == null) {
            return false;
        }
        letter.recycle();
        return true;
    }

    private static AccessibilityNodeInfo letterAKey(AccessibilityNodeInfo keys) {
        for (int index = 0; index < keys.getChildCount(); ++index) {
            AccessibilityNodeInfo child = keys.getChild(index);
            if (child != null) {
                if (Objects.equals("helium314.keyboard.keyboard.Key", stringOf(child.getClassName()))
                        && "a".equalsIgnoreCase(stringOf(child.getContentDescription()))
                        && child.isVisibleToUser() && child.isEnabled()) {
                    return child;
                }
                child.recycle();
            }
        }
        return null;
    }

    private static Rect letterAKeyBounds(AccessibilityNodeInfo keys) {
        Rect keyBounds = new Rect();
        keys.getBoundsInScreen(keyBounds);
        AccessibilityNodeInfo letter = letterAKey(keys);
        check(letter != null, "The keyboard view had no visible a key");
        try {
            Rect bounds = new Rect();
            letter.getBoundsInParent(bounds);
            bounds.offset(keyBounds.left, keyBounds.top);
            check(!bounds.isEmpty() && keyBounds.contains(bounds),
                    "The a key is outside the letter key view | key=" + bounds + " keys=" + keyBounds);
            return bounds;
        } finally {
            letter.recycle();
        }
    }

    private String awaitNativeDraft(RecordedJfkAudioSource source, boolean web,
            String prefix, String suffix, String step) throws Throwable {
        check(source.awaitPreview(), step + " NativeEngine did not recognize a nonempty partial");
        String partial = source.firstPartial;
        check(partial != null && !partial.isEmpty(), step + " had no actual native partial");
        awaitMain(step + " native partial acknowledged before Stop", () -> {
            InlineDictation.Session session = inlineSession();
            return session != null && session.phase == InlineDictation.Phase.LISTENING
                    && !session.waitingForAck() && partial.equals(session.acknowledgedDraft());
        });
        String visible = prefix + partial + suffix;
        awaitProtocolText(web, visible, step + " native words visible before Stop");
        assertKeysUsable(step + " with native words visible");
        markStep("LIVE_NATIVE partial=" + partial + " observedBeforeStop=true");
        return visible;
    }

    private void stopAndAwaitInsertion(RecordedJfkAudioSource source, String expected, String step)
            throws Throwable {
        source.continueAfterPreview();
        check(source.awaitAllPublished(), step + " JFK fixture was not fully published");
        clickNode(step + " Stop", node -> hasViewId(node, DICTATION_STOP_VIEW));
        awaitProtocolText(false, expected, step + " final replacement");
        check(source.awaitClosed(), step + " fixture did not close");
        check(source.foregroundAtClose(), step + " foreground service retired before capture closed");
        assertEditorText(KeyboardTestActivity.SECOND_EDITOR, KeyboardTestActivity.ORIGINAL_TEXT);
        // Controls stay visible for any delivery other than VERIFIED, so idle proves the readback.
        awaitDictationIdle(step);
        assertEditorText(KeyboardTestActivity.FIRST_EDITOR, expected);
    }

    private void verifyKeyboardInsertion(KeyboardFixture fixture) throws Throwable {
        String step = "keyboard insertion";
        RecordedJfkAudioSource source = startDefault(fixture, step);
        awaitNativeDraft(source, false, "Original: ", "", step);
        stopAndAwaitInsertion(source, KeyboardTestActivity.ORIGINAL_TEXT + EXPECTED_TRANSCRIPT, step);
    }

    private void verifyKeyboardWordInsertion(KeyboardFixture fixture, int cursor, String expected, String step)
            throws Throwable {
        RecordedJfkAudioSource source = startWord(fixture, cursor, step);
        awaitNativeDraft(source, false, "Hello".substring(0, cursor), "Hello".substring(cursor), step);
        stopAndAwaitInsertion(source, expected, step);
    }

    private void verifyKeyboardWordCancel(KeyboardFixture fixture) throws Throwable {
        String step = "keyboard composing Hello Cancel";
        RecordedJfkAudioSource source = startWord(fixture, 5, step);
        awaitNativeDraft(source, false, "Hello", "", step);
        clickNode(step, node -> hasViewId(node, DICTATION_CANCEL_VIEW));
        check(source.awaitClosed(), step + " did not close the fixture");
        awaitDictationIdle(step);
        assertEditorText(KeyboardTestActivity.FIRST_EDITOR, "Hello");
        assertEditorText(KeyboardTestActivity.SECOND_EDITOR, KeyboardTestActivity.ORIGINAL_TEXT);
    }

    private void verifyKeyboardSelectionReplace(KeyboardFixture fixture) throws Throwable {
        String step = "keyboard selection replacement";
        prepareProtocolEditor(false, "Original: ");
        int selected = "Original".length();
        selectProtocolEditor(false, "Original: ", 0, selected);
        RecordedJfkAudioSource source = startDictation(fixture, step);
        awaitNativeDraft(source, false, "", ": ", step);
        stopAndAwaitInsertion(source,
                EXPECTED_TRANSCRIPT + KeyboardTestActivity.ORIGINAL_TEXT.substring(selected), step);
    }

    private void verifyKeyboardCancel(KeyboardFixture fixture) throws Throwable {
        String step = "keyboard Cancel";
        prepareProtocolEditor(false, "Original: ");
        selectProtocolEditor(false, "Original: ", 0, 8);
        RecordedJfkAudioSource source = startDictation(fixture, step);
        awaitNativeDraft(source, false, "", ": ", step);
        clickNode(step, node -> hasViewId(node, DICTATION_CANCEL_VIEW));
        check(source.awaitClosed(), step + " did not close the fixture");
        awaitDictationIdle(step);
        assertEditorText(KeyboardTestActivity.FIRST_EDITOR, KeyboardTestActivity.ORIGINAL_TEXT);
        assertEditorText(KeyboardTestActivity.SECOND_EDITOR, KeyboardTestActivity.ORIGINAL_TEXT);
        awaitNode(step + " original selection restored", node -> isProtocolEditor(node, false, true)
                && node.getTextSelectionStart() == 0 && node.getTextSelectionEnd() == 8).recycle();
    }

    private void verifyKeyboardFieldChange(KeyboardFixture fixture) throws Throwable {
        String step = "keyboard field change";
        RecordedJfkAudioSource source = startDefault(fixture, step);
        String visible = awaitNativeDraft(source, false, "Original: ", "", step);
        focusEditor(KeyboardTestActivity.SECOND_EDITOR);
        check(source.awaitClosed(), step + " did not close the fixture");
        awaitDictationIdle(step);
        checkEquals(0, fixture.audioFactory().sources.size(), step + " auto-armed capture");
        assertEditorText(KeyboardTestActivity.FIRST_EDITOR, visible);
        assertEditorText(KeyboardTestActivity.SECOND_EDITOR, KeyboardTestActivity.ORIGINAL_TEXT);
    }

    private void verifyKeyboardRestart(KeyboardFixture fixture) throws Throwable {
        String step = "keyboard editor restart";
        RecordedJfkAudioSource source = startDefault(fixture, step);
        String visible = awaitNativeDraft(source, false, "Original: ", "", step);
        clickNode(step + " restartInput", node -> hasDescription(node, KeyboardTestActivity.RESTART_EDITOR));
        check(source.awaitClosed(), step + " did not close the fixture");
        awaitDictationIdle(step);
        checkEquals(0, fixture.audioFactory().sources.size(), step + " auto-armed capture");
        assertEditorText(KeyboardTestActivity.FIRST_EDITOR, visible);
        assertEditorText(KeyboardTestActivity.SECOND_EDITOR, KeyboardTestActivity.ORIGINAL_TEXT);
    }

    private void verifyKeyboardTyping(KeyboardFixture fixture) throws Throwable {
        String step = "keyboard letter tap";
        RecordedJfkAudioSource source = startDefault(fixture, step);
        String visible = awaitNativeDraft(source, false, "Original: ", "", step);
        String typedLetter = tapLetterA(step);
        awaitProtocolText(false, visible + typedLetter, step + " typed the letter after the kept draft");
        check(source.awaitClosed(), step + " did not close the fixture");
        awaitDictationIdle(step);
        assertEditorText(KeyboardTestActivity.FIRST_EDITOR, visible + typedLetter);
        assertEditorText(KeyboardTestActivity.SECOND_EDITOR, KeyboardTestActivity.ORIGINAL_TEXT);
    }

    private String tapLetterA(String step) throws Throwable {
        AccessibilityNodeInfo keys = awaitNode(step + " letter key view",
                PixelVoiceTestRunner::isLetterKeyView);
        Rect bounds;
        String typedLetter;
        try {
            bounds = letterAKeyBounds(keys);
            AccessibilityNodeInfo letter = letterAKey(keys);
            try {
                typedLetter = stringOf(letter.getContentDescription());
            } finally {
                letter.recycle();
            }
        } finally {
            keys.recycle();
        }
        runShell("input tap " + bounds.centerX() + " " + bounds.centerY());
        return typedLetter;
    }

    private void verifyKeyboardForegroundRetirement(KeyboardFixture fixture) throws Throwable {
        String step = "keyboard foreground retirement";
        RecordedJfkAudioSource source = startDefault(fixture, step);
        awaitNativeDraft(source, false, "Original: ", "", step);
        assertOnMain(() -> {
            check(foregroundRunning(), step + " captured without the microphone foreground service");
            String dump = dictationDump();
            check(dump.contains("leaseActive=true ticketPresent=true"),
                    step + " captured without a lease and ticket | " + dump);
        });
        clickNode(step + " Cancel", node -> hasViewId(node, DICTATION_CANCEL_VIEW));
        check(source.awaitClosed(), step + " did not close the fixture");
        check(source.foregroundAtClose(), step + " foreground service retired before capture closed");
        awaitDictationIdle(step);
        assertEditorText(KeyboardTestActivity.FIRST_EDITOR, KeyboardTestActivity.ORIGINAL_TEXT);
    }

    private void verifyKeyboardForegroundLoss(KeyboardFixture fixture) throws Throwable {
        String step = "keyboard foreground service loss";
        RecordedJfkAudioSource source = startDefault(fixture, step);
        String visible = awaitNativeDraft(source, false, "Original: ", "", step);
        Context context = getTargetContext();
        markStep(step + " OS stopService");
        assertOnMain(() -> check(context.stopService(new Intent(context, MicrophoneForegroundService.class)),
                "Microphone foreground service was not running"));
        check(source.awaitClosed(), step + " did not close the fixture");
        awaitDictationIdle(step);
        assertEditorText(KeyboardTestActivity.FIRST_EDITOR, visible);
        assertEditorText(KeyboardTestActivity.SECOND_EDITOR, KeyboardTestActivity.ORIGINAL_TEXT);
        String typedLetter = tapLetterA(step + " after service loss");
        awaitProtocolText(false, visible + typedLetter, step + " kept words remain normally editable");
    }

    private KeyboardFixture prepareWebViewFixture() throws Throwable {
        assertInstalledModel();
        grantMicrophonePermission();
        RecordedJfkAudioFactory audioFactory = new RecordedJfkAudioFactory(
                readWavPcm16(getContext().getAssets(), "jfk.wav"), this::foregroundRunning);
        VoiceRuntime runtime = new VoiceRuntime(
                getTargetContext().getFilesDir().toPath(),
                ModelStore.PRODUCTION_ARTIFACT,
                new PreviewNativeEngine(audioFactory),
                audioFactory,
                getTargetContext().getMainExecutor());
        VoiceApplication application = (VoiceApplication) getTargetContext().getApplicationContext();
        Field runtimeField = VoiceApplication.class.getDeclaredField("runtime");
        runtimeField.setAccessible(true);
        check(runtimeField.get(application) == null,
                "Voice runtime was initialized before WebView fixture injection");
        runtimeField.set(application, runtime);
        configureWebViewHost();
        return new KeyboardFixture(audioFactory);
    }

    private void configureWebViewHost() throws Throwable {
        check(getContext().getApplicationInfo().uid != getTargetContext().getApplicationInfo().uid,
                "WebView host must have a different UID from the keyboard");
        String hostPackage = getContext().getPackageName();
        check(!runShell("dumpsys package " + hostPackage).contains(Manifest.permission.RECORD_AUDIO),
                "WebView host unexpectedly requests RECORD_AUDIO");
        configureAccessibility();
        runShell("ime enable " + keyboardIme());
        runShell("settings put secure show_ime_with_hard_keyboard 1");
        runShell("ime set " + keyboardIme());
        checkEquals(keyboardIme(),
                runShell("settings get secure default_input_method").trim(),
                "default input method");

        Intent intent = new Intent()
                .setComponent(new ComponentName(hostPackage, WebViewTestActivity.class.getName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        getContext().startActivity(intent);
        awaitNode("WebView fixture first editor",
                node -> isWebViewEditor(node, WebViewTestActivity.FIRST_EDITOR)).recycle();
        awaitNode("WebView fixture second editor",
                node -> isWebViewEditor(node, WebViewTestActivity.SECOND_EDITOR)).recycle();
        focusWebViewEditor(WebViewTestActivity.FIRST_EDITOR);
        awaitMicrophoneKey("WebView fixture microphone key");
    }

    private boolean isWebViewEditor(AccessibilityNodeInfo node, String label) {
        String hostPackage = getContext().getPackageName();
        if (!packageIs(node, hostPackage)
                || !Objects.equals(EditText.class.getName(), stringOf(node.getClassName()))
                || !node.isEditable() || !node.isEnabled() || !node.isVisibleToUser()) {
            return false;
        }
        boolean inWebView = false;
        AccessibilityNodeInfo ancestor = node.getParent();
        while (ancestor != null) {
            inWebView = packageIs(ancestor, hostPackage)
                    && Objects.equals("android.webkit.WebView", stringOf(ancestor.getClassName()));
            AccessibilityNodeInfo next = inWebView ? null : ancestor.getParent();
            ancestor.recycle();
            if (inWebView) {
                break;
            }
            ancestor = next;
        }
        if (!inWebView) {
            return false;
        }
        AccessibilityNodeInfo parent = node.getParent();
        if (parent == null) {
            return false;
        }
        try {
            // HTML labels and inputs are siblings; <br> nodes carry only whitespace.
            String precedingText = null;
            for (int index = 0; index < parent.getChildCount(); ++index) {
                AccessibilityNodeInfo sibling = parent.getChild(index);
                if (sibling == null) {
                    continue;
                }
                try {
                    if (sibling.equals(node)) {
                        return Objects.equals(label, precedingText);
                    }
                    String text = stringOf(sibling.getText());
                    if (text != null && !text.isBlank()) {
                        precedingText = text;
                    }
                } finally {
                    sibling.recycle();
                }
            }
            return false;
        } finally {
            parent.recycle();
        }
    }

    private void awaitWebViewEditorText(String label, String text, String step) throws Throwable {
        awaitNode(step, node -> isWebViewEditor(node, label) && node.refresh()
                && Objects.equals(text, stringOf(node.getText()))).recycle();
    }

    private void setWebViewEditorText(String label, String text) throws Throwable {
        AccessibilityNodeInfo editor = awaitNode("set " + label,
                node -> isWebViewEditor(node, label));
        try {
            Bundle arguments = new Bundle();
            arguments.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            check(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments),
                    "Could not set " + label);
        } finally {
            editor.recycle();
        }
        awaitWebViewEditorText(label, text, "set " + label + " text");
    }

    private void focusWebViewEditor(String label) throws Throwable {
        clickNode("focus " + label, node -> isWebViewEditor(node, label));
        AccessibilityNodeInfo editor = awaitNode("focused " + label,
                node -> isWebViewEditor(node, label) && node.isFocused());
        int end;
        try {
            end = editor.getText().length();
            if (editor.getTextSelectionStart() != end || editor.getTextSelectionEnd() != end) {
                Bundle selection = new Bundle();
                selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, end);
                selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end);
                check(editor.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection),
                        "Could not set selection in " + label);
            }
        } finally {
            editor.recycle();
        }
        awaitNode("cursor at end of " + label,
                node -> isWebViewEditor(node, label) && node.isFocused()
                        && node.getTextSelectionStart() == end
                        && node.getTextSelectionEnd() == end).recycle();
    }

    private RecordedJfkAudioSource startWebViewDefault(KeyboardFixture fixture, String step)
            throws Throwable {
        prepareProtocolEditor(true, "Original: ");
        return startDictation(fixture, step);
    }

    private void verifyWebViewInsertion(KeyboardFixture fixture) throws Throwable {
        String step = "WebView insertion";
        RecordedJfkAudioSource source = startWebViewDefault(fixture, step);
        awaitNativeDraft(source, true, "Original: ", "", step);
        source.continueAfterPreview();
        check(source.awaitAllPublished(), step + " JFK fixture was not fully published");
        clickNode(step + " Stop", node -> hasViewId(node, DICTATION_STOP_VIEW));
        String expected = WebViewTestActivity.ORIGINAL_TEXT + EXPECTED_TRANSCRIPT;
        awaitWebViewEditorText(WebViewTestActivity.FIRST_EDITOR, expected,
                step + " literal insertion");
        check(source.awaitClosed(), step + " fixture did not close");
        check(source.foregroundAtClose(), step + " foreground retired before capture closed");
        awaitDictationIdle(step);
        awaitWebViewEditorText(WebViewTestActivity.FIRST_EDITOR, expected,
                step + " delivered editor");
        awaitWebViewEditorText(WebViewTestActivity.SECOND_EDITOR, WebViewTestActivity.ORIGINAL_TEXT,
                step + " other editor preserved");
    }

    private void verifyWebViewCancel(KeyboardFixture fixture) throws Throwable {
        String step = "WebView Cancel";
        prepareProtocolEditor(true, "Original: ");
        selectProtocolEditor(true, "Original: ", 0, 8);
        RecordedJfkAudioSource source = startDictation(fixture, step);
        awaitNativeDraft(source, true, "", ": ", step);
        clickNode(step, node -> hasViewId(node, DICTATION_CANCEL_VIEW));
        check(source.awaitClosed(), step + " did not close the fixture");
        check(source.foregroundAtClose(), step + " foreground retired before capture closed");
        awaitDictationIdle(step);
        awaitWebViewEditorText(WebViewTestActivity.FIRST_EDITOR, WebViewTestActivity.ORIGINAL_TEXT,
                step + " editor preserved");
        awaitWebViewEditorText(WebViewTestActivity.SECOND_EDITOR, WebViewTestActivity.ORIGINAL_TEXT,
                step + " other editor preserved");
        awaitNode(step + " original selection restored", node -> isProtocolEditor(node, true, true)
                && node.getTextSelectionStart() == 0 && node.getTextSelectionEnd() == 8).recycle();
    }

    private void verifyCompositionProtocol(StringBuilder report, boolean web) throws Throwable {
        assertInstalledModel();
        grantMicrophonePermission();
        EditorProtocolEngine engine = new EditorProtocolEngine();
        VoiceRuntime runtime = new VoiceRuntime(getTargetContext().getFilesDir().toPath(),
                ModelStore.PRODUCTION_ARTIFACT, engine, engine, getTargetContext().getMainExecutor());
        VoiceApplication application = (VoiceApplication) getTargetContext().getApplicationContext();
        Field runtimeField = VoiceApplication.class.getDeclaredField("runtime");
        runtimeField.setAccessible(true);
        check(runtimeField.get(application) == null, "Runtime initialized before editor protocol fixture");
        runtimeField.set(application, runtime);
        if (web) configureWebViewHost();
        else configureKeyboardHost();
        String surface = web ? "Chromium" : "EditText";
        runTest(report, surface + " composing revisions and final, editor protocol only",
                () -> verifyComposingRevisions(report, web, engine));
        runTest(report, surface + " selected-text discard, editor protocol only",
                () -> verifyComposingDiscard(report, web, engine));
        runTest(report, surface + " composing Hello prefix, editor protocol only",
                () -> verifyComposingPrefix(report, web, 5, engine));
        runTest(report, surface + " composing Hello inside word, editor protocol only",
                () -> verifyComposingPrefix(report, web, 3, engine));
    }

    private boolean isProtocolEditor(AccessibilityNodeInfo node, boolean web, boolean first) {
        boolean matches = web ? isWebViewEditor(node,
                first ? WebViewTestActivity.FIRST_EDITOR : WebViewTestActivity.SECOND_EDITOR)
                : hasDescription(node,
                first ? KeyboardTestActivity.FIRST_EDITOR : KeyboardTestActivity.SECOND_EDITOR);
        // Chromium composition can change without invalidating UiAutomation's virtual-node cache.
        return matches && node.refresh();
    }

    private void setProtocolEditorText(boolean web, boolean first, String text) throws Throwable {
        AccessibilityNodeInfo editor = awaitNode("composition protocol set source field",
                node -> isProtocolEditor(node, web, first));
        try {
            Bundle arguments = new Bundle();
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            check(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments),
                    "composition protocol could not set source field text");
        } finally {
            editor.recycle();
        }
        awaitNode("composition protocol source field text set",
                node -> isProtocolEditor(node, web, first)
                        && Objects.equals(text, stringOf(node.getText()))).recycle();
    }

    private void prepareProtocolEditor(boolean web, String text) throws Throwable {
        setProtocolEditorText(web, true, text);
        setProtocolEditorText(web, false, "Original: ");
        clickNode("composition protocol focus first field", node -> isProtocolEditor(node, web, true));
        selectProtocolEditor(web, text, text.length(), text.length());
        awaitMicrophoneKey("composition protocol keyboard visible");
        awaitProtocolText(web, text, "composition protocol prepared source fields");
    }

    private void selectProtocolEditor(boolean web, String expectedText, int start, int end)
            throws Throwable {
        AccessibilityNodeInfo editor = awaitNode("composition protocol focused source text",
                node -> isProtocolEditor(node, web, true) && node.isFocused()
                        && Objects.equals(expectedText, stringOf(node.getText())));
        try {
            check(start >= 0 && end >= 0 && start <= editor.getText().length()
                    && end <= editor.getText().length(), "Selection is outside the fresh source text");
            if (editor.getTextSelectionStart() != start || editor.getTextSelectionEnd() != end) {
                Bundle selection = new Bundle();
                selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start);
                selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end);
                check(editor.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection),
                        "composition protocol could not select text");
            }
        } finally {
            editor.recycle();
        }
        awaitNode("composition protocol actual selection",
                node -> isProtocolEditor(node, web, true) && node.isFocused()
                        && Objects.equals(expectedText, stringOf(node.getText()))
                        && node.getTextSelectionStart() == start
                        && node.getTextSelectionEnd() == end).recycle();
        awaitMain("keyboard observes source selection", () -> {
            InlineDictation.Selection actual = keyboardDictation().observation().selection();
            return actual.start() == start && actual.end() == end;
        });
    }

    private void awaitProtocolText(boolean web, String expected, String step) throws Throwable {
        awaitNode(step, node -> isProtocolEditor(node, web, true)
                && Objects.equals(expected, stringOf(node.getText()))).recycle();
        awaitNode(step + " other field preserved", node -> isProtocolEditor(node, web, false)
                && Objects.equals("Original: ", stringOf(node.getText()))).recycle();
    }

    private EditorProtocolProbe beginProtocolProbe(boolean web, String selected, EditorProtocolEngine engine)
            throws Throwable {
        AccessibilityNodeInfo editor = awaitNode("composition protocol original selection",
                node -> isProtocolEditor(node, web, true) && node.isFocused());
        int start;
        int end;
        try {
            start = editor.getTextSelectionStart();
            end = editor.getTextSelectionEnd();
        } finally {
            editor.recycle();
        }
        AtomicReference<EditorProtocolProbe> probe = new AtomicReference<>();
        assertOnMain(() -> {
            EditorProtocolProbe owner = new EditorProtocolProbe(keyboard(), start, end, selected);
            probe.set(owner);
        });
        try {
            probe.get().begin(engine);
            return probe.get();
        } catch (Throwable error) {
            probe.get().close();
            throw error;
        }
    }

    private void reviseProtocolProbe(StringBuilder report, boolean web, EditorProtocolProbe probe,
            String revision, String expected) throws Throwable {
        String step = "composition protocol revision " + revision;
        assertOnMain(() -> probe.revise(revision));
        awaitMain(step + " actual composing acknowledgement", probe::hasExpectedRange);
        awaitProtocolText(web, expected, step + " visible before finalization");
        AtomicReference<String> evidence = new AtomicReference<>();
        assertOnMain(() -> evidence.set(probe.confirmDraft()));
        report.append("PROTOCOL ").append(web ? "Chromium " : "EditText ")
                .append(evidence.get()).append('\n');
    }

    private void verifyComposingRevisions(StringBuilder report, boolean web, EditorProtocolEngine engine)
            throws Throwable {
        prepareProtocolEditor(web, "Original: ");
        EditorProtocolProbe probe = beginProtocolProbe(web, "", engine);
        try {
            awaitProtocolText(web, "Original: ", "composition begin preserves original");
            reviseProtocolProbe(report, web, probe, "one", "Original: one");
            reviseProtocolProbe(report, web, probe, "one two", "Original: one two");
            reviseProtocolProbe(report, web, probe, "one too", "Original: one too");
            reviseProtocolProbe(report, web, probe, "go", "Original: go");
            reviseProtocolProbe(report, web, probe, "go", "Original: go");
            reviseProtocolProbe(report, web, probe, "café 语音 🎤", "Original: café 语音 🎤");
            probe.commit("Final café 语音 🎤", false);
            awaitMain("final has no composing range", probe::hasTerminalRange);
            awaitProtocolText(web, "Original: Final café 语音 🎤", "final replaces without duplicate");
            assertOnMain(probe::confirmTerminal);
        } finally {
            probe.close();
        }
    }

    private void verifyComposingDiscard(StringBuilder report, boolean web, EditorProtocolEngine engine)
            throws Throwable {
        prepareProtocolEditor(web, "Original: ");
        selectProtocolEditor(web, "Original: ", 0, 8);
        EditorProtocolProbe probe = beginProtocolProbe(web, "Original", engine);
        try {
            awaitProtocolText(web, "Original: ", "begin does not replace selection");
            reviseProtocolProbe(report, web, probe, "draft", "draft: ");
            reviseProtocolProbe(report, web, probe, "new draft", "new draft: ");
            probe.commit("Original", true);
            awaitMain("discard has no composing range", probe::hasTerminalRange);
            awaitProtocolText(web, "Original: ", "discard restores selected original only");
            awaitNode("discard restores actual original selection",
                    node -> isProtocolEditor(node, web, true)
                            && node.getTextSelectionStart() == 0
                            && node.getTextSelectionEnd() == 8).recycle();
            assertOnMain(probe::confirmTerminal);
        } finally {
            probe.close();
        }
    }

    private void verifyComposingPrefix(StringBuilder report, boolean web, int cursor, EditorProtocolEngine engine)
            throws Throwable {
        prepareProtocolEditor(web, "Hello");
        selectProtocolEditor(web, "Hello", cursor, cursor);
        awaitMain("Heli composes Hello before protocol probe", () -> "Hello".equals(composingWord()));
        EditorProtocolProbe probe = beginProtocolProbe(web, "", engine);
        try {
            awaitProtocolText(web, "Hello", "begin finishes Hello in place");
            assertOnMain(() -> checkEquals("", composingWord(), "Hello composer reset at begin"));
            String draft = cursor == 5 ? "Hellodraft" : "Heldraftlo";
            reviseProtocolProbe(report, web, probe, "draft", draft);
            probe.commit("final", false);
            awaitMain("Hello final has no composing range", probe::hasTerminalRange);
            awaitProtocolText(web, cursor == 5 ? "Hellofinal" : "Helfinallo",
                    "final preserves Hello without replay");
            assertOnMain(probe::confirmTerminal);
        } finally {
            probe.close();
        }
    }

    private final class EditorProtocolProbe {
        private final LatinIME ime;
        private final EditorInfo editorInfo;
        private final KeyboardDictation dictation;
        private final int originalStart;
        private final int originalEnd;
        private final String selected;
        private InlineDictation.Session session;
        private ScriptedAudioSource source;
        private InlineDictation.Revision previousRevision;
        private long callbacksBeforeWrite;
        private String draft;
        private String terminalText;
        private boolean restoreSelection;

        EditorProtocolProbe(LatinIME ime, int originalStart, int originalEnd, String selected)
                throws ReflectiveOperationException {
            this.ime = ime;
            editorInfo = ime.getCurrentInputEditorInfo();
            check(editorInfo != null, "Protocol probe has no current editor identity");
            dictation = keyboardDictation();
            this.originalStart = originalStart;
            this.originalEnd = originalEnd;
            this.selected = selected;
        }

        private int insertionStart() {
            return Math.min(originalStart, originalEnd);
        }

        private InputConnection connection() {
            check(ime.getCurrentInputEditorInfo() == editorInfo,
                    "Protocol probe crossed an editor boundary");
            InputConnection connection = ime.getCurrentInputConnection();
            check(connection != null, "Protocol probe lost the current editor");
            return connection;
        }

        private InlineDictation.Readback readback(InputConnection connection, int before) {
            SurroundingText text = connection.getSurroundingText(before, 0, 0);
            check(text != null, "Protocol probe has no fresh readback");
            InlineDictation.Readback result = new InlineDictation.Readback(
                    stringOf(text.getText()), text.getSelectionStart(), text.getSelectionEnd(),
                    text.getOffset());
            check(result.valid(), "Protocol probe has invalid fresh readback");
            return result;
        }

        void begin(EditorProtocolEngine engine) throws Throwable {
            assertOnMain(() -> {
                check(ime.isInputViewShown(), "Protocol probe requires the visible keyboard");
                check(dictationDump().contains(DICTATION_IDLE), "Protocol probe requires idle dictation");
                InlineDictation.Readback before = readback(connection(), 0);
                check(before.matchesObservedCaret(originalStart, originalEnd),
                        "Original fresh selection disagrees with actual editor selection");
                checkEquals(selected, before.text().substring(
                        Math.min(before.selectionStart(), before.selectionEnd()),
                        Math.max(before.selectionStart(), before.selectionEnd())), "original selected text");
            });
            clickMicrophone("composition protocol production start");
            awaitNode("composition protocol listening", node -> hasViewId(node, DICTATION_STATUS_VIEW)
                    && Objects.equals("Listening…", stringOf(node.getText()))).recycle();
            source = engine.awaitSource();
            assertOnMain(() -> {
                session = inlineSession();
                check(session != null, "No production composing transaction");
                checkEquals(originalStart, session.originalSelection.start(), "owned original start");
                checkEquals(originalEnd, session.originalSelection.end(), "owned original end");
                checkEquals(selected, session.originalSelectedText, "owned original selected text");
            });
            assertKeysUsable("composition protocol production controls");
        }

        boolean hasExpectedRange() {
            InlineDictation.Selection observed = dictation.observation().selection();
            int end = insertionStart() + draft.length();
            return session.latestRevision != previousRevision && session.latestRevision != null
                    && draft.equals(session.latestRevision.text()) && draft.equals(session.acknowledgedDraft())
                    && !session.waitingForAck() && observed.start() == end && observed.end() == end
                    && observed.composingStart() == insertionStart() && observed.composingEnd() == end;
        }

        String confirmDraft() {
            InlineDictation.Observation observation = dictation.observation();
            InlineDictation.Selection observed = observation.selection();
            check(hasExpectedRange(), "Actual composing range was lost or changed | " + observed);
            InlineDictation.Readback current = readback(connection(), draft.length());
            check(current.matchesObservedCaret(observed.start(), observed.end()),
                    "Fresh draft readback disagrees with actual callback | " + observed);
            int end = current.selectionEnd();
            check(current.selectionStart() == end && end >= draft.length(),
                    "Fresh draft caret cannot prove the owned range");
            checkEquals(draft, current.text().substring(end - draft.length(), end),
                    "fresh exact composing text");
            return "draft=" + draft + " offset=" + current.offset() + " actual=" + observed
                    + " callbacksBeforeProof=" + (observation.sequence() - callbacksBeforeWrite);
        }

        void revise(String text) {
            if (draft != null) confirmDraft();
            callbacksBeforeWrite = dictation.observation().sequence();
            previousRevision = session.latestRevision;
            draft = text;
            source.submit(text);
        }

        void commit(String text, boolean restoreSelection) throws Throwable {
            assertOnMain(() -> {
                confirmDraft();
                terminalText = text;
                this.restoreSelection = restoreSelection;
                source.finalText = text;
            });
            clickNode("composition protocol " + (restoreSelection ? "Cancel" : "Stop"),
                    node -> hasViewId(node, restoreSelection ? DICTATION_CANCEL_VIEW : DICTATION_STOP_VIEW));
        }

        boolean hasTerminalRange() {
            InlineDictation.Selection observed = dictation.observation().selection();
            int start = restoreSelection ? originalStart : insertionStart() + terminalText.length();
            int end = restoreSelection ? originalEnd : start;
            return session.phase == InlineDictation.Phase.CLOSED
                    && session.delivery == InlineDictation.Delivery.VERIFIED
                    && observed.start() == start && observed.end() == end
                    && observed.composingStart() == -1 && observed.composingEnd() == -1;
        }

        void confirmTerminal() {
            InlineDictation.Selection observed = dictation.observation().selection();
            check(hasTerminalRange(), "Terminal actual selection/composition disagrees | " + observed);
            InlineDictation.Readback current = readback(connection(),
                    restoreSelection ? 0 : terminalText.length());
            check(current.matchesObservedCaret(observed.start(), observed.end()),
                    "Terminal fresh readback disagrees with actual selection");
            int start = restoreSelection ? Math.min(current.selectionStart(), current.selectionEnd())
                    : current.selectionEnd() - terminalText.length();
            int end = Math.max(current.selectionStart(), current.selectionEnd());
            check(start >= 0, "Terminal text is not present in fresh readback");
            checkEquals(terminalText, current.text().substring(start, end), "fresh terminal text");
        }

        void close() throws Throwable {
            assertOnMain(dictation::interrupt);
            if (source != null) {
                check(source.closed.await(UI_TRANSITION_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                        "Editor protocol capture did not close");
                check(source.foregroundAtClose, "Editor protocol foreground retired before capture closed");
            }
            awaitDictationIdle("composition protocol fixture retired");
        }
    }

    private void verifyKeyboardProductionMicrophone() throws Throwable {
        String step = "keyboard production microphone";
        assertInstalledModel();
        checkEquals(
                PackageManager.PERMISSION_GRANTED,
                getTargetContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO),
                "microphone permission prerequisite");
        configureKeyboardHost();
        resetEditors();
        focusEditor(KeyboardTestActivity.FIRST_EDITOR);
        clickMicrophone(step);
        awaitProductionMicrophone();
        assertKeysUsable(step);
        assertOnMain(() -> check(foregroundRunning(),
                step + " recorded without the microphone foreground service"));
        clickNode(step + " Cancel", node -> hasViewId(node, DICTATION_CANCEL_VIEW));
        awaitDictationIdle(step);
        assertEditorText(KeyboardTestActivity.FIRST_EDITOR, KeyboardTestActivity.ORIGINAL_TEXT);
        assertEditorText(KeyboardTestActivity.SECOND_EDITOR, KeyboardTestActivity.ORIGINAL_TEXT);
    }

    private void awaitProductionMicrophone() throws Throwable {
        AtomicReference<AndroidAudioSource> recordingSource = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        awaitNode(
                "keyboard production AudioRecord recording",
                node -> {
                    if (!hasViewId(node, DICTATION_STATUS_VIEW)) {
                        return false;
                    }
                    String status = stringOf(node.getText());
                    if (Objects.equals("Setup needed", status)) {
                        failure.compareAndSet(null, new AssertionError("Production capture failed"));
                        return true;
                    }
                    if (!Objects.equals("Listening…", status)) {
                        return false;
                    }
                    try {
                        AndroidAudioSource source = recordingAndroidAudioSource();
                        if (source == null) {
                            return false;
                        }
                        recordingSource.set(source);
                        return true;
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                        return true;
                    }
                }).recycle();
        if (failure.get() != null) {
            throw failure.get();
        }
        check(recordingSource.get() != null, "Production AudioRecord was not recording");
    }

    private AndroidAudioSource recordingAndroidAudioSource() throws Throwable {
        AtomicReference<AndroidAudioSource> recordingSource = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> {
            try {
                VoiceApplication application =
                        (VoiceApplication) getTargetContext().getApplicationContext();
                Field runtimeField = VoiceApplication.class.getDeclaredField("runtime");
                runtimeField.setAccessible(true);
                VoiceRuntime runtime = (VoiceRuntime) runtimeField.get(application);
                if (runtime == null || runtime.stage() != VoiceRuntime.Stage.RECORDING) {
                    return;
                }
                Field controllerField = VoiceRuntime.class.getDeclaredField("controller");
                controllerField.setAccessible(true);
                RecordingController controller =
                        (RecordingController) controllerField.get(runtime);
                if (controller.state() != RecordingController.State.RECORDING) {
                    return;
                }
                Field sessionField = RecordingController.class.getDeclaredField("session");
                sessionField.setAccessible(true);
                Object session = sessionField.get(controller);
                if (session == null) {
                    return;
                }
                Field sourceField = session.getClass().getDeclaredField("source");
                sourceField.setAccessible(true);
                Object source = sourceField.get(session);
                if (source == null) {
                    return;
                }
                check(source instanceof AndroidAudioSource,
                        "Production runtime used " + source.getClass().getName());
                Field recorderField = AndroidAudioSource.class.getDeclaredField("recorder");
                recorderField.setAccessible(true);
                AudioRecord recorder = (AudioRecord) recorderField.get(source);
                if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    recordingSource.set((AndroidAudioSource) source);
                }
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return recordingSource.get();
    }


    private void verifyKeyboardPermissionSetup() throws Throwable {
        String step = "keyboard permission setup";
        assertInstalledModel();
        assertMicrophoneDenied();
        configureKeyboardHost();
        focusEditor(KeyboardTestActivity.FIRST_EDITOR);

        Application application = (Application) getTargetContext().getApplicationContext();
        SetupActivityObserver observer = new SetupActivityObserver();
        application.registerActivityLifecycleCallbacks(observer);
        MainActivity activity = null;
        try {
            clickMicrophone(step);
            activity = observer.awaitOwner();
            awaitSetupReady(activity, step + " Activity");
            assertRecordAfterPermission(activity, false, step + " pending record before grant");
            assertOnMain(() -> check(dictationDump().contains(DICTATION_IDLE),
                    step + " armed a session | " + dictationDump()));

            clickNode(
                    step + " microphone",
                    node -> hasViewId(node, MICROPHONE_BUTTON_VIEW));
            awaitNode(step + " dialog", PixelVoiceTestRunner::isPermissionAllowButton)
                    .recycle();
            assertRecordAfterPermission(activity, false, step + " pending record in dialog");
            clickNode(step + " grant", PixelVoiceTestRunner::isPermissionAllowButton);
            awaitMicrophoneButtonGone(activity, step + " granted");
            waitForIdleSync();
            assertNoCapture(activity, step);
            assertOnMain(() -> check(dictationDump().contains(DICTATION_IDLE) && !foregroundRunning(),
                    step + " armed capture after the grant | " + dictationDump()));
        } finally {
            application.unregisterActivityLifecycleCallbacks(observer);
            finishActivity(activity);
        }
    }

    private void verifySetupIntentDisarmsRecording() throws Throwable {
        assertInstalledModel();
        assertMicrophoneDenied();
        configureAccessibility();
        MainActivity original = launchActivity();
        MainActivity setupOwner = null;
        Application application = (Application) getTargetContext().getApplicationContext();
        SetupActivityObserver observer = new SetupActivityObserver();
        View originalSetupHint = original.findViewById(R.id.setupHint);
        ViewTreeObserver.OnGlobalLayoutListener setupHintListener = () -> {
            if (originalSetupHint.getVisibility() == View.VISIBLE) {
                observer.capture(original);
            }
        };
        AtomicBoolean setupHintListenerRegistered = new AtomicBoolean();
        try {
            awaitMainReady(original, "setup intent initial Activity");
            assertRecordAfterPermission(original, false, "initial pending record");
            click(original.findViewById(R.id.recordButton));
            awaitNode("setup intent permission dialog", PixelVoiceTestRunner::isPermissionAllowButton)
                    .recycle();
            assertRecordAfterPermission(original, true, "pending record before setup intent");

            application.registerActivityLifecycleCallbacks(observer);
            runOnMainSync(() -> {
                originalSetupHint.getViewTreeObserver().addOnGlobalLayoutListener(setupHintListener);
                setupHintListenerRegistered.set(true);
            });
            markStep("setup intent delivery");
            getTargetContext().startActivity(MainActivity.setupIntent(getTargetContext()));
            setupOwner = observer.awaitOwner();
            awaitSetupReady(setupOwner, "setup intent delivered");
            assertRecordAfterPermission(original, false, "original pending record after setup intent");
            assertRecordAfterPermission(setupOwner, false, "setup owner pending record before grant");
            assertNoCapture(setupOwner, "setup intent before permission grant");

            if (getTargetContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_DENIED) {
                AccessibilityNodeInfo existingDialog = findNode(
                        PixelVoiceTestRunner::isPermissionAllowButton);
                if (existingDialog == null) {
                    clickNode(
                            "setup intent setup microphone",
                            node -> hasViewId(node, MICROPHONE_BUTTON_VIEW));
                    awaitNode(
                            "setup intent setup permission dialog",
                            PixelVoiceTestRunner::isPermissionAllowButton).recycle();
                } else {
                    existingDialog.recycle();
                }
                clickNode("setup intent permission grant", PixelVoiceTestRunner::isPermissionAllowButton);
                awaitMicrophoneButtonGone(setupOwner, "setup intent permission granted");
            }
            waitForIdleSync();
            assertRecordAfterPermission(setupOwner, false, "setup owner pending record after grant");
            assertNoCapture(setupOwner, "setup intent permission result");
        } finally {
            if (setupHintListenerRegistered.get()) {
                runOnMainSync(() -> {
                    ViewTreeObserver observerTree = originalSetupHint.getViewTreeObserver();
                    if (observerTree.isAlive()) {
                        observerTree.removeOnGlobalLayoutListener(setupHintListener);
                    }
                });
            }
            application.unregisterActivityLifecycleCallbacks(observer);
            if (setupOwner != original) {
                finishActivity(setupOwner);
            }
            finishActivity(original);
        }
    }

    private void awaitSetupReady(MainActivity activity, String step) throws Throwable {
        awaitView(
                activity,
                activity.findViewById(R.id.setupHint),
                step,
                "setup hint visible",
                view -> view.getVisibility() == View.VISIBLE);
        awaitMainReady(activity, step + " model");
    }

    private void awaitMainReady(MainActivity activity, String step) throws Throwable {
        awaitView(
                activity,
                activity.findViewById(R.id.recordButton),
                step,
                "record button enabled",
                View::isEnabled);
    }

    private void awaitMicrophoneButtonGone(MainActivity activity, String step) throws Throwable {
        awaitView(
                activity,
                activity.findViewById(R.id.microphoneButton),
                step,
                "microphone button gone",
                view -> view.getVisibility() == View.GONE);
    }

    private void assertMicrophoneDenied() {
        checkEquals(
                PackageManager.PERMISSION_DENIED,
                getTargetContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO),
                "microphone permission prerequisite");
    }

    private void assertRecordAfterPermission(
            MainActivity activity,
            boolean expected,
            String label) throws Throwable {
        assertOnMain(() -> {
            Field field = MainActivity.class.getDeclaredField("recordAfterPermission");
            field.setAccessible(true);
            checkEquals(expected, field.getBoolean(activity), label);
        });
    }

    private void assertNoCapture(MainActivity activity, String label) throws Throwable {
        assertOnMain(() -> {
            checkEquals(VoiceRuntime.Stage.READY, stateOf(activity), label + " runtime stage");
            Field controllerField = VoiceRuntime.class.getDeclaredField("controller");
            controllerField.setAccessible(true);
            RecordingController controller = (RecordingController) controllerField.get(runtimeOf(activity));
            Field sessionField = RecordingController.class.getDeclaredField("session");
            sessionField.setAccessible(true);
            check(sessionField.get(controller) == null, label + " retained a recording session");
            check(activity.findViewById(R.id.recordButton).isEnabled(),
                    label + " record button was not enabled");
            check(!activity.findViewById(R.id.stopButton).isEnabled(),
                    label + " stop button was enabled");
            checkEquals(View.VISIBLE, activity.findViewById(R.id.setupHint).getVisibility(),
                    label + " setup hint visibility");
        });
    }

    private void assertOnMain(CheckedRunnable assertion) throws Throwable {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> {
            try {
                assertion.run();
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
    }

    private static boolean isPermissionAllowButton(AccessibilityNodeInfo node) {
        String viewId = node.getViewIdResourceName();
        return viewId != null && viewId.endsWith(PERMISSION_ALLOW_FOREGROUND_VIEW);
    }


    private void resetEditors() throws Throwable {
        setEditorText(KeyboardTestActivity.FIRST_EDITOR, KeyboardTestActivity.ORIGINAL_TEXT);
        setEditorText(KeyboardTestActivity.SECOND_EDITOR, KeyboardTestActivity.ORIGINAL_TEXT);
    }

    private void setEditorText(String description, String text) throws Throwable {
        AccessibilityNodeInfo node = awaitNode(
                "set " + description,
                candidate -> hasDescription(candidate, description));
        try {
            Bundle arguments = new Bundle();
            arguments.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text);
            check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments),
                    "Could not set " + description);
        } finally {
            node.recycle();
        }
        awaitEditorText(description, text, "set " + description + " text");
    }

    private void focusEditor(String description) throws Throwable {
        clickNode("focus " + description, node -> hasDescription(node, description));
        AccessibilityNodeInfo focused = awaitNode(
                "focused " + description,
                node -> hasDescription(node, description) && node.isFocused());
        boolean alreadyAtEnd = focused.getTextSelectionStart() == focused.getText().length()
                && focused.getTextSelectionEnd() == focused.getText().length();
        focused.recycle();
        if (!alreadyAtEnd) {
            runShell("input keyevent KEYCODE_MOVE_END");
        }
        AccessibilityNodeInfo cursorAtEnd = awaitNode(
                "cursor at end of " + description,
                node -> hasDescription(node, description)
                        && node.getTextSelectionStart() == node.getText().length()
                        && node.getTextSelectionEnd() == node.getText().length());
        cursorAtEnd.recycle();
    }


    private void clickMicrophone(String step) throws Throwable {
        awaitMicrophoneKey(step + " keyboard ready");
        clickNode(step + " microphone key", PixelVoiceTestRunner::isMicrophoneKey);
    }

    private static boolean isMicrophoneKey(AccessibilityNodeInfo node) {
        return packageIs(node, PACKAGE) && node.isClickable() && node.isVisibleToUser()
                && Objects.equals("Voice input", stringOf(node.getContentDescription()));
    }

    private void awaitMicrophoneKey(String step) throws Throwable {
        checkEquals(keyboardIme(), currentInputMethod(), "current input method");
        AccessibilityNodeInfo microphone = findNode(PixelVoiceTestRunner::isMicrophoneKey);
        if (microphone != null) {
            microphone.recycle();
            return;
        }
        clickNode(step + " expand toolbar", node -> hasViewId(node, TOOLBAR_EXPAND_VIEW));
        awaitNode(step, PixelVoiceTestRunner::isMicrophoneKey).recycle();
    }

    private void awaitEditorText(String description, String text, String step) throws Throwable {
        AccessibilityNodeInfo node = awaitNode(
                step,
                candidate -> hasDescription(candidate, description) && candidate.refresh()
                        && Objects.equals(text, stringOf(candidate.getText())));
        node.recycle();
    }

    private void assertEditorText(String description, String expected) {
        AccessibilityNodeInfo node = findNode(candidate -> hasDescription(candidate, description));
        check(node != null, "Missing editor " + description);
        try {
            check(node.refresh(), "Source editor no longer exists " + description);
            checkEquals(expected, stringOf(node.getText()), description + " text");
        } finally {
            node.recycle();
        }
    }

    private void clickNode(String step, NodeCondition condition) throws Throwable {
        AccessibilityNodeInfo node = awaitNode(step, condition);
        try {
            check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK),
                    step + " did not accept an accessibility click");
        } finally {
            node.recycle();
        }
    }

    private AccessibilityNodeInfo awaitNode(String step, NodeCondition condition) throws Throwable {
        markStep(step);
        AccessibilityNodeInfo node = findNode(condition);
        if (node != null) {
            return node;
        }
        try {
            automation().executeAndWaitForEvent(
                    () -> { },
                    event -> {
                        AccessibilityNodeInfo match = findNode(condition);
                        if (match == null) {
                            return false;
                        }
                        match.recycle();
                        return true;
                    },
                    TimeUnit.SECONDS.toMillis(UI_TRANSITION_TIMEOUT_SECONDS));
        } catch (TimeoutException error) {
            throw new AssertionError(step + " timed out | " + describeAccessibilityState(), error);
        }
        node = findNode(condition);
        check(node != null, step + " event arrived without the expected node | "
                + describeAccessibilityState());
        return node;
    }

    private AccessibilityNodeInfo findNode(NodeCondition condition) {
        for (AccessibilityWindowInfo window : automation().getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            window.recycle();
            if (root == null) {
                continue;
            }
            AccessibilityNodeInfo found = findNode(root, condition);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo findNode(
            AccessibilityNodeInfo node,
            NodeCondition condition) {
        if (condition.matches(node)) {
            return node;
        }
        for (int index = 0; index < node.getChildCount(); ++index) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) {
                continue;
            }
            AccessibilityNodeInfo found = findNode(child, condition);
            if (found != null) {
                node.recycle();
                return found;
            }
        }
        node.recycle();
        return null;
    }

    private String describeAccessibilityState() {
        Set<String> packages = new LinkedHashSet<>();
        for (AccessibilityWindowInfo window : automation().getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            window.recycle();
            if (root != null) {
                packages.add(stringOf(root.getPackageName()));
                root.recycle();
            }
        }
        String currentIme;
        try {
            currentIme = currentInputMethod();
        } catch (IOException error) {
            currentIme = "unavailable(" + error.getMessage() + ")";
        }
        return "packages=" + packages + ", currentIme=" + currentIme;
    }

    private static boolean hasDescription(AccessibilityNodeInfo node, String description) {
        return Objects.equals(description, stringOf(node.getContentDescription()));
    }

    private static boolean hasViewId(AccessibilityNodeInfo node, String viewId) {
        return Objects.equals(viewId, node.getViewIdResourceName());
    }

    private static boolean packageIs(AccessibilityNodeInfo node, String packageName) {
        return Objects.equals(packageName, stringOf(node.getPackageName()));
    }

    private static String stringOf(CharSequence value) {
        return value == null ? null : value.toString();
    }

    private String currentInputMethod() throws IOException {
        String state = runShell("dumpsys input_method");
        String key = "mCurMethodId=";
        int start = state.indexOf(key);
        check(start >= 0, "Input method state did not contain " + key);
        start += key.length();
        int end = start;
        while (end < state.length() && !Character.isWhitespace(state.charAt(end))) {
            ++end;
        }
        return state.substring(start, end);
    }


    private String runShell(String command) throws IOException {
        ParcelFileDescriptor output = automation().executeShellCommand(command);
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(output)) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private MainActivity launchActivity() {
        return (MainActivity) startActivitySync(activityIntent());
    }

    private static VoiceRuntime runtimeOf(MainActivity activity)
            throws ReflectiveOperationException {
        Field field = MainActivity.class.getDeclaredField("runtime");
        field.setAccessible(true);
        return (VoiceRuntime) field.get(activity);
    }

    private static VoiceRuntime.Stage stateOf(MainActivity activity)
            throws ReflectiveOperationException {
        return runtimeOf(activity).stage();
    }

    private Intent activityIntent() {
        return new Intent(getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private void finishActivity(Activity activity) {
        if (activity != null && !activity.isFinishing()) {
            runOnMainSync(activity::finish);
            waitForIdleSync();
        }
    }

    private File assertInstalledModel() throws IOException {
        File model = installedModel();
        check(model.isFile(), "Install the pinned model in app-private storage before this test");
        checkEquals(ModelStore.MODEL_SIZE, Files.size(model.toPath()), "installed model size");
        return model;
    }

    private File installedModel() {
        return new File(
                getTargetContext().getFilesDir(),
                "models/parakeet-unified-en-0.6b-Q8_0.gguf");
    }

    private void grantMicrophonePermission() throws IOException {
        String packageName = getTargetContext().getPackageName();
        ParcelFileDescriptor output = automation().executeShellCommand(
                "pm grant " + packageName + " " + Manifest.permission.RECORD_AUDIO);
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(output)) {
            input.readAllBytes();
        }
        checkEquals(
                PackageManager.PERMISSION_GRANTED,
                getTargetContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO),
                "microphone permission");
    }

    private void awaitView(
            Activity activity,
            View view,
            String step,
            String target,
            ViewCondition condition) throws Throwable {
        CountDownLatch reached = new CountDownLatch(1);
        AtomicReference<String> actualStates = new AtomicReference<>("not sampled");
        ViewTreeObserver.OnPreDrawListener listener = () -> {
            actualStates.set(describeActualStates(activity, view));
            if (condition.matches(view)) {
                reached.countDown();
            }
            return true;
        };
        runOnMainSync(() -> {
            view.getViewTreeObserver().addOnPreDrawListener(listener);
            actualStates.set(describeActualStates(activity, view));
            if (condition.matches(view)) {
                reached.countDown();
            }
            view.invalidate();
        });
        markStep(step + " | await=" + target + " | actual=" + actualStates.get());
        boolean matched = reached.await(UI_TRANSITION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        check(matched, step + " timed out | await=" + target + " | actual=" + actualStates.get());
        runOnMainSync(() -> {
            actualStates.set(describeActualStates(activity, view));
            if (view.getViewTreeObserver().isAlive()) {
                view.getViewTreeObserver().removeOnPreDrawListener(listener);
            }
        });
        markStep(step + " | reached=" + target + " | actual=" + actualStates.get());
    }

    private static String describeActualStates(Activity activity, View target) {
        String runtimeStage;
        try {
            runtimeStage = stateOf((MainActivity) activity).name();
        } catch (ReflectiveOperationException | ClassCastException | NullPointerException error) {
            runtimeStage = "unavailable(" + error.getClass().getSimpleName() + ")";
        }
        return "target{" + describeView(target)
                + "}, record{" + describeView(activity.findViewById(R.id.recordButton))
                + "}, stop{" + describeView(activity.findViewById(R.id.stopButton))
                + "}, cancel{" + describeView(activity.findViewById(R.id.cancelButton))
                + "}, runtimeStage=" + runtimeStage
                + ", orientation=" + activity.getResources().getConfiguration().orientation
                + ", finishing=" + activity.isFinishing()
                + ", destroyed=" + activity.isDestroyed();
    }

    private static String describeView(View view) {
        if (view == null) {
            return "missing";
        }
        String name;
        try {
            name = view.getResources().getResourceEntryName(view.getId());
        } catch (RuntimeException error) {
            name = Integer.toString(view.getId());
        }
        return "name=" + name
                + ", enabled=" + view.isEnabled()
                + ", visibility=" + view.getVisibility()
                + ", shown=" + view.isShown();
    }

    private void markStep(String step) {
        currentStep = step;
        Bundle status = new Bundle();
        status.putString("stream", "STEP " + step + '\n');
        sendStatus(0, status);
    }

    private void click(View view) {
        runOnMainSync(view::performClick);
        waitForIdleSync();
    }

    private static short[] readWavPcm16(AssetManager assets, String name) throws IOException {
        byte[] wav;
        try (InputStream input = assets.open(name)) {
            wav = input.readAllBytes();
        }
        ByteBuffer data = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (data.getInt() != 0x46464952 || data.getInt(8) != 0x45564157) {
            throw new IOException("Test audio is not a RIFF/WAVE file");
        }
        int offset = 12;
        int channels = 0;
        int sampleRate = 0;
        int bitsPerSample = 0;
        List<short[]> chunks = new ArrayList<>();
        int sampleCount = 0;
        while (offset + 8 <= wav.length) {
            int id = data.getInt(offset);
            int size = data.getInt(offset + 4);
            int content = offset + 8;
            if (content + size > wav.length) {
                throw new IOException("Test WAV chunk exceeds the file");
            }
            if (id == 0x20746d66) {
                channels = data.getShort(content + 2) & 0xffff;
                sampleRate = data.getInt(content + 4);
                bitsPerSample = data.getShort(content + 14) & 0xffff;
            } else if (id == 0x61746164) {
                short[] chunk = new short[size / Short.BYTES];
                for (int index = 0; index < chunk.length; ++index) {
                    chunk[index] = data.getShort(content + index * Short.BYTES);
                }
                chunks.add(chunk);
                sampleCount += chunk.length;
            }
            offset = content + size + (size & 1);
        }
        if (channels != 1 || sampleRate != 16_000 || bitsPerSample != 16 || sampleCount == 0) {
            throw new IOException("Test WAV must be non-empty 16 kHz mono PCM16");
        }
        short[] samples = new short[sampleCount];
        int destination = 0;
        for (short[] chunk : chunks) {
            System.arraycopy(chunk, 0, samples, destination, chunk.length);
            destination += chunk.length;
        }
        return samples;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void checkEquals(long expected, long actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void checkEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private record KeyboardFixture(RecordedJfkAudioFactory audioFactory) {}

    private final class EditorProtocolEngine
            implements RecordingController.Engine, RecordingController.AudioSourceFactory {
        private final BlockingQueue<ScriptedAudioSource> sources = new LinkedBlockingQueue<>();
        private volatile ScriptedAudioSource current;

        @Override
        public void load(String modelPath) {}
        @Override
        public void start() {}
        @Override
        public String feed(short[] pcm) {
            try {
                ScriptRevision revision = current.fed.take();
                return revision.text == null ? "" : revision.text;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Editor protocol feed interrupted", error);
            }
        }
        @Override
        public String finish() { return current.finalText; }
        @Override
        public void cancel() { if (current != null) current.stop(); }
        @Override
        public void reset() {}
        @Override
        public void close() { cancel(); }
        @Override
        public RecordingController.AudioSource create() {
            ScriptedAudioSource source = new ScriptedAudioSource(PixelVoiceTestRunner.this::foregroundRunning);
            current = source;
            sources.add(source);
            return source;
        }
        @Override
        public int blockSamples() { return 1; }

        ScriptedAudioSource awaitSource() throws InterruptedException {
            ScriptedAudioSource source = sources.poll(UI_TRANSITION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            check(source != null, "Editor protocol source did not start");
            return source;
        }
    }

    private record ScriptRevision(String text) {}

    private static final class ScriptedAudioSource implements RecordingController.AudioSource {
        private final BlockingQueue<ScriptRevision> requested = new LinkedBlockingQueue<>();
        private final BlockingQueue<ScriptRevision> fed = new LinkedBlockingQueue<>();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final CountDownLatch closed = new CountDownLatch(1);
        private final CheckedCondition foregroundRunning;
        private volatile String finalText = "";
        private volatile boolean foregroundAtClose;

        ScriptedAudioSource(CheckedCondition foregroundRunning) {
            this.foregroundRunning = foregroundRunning;
        }

        void submit(String text) { requested.add(new ScriptRevision(text)); }
        @Override
        public void start() {}
        @Override
        public int read(short[] destination) {
            try {
                ScriptRevision revision = requested.take();
                if (stopped.get() || revision.text == null) return 0;
                fed.add(revision);
                // One synthetic block carries one protocol revision, not native recognition evidence.
                destination[0] = 0;
                return 1;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Editor protocol source interrupted", error);
            }
        }
        @Override
        public void stop() {
            if (stopped.compareAndSet(false, true)) {
                requested.add(new ScriptRevision(null));
                fed.add(new ScriptRevision(null));
            }
        }
        @Override
        public void close() {
            stop();
            try {
                foregroundAtClose = foregroundRunning.matches();
            } catch (Throwable error) {
                foregroundAtClose = false;
            }
            closed.countDown();
        }
    }

    private static final class PreviewNativeEngine implements RecordingController.Engine {
        private final NativeEngine nativeEngine = new NativeEngine();
        private final RecordedJfkAudioFactory audio;

        PreviewNativeEngine(RecordedJfkAudioFactory audio) { this.audio = audio; }
        @Override
        public void load(String modelPath) { nativeEngine.load(modelPath); }
        @Override
        public void start() { nativeEngine.start(); }
        @Override
        public String feed(short[] pcm) {
            String text = nativeEngine.feed(pcm);
            audio.current.fed(text);
            return text;
        }
        @Override
        public String finish() { return nativeEngine.finish(); }
        @Override
        public void cancel() { nativeEngine.cancel(); }
        @Override
        public void reset() { nativeEngine.reset(); }
        @Override
        public void close() { nativeEngine.close(); }
    }

    private static final class RecordedJfkAudioFactory
            implements RecordingController.AudioSourceFactory {
        private static final int BLOCK_SAMPLES = 320;

        private final short[] samples;
        private final CheckedCondition foregroundRunning;
        private final BlockingQueue<RecordedJfkAudioSource> sources = new LinkedBlockingQueue<>();
        private volatile RecordedJfkAudioSource current;

        RecordedJfkAudioFactory(short[] samples, CheckedCondition foregroundRunning) {
            this.samples = samples;
            this.foregroundRunning = foregroundRunning;
        }

        @Override
        public RecordingController.AudioSource create() {
            RecordedJfkAudioSource source = new RecordedJfkAudioSource(samples, foregroundRunning);
            current = source;
            sources.add(source);
            return source;
        }

        @Override
        public int blockSamples() {
            return BLOCK_SAMPLES;
        }

        RecordedJfkAudioSource awaitSource() throws InterruptedException {
            RecordedJfkAudioSource source = sources.poll(
                    UI_TRANSITION_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
            check(source != null, "Timed out waiting for the recorded JFK audio source");
            return source;
        }
    }

    private static final class RecordedJfkAudioSource implements RecordingController.AudioSource {
        private final short[] samples;
        private final CheckedCondition foregroundRunning;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicBoolean foregroundAtClose = new AtomicBoolean();
        private final CountDownLatch firstBlock = new CountDownLatch(1);
        private final CountDownLatch allPublished = new CountDownLatch(1);
        private final CountDownLatch stopRequested = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final CountDownLatch preview = new CountDownLatch(1);
        private final Semaphore nextBlock = new Semaphore(1);
        private final AtomicBoolean continuing = new AtomicBoolean();
        private volatile String firstPartial;
        private int offset;

        RecordedJfkAudioSource(short[] samples, CheckedCondition foregroundRunning) {
            this.samples = samples;
            this.foregroundRunning = foregroundRunning;
        }

        @Override
        public void start() {
            check(started.compareAndSet(false, true), "Recorded JFK audio source started twice");
        }

        @Override
        public int read(short[] destination) {
            check(started.get(), "Recorded JFK audio source read before start");
            try {
                nextBlock.acquire();
                if (stopped.get()) return 0;
                if (offset < samples.length) {
                    int count = Math.min(destination.length, samples.length - offset);
                    System.arraycopy(samples, offset, destination, 0, count);
                    offset += count;
                    firstBlock.countDown();
                    if (offset == samples.length) allPublished.countDown();
                    return count;
                }
                stopRequested.await();
                return 0;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Recorded JFK audio source interrupted", error);
            }
        }

        @Override
        public void stop() {
            if (stopped.compareAndSet(false, true)) nextBlock.release();
            stopRequested.countDown();
        }

        @Override
        public void close() {
            stop();
            if (closed.getCount() != 0) {
                try {
                    foregroundAtClose.set(foregroundRunning.matches());
                } catch (Throwable error) {
                    foregroundAtClose.set(false);
                }
            }
            closed.countDown();
        }

        void fed(String text) {
            if (firstPartial == null && !text.isEmpty()) {
                firstPartial = text;
                preview.countDown();
                if (!continuing.get()) return;
            }
            nextBlock.release();
        }

        void continueAfterPreview() {
            if (continuing.compareAndSet(false, true)) nextBlock.release();
        }

        boolean awaitPreview() throws InterruptedException {
            return preview.await(UI_TRANSITION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        boolean awaitFirstBlock() throws InterruptedException {
            return firstBlock.await(UI_TRANSITION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        boolean awaitAllPublished() throws InterruptedException {
            return allPublished.await(UI_TRANSITION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        boolean awaitClosed() throws InterruptedException {
            return closed.await(UI_TRANSITION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        boolean foregroundAtClose() {
            return foregroundAtClose.get();
        }
    }

    private static final class SetupActivityObserver implements Application.ActivityLifecycleCallbacks {
        private final CountDownLatch available = new CountDownLatch(1);
        private final AtomicReference<MainActivity> owner = new AtomicReference<>();

        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

        @Override
        public void onActivityStarted(Activity activity) {
            capture(activity);
        }

        @Override
        public void onActivityResumed(Activity activity) {
            capture(activity);
        }

        @Override
        public void onActivityPaused(Activity activity) {}

        @Override
        public void onActivityStopped(Activity activity) {}

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

        @Override
        public void onActivityDestroyed(Activity activity) {}

        MainActivity awaitOwner() throws InterruptedException {
            check(available.await(UI_TRANSITION_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Setup Activity launch timed out");
            return owner.get();
        }

        private void capture(Activity activity) {
            if (activity instanceof MainActivity mainActivity) {
                owner.compareAndSet(null, mainActivity);
                available.countDown();
            }
        }
    }

    private static final class ResumeObserver implements Application.ActivityLifecycleCallbacks {
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final CountDownLatch resumed = new CountDownLatch(1);
        private final AtomicReference<MainActivity> owner = new AtomicReference<>();

        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

        @Override
        public void onActivityStarted(Activity activity) {}

        @Override
        public void onActivityResumed(Activity activity) {
            if (activity instanceof MainActivity mainActivity) {
                owner.compareAndSet(null, mainActivity);
                resumed.countDown();
            }
        }

        @Override
        public void onActivityPaused(Activity activity) {}

        @Override
        public void onActivityStopped(Activity activity) {
            if (activity instanceof MainActivity) {
                stopped.countDown();
            }
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

        @Override
        public void onActivityDestroyed(Activity activity) {}

        boolean awaitStop() throws InterruptedException {
            return stopped.await(UI_TRANSITION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        boolean awaitResume() throws InterruptedException {
            return resumed.await(UI_TRANSITION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        MainActivity owner() {
            return owner.get();
        }
    }

    private interface CheckedRunnable {
        void run() throws Throwable;
    }

    private interface NodeCondition {
        boolean matches(AccessibilityNodeInfo node);
    }

    private interface ViewCondition {
        boolean matches(View view);
    }

    private interface CheckedCondition {
        boolean matches() throws Throwable;
    }
}
