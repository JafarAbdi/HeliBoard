package dev.juruc.pixelvoice;

import java.util.UUID;

public final class InlineDictation {
    enum Phase {
        WAITING_CONTROLS, WAITING_FOREGROUND, PREPARING, LISTENING, PROCESSING, RECOVERY, SETUP,
        CLOSED
    }

    enum Delivery { VERIFIED, NOT_SENT, UNKNOWN }
    enum TerminalIntent { NONE, STOP, CANCEL, KEEP }
    enum RevisionKind { PARTIAL, FINAL }
    private enum Purpose { BEGIN, DRAFT, RESTORE, FINAL, DISCARD }

    public enum Operation { BEGIN, COMPOSE, COMMIT, FINISH }
    public enum DispatchOutcome { SENT, NOT_SENT, UNCERTAIN }

    public record Selection(int start, int end, int composingStart, int composingEnd) {
        boolean sameCaret(Selection other) {
            return start == other.start && end == other.end;
        }
    }

    public record Observation(long sequence, Selection selection) {}

    /** end == -1 means the original selection, not an owned composing region. */
    public record Edit(Operation operation, String text, int start, int end,
            int selectionStart, int selectionEnd) {
        Selection expectedSelection() {
            return new Selection(selectionStart, selectionEnd,
                    operation == Operation.COMPOSE ? start : -1,
                    operation == Operation.COMPOSE ? start + text.length() : -1);
        }
    }

    public record Readback(String text, int selectionStart, int selectionEnd, int offset) {
        public boolean valid() {
            return text != null && selectionStart >= 0 && selectionEnd >= 0
                    && selectionStart <= text.length() && selectionEnd <= text.length()
                    && offset >= -1;
        }

        public boolean matchesObservedCaret(long observedStart, long observedEnd) {
            if (!valid() || observedStart < 0 || observedEnd < 0) return false;
            long oMin = Math.min(observedStart, observedEnd);
            long oMax = Math.max(observedStart, observedEnd);
            int rMin = Math.min(selectionStart, selectionEnd);
            int rMax = Math.max(selectionStart, selectionEnd);
            return oMax - oMin == rMax - rMin && (offset == -1
                    || ((long) offset + rMin == oMin && (long) offset + rMax == oMax));
        }

        boolean proves(Selection observed, String expected) {
            if (!matchesObservedCaret(observed.start, observed.end)) return false;
            int min = Math.min(selectionStart, selectionEnd);
            int max = Math.max(selectionStart, selectionEnd);
            if (min != max) return expected.equals(text.substring(min, max));
            return min >= expected.length()
                    && expected.equals(text.substring(min - expected.length(), min));
        }
    }

    record Revision(String text, RevisionKind kind) {}
    record Acknowledged(Operation operation, String text, Selection range) {}
    private record ExpectedAck(Edit edit, Selection before, Purpose purpose,
            DispatchOutcome outcome) {}

    static final class Session {
        final String id = UUID.randomUUID().toString();
        final long editorEpoch;
        final Selection originalSelection;
        final String originalSelectedText;
        Phase phase = Phase.WAITING_CONTROLS;
        Delivery delivery = Delivery.NOT_SENT;
        TerminalIntent terminalIntent = TerminalIntent.NONE;
        Acknowledged lastAcknowledged;
        Revision latestRevision;
        Revision pendingFullRevision;
        ExpectedAck inFlight;

        Session(long editorEpoch, Selection originalSelection, String originalSelectedText) {
            this.editorEpoch = editorEpoch;
            this.originalSelection = originalSelection;
            this.originalSelectedText = originalSelectedText;
        }

        int anchor() {
            return Math.min(originalSelection.start, originalSelection.end);
        }

        boolean hasDraft() {
            return lastAcknowledged != null && (lastAcknowledged.operation == Operation.COMPOSE
                    || lastAcknowledged.operation == Operation.COMMIT);
        }

        String acknowledgedDraft() {
            return hasDraft() ? lastAcknowledged.text : "";
        }

        String recoveryText() {
            return latestRevision == null ? acknowledgedDraft() : latestRevision.text;
        }

        int readbackBefore() {
            return inFlight != null ? inFlight.edit.text.length() : acknowledgedDraft().length();
        }

        boolean waitingForAck() {
            return inFlight != null;
        }
    }

    private long editorEpoch;
    private Session session;

    Session begin(int inputType, String privateImeOptions, Readback snapshot, Selection observed) {
        closeSession();
        if (!DictationTarget.acceptsDictation(inputType, privateImeOptions) || snapshot == null
                || !snapshot.matchesObservedCaret(observed.start, observed.end)) return null;
        int min = Math.min(snapshot.selectionStart, snapshot.selectionEnd);
        int max = Math.max(snapshot.selectionStart, snapshot.selectionEnd);
        session = new Session(editorEpoch, observed, snapshot.text.substring(min, max));
        return session;
    }

    Session session() {
        return session;
    }

    boolean owns(String id) {
        return session != null && session.id.equals(id) && session.editorEpoch == editorEpoch;
    }

