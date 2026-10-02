package dev.juruc.pixelvoice;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

final class VoiceRuntime {
    enum Stage {
        CHECKING_MODEL,
        MODEL_MISSING,
        ACQUIRING_MODEL,
        LOADING,
        READY,
        RECORDING,
        FINISHING,
        CANCELLING,
        ERROR,
        RELEASED
    }

    interface Listener {
        void onStage(Stage stage);

        void onModelProgress(boolean download, long bytes, long totalBytes);

        void onTranscript(String text, boolean isFinal);

        void onError(String message);
    }

    final class Lease implements AutoCloseable {
        private final Listener listener;
        private CompletionStage<Void> captureClosure;

        private Lease(Listener listener) {
            this.listener = listener;
        }

        boolean isActive() {
            return current == this;
        }

        void start() {
            if (!isActive() || pendingStart != null) {
                return;
            }
            if (ownsOperation(this) && (engineState == RecordingController.State.RECORDING
                    || engineState == RecordingController.State.FINISHING)) {
                return;
            }
            pendingStart = this;
            uiExecutor.execute(VoiceRuntime.this::drainPendingStart);
        }

        void stop() {
            if (pendingStart == this) {
                pendingStart = null;
            }
            if (ownsOperation(this)) {
                controller.stop();
            }
        }

        void cancel() {
            if (!isActive()) {
                return;
            }
            if (pendingStart == this) {
                pendingStart = null;
            }
            if (ownsOperation(this)) {
                controller.cancel();
            }
        }

        void download() {
            if (isActive() && modelPhase == ModelPhase.MISSING) {
                beginAcquisition();
                modelStore.download();
            }
        }

        void importModel(ModelStore.InputOpener opener) {
            if (isActive() && modelPhase == ModelPhase.MISSING) {
                beginAcquisition();
                modelStore.importModel(opener);
            }
        }

        void cancelModelSetup() {
            if (isActive()) {
                modelStore.cancel();
            }
        }

        void closeAfterCapture(Runnable callback) {
            Objects.requireNonNull(callback);
            CompletionStage<Void> closure = captureClosure;
            release(this);
            if (closure == null) {
                uiExecutor.execute(callback);
            } else {
                closure.thenRunAsync(callback, uiExecutor);
            }
        }

        @Override
        public void close() {
            release(this);
        }
    }

    private enum ModelPhase {
        UNCHECKED,
        CHECKING,
        MISSING,
        ACQUIRING,
        VERIFIED
    }

    private record Operation(long generation, Lease owner) {}

    private final RecordingController controller;
    private final ModelStore modelStore;
    private final Executor uiExecutor;
    private ModelPhase modelPhase = ModelPhase.UNCHECKED;
    private Path verifiedModel;
    private RecordingController.State engineState = RecordingController.State.LOADING;
    private Operation operation;
    private Lease current;
    private Lease pendingStart;
    private Stage pushedStage;

    VoiceRuntime(
            Path filesDirectory,
            ModelStore.Artifact artifact,
            RecordingController.Engine engine,
            RecordingController.AudioSourceFactory audioFactory,
            Executor uiExecutor) {
        this.uiExecutor = Objects.requireNonNull(uiExecutor);
        controller = new RecordingController(engine, audioFactory, new EngineListener(), uiExecutor);
        modelStore = new ModelStore(filesDirectory, new ModelListener(), uiExecutor, artifact);
    }

    Lease acquire(Listener listener) {
        Objects.requireNonNull(listener);
        Lease previous = current;
        if (previous != null) {
            release(previous);
            previous.listener.onStage(Stage.RELEASED);
        }
        Lease lease = new Lease(listener);
        current = lease;
        ensureModel();
        pushedStage = stage();
        listener.onStage(pushedStage);
        return lease;
    }

    Stage stage() {
        return switch (modelPhase) {
            case UNCHECKED, CHECKING -> Stage.CHECKING_MODEL;
            case MISSING -> Stage.MODEL_MISSING;
            case ACQUIRING -> Stage.ACQUIRING_MODEL;
            case VERIFIED -> switch (engineState) {
                case LOADING -> Stage.LOADING;
                case READY -> Stage.READY;
                case ERROR -> Stage.ERROR;
                case RECORDING -> ownsOperation(current) ? Stage.RECORDING : Stage.CANCELLING;
                case FINISHING -> ownsOperation(current) ? Stage.FINISHING : Stage.CANCELLING;
                case CANCELLING -> Stage.CANCELLING;
            };
        };
    }

