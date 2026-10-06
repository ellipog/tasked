package dev.ellipog.tasked.quest;

import java.util.function.Consumer;

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
 *
 * <h2>Two questions, not one</h2>
 *
 * <p>The flag used to answer one question — "was this a quest edit or a table edit" — and both answers
 * ended the same way: reload, broadcast the tree, and <b>re-sync every player's progress in full</b>.
 * That last half is the expensive one and it was paid for edits that cannot possibly have moved any
 * player's progress. Nudging a node two pixels to the right, which is a drag and therefore a burst of
 * ops, re-serialised every quest's resolved state for every connected player, once per tick of the
 * drag.
 *
 * <p>So the flag answers two: <b>what has to be re-read</b> ({@link Scope}) and <b>what has to be
 * re-sent about progress</b> ({@link Progress}). The second is the one worth getting right, and its
 * rule is about what progress <i>is</i>: a player's record is stored against a quest's <b>id</b>, and
 * against a task's or a reward's <b>position</b> within that quest. An edit that moves ids around is
 * handled by a delta, which is keyed by id and recomputes every state before sending what differs. An
 * edit that can move a task's or a reward's <i>position</i> is not: the client's counts are read by
 * position, so every one of them after the edit would mean a different row. That is the whole of
 * {@link Touch#FULL}.
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
     * <h2>The constants are declared in the order they owe</h2>
     *
     * <p>Each step down this list owes strictly more than the one above it — more to re-read, or a
     * louder progress sync, or both — so coalescing two edits is taking the later constant. That is not
     * a coincidence to be relied on quietly: it is the reason the list may not be reordered, and the
     * reason no two constants may share a {@link Scope} <i>and</i> a {@link Progress}. A pair with two
     * names would be a difference that does not exist, and the next person to change one of them would
     * have to find the other.
     */
    public enum Touch {

        /** Nothing owed. */
        NONE(Scope.NONE, Progress.NONE),

        /**
         * The reward tables only: the quests did not move.
         *
         * <p>Reloads {@code reward_tables/*.json} against the index already in memory, and leaves
         * progress alone — a reward table cannot have changed a player's resolved state.
         */
        TABLES(Scope.TABLES, Progress.NONE),

        /**
         * The quests, in a way that cannot have moved anything a player has.
         *
         * <p>Where a node sits, what it looks like, what it is called. The tree goes out because every
         * client draws it; no progress message goes with it, because a player's record holds none of
         * these things.
         */
        COSMETIC(Scope.QUESTS, Progress.NONE),

        /**
         * The quests, in a way that can move a resolved state but no row's position.
         *
         * <p>A rule, a dependency, a task's item, an id, an alias, a quest that appeared or one that
         * was deleted. A delta covers all of them: it is keyed by quest id, it recomputes every state
         * before sending, and it names the ids it removed rather than relying on their absence.
         */
        CONTENT(Scope.QUESTS, Progress.DELTA),

        /**
         * The quests, in a way whose effect on stored progress cannot be bounded.
         *
         * <p>A row can have moved position — something was inserted, removed or reordered — or the
         * whole chapter was rolled back to an earlier snapshot, which can have moved anything at all.
         * Progress is read by position within a quest, so a delta would leave every count after the
         * edit attached to the wrong row.
         */
        FULL(Scope.QUESTS, Progress.FULL);

        /** What a refresh has to re-read. */
        public enum Scope {

            /** Nothing worth re-reading: the files on disk are already what is loaded. */
            NONE,

            /** The reward tables alone, against the index already in memory. */
            TABLES,

            /** Every quest file, manifest and table, and the settings block with them. */
            QUESTS
        }

        /** What a refresh has to re-send about progress. */
        public enum Progress {

            /** Nothing: no player's resolved state can have moved. */
            NONE,

            /** A delta: the states that differ from what each player was last sent. */
            DELTA,

            /** The whole of it, because a row's position may have moved under a stored count. */
            FULL
        }

        private final Scope scope;
        private final Progress progress;

        Touch(Scope scope, Progress progress) {
            this.scope = scope;
            this.progress = progress;
        }

        /** What this owes a reload. */
        public Scope scope() {
            return scope;
        }

        /** What this owes the progress channel. */
        public Progress progress() {
            return progress;
        }

        /**
         * The heavier of two, which is what a burst of edits owes.
         *
         * <p>Relies on the declaration order, which is documented above as the thing that may not
         * change.
         */
        public Touch strongest(Touch other) {
            return other.ordinal() > ordinal() ? other : this;
        }
    }

    private static volatile Touch dirty = Touch.NONE;

    /** Marks the loaded tree stale, as far as one edit can tell: the next flush owes {@code touch}. */
    public static void request(Touch touch) {
        dirty = dirty.strongest(touch);
    }

    /** The same, for an edit that can only have changed the reward tables. */
    public static void requestTables() {
        request(Touch.TABLES);
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
     * <p>The refresh takes the touch rather than being one of two runnables, and that is the shape of
     * this round's change: a caller that was handed "full or tables" had to decide the progress channel
     * for itself, and the only caller decided it wrongly for every kind of edit there is. Handed the
     * touch, a caller has nothing to decide — see {@link Touch} for what each one owes and why.
     *
     * <p>The refresh is a parameter rather than a call because the reload and the broadcast need a
     * server, and this class is deliberately about the flag rather than about Minecraft — which is
     * what lets the coalescing be tested without one.
     */
    public static void flush(Consumer<Touch> refresh) {
        Touch owed = dirty;
        if (owed == Touch.NONE) {
            return;
        }
        dirty = Touch.NONE;
        refresh.accept(owed);
    }
}
