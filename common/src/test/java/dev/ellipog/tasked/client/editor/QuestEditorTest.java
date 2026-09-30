package dev.ellipog.tasked.client.editor;

import dev.ellipog.tasked.quest.MinecraftTestBootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A chapter open for editing: the files it is made of, the edits, undo, and a save that validates.
 *
 * <h2>Why these are the tests that matter for T9</h2>
 *
 * <p>Everything here touches files a person wrote. The failures that would hurt are all in this class's
 * neighbourhood: a create that adds a name to the manifest without a file, a delete that loses the file,
 * an undo that restores a chapter the disk does not agree with, a save that writes something the loader
 * will refuse on the next reload. None of them need a client to catch, and a client could not catch them
 * cheaply — which is why the model is game-free and the screen is not where this lives.
 *
 * <p>The fixture is a real chapter on disk, built by {@link #chapter()} the way the examples are laid
 * out — folder per chapter, manifest listing file names, one file per quest — because the editor walks
 * the tree with the loader's own discovery and a fixture that is not the real shape would test nothing.
 */
@DisplayName("A chapter, open for editing")
class QuestEditorTest {

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

    @BeforeAll
    static void bootstrapMinecraft() {
        // `save()` runs the loader's own validator, and the validator checks that a named item exists —
        // which reads `BuiltInRegistries.ITEM`, empty until vanilla has registered its items. Without
        // this the save tests would fail on a registry rather than on the editor, and worse, the failed
        // class initialiser poisons every other test in the same JVM. See the helper's own note, which
        // describes this exact message.
        MinecraftTestBootstrap.boot();
    }

    @BeforeEach
    void chapter(@TempDir Path dir) throws IOException {
        root = dir.resolve("quests");
        Path folder = root.resolve("getting_started").resolve("first_steps");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("chapter.json"), MANIFEST, StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("one.json"), ONE, StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("two.json"), TWO, StandardCharsets.UTF_8);

        // A group manifest, because the walk expects one: a chapter is only found under a group.
        Path group = root.resolve("getting_started");
        Files.writeString(group.resolve("group.json"), """
                { "id": "getting_started", "title": "Getting Started", "chapters": [ "first_steps" ] }
                """, StandardCharsets.UTF_8);
    }

    private QuestEditor open() {
        return QuestEditor.open(root, "first_steps").orElseThrow();
    }

    // ------------------------------------------------------------------
    // Opening
    // ------------------------------------------------------------------

    @Test
    @DisplayName("it opens the chapter's quests, in the manifest's order")
    void opensTheChapter() {
        QuestEditor editor = open();

        assertEquals(java.util.List.of("one", "two"), editor.questIds());
        assertNotNull(editor.quest("one"));
        assertEquals("One", editor.quest("one").text("title", ""));
        assertEquals(64, editor.quest("two").number("x", -1), 0.0001);
        assertFalse(editor.dirty(), "an editor that has changed nothing has nothing to save");
        assertFalse(editor.canUndo());
    }

    @Test
    @DisplayName("a chapter that is not there, or a root that is not there, is unavailable rather than new")
    void refusesWhatItCannotOpen(@TempDir Path empty) {
        assertTrue(QuestEditor.open(root, "no_such_chapter").isEmpty());
        assertTrue(QuestEditor.open(empty, "first_steps").isEmpty(), "no files, no editor");
        assertTrue(QuestEditor.open(null, "first_steps").isEmpty());
        assertTrue(QuestEditor.open(root, null).isEmpty());
    }

    // ------------------------------------------------------------------
    // Editing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a field edit lands in the file's own tree and marks the chapter unsaved")
    void editsFields() {
        QuestEditor editor = open();

        assertTrue(editor.set("one", "title", "One, Renamed"));
        assertTrue(editor.set("one", "icon.item", "minecraft:birch_log"));
        assertTrue(editor.set("one", "size", 40));
        assertTrue(editor.set("one", "showTitle", true));
        assertTrue(editor.set("one", "subtitle", null), "null removes the field");

        assertEquals("One, Renamed", editor.quest("one").text("title", ""));
        assertEquals("minecraft:birch_log", editor.quest("one").text("icon.item", ""));
        assertEquals(40, editor.quest("one").number("size", 0), 0.0001);
        assertTrue(editor.quest("one").flag("showTitle", false));
        assertFalse(editor.quest("one").has("subtitle"));
        assertTrue(editor.dirty());
        assertTrue(editor.canUndo());
    }

    @Test
    @DisplayName("an edit to an id the chapter does not hold does nothing, and is not undoable")
    void editsOnlyWhatIsOpen() {
        QuestEditor editor = open();

        assertFalse(editor.set("three", "title", "Three"));
        assertFalse(editor.move("three", 10, 10));
        assertFalse(editor.canUndo(), "a refused edit must not leave an undo entry behind");
        assertFalse(editor.dirty());
    }

    @Test
    @DisplayName("a move sets both coordinates, and one that changes nothing is not an edit")
    void moves() {
        QuestEditor editor = open();

        assertTrue(editor.move("one", 128, -64));
        assertEquals(128, editor.quest("one").number("x", 0), 0.0001);
        assertEquals(-64, editor.quest("one").number("y", 0), 0.0001);

        int before = editor.canUndo() ? 1 : 0;
        assertFalse(editor.move("one", 128, -64), "the same place is not a change");
        assertTrue(before == 1, "so no undo entry was pushed");
    }

    // ------------------------------------------------------------------
    // Creating, duplicating, deleting
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a created quest is a file and a name in the manifest, and it saves clean")
    void creates() {
        QuestEditor editor = open();

        String id = editor.create(96, 32);
        assertEquals("quest", id, "the first free name");
        assertTrue(editor.questIds().contains(id));
        assertTrue(Files.isRegularFile(editor.pathOf(id)), "the file exists before the manifest names it");
        assertEquals(96, editor.quest(id).number("x", 0), 0.0001);
        assertNotNull(editor.quest(id).text("title", null), "a new quest has a title to replace");
        assertTrue(editor.quest(id).has("tasks"), "and an empty task list, which the format requires");

        QuestEditor.SaveResult saved = editor.save();
        assertTrue(saved.ok(), () -> "and what it wrote is loadable: " + saved.messages());

        String second = editor.create(144, 32);
        assertNotEquals(id, second, "a second new quest takes a free name too");
    }

    private static void assertNotEquals(Object a, Object b, String message) {
        org.junit.jupiter.api.Assertions.assertNotEquals(a, b, message);
    }

    @Test
    @DisplayName("a duplicate is a copy beside the original, with its own id and file")
    void duplicates() {
        QuestEditor editor = open();

        String copy = editor.duplicate("two");
        assertNotNull(copy);
        assertEquals(java.util.List.of("one", "two", copy), editor.questIds(),
                "a copy goes at the end of the chapter's list");
        assertEquals(copy, editor.quest(copy).text("id", ""),
                "the copy's id is its own file name, not the one it was copied from");
        assertEquals(112, editor.quest(copy).number("x", 0), 0.0001, "a step to the right of the original");
        assertEquals(java.util.List.of("one"), editor.quest(copy).strings("dependsOn"),
                "a copy depends on what the original did -- the editor does not invent an intent");
        assertTrue(Files.isRegularFile(editor.pathOf(copy)));

        assertNull(editor.duplicate("nothing"), "duplicating what is not open is refused");
    }

    @Test
    @DisplayName("a delete renames the file out of the way and forgets the name")
    void deletes() {
        QuestEditor editor = open();
        Path file = editor.pathOf("two");

        assertTrue(editor.delete("two"));
        assertEquals(java.util.List.of("one"), editor.questIds());
        assertNull(editor.quest("two"));
        assertFalse(Files.exists(file), "the file is gone from the folder");
        assertTrue(Files.exists(file.resolveSibling("two.json.deleted")),
                "and is recoverable: a program that deletes an author's file outright is one mis-click"
                        + " from losing an evening's work");
        assertFalse(editor.delete("two"), "deleting what is not open is refused");
    }

    // ------------------------------------------------------------------
    // Undo
    // ------------------------------------------------------------------

    @Test
    @DisplayName("undo puts an edit back, and the file with it")
    void undoesAnEdit() {
        QuestEditor editor = open();

        editor.move("one", 200, 300);
        assertTrue(editor.undo());
        assertEquals(0, editor.quest("one").number("x", 0), 0.0001);
        assertFalse(editor.canUndo());

        assertTrue(editor.redo());
        assertEquals(200, editor.quest("one").number("x", 0), 0.0001);
    }

    @Test
    @DisplayName("undo puts a deleted quest back, file and all")
    void undoesADelete() throws IOException {
        QuestEditor editor = open();
        String before = Files.readString(editor.pathOf("two"), StandardCharsets.UTF_8);

        editor.delete("two");
        assertFalse(Files.exists(editor.pathOf("two")));

        assertTrue(editor.undo());
        assertEquals(java.util.List.of("one", "two"), editor.questIds());
        assertTrue(Files.isRegularFile(editor.pathOf("two")), "the file is back");
        assertEquals(before, Files.readString(editor.pathOf("two"), StandardCharsets.UTF_8),
                "byte for byte, which is what makes an undo an undo");
    }

    @Test
    @DisplayName("undo takes a created quest away again, file and all")
    void undoesACreate() {
        QuestEditor editor = open();
        String id = editor.create(10, 10);
        Path file = editor.pathOf(id);

        assertTrue(editor.undo());
        assertFalse(editor.questIds().contains(id));
        assertFalse(Files.exists(file), "and the file it wrote is out of the way");
    }

    @Test
    @DisplayName("a new edit forgets the redo trail")
    void redoTrailIsCleared() {
        QuestEditor editor = open();

        editor.move("one", 10, 10);
        editor.undo();
        assertTrue(editor.canRedo());

        editor.move("two", 20, 20);
        assertFalse(editor.canRedo(), "there is no future to redo into any more");
        assertTrue(editor.canUndo());
    }

    // ------------------------------------------------------------------
    // Saving
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a save writes every changed file and leaves nothing dirty")
    void saves() throws IOException {
        QuestEditor editor = open();

        editor.move("one", 32, 32);
        editor.set("two", "title", "Two, Renamed");
        QuestEditor.SaveResult result = editor.save();

        assertTrue(result.ok(), () -> "refused: " + result.messages());
        assertEquals(2, result.written());
        assertFalse(editor.dirty());

        assertTrue(Files.readString(editor.pathOf("one"), StandardCharsets.UTF_8).contains("32"));
        assertTrue(Files.readString(editor.pathOf("two"), StandardCharsets.UTF_8)
                .contains("Two, Renamed"));
    }

    @Test
    @DisplayName("a save that would write something unloadable writes nothing at all")
    void aBadEditIsRefusedWhole() throws IOException {
        QuestEditor editor = open();
        String oneBefore = Files.readString(editor.pathOf("one"), StandardCharsets.UTF_8);

        // An icon that is not an item id: the validator knows the format, and this is the smallest edit
        // that breaks it without breaking JSON.
        editor.set("one", "icon.item", "not an item id");
        editor.set("two", "title", "this one is fine");

        QuestEditor.SaveResult result = editor.save();

        assertFalse(result.ok(), "the save is refused");
        assertEquals(0, result.written());
        assertTrue(result.messages().stream().anyMatch(message -> message.contains("icon")),
                () -> "the message names the field: " + result.messages());
        assertEquals(oneBefore, Files.readString(editor.pathOf("one"), StandardCharsets.UTF_8),
                "and not one byte of the chapter was written");
        assertTrue(editor.dirty(), "the edits are still there to fix");
    }
}
