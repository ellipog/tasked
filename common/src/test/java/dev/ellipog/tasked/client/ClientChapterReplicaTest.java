package dev.ellipog.tasked.client;

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
 * The client's copy of a chapter: what it holds, and the one rule that keeps it from flooding a server.
 *
 * <h2>The two faults this holds shut</h2>
 *
 * <p>A request per frame — which works, and is why nobody would notice until a server log filled up — and a
 * copy that is stale without anybody knowing. {@code claim} answers both: it says yes once per revision and
 * remembers that it did, and a copy is current exactly when its revision is the tree's.
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
    @DisplayName("it is asked for once per revision, and again when the tree moves")
    void claimIsOncePerRevision() {
        assertTrue(ClientChapterReplica.claim("first_steps", 7L), "nothing loaded: ask");
        assertFalse(ClientChapterReplica.claim("first_steps", 7L), "asked and waiting: do not ask again");
        assertFalse(ClientChapterReplica.claim("first_steps", 7L));

        ClientChapterReplica.accept("first_steps", QUESTS, CHAPTER, 7L);
        assertFalse(ClientChapterReplica.claim("first_steps", 7L), "current: nothing to ask for");

        assertTrue(ClientChapterReplica.claim("first_steps", 8L), "the tree moved: ask again");
        assertFalse(ClientChapterReplica.claim("first_steps", 8L));
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
        assertFalse(ClientChapterReplica.claim(null, 1L));
        assertFalse(ClientChapterReplica.claim("  ", 1L));
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
