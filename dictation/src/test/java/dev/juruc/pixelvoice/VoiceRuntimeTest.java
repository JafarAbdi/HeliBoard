package dev.juruc.pixelvoice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public final class VoiceRuntimeTest {
    private static final byte[] MODEL_BYTES = "tiny model".getBytes(StandardCharsets.UTF_8);

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final QueuedExecutor ui = new QueuedExecutor();

    @Test
    public void firstLeaseVerifiesAndLoadsOnceAndLaterLeasesSeeWarmEngine() throws Exception {
        SessionEngine engine = new SessionEngine();
        VoiceRuntime runtime = runtime(engine, new GatedAudioFactory(), true);
        Surface first = new Surface();
        VoiceRuntime.Lease firstLease = runtime.acquire(first);
        ui.runUntil(() -> first.events.contains("stage:READY"));

        Surface second = new Surface();
        VoiceRuntime.Lease secondLease = runtime.acquire(second);

        assertEquals(
                List.of("stage:CHECKING_MODEL", "stage:LOADING", "stage:READY", "stage:RELEASED"),
                first.events);
        assertEquals(List.of("stage:READY"), second.events);
        assertEquals(1, engine.loadCount.get());
        assertFalse(firstLease.isActive());
        assertTrue(secondLease.isActive());
    }

    @Test
    public void startWaitsForActualReadyCallbackThenDeliversOwnTranscript() throws Exception {
        GatedLoadEngine engine = new GatedLoadEngine();
        GatedAudioFactory audio = new GatedAudioFactory();
        VoiceRuntime runtime = runtime(engine, audio, true);
        Surface surface = new Surface();
        VoiceRuntime.Lease lease = runtime.acquire(surface);
        ui.runUntil(() -> surface.events.contains("stage:LOADING"));

        lease.start();
        ui.runAvailable();
        assertEquals(0, engine.startCount.get());

        engine.allowLoad.countDown();
        ui.runUntil(() -> surface.events.contains("stage:RECORDING"));
        audio.permits(0).release();
        ui.runUntil(() -> surface.events.contains("preview:preview"));
        lease.stop();
        ui.runUntil(() -> surface.events.contains("final:final:1"));
        ui.runUntil(() -> runtime.stage() == VoiceRuntime.Stage.READY);

        assertEquals(
                List.of(
                        "stage:CHECKING_MODEL",
                        "stage:LOADING",
                        "stage:READY",
                        "stage:RECORDING",
                        "preview:preview",
                        "stage:FINISHING",
                        "final:final:1",
                        "stage:READY"),
                surface.events);
        assertEquals(1, engine.startCount.get());
    }

    @Test
    public void preemptionCancelsOldSessionAndNeverRoutesItsCallbacksToNewLease() throws Exception {
        SessionEngine engine = new SessionEngine();
        GatedAudioFactory audio = new GatedAudioFactory();
        VoiceRuntime runtime = runtime(engine, audio, true);
        Surface old = new Surface();
        VoiceRuntime.Lease oldLease = runtime.acquire(old);
        ui.runUntil(() -> old.events.contains("stage:READY"));
        oldLease.start();
        ui.runUntil(() -> old.events.contains("stage:RECORDING"));
        ui.runAvailable();
        audio.permits(0).release();
        ui.awaitQueuedTask();

        Surface fresh = new Surface();
        VoiceRuntime.Lease freshLease = runtime.acquire(fresh);
        assertEquals("stage:RELEASED", old.events.get(old.events.size() - 1));
        assertEquals(List.of("stage:CANCELLING"), fresh.events);

        freshLease.start();
        ui.runUntil(() -> fresh.events.contains("stage:RECORDING"));
        oldLease.stop();
        oldLease.cancel();
        oldLease.start();
        audio.permits(1).release();
        ui.runUntil(() -> fresh.events.contains("preview:preview"));
        freshLease.stop();
        ui.runUntil(() -> fresh.events.contains("final:final:1"));
        ui.runUntil(() -> runtime.stage() == VoiceRuntime.Stage.READY);

        assertEquals(
                List.of(
                        "stage:CANCELLING",
                        "stage:READY",
                        "stage:RECORDING",
                        "preview:preview",
                        "stage:FINISHING",
                        "final:final:1",
                        "stage:READY"),
                fresh.events);
        assertEquals(
                List.of(
                        "stage:CHECKING_MODEL",
                        "stage:LOADING",
                        "stage:READY",
                        "stage:RECORDING",
                        "stage:RELEASED"),
                old.events);
        assertEquals(2, engine.startCount.get());
        assertEquals(1, engine.cancelCount.get());
    }

    @Test
    public void stopWhileStartIsPendingBehindOldCancellationNeverStartsSecondSession()
            throws Exception {
        GatedResetEngine engine = new GatedResetEngine();
        GatedAudioFactory audio = new GatedAudioFactory();
        VoiceRuntime runtime = runtime(engine, audio, true);
        Surface old = new Surface();
        VoiceRuntime.Lease oldLease = runtime.acquire(old);
        ui.runUntil(() -> old.events.contains("stage:READY"));
        oldLease.start();
        ui.runUntil(() -> old.events.contains("stage:RECORDING"));
        audio.permits(0).release();
        ui.runUntil(() -> old.events.contains("preview:preview"));

        Surface fresh = new Surface();
        VoiceRuntime.Lease freshLease = runtime.acquire(fresh);
        assertTrue(engine.resetEntered.await(5, TimeUnit.SECONDS));
        freshLease.start();
        ui.runAvailable();
        assertEquals(VoiceRuntime.Stage.CANCELLING, runtime.stage());

        freshLease.stop();
        engine.allowReset.countDown();
        ui.runUntil(() -> fresh.events.contains("stage:READY"));
        ui.runAvailable();

        assertEquals(List.of("stage:CANCELLING", "stage:READY"), fresh.events);
        assertEquals(VoiceRuntime.Stage.READY, runtime.stage());
        assertEquals(1, engine.startCount.get());
        assertEquals(1, audio.created());
        assertEquals(1, engine.cancelCount.get());
    }

    @Test
    public void releaseDuringFinishingDropsFinalResultAndReturnsEngineToReady() throws Exception {
        GatedFinishEngine engine = new GatedFinishEngine();
        GatedAudioFactory audio = new GatedAudioFactory();
        VoiceRuntime runtime = runtime(engine, audio, true);
        Surface surface = new Surface();
        VoiceRuntime.Lease lease = runtime.acquire(surface);
        ui.runUntil(() -> surface.events.contains("stage:READY"));
        lease.start();
        ui.runUntil(() -> surface.events.contains("stage:RECORDING"));
        audio.permits(0).release();
        ui.runUntil(() -> surface.events.contains("preview:preview"));
        lease.stop();
        ui.runUntil(() -> surface.events.contains("stage:FINISHING"));
        assertTrue(engine.finishEntered.await(5, TimeUnit.SECONDS));

        lease.close();
        ui.runUntil(() -> runtime.stage() == VoiceRuntime.Stage.READY);
        Surface next = new Surface();
        runtime.acquire(next);

        assertEquals(
                List.of(
                        "stage:CHECKING_MODEL",
                        "stage:LOADING",
                        "stage:READY",
                        "stage:RECORDING",
                        "preview:preview",
                        "stage:FINISHING"),
                surface.events);
        assertEquals(List.of("stage:READY"), next.events);
        assertEquals(1, engine.cancelCount.get());
        assertEquals(1, engine.resetCount.get());
    }

    @Test
    public void recoveryErrorReachesOwnerBeforeItsNextStartBegins() throws Exception {
        SessionEngine engine = new SessionEngine();
        GatedAudioFactory audio = new GatedAudioFactory();
        audio.failNextRead = true;
        VoiceRuntime runtime = runtime(engine, audio, true);
        Surface surface = new Surface();
        VoiceRuntime.Lease lease = runtime.acquire(surface);
        ui.runUntil(() -> surface.events.contains("stage:READY"));

        surface.startOnNextReady = lease;
        lease.start();
        ui.runUntil(() -> surface.events.stream().filter("stage:RECORDING"::equals).count() == 2);

        assertEquals(
                List.of(
                        "stage:CHECKING_MODEL",
                        "stage:LOADING",
                        "stage:READY",
                        "stage:RECORDING",
                        "stage:READY",
                        "error:Microphone read failed with code -7",
                        "stage:RECORDING"),
                surface.events);
        assertEquals(1, engine.loadCount.get());
    }

    @Test
    public void loadFailureIsReportedAndNextLeaseReloadsFromVerifiedPathWithoutRechecking()
            throws Exception {
        FailingFirstLoadEngine engine = new FailingFirstLoadEngine();
        VoiceRuntime runtime = runtime(engine, new GatedAudioFactory(), true);
        Surface first = new Surface();
        runtime.acquire(first);
        ui.runUntil(() -> first.events.contains("error:model load failed"));

        Surface second = new Surface();
        runtime.acquire(second);
        ui.runUntil(() -> second.events.contains("stage:READY"));

        assertEquals(
                List.of("stage:CHECKING_MODEL", "stage:LOADING", "stage:ERROR", "error:model load failed",
                        "stage:RELEASED"),
                first.events);
        assertEquals(List.of("stage:LOADING", "stage:READY"), second.events);
        assertEquals(2, engine.loadCount.get());
    }

    @Test
    public void missingModelHoldsStartUntilImportVerifiesAndLoads() throws Exception {
        SessionEngine engine = new SessionEngine();
        VoiceRuntime runtime = runtime(engine, new GatedAudioFactory(), false);
        Surface surface = new Surface();
        VoiceRuntime.Lease lease = runtime.acquire(surface);
        ui.runUntil(() -> surface.events.contains("stage:MODEL_MISSING"));

        lease.start();
        ui.runAvailable();
        assertEquals(0, engine.startCount.get());

        lease.importModel(() -> new ByteArrayInputStream(MODEL_BYTES));
        ui.runUntil(() -> surface.events.contains("stage:RECORDING"));

        assertEquals(
                List.of(
                        "stage:CHECKING_MODEL",
                        "stage:MODEL_MISSING",
                        "stage:ACQUIRING_MODEL",
                        "stage:LOADING",
                        "stage:READY",
                        "stage:RECORDING"),
                surface.events);
        assertEquals(Long.valueOf(MODEL_BYTES.length), surface.progress.get(surface.progress.size() - 1));
        assertEquals(1, engine.loadCount.get());
    }

    @Test
    public void installedModelRejectsAcquisitionWhileReadyAndRecording() throws Exception {
        SessionEngine engine = new SessionEngine();
        GatedAudioFactory audio = new GatedAudioFactory();
        VoiceRuntime runtime = runtime(engine, audio, true);
        Surface surface = new Surface();
        VoiceRuntime.Lease lease = runtime.acquire(surface);
        AtomicInteger opened = new AtomicInteger();
        ModelStore.InputOpener opener = () -> {
            opened.incrementAndGet();
            return new ByteArrayInputStream(MODEL_BYTES);
        };
        ui.runUntil(() -> surface.events.contains("stage:READY"));

        assertTrue(lease.isActive());
        lease.importModel(opener);
        assertEquals(VoiceRuntime.Stage.READY, runtime.stage());
        ui.runAvailable();
        assertEquals(0, opened.get());
        assertEquals(1, engine.loadCount.get());

        lease.start();
        ui.runUntil(() -> surface.events.contains("stage:RECORDING"));
        assertTrue(lease.isActive());
        lease.importModel(opener);
        assertEquals(VoiceRuntime.Stage.RECORDING, runtime.stage());
        ui.runAvailable();
        assertEquals(0, opened.get());
        assertEquals(1, engine.loadCount.get());

        audio.permits(0).release();
        ui.runUntil(() -> surface.events.contains("preview:preview"));
        lease.stop();
        ui.runUntil(() -> surface.events.contains("final:final:1"));
        ui.runUntil(() -> runtime.stage() == VoiceRuntime.Stage.READY);
        ui.runAvailable();

        assertEquals(
                List.of(
                        "stage:CHECKING_MODEL",
                        "stage:LOADING",
                        "stage:READY",
                        "stage:RECORDING",
                        "preview:preview",
                        "stage:FINISHING",
                        "final:final:1",
                        "stage:READY"),
                surface.events);
        assertEquals(0, opened.get());
        assertEquals(1, engine.loadCount.get());
    }

    @Test
    public void finalTranscriptCanReleaseLeaseReentrantlyAndLeaveEngineReady() throws Exception {
        SessionEngine engine = new SessionEngine();
        GatedAudioFactory audio = new GatedAudioFactory();
        VoiceRuntime runtime = runtime(engine, audio, true);
        Surface surface = new Surface();
        VoiceRuntime.Lease lease = runtime.acquire(surface);
        ui.runUntil(() -> surface.events.contains("stage:READY"));
        surface.closeOnFinal = lease;
        lease.start();
        ui.runUntil(() -> surface.events.contains("stage:RECORDING"));
        audio.permits(0).release();
        ui.runUntil(() -> surface.events.contains("preview:preview"));
        lease.stop();
        ui.runUntil(() -> surface.events.contains("final:final:1"));

        assertFalse(lease.isActive());
        ui.runUntil(() -> runtime.stage() == VoiceRuntime.Stage.READY);
        ui.runAvailable();
        Surface next = new Surface();
        VoiceRuntime.Lease nextLease = runtime.acquire(next);

        assertEquals(
                List.of(
                        "stage:CHECKING_MODEL",
                        "stage:LOADING",
                        "stage:READY",
                        "stage:RECORDING",
                        "preview:preview",
                        "stage:FINISHING",
                        "final:final:1"),
                surface.events);
        assertEquals(List.of("stage:READY"), next.events);
        assertTrue(nextLease.isActive());
        assertEquals(1, engine.loadCount.get());
    }

    private VoiceRuntime runtime(
            RecordingController.Engine engine,
            RecordingController.AudioSourceFactory audio,
            boolean installModel) throws Exception {
        Path files = temporaryFolder.newFolder().toPath();
        if (installModel) {
            Files.createDirectories(files.resolve("models"));
            Files.write(files.resolve("models/model.gguf"), MODEL_BYTES);
        }
        return new VoiceRuntime(files, artifact(), engine, audio, ui);
    }

    private static ModelStore.Artifact artifact() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(MODEL_BYTES);
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) {
            hex.append(String.format("%02x", value & 0xff));
        }
        return new ModelStore.Artifact("model.gguf", MODEL_BYTES.length, hex.toString());
    }

    private static final class Surface implements VoiceRuntime.Listener {
        final List<String> events = new ArrayList<>();
        final List<Long> progress = new ArrayList<>();
        VoiceRuntime.Lease startOnNextReady;
        VoiceRuntime.Lease closeOnFinal;

        @Override
        public void onStage(VoiceRuntime.Stage stage) {
            events.add("stage:" + stage);
            if (stage == VoiceRuntime.Stage.READY && startOnNextReady != null) {
                VoiceRuntime.Lease lease = startOnNextReady;
                startOnNextReady = null;
                lease.start();
            }
        }

        @Override
        public void onModelProgress(long bytes, long totalBytes) {
            progress.add(bytes);
        }

        @Override
        public void onTranscript(String text, boolean isFinal) {
            events.add((isFinal ? "final:" : "preview:") + text);
            if (isFinal && closeOnFinal != null) {
                closeOnFinal.close();
            }
        }

        @Override
        public void onError(String message) {
            events.add("error:" + message);
        }
    }

    private static final class QueuedExecutor implements Executor {
        private final BlockingDeque<Runnable> tasks = new LinkedBlockingDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void awaitQueuedTask() throws InterruptedException {
            Runnable task = tasks.pollFirst(5, TimeUnit.SECONDS);
            if (task == null) {
                fail("Timed out waiting for a runtime callback to be queued");
            }
            tasks.addFirst(task);
        }

        void runUntil(BooleanSupplier condition) throws InterruptedException {
            while (!condition.getAsBoolean()) {
                Runnable task = tasks.poll(5, TimeUnit.SECONDS);
                if (task == null) {
                    fail("Timed out waiting for a queued runtime callback");
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

    private static class SessionEngine extends RecordingControllerTest.FakeEngine {
        final AtomicInteger startCount = new AtomicInteger();
        final AtomicInteger cancelCount = new AtomicInteger();

        @Override
        public void start() {
            blocks.clear();
            startCount.incrementAndGet();
        }

        @Override
        public void cancel() {
            super.cancel();
            cancelCount.incrementAndGet();
        }
    }

    private static final class GatedLoadEngine extends SessionEngine {
        final CountDownLatch allowLoad = new CountDownLatch(1);

        @Override
        public void load(String modelPath) {
            try {
                allowLoad.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            super.load(modelPath);
        }
    }

    private static final class FailingFirstLoadEngine extends SessionEngine {
        @Override
        public void load(String modelPath) {
            super.load(modelPath);
            if (loadCount.get() == 1) {
                throw new IllegalStateException("model load failed");
            }
        }
    }

    private static final class GatedResetEngine extends SessionEngine {
        final CountDownLatch resetEntered = new CountDownLatch(1);
        final CountDownLatch allowReset = new CountDownLatch(1);

        @Override
        public void reset() {
            resetEntered.countDown();
            try {
                allowReset.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            super.reset();
        }
    }

    private static final class GatedFinishEngine extends SessionEngine {
        final CountDownLatch finishEntered = new CountDownLatch(1);
        final CountDownLatch cancelSignal = new CountDownLatch(1);

        @Override
        public String finish() {
            finishEntered.countDown();
            try {
                cancelSignal.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("aborted");
        }

        @Override
        public void cancel() {
            super.cancel();
            cancelSignal.countDown();
        }
    }

    private static final class GatedAudioFactory implements RecordingController.AudioSourceFactory {
        private final List<Semaphore> sessionPermits = new ArrayList<>();
        private int created;
        volatile boolean failNextRead;

        synchronized int created() {
            return created;
        }

        synchronized Semaphore permits(int session) {
            while (sessionPermits.size() <= session) {
                sessionPermits.add(new Semaphore(0));
            }
            return sessionPermits.get(session);
        }

        @Override
        public synchronized RecordingController.AudioSource create() {
            Semaphore permits = permits(created++);
            return new RecordingController.AudioSource() {
                private volatile boolean stopped;

                @Override
                public void start() {}

                @Override
                public int read(short[] destination) {
                    if (failNextRead) {
                        failNextRead = false;
                        return -7;
                    }
                    try {
                        permits.acquire();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return -1;
                    }
                    if (stopped) {
                        return 0;
                    }
                    destination[0] = 1;
                    return 1;
                }

                @Override
                public void stop() {
                    stopped = true;
                    permits.release();
                }

                @Override
                public void close() {
                    stop();
                }
            };
        }

        @Override
        public int blockSamples() {
            return 8;
        }
    }
}