    boolean ownsComposition() {
        return session != null && session.phase != Phase.CLOSED && session.phase != Phase.RECOVERY
                && session.phase != Phase.SETUP;
    }

    void editorChanged() {
        ++editorEpoch;
        closeSession();
    }

    void closeSession() {
        if (session != null) {
            session.phase = Phase.CLOSED;
            session.pendingFullRevision = null;
            session.inFlight = null;
            session = null;
        }
    }

    void listening(String id) {
        if (owns(id) && session.phase.compareTo(Phase.LISTENING) <= 0
                && session.lastAcknowledged != null && !session.waitingForAck()) {
            session.phase = Phase.LISTENING;
        }
    }

    boolean stop(String id) {
        if (!owns(id) || session.phase != Phase.LISTENING
                || session.terminalIntent != TerminalIntent.NONE) return false;
        session.terminalIntent = TerminalIntent.STOP;
        session.phase = Phase.PROCESSING;
        return true;
    }

    void cancel(String id) {
        if (!owns(id) || !ownsComposition()) return;
        session.terminalIntent = TerminalIntent.CANCEL;
        session.pendingFullRevision = null;
        session.phase = Phase.PROCESSING;
    }

    Edit interrupt(String id, Selection actual) {
        if (!owns(id)) return null;
        Session live = session;
        Edit finish = null;
        if (live.phase != Phase.CLOSED && (live.hasDraft() || (live.inFlight != null
                && live.inFlight.edit.operation == Operation.COMPOSE)
                || (live.phase == Phase.RECOVERY && !live.recoveryText().isEmpty()))) {
            Selection cursor = live.inFlight == null ? actual : live.inFlight.edit.expectedSelection();
            finish = new Edit(Operation.FINISH, "", -1, -1, cursor.start, cursor.end);
        }
        live.terminalIntent = TerminalIntent.KEEP;
        closeSession();
        return finish;
    }

    void setupNeeded(String id) {
        if (owns(id) && session.phase != Phase.RECOVERY && session.phase != Phase.CLOSED) {
            session.phase = Phase.SETUP;
            session.pendingFullRevision = null;
            session.inFlight = null;
        }
    }

    void revise(String id, String text, boolean isFinal) {
        if (!owns(id) || (session.phase != Phase.LISTENING && session.phase != Phase.PROCESSING)
                || session.terminalIntent == TerminalIntent.CANCEL
                || (isFinal && session.terminalIntent != TerminalIntent.STOP)) return;
        if (session.latestRevision != null && session.latestRevision.kind == RevisionKind.FINAL) return;
        session.latestRevision = new Revision(text, isFinal ? RevisionKind.FINAL : RevisionKind.PARTIAL);
        session.pendingFullRevision = !isFinal && !session.waitingForAck()
                && text.equals(session.acknowledgedDraft()) ? null : session.latestRevision;
    }

    private boolean geometryOwned(Session live, Selection actual) {
        return live.hasDraft() ? live.lastAcknowledged.range.equals(actual)
                : live.originalSelection.sameCaret(actual) && (live.lastAcknowledged == null
                        || (actual.composingStart == -1 && actual.composingEnd == -1));
    }

    private boolean textOwned(Session live, Selection actual, Readback current) {
        if (current == null) return false;
        if (live.hasDraft()) return current.proves(actual, live.lastAcknowledged.text);
        if (!current.matchesObservedCaret(actual.start, actual.end)) return false;
        int min = Math.min(current.selectionStart, current.selectionEnd);
        int max = Math.max(current.selectionStart, current.selectionEnd);
        return live.originalSelectedText.equals(current.text.substring(min, max));
    }

    boolean confirmSnapshot(String id, Selection actual, Readback current) {
        return owns(id) && ownsComposition() && !session.waitingForAck()
                && geometryOwned(session, actual) && textOwned(session, actual, current);
    }

    Edit observe(String id, Selection actual, Readback current) {
        if (!owns(id) || !ownsComposition()) return null;
        Session live = session;
        ExpectedAck expected = live.inFlight;
        if (expected != null) {
            if (expected.outcome != DispatchOutcome.SENT) return null;
            boolean original = expected.purpose == Purpose.BEGIN;
            boolean matches = original ? live.originalSelection.sameCaret(actual)
                    && actual.composingStart == -1 && actual.composingEnd == -1
                    : expected.edit.expectedSelection().equals(actual);
            if (matches) {
                boolean proved = original ? textOwned(live, actual, current)
                        : current != null && current.proves(actual, expected.edit.text);
                if (!proved) {
                    uncertain(live, Delivery.UNKNOWN);
                    return null;
                }
                live.delivery = Delivery.VERIFIED;
                live.inFlight = null;
                if (expected.purpose == Purpose.FINAL || expected.purpose == Purpose.DISCARD) {
                    live.pendingFullRevision = null;
                    boolean restored = expected.edit.text.equals(live.originalSelectedText)
                            && expected.edit.selectionStart == live.originalSelection.start
                            && expected.edit.selectionEnd == live.originalSelection.end;
                    if (expected.purpose == Purpose.FINAL && live.terminalIntent == TerminalIntent.CANCEL
                            && !restored) {
                        live.lastAcknowledged = new Acknowledged(Operation.COMMIT, expected.edit.text, actual);
                    } else {
                        live.phase = Phase.CLOSED;
                    }
                } else {
                    Operation operation = expected.purpose == Purpose.DRAFT
                            ? Operation.COMPOSE : Operation.BEGIN;
                    live.lastAcknowledged = new Acknowledged(operation, expected.edit.text, actual);
                }
            } else if (!expected.before.equals(actual)
                    && !(original && live.originalSelection.sameCaret(actual))) {
                return lost(live, actual);
            }
            return null;
        }
        if (!geometryOwned(live, actual)) return lost(live, actual);
        if (!textOwned(live, actual, current)) uncertain(live, Delivery.UNKNOWN);
        return null;
    }

