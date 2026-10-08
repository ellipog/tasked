package dev.ellipog.tenet.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * What the reload found wrong with the pack, waiting for whoever is editing to read it.
 *
 * <h2>Why a store rather than a screen</h2>
 *
 * <p>The same reason {@code ClientEditReplies} is one: this arrives on the game thread with whatever is open,
 * which may be no book at all — the reload is coalesced and fires on the server's tick, not on the author's
 * gesture. A payload handler that reached for a screen would have to decide what to do when there is none, and
 * keeping the news until something wants it is the answer that already works here.
 *
 * <h2>Why a queue here, when the history notice is a flag</h2>
 *
 * <p>Because these are different kinds of news. "The undo history is gone" is one fact that cannot be said
 * twice — two reloads between two frames are one loss. Problems are <b>a set that changes</b>: an edit can
 * introduce one and the next can remove it, so the newest report is a different statement from the one before
 * it rather than a repeat of it. Keeping them in order means an author who made two edits sees both, and the
 * bound is there so a client that never drains cannot grow without limit.
 *
 * <p>Reading consumes, like the replies: the <i>queue</i> is news rather than state, and a problem reported
 * again on the next frame reads as a repeating error. The newest report is kept beside it as state — see
 * {@link #current} — because the Chapter tab's badge has to survive the toast being read, and a badge that
 * vanished when the author read the message would be a badge for people who already knew.
 */
public final class ClientEditProblems {

    /** Plenty for a burst of edits; a bound so a never-drained store cannot grow without limit. */
    private static final int MAX = 32;

    /** One report: how many the server found, and the lines it could fit. */
    public record Report(int count, List<String> lines) {
    }

    private static final Deque<Report> reports = new ArrayDeque<>();

    /**
     * The newest report, whether or not it has been read.
     *
     * <h2>Why this is not the queue's tail</h2>
     *
     * <p>Because reading consumes: {@link #drain} empties the queue, so a badge derived from it would go
     * blank the moment the author read the toast — the opposite of what a persistent surface is for. So
     * there are two fields and not one, and they are different things: the queue is the <b>news</b> that a
     * report arrived, and this is the <b>state</b> of the pack as the last load described it.
     *
     * <p>Replaced rather than accumulated, which is the other difference. A set of faults is not a sequence
     * of statements: the newest report is the whole truth about the pack, and a fault the previous report
     * listed and this one does not has been fixed.
     */
    private static volatile Report current;

    private ClientEditProblems() {
    }

    /**
     * Called by the payload handler, on the game thread.
     *
     * <h2>An unchanged report is state, not news</h2>
     *
     * <p>A report identical to the one already in force is <b>not queued</b>, so the toast that reads
     * {@link #drain} says nothing while the badge goes on showing the count. That is the same distinction
     * this class's own note draws between the queue and {@link #current}, and it is what stops a reload of a
     * pack whose faults have not changed — or an author joining a server that has had the same three faults
     * for a week — from re-reading every one of them out loud. A fault fixed or added changes the report, and
     * that is news.
     */
    public static synchronized void accept(int count, String text) {
        List<String> lines = text == null || text.isEmpty()
                ? List.of()
                : List.of(text.split("\n"));
        Report report = new Report(count, lines);
        if (!report.equals(current)) {
            if (reports.size() >= MAX) {
                // The oldest goes, and unlike the marker queue that is safe here: these are independent
                // statements rather than a sequence matched to something, so losing one costs one message and
                // does not shift what the others mean.
                reports.removeFirst();
            }
            reports.addLast(report);
        }
        current = report;
    }

    /** Every report nobody has read yet, oldest first. Reading them consumes them. */
    public static synchronized List<Report> drain() {
        List<Report> out = new ArrayList<>(reports);
        reports.clear();
        return out;
    }

    /**
     * The newest report, or empty when nothing has been reported since the client joined.
     *
     * <p>Read on the render path, which is why it is a volatile reference rather than a synchronized
     * accessor: the badge asks once a frame, and taking the lock a frame at a time to read one field is
     * cost for nothing. The reference is either null or a complete report — {@link Report} is immutable —
     * so there is no half-built state to see.
     */
    public static Optional<Report> current() {
        return Optional.ofNullable(current);
    }

    /** Forgets everything: called when the client leaves the world it was editing. */
    public static synchronized void clear() {
        reports.clear();
        current = null;
    }
}
