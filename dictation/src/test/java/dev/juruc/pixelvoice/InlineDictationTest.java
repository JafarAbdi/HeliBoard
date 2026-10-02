package dev.juruc.pixelvoice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class InlineDictationTest {
    private final InlineDictation dictation = new InlineDictation();
    private InlineDictation.Selection actual = new InlineDictation.Selection(6, 6, -1, -1);
    private InlineDictation.Readback current = new InlineDictation.Readback("", 0, 0, 6);
    private InlineDictation.Session session;

    private InlineDictation.Session begin() {
        session = dictation.begin(1, null, current, actual);
        assertNotNull(session);
        InlineDictation.Edit edit = dictation.next(session.id, actual, current);
        assertEquals(InlineDictation.Operation.BEGIN, edit.operation());
        dictation.dispatched(session.id, edit, InlineDictation.DispatchOutcome.SENT, actual, current);
        actual = new InlineDictation.Selection(actual.start(), actual.end(), -1, -1);
        dictation.observe(session.id, actual, current);
        assertFalse(session.waitingForAck());
        dictation.listening(session.id);
        return session;
    }

    private InlineDictation.Edit revision(String text, boolean isFinal) {
        dictation.revise(session.id, text, isFinal);
        return dictation.next(session.id, actual, current);
    }

    private InlineDictation.Edit firstDraft() {
        begin();
        InlineDictation.Edit edit = revision("one", false);
        assertEquals(new InlineDictation.Edit(InlineDictation.Operation.COMPOSE, "one", 6, -1, 9, 9), edit);
        dictation.dispatched(session.id, edit, InlineDictation.DispatchOutcome.SENT, actual, current);
        return edit;
    }

    private void acknowledge(String text, int start, int end, int composingStart, int composingEnd,
            int relativeStart, int relativeEnd, int offset) {
        actual = new InlineDictation.Selection(start, end, composingStart, composingEnd);
        current = new InlineDictation.Readback(text, relativeStart, relativeEnd, offset);
        dictation.observe(session.id, actual, current);
    }

    private void ownedDraft() {
        firstDraft();
        acknowledge("one", 9, 9, 6, 9, 3, 3, 6);
        assertEquals("one", session.acknowledgedDraft());
        assertEquals(InlineDictation.Delivery.VERIFIED, session.delivery);
    }

    @Test
    public void originalSelectionAndEpochComeFromFreshObservedData() {
        dictation.editorChanged();
        dictation.editorChanged();
        actual = new InlineDictation.Selection(8, 2, 0, 8);
        current = new InlineDictation.Readback("iginal", 6, 0, 2);
        begin();
        assertEquals(2, session.editorEpoch);
        assertEquals(new InlineDictation.Selection(8, 2, 0, 8), session.originalSelection);
        assertEquals("iginal", session.originalSelectedText);
        assertTrue(dictation.confirmSnapshot(session.id, actual, current));
    }

    @Test
    public void protectedEditorsPrivateOptionsAndUnknownCaretNeverBegin() {
        for (int inputType : new int[] {0, 0x81, 0x91, 0xe1, 0x12}) {
            assertNull(dictation.begin(inputType, null, current, actual));
        }
        for (String option : new String[] {"nm", "other, nm", "host.noMicrophoneKey"}) {
            assertNull(dictation.begin(1, option, current, actual));
        }
        assertNull(dictation.begin(1, null, current, new InlineDictation.Selection(-1, -1, -1, -1)));
        assertNull(dictation.begin(1, null, new InlineDictation.Readback("", 0, 0, 7), actual));
        assertNull(dictation.begin(1, null, new InlineDictation.Readback("x", 0, 1, -1), actual));
        assertNull(dictation.begin(1, null, null, actual));
        assertNotNull(dictation.begin(1, null, current, actual));
    }

    @Test
    public void beginFinishesThePretypedCompositionOnceWithoutReplayingItsText() {
        actual = new InlineDictation.Selection(3, 3, 0, 5);
        current = new InlineDictation.Readback("", 0, 0, 3);
        begin();
        assertEquals("", session.originalSelectedText);
        assertNull(dictation.next(session.id, actual, current));
        InlineDictation.Edit edit = revision("draft", false);
        assertEquals(new InlineDictation.Edit(InlineDictation.Operation.COMPOSE, "draft", 3, -1, 8, 8), edit);
    }

    @Test
    public void beginWaitsForActualPretypedCompositionRemovalBeforeListening() {
        actual = new InlineDictation.Selection(3, 3, 0, 5);
        current = new InlineDictation.Readback("", 0, 0, 3);
        session = dictation.begin(1, null, current, actual);
        InlineDictation.Edit begin = dictation.next(session.id, actual, current);
        dictation.dispatched(session.id, begin, InlineDictation.DispatchOutcome.SENT, actual, current);
        assertTrue(session.waitingForAck());
        assertFalse(dictation.confirmSnapshot(session.id, actual, current));
        dictation.listening(session.id);
        assertEquals(InlineDictation.Phase.WAITING_CONTROLS, session.phase);
        acknowledge("", 3, 3, -1, -1, 0, 0, 3);
        assertFalse(session.waitingForAck());
        assertTrue(dictation.confirmSnapshot(session.id, actual, current));
        dictation.listening(session.id);
        assertEquals(InlineDictation.Phase.LISTENING, session.phase);
    }

    @Test
    public void composingResumptionBeforeFirstPartialCannotReplaceThePretypedWord() {
        begin();
        assertNull(dictation.observe(session.id, new InlineDictation.Selection(6, 6, 0, 6), current));
        assertEquals(InlineDictation.Phase.CLOSED, session.phase);
        assertNull(revision("draft", false));
        assertNull(dictation.next(session.id, actual, current));
    }

    @Test
    public void dispatchAcceptanceAloneDoesNotAcknowledgeTheFirstDraft() {
        firstDraft();
        assertTrue(session.waitingForAck());
        assertEquals("", session.acknowledgedDraft());
        assertEquals(InlineDictation.Delivery.UNKNOWN, session.delivery);
        acknowledge("one", 9, 9, 6, 9, 3, 3, 6);
        assertEquals("one", session.acknowledgedDraft());
        assertFalse(session.waitingForAck());
    }

    @Test
    public void onlyTheLatestCompleteRevisionSurvivesAnOutstandingWrite() {
        firstDraft();
        assertNull(revision("one two", false));
        assertNull(revision("one three", false));
        assertEquals("one three", session.pendingFullRevision.text());
        acknowledge("one", 9, 9, 6, 9, 3, 3, 6);
        InlineDictation.Edit edit = dictation.next(session.id, actual, current);
        assertEquals(new InlineDictation.Edit(InlineDictation.Operation.COMPOSE,
                "one three", 6, 9, 15, 15), edit);
        assertNull(dictation.next(session.id, actual, current));
    }

    @Test
    public void identicalAcknowledgedRevisionsDoNotWrite() {
        ownedDraft();
        InlineDictation.Revision previous = session.latestRevision;
        assertNull(revision("one", false));
        assertTrue(session.latestRevision != previous);
        assertEquals("one", session.acknowledgedDraft());
        assertFalse(session.waitingForAck());
    }

    @Test
    public void sameLengthRevisionSettlesByFreshTextAndUnchangedActualRange() {
        ownedDraft();
        InlineDictation.Edit edit = revision("two", false);
        dictation.dispatched(session.id, edit, InlineDictation.DispatchOutcome.SENT, actual,
                new InlineDictation.Readback("two", 3, 3, 6));
        current = new InlineDictation.Readback("two", 3, 3, 6);
        assertEquals("two", session.acknowledgedDraft());
        assertEquals(new InlineDictation.Selection(9, 9, 6, 9), session.lastAcknowledged.range());
        assertFalse(session.waitingForAck());
        assertNull(revision("two", false));
    }

    @Test
    public void browserGrowShrinkAndUnicodeUseActualRangesWithUnsetOffsets() {
        current = new InlineDictation.Readback("", 0, 0, -1);
        firstDraft();
        acknowledge("one", 9, 9, 6, 9, 3, 3, -1);
        InlineDictation.Edit grow = revision("café 语音 🎤", false);
        dictation.dispatched(session.id, grow, InlineDictation.DispatchOutcome.SENT, actual,
                new InlineDictation.Readback("café 语音 🎤", 10, 10, -1));
        assertTrue(session.waitingForAck());
        acknowledge("café 语音 🎤", 16, 16, 6, 16, 10, 10, -1);
        assertEquals("café 语音 🎤", session.acknowledgedDraft());
        InlineDictation.Edit shrink = revision("go", false);
        assertEquals(new InlineDictation.Edit(InlineDictation.Operation.COMPOSE, "go", 6, 16, 8, 8), shrink);
        dictation.dispatched(session.id, shrink, InlineDictation.DispatchOutcome.SENT, actual, current);
        acknowledge("go", 8, 8, 6, 8, 2, 2, -1);
        assertEquals("go", session.acknowledgedDraft());
    }

    @Test
    public void wrongTextAtAnExpectedAckStopsWritesAndRetainsRecovery() {
        firstDraft();
        acknowledge("ONE", 9, 9, 6, 9, 3, 3, 6);
        assertEquals(InlineDictation.Phase.RECOVERY, session.phase);
        assertEquals("one", session.recoveryText());
        assertNull(revision("another", false));
        assertNull(dictation.next(session.id, actual, current));
    }

    @Test
    public void uncertainApiDispatchCannotBeRetriedOrUpgradedByLaterProof() {
        ownedDraft();
        InlineDictation.Edit edit = revision("two", false);
        dictation.dispatched(session.id, edit, InlineDictation.DispatchOutcome.UNCERTAIN, actual,
                new InlineDictation.Readback("two", 3, 3, 6));
        acknowledge("two", 9, 9, 6, 9, 3, 3, 6);
        assertEquals(InlineDictation.Phase.RECOVERY, session.phase);
        assertEquals(InlineDictation.Delivery.UNKNOWN, session.delivery);
        assertEquals("two", session.recoveryText());
        assertNull(dictation.next(session.id, actual, current));
    }

    @Test
    public void knownNotSentHasRecoveryButNoAutomaticInsertRetry() {
        begin();
        InlineDictation.Edit edit = revision("one", false);
        dictation.dispatched(session.id, edit, InlineDictation.DispatchOutcome.NOT_SENT, actual, null);
        assertEquals(InlineDictation.Delivery.NOT_SENT, session.delivery);
        assertEquals("one", session.recoveryText());
        assertNull(dictation.next(session.id, actual, current));
        assertNull(revision("one", false));
    }

    @Test
    public void candidateOnlyCompositionLossStopsWithoutReassertingOrDiscarding() {
        ownedDraft();
        acknowledge("one", 9, 9, -1, -1, 3, 3, 6);
        assertEquals(InlineDictation.Phase.CLOSED, session.phase);
        assertFalse(dictation.ownsComposition());
        dictation.cancel(session.id);
        assertNull(dictation.next(session.id, actual, current));
        assertEquals("one", session.acknowledgedDraft());
    }

    @Test
    public void caretMovementFinishesTheStillOwnedSpanWithoutChangingTextOrRestoringSelection() {
        ownedDraft();
        InlineDictation.Selection moved = new InlineDictation.Selection(8, 8, 6, 9);
        assertEquals(new InlineDictation.Edit(InlineDictation.Operation.FINISH, "", -1, -1, 8, 8),
                dictation.observe(session.id, moved, new InlineDictation.Readback("on", 2, 2, 6)));
        assertEquals(InlineDictation.TerminalIntent.KEEP, session.terminalIntent);
        assertEquals(InlineDictation.Phase.CLOSED, session.phase);
        assertNull(dictation.observe(session.id, moved, current));
    }

    @Test
    public void caretOrCandidateAnchorChangesRevokeTheDraft() {
        for (InlineDictation.Selection changed : new InlineDictation.Selection[] {
                new InlineDictation.Selection(8, 8, 6, 9),
                new InlineDictation.Selection(9, 8, 6, 9),
                new InlineDictation.Selection(9, 9, 5, 9)}) {
            actual = new InlineDictation.Selection(6, 6, -1, -1);
            current = new InlineDictation.Readback("", 0, 0, 6);
            ownedDraft();
            dictation.observe(session.id, changed, current);
            assertFalse(dictation.ownsComposition());
            assertNull(dictation.next(session.id, changed, current));
            assertEquals("one", session.acknowledgedDraft());
        }
    }

    @Test
    public void differentEditorEpochRejectsLateRevisionAckAndCancel() {
        firstDraft();
        InlineDictation.Session old = session;
        dictation.editorChanged();
        acknowledge("one", 9, 9, 6, 9, 3, 3, 6);
        dictation.cancel(old.id);
        assertFalse(dictation.owns(old.id));
        assertNull(dictation.session());
        assertEquals(InlineDictation.Phase.CLOSED, old.phase);
        assertNull(revision("late", false));
    }

    @Test
    public void finalWithoutPartialCommitsOnlyOverTheOriginalSelection() {
        actual = new InlineDictation.Selection(0, 8, -1, -1);
        current = new InlineDictation.Readback("Original", 0, 8, 0);
        begin();
        assertTrue(dictation.stop(session.id));
        InlineDictation.Edit edit = revision("final", true);
        assertEquals(new InlineDictation.Edit(InlineDictation.Operation.COMMIT, "final", 0, -1, 5, 5), edit);
    }

    @Test
    public void stopFinalReplacesTheDraftAndClosesOnceAfterActualProof() {
        ownedDraft();
        assertTrue(dictation.stop(session.id));
        assertFalse(dictation.stop(session.id));
        InlineDictation.Edit edit = revision("final", true);
        assertEquals(new InlineDictation.Edit(InlineDictation.Operation.COMMIT, "final", 6, 9, 11, 11), edit);
        dictation.dispatched(session.id, edit, InlineDictation.DispatchOutcome.SENT, actual, current);
        assertTrue(session.waitingForAck());
        acknowledge("final", 11, 11, -1, -1, 5, 5, 6);
        assertEquals(InlineDictation.Phase.CLOSED, session.phase);
        assertEquals(InlineDictation.Delivery.VERIFIED, session.delivery);
        assertNull(revision("duplicate", true));
        assertEquals("final", session.recoveryText());
    }

    @Test
    public void stopAndFinalWaitForAnOutstandingPartialAck() {
        firstDraft();
        assertTrue(dictation.stop(session.id));
        assertNull(revision("final", true));
        acknowledge("one", 9, 9, 6, 9, 3, 3, 6);
        assertEquals(new InlineDictation.Edit(InlineDictation.Operation.COMMIT, "final", 6, 9, 11, 11),
                dictation.next(session.id, actual, current));
    }

    @Test
    public void cancelBeforeTheFirstDraftHasNoContentWrite() {
        begin();
        dictation.cancel(session.id);
        assertNull(dictation.next(session.id, actual, current));
        assertEquals(InlineDictation.Phase.CLOSED, session.phase);
        assertEquals("", session.acknowledgedDraft());
    }

    @Test
    public void cancelAfterPartialRemovesOnlyTheOwnedDraftAtTheOriginalCaret() {
        ownedDraft();
        dictation.cancel(session.id);
        InlineDictation.Edit discard = dictation.next(session.id, actual, current);
        assertEquals(new InlineDictation.Edit(InlineDictation.Operation.COMMIT, "", 6, 9, 6, 6), discard);
        dictation.dispatched(session.id, discard, InlineDictation.DispatchOutcome.SENT, actual, current);
        acknowledge("", 6, 6, -1, -1, 0, 0, 6);
        assertEquals(InlineDictation.Phase.CLOSED, session.phase);
        assertEquals(InlineDictation.Delivery.VERIFIED, session.delivery);
    }

    @Test
    public void cancelRestoresSelectedTextAndItsOriginalDirection() {
        actual = new InlineDictation.Selection(8, 0, -1, -1);
        current = new InlineDictation.Readback("Original", 8, 0, 0);
        begin();
        InlineDictation.Edit draft = revision("new", false);
        dictation.dispatched(session.id, draft, InlineDictation.DispatchOutcome.SENT, actual, current);
        acknowledge("new", 3, 3, 0, 3, 3, 3, -1);
        dictation.cancel(session.id);
        InlineDictation.Edit discard = dictation.next(session.id, actual, current);
        assertEquals(new InlineDictation.Edit(InlineDictation.Operation.COMMIT, "Original", 0, 3, 8, 0), discard);
        dictation.dispatched(session.id, discard, InlineDictation.DispatchOutcome.SENT, actual, current);
        acknowledge("Original", 8, 0, -1, -1, 8, 0, -1);
        assertEquals(InlineDictation.Delivery.VERIFIED, session.delivery);
        assertEquals(InlineDictation.Phase.CLOSED, session.phase);
    }

    @Test
    public void cancelPendingPartialWaitsForActualAckBeforeDiscard() {
        firstDraft();
        dictation.cancel(session.id);
        assertNull(dictation.next(session.id, actual, current));
        assertNull(revision("late native partial", false));
        acknowledge("one", 9, 9, 6, 9, 3, 3, 6);
        assertEquals(new InlineDictation.Edit(InlineDictation.Operation.COMMIT, "", 6, 9, 6, 6),
                dictation.next(session.id, actual, current));
    }

    @Test
    public void cancelOverridesStopAndItsQueuedFinalRevision() {
        firstDraft();
        dictation.stop(session.id);
        revision("final", true);
        dictation.cancel(session.id);
        assertNull(session.pendingFullRevision);
        acknowledge("one", 9, 9, 6, 9, 3, 3, 6);
        assertEquals(new InlineDictation.Edit(InlineDictation.Operation.COMMIT, "", 6, 9, 6, 6),
                dictation.next(session.id, actual, current));
    }

    @Test
    public void cancelAfterFinalDispatchWaitsForItsAckThenRestoresOnlyThatOwnedText() {
        ownedDraft();
        dictation.stop(session.id);
        InlineDictation.Edit finalEdit = revision("final", true);
        dictation.dispatched(session.id, finalEdit, InlineDictation.DispatchOutcome.SENT, actual, current);
        dictation.cancel(session.id);
        assertNull(dictation.next(session.id, actual, current));
        acknowledge("final", 11, 11, -1, -1, 5, 5, 6);
        assertEquals(InlineDictation.Phase.PROCESSING, session.phase);
        assertEquals("final", session.acknowledgedDraft());
        InlineDictation.Edit discard = dictation.next(session.id, actual, current);
        assertEquals(new InlineDictation.Edit(InlineDictation.Operation.COMMIT, "", 6, 11, 6, 6), discard);
        dictation.dispatched(session.id, discard, InlineDictation.DispatchOutcome.SENT, actual, current);
        acknowledge("", 6, 6, -1, -1, 0, 0, 6);
        assertEquals(InlineDictation.Phase.CLOSED, session.phase);
        assertEquals(InlineDictation.Delivery.VERIFIED, session.delivery);
    }

    @Test
    public void recoveryKeepsTheLatestCoalescedWordsWithoutDispatchingThem() {
        firstDraft();
        revision("one two", false);
        acknowledge("wrong", 9, 9, 6, 9, 5, 5, 4);
        assertEquals(InlineDictation.Phase.RECOVERY, session.phase);
        assertEquals("one two", session.recoveryText());
        assertNull(dictation.next(session.id, actual, current));
    }

    @Test
    public void emptyFinalRestoresTheSelectedOriginalInsteadOfDeletingIt() {
        actual = new InlineDictation.Selection(0, 8, -1, -1);
        current = new InlineDictation.Readback("Original", 0, 8, 0);
        begin();
        InlineDictation.Edit draft = revision("one", false);
        dictation.dispatched(session.id, draft, InlineDictation.DispatchOutcome.SENT, actual, current);
        acknowledge("one", 3, 3, 0, 3, 3, 3, 0);
        dictation.stop(session.id);
        assertEquals(new InlineDictation.Edit(InlineDictation.Operation.COMMIT, "Original", 0, 3, 0, 8),
                revision("", true));
    }

    @Test
    public void emptyFinalWithoutDraftLeavesTheOriginalSelectionUntouched() {
        actual = new InlineDictation.Selection(0, 8, -1, -1);
        current = new InlineDictation.Readback("Original", 0, 8, -1);
        begin();
        dictation.stop(session.id);
        assertNull(revision("", true));
        assertEquals(InlineDictation.Phase.CLOSED, session.phase);
        assertEquals("Original", session.originalSelectedText);
    }

    @Test
    public void emptyPartialRestoresOriginalAndLaterRecognitionCanStartANewDraft() {
        ownedDraft();
        InlineDictation.Edit restore = revision("", false);
        assertEquals(new InlineDictation.Edit(InlineDictation.Operation.COMMIT, "", 6, 9, 6, 6), restore);
        dictation.dispatched(session.id, restore, InlineDictation.DispatchOutcome.SENT, actual, current);
        acknowledge("", 6, 6, -1, -1, 0, 0, 6);
        assertEquals(InlineDictation.Phase.LISTENING, session.phase);
        assertEquals("", session.acknowledgedDraft());
        assertEquals(new InlineDictation.Edit(InlineDictation.Operation.COMPOSE, "new", 6, -1, 9, 9),
                revision("new", false));
    }

    @Test
    public void typingInterruptionFinishesButNeverRestoresOrReplaysOriginal() {
        ownedDraft();
        assertEquals(new InlineDictation.Edit(InlineDictation.Operation.FINISH, "", -1, -1, 9, 9),
                dictation.interrupt(session.id, actual));
        assertNull(dictation.session());
        assertEquals("one", session.acknowledgedDraft());
        assertEquals(InlineDictation.TerminalIntent.KEEP, session.terminalIntent);
    }

    @Test
    public void interruptionOfAnOutstandingComposeCannotDispatchQueuedRevisions() {
        firstDraft();
        revision("next", false);
        assertEquals(new InlineDictation.Edit(InlineDictation.Operation.FINISH, "", -1, -1, 9, 9),
                dictation.interrupt(session.id, actual));
        assertNull(dictation.next(session.id, actual, current));
        assertNull(session.pendingFullRevision);
        assertFalse(session.waitingForAck());
    }

    @Test
    public void staleCommandsCannotAffectANewerSession() {
        begin();
        String old = session.id;
        begin();
        dictation.cancel(old);
        dictation.revise(old, "stale", false);
        dictation.observe(old, new InlineDictation.Selection(99, 99, -1, -1), null);
        assertNull(dictation.interrupt(old, actual));
        assertTrue(dictation.owns(session.id));
        assertEquals(InlineDictation.Phase.LISTENING, session.phase);
        assertNull(session.latestRevision);
    }

    @Test
    public void setupFailureNeverDispatchesAndDoesNotRearmCapture() {
        begin();
        dictation.setupNeeded(session.id);
        dictation.listening(session.id);
        assertEquals(InlineDictation.Phase.SETUP, session.phase);
        assertFalse(dictation.stop(session.id));
        assertNull(revision("late", false));
        assertNull(dictation.next(session.id, actual, current));
    }
}
