package dev.ellipog.tasked.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.ellipog.tasked.quest.MinecraftTestBootstrap;
import dev.ellipog.tasked.quest.task.TaskTypes;
import dev.ellipog.tasked.quest.reward.RewardTypes;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An op written down, read back, and applied to a chapter — including one that must be refused.
 *
 * <h2>Why the round trip is tested as carefully as the edit</h2>
 *
 * <p>Because the two ends are different programs: the op is written by a client, sent, and applied by a
 * server that has never seen the client's copy of anything. A codec that loses a field, a reader that
 * accepts what the applier cannot use, or an applier that leaves a half-written file are the three ways this
 * can be wrong, and all three look identical from the player's side: *the edit did not happen*. The refusal
 * case is the one that matters most — a save that refused must leave the disk exactly as it was — so it is
 * asserted on the file's own bytes rather than on the model's memory.
 */
@DisplayName("An edit, as an op")
class EditorOpsTest {

    private static final String MANIFEST = """
            {
              "$schema": "../../_schema/chapter.schema.json",
              "id": "first_steps",
              "title": "First Steps",
              "quests": [ "one.json", "two.json" ]
            }
            """;

    private static final String ONE = """
            {
              "id": "one",
              "title": "One",
              "x": 0,
              "y": 0,
              "icon": { "item": "minecraft:oak_log" }
            }
            """;

    private static final String TWO = """
            {
              "id": "two",
              "title": "Two",
              "x": 64,
              "y": 0,
              "dependsOn": [ "one" ],
              "icon": { "item": "minecraft:stick" }
            }
            """;

    private Path root;
    private Path folder;

    @BeforeAll
    static void bootstrapMinecraft() {
        // Applying an op saves, and saving validates with the loader's own validator, which checks that a
        // named item exists. See the helper: without this the registry is empty and the failed class
        // initialiser poisons every other test in the same JVM.
        MinecraftTestBootstrap.boot();
    }

