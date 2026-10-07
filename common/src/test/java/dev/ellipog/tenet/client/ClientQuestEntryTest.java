package dev.ellipog.tenet.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ellipog.tenet.net.QuestSync;
import dev.ellipog.tenet.quest.Fixtures;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestIndex;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
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
              {"type": "tenet:item", "item": "minecraft:oak_log", "count": 8}]}
            """;

    private static final String TABLE = """
            {"id": "table", "title": "Craft a table", "dependsOn": ["stone"], "tasks": [
              {"type": "tenet:item", "item": "minecraft:oak_planks", "count": 4}]}
            """;

    private static final String OTHER = """
            {"id": "other", "title": "Something else", "tasks": [
              {"type": "tenet:checkmark", "title": "Say hello"}]}
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
    @DisplayName("the loader no longer sends a duplicate at all")
    void theDuplicateNeverReachesTheWire() {
        // **This half changed.** `QuestIndex` used to add an entry to its list *before* claiming the id, so
        // a duplicate was reported as an error and sent anyway -- two rows for one id, one of which could
        // never be reached: it drew, it could be clicked, and the click opened the other quest. It is now
        // reported and dropped, so there is nothing on this side to disambiguate.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(STONE, STONE));

        assertEquals(1, index.questCount(), "reported and dropped, so the tree carries one");
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
        assertEquals(1, ClientQuestCache.entries().size(), "and one reaches the client");
        assertNotNull(ClientQuestCache.entry("stone"));
    }

    @Test
    @DisplayName("and a tree that does carry two still resolves to the first in declaration order")
    void theFirstOfADuplicateWins() {
        // Kept as a rule of the client's own rather than deleted along with the loader change, because the
        // wire is not a guarantee: a client on an older server is sent both, and this is the answer that
        // keeps a click opening the quest the server's own map resolves. The tree is therefore built by
        // hand -- no index this build can produce carries two quests under one id any more.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(STONE));
        JsonObject tree = JsonParser.parseString(
                new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray quests = tree.getAsJsonArray("quests");
        quests.add(quests.get(0).deepCopy());
        ClientQuestCache.acceptTree(quests.size(), index.chapterCount(),
                tree.toString().getBytes(StandardCharsets.UTF_8));

        List<ClientQuestCache.Entry> held = ClientQuestCache.entries();
        assertEquals(2, held.size(), "an older server's tree, which this build no longer produces");
        assertSame(held.get(0), ClientQuestCache.entry("stone"),
                "first wins: a plain put would have kept the second, and the two disagree about which "
                        + "quest a click opens");
    }
}
