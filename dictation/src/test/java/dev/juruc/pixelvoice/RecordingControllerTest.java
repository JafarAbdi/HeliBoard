package dev.juruc.pixelvoice;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class RecordingControllerTest {
    private RecordingController controller;

    @After
    public void closeController() {
        if (controller != null) {
            controller.close();
        }
    }

    @Test
    public void stopDrainsEveryCapturedBlockBeforeFinalizing() throws Exception {
        FakeEngine engine = new FakeEngine();
        FakeAudioFactory audio = new FakeAudioFactory(new short[][] {{1, 2}, {3}, {4, 5, 6}});
        AtomicReference<String> finalText = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        ExecutorService inference = Executors.newSingleThreadExecutor();
        controller = new RecordingController(
                engine,
                audio,
                new ListenerAdapter() {
                    @Override
                    public void onState(long generation, RecordingController.State state) {
                        if (state == RecordingController.State.READY) {
                            ready.countDown();
                        }
                    }

                    @Override
                    public void onTranscript(long generation, String text, boolean isFinal) {
                        if (isFinal) {
                            finalText.set(text);
                            finished.countDown();
                        }
                    }
                },
                Runnable::run,
                inference);

        controller.loadModel("model.gguf");
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        controller.startRecording();
        assertTrue(audio.allBlocksRead.await(5, TimeUnit.SECONDS));
        controller.stop();
        assertTrue(finished.await(5, TimeUnit.SECONDS));

        assertEquals("final:6", finalText.get());
        assertEquals(3, engine.blocks.size());
        assertArrayEquals(new short[] {1, 2}, engine.blocks.get(0));
        assertArrayEquals(new short[] {3}, engine.blocks.get(1));
        assertArrayEquals(new short[] {4, 5, 6}, engine.blocks.get(2));
        assertEquals(RecordingController.State.READY, controller.state());
    }

    @Test
    public void cancelDoesNotReturnReadyUntilMicrophoneIsClosed() throws Exception {
        FakeEngine engine = new FakeEngine();
        BlockingCloseAudioFactory audio = new BlockingCloseAudioFactory();
        CountDownLatch initialReady = new CountDownLatch(1);
        CountDownLatch readyBeforeClose = new CountDownLatch(1);
        CountDownLatch readyAfterClose = new CountDownLatch(1);
        AtomicInteger readyCount = new AtomicInteger();
        ExecutorService inference = Executors.newSingleThreadExecutor();
        controller = new RecordingController(
                engine,
                audio,
                new ListenerAdapter() {
                    @Override
                    public void onState(long generation, RecordingController.State state) {
                        if (state != RecordingController.State.READY) {
                            return;
                        }
                        if (readyCount.incrementAndGet() == 1) {
                            initialReady.countDown();
                        } else if (audio.closed) {
                            readyAfterClose.countDown();
                        } else {
                            readyBeforeClose.countDown();
                        }
                    }
                },
                Runnable::run,
                inference);

        controller.loadModel("model.gguf");
        assertTrue(initialReady.await(5, TimeUnit.SECONDS));
        controller.startRecording();
        assertTrue(audio.readEntered.await(5, TimeUnit.SECONDS));
        controller.cancel();
        assertTrue(audio.closeEntered.await(5, TimeUnit.SECONDS));

        assertFalse("READY published while the microphone was still open",
                readyBeforeClose.await(1, TimeUnit.SECONDS));
        audio.allowClose.countDown();
        assertTrue(readyAfterClose.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void cancelDuringSourceInitializationWaitsForSourceClose() throws Exception {
        FakeEngine engine = new FakeEngine();
        DelayedAudioFactory audio = new DelayedAudioFactory();
        CountDownLatch initialReady = new CountDownLatch(1);
        CountDownLatch recoveredReady = new CountDownLatch(1);
        AtomicInteger readyCount = new AtomicInteger();
        controller = new RecordingController(
                engine,
                audio,
                new ListenerAdapter() {
                    @Override
                    public void onState(long generation, RecordingController.State state) {
                        if (state == RecordingController.State.READY) {
                            if (readyCount.incrementAndGet() == 1) {
                                initialReady.countDown();
                            } else {
                                recoveredReady.countDown();
                            }
                        }
                    }
                },
                Runnable::run);

        controller.loadModel("model.gguf");
        assertTrue(initialReady.await(5, TimeUnit.SECONDS));
        controller.startRecording();
        assertTrue(audio.createEntered.await(5, TimeUnit.SECONDS));
        controller.cancel();
        assertEquals(RecordingController.State.CANCELLING, controller.state());

        audio.allowCreate.countDown();
        assertTrue(audio.closed.await(5, TimeUnit.SECONDS));
        assertTrue(recoveredReady.await(5, TimeUnit.SECONDS));
        assertFalse(audio.started);
    }

    @Test
    public void stopAndCloseFailuresRecoverOnlyAfterCloseWasAttempted() throws Exception {
        FakeEngine engine = new FakeEngine();
        FailingShutdownAudioFactory audio = new FailingShutdownAudioFactory();
        CountDownLatch initialReady = new CountDownLatch(1);
        CountDownLatch recoveredReady = new CountDownLatch(1);
        CountDownLatch errorReported = new CountDownLatch(1);
        AtomicInteger readyCount = new AtomicInteger();
        AtomicReference<String> errorMessage = new AtomicReference<>();
        controller = new RecordingController(
                engine,
                audio,
                new ListenerAdapter() {
                    @Override
                    public void onState(long generation, RecordingController.State state) {
                        if (state == RecordingController.State.READY) {
                            if (readyCount.incrementAndGet() == 1) {
                                initialReady.countDown();
                            } else {
                                recoveredReady.countDown();
                            }
                        }
                    }

                    @Override
                    public void onError(long generation, String message) {
                        errorMessage.set(message);
                        errorReported.countDown();
                    }
                },
                Runnable::run);

        controller.loadModel("model.gguf");
        assertTrue(initialReady.await(5, TimeUnit.SECONDS));
        controller.startRecording();
        assertTrue(audio.readEntered.await(5, TimeUnit.SECONDS));
        controller.stop();
        assertTrue(errorReported.await(5, TimeUnit.SECONDS));

        assertTrue(audio.stopAttempted);
        assertTrue(audio.closeAttempted);
        assertTrue(recoveredReady.await(5, TimeUnit.SECONDS));
        assertEquals("close failed", errorMessage.get());
        assertEquals(RecordingController.State.READY, controller.state());
    }

    @Test
    public void captureFailureCanRetryWithoutReloadingModel() throws Exception {
        FakeEngine engine = new FakeEngine();
        RetryAudioFactory audio = new RetryAudioFactory();
        CountDownLatch firstReady = new CountDownLatch(1);
        CountDownLatch recoveredReady = new CountDownLatch(1);
        CountDownLatch retryReady = new CountDownLatch(1);
        CountDownLatch errorReported = new CountDownLatch(1);
        AtomicInteger readyCount = new AtomicInteger();
        AtomicReference<String> errorMessage = new AtomicReference<>();
        controller = new RecordingController(
                engine,
                audio,
                new ListenerAdapter() {
                    @Override
                    public void onState(long generation, RecordingController.State state) {
                        if (state != RecordingController.State.READY) {
                            return;
                        }
                        switch (readyCount.incrementAndGet()) {
                            case 1 -> firstReady.countDown();
                            case 2 -> recoveredReady.countDown();
                            case 3 -> retryReady.countDown();
                            default -> { }
                        }
                    }

                    @Override
                    public void onError(long generation, String message) {
                        errorMessage.set(message);
                        errorReported.countDown();
                    }
                },
                Runnable::run);

        controller.loadModel("model.gguf");
        assertTrue(firstReady.await(5, TimeUnit.SECONDS));
        controller.startRecording();
        assertTrue(errorReported.await(5, TimeUnit.SECONDS));
        assertTrue(recoveredReady.await(5, TimeUnit.SECONDS));
        assertEquals("Microphone read failed with code -7", errorMessage.get());

        controller.startRecording();
        assertTrue(audio.retryReadEntered.await(5, TimeUnit.SECONDS));
        controller.stop();
        assertTrue(retryReady.await(5, TimeUnit.SECONDS));
        assertEquals(1, engine.loadCount.get());
        assertTrue(engine.resetCount.get() >= 1);
        assertEquals(RecordingController.State.READY, controller.state());
    }

    @Test
    public void closeWaitsForMicrophoneCloseBeforeEngineClose() throws Exception {
        BlockingCloseAudioFactory audio = new BlockingCloseAudioFactory();
        CloseTrackingEngine engine = new CloseTrackingEngine(audio);
        CountDownLatch ready = new CountDownLatch(1);
        controller = new RecordingController(
                engine,
                audio,
                new ListenerAdapter() {
                    @Override
                    public void onState(long generation, RecordingController.State state) {
                        if (state == RecordingController.State.READY) {
                            ready.countDown();
                        }
                    }
                },
                Runnable::run);

        controller.loadModel("model.gguf");
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        controller.startRecording();
        assertTrue(audio.readEntered.await(5, TimeUnit.SECONDS));
        controller.close();
        controller = null;
        assertTrue(audio.closeEntered.await(5, TimeUnit.SECONDS));
        assertFalse(engine.closedBeforeMicrophone);

        audio.allowClose.countDown();
        assertTrue(engine.closed.await(5, TimeUnit.SECONDS));
        assertFalse(engine.closedBeforeMicrophone);
    }

    @Test
    public void cancelKeepsExistingVisibleTextAndReturnsReady() throws Exception {
        BlockingEngine engine = new BlockingEngine();
        FakeAudioFactory audio = new FakeAudioFactory(new short[][] {{7, 8}});
        AtomicReference<String> visibleText = new AtomicReference<>("Keep this text");
        AtomicReference<String> errorMessage = new AtomicReference<>();
        CountDownLatch firstReady = new CountDownLatch(1);
        CountDownLatch secondReady = new CountDownLatch(1);
        AtomicInteger readyCount = new AtomicInteger();
        ExecutorService inference = Executors.newSingleThreadExecutor();
        controller = new RecordingController(
                engine,
                audio,
                new ListenerAdapter() {
                    @Override
                    public void onState(long generation, RecordingController.State state) {
                        if (state == RecordingController.State.READY) {
                            if (readyCount.incrementAndGet() == 1) {
                                firstReady.countDown();
                            } else {
                                secondReady.countDown();
                            }
                        }
                    }

                    @Override
                    public void onTranscript(long generation, String text, boolean isFinal) {
                        visibleText.set(text);
                    }

                    @Override
                    public void onError(long generation, String message) {
                        errorMessage.set(message);
                    }
                },
                Runnable::run,
                inference);

        controller.loadModel("model.gguf");
        assertTrue(firstReady.await(5, TimeUnit.SECONDS));
        controller.startRecording();
        assertTrue(engine.feedEntered.await(5, TimeUnit.SECONDS));
        controller.cancel();
        assertTrue(secondReady.await(5, TimeUnit.SECONDS));
        inference.submit(() -> {}).get(5, TimeUnit.SECONDS);

        assertNull(errorMessage.get());
        assertEquals("Keep this text", visibleText.get());
        assertEquals(RecordingController.State.READY, controller.state());
        assertTrue(engine.cancelled);
    }

    @Test
    public void cancelDuringFinishKeepsPreviewWithoutReportingAbort() throws Exception {
        FinishBlockingEngine engine = new FinishBlockingEngine();
        FakeAudioFactory audio = new FakeAudioFactory(new short[][] {{9}});
        AtomicReference<String> visibleText = new AtomicReference<>("Previous text");
        AtomicReference<String> errorMessage = new AtomicReference<>();
        CountDownLatch firstReady = new CountDownLatch(1);
        CountDownLatch secondReady = new CountDownLatch(1);
        CountDownLatch preview = new CountDownLatch(1);
        AtomicInteger readyCount = new AtomicInteger();
        ExecutorService inference = Executors.newSingleThreadExecutor();
        controller = new RecordingController(
                engine,
                audio,
                new ListenerAdapter() {
                    @Override
                    public void onState(long generation, RecordingController.State state) {
                        if (state == RecordingController.State.READY) {
                            if (readyCount.incrementAndGet() == 1) {
                                firstReady.countDown();
                            } else {
                                secondReady.countDown();
                            }
                        }
                    }

                    @Override
                    public void onTranscript(long generation, String text, boolean isFinal) {
                        visibleText.set(text);
                        if (!isFinal) {
                            preview.countDown();
                        }
                    }

                    @Override
                    public void onError(long generation, String message) {
                        errorMessage.set(message);
                    }
                },
                Runnable::run,
                inference);

        controller.loadModel("model.gguf");
        assertTrue(firstReady.await(5, TimeUnit.SECONDS));
        controller.startRecording();
        assertTrue(preview.await(5, TimeUnit.SECONDS));
        controller.stop();
        assertTrue(engine.finishEntered.await(5, TimeUnit.SECONDS));
        controller.cancel();
        assertTrue(secondReady.await(5, TimeUnit.SECONDS));
        inference.submit(() -> {}).get(5, TimeUnit.SECONDS);

        assertNull(errorMessage.get());
        assertEquals("preview", visibleText.get());
        assertEquals(RecordingController.State.READY, controller.state());
        assertEquals(1, engine.resetCount.get());
        assertFalse(audio.sourceAlive);
    }

    @Test
    public void cancelAfterCaptureClosedOverridesQueuedFinish() throws Exception {
        BacklogBlockingEngine engine = new BacklogBlockingEngine();
        FakeAudioFactory audio = new FakeAudioFactory(new short[][] {{1}, {2}});
        AtomicReference<String> visibleText = new AtomicReference<>("Previous text");
        AtomicReference<String> errorMessage = new AtomicReference<>();
        CountDownLatch firstReady = new CountDownLatch(1);
        CountDownLatch secondReady = new CountDownLatch(1);
        CountDownLatch preview = new CountDownLatch(1);
        AtomicInteger readyCount = new AtomicInteger();
        ExecutorService inference = Executors.newSingleThreadExecutor();
        controller = new RecordingController(
                engine,
                audio,
                new ListenerAdapter() {
                    @Override
                    public void onState(long generation, RecordingController.State state) {
                        if (state == RecordingController.State.READY) {
                            if (readyCount.incrementAndGet() == 1) {
                                firstReady.countDown();
                            } else {
                                secondReady.countDown();
                            }
                        }
                    }

                    @Override
                    public void onTranscript(long generation, String text, boolean isFinal) {
                        visibleText.set(text);
                        if (!isFinal) {
                            preview.countDown();
                        }
                    }

                    @Override
                    public void onError(long generation, String message) {
                        errorMessage.set(message);
                    }
                },
                Runnable::run,
                inference);

        controller.loadModel("model.gguf");
        assertTrue(firstReady.await(5, TimeUnit.SECONDS));
        controller.startRecording();
        assertTrue(preview.await(5, TimeUnit.SECONDS));
        assertTrue(engine.backlogFeedEntered.await(5, TimeUnit.SECONDS));
        controller.stop();
        assertTrue(audio.closed.await(5, TimeUnit.SECONDS));
        controller.cancel();
        assertTrue(secondReady.await(5, TimeUnit.SECONDS));
        inference.submit(() -> {}).get(5, TimeUnit.SECONDS);

        assertNull(errorMessage.get());
        assertEquals("preview", visibleText.get());
        assertEquals(0, engine.finishCount.get());
        assertEquals(1, engine.resetCount.get());
        assertEquals(RecordingController.State.READY, controller.state());
        assertFalse(audio.sourceAlive);
    }

    private static class ListenerAdapter implements RecordingController.Listener {
        @Override
        public void onState(long generation, RecordingController.State state) {}

        @Override
        public void onTranscript(long generation, String text, boolean isFinal) {}

        @Override
        public void onError(long generation, String message) {
            throw new AssertionError(message);
        }
    }

    static class FakeEngine implements RecordingController.Engine {
        final List<short[]> blocks = new ArrayList<>();
        final CountDownLatch loaded = new CountDownLatch(1);
        final AtomicInteger loadCount = new AtomicInteger();
        final AtomicInteger resetCount = new AtomicInteger();
        volatile boolean cancelled;

        @Override
        public void load(String modelPath) {
            loadCount.incrementAndGet();
            loaded.countDown();
        }

        @Override
        public void start() {}

        @Override
        public String feed(short[] pcm) {
            blocks.add(pcm.clone());
            return "preview";
        }

        @Override
        public String finish() {
            int samples = blocks.stream().mapToInt(block -> block.length).sum();
            return "final:" + samples;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public void reset() {
            resetCount.incrementAndGet();
        }

        @Override
        public void close() {}
    }

    private static final class CloseTrackingEngine extends FakeEngine {
        final BlockingCloseAudioFactory audio;
        final CountDownLatch closed = new CountDownLatch(1);
        volatile boolean closedBeforeMicrophone;

        CloseTrackingEngine(BlockingCloseAudioFactory audio) {
            this.audio = audio;
        }

        @Override
        public void reset() {
            closedBeforeMicrophone |= !audio.closed;
            super.reset();
        }

        @Override
        public void close() {
            closedBeforeMicrophone |= !audio.closed;
            closed.countDown();
        }
    }

    private static final class BlockingEngine extends FakeEngine {
        final CountDownLatch feedEntered = new CountDownLatch(1);
        final CountDownLatch cancelSignal = new CountDownLatch(1);

        @Override
        public String feed(short[] pcm) {
            feedEntered.countDown();
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

    private static final class FinishBlockingEngine extends FakeEngine {
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

    private static final class BacklogBlockingEngine extends FakeEngine {
        final CountDownLatch backlogFeedEntered = new CountDownLatch(1);
        final CountDownLatch cancelSignal = new CountDownLatch(1);
        final AtomicInteger feedCount = new AtomicInteger();
        final AtomicInteger finishCount = new AtomicInteger();

        @Override
        public String feed(short[] pcm) {
            if (feedCount.incrementAndGet() == 1) {
                return super.feed(pcm);
            }
            backlogFeedEntered.countDown();
            try {
                cancelSignal.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("aborted");
        }

        @Override
        public String finish() {
            finishCount.incrementAndGet();
            return "unexpected final";
        }

        @Override
        public void cancel() {
            super.cancel();
            cancelSignal.countDown();
        }
    }

    private static final class DelayedAudioFactory
            implements RecordingController.AudioSourceFactory {
        final CountDownLatch createEntered = new CountDownLatch(1);
        final CountDownLatch allowCreate = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        volatile boolean started;

        @Override
        public RecordingController.AudioSource create() {
            createEntered.countDown();
            try {
                allowCreate.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            return new RecordingController.AudioSource() {
                @Override
                public void start() {
                    started = true;
                }

                @Override
                public int read(short[] destination) {
                    return 0;
                }

                @Override
                public void stop() {}

                @Override
                public void close() {
                    closed.countDown();
                }
            };
        }

        @Override
        public int blockSamples() {
            return 8;
        }
    }

    private static final class FailingShutdownAudioFactory
            implements RecordingController.AudioSourceFactory {
        final CountDownLatch readEntered = new CountDownLatch(1);
        final CountDownLatch stopped = new CountDownLatch(1);
        volatile boolean stopAttempted;
        volatile boolean closeAttempted;

        @Override
        public RecordingController.AudioSource create() {
            return new RecordingController.AudioSource() {
                @Override
                public void start() {}

                @Override
                public int read(short[] destination) {
                    readEntered.countDown();
                    try {
                        stopped.await();
                        return 0;
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return -1;
                    }
                }

                @Override
                public void stop() {
                    stopAttempted = true;
                    stopped.countDown();
                    throw new IllegalStateException("stop failed");
                }

                @Override
                public void close() {
                    closeAttempted = true;
                    throw new IllegalStateException("close failed");
                }
            };
        }

        @Override
        public int blockSamples() {
            return 8;
        }
    }

    private static final class RetryAudioFactory
            implements RecordingController.AudioSourceFactory {
        final AtomicInteger sources = new AtomicInteger();
        final CountDownLatch retryReadEntered = new CountDownLatch(1);
        final CountDownLatch retryStopped = new CountDownLatch(1);

        @Override
        public RecordingController.AudioSource create() {
            boolean fail = sources.getAndIncrement() == 0;
            return new RecordingController.AudioSource() {
                @Override
                public void start() {}

                @Override
                public int read(short[] destination) {
                    if (fail) {
                        return -7;
                    }
                    retryReadEntered.countDown();
                    try {
                        retryStopped.await();
                        return 0;
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return -1;
                    }
                }

                @Override
                public void stop() {
                    retryStopped.countDown();
                }

                @Override
                public void close() {
                    retryStopped.countDown();
                }
            };
        }

        @Override
        public int blockSamples() {
            return 8;
        }
    }

    private static final class BlockingCloseAudioFactory
            implements RecordingController.AudioSourceFactory {
        final CountDownLatch readEntered = new CountDownLatch(1);
        final CountDownLatch closeEntered = new CountDownLatch(1);
        final CountDownLatch allowClose = new CountDownLatch(1);
        final CountDownLatch stopped = new CountDownLatch(1);
        volatile boolean closed;

        @Override
        public RecordingController.AudioSource create() {
            return new RecordingController.AudioSource() {
                @Override
                public void start() {}

                @Override
                public int read(short[] destination) {
                    readEntered.countDown();
                    try {
                        stopped.await();
                        return 0;
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return -1;
                    }
                }

                @Override
                public void stop() {
                    stopped.countDown();
                }

                @Override
                public void close() {
                    closeEntered.countDown();
                    try {
                        allowClose.await();
                        closed = true;
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
        }

        @Override
        public int blockSamples() {
            return 8;
        }
    }

    static final class FakeAudioFactory implements RecordingController.AudioSourceFactory {
        final CountDownLatch allBlocksRead = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        private final short[][] blocks;
        volatile boolean sourceAlive;

        FakeAudioFactory(short[][] blocks) {
            this.blocks = blocks;
        }

        @Override
        public RecordingController.AudioSource create() {
            sourceAlive = true;
            return new RecordingController.AudioSource() {
                private final CountDownLatch stopped = new CountDownLatch(1);
                private int index;

                @Override
                public void start() {}

                @Override
                public int read(short[] destination) {
                    if (index < blocks.length) {
                        short[] block = blocks[index++];
                        System.arraycopy(block, 0, destination, 0, block.length);
                        if (index == blocks.length) {
                            allBlocksRead.countDown();
                        }
                        return block.length;
                    }
                    try {
                        stopped.await();
                        return 0;
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return -1;
                    }
                }

                @Override
                public void stop() {
                    stopped.countDown();
                }

                @Override
                public void close() {
                    sourceAlive = false;
                    closed.countDown();
                    stopped.countDown();
                }
            };
        }

        @Override
        public int blockSamples() {
            return 8;
        }
    }
}
