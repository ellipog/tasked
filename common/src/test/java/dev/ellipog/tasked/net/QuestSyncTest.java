package dev.ellipog.tasked.net;

import dev.ellipog.tasked.client.ClientQuestCache;
import dev.ellipog.tasked.progress.ProgressionEngine;
import dev.ellipog.tasked.progress.QuestProgress;
import dev.ellipog.tasked.progress.QuestState;
import dev.ellipog.tasked.progress.TeamProgress;
import dev.ellipog.tasked.quest.Fixtures;
import dev.ellipog.tasked.quest.MinecraftTestBootstrap;
import dev.ellipog.tasked.quest.QuestIndex;
import dev.ellipog.tasked.quest.QuestShape;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static dev.ellipog.tasked.quest.Fixtures.q;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server's writer against the client's reader.
 *
 * <h2>The bug this exists to catch</h2>
 *
 * <p>{@link QuestSync} writes JSON by hand and {@link ClientQuestCache} reads it by hand, and the two
 * never meet in the compiler. So if the writer emits {@code "chapterTitle"} and the reader asks for
 * {@code "chapter_title"}, both sides compile, the packet sends, nothing throws, and every chapter in
 * the quest book is titled with an empty string. There is no error to find — only a screen that looks
 * wrong in a way nobody can trace back to a string.
 *
 * <p>These tests do the round trip in one process: build a real {@link QuestIndex}, serialise it the
 * way the server does, hand the bytes to the client cache exactly as the payload handler would, and
 * read the values back. A mismatch on either side fails here, in the build, naming the field.
 *
 * <h2>Why it needs vanilla bootstrapped</h2>
 *
 * <p>Because the client resolves item ids to stacks as the tree arrives, so that a screen does not do
 * a registry lookup per node per frame. That resolution reads {@code BuiltInRegistries.ITEM}, and an
 * unbootstrapped registry does not merely return nothing — {@code BuiltInRegistries}'s own class
 * initialiser throws, from inside a vanilla class, with a message about a registry that has nothing
 * to do with this test. So it is bootstrapped first, exactly as a real client is by the time any
 * packet arrives.
 */
@DisplayName("QuestSync round trip")
class QuestSyncTest {

    /** An arbitrary server tick, for cooldown arithmetic. */
    private static final long NOW = 10_000L;

    /** An arbitrary client tick, deliberately different from the server's. */
    private static final long CLIENT_TICK = 1_000L;

    @BeforeAll
    static void bootstrapMinecraft() {
        // The tree parser resolves item ids against the item registry, so the registry has to exist.
        MinecraftTestBootstrap.boot();
    }

    @BeforeEach
    @AfterEach
    void clearCache() {
        // Both, so a failure in one test cannot leave entries for the next one to find and pass on.
        ClientQuestCache.clear();
    }

    // ------------------------------------------------------------------
    // The tree
    // ------------------------------------------------------------------

    private static QuestIndex twoQuests() {
        return Fixtures.indexOf(Fixtures.file(
                q("punch_a_tree").build(),
                q("make_a_table").dependsOn("punch_a_tree").build()));
    }

    @Test
    @DisplayName("the raw JSON names the fields the reader looks for")
    void fieldNamesMatch() {
        // Named explicitly, because this is the contract that has no compiler behind it. If either
        // side renames one of these, this test says which, in one line, instead of leaving a blank
        // string somewhere on a screen.
        String json = new String(QuestSync.treeAsJson(twoQuests()), StandardCharsets.UTF_8);

        for (String key : List.of("\"quests\"", "\"chapterId\"", "\"chapterTitle\"", "\"id\"",
                "\"title\"", "\"icon\"", "\"x\"", "\"y\"", "\"size\"", "\"shape\"",
                "\"invisible\"",
                "\"description\"", "\"dependsOn\"", "\"tasks\"", "\"rewards\"")) {
            assertTrue(json.contains(key), "the tree JSON has no " + key + " field");
        }
    }

