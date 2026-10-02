package dev.juruc.pixelvoice;

final class NativeEngine implements RecordingController.Engine, AutoCloseable {
    static {
        System.loadLibrary("pixelvoice_jni");
    }

    private volatile long handle = nativeCreate();

    @Override
    public void load(String modelPath) {
        nativeLoad(requireHandle(), modelPath);
    }

    @Override
    public void start() {
        nativeStart(requireHandle());
    }

    @Override
    public String feed(short[] pcm) {
        return nativeFeed(requireHandle(), pcm);
    }

    @Override
    public String finish() {
        return nativeFinish(requireHandle());
    }

    @Override
    public void cancel() {
        long current = handle;
        if (current != 0) {
            nativeCancel(current);
        }
    }

    @Override
    public void reset() {
        nativeReset(requireHandle());
    }

    @Override
    public synchronized void close() {
        long current = handle;
        if (current == 0) {
            return;
        }
        handle = 0;
        nativeDestroy(current);
    }

    private long requireHandle() {
        long current = handle;
        if (current == 0) {
            throw new IllegalStateException("Native engine is closed");
        }
        return current;
    }

    private static native long nativeCreate();

    private static native void nativeLoad(long handle, String modelPath);

    private static native void nativeStart(long handle);

    private static native String nativeFeed(long handle, short[] pcm);

    private static native String nativeFinish(long handle);

    private static native void nativeCancel(long handle);

    private static native void nativeReset(long handle);

    private static native void nativeDestroy(long handle);
}
