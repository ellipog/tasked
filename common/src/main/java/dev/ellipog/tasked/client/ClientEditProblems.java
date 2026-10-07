package dev.ellipog.tasked.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

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
 * <p>Reading consumes, like the replies: these are news rather than state, and a problem reported again on the
 * next frame reads as a repeating error.
 */
public final class ClientEditProblems {

    /** Plenty for a burst of edits; a bound so a never-drained store cannot grow without limit. */
    private static final int MAX = 32;

    /** One report: how many the server found, and the lines it could fit. */
    public record Report(int count, List<String> lines) {
    }

    private static final Deque<Report> reports = new ArrayDeque<>();

    private ClientEditProblems() {
    }

    /** Called by the payload handler, on the game thread. */
    public static synchronized void accept(int count, String text) {
        List<String> lines = text == null || text.isEmpty()
                ? List.of()
                : List.of(text.split("\n"));
        if (reports.size() >= MAX) {
            // The oldest goes, and unlike the marker queue that is safe here: these are independent
            // statements rather than a sequence matched to something, so losing one costs one message and
            // does not shift what the others mean.
            reports.removeFirst();
        }
        reports.addLast(new Report(count, lines));
    }

    /** Every report nobody has read yet, oldest first. Reading them consumes them. */
    public static synchronized List<Report> drain() {
        List<Report> out = new ArrayList<>(reports);
        reports.clear();
        return out;
    }

    /** Forgets everything: called when the client leaves the world it was editing. */
    public static synchronized void clear() {
        reports.clear();
    }
}
