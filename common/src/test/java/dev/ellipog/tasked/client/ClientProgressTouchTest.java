package dev.ellipog.tasked.client;

import dev.ellipog.tasked.quest.MinecraftTestBootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the cache remembers about the last progress message.
 *
 * <h2>Why the cache publishes this at all</h2>
 *
 * <p>Because the message is the only thing that knows what changed, and it is discarded the moment it
 * has been applied: a delta says "these quests are now thus", the reader walks exactly those keys, and
 * then they are gone. A reader that wants to know what moved has no way to ask afterwards — the
 * alternative is comparing the whole cache against a remembered copy, which is the expensive thing the
 * answer exists to avoid.
 *
 * <p>The <b>full</b> flag is the half worth being careful about, and it is why these cases are mostly
 * about it: a delta's ids are the quests that moved, while a full message says nothing whatever about
 * what moved — it is the whole of the server's answer. A reader holding a baseline that treated a full
 * as a delta would keep every unchanged quest's stale state and miss the next change to it, which is a
 * notification that simply never happens. Nothing here needs a client: this is the cache's own answer.
 */
@DisplayName("what the last progress message named")
class ClientProgressTouchTest {

    private static final UUID TEAM = UUID.randomUUID();

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.boot();
    }

    @BeforeEach
    @AfterEach
    void clearCache() {
        ClientQuestCache.clear();
    }

    /** A full sync, as a join sends. */
    private static void full(String quests) {
        ClientQuestCache.acceptProgress(TEAM, 100L,
                ("{\"quests\":" + quests + "}").getBytes(StandardCharsets.UTF_8), 50L);
    }

    /** A delta, as a change sends: relative to the full this client already holds. */
    private static void delta(String body) {
        ClientQuestCache.acceptProgress(TEAM, 200L, body.getBytes(StandardCharsets.UTF_8), 60L, false);
    }

    @Test
    @DisplayName("a full sync names every quest it carried, and says it was the whole of it")
    void aFullSyncNamesEverything() {
        full("{\"a\":{\"state\":\"STARTED\",\"tasks\":[0]},\"b\":{\"state\":\"UNLOCKED\",\"tasks\":[0]}}");

        ClientQuestCache.ProgressTouch touch = ClientQuestCache.lastProgressTouch();
        assertTrue(touch.full(), "a full sync is the whole of the server's answer, not a list of changes");
        assertEquals(Set.of("a", "b"), touch.ids());
    }

    @Test
    @DisplayName("a delta names what it carried and nothing else")
    void aDeltaNamesOnlyWhatItCarried() {
        full("{\"a\":{\"state\":\"STARTED\",\"tasks\":[0]},\"b\":{\"state\":\"UNLOCKED\",\"tasks\":[0]}}");
        delta("{\"quests\":{\"b\":{\"state\":\"STARTED\",\"tasks\":[1]}}}");

        ClientQuestCache.ProgressTouch touch = ClientQuestCache.lastProgressTouch();
        assertFalse(touch.full(), "a delta is not the whole of anything");
        assertEquals(Set.of("b"), touch.ids(),
                "'a' was not named, which is what lets a reader leave its baseline alone");
    }

    @Test
    @DisplayName("a removal is named, because absence cannot say it")
    void aRemovalIsNamed() {
        full("{\"a\":{\"state\":\"STARTED\",\"tasks\":[0]},\"b\":{\"state\":\"UNLOCKED\",\"tasks\":[0]}}");
        delta("{\"removed\":[\"a\"],\"quests\":{\"b\":{\"state\":\"UNLOCKED\",\"tasks\":[0]}}}");

        assertEquals(Set.of("a", "b"), ClientQuestCache.lastProgressTouch().ids(),
                "gone is a change like any other, and the only way to say it is to name it");
    }

    @Test
    @DisplayName("a cleared cache names nothing, as the whole of it")
    void aClearedCacheNamesNothing() {
        full("{\"a\":{\"state\":\"STARTED\",\"tasks\":[0]}}");
        ClientQuestCache.clear();

        ClientQuestCache.ProgressTouch touch = ClientQuestCache.lastProgressTouch();
        assertTrue(touch.full(),
                "a reader holding a baseline has to treat this as a whole answer rather than as a "
                        + "message that named no quests");
        assertTrue(touch.ids().isEmpty());
    }

    @Test
    @DisplayName("a message this client cannot read names nothing, and does not leave the old names standing")
    void anUnreadableMessageNamesNothing() {
        full("{\"a\":{\"state\":\"STARTED\",\"tasks\":[0]}}");

        ClientQuestCache.acceptProgress(TEAM, 300L, "not json at all".getBytes(StandardCharsets.UTF_8), 70L);

        ClientQuestCache.ProgressTouch touch = ClientQuestCache.lastProgressTouch();
        assertTrue(touch.full(), "the cache was emptied by that message, so the answer is a whole one");
        assertTrue(touch.ids().isEmpty(),
                "and leaving the previous message's ids standing would have a reader compare a cache "
                        + "that no longer holds them");
    }

    @Test
    @DisplayName("a delta with no full sync behind it is refused, and changes nothing")
    void aRefusedDeltaChangesNothing() {
        full("{\"a\":{\"state\":\"STARTED\",\"tasks\":[0]}}");
        ClientQuestCache.ProgressTouch before = ClientQuestCache.lastProgressTouch();

        // A team this client holds no full sync for: there is nothing for the delta to be relative to.
        ClientQuestCache.acceptProgress(UUID.randomUUID(), 200L,
                "{\"quests\":{\"b\":{\"state\":\"STARTED\",\"tasks\":[1]}}}".getBytes(StandardCharsets.UTF_8),
                60L, false);

        assertEquals(before, ClientQuestCache.lastProgressTouch(),
                "a refusal applies nothing, so it names nothing");
        assertEquals(0, ClientQuestCache.taskProgressOf("b", 0), "and the cache is untouched");
    }
}
