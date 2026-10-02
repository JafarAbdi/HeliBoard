package dev.juruc.pixelvoice;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.ResultReceiver;
import android.os.UserManager;
import android.view.inputmethod.SurroundingText;

import java.io.PrintWriter;
import java.util.Objects;

/** One main-thread writer for the composing transaction, runtime lease and foreground ticket. */
public final class KeyboardDictation {
    public enum Status {
        IDLE, PREPARING, LISTENING, PROCESSING, NOT_INSERTED, CHECK_INSERTION, SETUP_NEEDED
    }

    public record Controls(Status status) {}

    public interface Host {
        SurroundingText surroundingText(int before, int after);

        /** Each command obtains the current connection. SENT still requires actual editor proof. */
        InlineDictation.DispatchOutcome apply(InlineDictation.Edit edit);

        void render(Controls controls);

        boolean controlsVisible();
    }

    private static final Controls HIDDEN = new Controls(Status.IDLE);
    private static final InlineDictation.Selection NO_SELECTION =
            new InlineDictation.Selection(-1, -1, -1, -1);

    private static final class Capture {
        final InlineDictation.Session session;
        VoiceRuntime.Lease lease;
        MicrophoneForegroundService.Ticket ticket;
        VoiceRuntime.Stage lastRuntimeCallback;

        Capture(InlineDictation.Session session) {
            this.session = session;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final InlineDictation dictation = new InlineDictation();
    private final Context context;
    private final Host host;
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            editorSurfaceChanged();
        }
    };
    private Capture capture;
    private boolean closed;
    private InlineDictation.Observation observation = new InlineDictation.Observation(0, NO_SELECTION);

