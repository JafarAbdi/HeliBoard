package dev.juruc.pixelvoice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public final class VoiceCaptureRetirementTest {
    private static final byte[] MODEL_BYTES = "tiny model".getBytes(StandardCharsets.UTF_8);

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final QueuedExecutor ui = new QueuedExecutor();
    private final List<ControlledAudioFactory> factories = new ArrayList<>();
    private final List<VoiceRuntime.Lease> leases = new ArrayList<>();
    private RecordingController controller;

    @After
    public void releaseResources() {
        for (ControlledAudioFactory factory : factories) {
            factory.allowCreate.countDown();
            for (ControlledSource source : factory.sources) {
                source.stop();
                source.allowClose.countDown();
            }
        }
        for (VoiceRuntime.Lease lease : leases) {
            lease.close();
        }
        if (controller != null) {
            controller.close();
        }
    }

    @Test
    public void preemptedLeaseRetiresOnlyItsCaptureAndNextStartWaitsForOldClosure()
            throws Exception {
        ControlledSource oldSource = new ControlledSource();
        ControlledSource freshSource = new ControlledSource();
        ControlledAudioFactory audio = audio(oldSource, freshSource);
        VoiceRuntime runtime = runtime(new RecordingControllerTest.FakeEngine(), audio);
        Surface old = new Surface();
        VoiceRuntime.Lease oldLease = acquire(runtime, old);
        ui.runUntil(() -> runtime.stage() == VoiceRuntime.Stage.READY);
        oldLease.start();
        ui.runUntil(() -> runtime.stage() == VoiceRuntime.Stage.RECORDING);
        await(oldSource.readEntered, "old capture did not read");

        Surface fresh = new Surface();
        VoiceRuntime.Lease freshLease = acquire(runtime, fresh);
        await(oldSource.closeEntered, "preemption did not close old capture");
        List<String> retirements = new ArrayList<>();
        Thread uiThread = Thread.currentThread();
        oldLease.closeAfterCapture(() -> {
            assertSame(uiThread, Thread.currentThread());
            retirements.add("old closed:" + oldSource.closed);
        });
        freshLease.start();
        ui.runAvailable();

        assertFalse(oldLease.isActive());
        assertEquals("stage:RELEASED", old.events.get(old.events.size() - 1));
        assertEquals(VoiceRuntime.Stage.CANCELLING, runtime.stage());
        assertEquals(1, audio.created.get());
        assertEquals(List.of(), retirements);

        oldSource.allowClose.countDown();
        ui.runUntil(() -> retirements.size() == 1
                && runtime.stage() == VoiceRuntime.Stage.RECORDING);
        await(freshSource.readEntered, "fresh capture did not read after old closure");
        assertEquals(List.of("old closed:true"), retirements);
        assertEquals(2, audio.created.get());

        oldLease.closeAfterCapture(() -> retirements.add("old again:" + oldSource.closed));
        ui.runUntil(() -> retirements.size() == 2);
        assertEquals(List.of("old closed:true", "old again:true"), retirements);
        assertTrue(freshLease.isActive());
        assertFalse(freshSource.closed);

        freshLease.closeAfterCapture(() -> retirements.add("fresh closed:" + freshSource.closed));
        await(freshSource.closeEntered, "fresh release did not close capture");
        ui.runAvailable();
        assertEquals(2, retirements.size());
        freshSource.allowClose.countDown();
        ui.runUntil(() -> retirements.size() == 3);
        ui.runUntil(() -> runtime.stage() == VoiceRuntime.Stage.READY);
        ui.runAvailable();
        assertEquals(List.of("old closed:true", "old again:true", "fresh closed:true"),
                retirements);
        assertEquals(List.of("stage:CANCELLING", "stage:READY", "stage:RECORDING"),
                fresh.events.stream().filter(event -> event.startsWith("stage:")).toList());
    }

    @Test
    public void pendingStartReleaseDisarmsBeforeNoCaptureRetirement() throws Exception {
        CountDownLatch allowLoad = new CountDownLatch(1);
        RecordingControllerTest.FakeEngine engine = new RecordingControllerTest.FakeEngine() {
            @Override
            public void load(String modelPath) {
                awaitSignal(allowLoad, "model load was not released");
                super.load(modelPath);
            }
        };
        ControlledAudioFactory audio = audio(new ControlledSource());
        VoiceRuntime runtime = runtime(engine, audio);
        VoiceRuntime.Lease lease = acquire(runtime, new Surface());
        ui.runUntil(() -> runtime.stage() == VoiceRuntime.Stage.LOADING);
        lease.start();
        List<String> retirements = new ArrayList<>();
        lease.closeAfterCapture(() -> retirements.add("active:" + lease.isActive()));
        lease.start();
        try {
            ui.runUntil(() -> retirements.size() == 1);
            assertEquals(List.of("active:false"), retirements);
            assertEquals(0, audio.created.get());
        } finally {
            allowLoad.countDown();
        }

        ui.runUntil(() -> runtime.stage() == VoiceRuntime.Stage.READY);
        ui.runAvailable();
        assertEquals(0, audio.created.get());
        VoiceRuntime.Lease fresh = acquire(runtime, new Surface());
        fresh.start();
        ui.runUntil(() -> runtime.stage() == VoiceRuntime.Stage.RECORDING);
        await(audio.sources.get(0).readEntered, "fresh lease did not capture");
        assertEquals(1, audio.created.get());
        assertEquals(List.of("active:false"), retirements);
    }

    @Test
    public void finalStopCanReleaseReentrantlyAndRetirementRunsOnceAfterSourceClose()
            throws Exception {
        ControlledSource source = new ControlledSource();
        VoiceRuntime runtime = runtime(new RecordingControllerTest.FakeEngine(), audio(source));
        Surface surface = new Surface();
        VoiceRuntime.Lease lease = acquire(runtime, surface);
        List<String> retirements = new ArrayList<>();
        surface.onFinal = () -> {
            lease.close();
            lease.closeAfterCapture(() -> retirements.add("closed:" + source.closed));
        };
        ui.runUntil(() -> runtime.stage() == VoiceRuntime.Stage.READY);
        lease.start();
        ui.runUntil(() -> surface.events.contains("preview:preview"));
        lease.stop();
        await(source.closeEntered, "Stop did not close capture");
        ui.runAvailable();
        assertEquals(List.of(), retirements);
        assertFalse(surface.events.contains("final:final:1"));

        source.allowClose.countDown();
        ui.runUntil(() -> retirements.size() == 1);
        ui.runUntil(() -> runtime.stage() == VoiceRuntime.Stage.READY);
        ui.runAvailable();
        assertFalse(lease.isActive());
        assertEquals(List.of("closed:true"), retirements);
        assertEquals(List.of("stage:CHECKING_MODEL", "stage:LOADING", "stage:READY",
                "stage:RECORDING", "preview:preview", "stage:FINISHING", "final:final:1"),
                surface.events);
    }

    @Test
    public void captureFailureClosureRemainsAvailableAfterRecoveryAndModelGenerationChange()
            throws Exception {
        ControlledSource source = new ControlledSource();
        source.failRead = true;
        Surface surface = controller(new RecordingControllerTest.FakeEngine(), audio(source));
        long generation = controller.startRecording();
        CompletionStage<Void> closure = controller.captureClosure(generation);
        await(source.closeEntered, "failed read did not close source");
        assertFalse(closure.toCompletableFuture().isDone());

        source.allowClose.countDown();
        ui.runUntil(() -> surface.events.contains("error:Microphone read failed with code -7"));
        closure.toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(RecordingController.State.READY, controller.state());
        assertSame(closure, controller.captureClosure(generation));
        assertTrue(source.closed);

        controller.loadModel("model.gguf");
        ui.runUntil(() -> surface.events.stream().filter("stage:READY"::equals).count() == 3);
        assertSame(closure, controller.captureClosure(generation));
        assertTrue(controller.captureClosure(generation).toCompletableFuture().isDone());
        assertThrows(IllegalArgumentException.class,
                () -> controller.captureClosure(generation + 1));
    }

    @Test
    public void closeAndResetFailuresStillCompleteClosureAfterCloseReturns() throws Exception {
        ControlledSource source = new ControlledSource();
        source.failClose = true;
        RecordingControllerTest.FakeEngine engine = new RecordingControllerTest.FakeEngine() {
            @Override
            public void reset() {
                super.reset();
                if (resetCount.get() == 1) {
                    throw new IllegalStateException("reset failed");
                }
            }
        };
        Surface surface = controller(engine, audio(source));
        long generation = controller.startRecording();
        CompletionStage<Void> closure = controller.captureClosure(generation);
        await(source.readEntered, "capture did not read");
        controller.cancel();
        await(source.closeEntered, "cancel did not close source");
        assertFalse(closure.toCompletableFuture().isDone());

        source.allowClose.countDown();
        ui.runUntil(() -> surface.events.contains("error:close failed"));
        closure.toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(RecordingController.State.ERROR, controller.state());
        assertSame(closure, controller.captureClosure(generation));
        assertTrue(source.closed);
        assertFalse(surface.events.contains("final:final:1"));
    }

    @Test
    public void cancelDuringConstructionWaitsForReturnedSourceToCloseWithoutStartingIt()
            throws Exception {
        ControlledSource source = new ControlledSource();
        ControlledAudioFactory audio = audio(source);
        audio.allowCreate = new CountDownLatch(1);
        VoiceRuntime runtime = runtime(new RecordingControllerTest.FakeEngine(), audio);
        VoiceRuntime.Lease lease = acquire(runtime, new Surface());
        ui.runUntil(() -> runtime.stage() == VoiceRuntime.Stage.READY);
        lease.start();
        ui.runUntil(() -> runtime.stage() == VoiceRuntime.Stage.RECORDING);
        await(audio.createEntered, "source construction did not begin");
        List<String> retirements = new ArrayList<>();
        lease.closeAfterCapture(() -> retirements.add("closed:" + source.closed));
        ui.runAvailable();
        assertEquals(List.of(), retirements);

        audio.allowCreate.countDown();
        await(source.closeEntered, "cancelled constructed source did not close");
        ui.runAvailable();
        assertFalse(source.started);
        assertEquals(List.of(), retirements);
        source.allowClose.countDown();
        ui.runUntil(() -> retirements.size() == 1);
        ui.runUntil(() -> runtime.stage() == VoiceRuntime.Stage.READY);
        ui.runAvailable();
        assertEquals(List.of("closed:true"), retirements);
    }

    @Test
    public void constructionFailureCompletesGenerationClosureWithoutASource() throws Exception {
        ControlledAudioFactory audio = audio();
        audio.creationFailure = new IllegalStateException("source construction failed");
        Surface surface = controller(new RecordingControllerTest.FakeEngine(), audio);
        long generation = controller.startRecording();
        ui.runUntil(() -> surface.events.contains("error:source construction failed"));
        controller.captureClosure(generation).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(RecordingController.State.READY, controller.state());
        assertEquals(1, audio.created.get());
        assertEquals(List.of("stage:LOADING", "stage:READY", "stage:RECORDING", "stage:READY",
                "error:source construction failed"), surface.events);
    }

    @Test
    public void interruptedInferenceReadyDoesNotCompleteClosureWhileSourceCloseIsBlocked()
            throws Exception {
        ControlledSource source = new ControlledSource();
        RecordingControllerTest.FakeEngine engine = new RecordingControllerTest.FakeEngine() {
            @Override
            public void start() {
                awaitSignal(source.readEntered, "capture did not read before inference interrupt");
                Thread.currentThread().interrupt();
            }
        };
        Surface surface = controller(engine, audio(source));
        long generation = controller.startRecording();
        await(source.closeEntered, "interrupted inference did not close source");
        ui.runUntil(() -> surface.events.contains("error:InterruptedException"));
        assertEquals(RecordingController.State.READY, controller.state());
        CompletionStage<Void> closure = controller.captureClosure(generation);
        List<String> retirements = new ArrayList<>();
        closure.thenRunAsync(() -> retirements.add("closed:" + source.closed), ui);
        ui.runAvailable();
        assertFalse(closure.toCompletableFuture().isDone());
        assertEquals(List.of(), retirements);

        source.allowClose.countDown();
        ui.runUntil(() -> retirements.size() == 1);
        assertEquals(List.of("closed:true"), retirements);
        assertEquals(List.of("stage:LOADING", "stage:READY", "stage:RECORDING", "stage:READY",
                "error:InterruptedException"), surface.events);
    }

    @Test
    public void controllerCloseDoesNotFilterCaptureClosureByUiGeneration() throws Exception {
        ControlledSource source = new ControlledSource();
        Surface surface = controller(new RecordingControllerTest.FakeEngine(), audio(source));
        long generation = controller.startRecording();
        CompletionStage<Void> closure = controller.captureClosure(generation);
        await(source.readEntered, "capture did not read");
        List<String> retirements = new ArrayList<>();
        closure.thenRunAsync(() -> retirements.add("closed:" + source.closed), ui);
        controller.close();
        await(source.closeEntered, "controller close did not close source");
        ui.runAvailable();
        assertEquals(List.of(), retirements);

        source.allowClose.countDown();
        ui.runUntil(() -> retirements.size() == 1);
        ui.runAvailable();
        assertEquals(List.of("closed:true"), retirements);
        assertSame(closure, controller.captureClosure(generation));
        assertFalse(surface.events.contains("final:final:1"));
    }

    private ControlledAudioFactory audio(ControlledSource... sources) {
        ControlledAudioFactory factory = new ControlledAudioFactory(List.of(sources));
        factories.add(factory);
        return factory;
    }

    private VoiceRuntime runtime(
            RecordingController.Engine engine, ControlledAudioFactory audio) throws Exception {
        Path files = temporaryFolder.newFolder().toPath();
        Files.createDirectories(files.resolve("models"));
        Files.write(files.resolve("models/model.gguf"), MODEL_BYTES);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(MODEL_BYTES);
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) {
            hex.append(String.format("%02x", value & 0xff));
        }
        ModelStore.Artifact artifact =
                new ModelStore.Artifact("model.gguf", MODEL_BYTES.length, hex.toString(), "unused");
        return new VoiceRuntime(files, artifact, engine, audio, ui);
    }

    private VoiceRuntime.Lease acquire(VoiceRuntime runtime, Surface surface) {
        VoiceRuntime.Lease lease = runtime.acquire(surface);
        leases.add(lease);
        return lease;
    }

    private Surface controller(
            RecordingController.Engine engine, ControlledAudioFactory audio) throws Exception {
        Surface surface = new Surface();
        controller = new RecordingController(engine, audio, new RecordingController.Listener() {
            @Override
            public void onState(long generation, RecordingController.State state) {
                surface.events.add("stage:" + state);
            }

            @Override
            public void onTranscript(long generation, String text, boolean isFinal) {
                surface.onTranscript(text, isFinal);
            }

            @Override
            public void onError(long generation, String message) {
                surface.onError(message);
            }
        }, ui);
        controller.loadModel("model.gguf");
        ui.runUntil(() -> surface.events.contains("stage:READY"));
        return surface;
    }

    private static void await(CountDownLatch signal, String message) throws InterruptedException {
        assertTrue(message, signal.await(5, TimeUnit.SECONDS));
    }

    private static void awaitSignal(CountDownLatch signal, String message) {
        try {
            await(signal, message);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, error);
        }
    }

    private static final class Surface implements VoiceRuntime.Listener {
        final List<String> events = new ArrayList<>();
        Runnable onFinal;

        @Override
        public void onStage(VoiceRuntime.Stage stage) {
            events.add("stage:" + stage);
        }

        @Override
        public void onModelProgress(boolean download, long bytes, long totalBytes) {}

        @Override
        public void onTranscript(String text, boolean isFinal) {
            events.add((isFinal ? "final:" : "preview:") + text);
            if (isFinal && onFinal != null) {
                onFinal.run();
            }
        }

        @Override
        public void onError(String message) {
            events.add("error:" + message);
        }
    }

    private static final class QueuedExecutor implements Executor {
        private final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runUntil(BooleanSupplier condition) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!condition.getAsBoolean()) {
                long remaining = deadline - System.nanoTime();
                Runnable task = remaining > 0 ? tasks.poll(remaining, TimeUnit.NANOSECONDS) : null;
                if (task == null) {
                    fail("Timed out waiting for capture retirement or a runtime callback");
                }
                task.run();
            }
        }

        void runAvailable() {
            Runnable task;
            while ((task = tasks.poll()) != null) {
                task.run();
            }
        }
    }

    private static final class ControlledAudioFactory
            implements RecordingController.AudioSourceFactory {
        final List<ControlledSource> sources;
        final AtomicInteger created = new AtomicInteger();
        final CountDownLatch createEntered = new CountDownLatch(1);
        CountDownLatch allowCreate = new CountDownLatch(0);
        RuntimeException creationFailure;

        ControlledAudioFactory(List<ControlledSource> sources) {
            this.sources = sources;
        }

        @Override
        public RecordingController.AudioSource create() {
            int index = created.getAndIncrement();
            createEntered.countDown();
            awaitSignal(allowCreate, "source construction was not released");
            if (creationFailure != null) {
                throw creationFailure;
            }
            return sources.get(index);
        }

        @Override
        public int blockSamples() {
            return 8;
        }
    }

    private static final class ControlledSource implements RecordingController.AudioSource {
        final CountDownLatch readEntered = new CountDownLatch(1);
        final CountDownLatch stopped = new CountDownLatch(1);
        final CountDownLatch closeEntered = new CountDownLatch(1);
        final CountDownLatch allowClose = new CountDownLatch(1);
        volatile boolean started;
        volatile boolean closed;
        boolean failRead;
        boolean failClose;
        private boolean sentBlock;

        @Override
        public void start() {
            started = true;
        }

        @Override
        public int read(short[] destination) {
            readEntered.countDown();
            if (failRead) {
                return -7;
            }
            if (!sentBlock) {
                sentBlock = true;
                destination[0] = 1;
                return 1;
            }
            awaitSignal(stopped, "source read was not stopped");
            return 0;
        }

        @Override
        public void stop() {
            stopped.countDown();
        }

        @Override
        public void close() {
            closeEntered.countDown();
            awaitSignal(allowClose, "source close was not released");
            closed = true;
            if (failClose) {
                throw new IllegalStateException("close failed");
            }
        }
    }
}
