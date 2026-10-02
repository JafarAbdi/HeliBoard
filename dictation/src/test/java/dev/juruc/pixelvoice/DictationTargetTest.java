package dev.juruc.pixelvoice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DictationTargetTest {
    private static final int TEXT = 0x00000001;
    private static final int TEXT_PASSWORD = 0x00000001 | 0x00000080;
    private static final int NUMBER_PASSWORD = 0x00000002 | 0x00000010;

    @Test
    public void passwordNullAndNoMicrophoneEditorsAreRejected() {
        assertTrue(DictationTarget.acceptsDictation(TEXT, null));
        assertTrue(DictationTarget.acceptsDictation(TEXT, "com.example.other"));
        assertFalse(DictationTarget.acceptsDictation(0, null));
        assertFalse(DictationTarget.acceptsDictation(TEXT_PASSWORD, null));
        assertFalse(DictationTarget.acceptsDictation(NUMBER_PASSWORD, null));
        assertFalse(DictationTarget.acceptsDictation(TEXT, "nm"));
        assertFalse(DictationTarget.acceptsDictation(TEXT, "foo, nm"));
        assertFalse(DictationTarget.acceptsDictation(TEXT, "com.example.app.noMicrophoneKey"));
    }
}
