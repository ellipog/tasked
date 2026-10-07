package dev.ellipog.tenet.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The news that the server threw its undo history away, and the two properties that make it usable.
 *
 * <h2>Why this is a flag the screen drains rather than a message a handler shows</h2>
 *
 * <p>The payload arrives on the game thread with whatever is open, which may be no book at all — a reload is
 * an operator's command and the players it affects are not necessarily looking at anything. A handler that
 * reached for a screen would have to decide what to do when there is none, and keeping the news until
 * something wants it is the answer the reply store already uses.
 *
 * <p>Reading <b>consumes</b>, because this is news rather than state: the book says it once, and a notice
 * reported again on the next frame reads as a repeating error. And it is a <b>flag</b> rather than a queue,
 * because two reloads between two frames are one fact — the history is gone — and reporting it twice would be
 * the same repeating-error bug with a longer fuse.
 */
@DisplayName("the client's edit-history notice")
class ClientEditHistoryTest {

    @BeforeEach
    void reset() {
        ClientEditHistory.clear();
    }

    @Test
    @DisplayName("nothing is waiting until the server says something")
    void nothingIsWaitingAtFirst() {
        // A fresh client, and one that has just left a world: the book must not report a discard that never
        // happened, which would clear an author's undo counters for no reason and say so.
        assertFalse(ClientEditHistory.takeDiscarded(), "a client nobody has told has nothing to report");
    }

    @Test
    @DisplayName("a discard is reported once, and reading it consumes it")
    void aDiscardIsReportedOnce() {
        ClientEditHistory.discard();

        assertTrue(ClientEditHistory.takeDiscarded(),
                "the notice the server sent has to reach the screen, or the undo button stays live");
        assertFalse(ClientEditHistory.takeDiscarded(),
                "and it is news, not state: reporting it again on the next frame is a repeating error");
    }

    @Test
    @DisplayName("two discards between two frames are one fact, not two")
    void twoDiscardsAreOneFact() {
        // A flag rather than a queue, deliberately. Two reloads before the book next drew are one thing that
        // happened to its undo history, and saying it twice would be a message about nothing.
        ClientEditHistory.discard();
        ClientEditHistory.discard();

        assertTrue(ClientEditHistory.takeDiscarded(), "the first read reports it");
        assertFalse(ClientEditHistory.takeDiscarded(), "and the second has nothing left to say");
    }

    @Test
    @DisplayName("leaving the world forgets a notice nobody read")
    void clearForgetsAnUnreadNotice() {
        // A discard recorded just before a disconnect belongs to the session that ended: the next world has
        // its own history, and a stale notice would clear counters that were never stale.
        ClientEditHistory.discard();
        ClientEditHistory.clear();

        assertFalse(ClientEditHistory.takeDiscarded(), "the notice did not outlive the world it was about");
    }

    @Test
    @DisplayName("a discard after a read is reported again, because it is a new fact")
    void aLaterDiscardIsReported() {
        ClientEditHistory.discard();
        assertTrue(ClientEditHistory.takeDiscarded());

        ClientEditHistory.discard();
        assertTrue(ClientEditHistory.takeDiscarded(),
                "a second reload is a second loss of history, so the screen has to hear about it too");
    }
}
