package dev.juruc.pixelvoice;

import android.app.Application;
import android.content.Context;

public class VoiceApplication extends Application {
    private VoiceRuntime runtime;
    private String reviewText;

    static VoiceApplication of(Context context) {
        return (VoiceApplication) context.getApplicationContext();
    }

    void retainReview(String text) {
        reviewText = text;
    }

    boolean hasReview() {
        return reviewText != null;
    }

    String takeReview() {
        String text = reviewText;
        reviewText = null;
        return text;
    }

    static VoiceRuntime runtimeOf(Context context) {
        return of(context).runtime();
    }

    private synchronized VoiceRuntime runtime() {
        if (runtime == null) {
            runtime = new VoiceRuntime(
                    getFilesDir().toPath(),
                    ModelStore.PRODUCTION_ARTIFACT,
                    new NativeEngine(),
                    new AndroidAudioSource.Factory(),
                    getMainExecutor());
        }
        return runtime;
    }
}
