package dev.ellipog.tenet.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor session's epoch: the signal a frame cache needs and the tree revision cannot give it.
 *
 * <h2>Why this is the whole reason the epoch exists</h2>
 *
 * <p>A node's screen position is read from here while an author is dragging it, and nothing about that
 * changes the tree: {@code moved(...)} records the position <b>at</b> a revision, and the revision the drag
 * was recorded at does not move until the server answers. So a reader that keyed a cache on the tree revision
 * would hand back the position the node had when the drag started — for the whole drag, which is the one
 * moment the position is changing fastest.
 *
 * <p>What this asserts is exactly that asymmetry: a recorded position and a clear are changes of answer, and
 * being called with the revision the positions are already recorded at is not.
 *
 * <h2>What the clear means, and the mistake worth recording</h2>
 *
 * <p><b>What this class stores is "the server has not answered yet."</b> A revision that moves means it has
 * answered, so <i>every</i> pending position is stale at that moment — which is why {@link
 * EditorSession#onRevision} clears wholesale rather than dropping only the entries the new tree disagrees
 * with. Making it selective is a change that looks like a strict improvement and is not: a position kept past
 * the server's answer is drawn over the top of it, because {@code QuestBookScreen.nodeX} falls back to
 * {@code movedX} for any id that is present, with no further check.
 *
 * <p>That was tried, and the (0,0) write it was meant to help with is fixed somewhere else entirely — see
 * {@link #aPendingPositionIsGoneOnceTheTreeAnswers}, and {@code QuestBookScreen}'s commit loop, which now asks
 * {@link EditorSession#hasMoved} before reading a position.
 */
@DisplayName("the editor session's epoch")
class EditorSessionEpochTest {

    @Test
    @DisplayName("a recorded position moves it, and the tree arriving does too, but nothing else")
    void theEpochMovesOnEveryChangeOfAnswer() {
        EditorSession session = new EditorSession();
        long atStart = session.epoch();

        session.moved("a_quest", 12, 34, 7L);
        long afterMove = session.epoch();
        assertNotEquals(atStart, afterMove, "a position was recorded, so the answer changed");

        session.onRevision(7L);
        assertEquals(afterMove, session.epoch(),
                "the tree the positions were recorded at is the tree they are still waiting on: nothing "
                        + "has changed");

        session.moved("a_quest", 56, 78, 7L);
        assertNotEquals(afterMove, session.epoch(), "and the same node moved again is a new answer");

        long afterSecondMove = session.epoch();
        session.onRevision(8L);
        assertNotEquals(afterSecondMove, session.epoch(),
                "a newer tree clears the pending positions, which is a change like any other — the files' "
                        + "own answer takes over and a cache stamped while they were pending is wrong");
    }

    @Test
    @DisplayName("a pending position is gone once the tree answers, agreeing or not")
    void aPendingPositionIsGoneOnceTheTreeAnswers() {
        // The semantics, stated as a test because getting them backwards is easy and the consequence is
        // visible: this class means "the server has not answered yet", so a revision that moves invalidates
        // *everything* pending. An entry kept here is drawn, not merely remembered -- `nodeX` falls back to
        // `movedX` for any id present -- so keeping one past the server's answer would put the node back
        // where the author left it while the file said otherwise.
        EditorSession session = new EditorSession();
        session.moved("moved_by_me", 40, 50, 3L);
        session.moved("moved_elsewhere", 60, 70, 3L);

        session.onRevision(4L);

        assertFalse(session.hasMoved("moved_by_me"),
                "the server has answered, so nothing here is still waiting on it");
        assertFalse(session.hasMoved("moved_elsewhere"),
                "and that is true whether or not the new tree happens to agree about this quest");
    }

    @Test
    @DisplayName("hasMoved is what the commit path must ask, because a dropped position reads as zero")
    void aDroppedPositionReadsAsZero() {
        // **This is the (0,0) fault, as a property rather than as a screen.** `movedX` answers zero for an id
        // it does not hold, so a caller that reads a position without asking `hasMoved` first gets (0,0) for
        // a node it merely *remembers* having moved. A multi-node drag release did exactly that for every
        // other selected quest whenever the pending positions had been dropped between the last mouse-move and
        // the release -- and (0,0) is a real position, so the file and the canvas agreed and the loss showed
        // up only as a diff.
        //
        // The fix is the read path's own rule, which `nodeX` has always followed: ask, then read.
        EditorSession session = new EditorSession();
        session.moved("moved_by_me", 40, 50, 3L);
        session.onRevision(4L);

        assertEquals(0, session.movedX("moved_by_me"),
                "an id the session no longer holds answers zero -- which is why reading it blind is the bug");
        assertFalse(session.hasMoved("moved_by_me"),
                "and `hasMoved` is the question that distinguishes 'no longer pending' from 'at the origin'");
    }

    @Test
    @DisplayName("a position is readable while it is genuinely pending")
    void aPendingPositionIsReadable() {
        EditorSession session = new EditorSession();
        session.moved("still_pending", 100, 200, 5L);

        session.onRevision(5L);

        assertTrue(session.hasMoved("still_pending"),
                "the revision has not moved, so the server has not answered and the author's position stands");
        assertEquals(100, session.movedX("still_pending"));
        assertEquals(200, session.movedY("still_pending"));
    }
}
