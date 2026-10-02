package dev.juruc.pixelvoice;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class RecordingController implements AutoCloseable {
    enum State {
        LOADING,
        READY,
        RECORDING,
        FINISHING,
        CANCELLING,
        ERROR
    }

    interface Engine extends AutoCloseable {
        void load(String modelPath);

        void start();

        String feed(short[] pcm);

        String finish();

        void cancel();

        void reset();

        @Override
        void close();
    }

    interface AudioSource extends AutoCloseable {
        void start();

        int read(short[] destination);

        void stop();

        @Override
        void close();
    }

    interface AudioSourceFactory {
        AudioSource create();

        int blockSamples();
    }

    interface Listener {
        void onState(long generation, State state);

        void onTranscript(long generation, String text, boolean isFinal);

        void onError(long generation, String message);
    }

    private enum Terminal {
        FINISH,
        CANCEL
    }

    private sealed interface CaptureMessage permits AudioBlock, CaptureEnded {}

    private record AudioBlock(short[] samples) implements CaptureMessage {}

    private record CaptureEnded(Throwable failure) implements CaptureMessage {}

    private record CaptureClosure(long generation, CompletionStage<Void> completion) {}

    private static final class Session {
        final long generation;
        final BlockingQueue<CaptureMessage> queue = new LinkedBlockingQueue<>();
        final AtomicBoolean captureRequested = new AtomicBoolean(true);
        final AtomicBoolean stopSignalled = new AtomicBoolean();
        final AtomicReference<Terminal> terminal = new AtomicReference<>(Terminal.FINISH);
        final AtomicReference<Throwable> endingFailure = new AtomicReference<>();
        final AtomicReference<CaptureEnded> captureResult = new AtomicReference<>();
        final CountDownLatch stopComplete = new CountDownLatch(1);
        final CountDownLatch captureClosed = new CountDownLatch(1);
        final CompletableFuture<Void> captureClosure = new CompletableFuture<>();
        volatile AudioSource source;

        Session(long generation) {
            this.generation = generation;
        }

        void requestCancel() {
            terminal.set(Terminal.CANCEL);
            captureRequested.set(false);
        }
    }

    private final Engine engine;
    private final AudioSourceFactory audioFactory;
    private final Listener listener;
    private final Executor uiExecutor;
    private final ExecutorService inferenceExecutor;
    private State state = State.LOADING;
    private long generation;
    private Session session;
    private CaptureClosure captureClosure;
    private boolean closed;

    RecordingController(
            Engine engine,
            AudioSourceFactory audioFactory,
            Listener listener,
            Executor uiExecutor) {
        this(engine, audioFactory, listener, uiExecutor, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pixel-voice-inference");
            thread.setDaemon(true);
            return thread;
        }));
    }

    RecordingController(
            Engine engine,
            AudioSourceFactory audioFactory,
            Listener listener,
            Executor uiExecutor,
            ExecutorService inferenceExecutor) {
        this.engine = Objects.requireNonNull(engine);
        this.audioFactory = Objects.requireNonNull(audioFactory);
        this.listener = Objects.requireNonNull(listener);
        this.uiExecutor = Objects.requireNonNull(uiExecutor);
        this.inferenceExecutor = Objects.requireNonNull(inferenceExecutor);
    }

    synchronized State state() {
        return state;
    }

    synchronized long loadModel(String modelPath) {
        requireOpen();
        if (state == State.RECORDING || state == State.FINISHING || state == State.CANCELLING) {
            throw new IllegalStateException("Cannot load a model during recording");
        }
        long loadGeneration = ++generation;
        setStateLocked(State.LOADING, loadGeneration);
        inferenceExecutor.execute(() -> {
            try {
                engine.load(modelPath);
                synchronized (RecordingController.this) {
                    if (generation != loadGeneration || closed) {
                        return;
                    }
                    setStateLocked(State.READY, loadGeneration);
                }
            } catch (Throwable error) {
                fail(loadGeneration, error);
            }
        });
        return loadGeneration;
    }

    synchronized long startRecording() {
        requireOpen();
        if (state != State.READY) {
            throw new IllegalStateException("Recorder is not ready");
        }
        Session next = new Session(++generation);
        session = next;
        captureClosure = new CaptureClosure(next.generation, next.captureClosure);
        setStateLocked(State.RECORDING, next.generation);

        inferenceExecutor.execute(() -> runInference(next));
        Thread captureThread = new Thread(() -> runCapture(next), "pixel-voice-capture");
        captureThread.setDaemon(true);
        captureThread.start();
        return next.generation;
    }

    synchronized CompletionStage<Void> captureClosure(long expectedGeneration) {
        if (captureClosure == null || captureClosure.generation != expectedGeneration) {
            throw new IllegalArgumentException("No capture for generation " + expectedGeneration);
        }
        return captureClosure.completion;
    }

    void stop() {
        Session current;
        synchronized (this) {
            if (state != State.RECORDING) {
                return;
            }
            current = session;
            current.terminal.set(Terminal.FINISH);
            current.captureRequested.set(false);
            setStateLocked(State.FINISHING, current.generation);
        }
        stopSource(current);
    }

    void cancel() {
        Session current;
        synchronized (this) {
            if (state != State.RECORDING && state != State.FINISHING) {
                return;
            }
            current = session;
            current.requestCancel();
            setStateLocked(State.CANCELLING, current.generation);
        }
        cancelEngine(current);
        stopSource(current);
    }

    private void runCapture(Session current) {
        AudioSource source = null;
        Throwable failure = null;
        try {
            source = audioFactory.create();
            current.source = source;
            if (current.captureRequested.get()) {
                source.start();
            }
            int blockSamples = audioFactory.blockSamples();
            if (blockSamples <= 0) {
                throw new IllegalStateException("Audio block size is invalid");
            }
            while (current.captureRequested.get()) {
                short[] buffer = new short[blockSamples];
                int count = source.read(buffer);
                boolean captureRequested = current.captureRequested.get();
                if (count < 0) {
                    if (captureRequested) {
                        throw new IllegalStateException("Microphone read failed with code " + count);
                    }
                    break;
                }
                if (count == 0) {
                    if (!captureRequested) {
                        break;
                    }
                    continue;
                }
                if (!captureRequested && current.terminal.get() == Terminal.CANCEL) {
                    break;
                }
                short[] owned = count == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, count);
                current.queue.put(new AudioBlock(owned));
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            if (current.captureRequested.get()) {
                failure = new IllegalStateException("Microphone capture was interrupted", error);
            }
        } catch (Throwable error) {
            if (current.captureRequested.get()) {
                failure = error;
            }
        } finally {
            try {
                failure = closeSource(source, failure);
                current.source = null;
                if (!current.captureRequested.get()) {
                    failure = awaitStopRequest(current, failure);
                }
                failure = combineFailures(failure, current.endingFailure.get());
                CaptureEnded ended = new CaptureEnded(failure);
                current.captureResult.set(ended);
                current.captureClosed.countDown();
                current.queue.offer(ended);
            } finally {
                current.captureClosure.complete(null);
            }
        }
    }

    private void runInference(Session current) {
        boolean cancellableCallInProgress = false;
        try {
            engine.start();
            while (true) {
                CaptureMessage message = current.queue.take();
                if (message instanceof AudioBlock block) {
                    if (current.terminal.get() != Terminal.CANCEL) {
                        cancellableCallInProgress = true;
                        String preview = engine.feed(block.samples());
                        cancellableCallInProgress = false;
                        if (current.terminal.get() != Terminal.CANCEL) {
                            postTranscript(current.generation, preview, false);
                        }
                    }
                    continue;
                }
                CaptureEnded ended = (CaptureEnded) message;
                if (ended.failure() != null) {
                    cancelEngine(current);
                    recover(current, combineFailures(ended.failure(), current.endingFailure.get()));
                } else if (current.terminal.get() == Terminal.CANCEL) {
                    completeCancellation(current);
                } else {
                    cancellableCallInProgress = true;
                    String finalText = engine.finish();
                    cancellableCallInProgress = false;
                    if (current.terminal.get() == Terminal.CANCEL) {
                        completeCancellation(current);
                    } else {
                        postTranscript(current.generation, finalText, true);
                        complete(current);
                    }
                }
                return;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            abortAndRecover(current, error);
        } catch (RuntimeException error) {
            if (cancellableCallInProgress && current.terminal.get() == Terminal.CANCEL) {
                completeCancellation(current);
            } else {
                abortAndRecover(current, error);
            }
        } catch (Error error) {
            abortAndRecover(current, error);
            throw error;
        }
    }

    private void completeCancellation(Session current) {
        CaptureEnded ended;
        try {
            ended = awaitCaptureEnd(current);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            recover(current, combineFailures(error, current.endingFailure.get()));
            return;
        }
        Throwable failure = combineFailures(ended.failure(), current.endingFailure.get());
        if (failure != null) {
            recover(current, failure);
            return;
        }
        try {
            engine.reset();
        } catch (RuntimeException error) {
            fail(current.generation, error);
            return;
        }
        complete(current);
    }

    private void abortAndRecover(Session current, Throwable failure) {
        current.requestCancel();
        cancelEngine(current);
        stopSource(current);
        try {
            CaptureEnded ended = awaitCaptureEnd(current);
            recover(current, combineFailures(failure, ended.failure()));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            recover(current, combineFailures(failure, error));
        }
    }

    private static CaptureEnded awaitCaptureEnd(Session current) throws InterruptedException {
        current.captureClosed.await();
        return current.captureResult.get();
    }

    private void recover(Session failed, Throwable error) {
        try {
            engine.reset();
        } catch (Throwable resetError) {
            fail(failed.generation, combineFailures(error, resetError));
            return;
        }
        synchronized (this) {
            if (session != failed || generation != failed.generation || closed) {
                return;
            }
            session = null;
            state = State.READY;
            String message = messageOf(error);
            post(failed.generation, () -> {
                listener.onState(failed.generation, State.READY);
                listener.onError(failed.generation, message);
            });
        }
    }

    private void complete(Session completed) {
        synchronized (this) {
            if (session != completed || generation != completed.generation || closed) {
                return;
            }
            session = null;
            setStateLocked(State.READY, completed.generation);
        }
    }

    private void fail(long expectedGeneration, Throwable error) {
        Session failedSession;
        synchronized (this) {
            if (generation != expectedGeneration || closed) {
                return;
            }
            failedSession = session;
            session = null;
            state = State.ERROR;
            if (failedSession != null) {
                failedSession.requestCancel();
            }
        }
        if (failedSession != null) {
            stopSource(failedSession);
        }
        post(expectedGeneration, () -> {
            listener.onState(expectedGeneration, State.ERROR);
            listener.onError(expectedGeneration, messageOf(error));
        });
    }

    private void setStateLocked(State next, long expectedGeneration) {
        state = next;
        post(expectedGeneration, () -> listener.onState(expectedGeneration, next));
    }

    private void postTranscript(long expectedGeneration, String text, boolean isFinal) {
        post(expectedGeneration, () -> listener.onTranscript(expectedGeneration, text, isFinal));
    }

    private void post(long expectedGeneration, Runnable callback) {
        uiExecutor.execute(() -> {
            synchronized (RecordingController.this) {
                if (closed || generation != expectedGeneration) {
                    return;
                }
            }
            callback.run();
        });
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private void cancelEngine(Session current) {
        try {
            engine.cancel();
        } catch (RuntimeException error) {
            current.endingFailure.compareAndSet(null, error);
        }
    }

    private static void stopSource(Session current) {
        if (!current.stopSignalled.compareAndSet(false, true)) {
            return;
        }
        try {
            AudioSource source = current.source;
            if (source != null) {
                source.stop();
            }
        } catch (RuntimeException error) {
            current.endingFailure.compareAndSet(null, error);
        } finally {
            current.stopComplete.countDown();
        }
    }

    private static Throwable closeSource(AudioSource source, Throwable failure) {
        if (source == null) {
            return failure;
        }
        try {
            source.close();
        } catch (RuntimeException error) {
            return combineFailures(failure, error);
        }
        return failure;
    }

    private static Throwable awaitStopRequest(Session current, Throwable failure) {
        try {
            current.stopComplete.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return combineFailures(failure, error);
        }
        return failure;
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

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Recorder is closed");
        }
    }

    @Override
    public void close() {
        Session current;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            ++generation;
            current = session;
            if (current != null) {
                current.requestCancel();
            }
        }
        if (current != null) {
            cancelEngine(current);
            stopSource(current);
        }
        inferenceExecutor.execute(() -> {
            try {
                engine.reset();
            } finally {
                engine.close();
            }
        });
        inferenceExecutor.shutdown();
    }
}
