package dev.juruc.pixelvoice;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;

public final class MainActivity extends Activity {
    static final String EXTRA_SETUP = "dev.juruc.pixelvoice.SETUP";
    private static final String EXTRA_REVIEW = "dev.juruc.pixelvoice.REVIEW";
    private static final int MICROPHONE_PERMISSION_REQUEST = 10;
    private static final int MODEL_FILE_REQUEST = 11;
    private static final String SAVED_TRANSCRIPT = "transcript";
    private static final String SAVED_RECORD_AFTER_PERMISSION = "recordAfterPermission";

    private TextView modelStatus;
    private TextView recordingStatus;
    private TextView setupHint;
    private ProgressBar modelProgress;
    private Button downloadButton;
    private Button importButton;
    private Button cancelModelButton;
    private Button recordButton;
    private Button stopButton;
    private Button cancelButton;
    private Button microphoneButton;
    private EditText transcript;
    private VoiceRuntime runtime;
    private VoiceRuntime.Lease lease;
    private VoiceRuntime.Stage stage = VoiceRuntime.Stage.CHECKING_MODEL;
    private boolean pickingModel;
    private boolean recordAfterPermission;

    static Intent setupIntent(Context context) {
        return new Intent(context, MainActivity.class)
                .putExtra(EXTRA_SETUP, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    static Intent reviewIntent(Context context) {
        return new Intent(context, MainActivity.class)
                .putExtra(EXTRA_REVIEW, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        if (savedInstanceState != null) {
            transcript.setText(savedInstanceState.getCharSequence(SAVED_TRANSCRIPT, ""));
            recordAfterPermission = savedInstanceState.getBoolean(SAVED_RECORD_AFTER_PERMISSION);
        }
        runtime = VoiceApplication.runtimeOf(this);
        applySetup(getIntent());

        downloadButton.setOnClickListener(view -> {
            modelStatus.setText(R.string.model_verifying);
            modelProgress.setProgress(0);
            lease().download();
        });
        importButton.setOnClickListener(view -> openModelPicker());
        cancelModelButton.setOnClickListener(view -> lease().cancelModelSetup());
        recordButton.setOnClickListener(view -> requestRecord());
        stopButton.setOnClickListener(view -> lease().stop());
        cancelButton.setOnClickListener(view -> lease().cancel());
        findViewById(R.id.copyButton).setOnClickListener(view -> copyTranscript());
        findViewById(R.id.reviewDictationButton).setOnClickListener(view -> reviewDictation());
        microphoneButton.setOnClickListener(view -> requestMicrophone(false));
        findViewById(R.id.enableKeyboardButton).setOnClickListener(
                view -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        findViewById(R.id.chooseKeyboardButton).setOnClickListener(
                view -> getSystemService(InputMethodManager.class).showInputMethodPicker());
        findViewById(R.id.keyboardSettingsButton).setOnClickListener(view -> openKeyboardSettings());

        lease = runtime.acquire(new RuntimeListener());
    }

    private void bindViews() {
        modelStatus = findViewById(R.id.modelStatus);
        recordingStatus = findViewById(R.id.recordingStatus);
        setupHint = findViewById(R.id.setupHint);
        modelProgress = findViewById(R.id.modelProgress);
        downloadButton = findViewById(R.id.downloadButton);
        importButton = findViewById(R.id.importButton);
        cancelModelButton = findViewById(R.id.cancelModelButton);
        recordButton = findViewById(R.id.recordButton);
        stopButton = findViewById(R.id.stopButton);
        cancelButton = findViewById(R.id.cancelButton);
        microphoneButton = findViewById(R.id.microphoneButton);
        transcript = findViewById(R.id.transcript);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applySetup(intent);
    }

    private void applySetup(Intent intent) {
        boolean setup = intent.getBooleanExtra(EXTRA_SETUP, false);
        setupHint.setVisibility(setup ? View.VISIBLE : View.GONE);
        if (setup || intent.getBooleanExtra(EXTRA_REVIEW, false)) {
            recordAfterPermission = false;
        }
        if (intent.getBooleanExtra(EXTRA_REVIEW, false)) {
            reviewDictation();
            intent.removeExtra(EXTRA_REVIEW);
        }
    }

    private void reviewDictation() {
        String text = VoiceApplication.of(this).takeReview();
        if (text != null) {
            transcript.setText(text);
            transcript.setSelection(transcript.length());
        }
        renderReview();
    }

    private void renderReview() {
        findViewById(R.id.reviewDictationButton).setVisibility(
                VoiceApplication.of(this).hasReview() ? View.VISIBLE : View.GONE);
    }

    private VoiceRuntime.Lease lease() {
        if (!lease.isActive()) {
            lease = runtime.acquire(new RuntimeListener());
        }
        return lease;
    }

    @Override
    protected void onStart() {
        super.onStart();
        lease();
        renderPermission();
    }

    private void openKeyboardSettings() {
        for (InputMethodInfo method : getSystemService(InputMethodManager.class).getInputMethodList()) {
            if (method.getPackageName().equals(getPackageName())
                    && method.getSettingsActivity() != null) {
                startActivity(new Intent().setClassName(this, method.getSettingsActivity()));
                return;
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderReview();
    }

    private boolean microphoneGranted() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestRecord() {
        if (microphoneGranted()) {
            lease().start();
            return;
        }
        requestMicrophone(true);
    }

    private void requestMicrophone(boolean recordAfterGrant) {
        recordAfterPermission = recordAfterGrant;
        requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, MICROPHONE_PERMISSION_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != MICROPHONE_PERMISSION_REQUEST) {
            return;
        }
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted && recordAfterPermission && stage == VoiceRuntime.Stage.READY) {
            lease().start();
        } else if (!granted) {
            Toast.makeText(this, R.string.microphone_permission_needed, Toast.LENGTH_LONG).show();
        }
        recordAfterPermission = false;
        renderPermission();
    }

    private void openModelPicker() {
        pickingModel = true;
        render();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        startActivityForResult(intent, MODEL_FILE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != MODEL_FILE_REQUEST) {
            return;
        }
        pickingModel = false;
        Uri uri = resultCode == RESULT_OK && data != null ? data.getData() : null;
        if (uri == null) {
            render();
            return;
        }
        modelStatus.setText(R.string.model_verifying);
        modelProgress.setProgress(0);
        lease().importModel(() -> {
            InputStream input = getContentResolver().openInputStream(uri);
            if (input == null) {
                throw new IOException("The selected model file could not be opened");
            }
            return input;
        });
    }

    private void renderPermission() {
        microphoneButton.setVisibility(microphoneGranted() ? View.GONE : View.VISIBLE);
    }

    private void render() {
        boolean modelStage = switch (stage) {
            case CHECKING_MODEL, MODEL_MISSING, ACQUIRING_MODEL -> true;
            default -> false;
        };
        boolean missing = stage == VoiceRuntime.Stage.MODEL_MISSING && !pickingModel;
        boolean acquiring = stage == VoiceRuntime.Stage.ACQUIRING_MODEL;
        boolean recording = stage == VoiceRuntime.Stage.RECORDING;
        boolean finishing = stage == VoiceRuntime.Stage.FINISHING;
        boolean idle = stage == VoiceRuntime.Stage.READY || stage == VoiceRuntime.Stage.RELEASED;

        downloadButton.setVisibility(modelStage ? View.VISIBLE : View.GONE);
        importButton.setVisibility(modelStage ? View.VISIBLE : View.GONE);
        downloadButton.setEnabled(missing);
        importButton.setEnabled(missing);
        cancelModelButton.setVisibility(acquiring ? View.VISIBLE : View.GONE);
        modelProgress.setVisibility(
                stage == VoiceRuntime.Stage.CHECKING_MODEL || acquiring ? View.VISIBLE : View.GONE);

        recordButton.setEnabled(idle);
        stopButton.setEnabled(recording);
        cancelButton.setEnabled(recording || finishing);
        transcript.setEnabled(idle || stage == VoiceRuntime.Stage.ERROR);
        findViewById(R.id.reviewDictationButton).setEnabled(idle || stage == VoiceRuntime.Stage.ERROR);
        getWindow().getDecorView().setKeepScreenOn(recording || finishing);

        int label = switch (stage) {
            case CHECKING_MODEL, MODEL_MISSING, ACQUIRING_MODEL -> R.string.state_model_required;
            case LOADING -> R.string.state_loading;
            case READY -> R.string.state_ready;
            case RECORDING -> R.string.state_recording;
            case FINISHING -> R.string.state_finishing;
            case CANCELLING -> R.string.state_cancelling;
            case ERROR -> R.string.state_error;
            case RELEASED -> R.string.state_released;
        };
        recordingStatus.setText(label);
    }

    private void copyTranscript() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.transcript_hint), transcript.getText()));
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putCharSequence(SAVED_TRANSCRIPT, transcript.getText());
        outState.putBoolean(SAVED_RECORD_AFTER_PERMISSION, recordAfterPermission);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onStop() {
        recordAfterPermission = false;
        if (lease.isActive()) {
            lease.stop();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        lease.close();
        super.onDestroy();
    }

    private final class RuntimeListener implements VoiceRuntime.Listener {
        @Override
        public void onStage(VoiceRuntime.Stage next) {
            stage = next;
            switch (next) {
                case CHECKING_MODEL -> {
                    modelStatus.setText(R.string.model_verifying);
                    modelProgress.setProgress(0);
                }
                case MODEL_MISSING -> modelStatus.setText(R.string.model_missing);
                case LOADING, READY, RECORDING, FINISHING, CANCELLING, ERROR ->
                        modelStatus.setText(R.string.model_ready);
                case ACQUIRING_MODEL, RELEASED -> { }
            }
            render();
        }

        @Override
        public void onModelProgress(boolean download, long bytes, long totalBytes) {
            int percent = (int) (bytes * 100 / totalBytes);
            modelProgress.setProgress(percent);
            modelStatus.setText(getString(
                    download ? R.string.model_downloading : R.string.model_importing,
                    percent));
        }

        @Override
        public void onTranscript(String text, boolean isFinal) {
            transcript.setText(text);
            transcript.setSelection(transcript.length());
            if (isFinal) {
                transcript.setEnabled(true);
            }
        }

        @Override
        public void onError(String message) {
            switch (stage) {
                case CHECKING_MODEL, MODEL_MISSING, ACQUIRING_MODEL ->
                        modelStatus.setText(getString(R.string.model_error, message));
                default -> recordingStatus.setText(getString(R.string.recording_error, message));
            }
        }
    }
}
