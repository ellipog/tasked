package dev.ellipog.tenet.quest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The coalescing itself, without a server: any number of requests, one refresh.
 *
 * <p>The refresh is a consumer here rather than a call, which is the whole reason the flag is its own
 * class — a burst of edits can be driven through it in a test JVM, and what the flush was handed can be
 * read back rather than inferred from what the server did.
 *
 * <p>The burst cases are the ones this round is about. A drag sends one op per client tick and every one
 * of them used to owe <b>every connected player</b> a full re-serialisation of every quest's resolved
 * state — so the assertion that matters is not that the flush happens once, which was already true, but
 * that what it owes survives being asked for forty times.
 */
class TreeRefreshTest {

    /** What the flush was handed, in order. */
    private final List<TreeRefresh.Touch> flushed = new ArrayList<>();

    /** The pinned clock, millis. Tests move it by hand; production never sees it. */
    private final long[] now = {100_000L};

    /**
     * The flag is static, and a case that fails before its flush leaves it armed — which would fail the
     * next case too and read as two bugs rather than one. Consuming whatever is owed is the cheapest way
     * to make this file order-independent.
     */
    @BeforeEach
    void nothingIsOwed() {
        TreeRefresh.setClock(() -> now[0]);
        now[0] = 100_000L;
        TreeRefresh.clear();
        TreeRefresh.flush((touch, chapters) -> {
        });
    }

    @org.junit.jupiter.api.AfterEach
    void restoreClock() {
        TreeRefresh.setClock(System::currentTimeMillis);
        TreeRefresh.clear();
    }

    private void flush() {
        TreeRefresh.flush((touch, chapters) -> flushed.add(touch));
    }

    @Test
    @DisplayName("any number of requests costs one flush, and a clean tree is not flushed")
    void requestsCoalesce() {
        TreeRefresh.request(TreeRefresh.Touch.CONTENT);
        TreeRefresh.request(TreeRefresh.Touch.CONTENT);
        TreeRefresh.request(TreeRefresh.Touch.CONTENT);
        assertTrue(TreeRefresh.pending());

        flush();
        assertEquals(List.of(TreeRefresh.Touch.CONTENT), flushed,
                "three requests, one reload and one broadcast");
        assertFalse(TreeRefresh.pending());

        flush();
        assertEquals(1, flushed.size(), "nothing was asked for, so nothing runs");
    }

    @Test
    @DisplayName("a table edit is its own reload, and owes nothing on the progress channel")
    void aTableEditIsItsOwnReload() {
        // The bug this pins: a weight press paid for the whole pack. A table edit re-read and
        // re-validated every quest file and re-synced every player's progress -- work a reward table
        // cannot have changed -- because the flag said only "something moved".
        TreeRefresh.requestTables();

        assertEquals(TreeRefresh.Touch.TABLES, TreeRefresh.pendingTouch());
        assertEquals(TreeRefresh.Touch.Scope.TABLES, TreeRefresh.Touch.TABLES.scope(),
                "the tables are re-read and the quests are not");
        assertEquals(TreeRefresh.Touch.Progress.NONE, TreeRefresh.Touch.TABLES.progress(),
                "and no player's resolved state can have moved");
        flush();
        assertEquals(List.of(TreeRefresh.Touch.TABLES), flushed);
    }

    @Test
    @DisplayName("the heavier of two edits in one tick is the one the flush owes")
    void theHeaviestRequestWins() {
        TreeRefresh.requestTables();
        TreeRefresh.request(TreeRefresh.Touch.COSMETIC);

        assertEquals(TreeRefresh.Touch.COSMETIC, TreeRefresh.pendingTouch(),
                "a quest edit subsumes a table edit: the reload reads the tables anyway");
        flush();
        assertEquals(List.of(TreeRefresh.Touch.COSMETIC), flushed,
                "one flush, and it is the one that covers both");
    }