    public KeyboardDictation(Context context, Host host) {
        this.context = Objects.requireNonNull(context);
        this.host = Objects.requireNonNull(host);
        context.registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF),
                Context.RECEIVER_NOT_EXPORTED);
    }

    public InlineDictation.Observation observation() {
        return observation;
    }

    public boolean ownsComposition() {
        return capture != null && dictation.ownsComposition();
    }

    public void editorStarted(int initialSelStart, int initialSelEnd) {
        observation = new InlineDictation.Observation(observation.sequence(),
                new InlineDictation.Selection(initialSelStart, initialSelEnd, -1, -1));
    }

    public void editorSurfaceChanged() {
        dictation.editorChanged();
        abandon();
    }

    public void editorChanged() {
        observation = new InlineDictation.Observation(observation.sequence(), NO_SELECTION);
        editorSurfaceChanged();
    }

    public void start(int inputType, String privateImeOptions) {
        if (closed || capture != null || !context.getSystemService(UserManager.class).isUserUnlocked()
                || !DictationTarget.acceptsDictation(inputType, privateImeOptions)) return;
        if (!microphoneGranted() || !context.getFilesDir().toPath().resolve("models")
                .resolve(ModelStore.PRODUCTION_ARTIFACT.fileName()).toFile().isFile()) {
            context.startActivity(MainActivity.setupIntent(context));
            return;
        }
        InlineDictation.Session session = dictation.begin(inputType, privateImeOptions,
                fresh(0), observation.selection());
        if (session == null) return;
        Capture owner = new Capture(session);
        capture = owner;
        drive(owner);
    }

    /** Foreground readiness and capture wait for a visible controls draw. */
    public void controlsDrawn() {
        Capture owner = capture;
        if (owner == null || owner.session.phase != InlineDictation.Phase.WAITING_CONTROLS
                || owner.session.waitingForAck()) return;
        if (!captureSafe(owner)) {
            abandon();
            return;
        }
        owner.session.phase = InlineDictation.Phase.WAITING_FOREGROUND;
        try {
            MicrophoneForegroundService.acquire(context, owner.session.id, new ResultReceiver(null) {
                @Override
                protected void onReceiveResult(int result, Bundle data) {
                    if (Looper.myLooper() == main.getLooper()) foregroundResult(owner, result, data);
                    else main.post(() -> foregroundResult(owner, result, data));
                }
            });
        } catch (RuntimeException error) {
            abandon();
        }
    }

    public void stop() {
        Capture owner = capture;
        if (owner == null) return;
        switch (owner.session.phase) {
            case LISTENING -> {
                if (!sourceSafe(owner) || !host.controlsVisible()) {
                    abandon();
                } else if (dictation.stop(owner.session.id)) {
                    render();
                    owner.lease.stop();
                }
            }
            case WAITING_CONTROLS, WAITING_FOREGROUND, PREPARING -> interrupt();
            case PROCESSING, RECOVERY, SETUP, CLOSED -> { }
        }
    }

    /** Explicit discard is distinct from interruption. Pending editor writes must acknowledge first. */
    public void cancel() {
        Capture owner = capture;
        if (owner == null) return;
        if (!dictation.ownsComposition()) {
            interrupt();
            return;
        }
        dictation.cancel(owner.session.id);
        retire(owner);
        drive(owner);
    }

    /** Called before an ordinary edit. Finishing composition keeps its text; it never replays it. */
    public void interrupt() {
        Capture owner = capture;
        if (owner == null) return;
        boolean currentEditor = editorSafe(owner);
        InlineDictation.Edit finish = dictation.interrupt(owner.session.id, observation.selection());
        capture = null;
        retire(owner);
        if (currentEditor && finish != null) apply(finish);
        host.render(HIDDEN);
    }

    private void abandon() {
        Capture owner = capture;
        capture = null;
        dictation.closeSession();
        if (owner != null) {
            retire(owner);
            host.render(HIDDEN);
        }
    }

    /** Called before Heli selection handling, including composing-range-only callbacks. */
    public boolean selectionChanged(int start, int end, int composingStart, int composingEnd) {
        observation = new InlineDictation.Observation(observation.sequence() + 1,
                new InlineDictation.Selection(start, end, composingStart, composingEnd));
        Capture owner = capture;
        if (owner == null) return false;
        boolean owned = dictation.ownsComposition();
        drive(owner);
        return owned && (dictation.ownsComposition()
                || (owner.session.phase == InlineDictation.Phase.CLOSED
                && owner.session.delivery == InlineDictation.Delivery.VERIFIED));
    }

    public void copy() {
        Capture owner = capture;
        if (owner == null || owner.session.recoveryText().isEmpty()) return;
        context.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(
                context.getString(R.string.transcript_hint), owner.session.recoveryText()));
        interrupt();
    }

    public void edit() {
        Capture owner = capture;
        if (owner == null || owner.session.recoveryText().isEmpty()) return;
        VoiceApplication.of(context).retainReview(owner.session.recoveryText());
        interrupt();
        context.startActivity(MainActivity.reviewIntent(context));
    }

    public void setup() {
        interrupt();
        context.startActivity(MainActivity.setupIntent(context));
    }

    public void close() {
        if (closed) return;
        closed = true;
        context.unregisterReceiver(screenOff);
        editorChanged();
    }

    public void dump(PrintWriter writer) {
        Capture owner = capture;
        writer.println("dictation closed=" + closed
                + " phase=" + (owner == null ? "NONE" : owner.session.phase)
                + " delivery=" + (owner == null ? "NONE" : owner.session.delivery)
                + " controlsVisible=" + host.controlsVisible()
                + " leasePresent=" + (owner != null && owner.lease != null)
                + " leaseActive=" + (owner != null && owner.lease != null && owner.lease.isActive())
                + " ticketPresent=" + (owner != null && owner.ticket != null)
                + " lastRuntimeCallback=" + (owner == null || owner.lastRuntimeCallback == null
                        ? "NONE" : owner.lastRuntimeCallback)
                + " draftLength=" + (owner == null ? 0 : owner.session.acknowledgedDraft().length())
                + " ackPending=" + (owner != null && owner.session.waitingForAck())
                + " terminal=" + (owner == null ? "NONE" : owner.session.terminalIntent));
    }

    private boolean microphoneGranted() {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean editorSafe(Capture owner) {
        return !closed && capture == owner && dictation.owns(owner.session.id)
                && context.getSystemService(PowerManager.class).isInteractive()
                && !context.getSystemService(KeyguardManager.class).isKeyguardLocked();
    }

    private boolean sourceSafe(Capture owner) {
        return editorSafe(owner) && microphoneGranted();
    }

    private boolean captureSafe(Capture owner) {
        return sourceSafe(owner) && host.controlsVisible()
                && dictation.confirmSnapshot(owner.session.id, observation.selection(),
                fresh(owner.session.readbackBefore()));
    }

    private InlineDictation.Readback fresh(int before) {
        try {
            SurroundingText text = host.surroundingText(before, 0);
            return text == null ? null : new InlineDictation.Readback(
                    text.getText() == null ? null : text.getText().toString(),
                    text.getSelectionStart(), text.getSelectionEnd(), text.getOffset());
        } catch (RuntimeException error) {
            return null;
        }
    }

    private void drive(Capture owner) {
        if (capture != owner) return;
        if (!sourceSafe(owner) || (owner.session.phase != InlineDictation.Phase.WAITING_CONTROLS
                && !host.controlsVisible())) {
            abandon();
            return;
        }
        while (capture == owner && dictation.ownsComposition()) {
            InlineDictation.Readback current = fresh(owner.session.readbackBefore());
            InlineDictation.Edit keep = dictation.observe(owner.session.id, observation.selection(), current);
            if (keep != null) apply(keep);
            InlineDictation.Edit edit = dictation.next(owner.session.id, observation.selection(), current);
            if (edit == null) break;
            InlineDictation.DispatchOutcome outcome = apply(edit);
            dictation.dispatched(owner.session.id, edit, outcome, observation.selection(),
                    outcome == InlineDictation.DispatchOutcome.SENT
                            ? fresh(owner.session.readbackBefore()) : null);
            if (owner.session.waitingForAck()) break;
        }
        if (capture != owner) return;
        if (owner.session.phase == InlineDictation.Phase.CLOSED) {
            abandon();
        } else {
            if (owner.session.phase == InlineDictation.Phase.RECOVERY) {
                retire(owner);
                if (!owner.session.recoveryText().isEmpty()) {
                    VoiceApplication.of(context).retainReview(owner.session.recoveryText());
                }
            }
            render();
        }
    }

    private InlineDictation.DispatchOutcome apply(InlineDictation.Edit edit) {
        try {
            return host.apply(edit);
        } catch (RuntimeException error) {
            return InlineDictation.DispatchOutcome.UNCERTAIN;
        }
    }

    private Controls controls() {
        Capture owner = capture;
        if (owner == null) return HIDDEN;
        return new Controls(switch (owner.session.phase) {
            case WAITING_CONTROLS, WAITING_FOREGROUND, PREPARING -> Status.PREPARING;
            case LISTENING -> Status.LISTENING;
            case PROCESSING -> Status.PROCESSING;
            case RECOVERY -> owner.session.delivery == InlineDictation.Delivery.UNKNOWN
                    ? Status.CHECK_INSERTION : Status.NOT_INSERTED;
            case SETUP -> Status.SETUP_NEEDED;
            case CLOSED -> Status.IDLE;
        });
    }

    private void render() {
        host.render(controls());
    }

    @androidx.annotation.RequiresApi(33)
    private void foregroundResult(Capture owner, int result, Bundle data) {
        if (result == MicrophoneForegroundService.READY) {
            MicrophoneForegroundService.Ticket ticket = new MicrophoneForegroundService.Ticket(data);
            if (capture != owner || owner.session.phase != InlineDictation.Phase.WAITING_FOREGROUND) {
                ticket.close();
                return;
            }
            owner.ticket = ticket;
            if (!captureSafe(owner)) {
                abandon();
                return;
            }
            owner.session.phase = InlineDictation.Phase.PREPARING;
            owner.lease = VoiceApplication.runtimeOf(context).acquire(new CaptureListener(owner));
            owner.lease.start();
            return;
        }
        if (capture != owner) return;
        switch (result) {
            case MicrophoneForegroundService.STOP -> stop();
            case MicrophoneForegroundService.CANCEL -> cancel();
            case MicrophoneForegroundService.LOST -> interrupt();
            default -> { }
        }
    }

    private static void retire(Capture owner) {
        MicrophoneForegroundService.Ticket ticket = owner.ticket;
        owner.ticket = null;
        Runnable release = () -> {
            if (ticket != null) ticket.close();
        };
        VoiceRuntime.Lease lease = owner.lease;
        owner.lease = null;
        if (lease != null) lease.closeAfterCapture(release);
        else release.run();
    }

    private void setupNeeded(Capture owner) {
        retire(owner);
        dictation.setupNeeded(owner.session.id);
        render();
    }

    private final class CaptureListener implements VoiceRuntime.Listener {
        private final Capture owner;

        CaptureListener(Capture owner) {
            this.owner = owner;
        }

        @Override
        public void onStage(VoiceRuntime.Stage stage) {
            // acquire() reports the current stage before the lease is assigned to the capture.
            main.post(() -> {
                if (capture != owner) return;
                owner.lastRuntimeCallback = stage;
                if (owner.lease == null) return;
                if (!sourceSafe(owner) || !host.controlsVisible()) {
                    abandon();
                    return;
                }
                switch (stage) {
                    case RECORDING -> {
                        dictation.listening(owner.session.id);
                        render();
                    }
                    case MODEL_MISSING, ERROR -> setupNeeded(owner);
                    case RELEASED -> editorSurfaceChanged();
                    case CHECKING_MODEL, ACQUIRING_MODEL, LOADING, FINISHING, CANCELLING, READY -> { }
                }
            });
        }

        @Override
        public void onModelProgress(boolean download, long bytes, long totalBytes) {}

        @Override
        public void onTranscript(String text, boolean isFinal) {
            if (capture != owner || owner.lease == null) return;
            dictation.revise(owner.session.id, text, isFinal);
            if (isFinal) retire(owner);
            if (isFinal || owner.session.waitingForAck() || owner.session.pendingFullRevision != null) {
                drive(owner);
            } else if (!sourceSafe(owner) || !host.controlsVisible()) {
                abandon();
            }
        }

        @Override
        public void onError(String message) {
            if (capture == owner && owner.lease != null) setupNeeded(owner);
        }
    }
}