    Edit next(String id, Selection actual, Readback current) {
        if (!owns(id) || !ownsComposition() || session.inFlight != null) return null;
        Session live = session;
        if (!geometryOwned(live, actual)) {
            lost(live, actual);
            return null;
        }
        if (!textOwned(live, actual, current)) {
            uncertain(live, Delivery.NOT_SENT);
            return null;
        }
        if (live.lastAcknowledged == null) {
            return expect(live, actual, new Edit(Operation.BEGIN, "", -1, -1,
                    live.originalSelection.start, live.originalSelection.end), Purpose.BEGIN);
        }
        if (live.terminalIntent == TerminalIntent.CANCEL) {
            if (!live.hasDraft()) {
                live.phase = Phase.CLOSED;
                return null;
            }
            return restore(live, actual, Purpose.DISCARD);
        }
        Revision revision = live.pendingFullRevision;
        if (revision == null) return null;
        live.pendingFullRevision = null;
        if (revision.text.isEmpty()) {
            if (!live.hasDraft()) {
                if (revision.kind == RevisionKind.FINAL) live.phase = Phase.CLOSED;
                return null;
            }
            return restore(live, actual,
                    revision.kind == RevisionKind.FINAL ? Purpose.FINAL : Purpose.RESTORE);
        }
        if (revision.kind == RevisionKind.PARTIAL && live.hasDraft()
                && revision.text.equals(live.lastAcknowledged.text)) return null;
        long end = (long) live.anchor() + revision.text.length();
        if (end > Integer.MAX_VALUE) {
            uncertain(live, Delivery.NOT_SENT);
            return null;
        }
        return expect(live, actual, new Edit(revision.kind == RevisionKind.FINAL
                        ? Operation.COMMIT : Operation.COMPOSE, revision.text, live.anchor(),
                live.hasDraft() ? live.lastAcknowledged.range.end : -1,
                (int) end, (int) end),
                revision.kind == RevisionKind.FINAL ? Purpose.FINAL : Purpose.DRAFT);
    }

    private Edit restore(Session live, Selection actual, Purpose purpose) {
        return expect(live, actual, new Edit(Operation.COMMIT, live.originalSelectedText,
                live.anchor(), live.lastAcknowledged.range.end,
                live.originalSelection.start, live.originalSelection.end), purpose);
    }

    private Edit expect(Session live, Selection actual, Edit edit, Purpose purpose) {
        live.inFlight = new ExpectedAck(edit, actual, purpose, null);
        live.delivery = Delivery.UNKNOWN;
        return edit;
    }

    void dispatched(String id, Edit edit, DispatchOutcome outcome, Selection actual, Readback after) {
        if (!owns(id) || session.inFlight == null || session.inFlight.edit != edit) return;
        if (outcome != DispatchOutcome.SENT) {
            uncertain(session, outcome == DispatchOutcome.NOT_SENT ? Delivery.NOT_SENT : Delivery.UNKNOWN);
            return;
        }
        ExpectedAck previous = session.inFlight;
        session.inFlight = new ExpectedAck(edit, previous.before, previous.purpose, outcome);
        observe(id, actual, after);
    }

    private void uncertain(Session live, Delivery delivery) {
        live.delivery = delivery;
        live.phase = Phase.RECOVERY;
        live.pendingFullRevision = null;
        live.inFlight = null;
    }

    private Edit lost(Session live, Selection actual) {
        Selection ownedRange = live.hasDraft() ? live.lastAcknowledged.range : null;
        if (live.inFlight != null && live.inFlight.edit.operation == Operation.COMPOSE) {
            ownedRange = live.inFlight.edit.expectedSelection();
        }
        // A caret move inside the still-owned span must not let the next key replace all its words.
        Edit finish = ownedRange != null && actual.composingStart >= 0
                && actual.composingStart == ownedRange.composingStart
                && actual.composingEnd == ownedRange.composingEnd
                ? new Edit(Operation.FINISH, "", -1, -1, actual.start, actual.end) : null;
        live.delivery = Delivery.UNKNOWN;
        live.terminalIntent = TerminalIntent.KEEP;
        live.phase = Phase.CLOSED;
        live.pendingFullRevision = null;
        live.inFlight = null;
        return finish;
    }
}