    @Test
    @DisplayName("a burst of nudges owes no progress at all")
    void aBurstOfNudgesOwesNothing() {
        // The round's point as a test. Forty ticks of a drag is forty ops, and the cost that used to
        // come with them was forty full progress syncs per connected player.
        for (int round = 0; round < 40; round++) {
            TreeRefresh.request(TreeRefresh.Touch.COSMETIC);
        }

        assertEquals(TreeRefresh.Touch.Progress.NONE, TreeRefresh.pendingTouch().progress(),
                "where a node sits is not something a player's record holds");
        flush();
        assertEquals(List.of(TreeRefresh.Touch.COSMETIC), flushed);
    }

    @Test
    @DisplayName("a cheap edit cannot cheapen an expensive one in the same tick")
    void theOrderOfRequestsDoesNotMatter() {
        // The dangerous direction, and the reason coalescing takes the heaviest: an insertion and a
        // nudge arriving in one tick must not be answered with the nudge's answer, because the row that
        // moved position may be the row a stored count belongs to.
        TreeRefresh.request(TreeRefresh.Touch.FULL);
        TreeRefresh.request(TreeRefresh.Touch.COSMETIC);
        assertEquals(TreeRefresh.Touch.FULL, TreeRefresh.pendingTouch());

        TreeRefresh.request(TreeRefresh.Touch.CONTENT);
        assertEquals(TreeRefresh.Touch.FULL, TreeRefresh.pendingTouch(),
                "and a third edit in between does not change that either");
        flush();
    }

    @Test
    @DisplayName("a request that arrives during the flush arms the next one")
    void aRequestDuringTheFlushIsNotLost() {
        TreeRefresh.request(TreeRefresh.Touch.COSMETIC);
        TreeRefresh.flush((touch, chapters) -> {
            flushed.add(touch);
            // The same tick's next packet, or another author's edit: it must owe a flush of its own.
            TreeRefresh.request(TreeRefresh.Touch.CONTENT);
        });

        assertTrue(TreeRefresh.pending(), "the edit that arrived during the refresh owes another");
        flush();
        assertEquals(List.of(TreeRefresh.Touch.COSMETIC, TreeRefresh.Touch.CONTENT), flushed);
        assertFalse(TreeRefresh.pending());
    }

    @Test
    @DisplayName("a cosmetic flush names the chapters it would re-read")
    void cosmeticFlushNamesChapters() {
        TreeRefresh.request(TreeRefresh.Touch.COSMETIC, "first_steps");
        TreeRefresh.request(TreeRefresh.Touch.COSMETIC, "second_steps");
        TreeRefresh.request(TreeRefresh.Touch.COSMETIC, "first_steps");

        List<java.util.Set<String>> handed = new ArrayList<>();
        TreeRefresh.flush((touch, chapters) -> {
            flushed.add(touch);
            handed.add(chapters);
        });
        assertEquals(List.of(TreeRefresh.Touch.COSMETIC), flushed);
        assertEquals(List.of(java.util.Set.of("first_steps", "second_steps")), handed,
                "arrival order, deduplicated: the flush re-reads each dirty chapter once");
        assertFalse(TreeRefresh.pending());
    }

    @Test
    @DisplayName("a table edit sharing a tick with a nudge still owes its tables")
    void mixedTickKeepsTables() {
        TreeRefresh.requestTables();
        TreeRefresh.request(TreeRefresh.Touch.COSMETIC, "first_steps");

        assertEquals(TreeRefresh.Touch.COSMETIC, TreeRefresh.pendingTouch(),
                "the touch collapses, but the tables must not collapse with it");
        assertTrue(TreeRefresh.drainTables(), "read once, by the flush");
        assertFalse(TreeRefresh.drainTables(), "and consumed by the read");
        flush();
        assertFalse(TreeRefresh.pending());
    }

