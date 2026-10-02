package dev.juruc.pixelvoice;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

final class AndroidAudioSource implements RecordingController.AudioSource {
    static final class Factory implements RecordingController.AudioSourceFactory {
        private final int bufferBytes;

        Factory() {
            bufferBytes = AudioRecord.getMinBufferSize(
                    16_000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (bufferBytes <= 0) {
                throw new IllegalStateException("16 kHz mono PCM capture is unavailable");
            }
        }

        @Override
        public RecordingController.AudioSource create() {
            return new AndroidAudioSource(bufferBytes);
        }

        @Override
        public int blockSamples() {
            return bufferBytes / Short.BYTES;
        }
    }

    private final AudioRecord recorder;

    private AndroidAudioSource(int bufferBytes) {
        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(16_000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build();
        try {
            recorder = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .build();
        } catch (SecurityException error) {
            throw new IllegalStateException("Microphone permission was revoked", error);
        }
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            throw new IllegalStateException("Could not initialize the microphone");
        }
    }

    @Override
    public void start() {
        recorder.startRecording();
        if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            throw new IllegalStateException("Microphone did not start");
        }
    }

    @Override
    public int read(short[] destination) {
        return recorder.read(destination, 0, destination.length, AudioRecord.READ_BLOCKING);
    }

    @Override
    public void stop() {
        if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            recorder.stop();
        }
    }

    @Override
    public void close() {
        recorder.release();
    }
}
