package dev.ellipog.tasked.client.viewer;

import dev.ellipog.armature.integration.QuestPage;
import dev.ellipog.armature.integration.QuestRef;
import dev.ellipog.armature.integration.QuestRow;
import dev.ellipog.tasked.client.ClientQuestCache;
import dev.ellipog.tasked.quest.Fixtures;
import dev.ellipog.tasked.quest.MinecraftTestBootstrap;
import dev.ellipog.tasked.quest.QuestIndex;
import dev.ellipog.tasked.net.QuestSync;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The content Tasked hands the viewer seam: what the snapshot holds, what the live reads say, and
 * the one expansion a test would otherwise not reach.
 *
 * <h2>Why the tag resolver is a parameter</h2>
 *
 * <p>{@code #minecraft:logs} becoming the items a viewer can find is the whole reason a tag task is
 * visible in an item lookup, and a test JVM loads no datapack — {@code BuiltInRegistries.ITEM.getTag}
 * answers empty for every tag there. So {@link QuestViewerContent#rebuild} takes the resolver, and
 * these tests hand it a fake while production hands it the registry. The alternative — a mutable
 * static swapped in tests — is the seam that leaks into the shipped class.
 *
 * <h2>The tree is built the way the server builds it</h2>
 *
 * <p>Through {@code Fixtures} and {@code QuestSync.treeAsJson}, exactly as {@code QuestSyncTest}
 * does, so this is a test of the content seam and not a second, parallel description of the wire.
 */
@DisplayName("Tasked's viewer content")
class QuestViewerContentTest {

    private static final String ONE_QUEST = """
            {"id": "tree", "title": "Punch a tree", "tasks": [
              {"type": "tasked:item", "item": "minecraft:oak_log", "count": 8},
              {"type": "tasked:item_tag", "tag": "minecraft:logs", "count": 4},
              {"type": "tasked:checkmark", "title": "Read the sign"}],
             "rewards": [{"type": "tasked:item", "item": "minecraft:diamond", "count": 1}]}
            """;

    private static final String TWO_QUESTS = """
            {"id": "tree", "title": "Punch a tree", "tasks": [
              {"type": "tasked:item", "item": "minecraft:oak_log", "count": 8}]}
            """;

    private static final String SECOND_QUEST = """
            {"id": "craft", "title": "Craft a table", "dependsOn": ["tree"], "tasks": [
              {"type": "tasked:item", "item": "minecraft:oak_planks", "count": 4}]}
            """;

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.boot();
    }

    @BeforeEach
    @AfterEach
    void clearCache() {
        ClientQuestCache.clear();
        QuestBookFocus.clear();
    }

    private static void accept(String... quests) {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(quests));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
    }

    private static List<String> ids(List<QuestRef> refs) {
        return refs.stream().map(QuestRef::id).toList();
    }

    @Test
    @DisplayName("the snapshot carries a page per quest, with rows and the item index")
    void theSnapshotCarriesPagesAndAnIndex() {
        accept(ONE_QUEST);
        QuestViewerContent content = new QuestViewerContent();
        content.tick();

        assertEquals(1, content.pages().size());
        QuestPage page = content.pages().get(0);
        assertEquals("tree", page.quest().id());
        assertEquals("Punch a tree", page.quest().title());
        assertEquals(3, page.tasks().size(), "every task has a row, item or not");
        assertEquals(1, page.rewards().size());

        QuestRow itemTask = page.tasks().get(0);
        assertEquals("Oak Log", itemTask.label(), "an item task reads as its item's name");
        assertEquals(8, itemTask.need());
        assertFalse(itemTask.done(), "a fresh snapshot has no progress on it");

        assertEquals(List.of("tree"), ids(content.index().questsUsing(ResourceLocation.parse("minecraft:oak_log"))));
        assertEquals(List.of("tree"), ids(content.index().questsAwarding(ResourceLocation.parse("minecraft:diamond"))));
        assertTrue(content.index().questsUsing(ResourceLocation.parse("minecraft:diamond")).isEmpty(),
                "a reward is not a use");
    }

    @Test
    @DisplayName("a tag task keeps its tag and finds its members through the resolver")
    void aTagTaskCarriesItsTagAndExpandsIt() {
        accept(ONE_QUEST);
        QuestViewerContent content = new QuestViewerContent();
        content.rebuild(ClientQuestCache.treeRevision(), tag -> expanded(tag));

        QuestRow tagRow = content.pages().get(0).tasks().get(1);
        assertTrue(tagRow.hasTag(), "the row must say which tag it is about");
        assertEquals("minecraft:logs", tagRow.tagId());

        // The whole point: an item that is a member of the tag finds the quest. A test JVM has no
        // datapack, so the resolver is the fake above; production reads the same shape from the synced
        // client registry.
        assertEquals(List.of("tree"),
                ids(content.index().questsUsing(ResourceLocation.parse("minecraft:birch_log"))));
        assertTrue(content.index().questsUsing(ResourceLocation.parse("minecraft:stone")).isEmpty(),
                "an item outside the tag does not");
    }

    /** The fake datapack: {@code #minecraft:logs} holds two items, everything else is empty. */
    private static List<ResourceLocation> expanded(TagKey<Item> tag) {
        if (!tag.location().toString().equals("minecraft:logs")) {
            return List.of();
        }
        return List.of(ResourceLocation.parse("minecraft:oak_log"),
                ResourceLocation.parse("minecraft:birch_log"));
    }

    @Test
    @DisplayName("live reads follow the progress as it arrives")
    void liveReadsFollowProgress() {
        accept(ONE_QUEST);
        QuestViewerContent content = new QuestViewerContent();
        content.tick();

        assertEquals(0, content.liveTask("tree", 0).have(), "nothing reported yet");
        assertEquals("Locked", content.stateText("tree"), "no progress means locked");

        String progress = "{\"quests\":{\"tree\":{\"state\":\"STARTED\",\"tasks\":[5,0,0],"
                + "\"claimable\":false}}}";
        ClientQuestCache.acceptProgress(UUID.randomUUID(), 100L,
                progress.getBytes(StandardCharsets.UTF_8), 50L);

        assertEquals(5, content.liveTask("tree", 0).have());
        assertFalse(content.liveTask("tree", 0).done(), "5 of 8 is not done");
        assertEquals("In progress", content.stateText("tree"));
        assertEquals(0, content.liveTask("tree", 99).have(), "an out-of-range row is blank, not a crash");
        assertEquals(0, content.liveTask("gone", 0).have());
    }

    @Test
    @DisplayName("the snapshot is rebuilt when the tree revision moves, and not before")
    void rebuiltOnlyOnANewTree() {
        accept(TWO_QUESTS);
        QuestViewerContent content = new QuestViewerContent();
        content.tick();

        List<QuestPage> first = content.pages();
        long firstRevision = content.revision();
        content.tick();
        assertSame(first, content.pages(), "a tick with no new tree is a no-op");

        accept(TWO_QUESTS, SECOND_QUEST);
        content.tick();
        assertEquals(2, content.pages().size(), "a new tree is a new snapshot");
        assertTrue(content.revision() > firstRevision);
    }

    @Test
    @DisplayName("the category values are Tasked's, not the viewer's")
    void categoryValuesAreTheContents() {
        QuestViewerContent content = new QuestViewerContent();

        assertEquals(ResourceLocation.parse("tasked:quests"), content.categoryId());
        assertEquals("Quests", content.categoryTitle().getString());
        assertFalse(content.categoryIcon().isEmpty(), "the category needs an icon to be drawn");
    }
}
