package dev.ellipog.tenet.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The replica gate: when a panel may ask for a copy, and when it has to wait.
 *
 * <h2>The bug these pin</h2>
 *
 * <p>The gate was a two-second timer on the last <i>attempt</i>, whatever revision it was for. A tree
 * revision that arrived inside that window therefore could not be fetched at all — and with edits
 * arriving closer together than the window (another author, or a burst) a panel's copy could stay stale
 * for as long as they kept going. That is the "the selected table lags behind" report, and the same
 * shape in the chapter replica is what made a table panel wait for its file.
 *
 * <p>So the rule is per revision: a <b>newer</b> revision is a new question and may be asked at once,
 * while the retry window stays as the backstop for the <i>same</i> one — a refusal, or a file that has
 * not appeared yet.
 */
@DisplayName("the replica gate")
class ReplicaGateTest {

    /** The revision a panel starts asking at, and the one after it. */
    private static final long FIRST = 10L;
    private static final long SECOND = 11L;

    @BeforeEach
    void fresh() {
        ClientTableReplica.clear();
        ClientChapterReplica.clear();
    }

    @Test
    @DisplayName("a table copy is asked for once, and a new revision is asked for at once")
    void aNewerRevisionIsAskedForImmediately() {
        assertTrue(ClientTableReplica.claim("dice", FIRST, 1_000L), "nothing has been asked for yet");
        assertFalse(ClientTableReplica.claim("dice", FIRST, 1_001L),
                "the same revision inside the window waits");

        // The tree moved: a different question, and the timer has nothing to say about it.
        assertTrue(ClientTableReplica.claim("dice", SECOND, 1_002L),
                "a newer revision does not wait out the window");
    }

    @Test
    @DisplayName("the generation moves on every change a reader would see")
    void theGenerationMovesOnEveryChange() {
        // The fix for a panel that sat on "Waiting for this table's file..." forever. It cached the table
        // it had decoded against the copy's *revision*, which is the tree's -- and the tree's is zero in a
        // fresh world, so "dice@0" before the file arrived and "dice@0" after it did were one key and the
        // copy was never read. A generation cannot be equal on both sides of a change.
        long before = ClientTableReplica.generation();

        ClientTableReplica.accept("dice", "{ \"entries\": [] }", 0L, java.util.List.of());
        long afterAccept = ClientTableReplica.generation();
        assertTrue(afterAccept > before, "a copy arriving is a change, whatever revision it carries");

        ClientTableReplica.refuse("toll", "you may not read this");
        assertTrue(ClientTableReplica.generation() > afterAccept, "so is a refusal");

        long afterRefuse = ClientTableReplica.generation();
        ClientTableReplica.clear();
        assertTrue(ClientTableReplica.generation() > afterRefuse, "and so is forgetting everything");

        // A refusal with nothing to say is not a change: nothing is recorded, and the map a reader sees is
        // exactly as it was.
        long settled = ClientTableReplica.generation();
        ClientTableReplica.refuse("dice", "");
        assertTrue(ClientTableReplica.generation() == settled, "an empty refusal remembers nothing");
    }

    @Test
    @DisplayName("the copy that answers a revision stops that revision being asked for again")
    void anAnsweredRevisionIsSettled() {
        assertTrue(ClientTableReplica.claim("dice", FIRST, 1_000L));
        ClientTableReplica.accept("dice", "{ \"entries\": [] }", FIRST, java.util.List.of());

        assertNotNull(ClientTableReplica.of("dice"));
        assertFalse(ClientTableReplica.claim("dice", FIRST, 1_001L), "the copy is current");
        assertTrue(ClientTableReplica.claim("dice", SECOND, 1_002L), "and a new revision asks again");
    }

    @Test
    @DisplayName("a refused revision is retried, but no faster than the window")
    void aRefusalRetriesOnTheClock() {
        // The refusal is remembered so a panel can say what the server said, and the gate keeps a
        // screen that asks every frame from becoming a flood.
        assertTrue(ClientTableReplica.claim("missing", FIRST, 1_000L));
        // A refusal is an answer with no copy: the panel keeps the sentence, and there is nothing for
        // `accept` to take. (An accepted copy is what clears the sentence -- it is the server saying the
        // table is there now.)
        ClientTableReplica.refuse("missing", "no reward table named \"missing\"");
        assertNotNull(ClientTableReplica.refusal("missing"));

        assertFalse(ClientTableReplica.claim("missing", FIRST, 1_001L), "not a frame later");
        assertTrue(ClientTableReplica.claim("missing", FIRST,
                1_000L + ClientTableReplica.RETRY_MILLIS), "and again once the window has passed");
        assertNotNull(ClientTableReplica.refusal("missing"), "the sentence stays until a copy arrives");
    }

    @Test
    @DisplayName("a chapter copy is gated the same way, per revision")
    void theChapterGateAgrees() {
        assertTrue(ClientChapterReplica.claim("first_steps", FIRST, 1_000L));
        assertFalse(ClientChapterReplica.claim("first_steps", FIRST, 1_001L));
        assertTrue(ClientChapterReplica.claim("first_steps", SECOND, 1_002L),
                "a newer revision is a new question");
    }

    @Test
    @DisplayName("an empty answer is not a copy, so the question is asked again")
    void anEmptyAnswerIsNotUsable() {
        // An empty file is the server saying it has nothing to send -- and settling on it would leave
        // a panel claiming a copy is on its way for the rest of the session.
        assertTrue(ClientTableReplica.claim("empty", FIRST, 1_000L));
        ClientTableReplica.accept("empty", "", FIRST, java.util.List.of());

        assertTrue(ClientTableReplica.claim("empty", FIRST,
                1_000L + ClientTableReplica.RETRY_MILLIS), "still nothing to show, so ask again");
    }
}
