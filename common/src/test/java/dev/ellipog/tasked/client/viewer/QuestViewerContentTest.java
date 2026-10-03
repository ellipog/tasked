package dev.ellipog.tasked.client.viewer;

import dev.ellipog.tasked.client.viewer.PagePalette;
import dev.ellipog.tasked.client.viewer.QuestContent;
import dev.ellipog.tasked.client.viewer.QuestPage;
import dev.ellipog.tasked.client.viewer.QuestRef;
import dev.ellipog.tasked.client.viewer.QuestRow;
import dev.ellipog.tasked.client.ClientQuestCache;
import dev.ellipog.tasked.net.QuestSync;
import dev.ellipog.tasked.quest.Fixtures;
import dev.ellipog.tasked.quest.MinecraftTestBootstrap;
import dev.ellipog.tasked.quest.QuestIndex;

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
 * the rows a page is allowed to show.
 *
 * <h2>What a page shows, and why the tests say so</h2>
 *
 * <p>A viewer page carries item references — item tasks, tag tasks, item rewards. Text tasks are
 * dropped (a checkmark has no item to look up), a quest with no item rows has no page at all, and a
 * row keeps the index of the source list it came from so live reads do not read the wrong row after
 * filtering. Each of those has a test here, because each is a decision rather than an accident.
 *
 * <h2>Why the tag resolver is a parameter</h2>
 *
 * <p>{@code #minecraft:logs} becoming the items a viewer can find is the whole reason a tag task is
 * visible in an item lookup, and a test JVM loads no datapack — {@code BuiltInRegistries.ITEM.getTag}
 * answers empty for every tag there. So {@link QuestViewerContent#rebuild} takes the resolver, and
 * these tests hand it a fake while production hands it the registry.
 */
@DisplayName("Tasked's viewer content")
class QuestViewerContentTest {

    private static final String ONE_QUEST = """
            {"id": "tree", "title": "Punch a tree", "tasks": [
              {"type": "tasked:item", "item": "minecraft:oak_log", "count": 8},
              {"type": "tasked:item_tag", "tag": "minecraft:logs", "count": 4},
              {"type": "tasked:checkmark", "title": "Read the sign"},
              {"type": "tasked:item", "item": "tasked:does_not_exist", "count": 1}],
             "rewards": [{"type": "tasked:item", "item": "minecraft:diamond", "count": 1}]}
            """;

    private static final String TEXT_ONLY = """
            {"id": "talk", "title": "Just talk", "tasks": [
              {"type": "tasked:checkmark", "title": "Say hello"}],
             "rewards": [{"type": "tasked:xp", "amount": 5}]}
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
    @DisplayName("the page carries the item tasks, the tag task and the item reward -- and nothing text-only")
    void thePageCarriesItemRowsOnly() {
        accept(ONE_QUEST);
        QuestViewerContent content = new QuestViewerContent();
        content.tick();

        assertEquals(1, content.pages().size());
        QuestPage page = content.pages().get(0);
        assertEquals("tree", page.quest().id());
        assertEquals(3, page.tasks().size(),
                "the item task, the tag task and the missing item keep their rows; the checkmark does not");
        assertEquals(1, page.rewards().size());

        QuestRow itemTask = page.tasks().get(0);
        assertEquals("Oak Log", itemTask.label(), "an item task reads as its item's name");
        assertEquals(8, itemTask.need());
        assertEquals(0, itemTask.sourceIndex(), "and remembers where it came from");
        assertFalse(itemTask.done(), "a fresh snapshot has no progress on it");

        assertEquals(1, page.tasks().get(1).sourceIndex(),
                "the tag task is the second entry of the source list");
        assertTrue(page.tasks().get(1).hasTag());

        QuestRow missing = page.tasks().get(2);
        assertEquals(3, missing.sourceIndex(), "the missing item is the fourth entry, after the checkmark");
        assertTrue(missing.icon().isEmpty(), "an item this build does not have draws no icon");
        assertEquals("tasked:does_not_exist", missing.label(),
                "so its id is the label -- the book's keep-and-mark rule, on a viewer page");

        assertEquals(List.of("tree"), ids(content.index().questsUsing(ResourceLocation.parse("minecraft:oak_log"))));
        assertEquals(List.of("tree"), ids(content.index().questsAwarding(ResourceLocation.parse("minecraft:diamond"))));
        assertTrue(content.index().questsUsing(ResourceLocation.parse("minecraft:diamond")).isEmpty(),
                "a reward is not a use");
    }

    @Test
    @DisplayName("a quest with only text rows has no page, and no lookup")
    void aTextOnlyQuestHasNoPage() {
        accept(TEXT_ONLY);
        QuestViewerContent content = new QuestViewerContent();
        content.tick();

        assertTrue(content.pages().isEmpty(),
                "nothing could lead a player to it, and a blank page is worse than none");
        assertTrue(content.index().isEmpty());
    }

    @Test
    @DisplayName("a tag task keeps its tag and finds its members through the resolver")
    void aTagTaskCarriesItsTagAndExpandsIt() {
        accept(ONE_QUEST);
        QuestViewerContent content = new QuestViewerContent();
        content.rebuild(ClientQuestCache.treeRevision(), QuestViewerContentTest::expanded);

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
    @DisplayName("live reads follow the progress, by source index")
    void liveReadsFollowProgress() {
        accept(ONE_QUEST);
        QuestViewerContent content = new QuestViewerContent();
        content.tick();

        assertEquals(0, content.liveTask("tree", 0).have(), "nothing reported yet");
        assertEquals("Locked", content.stateText("tree"), "no progress means locked");

        String progress = "{\"quests\":{\"tree\":{\"state\":\"STARTED\",\"tasks\":[5,0,0,0],"
                + "\"claimable\":false}}}";
        ClientQuestCache.acceptProgress(UUID.randomUUID(), 100L,
                progress.getBytes(StandardCharsets.UTF_8), 50L);

        assertEquals(5, content.liveTask("tree", 0).have());
        assertFalse(content.liveTask("tree", 0).done(), "5 of 8 is not done");
        assertEquals(0, content.liveTask("tree", 1).have(),
                "the tag task's own row, read by its source index");
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
    @DisplayName("the category and section words are Tasked's, not the viewer's")
    void categoryValuesAreTheContents() {
        QuestViewerContent content = new QuestViewerContent();

        assertEquals(ResourceLocation.parse("tasked:quests"), content.categoryId());
        assertEquals("Quests", content.categoryTitle().getString());
        assertEquals("Tasks", content.tasksLabel().getString());
        assertEquals("Rewards", content.rewardsLabel().getString());
        assertFalse(content.categoryIcon().isEmpty(), "the category needs an icon to be drawn");
    }

    @Test
    @DisplayName("a reward's live row carries a status, never a count")
    void rewardRowsCarryStatusNotProgress() {
        accept(ONE_QUEST);
        QuestViewerContent content = new QuestViewerContent();
        content.tick();

        ClientQuestCache.acceptProgress(UUID.randomUUID(), 100L,
                "{\"quests\":{\"tree\":{\"state\":\"COMPLETED\",\"tasks\":[8,4,0,1],\"claimable\":true}}}"
                        .getBytes(StandardCharsets.UTF_8), 50L);

        QuestRow reward = content.liveReward("tree", 0);
        assertEquals(0, reward.need(), "a reward is not a requirement, so it draws no bar");
        assertTrue(reward.claimable(), "completed, unlocked and unclaimed is Ready");
        assertFalse(reward.done(), "no player in a test JVM has claimed anything");
        assertFalse(reward.locked());
    }

    @Test
    @DisplayName("a reward's claim state is per player, read from the cache")
    void rewardClaimsArePerPlayer() {
        accept(ONE_QUEST);
        UUID player = UUID.randomUUID();
        ClientQuestCache.acceptProgress(UUID.randomUUID(), 100L,
                ("{\"quests\":{\"tree\":{\"state\":\"COMPLETED\",\"tasks\":[8,4,0,1],"
                        + "\"claims\":{\"" + player + "\":[0]}}}}").getBytes(StandardCharsets.UTF_8), 50L);

        assertTrue(ClientQuestCache.rewardClaimedBy(player, "tree", 0));
        assertFalse(ClientQuestCache.rewardClaimedBy(UUID.randomUUID(), "tree", 0),
                "a teammate's claim is not this player's");
        assertFalse(ClientQuestCache.rewardClaimedBy(player, "tree", 1), "row one is unclaimed");
    }

    @Test
    @DisplayName("the state, its colour and the reward status words all come from the content")
    void stateColourAndStatusWords() {
        accept(ONE_QUEST);
        QuestViewerContent content = new QuestViewerContent();
        content.tick();

        assertEquals(PagePalette.LOCKED, content.stateColour("tree"), "no progress reads locked");

        ClientQuestCache.acceptProgress(UUID.randomUUID(), 1L,
                "{\"quests\":{\"tree\":{\"state\":\"UNLOCKED\",\"tasks\":[0,0,0,0]}}}"
                        .getBytes(StandardCharsets.UTF_8), 1L);
        assertEquals(PagePalette.AVAILABLE, content.stateColour("tree"));

        ClientQuestCache.acceptProgress(UUID.randomUUID(), 1L,
                "{\"quests\":{\"tree\":{\"state\":\"STARTED\",\"tasks\":[1,0,0,0]}}}"
                        .getBytes(StandardCharsets.UTF_8), 1L);
        assertEquals(PagePalette.PROGRESS, content.stateColour("tree"));

        ClientQuestCache.acceptProgress(UUID.randomUUID(), 1L,
                "{\"quests\":{\"tree\":{\"state\":\"COMPLETED\",\"tasks\":[8,4,0,1]}}}"
                        .getBytes(StandardCharsets.UTF_8), 1L);
        assertEquals(PagePalette.COMPLETE, content.stateColour("tree"));

        assertEquals("Ready", content.rewardStatusLabel(QuestContent.RewardStatus.READY).getString());
        assertEquals("Locked", content.rewardStatusLabel(QuestContent.RewardStatus.LOCKED).getString());
        assertEquals("Claimed", content.rewardStatusLabel(QuestContent.RewardStatus.CLAIMED).getString());
    }
}
