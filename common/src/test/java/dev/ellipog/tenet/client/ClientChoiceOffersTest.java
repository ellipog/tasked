package dev.ellipog.tenet.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The choice queue: offers come in order, and the two ways out mean different things.
 *
 * <p>The bug this exists to pin: a single slot kept the last offer and dropped the rest, so Claim all
 * across a book with several choices silently asked about one of them. A queue is the fix, and the
 * distinction it has to keep is between <b>popping</b> (answered, or kept for later — this offer leaves
 * the walk) and <b>clearing</b> (Escape — the whole walk ends).
 */
@DisplayName("the choice offer queue")
class ClientChoiceOffersTest {

    @AfterEach
    void clear() {
        ClientChoiceOffers.clear();
    }

    @Test
    @DisplayName("offers queue and pop in order, and none replaces another")
    void offersQueue() {
        ClientChoiceOffers.accept("first", 0, List.of());
        ClientChoiceOffers.accept("second", 1, List.of());

        assertEquals(2, ClientChoiceOffers.size(), "both are waiting; the second did not replace the first");
        assertEquals("first", ClientChoiceOffers.current().questId());
        ClientChoiceOffers.pop();
        assertEquals("second", ClientChoiceOffers.current().questId(), "the next one comes up");
        ClientChoiceOffers.pop();
        assertNull(ClientChoiceOffers.current(), "and the walk ends when the last is answered");
    }

    @Test
    @DisplayName("keeping one for later leaves the walk rather than coming back around")
    void keepingForLaterDoesNotRotate() {
        // The carousel this forbids: A skipped, B skipped, A back in the player's face. "Not now"
        // means not during this session; the reward waits in the inbox with its Choose button.
        ClientChoiceOffers.accept("a", 0, List.of());
        ClientChoiceOffers.accept("b", 0, List.of());

        ClientChoiceOffers.pop();   // keep "a" for later
        assertEquals("b", ClientChoiceOffers.current().questId());
        ClientChoiceOffers.pop();   // keep "b" for later too
        assertNull(ClientChoiceOffers.current(), "no offer comes back");
        assertEquals(0, ClientChoiceOffers.size());
    }

    @Test
    @DisplayName("clearing ends the whole walk, not just the question on screen")
    void clearingEndsTheWalk() {
        ClientChoiceOffers.accept("a", 0, List.of());
        ClientChoiceOffers.accept("b", 0, List.of());

        ClientChoiceOffers.clear();

        assertNull(ClientChoiceOffers.current());
        assertEquals(0, ClientChoiceOffers.size());
    }
}