    @Test
    @DisplayName("a chapterless cosmetic still means a full reload, and clear forgets all of it")
    void chapterlessCosmeticMeansFullReload() {
        TreeRefresh.request(TreeRefresh.Touch.COSMETIC, "");
        TreeRefresh.request(TreeRefresh.Touch.COSMETIC, null);

        List<java.util.Set<String>> handed = new ArrayList<>();
        TreeRefresh.flush((touch, chapters) -> {
            flushed.add(touch);
            handed.add(chapters);
        });
        assertEquals(List.of(java.util.Set.of()), handed,
                "blank chapters are never tracked: a refresh of nothing is not an answer");
        assertFalse(TreeRefresh.pending());

        TreeRefresh.request(TreeRefresh.Touch.CONTENT, "first_steps");
        TreeRefresh.clear();
        assertFalse(TreeRefresh.pending(), "a reload already did the work");
        int flushedBefore = flushed.size();
        flush();
        assertEquals(flushedBefore, flushed.size(), "and nothing runs after it");
    }

    @Test
    @DisplayName("even a lone cosmetic edit waits out the quiet window")
    void loneCosmeticWaitsToo() {
        // The price of coalescing, stated plainly: the gate cannot know whether a second op
        // is coming, so it always waits. The author never feels it — optimistic drafts cover
        // their own echo — and anything heavier than cosmetic skips the wait entirely.
        TreeRefresh.request(TreeRefresh.Touch.COSMETIC, "first_steps");
        assertFalse(TreeRefresh.due());
        now[0] += TreeRefresh.COSMETIC_QUIET_MILLIS;
        assertTrue(TreeRefresh.due());
        flush();
        assertFalse(TreeRefresh.pending());
    }

    @Test
    @DisplayName("two quick cosmetic edits cost one refresh")
    void twoQuickEditsCostOneRefresh() {
        TreeRefresh.request(TreeRefresh.Touch.COSMETIC, "first_steps");
        assertFalse(TreeRefresh.due(), "the quiet window just opened");

        now[0] += 200L;
        TreeRefresh.request(TreeRefresh.Touch.COSMETIC, "first_steps");
        assertFalse(TreeRefresh.due(), "200 ms after the last op is inside it");

        now[0] += 500L;
        assertTrue(TreeRefresh.due(), "500 ms after the last op the window has passed");
        flush();
        assertEquals(1, flushed.size(), "two ops, one refresh, one broadcast");
        assertFalse(TreeRefresh.pending());
    }

    @Test
    @DisplayName("a slow trickle of cosmetic edits still syncs inside the cap")
    void trickleSyncsInsideCap() {
        // An op every 100 ms forever: quiescence never arrives on its own, so the cap fires
        // about every two seconds and the server breathes instead of reloading per op.
        for (int round = 0; round < 25; round++) {
            now[0] += 100L;
            TreeRefresh.request(TreeRefresh.Touch.COSMETIC, "first_steps");
            if (TreeRefresh.due()) {
                flush();
            }
        }
        assertEquals(1, flushed.size(), "first cap firing covers the whole trickle so far");
        now[0] += TreeRefresh.COSMETIC_MAX_MILLIS;
        assertTrue(TreeRefresh.due());
        flush();
        assertEquals(2, flushed.size());
        assertFalse(TreeRefresh.pending(), "and the tail goes out with it");
    }

    @Test
    @DisplayName("anything heavier than cosmetic ignores the clock")
    void heavyEditsIgnoreTheClock() {
        TreeRefresh.request(TreeRefresh.Touch.COSMETIC, "first_steps");
        flush();
        now[0] += 10L;

        TreeRefresh.request(TreeRefresh.Touch.CONTENT, "first_steps");
        assertTrue(TreeRefresh.due(), "stored progress may have moved — no waiting");
        TreeRefresh.request(TreeRefresh.Touch.TABLES);
        assertTrue(TreeRefresh.due());
        flush();
        assertFalse(TreeRefresh.pending());
    }

    @Test
    @DisplayName("nothing pending is never due")
    void nothingPendingIsNeverDue() {
        now[0] += 1_000_000L;
        assertFalse(TreeRefresh.due());
    }
}
