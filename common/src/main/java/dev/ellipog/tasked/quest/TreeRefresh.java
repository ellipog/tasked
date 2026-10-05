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

    /**
     * How much of the loaded state moved, and therefore what a refresh has to do.
     *
     * <h2>Why a table edit is not a quest edit</h2>
     *
     * <p>Both end in "reload and broadcast the tree", and the reload was the same full
     * {@code QuestLoader.load} for either: every quest file, every manifest and every table re-read and
     * re-validated, then the whole tree re-serialised and re-sent to every player, and then a full
     * progress sync — which a table edit cannot possibly have changed. A weight press paid for the pack.
     *
     * <p>So the flag carries the <b>kind</b> of change: {@link #ALL} for anything that can touch quests,
     * {@link #TABLES} for the reward tables alone, which reload only {@code reward_tables/*.json} against
     * the index already in memory and broadcast the tree without touching progress. {@link #ALL} wins
     * whenever both are asked for in one tick, because a quest edit's refresh subsumes a table's.
     */
    public enum Touch {
        NONE,
        TABLES,
        ALL
    }

    private static volatile Touch dirty = Touch.NONE;

    private TreeRefresh() {
    }

    /** Marks the loaded tree stale: the next flush reloads and broadcasts it. */
    public static void request() {
        dirty = Touch.ALL;
    }

    /** The same, for an edit that can only have changed the reward tables. */
    public static void requestTables() {
        if (dirty == Touch.NONE) {
            dirty = Touch.TABLES;
        }
    }

    /** Whether a flush is owed. */
    public static boolean pending() {
        return dirty != Touch.NONE;
    }

    /** What the next flush owes, without consuming it. */
    public static Touch pendingTouch() {
        return dirty;
    }

    /**
     * Runs the refresh once if one is owed, and once for any number of requests.
     *
     * <p>The refresh is a parameter rather than a call because the reload and the broadcast need a
     * server, and this class is deliberately about the flag rather than about Minecraft — which is
     * what lets the coalescing be tested without one.
     *
     * <p>Two refreshes, because there are two kinds of change: {@code tables} runs for a
     * {@link Touch#TABLES} flush and {@code all} for a {@link Touch#ALL} one. A caller that only has one
     * way to refresh passes it as {@code all}, which is what every caller did before the split.
     */
    public static void flush(Runnable all, Runnable tables) {
        Touch owed = dirty;
        if (owed == Touch.NONE) {
            return;
        }
        dirty = Touch.NONE;
        if (owed == Touch.TABLES) {
            tables.run();
        }
        else {
            all.run();
        }
    }

    /** The one-refresh form: every kind of flush runs it. */
    public static void flush(Runnable refresh) {
        flush(refresh, refresh);
    }
}
