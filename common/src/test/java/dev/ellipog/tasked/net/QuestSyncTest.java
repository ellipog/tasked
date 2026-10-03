package dev.ellipog.tasked.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ellipog.tasked.client.ClientQuestCache;
import dev.ellipog.tasked.progress.ProgressService;
import dev.ellipog.tasked.progress.ProgressionEngine;
import dev.ellipog.tasked.progress.QuestProgress;
import dev.ellipog.tasked.progress.QuestState;
import dev.ellipog.tasked.progress.TeamProgress;
import dev.ellipog.tasked.quest.DependencyStyle;
import dev.ellipog.tasked.quest.Fixtures;
import dev.ellipog.tasked.quest.MinecraftTestBootstrap;
import dev.ellipog.tasked.quest.Quest;
import dev.ellipog.tasked.quest.QuestIndex;
import dev.ellipog.tasked.quest.QuestLoader;
import dev.ellipog.tasked.quest.QuestShape;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.ellipog.tasked.quest.Fixtures.q;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

        for (String key : List.of("\"version\"", "\"groups\"", "\"chapterGroupId\"", "\"quests\"",
                "\"chapterId\"", "\"chapterTitle\"", "\"chapterIcon\"", "\"id\"",
                "\"title\"", "\"icon\"", "\"x\"", "\"y\"", "\"size\"", "\"shape\"",
                "\"iconScale\"", "\"showTitle\"", "\"invisible\"", "\"chapterLinear\"", "\"order\"",
                "\"description\"", "\"dependsOn\"", "\"tasks\"", "\"rewards\"")) {
            assertTrue(json.contains(key), "the tree JSON has no " + key + " field");
        }
    }

    @Test
    @DisplayName("the icon scale and the name flag arrive, since the validator accepts both")
    void appearanceFieldsArrive() {
        // The same defect as `shape`, which is why this test exists in the same shape as that one: a
        // field the format accepts, the validator checks and `/tasked` prints -- and the wire never
        // carries. It reads as supported and does nothing, which is worse than a field that is absent,
        // because an author would look for the bug in the drawing code.
        //
        // Both of these are exactly that risk: they are presentation, so nothing on the server behaves
        // differently when they are wrong, and no test that runs on the server can notice.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                q("plain").build(),
                q("named").showTitle(true).iconScale(0.4).build()));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.Entry plain = entryFor("plain");
        ClientQuestCache.Entry named = entryFor("named");

        assertEquals(0.75, plain.iconScale(), 1.0E-9,
                "a quest with no iconScale in its file should arrive at the default");
        assertFalse(plain.showTitle(),
                "a quest with no showTitle in its file should arrive with the name NOT drawn");

        assertEquals(0.4, named.iconScale(), 1.0E-9, "an explicit icon scale should survive the wire");
        assertTrue(named.showTitle(), "and an explicit showTitle should survive it too");

        String json = new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"showTitle\":true"), "showTitle should travel as a boolean");
    }

    @Test
    @DisplayName("an out-of-range icon scale from a server is clamped rather than drawn")
    void anOutOfRangeIconScaleIsClamped() {
        // The codec already bounds this on the *server*, over that server's files. What arrives is a
        // number from possibly a different version, and the screen must not draw outside its node
        // because of one -- the same reasoning as QuestShape.span clamping its own output.
        String handWritten = "{\"version\":1,\"quests\":[{\"chapterId\":\"c\",\"chapterTitle\":\"C\","
                + "\"id\":\"wild\",\"title\":\"Wild\",\"icon\":\"minecraft:stone\",\"x\":0,\"y\":0,"
                + "\"size\":48,\"shape\":\"rounded\",\"iconScale\":99.0,\"showTitle\":false,"
                + "\"description\":[],\"dependsOn\":[],\"tasks\":[],\"rewards\":[]},"
                + "{\"chapterId\":\"c\",\"chapterTitle\":\"C\",\"id\":\"none\",\"title\":\"None\","
                + "\"icon\":\"minecraft:stone\",\"x\":64,\"y\":0,\"size\":48,\"shape\":\"rounded\","
                + "\"iconScale\":-5.0,\"showTitle\":false,\"description\":[],\"dependsOn\":[],"
                + "\"tasks\":[],\"rewards\":[]}]}";
        ClientQuestCache.acceptTree(2, 1, handWritten.getBytes(StandardCharsets.UTF_8));

        assertEquals(QuestShape.MAX_ICON_SCALE, entryFor("wild").iconScale(), 1.0E-9);
        assertEquals(QuestShape.MIN_ICON_SCALE, entryFor("none").iconScale(), 1.0E-9);
    }

    @Test
    @DisplayName("an item's custom data crosses the wire and arrives on the icon's stack")
    void itemComponentsArrive() {
        // The wire sends the patch beside the id, in the file's own spelling, through the same codec
        // `ItemRef` uses. This is the read half; without it, a renamed item's name would be a field
        // the server sends and the client never applies -- the failure mode this whole test class is
        // about.
        String handWritten = "{\"version\":1,\"quests\":[{\"chapterId\":\"c\",\"chapterTitle\":\"C\","
                + "\"id\":\"battered\",\"title\":\"Battered\",\"icon\":\"minecraft:diamond_sword\","
                + "\"iconComponents\":{\"minecraft:damage\":5},"
                + "\"x\":0,\"y\":0,\"size\":48,\"shape\":\"rounded\",\"iconScale\":1.0,"
                + "\"showTitle\":false,\"description\":[],\"dependsOn\":[],\"tasks\":[],\"rewards\":[]}]}";
        ClientQuestCache.acceptTree(1, 1, handWritten.getBytes(StandardCharsets.UTF_8));

        assertEquals(5, entryFor("battered").icon().getDamageValue(),
                "the component arrived and was applied to the stack");
        assertEquals("minecraft:diamond_sword", entryFor("battered").iconId(),
                "and the id is kept beside it, which is what lets a missing item say so");
    }

    @Test
    @DisplayName("a chapter's icon and a group's icon both cross the wire")
    void chapterAndGroupIconsArrive() {
        // Two rows, two sources: a chapter row draws the chapter's own icon, which rides on every quest
        // of it -- the client has no chapter record to hang it on, the same shape `chapterTheme` has --
        // and a heading draws the group's, which is optional in the file. Absent means "fall back to the
        // first chapter", and that fallback is a client decision rather than a value the server invents.
        String file = """
                {
                  "version": 1,
                  "chapterGroups": [
                    {
                      "id": "group",
                      "title": "Group",
                      "icon": { "item": "minecraft:anvil" },
                      "chapters": [
                        {
                          "id": "chapter",
                          "title": "Chapter",
                          "icon": { "item": "minecraft:crafting_table" },
                          "quests": [ %s ]
                        }
                      ]
                    }
                  ]
                }
                """.formatted(q("punch_a_tree").build());
        QuestIndex index = Fixtures.indexOf(file);
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.Entry entry = entryFor("punch_a_tree");
        assertEquals(Items.CRAFTING_TABLE, entry.chapterIcon().getItem(),
                "the chapter's icon arrived on its quest");
        assertEquals("minecraft:crafting_table", entry.chapterIconId(),
                "and the id is kept beside it, for the row that has no stack to draw");

        ClientQuestCache.GroupEntry group = ClientQuestCache.groups().get(0);
        assertEquals(Items.ANVIL, group.icon().getItem(), "the group's own icon arrived");
        assertEquals("minecraft:anvil", group.iconId());
    }

    @Test
    @DisplayName("a linear chapter arrives marked linear, with its quests in order")
    void aLinearChapterTravels() {
        // A linear chapter declares no dependencies at all -- the list order is the progression -- so
        // without these two fields the client draws a row of unconnected nodes for a chapter that is,
        // in fact, a road. The lines have to come from somewhere, and the server is the only thing that
        // knows.
        QuestIndex index = Fixtures.indexOf(Fixtures.fileWithChapter(
                "\"progressionMode\": \"linear\",",
                q("first").build(), q("second").build(), q("third").build()));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            assertTrue(entry.chapterLinear(),
                    entry.id() + " is in a linear chapter, but arrived as flexible");
        }
        assertEquals(0, entryFor("first").orderInChapter());
        assertEquals(1, entryFor("second").orderInChapter());
        assertEquals(2, entryFor("third").orderInChapter());

        // And the default is not linear, because a chapter that declares nothing must not be treated as
        // declaring an order -- FLEXIBLE is what the codec defaults to, and this is the same default
        // arriving at the client.
        QuestIndex flexible = Fixtures.indexOf(Fixtures.file(q("alone").build()));
        ClientQuestCache.acceptTree(flexible.questCount(), flexible.chapterCount(),
                QuestSync.treeAsJson(flexible));
        assertFalse(entryFor("alone").chapterLinear());
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
                "\"labelFallback\"", "\"labelArg\"", "\"optional\"", "\"manual\"")) {
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
    @DisplayName("a row's subject crosses the wire, so the key renders the sentence it was written for")
    void subjectsCrossTheWire() {
        // The reported bug at its own example: a stage task and a stage reward read "1" on the card,
        // because the count was the only argument the client had to give a key written for a subject.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tasked:stage\", \"stage\": \"my_pack:inducted\"} ], \"rewards\": "
                        + "[ {\"type\": \"tasked:stage\", \"stage\": \"my_pack:inducted\"} ]}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.TaskEntry task = entryFor("a").tasks().get(0);
        assertEquals("my_pack:inducted", task.labelArg(), "the task's subject did not cross the wire");
        // With no language loaded this JVM resolves the fallback, which is the same sentence the key
        // holds -- the naming sweep reads the file's own English for every type.
        assertEquals("Have the stage my_pack:inducted", task.text().getString(),
                "the task must read as its sentence, not as the bare count");

        ClientQuestCache.RewardEntry reward = entryFor("a").rewards().get(0);
        assertEquals("my_pack:inducted", reward.labelArg(), "the reward's subject did not cross the wire");
        assertEquals("Grant the stage my_pack:inducted", reward.text().getString(),
                "the reward must read as its sentence, not as the bare count");
    }

    @Test
    @DisplayName("a payload from an older server, with no subject on it, still renders the count-shaped keys")
    void anOlderPayloadStillReads() {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": [], \"rewards\": "
                        + "[ {\"type\": \"tasked:xp\", \"amount\": 5, \"levels\": true} ]}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(),
                withoutSubjects(QuestSync.treeAsJson(index)));

        ClientQuestCache.RewardEntry reward = entryFor("a").rewards().get(0);
        assertEquals("", reward.labelArg(), "an older writer sends no subject");
        // The count is still the argument the sentence is built from -- this is the pairing an older
        // server's keys rely on, and the reason the client falls back to the count rather than to "".
        assertEquals("5 levels", reward.text().getString());
    }

    /** The tree as an older writer would have sent it: every {@code labelArg} taken back out. */
    private static byte[] withoutSubjects(byte[] tree) {
        JsonObject json = JsonParser.parseString(new String(tree, StandardCharsets.UTF_8))
                .getAsJsonObject();
        for (JsonElement quest : json.getAsJsonArray("quests")) {
            for (String member : List.of("tasks", "rewards")) {
                if (!quest.getAsJsonObject().has(member)) {
                    continue;
                }
                for (JsonElement entry : quest.getAsJsonObject().getAsJsonArray(member)) {
                    entry.getAsJsonObject().remove("labelArg");
                }
            }
        }
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a tree with no quests still arrives as a tree, and a chapter with no quests is data")
    void emptyTreeArrives() {
        // A server with no quest files, which is what a fresh install is. The client has to be able
        // to tell "nothing loaded" from "nothing received", and hasTree is that distinction.
        //
        // Since version 3 the chapter list travels on its own, so "no quests" and "nothing to show" are
        // no longer the same sentence: `Fixtures.file()` is one group holding one chapter with no quests,
        // and that chapter is a real row in the sidebar -- which is exactly what makes a newly created
        // chapter visible before anybody writes a quest into it.
        QuestIndex empty = Fixtures.indexOf(Fixtures.file());
        ClientQuestCache.acceptTree(empty.questCount(), empty.chapterCount(), QuestSync.treeAsJson(empty));

        assertTrue(ClientQuestCache.hasTree(), "an empty tree should still count as received");
        assertTrue(ClientQuestCache.entries().isEmpty(), "there are no quests to show");
        assertEquals(1, ClientQuestCache.chapters().size(),
                "but there is a chapter, and a chapter is something to draw");
        assertTrue(ClientQuestCache.hasData(), "a chapter with no quests is still a chapter");

        // And a tree with nothing in it at all is still "nothing to show", which is the state a fresh
        // install with no chapters is in.
        ClientQuestCache.acceptTree(0, 0, """
                {"version":3,"groups":[],"chapters":[],"quests":[]}
                """.getBytes(StandardCharsets.UTF_8));
        assertTrue(ClientQuestCache.hasTree());
        assertFalse(ClientQuestCache.hasData(), "no chapters and no quests is nothing to show");
    }

    @Test
    @DisplayName("line styles arrive as the chapter's resolved default plus the overrides as written")
    void dependencyStylesRoundTrip(@TempDir Path temp) throws IOException {
        // Built through the real loader, because the two halves being tested are the writer and the
        // reader and the file shape between them: a chapter default that has to be resolved, and a
        // quest's override that must arrive unsaid on the axes it does not name.
        Path quests = temp.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(quests.resolve("group/chapter"));
        Files.writeString(quests.resolve("group/group.json"), """
                { "id": "group", "title": "Group", "chapters": ["chapter"] }
                """);
        Files.writeString(quests.resolve("group/chapter/chapter.json"), """
                { "$schema": "../../../_schema/chapter.schema.json",
                  "id": "chapter", "title": "Chapter",
                  "dependencyStyle": { "form": "curved", "weight": "thick" },
                  "quests": ["one.json", "two.json"] }
                """);
        Files.writeString(quests.resolve("group/chapter/one.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "one", "title": "One" }
                """);
        Files.writeString(quests.resolve("group/chapter/two.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "two", "title": "Two",
                  "dependsOn": ["one"],
                  "dependencyLines": { "one": { "arrows": "none", "dash": "dashed" } } }
                """);

        QuestLoader.Result loaded = QuestLoader.load(temp);
        assertTrue(loaded.ok(), () -> "the fixture has to load cleanly:\n"
                + loaded.problems().all().stream().map(problem -> problem.render())
                        .reduce("", (a, b) -> a + "\n" + b));
        QuestIndex index = loaded.index();
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.Entry two = entryFor("two");
        DependencyStyle chapter = two.chapterDependencyStyle();
        assertEquals(DependencyStyle.Form.CURVED, chapter.formOr(null));
        assertEquals(DependencyStyle.Weight.THICK, chapter.weightOr(null));
        assertEquals(DependencyStyle.Arrows.ONE, chapter.arrowsOr(null),
                "an axis the chapter did not name arrives as the built-in, already resolved");

        DependencyStyle line = two.dependencyLines().get("one");
        assertEquals(DependencyStyle.Arrows.NONE, line.arrowsOr(null));
        assertEquals(DependencyStyle.Dash.DASHED, line.dashOr(null));
        assertTrue(line.form().isEmpty(), "the line named no form, so the chapter's stays in force");
        assertTrue(entryFor("one").dependencyLines().isEmpty(),
                "a quest that overrides nothing carries no map at all");
    }

    // ------------------------------------------------------------------
    // Chapter groups, which version 2 added
    // ------------------------------------------------------------------

    /**
     * The two additions of version 2: a flat {@code groups[]} at the root, and a {@code chapterGroupId}
     * on every quest.
     *
     * <h2>Why this is a nested class of its own rather than four more methods above</h2>
     *
     * <p>Because it is the one part of the wire with a <b>compatibility claim</b> attached, and the claim
     * is what needs testing rather than the fields. The writer and the reader are both hand-written JSON,
     * so nothing but this test connects them; and on top of that the design promises that every change
     * here is additive, which means an old client on a new server keeps drawing today's flat list.
     *
     * <p>That promise is the reason three of the tests below are about what a tree <i>without</i> the new
     * fields does, and what an unexpectedly high version number does. Those are the cases a future
     * change breaks silently — nothing throws, no test about the new field notices, and the symptom is
     * somebody's quest book drawn as a different shape from the one they wrote.
     */
    @Nested
    @DisplayName("the tree's chapter groups")
    class Groups {

        /**
         * Two groups, deliberately written out of alphabetical order.
         *
         * <p>{@code zzz_written_first} before {@code aaa_written_second}, so that "declaration order" and
         * "sorted order" are different answers and a client that sorted cannot pass by accident. Only the
         * characters differ — this is the same trap the loader's folder-name order creates, arriving at
         * the opposite end of the wire, and it is worth catching on both sides independently because
         * neither end can see the other's sort.
         *
         * <p>Written as a version-1 file on purpose. The wire format is version 1's <i>tree</i> plus two
         * fields, and building it through the version-1 codec keeps this test about the wire rather than
         * about the version-2 folder layout — which the acceptance test covers and which would otherwise
         * have to load here too.
         */
        private static final String TWO_GROUPS = """
                {
                  "version": 1,
                  "chapterGroups": [
                    {
                      "id": "zzz_written_first",
                      "title": "Written First",
                      "collapsedByDefault": true,
                      "chapters": [
                        {
                          "id": "first_steps",
                          "title": "First Steps",
                          "quests": [
                            { "id": "punch_a_tree", "title": "Punch a Tree",
                              "tasks": [ { "type": "tasked:checkmark", "title": "t" } ] }
                          ]
                        }
                      ]
                    },
                    {
                      "id": "aaa_written_second",
                      "title": "Written Second",
                      "chapters": [
                        {
                          "id": "second_steps",
                          "title": "Second Steps",
                          "quests": [
                            { "id": "build_a_house", "title": "Build a House",
                              "tasks": [ { "type": "tasked:checkmark", "title": "t" } ] }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;

        private static QuestIndex twoGroups() {
            return Fixtures.indexOf(TWO_GROUPS);
        }

        private static void send(QuestIndex index) {
            ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(),
                    QuestSync.treeAsJson(index));
        }

        @Test
        @DisplayName("the raw JSON carries the chapter list, which is what makes an empty chapter real")
        void theChapterListIsOnTheWire() {
            // Version 3's addition, and the reason it exists: a chapter used to reach the client only as
            // a property of the quests inside it, so one with no quests could not be seen, selected or
            // edited. Asserted here as raw JSON as well as round-tripped through the cache, because the
            // two ends are hand-written and a field name is exactly what they can disagree about.
            QuestIndex index = twoGroups();
            JsonObject root = JsonParser.parseString(
                    new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8)).getAsJsonObject();

            assertEquals(QuestSync.TREE_VERSION, root.get("version").getAsInt());
            JsonArray chapters = root.getAsJsonArray("chapters");
            assertNotNull(chapters, "version 3 has to send the chapter list, however short it is");
            assertEquals(index.chapters().size(), chapters.size());
            JsonObject first = chapters.get(0).getAsJsonObject();
            assertEquals("first_steps", first.get("id").getAsString());
            assertEquals("zzz_written_first", first.get("groupId").getAsString());
            assertEquals("First Steps", first.get("title").getAsString());
            assertTrue(first.has("icon"),
                    "the chapter's own icon, so a chapter with no quests has one of its own rather than "
                            + "borrowing from a quest that does not exist");

            // And the reader holds it, under the same names.
            send(index);
            assertEquals(2, ClientQuestCache.chapters().size());
            assertEquals("first_steps", ClientQuestCache.chapters().get(0).id());
            assertEquals("zzz_written_first", ClientQuestCache.chapters().get(0).groupId());
        }

        @Test
        @DisplayName("the raw JSON carries the version, the headings and each quest's group")
        void groupFieldsMatch() {
            // The same contract check as `fieldNamesMatch` above, tightened onto the three things
            // version 2 added. Named here as well as in that list because that list is a `for` over
            // names, and a name *removed* from it would show up as one fewer iteration rather than as a
            // failure -- this one cannot be weakened without deleting the line.
            String json = new String(QuestSync.treeAsJson(twoGroups()), StandardCharsets.UTF_8);

            assertTrue(json.contains("\"version\":8"),
                    "the tree should declare version 8 (the condition displays), so a reader can tell what it is looking at: " + json);
            assertTrue(json.contains("\"groups\""),
                    "the tree has no groups array, so the client has nothing to build headings from: " + json);
            assertTrue(json.contains("\"chapters\""),
                    "the tree has no chapter list, so an empty chapter would be invisible again: " + json);
            assertTrue(json.contains("\"chapterGroupId\""),
                    "no quest says which group its chapter is in, so the headings would have nothing "
                            + "under them: " + json);

            // And the value, not just the key: a `chapterGroupId` written as a constant would satisfy
            // the check above for every quest.
            assertTrue(json.contains("\"chapterGroupId\":\"zzz_written_first\""), json);
            assertTrue(json.contains("\"chapterGroupId\":\"aaa_written_second\""), json);
        }

        @Test
        @DisplayName("the headings arrive, in the order the server declared them")
        void groupsArrive() {
            // Order is asserted because it is the one thing a client can get wrong without any symptom
            // that points here: sorting these would draw a perfectly good book with the groups in the
            // wrong places, and nothing on screen would say so.
            QuestIndex index = twoGroups();
            send(index);

            assertEquals(2, ClientQuestCache.groups().size(),
                    "the headings did not arrive: " + ClientQuestCache.groups());
            assertEquals(List.of("zzz_written_first", "aaa_written_second"),
                    ClientQuestCache.groups().stream().map(ClientQuestCache.GroupEntry::id).toList(),
                    "declaration order, not sorted order");
            assertEquals("Written First", ClientQuestCache.groups().get(0).title(),
                    "the heading's title did not cross the wire");
        }

        @Test
        @DisplayName("the authored collapsed flag arrives, which is what the sidebar seeds from")
        void collapsedByDefaultArrives() {
            // This is the *only* thing the server says about whether a group starts open, and it applies
            // once, on the first sight of a tree. What the player toggles afterwards is theirs -- which is
            // why the flag travels in one direction only and there is no "collapsed" field on the way
            // back. Both values are asserted, because a flag that always arrived true would pass a test
            // that only checked the true case.
            QuestIndex index = twoGroups();
            send(index);

            assertTrue(ClientQuestCache.groups().get(0).collapsedByDefault(),
                    "the authored collapsed flag did not survive the wire, so a group meant to start "
                            + "closed would start open");
            assertFalse(ClientQuestCache.groups().get(1).collapsedByDefault(),
                    "a group that declares nothing must arrive open -- `collapsedByDefault` defaults to "
                            + "false, and a wire that sent true for it would collapse every group");
        }

        @Test
        @DisplayName("every quest says which group its chapter is in")
        void questsCarryTheirGroupId() {
            // `groups[]` says what the headings are called; this is what says what hangs under them.
            // Without it the client would have headings and no way to place a single chapter.
            QuestIndex index = twoGroups();
            send(index);

            assertEquals("zzz_written_first", entryFor("punch_a_tree").chapterGroupId(),
                    "the quest did not carry its chapter's group");
            assertEquals("aaa_written_second", entryFor("build_a_house").chapterGroupId(),
                    "and the two quests should name *different* groups -- one shared constant would pass "
                            + "a single-assertion version of this test");
        }

        @Test
        @DisplayName("a server older than groups still draws, with no headings and no group id")
        void anOlderServerStillWorks() {
            // The additive promise in the direction this client must honour: it asks for `groups` and
            // `chapterGroupId`, and a tree that has neither has to load anyway. A reader that required
            // either would turn every old server into an empty quest book.
            //
            // Hand-written and version 1, because that is exactly what an older server sends -- and
            // writing it by hand is the point: it is not this build's writer output, so it cannot
            // accidentally contain a field the writer learned to send.
            String older = "{\"version\":1,\"quests\":[{\"chapterId\":\"c\",\"chapterTitle\":\"C\","
                    + "\"id\":\"a\",\"title\":\"A\",\"icon\":\"minecraft:stone\",\"x\":0,\"y\":0,"
                    + "\"size\":48,\"shape\":\"rounded\",\"iconScale\":0.75,\"showTitle\":false,"
                    + "\"description\":[],\"dependsOn\":[],\"tasks\":[],\"rewards\":[]}]}";
            ClientQuestCache.acceptTree(1, 1, older.getBytes(StandardCharsets.UTF_8));

            assertTrue(ClientQuestCache.hasData(), "an older server's tree must still draw");
            assertTrue(ClientQuestCache.groups().isEmpty(),
                    "no groups array means no headings -- and that is the flat chapter list's cue, so it "
                            + "must be empty rather than invented or defaulted");
            assertEquals("", entryFor("a").chapterGroupId(),
                    "and a quest with no group field belongs to no group, which is the same cue at the "
                            + "other level");
        }

        @Test
        @DisplayName("a newer server's tree is drawn with what this client knows, rather than refused")
        void aNewerServerIsNotRefused() {
            // The one thing the version number must never be used for, and the reason it is read and only
            // warned about. Refusing a version this build does not know would break precisely the case the
            // additive design exists to keep working: an install that has not been updated, against a
            // server that has. The alternative -- drawing what it understands and saying so in the log --
            // degrades in the direction that keeps a player playing.
            QuestIndex index = twoGroups();
            String version2 = new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8);

            // Version 9, plus a field this build has never heard of, which is what a newer server's tree
            // actually looks like: the fields it knows, and one more.
            String version9 = version2
                    .replace("\"version\":2", "\"version\":9")
                    .replace("\"quests\":", "\"somethingThisBuildHasNeverSeen\":[1,2,3],\"quests\":");

            ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(),
                    version9.getBytes(StandardCharsets.UTF_8));

            assertTrue(ClientQuestCache.hasData(),
                    "a newer tree must still be drawn with whatever this build understands");
            assertEquals(2, ClientQuestCache.groups().size(),
                    "and the fields it *does* know must still arrive");
            assertEquals("zzz_written_first", entryFor("punch_a_tree").chapterGroupId());
        }
    }

    // ------------------------------------------------------------------
    // The tree revision
    // ------------------------------------------------------------------

    /**
     * The counter that lets a screen tell "still my tree" from "a new one arrived".
     *
     * <h2>The behaviour it exists for, stated as a test rather than as a comment</h2>
     *
     * <p>A screen builds a collapsible outline and keeps the player's toggles in it. It has to be able to
     * ask whether the tree under that outline is still the one it described — and the answer must be "yes"
     * for a resize, a scroll, a toggle and a redraw, and "no" when a reload replaces the tree. Getting the
     * second half wrong leaves a stale outline; getting the first half wrong silently resets the player's
     * collapses every frame, which reads as the toggle buttons not working.
     *
     * <p>Every axis a screen might read is touched in {@link #standsStillWhileNothingArrives}, because the
     * failure to guard against is a reader that bumped the counter as a side effect of answering a
     * question — which is the kind of thing a getter never does until somebody caches something.
     */
    @Nested
    @DisplayName("the tree revision")
    class Revision {

        @Test
        @DisplayName("moves when a tree arrives, so a screen knows its outline is stale")
        void movesWhenATreeArrives() {
            long before = ClientQuestCache.treeRevision();
            QuestIndex index = twoQuests();
            ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

            assertNotEquals(before, ClientQuestCache.treeRevision(),
                    "a tree arrived, so a screen holding an outline built from the previous one has no way "
                            + "to find out -- its outline would keep the rows of a tree the cache has "
                            + "replaced");
        }

        @Test
        @DisplayName("moves on a clear as well, because clearing empties the cache too")
        void movesWhenCleared() {
            // The half that is easy to forget, and the one whose absence has the worst symptom: a
            // disconnect empties the cache, and a screen that seeded an outline at the old revision would
            // go on drawing that tree's rows for a cache with nothing in it.
            QuestIndex index = twoQuests();
            ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
            long loaded = ClientQuestCache.treeRevision();

            ClientQuestCache.clear();

            assertNotEquals(loaded, ClientQuestCache.treeRevision(),
                    "the cache now holds a different tree -- an empty one -- and the revision has to say so");
        }

        @Test
        @DisplayName("moves for a tree that could not be read, since that empties it too")
        void movesForAnUnreadableTree() {
            // A third path out of `acceptTree` that changes what the cache holds, and the one a
            // success-only counter would miss. The tree it had is gone either way; the difference is only
            // in why.
            QuestIndex index = twoQuests();
            ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
            long loaded = ClientQuestCache.treeRevision();

            ClientQuestCache.acceptTree(5, 1, "not json at all".getBytes(StandardCharsets.UTF_8));

            assertNotEquals(loaded, ClientQuestCache.treeRevision(),
                    "the cache was emptied by the failed parse, so a screen must re-seed -- otherwise it "
                            + "draws rows for a tree that is no longer cached");
        }

        @Test
        @DisplayName("moves on a reload, so the authored defaults seed the outline again")
        void movesOnAReload() {
            QuestIndex index = twoQuests();
            QuestSync.treeAsJson(index);

            ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
            long first = ClientQuestCache.treeRevision();
            ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

            assertNotEquals(first, ClientQuestCache.treeRevision(),
                    "a reload means the files changed, and the authored state is the honest one for a tree "
                            + "nobody has seen -- so the revision has to move even when the content is "
                            + "byte-identical, because the unit is 'a tree arrived' and not 'the tree "
                            + "differs'");
        }

        @Test
        @DisplayName("does not move while a screen reads what is cached")
        void standsStillWhileNothingArrives() {
            // The property the counter is *for*: everything a screen does to draw and interact with a
            // tree is a read, and no read may move this. A resize, a scroll, a toggle and a redraw are all
            // in the list below, and every one of them is something a player does constantly.
            QuestIndex index = twoGroupsForRevision();
            ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
            long settled = ClientQuestCache.treeRevision();

            for (int frame = 0; frame < 5; frame++) {
                assertFalse(ClientQuestCache.entries().isEmpty(), "the fixture should have loaded");
                ClientQuestCache.groups();
                ClientQuestCache.chapterTheme("first_steps");
                ClientQuestCache.stateOf("punch_a_tree");
                ClientQuestCache.canClaimFor(UUID.randomUUID(), "punch_a_tree");
                assertEquals(settled, ClientQuestCache.treeRevision(),
                        "frame " + frame + ": reading the cache moved the revision, so a screen that "
                                + "rebuilt its outline from it would reset the player's toggles every "
                                + "frame -- which looks like the toggles not working");
            }
        }

        private static QuestIndex twoGroupsForRevision() {
            return Fixtures.indexOf("""
                    {
                      "version": 1,
                      "chapterGroups": [
                        {
                          "id": "g",
                          "title": "G",
                          "chapters": [
                            {
                              "id": "first_steps",
                              "title": "First Steps",
                              "quests": [
                                { "id": "punch_a_tree", "title": "Punch a Tree",
                                  "tasks": [ { "type": "tasked:checkmark", "title": "t" } ] }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                    """);
        }
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
    @DisplayName("who is contributing arrives with the quest, and reaches the client's cache")
    void contributionsArrive() {
        // The whole of the feature's plumbing in one test: the engine's per-member picture, through the
        // JSON the client is sent, into the cache the book draws from. Every part of it was already
        // there except the picture, which the engine computed and threw away.
        QuestIndex index = twoQuests();
        TeamProgress progress = progressWith(index, "punch_a_tree",
                QuestProgress.NONE.recordTask(0, 4));
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, progress, NOW);

        UUID ellio = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID friend = UUID.fromString("00000000-0000-0000-0000-00000000000b");

        // Ordered, as the engine hands it over: the client's delta compares JSON text, so a picture
        // that came out in a different order for the same numbers would re-send the quest forever.
        Map<UUID, Integer> picture = new LinkedHashMap<>();
        picture.put(ellio, 4);
        picture.put(friend, 2);

        dev.ellipog.tasked.progress.ProgressService.Contributors contributors = (questId, taskIndex) ->
                questId.equals("punch_a_tree") && taskIndex == 0 ? picture : Map.of();

        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW,
                QuestSync.progressAsJson(resolution, progress, index, contributors), CLIENT_TICK);

        Map<UUID, Integer> arrived = ClientQuestCache.contributorsOf("punch_a_tree", 0);
        assertEquals(4, arrived.get(ellio),
                "the member holding four logs has to arrive holding four");
        assertEquals(2, arrived.get(friend), "and so does the one holding two");
        assertEquals(4, ClientQuestCache.taskProgressOf("punch_a_tree", 0),
                "and the party's own total is unchanged by any of this");
        assertTrue(ClientQuestCache.contributorsOf("punch_a_tree", 1).isEmpty(),
                "a task nobody is carrying anything toward names nobody");
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
    @DisplayName("a finished quest with rewards still to collect arrives as claimable, and stops once collected")
    void claimableArrives() {
        // The field the Claim button hangs on, and it was not on the wire at all until now -- which is
        // the same shape of gap as `shape` before it: a value the engine records, `/tasked progress`
        // prints, and no client ever hears about, so the button could not have existed.
        //
        // Both halves are asserted, because a flag that is only ever set is indistinguishable from one
        // that is always true. The collected half is what makes this a test of the guard.
        QuestIndex index = rewardedQuest();
        Quest quest = Fixtures.quest(index, "a");
        // The claim view reads the reward's own team flag from the tree, so the tree must be loaded
        // -- which it always is when the book is open.
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        TeamProgress waiting = TeamProgress.empty().put(quest,
                QuestProgress.NONE.completedAt(NOW).withRewardsClaimed(false));
        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW,
                QuestSync.progressAsJson(ProgressionEngine.resolve(index, waiting, NOW), waiting, index),
                CLIENT_TICK);

        assertEquals(QuestState.COMPLETED, ClientQuestCache.stateOf("a"), "fixture sanity");
        UUID player = UUID.randomUUID();
        assertTrue(ClientQuestCache.canClaimFor(player, "a"),
                "a finished quest whose rewards nobody has collected must read as claimable on the "
                        + "client, or there is nothing for a Claim button to be drawn from");

        TeamProgress collected = waiting.put(quest, waiting.progressOf(quest)
                .withClaims(dev.ellipog.tasked.progress.QuestClaims.NONE.withPlayerClaim(player, 0)));
        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW,
                QuestSync.progressAsJson(ProgressionEngine.resolve(index, collected, NOW), collected, index),
                CLIENT_TICK);

        assertFalse(ClientQuestCache.canClaimFor(player, "a"),
                "and not once they have collected their own copy");
        assertTrue(ClientQuestCache.canClaimFor(UUID.randomUUID(), "a"),
                "a teammate still has their own copy to collect -- the whole point of per-player claims");
    }

    @Test
    @DisplayName("an unfinished quest is not claimable, however many rewards it carries")
    void unfinishedIsNotClaimable() {
        // The other half of the guard, and the half a "does it have rewards" shortcut gets wrong: a
        // quest that is merely unlocked and carrying rewards must not offer a Claim button, or the
        // button would be on screen from the moment the quest appeared.
        QuestIndex index = rewardedQuest();
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
        TeamProgress fresh = TeamProgress.empty();

        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW,
                QuestSync.progressAsJson(ProgressionEngine.resolve(index, fresh, NOW), fresh, index),
                CLIENT_TICK);

        assertEquals(QuestState.UNLOCKED, ClientQuestCache.stateOf("a"), "fixture sanity");
        assertFalse(ClientQuestCache.canClaimFor(UUID.randomUUID(), "a"));
    }

    /**
     * One quest with a reward, built through the real codecs.
     *
     * <p>A legitimate prize worth having, and deliberately not a consumable one: the item never has to
     * be granted here, because these tests are about what travels on the wire. What matters is that the
     * quest has a non-empty reward list, which is the first clause of the claimable guard.
     */
    private static QuestIndex rewardedQuest() {
        return Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": ["
                        + " { \"type\": \"tasked:checkmark\", \"title\": \"done\"} ],"
                        + " \"rewards\": ["
                        + " { \"type\": \"tasked:item\", \"item\": \"minecraft:wooden_axe\", \"count\": 1} ]}"));
    }

    @Test
    @DisplayName("a stage-locked quest arrives LOCKED and with no claim, whatever the team's progress says")
    void aStageLockedQuestArrivesLocked() {
        // The third per-player overlay on this wire, after the contributors and the claimable flag. A
        // gated quest's stored state is the team's, and whether *this* player may see and collect it is
        // not -- so the client is told locked, and the claim flag is not sent at all.
        QuestIndex index = gatedRewardedQuest();
        Quest quest = Fixtures.quest(index, "a");
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        // Finished with the reward still waiting: exactly the state that must not draw a Claim button
        // for a player the gate has shut out.
        TeamProgress waiting = TeamProgress.empty().put(quest,
                QuestProgress.NONE.completedAt(NOW).withRewardsClaimed(false));
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, waiting, NOW);
        UUID player = UUID.randomUUID();

        byte[] gated = QuestSync.progressDelta(resolution, waiting, index, null,
                (questId, taskIndex) -> Map.of(), java.util.Set.of("a")).json();
        assertFalse(new String(gated, StandardCharsets.UTF_8).contains("claimable"),
                "a gated quest must not carry the claim flag at all: "
                        + new String(gated, StandardCharsets.UTF_8));
        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW, gated, CLIENT_TICK);

        assertEquals(QuestState.LOCKED, ClientQuestCache.stateOf("a"),
                "the overlay is the whole of this player's view of the quest");
        assertFalse(ClientQuestCache.canClaimFor(player, "a"),
                "and a quest this player cannot open must not offer them a claim");

        // The control: the same progress with the gate open reads exactly as the stored state does, so
        // the assertions above are about the overlay and not about the fixture.
        byte[] open = QuestSync.progressDelta(resolution, waiting, index, null,
                (questId, taskIndex) -> Map.of(), java.util.Set.of()).json();
        assertTrue(new String(open, StandardCharsets.UTF_8).contains("claimable"),
                "the control must carry the claim flag: " + new String(open, StandardCharsets.UTF_8));
        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW, open, CLIENT_TICK);

        assertEquals(QuestState.COMPLETED, ClientQuestCache.stateOf("a"),
                "with the stage held, the same progress is the completed quest it always was");
        assertTrue(ClientQuestCache.canClaimFor(player, "a"),
                "and it is claimable again");
    }

    /** The rewarded quest, behind a stage: the fixture the overlay test needs both halves of. */
    private static QuestIndex gatedRewardedQuest() {
        return Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"requiresStage\": \"my_pack:marked\","
                        + " \"tasks\": [ { \"type\": \"tasked:checkmark\", \"title\": \"done\"} ],"
                        + " \"rewards\": ["
                        + " { \"type\": \"tasked:item\", \"item\": \"minecraft:wooden_axe\", \"count\": 1} ]}"));
    }

    // ------------------------------------------------------------------
    // Conditions
    // ------------------------------------------------------------------

    /**
     * One quest whose rows carry conditions: a stage gate, an item gate, and a reward with two.
     *
     * <p>Both display shapes are in here on purpose -- a text gate and an item gate -- because the
     * line a hover shows is built differently for each and only the item one carries a count.
     */
    private static QuestIndex conditionedQuest() {
        return Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": ["
                        + " { \"type\": \"tasked:checkmark\", \"title\": \"done\","
                        + "   \"conditions\": [ { \"type\": \"tasked:stage\", \"stage\": \"my_pack:marked\" } ] },"
                        + " { \"type\": \"tasked:checkmark\", \"title\": \"more\","
                        + "   \"conditions\": [ { \"type\": \"tasked:item\", \"item\": \"minecraft:cobblestone\","
                        + "                       \"count\": 8 } ] } ],"
                        + " \"rewards\": ["
                        + " { \"type\": \"tasked:item\", \"item\": \"minecraft:wooden_axe\", \"count\": 1,"
                        + "   \"conditions\": ["
                        + "     { \"type\": \"tasked:item_tag\", \"tag\": \"minecraft:logs\", \"count\": 8 },"
                        + "     { \"type\": \"tasked:party_size\", \"min\": 2 } ] } ]}"));
    }

    @Test
    @DisplayName("a task's and a reward's conditions cross the wire, and each reads as a sentence")
    void conditionDisplayCrossesTheWire() {
        QuestIndex index = conditionedQuest();
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.Entry entry = ClientQuestCache.entry("a");
        assertNotNull(entry, "the tree must have arrived");

        ClientQuestCache.TaskEntry stageGated = entry.tasks().get(0);
        assertEquals(1, stageGated.conditions().size(), "the task's gate must be on the wire");
        assertEquals("Have the stage my_pack:marked", stageGated.conditions().get(0).line(),
                "a text gate reads as the sentence the key and the subject make");

        ClientQuestCache.TaskEntry itemGated = entry.tasks().get(1);
        assertEquals(1, itemGated.conditions().size());
        assertEquals("Cobblestone \u00d78", itemGated.conditions().get(0).line(),
                "an item gate reads as the item's own name and count, which is the one line that "
                        + "carries its number outside the sentence");

        ClientQuestCache.RewardEntry reward = entry.rewards().get(0);
        assertEquals(2, reward.conditions().size(), "both of the reward's gates");
        assertEquals("Have #minecraft:logs \u00d78", reward.conditions().get(0).line(),
                "a tag gate names the tag and the count in its subject");
        assertEquals("Be in a party of 2 or more", reward.conditions().get(1).line(),
                "and a party gate reads as the sentence its key writes");
    }

    @Test
    @DisplayName("a lock arrives per row, absence reads unlocked, and a locked reward is not offered")
    void conditionLocksArriveAndAbsenceReadsUnlocked() {
        QuestIndex index = conditionedQuest();
        Quest quest = Fixtures.quest(index, "a");
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        // Finished with the reward waiting: the state where a locked row would otherwise offer a
        // Claim button the server refuses.
        TeamProgress waiting = TeamProgress.empty().put(quest,
                QuestProgress.NONE.completedAt(NOW).withRewardsClaimed(false));
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, waiting, NOW);
        UUID player = UUID.randomUUID();

        Map<String, ProgressService.LockView> locks = Map.of("a", new ProgressService.LockView(
                Map.of(0, List.of(0), 1, List.of(0)), Map.of(0, List.of(0, 1))));
        byte[] locked = QuestSync.progressDelta(resolution, waiting, index, null,
                (questId, taskIndex) -> Map.of(), java.util.Set.of(), locks).json();
        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW, locked, CLIENT_TICK);

        assertEquals(List.of(0), ClientQuestCache.taskLockOf("a", 0), "the stage gate's row");
        assertEquals(List.of(0), ClientQuestCache.taskLockOf("a", 1), "the item gate's row");
        assertEquals(List.of(0, 1), ClientQuestCache.rewardLockOf("a", 0),
                "both unmet conditions, ascending, because the hover names exactly those");
        assertTrue(ClientQuestCache.taskLockOf("a", 5).isEmpty(),
                "a row the server said nothing about reads unlocked -- absence is the safe default "
                        + "here, the opposite of `claimable`");
        assertFalse(ClientQuestCache.canClaimFor(player, "a"),
                "a reward this player is locked out of is not offered a button");

        // The control, which is also the shape an older server sends: no lock keys at all.
        byte[] none = QuestSync.progressDelta(resolution, waiting, index, null,
                (questId, taskIndex) -> Map.of(), java.util.Set.of(), Map.of()).json();
        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW, none, CLIENT_TICK);

        assertTrue(ClientQuestCache.taskLockOf("a", 0).isEmpty(), "nothing is locked without locks");
        assertTrue(ClientQuestCache.canClaimFor(player, "a"),
                "and the claim is offered again -- the two answers come from the same map");
    }

    @Test
    @DisplayName("a lock flip changes the quest's text, and an unchanged picture sends nothing")
    void aLockFlipChangesTheDelta() {
        // The delta is a comparison of the per-quest text, so a lock the client would draw must be in
        // that text or a lock appearing would produce no message at all. The same property makes the
        // refresh affordable: an unchanged picture serialises identically and the delta stays empty.
        QuestIndex index = conditionedQuest();
        Quest quest = Fixtures.quest(index, "a");
        TeamProgress waiting = TeamProgress.empty().put(quest,
                QuestProgress.NONE.completedAt(NOW).withRewardsClaimed(false));
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, waiting, NOW);

        Map<String, ProgressService.LockView> locks = Map.of("a", new ProgressService.LockView(
                Map.of(0, List.of(0)), Map.of()));
        QuestSync.Delta with = QuestSync.progressDelta(resolution, waiting, index, null,
                (questId, taskIndex) -> Map.of(), java.util.Set.of(), locks);
        QuestSync.Delta without = QuestSync.progressDelta(resolution, waiting, index, null,
                (questId, taskIndex) -> Map.of(), java.util.Set.of(), Map.of());
        assertNotEquals(new String(with.json(), StandardCharsets.UTF_8),
                new String(without.json(), StandardCharsets.UTF_8),
                "a lock must be visible in the text the delta is made of");

        QuestSync.Delta settled = QuestSync.progressDelta(resolution, waiting, index, with.snapshot(),
                (questId, taskIndex) -> Map.of(), java.util.Set.of(), locks);
        assertEquals(0, questsOf(settled.json()).size(),
                "the same picture again must produce nothing to send, or the refresh would resend "
                        + "every quest every second: " + new String(settled.json(), StandardCharsets.UTF_8));
    }

    private static JsonObject questsOf(byte[] json) {
        return JsonParser.parseString(new String(json, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("quests");
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
