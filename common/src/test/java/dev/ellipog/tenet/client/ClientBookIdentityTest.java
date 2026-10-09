package dev.ellipog.tenet.client;

import dev.ellipog.tenet.client.viewer.MinecraftTestBootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

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

    @BeforeAll
    static void bootstrap() {
        // The overlay below bumps `ClientQuestCache.textRevision()`, which initialises
        // `ClientQuestCache` — and that class's initialiser resolves item stacks against
        // `BuiltInRegistries.ITEM`. See `ClientLocaleTest` for the poisoning this avoids.
        MinecraftTestBootstrap.boot();
    }

    private static void tree(String json) {
        ClientQuestCache.acceptTree(0, 0, null, json.getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void forget() {
        // A static cache: a test that leaves a tree behind changes the next one. And the overlay,
        // for the same reason: a locale left by a test that translated the title would answer for
        // the next test's lookup, which would pass without the tree having carried anything.
        ClientQuestCache.clear();
        ClientLocale.clear();
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

    @Test
    @DisplayName("the book's title reads the locale overlay first, and the editor reads the raw tree")
    void theTitleReadsTheOverlayFirst() {
        // The header draws what the player reads, so it resolves `book.title` over the tree's own
        // title — the road a pack that translates its name uses. The editor instead seeds its Book
        // field from the raw tree, because a translation written back would replace the author's
        // own words with somebody else's translation of them.
        tree("""
                {"version":10,"bookTitle":"The Orrery Ledger","quests":[]}""");
        assertEquals("The Orrery Ledger", ClientQuestCache.bookTitle(),
                "with no overlay the tree's own title is what the header draws");

        ClientLocale.accept("hu_hu", "hu_hu", Map.of("book.title", "Az Orrery Fokonyv"));

        assertEquals("Az Orrery Fokonyv", ClientQuestCache.bookTitle(),
                "the overlay wins where the header draws");
        assertEquals("The Orrery Ledger", ClientQuestCache.bookTitleRaw(),
                "while the raw tree keeps the author's words for the editor");
    }
}