    @BeforeEach
    void chapter(@TempDir Path dir) throws IOException {
        root = dir.resolve("quests");
        folder = root.resolve("getting_started").resolve("first_steps");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("chapter.json"), MANIFEST, StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("one.json"), ONE, StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("two.json"), TWO, StandardCharsets.UTF_8);
        Path group = root.resolve("getting_started");
        Files.writeString(group.resolve("group.json"), """
                { "id": "getting_started", "title": "Getting Started", "chapters": [ "first_steps" ] }
                """, StandardCharsets.UTF_8);
    }

    private QuestEditor open() {
        return QuestEditor.open(root, "first_steps").orElseThrow();
    }

    private String file(String id) throws IOException {
        return Files.readString(folder.resolve(id + ".json"), StandardCharsets.UTF_8);
    }

    private static JsonObject roundTrip(QuestEditor editor, EditorOp op) {
        JsonObject wire = EditorOps.write(op);
        EditorOp read = EditorOps.read(wire);
        assertEquals(op, read, "an op the codec loses is an edit that does nothing");
        return wire;
    }

    // ------------------------------------------------------------------
    // The wire
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every kind survives being written down and read back")
    void theRoundTrip() {
        QuestEditor editor = open();
        JsonArray list = new JsonArray();
        list.add("one");

        roundTrip(editor, new EditorOp.SetField("one", "title", new JsonPrimitive("Renamed")));
        roundTrip(editor, new EditorOp.SetField("two", "dependsOn", list));
        // The raw path's shape: a whole object, the way an unknown type's entry crosses. It is the one
        // value the typed setters cannot carry, and the reason `SetField` holds a `JsonElement` at all.
        JsonObject icon = new JsonObject();
        icon.addProperty("item", "minecraft:oak_log");
        icon.addProperty("count", 2);
        roundTrip(editor, new EditorOp.SetField("one", "icon", icon));
        roundTrip(editor, new EditorOp.SetField("two", "x", new JsonPrimitive(128)));
        roundTrip(editor, new EditorOp.SetField("one", "repeatable", new JsonPrimitive(true)));
        roundTrip(editor, new EditorOp.SetField("one", "title", JsonNull.INSTANCE));
        roundTrip(editor, new EditorOp.Move("one", 12.5, -30));
        roundTrip(editor, new EditorOp.Create(16, 16));
        roundTrip(editor, new EditorOp.Duplicate("one"));
        roundTrip(editor, new EditorOp.Paste(pasteTree(), 26, 26));
        roundTrip(editor, new EditorOp.Insert("one", "tasks", 0, pasteTree()));
        roundTrip(editor, new EditorOp.Remove("one", "tasks", 0));
        roundTrip(editor, new EditorOp.MoveEntry("one", "tasks", 0, 1));
        roundTrip(editor, new EditorOp.SetChapter("icon", icon));
        roundTrip(editor, new EditorOp.SetGroup("icon", icon));
        roundTrip(editor, new EditorOp.SetIndex("bookTitle", new JsonPrimitive("The Orrery Ledger")));
        roundTrip(editor, new EditorOp.SetIndex("bookIcon", JsonNull.INSTANCE));
        roundTrip(editor, new EditorOp.Delete("two"));
        roundTrip(editor, new EditorOp.Undo());
        roundTrip(editor, new EditorOp.Redo());
    }

    /** A quest tree from somewhere else: the shape the clipboard carries across chapters. */
    private static JsonObject pasteTree() {
        JsonObject tree = new JsonObject();
        tree.addProperty("id", "from_elsewhere");
        tree.addProperty("title", "Pasted");
        tree.addProperty("x", 0);
        tree.addProperty("y", 0);
        JsonObject icon = new JsonObject();
        icon.addProperty("item", "minecraft:oak_log");
        tree.add("icon", icon);
        // A field the panel shows but never writes: the paste carries the tree whole, so it survives
        // an op path that has no row for it.
        JsonArray description = new JsonArray();
        description.add("kept, not understood");
        tree.add("description", description);
        return tree;
    }

    @Test
    @DisplayName("a kind this build does not know, or a malformed one, is a refusal rather than an exception")
    void theReaderRefuses() {
        // This runs on a server thread holding a player's message: a client from a newer version must cost a
        // sentence, not a crash in the tick loop.
        assertNull(EditorOps.read(null));
        assertNull(EditorOps.read(new JsonObject()));
        assertNull(EditorOps.read(new JsonObject()));           // no kind
        JsonObject unknown = new JsonObject();
        unknown.addProperty("kind", "reticulate");
        assertNull(EditorOps.read(unknown));

        JsonObject noQuest = new JsonObject();
        noQuest.addProperty("kind", "delete");
        assertNull(EditorOps.read(noQuest), "a delete with nothing to delete");

        JsonObject badNumber = new JsonObject();
        badNumber.addProperty("kind", "move");
        badNumber.addProperty("quest", "one");
        badNumber.addProperty("x", "left");
        badNumber.addProperty("y", 0);
        assertNull(EditorOps.read(badNumber));
    }

    // ------------------------------------------------------------------
    // Applying
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a field edit lands in the model and on the disk")
    void aFieldIsWritten() throws IOException {
        QuestEditor editor = open();

        EditorOps.Applied applied = EditorOps.apply(editor,
                new EditorOp.SetField("one", "title", new JsonPrimitive("Renamed")));

        assertTrue(applied.ok(), () -> "refused: " + applied.messages());
        assertEquals("Renamed", editor.quest("one").text("title", ""));
        assertTrue(file("one").contains("\"Renamed\""), "the file is where the edit has to end up");
        assertFalse(editor.dirty(), "an applied op is saved, so nothing is left pending");
    }

    @Test
    @DisplayName("a list, a number, a flag and a null all arrive as the shapes the format uses")
    void theValueShapes() throws IOException {
        QuestEditor editor = open();
        // An empty list: a *non-empty* one would have to name a quest that does not already depend on this
        // one, because "one depends on two" and "two depends on one" is a cycle -- which the validator
        // refused, correctly, the first time this test ran with `two` in it.
        JsonArray list = new JsonArray();

        assertTrue(EditorOps.apply(editor,
                new EditorOp.SetField("one", "dependsOn", list)).ok());
        assertTrue(EditorOps.apply(editor,
                new EditorOp.SetField("one", "x", new JsonPrimitive(96))).ok());
        assertTrue(EditorOps.apply(editor,
                new EditorOp.SetField("one", "repeatable", new JsonPrimitive(true))).ok());
        assertTrue(editor.quest("one").has("dependsOn"), "written as an empty list, not left out");
        assertTrue(editor.quest("one").strings("dependsOn").isEmpty());
        assertEquals(96, editor.quest("one").number("x", -1), 0.0001);
        assertTrue(editor.quest("one").flag("repeatable", false));

        assertTrue(EditorOps.apply(editor, new EditorOp.SetField("one", "repeatable", JsonNull.INSTANCE)).ok());
        assertFalse(editor.quest("one").has("repeatable"), "a null value removes the field rather than "
                + "blanking it");
    }

    @Test
    @DisplayName("an edit the loader would refuse is reported, changes nothing, and leaves the file alone")
    void aRefusedEditCostsNothing() throws IOException {
        String before = file("one");
        QuestEditor editor = open();
        // An unknown *field*, which is still fatal. It has to be a fault the loader genuinely cannot
        // read, and the two former candidates are gone: a missing item became a warning when the id was
        // kept and the row marked, and an unknown *type* became one when the dispatch learned to decode
        // it to a placeholder -- so a chapter containing `addon:missing` now loads, and refusing its save
        // would be the editor disagreeing with the loader about the same file.
        //
        // A field no type declares is the fault that remains an error, and it is the right one for this
        // test: it is what the typo check exists to catch, and the check is what makes "the loader would
        // refuse this" true rather than assumed.
        JsonObject mystery = new JsonObject();
        mystery.addProperty("type", "tasked:checkmark");
        mystery.addProperty("title", "Did it");
        mystery.addProperty("splines", 4);

        EditorOps.Applied applied = EditorOps.apply(editor, new EditorOp.Insert("one", "tasks", 0, mystery));

        assertFalse(applied.ok(), "an unloadable chapter must not be written");
        assertFalse(applied.messages().isEmpty(), "and the reason has to be sayable");
        assertEquals(before, file("one"), "the file is byte for byte what it was");
        assertFalse(editor.quest("one").has("tasks"),
                "and the model was put back, so memory and disk agree again");
        assertFalse(editor.dirty());
    }

    @Test
    @DisplayName("a move writes the position it was given, which is what an empty one looked like")
    void aMoveIsAPosition() throws IOException {
        // The assertion that was missing when drags committed 0,0: the earlier tests round-tripped the op
        // *object*, and a faithful write and read of zero is indistinguishable from a faithful write and read
        // of -64,32. This asks the file instead, at a position no fixture uses.
        QuestEditor editor = open();

        assertTrue(EditorOps.apply(editor, new EditorOp.Move("two", 128, -64)).ok());

        String written = file("two");
        assertTrue(written.contains("\"x\": 128"), () -> "x was: " + written);
        assertTrue(written.contains("\"y\": -64"), () -> "y was: " + written);
    }

    @Test
    @DisplayName("creating returns the id the server chose, and the manifest lists it")
    void creating() {
        QuestEditor editor = open();

        EditorOps.Applied applied = EditorOps.apply(editor, new EditorOp.Create(200, 40));

        assertTrue(applied.ok(), () -> "refused: " + applied.messages());
        assertNotNull(applied.questId(), "the client has to be told which quest it just made");
        assertTrue(editor.questIds().contains(applied.questId()));
        assertNotNull(editor.quest(applied.questId()));
        assertTrue(Files.exists(folder.resolve(applied.questId() + ".json")));
    }

    @Test
    @DisplayName("a duplicate is a new id, not the same file twice")
    void duplicating() {
        QuestEditor editor = open();

        EditorOps.Applied applied = EditorOps.apply(editor, new EditorOp.Duplicate("one"));

        assertTrue(applied.ok());
        assertNotEquals("one", applied.questId());
        assertTrue(editor.questIds().containsAll(List.of("one", applied.questId())));
    }

    @Test
    @DisplayName("a pasted tree lands under its own id, with everything the panel cannot carry")
    void pasting() throws IOException {
        QuestEditor editor = open();

        EditorOps.Applied applied = EditorOps.apply(editor, new EditorOp.Paste(pasteTree(), 96, 48));

        assertTrue(applied.ok(), () -> "refused: " + applied.messages());
        assertEquals("from_elsewhere", applied.questId(), "the id a paste landed under is reported");
        assertTrue(file("from_elsewhere").contains("kept, not understood"),
                "the tree is the file: a field the panel has no row for survives the paste");
        assertEquals(96, editor.quest("from_elsewhere").number("x", -1), 0.0001,
                "and the position is the paste point, not the copy's own coordinates");

        // Pasting the same tree twice: the second gets a name of its own rather than a collision --
        // which is what makes pasting into the chapter a copy came from still land.
        EditorOps.Applied again = EditorOps.apply(editor, new EditorOp.Paste(pasteTree(), 0, 0));
        assertTrue(again.ok(), () -> "refused: " + again.messages());
        assertEquals("from_elsewhere_2", again.questId());
    }

    @Test
    @DisplayName("a paste that would not load is refused whole, with nothing left behind")
    void aRefusedPasteCostsNothing() {
        QuestEditor editor = open();
        JsonObject broken = new JsonObject();
        broken.addProperty("id", "broken");
        // No title: the loader requires one, and the validator is what says so.

        EditorOps.Applied applied = EditorOps.apply(editor, new EditorOp.Paste(broken, 0, 0));

        assertFalse(applied.ok());
        assertFalse(applied.messages().isEmpty(), "the validator's own message is what a refusal carries");
        assertNull(editor.quest("broken"), "nothing left in the model");
        assertFalse(Files.exists(folder.resolve("broken.json")), "and nothing left on disk");
    }

    @Test
    @DisplayName("a task is inserted from the type's own defaults, in order, and the loader accepts it")
    void insertingATask() throws IOException {
        QuestEditor editor = open();
        JsonObject task = TaskTypes.defaultTree(TaskTypes.ITEM.id()).orElseThrow();

        assertTrue(EditorOps.apply(editor, new EditorOp.Insert("one", "tasks", 0, task)).ok(),
                "a default item task validates as written");
        assertTrue(EditorOps.apply(editor, new EditorOp.Insert("one", "tasks", 1,
                TaskTypes.defaultTree(TaskTypes.CHECKMARK.id()).orElseThrow())).ok());

        assertEquals("tasked:item", editor.quest("one").text("tasks.0.type", ""));
        assertEquals("tasked:checkmark", editor.quest("one").text("tasks.1.type", ""),
                "the second insert landed after the first");
        assertTrue(file("one").contains("tasked:checkmark"), "and both are on disk");
    }

    @Test
    @DisplayName("a reward moves within its array, and the order is the order on disk")
    void movingAReward() {
        QuestEditor editor = open();
        assertTrue(EditorOps.apply(editor, new EditorOp.Insert("one", "rewards", 0,
                RewardTypes.defaultTree(RewardTypes.ITEM.id()).orElseThrow())).ok());
        assertTrue(EditorOps.apply(editor, new EditorOp.Insert("one", "rewards", 1,
                RewardTypes.defaultTree(RewardTypes.XP.id()).orElseThrow())).ok());

        assertTrue(EditorOps.apply(editor, new EditorOp.MoveEntry("one", "rewards", 0, 1)).ok());
        assertEquals("tasked:xp", editor.quest("one").text("rewards.0.type", ""),
                "the item reward moved behind the xp one");
        assertEquals("tasked:item", editor.quest("one").text("rewards.1.type", ""));
    }

    @Test
    @DisplayName("the chapter's quest list reorders by saying the whole list -- the drag's own op")
    void theQuestListReorders() throws IOException {
        QuestEditor editor = open();
        JsonArray reordered = new JsonArray();
        reordered.add("two.json");
        reordered.add("one.json");

        assertTrue(EditorOps.apply(editor, new EditorOp.SetChapter("quests", reordered)).ok(),
                "a chapter's list is the manifest's array, and SetChapter is what reaches it");
        assertEquals(List.of("two", "one"), editor.questIds(),
                "the manifest's order is the chapter's order, in memory and on disk");
        String onDisk = file("chapter");
        assertTrue(onDisk.indexOf("two.json") < onDisk.indexOf("one.json"),
                "and the file itself says so, in that order");
    }

    @Test
    @DisplayName("a group op writes the group's own file, and the chapter's is not touched")
    void groupOpsApply() throws IOException {
        QuestEditor editor = open();
        String chapterBefore = file("chapter");
        JsonObject icon = new JsonObject();
        icon.addProperty("item", "minecraft:anvil");

        EditorOps.Applied applied = EditorOps.apply(editor, new EditorOp.SetGroup("icon", icon));

        assertTrue(applied.ok(), () -> "the op was refused: " + applied.messages());
        String group = Files.readString(root.resolve("getting_started").resolve("group.json"),
                StandardCharsets.UTF_8);
        assertTrue(group.contains("minecraft:anvil"), "the group's file is what was written");
        assertEquals(chapterBefore, file("chapter"), "and the chapter's own file is untouched");
    }

    @Test
    @DisplayName("deleting a task's item is refused whole -- the loader would drop the quest")
    void aRequiredFieldCannotBeDeleted() throws IOException {
        QuestEditor editor = open();
        JsonObject task = new JsonObject();
        task.addProperty("type", "tasked:item");
        task.addProperty("item", "minecraft:oak_log");
        assertTrue(EditorOps.apply(editor, new EditorOp.Insert("one", "tasks", 0, task)).ok());

        String before = file("one");
        assertFalse(EditorOps.apply(editor, new EditorOp.SetField("one", "tasks.0.item", null)).ok(),
                "an item task with no item is a quest the loader drops, so the save must refuse it");
        assertEquals(before, file("one"), "a refused edit leaves the bytes exactly as they were");
    }

    @Test
    @DisplayName("clearing the icon is legal -- it is the one item field the format can do without")
    void theIconMayBeCleared() throws IOException {
        // The other half of the pin above, and the reason the picker's clear row is allowed to exist
        // at all: the icon is optional, the default applies when the whole object is absent, and the
        // clear removes the object rather than the leaf -- {"icon": {}} does not load either.
        QuestEditor editor = open();
        assertTrue(EditorOps.apply(editor, new EditorOp.SetField("one", "icon", null)).ok(),
                "an absent icon falls back to the default, so this save must be allowed");
        assertFalse(file("one").contains("\"icon\""), "and the file no longer names one");
    }

    @Test
    @DisplayName("a removed entry is gone from the file, and an impossible index changes nothing")
    void removingAnEntry() {
        QuestEditor editor = open();
        assertTrue(EditorOps.apply(editor, new EditorOp.Insert("one", "tasks", 0,
                TaskTypes.defaultTree(TaskTypes.ITEM.id()).orElseThrow())).ok());

        assertTrue(EditorOps.apply(editor, new EditorOp.Remove("one", "tasks", 0)).ok());
        assertNull(editor.quest("one").get("tasks.0"), "the list is empty again");

        EditorOps.Applied refused = EditorOps.apply(editor, new EditorOp.Remove("one", "tasks", 3));
        assertFalse(refused.ok(), "removing what is not there is a refusal, not a silent success");
    }

    @Test
    @DisplayName("a field whose value is an object crosses the wire as itself")
    void objectValuesCross() throws IOException {
        QuestEditor editor = open();
        // The raw-editing path: a whole value the panel has no shape for. "icon" is an object the
        // format declares, so the validator accepts it and the file shows it.
        JsonObject icon = new JsonObject();
        icon.addProperty("item", "minecraft:diamond");
        icon.addProperty("count", 2);

        assertTrue(EditorOps.apply(editor,
                new EditorOp.SetField("one", "icon", icon)).ok());
        assertEquals("minecraft:diamond", editor.quest("one").text("icon.item", ""));
        assertEquals(2, editor.quest("one").number("icon.count", 0), 0.0001,
                "the object arrived whole, count and all");
    }

    @Test
    @DisplayName("a delete is recoverable: the file is renamed rather than destroyed")
    void deleting() {
        QuestEditor editor = open();

        EditorOps.Applied applied = EditorOps.apply(editor, new EditorOp.Delete("two"));

        assertTrue(applied.ok());
        assertFalse(editor.questIds().contains("two"));
        assertFalse(Files.exists(folder.resolve("two.json")));
        assertTrue(Files.exists(folder.resolve("two.json.deleted")), "recoverable means the bytes are still "
                + "there, which is the thing an author has no other way of getting back");
    }

    @Test
    @DisplayName("undo through an op puts a field back, on the disk as well as in memory")
    void undoing() throws IOException {
        QuestEditor editor = open();
        assertTrue(EditorOps.apply(editor, new EditorOp.SetField("one", "title", new JsonPrimitive("Renamed")))
                .ok());

        EditorOps.Applied undone = EditorOps.apply(editor, new EditorOp.Undo());

        assertTrue(undone.ok(), () -> "refused: " + undone.messages());
        assertEquals("One", editor.quest("one").text("title", ""));
        assertTrue(file("one").contains("\"One\""));
    }

    @Test
    @DisplayName("an op about a quest that is not there is refused, not applied to something else")
    void noSuchQuest() {
        QuestEditor editor = open();

        EditorOps.Applied applied = EditorOps.apply(editor, new EditorOp.SetField("three", "title",
                new JsonPrimitive("Ghost")));

        assertFalse(applied.ok());
        assertEquals(List.of("one", "two"), editor.questIds());
        assertFalse(editor.dirty());
    }

    // ------------------------------------------------------------------
    // A batch: one gesture, one step
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a batch of duplicates is one history step, and one undo takes all of them back")
    void aBatchIsOneStep() throws IOException {
        QuestEditor editor = open();

        EditorOps.Applied applied = EditorOps.apply(editor, EditorOps.batch(List.of(
                new EditorOp.Duplicate("one"), new EditorOp.Duplicate("two"),
                new EditorOp.Duplicate("one"))));

        assertTrue(applied.ok(), () -> "refused: " + applied.messages());
        assertEquals(5, editor.questIds().size(), "three copies of two originals: " + editor.questIds());
        assertTrue(applied.questId().isEmpty(),
                "a batch names no single quest: a gesture that made three of them has no one id");
        assertEquals(List.of("Duplicated 3 quests"), applied.messages(),
                "and the sentence is the count, which is what the author can check");

        // **The point of the round.** Seventy quests used to be seventy presses of Ctrl+Z.
        assertTrue(editor.undo(), "the whole gesture is one step on the history");
        assertEquals(List.of("one", "two"), editor.questIds(), "and one undo takes all of it back");
        assertFalse(editor.undo(), "there was nothing else on the stack: it was one step, not three");

        // And the disk agrees, which is the half a memory-only assertion would miss: `restore` renames
        // what the batch created out of the way again.
        assertFalse(Files.exists(folder.resolve("one_copy.json")), "the copies are off the disk");
    }

    @Test
    @DisplayName("a batch whose elements delete is one step too, and says how many")
    void aBatchOfDeletes() {
        QuestEditor editor = open();

        EditorOps.Applied applied = EditorOps.apply(editor, EditorOps.batch(
                List.of(new EditorOp.Delete("one"), new EditorOp.Delete("two"))));

        assertTrue(applied.ok(), () -> "refused: " + applied.messages());
        assertEquals(List.of("Deleted 2 quests"), applied.messages());
        assertTrue(editor.undo(), "one step");
        assertEquals(List.of("one", "two"), editor.questIds(), "and one undo puts both back");
        assertTrue(Files.exists(folder.resolve("two.json")), "with the file, renamed back into place");
    }

    @Test
    @DisplayName("a batch that would not all apply is refused whole, and puts the disk back")
    void aBatchIsAllOrNothing() throws IOException {
        QuestEditor editor = open();
        String before = file("one");

        // The second element cannot apply -- there is no quest called "ghost" -- and the honest answer is
        // none of them rather than the first one and a mystery.
        EditorOps.Applied applied = EditorOps.apply(editor, EditorOps.batch(List.of(
                new EditorOp.Duplicate("one"), new EditorOp.Duplicate("ghost"))));

        assertFalse(applied.ok(), "a batch holding an edit that cannot apply is refused");
        assertFalse(applied.messages().isEmpty(), "with a sentence");
        assertEquals(List.of("one", "two"), editor.questIds(), "nothing was duplicated");
        assertEquals(before, file("one"), "the bytes are as they were");
        assertFalse(Files.exists(folder.resolve("one_copy.json")), "and nothing new is on the disk");
        assertFalse(editor.canUndo(), "an edit that did not happen is not an undo step");
        assertFalse(editor.canRedo(), "and it leaves no redo trail either");
    }

    @Test
    @DisplayName("a batch refuses the edits that are not one chapter's, before touching anything")
    void aBatchRefusesTheWrongKinds() throws IOException {
        QuestEditor editor = open();
        String before = file("one");

        for (EditorOp wrong : List.of(new EditorOp.Undo(), new EditorOp.Redo(),
                new EditorOp.SetIndex("bookTitle", new JsonPrimitive("The Orrery Ledger")),
                new EditorOp.Batch(List.of(new EditorOp.Delete("two"))),
                new EditorOp.MoveGroup("getting_started", 0))) {
            EditorOps.Applied applied = EditorOps.apply(editor,
                    EditorOps.batch(List.of(new EditorOp.Duplicate("one"), wrong)));

            assertFalse(applied.ok(), () -> "a batch holding " + wrong + " must be refused");
            assertFalse(applied.messages().isEmpty(), "with a sentence: " + wrong);
        }

        assertFalse(EditorOps.apply(editor, new EditorOp.Batch(List.of())).ok(),
                "an empty batch is a refusal, not a silent success");
        assertEquals(before, file("one"), "and nothing was written while refusing");
        assertEquals(List.of("one", "two"), editor.questIds());
        assertFalse(editor.canUndo(), "no refusal left a step behind");
    }

    @Test
    @DisplayName("a batch crosses the wire, and an element this build cannot read is dropped")
    void aBatchCrossesTheWire() {
        EditorOp.Batch batch = new EditorOp.Batch(List.of(new EditorOp.Duplicate("one"),
                new EditorOp.Delete("two")));
        roundTrip(open(), batch);
        roundTrip(open(), new EditorOp.Batch(List.of()));

        // A newer client's element, and one that is not an object at all: the gesture's readable half
        // still travels, because refusing all of it over one unknown kind would make an older server
        // read "duplicate these seventy" as an error nobody can act on.
        JsonObject wire = EditorOps.write(batch);
        JsonObject unknown = new JsonObject();
        unknown.addProperty("kind", "reticulate");
        wire.getAsJsonArray("ops").add(unknown);
        wire.getAsJsonArray("ops").add(new JsonPrimitive("not an object"));

        assertEquals(batch, EditorOps.read(wire), "the readable half is still the gesture");
    }

    @Test
    @DisplayName("one op stays itself rather than becoming a one-element batch")
    void oneOpIsNotABatch() {
        EditorOp single = new EditorOp.Duplicate("one");

        assertEquals(single, EditorOps.batch(List.of(single)),
                "a single edit keeps the answer it gives: the id it made, which selects the new node");
        assertTrue(EditorOps.batch(List.of(single, new EditorOp.Delete("two"))) instanceof EditorOp.Batch,
                "and two edits are one step");
    }
}