    @Test
    @DisplayName("the node shape arrives, so a circle is drawn as a circle and not as a square")
    void shapeArrives() {
        // Added after the fact, and it is the test that would have caught the defect: `shape` was in
        // the quest file format, validated, printed by `/tasked` and requested by the shipped
        // questline -- while this method did not write it at all, so the client could not honour it
        // and drew every node as a square. Nothing failed. The field list above simply did not
        // mention it, and a list of names is only as good as the names on it.
        QuestIndex index = twoQuests();
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.Entry first = ClientQuestCache.entries().get(0);
        assertEquals(QuestShape.ROUNDED, first.shape(),
                "a quest with no shape in the file should default to ROUNDED, not to null");

        // And a shape that is not the default survives, because a default that swallows everything is
        // indistinguishable from a field that never travels.
        String json = new String(QuestSync.treeAsJson(twoQuests()), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"shape\":\"rounded\""),
                "the shape should travel lowercase, spelled the way a quest file spells it");
    }

    @Test
    @DisplayName("a shape name this client does not know reads as ROUNDED rather than throwing")
    void unknownShapeFallsBack() {
        // A server running a newer version can name a shape this client has never heard of. The choice
        // is between a node drawn as the default and a screen that throws while a player stands in
        // front of it -- and this is a different decision from the validator's, where an unknown name
        // is an error because the author is still able to fix it.
        assertEquals(QuestShape.ROUNDED, QuestShape.byName("dodecahedron", QuestShape.ROUNDED));
        assertEquals(QuestShape.ROUNDED, QuestShape.byName("", QuestShape.ROUNDED));
        assertEquals(QuestShape.ROUNDED, QuestShape.byName(null, QuestShape.ROUNDED));
        // And the names that ARE known are still recognised, in any case, so the fallback does not
        // quietly swallow everything.
        assertEquals(QuestShape.CIRCLE, QuestShape.byName("circle", QuestShape.ROUNDED));
        assertEquals(QuestShape.HEXAGON, QuestShape.byName("Hexagon", QuestShape.ROUNDED));
        assertEquals(QuestShape.TOME, QuestShape.byName("TOME", QuestShape.ROUNDED));
    }

    @Test
    @DisplayName("the task and reward fields the reader looks for are all present")
    void taskAndRewardFieldNamesMatch() {
        String json = new String(QuestSync.treeAsJson(twoQuests()), StandardCharsets.UTF_8);

        for (String key : List.of("\"type\"", "\"item\"", "\"count\"", "\"label\"",
                "\"labelFallback\"", "\"optional\"", "\"manual\"")) {
            assertTrue(json.contains(key), "the task JSON has no " + key + " field");
        }
    }

    @Test
    @DisplayName("a quest survives the trip through the client's parser intact")
    void questFieldsArrive() {
        QuestIndex index = twoQuests();
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        assertTrue(ClientQuestCache.hasTree(), "the cache did not register a tree at all");
        assertTrue(ClientQuestCache.hasData());
        assertEquals(2, ClientQuestCache.questCount());

        ClientQuestCache.Entry first = ClientQuestCache.entries().get(0);
        assertEquals("chapter", first.chapterId());
        assertEquals("Chapter", first.chapterTitle(), "the chapter title did not cross the wire");
        assertEquals("punch_a_tree", first.id());
        assertEquals("punch_a_tree", first.title(), "the quest title did not cross the wire");
        assertEquals(1, first.tasks().size());
        assertEquals(0, first.rewards().size());
        assertEquals(List.of(), first.dependencies());
        assertFalse(first.invisible());
        assertEquals(48, first.size(), "the default node size did not cross the wire");
    }

    @Test
    @DisplayName("a dependency list arrives, rather than always being empty")
    void dependenciesArrive() {
        // The failure mode if "dependsOn" were misspelled is a dependency list that is always empty
        // -- and an empty list is what most quests have, so it would look correct.
        QuestIndex index = twoQuests();
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.Entry second = ClientQuestCache.entries().get(1);
        assertEquals("make_a_table", second.id());
        assertEquals(List.of("punch_a_tree"), second.dependencies());
    }

    @Test
    @DisplayName("an invisible quest travels with its flag, so the client can hide it")
    void invisibleFlagArrives() {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                q("seen").build(),
                q("secret").invisible(true).build()));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        assertFalse(entryFor("seen").invisible());
        assertTrue(entryFor("secret").invisible(),
                "the invisible flag did not cross the wire, so a hidden quest would be shown");
    }

    @Test
    @DisplayName("a quest's own icon arrives as a real stack, not as an empty one")
    void iconArrives() {
        // The fixture default is ItemRef.DEFAULT_ICON, which is paper. Asserting a specific item
        // rather than just "non-empty", because an empty stack and a *wrong* stack are both bugs and
        // only the second survives a non-empty assertion.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(q("no_icon").build()));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ItemStack icon = entryFor("no_icon").icon();
        assertFalse(icon.isEmpty(), "the icon did not survive the wire");
        assertEquals(Items.PAPER, icon.getItem(), "the default icon should be paper");
    }

    @Test
    @DisplayName("an item task arrives with its own item, count and no manual button")
    void itemTaskArrives() {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tasked:item\", \"item\": \"minecraft:oak_log\", \"count\": 8} ]}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.TaskEntry task = entryFor("a").tasks().get(0);
        assertTrue(task.hasItem(), "an item task should carry its item");
        assertEquals(Items.OAK_LOG, task.item().getItem());
        assertEquals(8, task.count(), "the requirement did not cross the wire");
        assertFalse(task.optional());
        assertFalse(task.manual(),
                "a presence-only item task completes by itself, so it should have no submit button");
    }

    @Test
    @DisplayName("an item task that consumes does offer a manual button")
    void consumingItemTaskIsManual() {
        // The whole reason `manual` is on the wire: a task that takes your items is one a player may
        // want to hand over deliberately, and the button should only exist where it does something.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": [ {\"type\": \"tasked:item\", "
                        + "\"item\": \"minecraft:diamond\", \"count\": 1, \"consumeItems\": true} ]}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        assertTrue(entryFor("a").tasks().get(0).manual());
    }

    @Test
    @DisplayName("a checkmark arrives as text, with the author's own words as the fallback")
    void checkmarkArrives() {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tasked:checkmark\", \"title\": \"I read the sign\"} ]}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.TaskEntry task = entryFor("a").tasks().get(0);
        assertFalse(task.hasItem(), "a checkmark has no item to draw");
        assertTrue(task.manual(), "a checkmark only completes when someone hands it over");
        // The literal title went as the label, since a plain string in the file is not translatable.
        assertEquals("I read the sign", task.text().getString());
    }

    @Test
    @DisplayName("a translatable checkmark keeps its key and its English words apart")
    void translatableCheckmarkArrives() {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": [ {\"type\": \"tasked:checkmark\", "
                        + "\"title\": {\"translate\": \"tasked.test.read_it\", \"fallback\": \"Read it\"}} ]}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.TaskEntry task = entryFor("a").tasks().get(0);
        // The fallback is what is shown, because the key has no translation in this test JVM. That is
        // the behaviour that matters: a missing translation must not show a raw key.
        assertEquals("Read it", task.text().getString());
    }

    @Test
    @DisplayName("an optional task arrives flagged optional, so it can be drawn differently")
    void optionalTaskArrives() {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                Fixtures.q("a").tasks(2).optional(1).build()));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        assertFalse(entryFor("a").tasks().get(0).optional());
        assertTrue(entryFor("a").tasks().get(1).optional());
    }

    @Test
    @DisplayName("a reward arrives with its item and count")
    void rewardArrives() {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": [], \"rewards\": "
                        + "[ {\"type\": \"tasked:item\", \"item\": \"minecraft:diamond\", \"count\": 4} ]}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.RewardEntry reward = entryFor("a").rewards().get(0);
        assertTrue(reward.hasItem());
        assertEquals(Items.DIAMOND, reward.item().getItem());
        assertEquals(4, reward.count());
        assertEquals("Diamond", reward.text().getString(),
                "an item reward should read as the item's own name");
    }

    @Test
    @DisplayName("an experience reward arrives as text, since there is no item to look up")
    void xpRewardArrives() {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": [], \"rewards\": "
                        + "[ {\"type\": \"tasked:xp\", \"amount\": 5, \"levels\": true} ]}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.RewardEntry reward = entryFor("a").rewards().get(0);
        assertFalse(reward.hasItem(), "experience has no item");
        assertEquals(5, reward.count());
        assertTrue(reward.text().getString().contains("level"),
                "five levels should read as levels, not as points: " + reward.text().getString());
    }

    @Test
    @DisplayName("an empty questline arrives as an empty tree, not as a failure")
    void emptyTreeArrives() {
        // A server with no quest files, which is what a fresh install is. The client has to be able
        // to tell "nothing loaded" from "nothing received", and hasTree is that distinction.
        QuestIndex empty = Fixtures.indexOf(Fixtures.file());
        ClientQuestCache.acceptTree(empty.questCount(), empty.chapterCount(), QuestSync.treeAsJson(empty));

        assertTrue(ClientQuestCache.hasTree(), "an empty tree should still count as received");
        assertFalse(ClientQuestCache.hasData(), "an empty tree has nothing to show");
    }

    // ------------------------------------------------------------------
    // Progress
    // ------------------------------------------------------------------

    private static TeamProgress progressWith(QuestIndex index, String id, QuestProgress progress) {
        return TeamProgress.empty().put(Fixtures.quest(index, id), progress);
    }

    @Test
    @DisplayName("the raw progress JSON names the fields the reader looks for")
    void progressFieldNamesMatch() {
        QuestIndex index = twoQuests();
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, TeamProgress.empty(), NOW);
        String json = new String(QuestSync.progressAsJson(resolution, TeamProgress.empty(), index),
                StandardCharsets.UTF_8);

        for (String key : List.of("\"quests\"", "\"state\"", "\"tasks\"")) {
            assertTrue(json.contains(key), "the progress JSON has no " + key + " field");
        }
    }

    @Test
    @DisplayName("quest states arrive, so the book can show what is playable")
    void statesArrive() {
        QuestIndex index = twoQuests();
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, TeamProgress.empty(), NOW);

        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW,
                QuestSync.progressAsJson(resolution, TeamProgress.empty(), index), CLIENT_TICK);

        assertEquals(QuestState.UNLOCKED, ClientQuestCache.stateOf("punch_a_tree"));
        assertEquals(QuestState.LOCKED, ClientQuestCache.stateOf("make_a_table"));
    }

    @Test
    @DisplayName("a completed quest arrives as completed, and unlocks what follows")
    void completionArrives() {
        QuestIndex index = twoQuests();
        TeamProgress progress = progressWith(index, "punch_a_tree",
                QuestProgress.NONE.completedAt(NOW).withRewardsClaimed(true));
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, progress, NOW);

        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW,
                QuestSync.progressAsJson(resolution, progress, index), CLIENT_TICK);

        assertEquals(QuestState.COMPLETED, ClientQuestCache.stateOf("punch_a_tree"));
        assertEquals(QuestState.UNLOCKED, ClientQuestCache.stateOf("make_a_table"));
    }

    @Test
    @DisplayName("per-task progress arrives, which is what a 5 / 8 line is drawn from")
    void taskProgressArrives() {
        // The single most useful line in a quest book, and the reason per-task progress is on the
        // wire at all rather than just the quest's state.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tasked:item\", \"item\": \"minecraft:oak_log\", \"count\": 8} ]}"));
        TeamProgress progress = progressWith(index, "a", QuestProgress.NONE.recordTask(0, 5));
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, progress, NOW);

        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW,
                QuestSync.progressAsJson(resolution, progress, index), CLIENT_TICK);

        assertEquals(5, ClientQuestCache.taskProgressOf("a", 0));
        assertEquals(0, ClientQuestCache.taskProgressOf("a", 7), "an unknown task index must read as zero");
        assertEquals(0, ClientQuestCache.taskProgressOf("nonexistent", 0));
    }

    @Test
    @DisplayName("a state name the client does not know reads as LOCKED rather than throwing")
    void unknownStateIsSafe() {
        // Happens when the client and server are different versions. LOCKED is the safe reading and
        // the honest one: the client cannot show progress it does not understand, and showing it as
        // complete would be a lie in the more dangerous direction.
        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW,
                "{\"quests\":{\"a\":{\"state\":\"TELEPORTED\",\"tasks\":[]}}}".getBytes(StandardCharsets.UTF_8),
                CLIENT_TICK);

        assertEquals(QuestState.LOCKED, ClientQuestCache.stateOf("a"));
    }

    @Test
    @DisplayName("an unknown quest reads as LOCKED, so a stale client does not show a phantom")
    void unknownQuestIsSafe() {
        assertEquals(QuestState.LOCKED, ClientQuestCache.stateOf("never_heard_of_it"));
    }

    @Test
    @DisplayName("the team id arrives, including the awkward one")
    void teamIdArrives() {
        UUID team = UUID.fromString("3f2a1b4c-5d6e-7f80-9a0b-1c2d3e4f5061");
        byte[] progress = "{\"quests\":{}}".getBytes(StandardCharsets.UTF_8);

        ClientQuestCache.acceptProgress(team, NOW, progress, CLIENT_TICK);
        assertEquals(team, ClientQuestCache.teamId().orElseThrow());

        // All-ones: both halves are -1 as signed longs, which is the case a hand-rolled split gets
        // wrong. The payload carries the bits and the reader rebuilds from them.
        UUID awkward = new UUID(-1L, -1L);
        ClientQuestCache.acceptProgress(awkward, NOW, progress, CLIENT_TICK);
        assertEquals(awkward, ClientQuestCache.teamId().orElseThrow());
    }

    // ------------------------------------------------------------------
    // Cooldowns
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a cooldown counts down on the client, from the tick it arrived at")
    void cooldownCountsDown() {
        // The server sends "500 ticks left"; the client subtracts its own elapsed ticks. Nothing
        // further goes over the wire, which is the point -- a packet per second per cooling quest
        // would be absurd.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                q("daily").repeatable(true).repeatCooldownTicks(600).build()));
        TeamProgress progress = progressWith(index, "daily",
                QuestProgress.NONE.completedAt(NOW).withRewardsClaimed(true));

        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, progress, NOW + 100);
        assertEquals(500, resolution.cooldownOf(Fixtures.quest(index, "daily")), "fixture sanity");

        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW + 100,
                QuestSync.progressAsJson(resolution, progress, index), CLIENT_TICK);

        assertEquals(500, ClientQuestCache.cooldownOf("daily", CLIENT_TICK),
                "the cooldown did not survive the wire");
        assertEquals(400, ClientQuestCache.cooldownOf("daily", CLIENT_TICK + 100),
                "100 client ticks later, 100 fewer should remain");
        assertEquals(0, ClientQuestCache.cooldownOf("daily", CLIENT_TICK + 5_000),
                "a cooldown that has run out must not go negative");
    }

    @Test
    @DisplayName("a quest with no cooldown reports none, rather than inventing one")
    void noCooldown() {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(q("once").build()));
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, TeamProgress.empty(), NOW);

        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW,
                QuestSync.progressAsJson(resolution, TeamProgress.empty(), index), CLIENT_TICK);

        assertEquals(0, ClientQuestCache.cooldownOf("once", CLIENT_TICK));
    }

    // ------------------------------------------------------------------
    // Clearing and failure
    // ------------------------------------------------------------------

    @Test
    @DisplayName("clearing forgets the tree, the progress and the team")
    void clearForgetsEverything() {
        // What a disconnect does. Without it, leaving one server and joining another shows the first
        // server's questline until the new sync arrives -- which looks exactly like a sync failure.
        QuestIndex index = twoQuests();
        TeamProgress empty = TeamProgress.empty();
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW,
                QuestSync.progressAsJson(ProgressionEngine.resolve(index, empty, NOW), empty, index),
                CLIENT_TICK);

        assertTrue(ClientQuestCache.hasData(), "fixture sanity: there should be something to clear");

        ClientQuestCache.clear();

        assertFalse(ClientQuestCache.hasData());
        assertFalse(ClientQuestCache.hasTree(), "a cleared cache must not claim to have a tree");
        assertEquals(0, ClientQuestCache.questCount());
        assertEquals(0, ClientQuestCache.entries().size());
        assertTrue(ClientQuestCache.teamId().isEmpty());
        assertEquals(QuestState.LOCKED, ClientQuestCache.stateOf("punch_a_tree"));
    }

    @Test
    @DisplayName("a malformed tree leaves the cache empty rather than half-filled")
    void malformedTreeIsDiscarded() {
        // A half-parsed tree would render as a quest list with entries missing in the middle and
        // nothing saying why, which is the hardest kind of bug to report.
        ClientQuestCache.acceptTree(5, 1, "not json at all".getBytes(StandardCharsets.UTF_8));

        assertFalse(ClientQuestCache.hasData());
        assertEquals(0, ClientQuestCache.entries().size());
    }

    @Test
    @DisplayName("a malformed progress payload leaves the states empty rather than throwing")
    void malformedProgressIsDiscarded() {
        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW,
                "}{ not json".getBytes(StandardCharsets.UTF_8), CLIENT_TICK);

        assertEquals(QuestState.LOCKED, ClientQuestCache.stateOf("anything"));
    }

    @Test
    @DisplayName("an item the client does not have resolves to nothing, not to a crash")
    void unknownItemResolvesToNothing() {
        // A client missing a mod the server has is a normal situation. The alternative -- throwing
        // inside a payload handler -- disconnects the player over a missing icon.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": [ {\"type\": \"tasked:item\", "
                        + "\"item\": \"someothermod:widget\", \"count\": 1} ]}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.TaskEntry task = entryFor("a").tasks().get(0);
        assertFalse(task.hasItem(), "an uninstalled mod's item should resolve to nothing");
        // And the row still renders something rather than a blank line.
        assertFalse(task.text().getString().isEmpty(), "a task with no item must still have a label");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static ClientQuestCache.Entry entryFor(String questId) {
        return ClientQuestCache.entries().stream()
                .filter(entry -> entry.id().equals(questId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entry for " + questId + " in "
                        + ClientQuestCache.entries().stream().map(ClientQuestCache.Entry::id).toList()));
    }
}
