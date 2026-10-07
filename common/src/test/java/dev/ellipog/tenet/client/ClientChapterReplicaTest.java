package dev.ellipog.tenet.client;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client's copy of a chapter: what it holds, and the two rules that keep it honest.
 *
 * <h2>The three faults this holds shut</h2>
 *
 * <p>A request per frame — which works, and is why nobody would notice until a server log filled up. A copy
 * that is stale without anybody knowing — {@code claim} answers both by asking only while no usable copy
 * exists, and no faster than its retry window.
 *
 * <p>And the one a player found: a request that was <b>refused</b>, or answered with an empty chapter file,
 * used to leave the panel saying "the copy has not arrived yet" for good, because nothing but a tree
 * revision could re-arm the ask. An empty chapter tree is not an answer, and a refusal is remembered so the
 * panel can say what the server said — the two cases below are that fix, asserted rather than described.
 */
@DisplayName("The client's copy of a chapter")
class ClientChapterReplicaTest {

    private static final String QUESTS = """
            {
              "one": { "id": "one", "title": "One", "x": 0, "icon": { "item": "minecraft:oak_log" } },
              "two": { "id": "two", "title": "Two", "unknownToThisBuild": { "anything": [1, 2] } }
            }
            """;

    private static final String CHAPTER = """
            { "id": "first_steps", "title": "First Steps", "progressionMode": "FLEXIBLE" }
            """;

    @AfterEach
    void clear() {
        ClientChapterReplica.clear();
    }

    @Test
    @DisplayName("it keeps every quest's own tree, including fields the synced tree drops")
    void itHoldsTheFiles() {
        ClientChapterReplica.accept("first_steps", QUESTS, CHAPTER, 7L);

        JsonObject two = ClientChapterReplica.quest("first_steps", "two");
        assertNotNull(two);
        assertEquals("Two", two.get("title").getAsString());
        assertTrue(two.has("unknownToThisBuild"), "an addon's type is the whole reason for the copy");
        assertNull(ClientChapterReplica.quest("first_steps", "three"));
        assertNull(ClientChapterReplica.quest("nowhere", "one"));
    }

    @Test
    @DisplayName("it asks while no usable copy exists, and no faster than the retry window")
    void claimAsksWithoutFlooding() {
        assertTrue(ClientChapterReplica.claim("first_steps", 7L, 0L), "nothing loaded: ask");
        assertFalse(ClientChapterReplica.claim("first_steps", 7L, 10L),
                "asked a moment ago: do not ask again");
        assertFalse(ClientChapterReplica.claim("first_steps", 7L, ClientChapterReplica.RETRY_MILLIS - 1),
                "still inside the window: no flood");
        assertTrue(ClientChapterReplica.claim("first_steps", 7L, ClientChapterReplica.RETRY_MILLIS),
                "the window has passed and there is still no copy: ask again");

        ClientChapterReplica.accept("first_steps", QUESTS, CHAPTER, 7L);
        assertFalse(ClientChapterReplica.claim("first_steps", 7L, ClientChapterReplica.RETRY_MILLIS * 10),
                "current: nothing to ask for, however long the panel stays open");

        assertTrue(ClientChapterReplica.claim("first_steps", 8L, ClientChapterReplica.RETRY_MILLIS * 10),
                "the tree moved: ask again");
    }

    @Test
    @DisplayName("an empty chapter file is not an answer, so the ask keeps coming back")
    void anEmptyChapterTreeIsNotAnAnswer() {
        // The server sends "{}" when it has no chapter file to send. Counting that as the copy is how the
        // Chapter tab came to say "has not arrived yet" forever while the server had already answered.
        assertTrue(ClientChapterReplica.claim("first_steps", 7L, 0L), "the request goes out");
        ClientChapterReplica.accept("first_steps", QUESTS, "{}", 7L);

        assertFalse(ClientChapterReplica.claim("first_steps", 7L, 10L),
                "the attempt was a moment ago: still inside the window");
        assertTrue(ClientChapterReplica.claim("first_steps", 7L, ClientChapterReplica.RETRY_MILLIS),
                "an empty chapter file leaves the ask open, so a later load heals the panel");
    }

    @Test
    @DisplayName("a refusal is remembered, shown, and does not close the ask")
    void aRefusalIsRemembered() {
        ClientChapterReplica.claim("first_steps", 7L, 0L);
        ClientChapterReplica.refuse("first_steps", "no chapter called \"first_steps\"");

        assertEquals("no chapter called \"first_steps\"", ClientChapterReplica.refusal("first_steps"),
                "the panel's placeholder shows what the server said");
        assertTrue(ClientChapterReplica.claim("first_steps", 7L, ClientChapterReplica.RETRY_MILLIS),
                "a refusal does not stop the retry -- the file may have been fixed");

        ClientChapterReplica.accept("first_steps", QUESTS, CHAPTER, 7L);
        assertNull(ClientChapterReplica.refusal("first_steps"), "a copy that arrives clears the refusal");
    }

    @Test
    @DisplayName("a copy that cannot be read leaves an empty one, not a half-parsed one")
    void malformedIsEmpty() {
        ClientChapterReplica.accept("first_steps", "not json at all", "{}", 1L);

        assertNotNull(ClientChapterReplica.of("first_steps"));
        assertTrue(ClientChapterReplica.of("first_steps").quests().isEmpty());
        assertNull(ClientChapterReplica.quest("first_steps", "one"));
    }

    @Test
    @DisplayName("nothing is asked for a chapter that is not named")
    void noChapterNoClaim() {
        assertFalse(ClientChapterReplica.claim(null, 1L, 0L));
        assertFalse(ClientChapterReplica.claim("  ", 1L, 0L));
    }

    @Test
    @DisplayName("the chapter's own file travels with the quests, for the chapter panel")
    void itHoldsTheChapterTree() {
        ClientChapterReplica.accept("first_steps", QUESTS, CHAPTER, 7L);

        assertEquals("First Steps", ClientChapterReplica.chapterTree("first_steps").get("title").getAsString());
        assertEquals("FLEXIBLE",
                ClientChapterReplica.chapterTree("first_steps").get("progressionMode").getAsString());
        assertTrue(ClientChapterReplica.chapterTree("no_such_chapter").isEmpty(),
                "a chapter with no copy has an empty tree, not a null");
    }
}
