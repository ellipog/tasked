package dev.ellipog.tasked.quest;

/**
 * One tree refresh per burst of edits.
 *
 * <h2>Why a flag rather than doing it inline</h2>
 *
 * <p>Every accepted edit used to reload the whole questline from disk and broadcast the whole tree to
 * every player, immediately, per operation — and on the client every resulting revision rebuilds the
 * book screen. Spamming a stepper is a burst of operations a tick apart at most, so the work was paid
 * once per press for a state only the last press describes. An operation now marks the tree dirty and
 * the flush runs at most once per server tick, driven from the tick hook the server already has; the
 * reply to the author is still per operation and immediate.
 *
 * <h2>Why the flag is cleared before the refresh runs</h2>
 *
 * <p>An edit that arrives while the flush is running — the same tick's next packet, or another
 * author's — must arm the next flush rather than be lost inside this one. Clearing first and running
 * second is what makes that true, and it is the one ordering decision in this class.
 */
public final class TreeRefresh {

    private static volatile boolean dirty;

    private TreeRefresh() {
    }

    /** Marks the loaded tree stale: the next flush reloads and broadcasts it. */
    public static void request() {
        dirty = true;
    }

    /** Whether a flush is owed. */
    public static boolean pending() {
        return dirty;
    }

    /**
     * Runs the refresh once if one is owed, and once for any number of requests.
     *
     * <p>The refresh is a parameter rather than a call because the reload and the broadcast need a
     * server, and this class is deliberately about the flag rather than about Minecraft — which is
     * what lets the coalescing be tested without one.
     */
    public static void flush(Runnable refresh) {
        if (!dirty) {
            return;
        }
        dirty = false;
        refresh.run();
    }
}
