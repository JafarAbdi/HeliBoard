package dev.juruc.pixelvoice;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class ModelStore implements AutoCloseable {
    static final long MODEL_SIZE = 731_357_568L;
    static final String MODEL_SHA256 = "4b50b6dd862bf6e346929aaf4f5eaacec003bfa3f56462d6c874b41ef2f38795";
    static final String MODEL_URL = "https://huggingface.co/handy-computer/parakeet-unified-en-0.6b-gguf/resolve/"
            + "7e948f21b7bdbac698d3318db9d350f1096f3b6c/parakeet-unified-en-0.6b-Q8_0.gguf";
    private static final String MODEL_FILE = "parakeet-unified-en-0.6b-Q8_0.gguf";
    static final Artifact PRODUCTION_ARTIFACT =
            new Artifact(MODEL_FILE, MODEL_SIZE, MODEL_SHA256);

    record Artifact(String fileName, long size, String sha256) {
        Artifact {
            Objects.requireNonNull(fileName);
            Objects.requireNonNull(sha256);
            if (fileName.isBlank() || size < 0 || sha256.length() != 64) {
                throw new IllegalArgumentException("Invalid pinned artifact");
            }
        }
    }

    interface InputOpener {
        InputStream open() throws IOException;
    }

    interface Listener {
        void onChecking();

        void onMissing();

        void onProgress(long bytes, long totalBytes);

        void onReady(Path modelPath);

        void onCancelled();

        void onError(String message);
    }

    private static final class Operation {
        final long generation;
        final Path partial;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicReference<Closeable> activeIo = new AtomicReference<>();
        final AtomicReference<IOException> cancellationFailure = new AtomicReference<>();
        final AtomicReference<Long> latestProgress = new AtomicReference<>();
        final AtomicBoolean progressPosted = new AtomicBoolean();
        boolean published;

        Operation(long generation, Path partial) {
            this.generation = generation;
            this.partial = partial;
        }

        void register(Closeable resource) throws IOException, CancelledException {
            if (!activeIo.compareAndSet(null, resource)) {
                throw new IllegalStateException("Operation already owns an I/O resource");
            }
            if (cancelled.get()) {
                closeActive();
                throw new CancelledException();
            }
        }

        void closeActive() throws IOException {
            Closeable resource = activeIo.getAndSet(null);
            if (resource != null) {
                resource.close();
            }
        }

        void cancel() {
            cancelled.set(true);
            try {
                closeActive();
            } catch (IOException error) {
                cancellationFailure.compareAndSet(null, error);
            }
        }
    }

    private final Path directory;
    private final Path installedModel;
    private final Artifact artifact;
    private final Listener listener;
    private final Executor callbackExecutor;
    private final ExecutorService workers;
    private long generation;
    private Operation active;
    private boolean closed;

    ModelStore(Path filesDirectory, Listener listener, Executor callbackExecutor, Artifact artifact) {
        this.artifact = Objects.requireNonNull(artifact);
        directory = filesDirectory.resolve("models");
        installedModel = directory.resolve(artifact.fileName());
        this.listener = Objects.requireNonNull(listener);
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor);
        workers = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "pixel-voice-model-store");
            thread.setDaemon(true);
            return thread;
        });
    }

    synchronized void verifyInstalled() {
        Operation operation = replaceOperation();
        post(operation, listener::onChecking);
        workers.execute(() -> verifyInstalled(operation));
    }

    synchronized void importModel(InputOpener opener) {
        Objects.requireNonNull(opener);
        Operation operation = replaceOperation();
        workers.execute(() -> acquire(operation, opener));
    }

    void cancel() {
        Operation operation;
        long cancelGeneration;
        synchronized (this) {
            if (closed || active == null || active.published) {
                return;
            }
            operation = active;
            active = null;
            cancelGeneration = ++generation;
        }
        operation.cancel();
        callbackExecutor.execute(() -> {
            synchronized (ModelStore.this) {
                if (closed || generation != cancelGeneration || active != null) {
                    return;
                }
                IOException cancellationFailure = operation.cancellationFailure.get();
                if (cancellationFailure == null) {
                    listener.onCancelled();
                } else {
                    listener.onError(messageOf(cancellationFailure));
                }
            }
        });
    }

    private synchronized Operation replaceOperation() {
        if (closed) {
            throw new IllegalStateException("Model store is closed");
        }
        if (active != null) {
            active.cancel();
        }
        long operationGeneration = ++generation;
        Path partial = directory.resolve(
                "." + artifact.fileName() + "." + UUID.randomUUID() + ".part");
        Operation operation = new Operation(operationGeneration, partial);
        active = operation;
        return operation;
    }

    private void verifyInstalled(Operation operation) {
        try {
            Files.createDirectories(directory);
            checkActive(operation);
            if (!Files.isRegularFile(installedModel)) {
                postTerminal(operation, listener::onMissing);
                return;
            }
            verifyFile(installedModel, operation, false);
            postTerminal(operation, () -> listener.onReady(installedModel));
        } catch (CancelledException error) {
            postTerminal(operation, listener::onCancelled);
        } catch (Exception error) {
            postTerminal(operation, () -> listener.onError(messageOf(error)));
        }
    }

    private void acquire(Operation operation, InputOpener inputOpener) {
        try {
            Files.createDirectories(directory);
            checkActive(operation);
            InputStream opened = inputOpener.open();
            operation.register(opened);
            try (InputStream input = new BufferedInputStream(opened);
                    OutputStream output = new BufferedOutputStream(Files.newOutputStream(operation.partial))) {
                checkActive(operation);
                copy(operation, input, output);
            } finally {
                operation.closeActive();
            }
            checkActive(operation);
            verifyFile(operation.partial, operation, true);
            publish(operation);
            postTerminal(operation, () -> listener.onReady(installedModel));
        } catch (CancelledException error) {
            finishFailedAcquisition(operation, null, true);
        } catch (Exception error) {
            finishFailedAcquisition(operation, error, operation.cancelled.get());
        }
    }

    private void finishFailedAcquisition(Operation operation, Exception failure, boolean cancelled) {
        IOException cleanupFailure = deletePartial(operation.partial);
        IOException cancellationFailure = operation.cancellationFailure.get();
        Throwable error = combineFailures(failure, cancellationFailure);
        error = combineFailures(error, cleanupFailure);
        if (cancelled && error == null) {
            postTerminal(operation, listener::onCancelled);
        } else {
            Throwable reported = error == null ? new IOException("Model acquisition failed") : error;
            postTerminal(operation, () -> listener.onError(messageOf(reported)));
        }
    }

    private void copy(Operation operation, InputStream input, OutputStream output)
            throws IOException, CancelledException {
        byte[] buffer = new byte[64 * 1024];
        long copied = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            checkActive(operation);
            copied += count;
            if (copied > artifact.size()) {
                throw new IOException("Model is larger than the pinned file");
            }
            output.write(buffer, 0, count);
            progress(operation, copied);
        }
        output.flush();
        if (copied != artifact.size()) {
            throw new IOException("Model size is " + copied + " bytes, expected " + artifact.size());
        }
    }

    private void verifyFile(
            Path path,
            Operation operation,
            boolean reportProgress)
            throws IOException, NoSuchAlgorithmException, CancelledException {
        long size = Files.size(path);
        if (size != artifact.size()) {
            throw new IOException("Model size is " + size + " bytes, expected " + artifact.size());
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        long checked = 0;
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkActive(operation);
                digest.update(buffer, 0, count);
                checked += count;
                if (reportProgress) {
                    progress(operation, checked);
                }
            }
        }
        String actual = toHex(digest.digest());
        if (!artifact.sha256().equals(actual)) {
            throw new IOException("Model SHA-256 does not match the pinned file");
        }
    }

    private void progress(Operation operation, long bytes) {
        operation.latestProgress.set(bytes);
        scheduleProgress(operation);
    }

    private void scheduleProgress(Operation operation) {
        if (!operation.progressPosted.compareAndSet(false, true)) {
            return;
        }
        callbackExecutor.execute(() -> dispatchProgress(operation));
    }

    private void dispatchProgress(Operation operation) {
        Long bytes = operation.latestProgress.getAndSet(null);
        synchronized (this) {
            if (bytes != null && isActiveLocked(operation)) {
                listener.onProgress(bytes, artifact.size());
            }
        }
        operation.progressPosted.set(false);
        if (operation.latestProgress.get() != null) {
            scheduleProgress(operation);
        }
    }

    private void publish(Operation operation) throws IOException, CancelledException {
        synchronized (this) {
            checkActive(operation);
            try {
                Files.move(
                        operation.partial,
                        installedModel,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                operation.published = true;
            } catch (AtomicMoveNotSupportedException error) {
                throw new IOException("App storage does not support atomic model installation", error);
            }
        }
    }

    private void post(Operation operation, Runnable callback) {
        callbackExecutor.execute(() -> {
            synchronized (ModelStore.this) {
                if (isActiveLocked(operation)) {
                    callback.run();
                }
            }
        });
    }

    private void postTerminal(Operation operation, Runnable callback) {
        callbackExecutor.execute(() -> {
            synchronized (ModelStore.this) {
                if (!isActiveLocked(operation)) {
                    return;
                }
                if (operation.cancelled.get()) {
                    listener.onCancelled();
                } else {
                    callback.run();
                }
                if (isActiveLocked(operation)) {
                    active = null;
                }
            }
        });
    }

    private boolean isActiveLocked(Operation operation) {
        return !closed && active == operation && generation == operation.generation;
    }

    private synchronized void checkActive(Operation operation) throws CancelledException {
        if (!isActiveLocked(operation) || operation.cancelled.get()) {
            throw new CancelledException();
        }
    }

    private static IOException deletePartial(Path partial) {
        try {
            Files.deleteIfExists(partial);
            return null;
        } catch (IOException error) {
            return error;
        }
    }

    private static Throwable combineFailures(Throwable first, Throwable second) {
        if (first == null) {
            return second;
        }
        if (second != null && second != first) {
            first.addSuppressed(second);
        }
        return first;
    }

    private static String toHex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; ++index) {
            int value = bytes[index] & 0xff;
            result[index * 2] = digits[value >>> 4];
            result[index * 2 + 1] = digits[value & 0x0f];
        }
        return new String(result);
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    @Override
    public void close() {
        Operation operation;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            ++generation;
            operation = active;
            active = null;
        }
        if (operation != null) {
            operation.cancel();
        }
        workers.shutdownNow();
    }

    private static final class CancelledException extends Exception {}
}
