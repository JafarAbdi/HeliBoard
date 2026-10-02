package dev.juruc.pixelvoice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class InlineDictationSnapshotTest {
    private final InlineDictation dictation = new InlineDictation();
    private final InlineDictation.Selection original = new InlineDictation.Selection(9, 9, -1, -1);
    private final InlineDictation.Readback browser = new InlineDictation.Readback("", 0, 0, -1);

    private InlineDictation.Session begin() {
        InlineDictation.Session session = dictation.begin(1, null, browser, original);
        InlineDictation.Edit begin = dictation.next(session.id, original, browser);
        dictation.dispatched(session.id, begin, InlineDictation.DispatchOutcome.SENT, original, browser);
        dictation.listening(session.id);
        return session;
    }

    private InlineDictation.Session pending() {
        InlineDictation.Session session = begin();
        dictation.revise(session.id, "hello", false);
        InlineDictation.Edit edit = dictation.next(session.id, original, browser);
        dictation.dispatched(session.id, edit, InlineDictation.DispatchOutcome.SENT, original,
                new InlineDictation.Readback("hello", 5, 5, -1));
        return session;
    }

    @Test
    public void malformedReadbackRejectsTheEditorBoundary() {
        for (InlineDictation.Readback invalid : new InlineDictation.Readback[] {
                new InlineDictation.Readback(null, 0, 0, 9),
                new InlineDictation.Readback("x", -1, 1, 9),
                new InlineDictation.Readback("x", 0, 2, 9),
                new InlineDictation.Readback("x", 1, 1, -2)}) {
            assertFalse(invalid.valid());
            assertNull(dictation.begin(1, null, invalid, original));
        }
        assertNotNull(dictation.begin(1, null, browser, original));
    }

    @Test
    public void knownOffsetMustAgreeWithActualAbsoluteCaretInEitherDirection() {
        InlineDictation.Readback selected = new InlineDictation.Readback("word", 4, 0, 9);
        assertTrue(selected.matchesObservedCaret(13, 9));
        assertTrue(selected.matchesObservedCaret(9, 13));
        assertFalse(selected.matchesObservedCaret(10, 14));
        assertFalse(selected.matchesObservedCaret(9, 12));
        assertFalse(selected.matchesObservedCaret(-1, -1));
    }

    @Test
    public void unsetOffsetStillRequiresTheActualSelectedSpan() {
        InlineDictation.Readback selected = new InlineDictation.Readback("word", 0, 4, -1);
        assertTrue(selected.matchesObservedCaret(9, 13));
        assertFalse(selected.matchesObservedCaret(9, 12));
        InlineDictation.Session session = dictation.begin(1, null, selected,
                new InlineDictation.Selection(9, 13, -1, -1));
        assertEquals("word", session.originalSelectedText);
        assertEquals(new InlineDictation.Selection(9, 13, -1, -1), session.originalSelection);
    }

    @Test
    public void actualCallbackPlusFreshBrowserTextAcknowledgesTheWholeDraft() {
        InlineDictation.Session session = pending();
        dictation.observe(session.id, new InlineDictation.Selection(14, 14, 9, 14),
                new InlineDictation.Readback("hello", 5, 5, -1));
        assertEquals("hello", session.acknowledgedDraft());
        assertEquals(InlineDictation.Delivery.VERIFIED, session.delivery);
        assertFalse(session.waitingForAck());
    }

    @Test
    public void newTextReadbackCannotReplaceAnActualCaretOrCandidateAck() {
        InlineDictation.Session session = pending();
        dictation.observe(session.id, original, new InlineDictation.Readback("hello", 5, 5, 9));
        assertTrue(session.waitingForAck());
        assertEquals("", session.acknowledgedDraft());
        assertNull(dictation.next(session.id, original, browser));
    }

    @Test
    public void expectedGeometryWithWrongKnownOffsetIsNotProof() {
        InlineDictation.Session session = pending();
        dictation.observe(session.id, new InlineDictation.Selection(14, 14, 9, 14),
                new InlineDictation.Readback("hello", 5, 5, 10));
        assertEquals(InlineDictation.Phase.RECOVERY, session.phase);
        assertEquals(InlineDictation.Delivery.UNKNOWN, session.delivery);
        assertEquals("hello", session.recoveryText());
    }

    @Test
    public void missingFreshAckTextStopsWritesWithoutADeferredRetry() {
        InlineDictation.Session session = pending();
        dictation.observe(session.id, new InlineDictation.Selection(14, 14, 9, 14), null);
        dictation.observe(session.id, new InlineDictation.Selection(14, 14, 9, 14),
                new InlineDictation.Readback("hello", 5, 5, -1));
        assertEquals(InlineDictation.Phase.RECOVERY, session.phase);
        assertEquals("", session.acknowledgedDraft());
        assertNull(dictation.next(session.id, original, browser));
    }

    @Test
    public void originalSelectedTextMustStillMatchBeforeFirstReplacement() {
        InlineDictation.Selection selected = new InlineDictation.Selection(9, 13, -1, -1);
        InlineDictation.Readback before = new InlineDictation.Readback("word", 0, 4, -1);
        InlineDictation.Session session = dictation.begin(1, null, before, selected);
        InlineDictation.Edit begin = dictation.next(session.id, selected, before);
        dictation.dispatched(session.id, begin, InlineDictation.DispatchOutcome.SENT, selected, before);
        dictation.listening(session.id);
        dictation.revise(session.id, "draft", false);
        assertNull(dictation.next(session.id, selected, new InlineDictation.Readback("edit", 0, 4, -1)));
        assertEquals(InlineDictation.Phase.RECOVERY, session.phase);
        assertEquals("draft", session.recoveryText());
        assertEquals("word", session.originalSelectedText);
    }

    @Test
    public void candidateOnlyLossWithTheSameVisibleSuffixCannotAuthorizeAnotherWrite() {
        InlineDictation.Session session = pending();
        InlineDictation.Readback fresh = new InlineDictation.Readback("hello", 5, 5, -1);
        dictation.observe(session.id, new InlineDictation.Selection(14, 14, 9, 14), fresh);
        dictation.revise(session.id, "world", false);
        assertNull(dictation.next(session.id, new InlineDictation.Selection(14, 14, -1, -1), fresh));
        assertEquals(InlineDictation.Phase.CLOSED, session.phase);
        assertEquals("hello", session.acknowledgedDraft());
    }

    @Test
    public void onlyTheRelevantFullRevisionLengthIsRequestedWhileWaiting() {
        InlineDictation.Session session = pending();
        assertEquals(5, session.readbackBefore());
        dictation.revise(session.id, "hello much longer", false);
        assertEquals(5, session.readbackBefore());
        InlineDictation.Selection ack = new InlineDictation.Selection(14, 14, 9, 14);
        InlineDictation.Readback fresh = new InlineDictation.Readback("hello", 5, 5, -1);
        dictation.observe(session.id, ack, fresh);
        assertEquals(5, session.readbackBefore());
        dictation.next(session.id, ack, fresh);
        assertEquals(17, session.readbackBefore());
    }

    @Test
    public void editorApiIntegerRangeCannotOverflowAComposingCursor() {
        InlineDictation.Selection edge = new InlineDictation.Selection(
                Integer.MAX_VALUE, Integer.MAX_VALUE, -1, -1);
        InlineDictation.Readback fresh = new InlineDictation.Readback("", 0, 0, Integer.MAX_VALUE);
        InlineDictation.Session session = dictation.begin(1, null, fresh, edge);
        InlineDictation.Edit begin = dictation.next(session.id, edge, fresh);
        dictation.dispatched(session.id, begin, InlineDictation.DispatchOutcome.SENT, edge, fresh);
        dictation.listening(session.id);
        dictation.revise(session.id, "x", false);
        assertNull(dictation.next(session.id, edge, fresh));
        assertEquals(InlineDictation.Delivery.NOT_SENT, session.delivery);
        assertEquals("x", session.recoveryText());
    }
}
