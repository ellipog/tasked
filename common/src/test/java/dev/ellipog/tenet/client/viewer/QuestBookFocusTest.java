package dev.ellipog.tenet.client.viewer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pending focus: parked by a viewer's click, taken once by the book, dropped on disconnect.
 *
 * <p>Small on purpose, and tested on purpose: the request crosses a boundary the screen does not
 * control -- the book is constructed later, by Minecraft's screen machinery -- and the two ways that
 * can go wrong are both silent. A request that is never consumed opens a plain book, and one that is
 * consumed twice opens a second card nobody asked for.
 */
class QuestBookFocusTest {

    @AfterEach
    void clear() {
        QuestBookFocus.clear();
    }

    @Test
    @DisplayName("a request is taken once and only once")
    void aRequestIsTakenOnce() {
        QuestBookFocus.request("first_steps");

        assertEquals(Optional.of("first_steps"), QuestBookFocus.consume());
        assertTrue(QuestBookFocus.consume().isEmpty(),
                "the second consume in a row must find nothing, or a screen rebuild would reopen the card");
    }

    @Test
    @DisplayName("nothing requested means nothing consumed")
    void nothingRequested() {
        assertTrue(QuestBookFocus.consume().isEmpty());
    }

    @Test
    @DisplayName("a cleared request is not consumed, and an empty id is not a request")
    void clearAndEmpty() {
        QuestBookFocus.request("a_quest");
        QuestBookFocus.clear();
        assertTrue(QuestBookFocus.consume().isEmpty(), "disconnect drops a pending request");

        QuestBookFocus.request("");
        QuestBookFocus.request(null);
        assertTrue(QuestBookFocus.consume().isEmpty(), "an id that cannot name a quest is not a request");
    }
}
