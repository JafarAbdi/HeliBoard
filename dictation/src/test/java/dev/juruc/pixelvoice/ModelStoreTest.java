package dev.juruc.pixelvoice;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class ModelStoreTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final List<ModelStore> stores = new ArrayList<>();

    @After
    public void closeStores() {
        for (ModelStore store : stores) {
            store.close();
        }
    }

    @Test
    public void invalidImportsPreservePreviouslyVerifiedModel() throws Exception {
        byte[] expected = "good".getBytes(StandardCharsets.UTF_8);
        TestListener listener = new TestListener();
        ModelStore store = store(listener, Runnable::run, artifact(expected));

        store.importModel(() -> new ByteArrayInputStream(expected));
        assertEquals("ready", listener.awaitTerminal());
        Path installed = listener.readyPath;
        assertArrayEquals(expected, Files.readAllBytes(installed));

        store.importModel(() -> new ByteArrayInputStream("bad".getBytes(StandardCharsets.UTF_8)));
        assertEquals("Model size is 3 bytes, expected 4", listener.awaitTerminal());
        assertArrayEquals(expected, Files.readAllBytes(installed));

        store.importModel(() -> new ByteArrayInputStream("evil".getBytes(StandardCharsets.UTF_8)));
        assertEquals("Model SHA-256 does not match the pinned file", listener.awaitTerminal());
        assertArrayEquals(expected, Files.readAllBytes(installed));

        store.verifyInstalled();
        assertEquals("ready", listener.awaitTerminal());
        assertArrayEquals(expected, Files.readAllBytes(installed));
    }

    @Test
    public void replacedBlockingImportCannotPublishOrHideNewProgress() throws Exception {
        byte[] expected = "new!".getBytes(StandardCharsets.UTF_8);
        QueuedExecutor callbacks = new QueuedExecutor();
        TestListener listener = new TestListener();
        ModelStore store = store(listener, callbacks, artifact(expected));
        CountDownLatch oldOpenEntered = new CountDownLatch(1);
        CountDownLatch releaseOldOpen = new CountDownLatch(1);
        CountDownLatch oldInputClosed = new CountDownLatch(1);

        store.importModel(() -> {
            oldOpenEntered.countDown();
            await(releaseOldOpen);
            return new ByteArrayInputStream("old?".getBytes(StandardCharsets.UTF_8)) {
                @Override
                public void close() throws IOException {
                    super.close();
                    oldInputClosed.countDown();
                }
            };
        });
        assertTrue(oldOpenEntered.await(5, TimeUnit.SECONDS));

        store.importModel(() -> new ByteArrayInputStream(expected));
        callbacks.runUntil(() -> listener.terminalCount.get() == 1);
        assertEquals("ready", listener.awaitTerminal());
        assertFalse(listener.progressBytes.isEmpty());
        assertTrue(listener.progressBytes.stream().allMatch(bytes -> bytes == 4L));

        releaseOldOpen.countDown();
        assertTrue(oldInputClosed.await(5, TimeUnit.SECONDS));
        callbacks.runAvailable();
        assertEquals(1, listener.terminalCount.get());
        assertArrayEquals(expected, Files.readAllBytes(listener.readyPath));
    }

    @Test
    public void closeDropsCallbacksFromBlockedImport() throws Exception {
        byte[] expected = "good".getBytes(StandardCharsets.UTF_8);
        TestListener listener = new TestListener();
        CountDownLatch callbackSubmitted = new CountDownLatch(1);
        Executor callbacks = runnable -> {
            callbackSubmitted.countDown();
            runnable.run();
        };
        ModelStore store = store(listener, callbacks, artifact(expected));
        CountDownLatch openEntered = new CountDownLatch(1);
        CountDownLatch releaseOpen = new CountDownLatch(1);

        store.importModel(() -> {
            openEntered.countDown();
            await(releaseOpen);
            return new ByteArrayInputStream(expected);
        });
        assertTrue(openEntered.await(5, TimeUnit.SECONDS));
        store.close();
        stores.remove(store);
        releaseOpen.countDown();
        assertTrue(callbackSubmitted.await(5, TimeUnit.SECONDS));

        assertEquals(0, listener.terminalCount.get());
        assertNull(listener.terminals.poll());
    }

    @Test
    public void concurrentStoresUseDifferentPartialFiles() throws Exception {
        byte[] expected = "good".getBytes(StandardCharsets.UTF_8);
        Path root = temporaryFolder.newFolder().toPath();
        TestListener firstListener = new TestListener();
        TestListener secondListener = new TestListener();
        ModelStore.Artifact artifact = artifact(expected);
        ModelStore first = new ModelStore(root, firstListener, Runnable::run, artifact);
        ModelStore second = new ModelStore(root, secondListener, Runnable::run, artifact);
        stores.add(first);
        stores.add(second);
        BlockingInput firstInput = new BlockingInput();
        BlockingInput secondInput = new BlockingInput();

        first.importModel(() -> firstInput);
        second.importModel(() -> secondInput);
        assertTrue(firstInput.blocked.await(5, TimeUnit.SECONDS));
        assertTrue(secondInput.blocked.await(5, TimeUnit.SECONDS));

        try (var paths = Files.list(root.resolve("models"))) {
            List<Path> partials = paths.filter(path -> path.getFileName().toString().endsWith(".part")).toList();
            assertEquals(2, partials.size());
            assertFalse(partials.get(0).equals(partials.get(1)));
        }

        first.cancel();
        second.cancel();
        assertTrue(firstInput.closed.await(5, TimeUnit.SECONDS));
        assertTrue(secondInput.closed.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void progressCallbacksAreCoalesced() throws Exception {
        byte[] expected = new byte[11_000];
        for (int index = 0; index < expected.length; ++index) {
            expected[index] = (byte) index;
        }
        QueuedExecutor callbacks = new QueuedExecutor();
        TestListener listener = new TestListener();
        ModelStore store = store(listener, callbacks, artifact(expected));

        store.importModel(() -> new OneByteInput(expected));
        assertTrue(callbacks.twoTasksSubmitted.await(5, TimeUnit.SECONDS));
        assertEquals(2, callbacks.size());
        callbacks.runUntil(() -> listener.terminalCount.get() == 1);

        assertEquals(List.of(11_000L), listener.progressBytes);
        assertEquals("ready", listener.awaitTerminal());
    }

    private ModelStore store(
            TestListener listener,
            Executor callbacks,
            ModelStore.Artifact artifact) throws IOException {
        ModelStore store = new ModelStore(temporaryFolder.newFolder().toPath(), listener, callbacks, artifact);
        stores.add(store);
        return store;
    }

    private static ModelStore.Artifact artifact(byte[] bytes) throws Exception {
        return new ModelStore.Artifact("model.gguf", bytes.length, sha256(bytes));
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while opening test input", error);
        }
    }

    private static final class TestListener implements ModelStore.Listener {
        final BlockingQueue<String> terminals = new LinkedBlockingQueue<>();
        final List<Long> progressBytes = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger terminalCount = new AtomicInteger();
        volatile Path readyPath;

        @Override
        public void onChecking() {}

        @Override
        public void onMissing() {
            terminal("missing");
        }

        @Override
        public void onProgress(long bytes, long totalBytes) {
            progressBytes.add(bytes);
        }

        @Override
        public void onReady(Path modelPath) {
            readyPath = modelPath;
            terminal("ready");
        }

        @Override
        public void onCancelled() {
            terminal("cancelled");
        }

        @Override
        public void onError(String message) {
            terminal(message);
        }

        String awaitTerminal() throws InterruptedException {
            String terminal = terminals.poll(5, TimeUnit.SECONDS);
            assertTrue("Timed out waiting for model-store terminal callback", terminal != null);
            return terminal;
        }

        private void terminal(String value) {
            terminalCount.incrementAndGet();
            terminals.add(value);
        }
    }

    private static final class QueuedExecutor implements Executor {
        final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
        final CountDownLatch twoTasksSubmitted = new CountDownLatch(2);

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
            twoTasksSubmitted.countDown();
        }

        int size() {
            return tasks.size();
        }

        void runUntil(Condition condition) throws Exception {
            while (!condition.done()) {
                Runnable task = tasks.poll(5, TimeUnit.SECONDS);
                assertTrue("Timed out waiting for queued callback", task != null);
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

    private interface Condition {
        boolean done();
    }

    private static final class BlockingInput extends InputStream {
        final CountDownLatch blocked = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicBoolean first = new AtomicBoolean(true);

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int count = read(one, 0, 1);
            return count == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] destination, int offset, int length) throws IOException {
            if (first.compareAndSet(true, false)) {
                destination[offset] = 1;
                return 1;
            }
            blocked.countDown();
            await(closed);
            throw new IOException("closed");
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class OneByteInput extends InputStream {
        private final byte[] bytes;
        private int index;

        OneByteInput(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int read() {
            return index == bytes.length ? -1 : bytes[index++] & 0xff;
        }

        @Override
        public int read(byte[] destination, int offset, int length) {
            if (index == bytes.length) {
                return -1;
            }
            destination[offset] = bytes[index++];
            return 1;
        }
    }
}
