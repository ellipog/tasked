package dev.ellipog.tasked.client;

import dev.ellipog.tasked.net.QuestSync;
import dev.ellipog.tasked.quest.Fixtures;
import dev.ellipog.tasked.quest.MinecraftTestBootstrap;
import dev.ellipog.tasked.quest.QuestIndex;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The id index behind {@code ClientQuestCache.entry}.
 *
 * <h2>Why a scan needed replacing, and what the replacement must not change</h2>
 *
 * <p>The lookup is asked per <b>row</b>: the rewards inbox and the choice overlay ask once per row they
 * draw, and a viewer page asks once per task on it. A scan made those O(quests) each, so the cost of
 * one lookup was the size of the pack — invisible in a screenshot and obvious in a large one.
 *
 * <p>Two properties of the old scan are load-bearing and are what this file is mostly about, because an
 * index can quietly break either: it matched <b>exactly</b> (a lookup that folded case would resolve an
 * id the tree does not hold), and it returned the <b>first</b> match in declaration order (a map built
 * with a plain put keeps the last, so a duplicated id would open a different quest than the scan did).
 */
@DisplayName("The client cache's quest index")
class ClientQuestEntryTest {

    private static final String STONE = """
            {"id": "stone", "title": "Punch a tree", "tasks": [
              {"type": "tasked:item", "item": "minecraft:oak_log", "count": 8}]}
            """;

    private static final String TABLE = """
            {"id": "table", "title": "Craft a table", "dependsOn": ["stone"], "tasks": [
              {"type": "tasked:item", "item": "minecraft:oak_planks", "count": 4}]}
            """;

    private static final String OTHER = """
            {"id": "other", "title": "Something else", "tasks": [
              {"type": "tasked:checkmark", "title": "Say hello"}]}
            """;

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.boot();
    }

    @BeforeEach
    @AfterEach
    void clearCache() {
        ClientQuestCache.clear();
    }

    private static void accept(String... quests) {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(quests));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
    }

    @Test
    @DisplayName("every quest in the tree resolves, and an unknown one is null rather than a crash")
    void everyIdResolves() {
        accept(STONE, TABLE, OTHER);

        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            assertSame(entry, ClientQuestCache.entry(entry.id()),
                    "the index and the list must agree about every quest in the tree");
        }
        assertNull(ClientQuestCache.entry("nothing_like_this"), "an id the tree does not hold");
        assertNull(ClientQuestCache.entry(null), "a null id: the scan answered null and so does this");
        assertNull(ClientQuestCache.entry(""), "and an empty one, which no quest is called");
    }

    @Test
    @DisplayName("the id is matched exactly, so a differently-cased one is not a hit")
    void idsAreMatchedExactly() {
        accept(STONE);

        assertEquals("stone", ClientQuestCache.entry("stone").id());
        assertNull(ClientQuestCache.entry("STONE"),
                "the server's own identifier map is exact, so folding case here would resolve an id the "
                        + "tree does not hold");
        assertNull(ClientQuestCache.entry("Stone"));
    }

    @Test
    @DisplayName("the index follows the tree, and forgets what it no longer holds")
    void theIndexFollowsTheTree() {
        accept(STONE, TABLE);
        assertNotNull(ClientQuestCache.entry("table"));

        accept(STONE);
        assertNotNull(ClientQuestCache.entry("stone"), "the quest still in the tree still resolves");
        assertNull(ClientQuestCache.entry("table"),
                "a quest the new tree does not carry is not left behind by the old index");
    }

    @Test
    @DisplayName("an empty cache resolves nothing, and an emptied one forgets everything")
    void emptyIsEmpty() {
        assertNull(ClientQuestCache.entry("stone"), "nothing has arrived yet");

        accept(STONE);
        ClientQuestCache.clear();
        assertNull(ClientQuestCache.entry("stone"),
                "a cleared cache holds no quest, and the index is derived from the list so it goes with it");
    }

    @Test
    @DisplayName("two quests claiming one id: the first in declaration order wins, as the scan did")
    void theFirstOfADuplicateWins() {
        // Reachable, and not only in theory: `QuestIndex.build` adds a quest to its list *before* it
        // claims the id, so a duplicate is reported as an error and is still sent -- and the client
        // holds both. What matters is that the index answers with the same quest the scan would have.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(STONE, STONE));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        List<ClientQuestCache.Entry> tree = ClientQuestCache.entries();
        assertEquals(2, tree.size(), "the loader reports the duplicate and sends both");
        assertSame(tree.get(0), ClientQuestCache.entry("stone"),
                "first wins: a plain put would have kept the second, and the two disagree about which "
                        + "quest a click opens");
    }
}