    private boolean ownsOperation(Lease lease) {
        return lease != null && lease == current && operation != null && operation.owner == lease;
    }

    private void release(Lease lease) {
        if (current != lease) {
            return;
        }
        if (pendingStart == lease) {
            pendingStart = null;
        }
        if (ownsOperation(lease)) {
            controller.cancel();
        }
        current = null;
        pushedStage = null;
    }

    private void ensureModel() {
        switch (modelPhase) {
            case UNCHECKED, MISSING -> {
                modelPhase = ModelPhase.CHECKING;
                modelStore.verifyInstalled();
            }
            case CHECKING, ACQUIRING -> { }
            case VERIFIED -> {
                if (engineState == RecordingController.State.ERROR) {
                    load();
                }
            }
        }
    }

    private void beginAcquisition() {
        modelPhase = ModelPhase.ACQUIRING;
        pushStage();
    }

    private void load() {
        long generation = controller.loadModel(verifiedModel.toString());
        operation = new Operation(generation, null);
        engineState = RecordingController.State.LOADING;
    }

    private void drainPendingStart() {
        Lease lease = pendingStart;
        if (lease == null || lease != current || stage() != Stage.READY) {
            return;
        }
        pendingStart = null;
        long generation = controller.startRecording();
        lease.captureClosure = controller.captureClosure(generation);
        operation = new Operation(generation, lease);
        engineState = RecordingController.State.RECORDING;
        pushStage();
    }

    private void pushStage() {
        Lease lease = current;
        if (lease == null) {
            return;
        }
        Stage stage = stage();
        if (stage == pushedStage) {
            return;
        }
        pushedStage = stage;
        lease.listener.onStage(stage);
    }

    private boolean isCurrentOperation(long generation) {
        return operation != null && operation.generation == generation;
    }

    private Lease resultTarget(long generation) {
        if (!isCurrentOperation(generation)) {
            return null;
        }
        Lease owner = operation.owner;
        if (owner == null) {
            return current;
        }
        return owner == current ? owner : null;
    }

    private final class EngineListener implements RecordingController.Listener {
        @Override
        public void onState(long generation, RecordingController.State state) {
            if (!isCurrentOperation(generation)) {
                return;
            }
            engineState = state;
            pushStage();
            if (state == RecordingController.State.READY) {
                uiExecutor.execute(VoiceRuntime.this::drainPendingStart);
            }
        }

        @Override
        public void onTranscript(long generation, String text, boolean isFinal) {
            Lease target = resultTarget(generation);
            if (target != null) {
                target.listener.onTranscript(text, isFinal);
            }
        }

        @Override
        public void onError(long generation, String message) {
            Lease target = resultTarget(generation);
            if (target != null) {
                target.listener.onError(message);
            }
        }
    }

    private final class ModelListener implements ModelStore.Listener {
        @Override
        public void onChecking() {
            modelPhase = ModelPhase.CHECKING;
            pushStage();
        }

        @Override
        public void onMissing() {
            modelPhase = ModelPhase.MISSING;
            pushStage();
        }

        @Override
        public void onProgress(boolean download, long bytes, long totalBytes) {
            if (current != null) {
                current.listener.onModelProgress(download, bytes, totalBytes);
            }
        }

        @Override
        public void onReady(Path modelPath) {
            modelPhase = ModelPhase.VERIFIED;
            verifiedModel = modelPath;
            if (operation == null || engineState == RecordingController.State.ERROR) {
                load();
            }
            pushStage();
        }

        @Override
        public void onCancelled() {
            modelPhase = ModelPhase.MISSING;
            pushStage();
        }

        @Override
        public void onError(String message) {
            modelPhase = ModelPhase.MISSING;
            pushStage();
            if (current != null) {
                current.listener.onError(message);
            }
        }
    }
}
