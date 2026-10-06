package dev.ellipog.tasked.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The editor session's epoch: the signal a frame cache needs and the tree revision cannot give it.
 *
 * <h2>Why this is the whole reason the epoch exists</h2>
 *
 * <p>A node's screen position is read from here while an author is dragging it, and nothing about that
 * changes the tree: `moved(...)` records the position <b>at</b> a revision, and the revision the drag was
 * recorded at does not move until the server answers. So a reader that keyed a cache on the tree revision
 * would hand back the position the node had when the drag started — for the whole drag, which is the one
 * moment the position is changing fastest.
 *
 * <p>What this asserts is exactly that asymmetry: a recorded position and a clear are changes of answer,
 * and being called with the revision the positions are already recorded at is not.
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
}
