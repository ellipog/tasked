package dev.ellipog.tasked.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pack's own name and icon for the book: they arrive with the tree, and they leave with it.
 *
 * <h2>Why the cache is where this is asserted</h2>
 *
 * <p>The header's drawing cannot be called without a client, but everything it reads can: the two
 * values, the resolved stack, and the difference between "no icon declared" and "an id that did not
 * resolve" -- which is the difference between a header with no mark and a header with the missing-item
 * one. That distinction is the reason the id is kept beside the stack at all, so it is the first thing
 * asserted here.
 */
@DisplayName("The book's own identity")
class ClientBookIdentityTest {

    private static void tree(String json) {
        ClientQuestCache.acceptTree(0, 0, null, json.getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void forget() {
        // A static cache: a test that leaves a tree behind changes the next one.
        ClientQuestCache.clear();
    }

    @Test
    @DisplayName("a pack's name and icon arrive with its tree")
    void theIdentityArrives() {
        tree("""
                {"version":10,"bookTitle":"The Orrery Ledger","bookIcon":"minecraft:spyglass","quests":[]}""");

        assertEquals("The Orrery Ledger", ClientQuestCache.bookTitle());
        assertEquals("minecraft:spyglass", ClientQuestCache.bookIconId());
        assertFalse(ClientQuestCache.bookIcon().isEmpty(), "the item resolves on this client");
    }

    @Test
    @DisplayName("no icon and a missing item are different answers")
    void missingIsNotAbsent() {
        tree("""
                {"version":10,"bookTitle":"","bookIcon":"","quests":[]}""");
        assertEquals("", ClientQuestCache.bookTitle(), "empty means the client's own title");
        assertEquals("", ClientQuestCache.bookIconId(), "and no icon at all");
        assertTrue(ClientQuestCache.bookIcon().isEmpty());

        tree("""
                {"version":10,"bookIcon":"no_such_mod:no_such_item","quests":[]}""");
        assertEquals("no_such_mod:no_such_item", ClientQuestCache.bookIconId(),
                "the id is kept, so the header can mark the missing item");
        assertTrue(ClientQuestCache.bookIcon().isEmpty(), "while the stack is empty");
    }

    @Test
    @DisplayName("a disconnect forgets the book with everything else")
    void clearForgetsTheBook() {
        tree("""
                {"version":10,"bookTitle":"The Orrery Ledger","bookIcon":"minecraft:spyglass","quests":[]}""");

        ClientQuestCache.clear();

        assertEquals("", ClientQuestCache.bookTitle());
        assertEquals("", ClientQuestCache.bookIconId());
        assertTrue(ClientQuestCache.bookIcon().isEmpty());
    }
}
