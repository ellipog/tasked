package dev.ellipog.tasked.editor;

import com.google.gson.JsonObject;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One file, as the editor's tree: the round trip, the dotted paths, and what it promises about a
 * hand-written file.
 *
 * <h2>The assertion that matters most</h2>
 *
 * <p>{@link #aHandWrittenFileKeepsEverythingTheEditorDoesNotUnderstand()} is the contract the editor
 * exists to keep. The loader throws away what it does not know, so an editor built on the loader's
 * records would drop a field it had no panel for — silently, on the first save, in a file its author may
 * have spent an evening on. The tree is what makes that impossible, and the test is what says so.
 */
@DisplayName("A quest file, as the editor's tree")
class JsonFileTest {

    /** A file with the shape a hand-written one has: inline objects, and a field the editor knows nothing about. */
    private static final String HAND_WRITTEN = """
            {
              "$schema": "../../_schema/quest.schema.json",
              "id": "punch_a_tree",
              "title": "Punch a Tree",
              "x": 0,
              "y": 0,
              "icon": { "item": "minecraft:oak_log" },
              "tasks": [
                { "type": "tasked:item", "item": "minecraft:oak_log", "count": 8 }
              ],
              "authorNote": "counted by hand, do not touch",
              "x-extra": { "anything": [1, 2, 3] }
            }
            """;

    private static JsonFile parse(Path dir, String text) {
        return JsonFile.parse(dir.resolve("punch_a_tree.json"), text);
    }

    @Test
    @DisplayName("a hand-written file keeps everything the editor does not understand")
    void aHandWrittenFileKeepsEverythingTheEditorDoesNotUnderstand() {
        // Deliberately the two shapes a loader-shaped editor loses: a top-level field it has no panel
        // for, and a whole nested object that is not part of the format at all.
        String[] foreign = {"authorNote", "x-extra"};

        JsonFile file = parse(Path.of("."), HAND_WRITTEN);
        file.setNumber("x", 64);
        file.setText("title", "Punch a Tree, Again");
        String written = file.json();

        for (String field : foreign) {
            assertTrue(written.contains(field), () -> field + " was dropped by a save");
        }
        assertTrue(written.contains("counted by hand, do not touch"), "the value survived, not just the key");
        assertTrue(written.contains("\"type\": \"tasked:item\""), "and the task's own fields");
    }

    @Test
    @DisplayName("a new file is dirty, and clean again once it is written")
    void dirtyFollowsTheFile(@TempDir Path dir) throws IOException {
        JsonFile file = parse(dir, HAND_WRITTEN);
        assertFalse(file.dirty(), "a file just read is the file on disk");

        file.setNumber("x", 10);
        assertTrue(file.dirty());

        // And back: setting it to what it already was in text terms leaves the file clean, because
        // dirty() compares the text rather than tracking a flag somebody has to remember to clear.
        file.setNumber("x", 0);
        assertFalse(file.dirty(), "the text is the same, so there is nothing to write");
    }

    @Test
    @DisplayName("writing puts it on disk and marks it saved")
    void writingLands(@TempDir Path dir) throws IOException {
        JsonFile file = parse(dir, HAND_WRITTEN);
        file.setText("subtitle", "with a subtitle");
        file.write();

        String onDisk = Files.readString(file.file(), StandardCharsets.UTF_8);
        assertTrue(onDisk.contains("with a subtitle"));
        assertFalse(file.dirty());
        assertTrue(onDisk.endsWith("\n"), "a text file ends with a newline");
    }

    @Test
    @DisplayName("dotted paths read and create the chain they name")
    void dottedPaths() {
        JsonFile file = parse(Path.of("."), HAND_WRITTEN);

        assertEquals("minecraft:oak_log", file.text("icon.item", "none"));
        assertEquals(1, file.number("icon.count", 1), 0.0001, "a missing number takes the fallback");
        assertEquals(8, file.number("tasks.0.count", -1), 0.0001,
                "an array step reaches a task's own field: the path the panel commits through");
        assertEquals(-1, file.number("tasks[0].count", -1), 0.0001,
                "the bracket form is not this path syntax, so it falls back");
        assertEquals(-1, file.number("tasks.0.nothing", -1), 0.0001, "and so does a field not there");

        file.setText("icon.item", "minecraft:birch_log");
        assertEquals("minecraft:birch_log", file.text("icon.item", "none"));

        // A path whose middle does not exist yet is created, which is what makes `icon.count` editable
        // on a file that has no count.
        file.setNumber("icon.count", 3);
        assertEquals(3, file.number("icon.count", 0), 0.0001);

        file.setText("presentation.frame", "gold");
        assertEquals("gold", file.text("presentation.frame", "none"),
                "a path one level deeper than the format goes creates its own object");

        file.remove("icon.count");
        assertFalse(file.has("icon.count"));
        assertEquals("minecraft:birch_log", file.text("icon.item", "none"),
                "and removing one field leaves its siblings alone");
    }

    @Test
    @DisplayName("a task's own field writes through its array step, and reads back the same way")
    void arrayStepsWrite() {
        JsonFile file = parse(Path.of("."), HAND_WRITTEN);

        file.setNumber("tasks.0.count", 12);
        assertEquals(12, file.number("tasks.0.count", -1), 0.0001);

        file.setText("tasks.0.item", "minecraft:birch_log");
        assertEquals("minecraft:birch_log", file.text("tasks.0.item", "none"));
        assertTrue(file.json().contains("\"count\": 12"), "the task's own object was written, not replaced");
        assertTrue(file.json().contains("\"type\": \"tasked:item\""),
                "and its other fields are still there");

        file.remove("tasks.0.count");
        assertFalse(file.has("tasks.0.count"));
        assertEquals("minecraft:birch_log", file.text("tasks.0.item", "none"),
                "removing one task field leaves its siblings alone");
    }

    @Test
    @DisplayName("a whole number is written whole, and a fraction keeps its point")
    void wholeNumbersAreWrittenWhole() {
        JsonFile file = parse(Path.of("."), HAND_WRITTEN);

        file.setNumber("x", 8.0);
        assertTrue(file.json().contains("\"x\": 8"), "8.0 in a diff where 8 was is noise: " + file.json());
        assertFalse(file.json().contains("\"x\": 8.0"), "a drag writes the number an author would type");

        file.setNumber("iconScale", 1.5);
        assertTrue(file.json().contains("\"iconScale\": 1.5"), "a fraction is not rounded away");
    }

    @Test
    @DisplayName("a path that would replace a container is refused, not clobbered")
    void unwritablePathsRefuse() {
        JsonFile file = parse(Path.of("."), HAND_WRITTEN);

        // The old version replaced a non-object member with a fresh object to walk through -- which for
        // an array is data loss. Every one of these is refused, and the tree is untouched.
        assertThrows(JsonFile.UnwritablePath.class, () -> file.setText("tasks.item", "x"));
        assertThrows(JsonFile.UnwritablePath.class, () -> file.setText("tasks.9.count", "1"));
        assertThrows(JsonFile.UnwritablePath.class, () -> file.setText("title.deep", "x"));
        assertThrows(JsonFile.UnwritablePath.class, () -> file.setText("x.y", "z"));
        assertTrue(file.json().contains("\"type\": \"tasked:item\""),
                "the task array survived every refusal");

        // And a refusal is only for writes: the same paths read as absent, which is what a read of a
        // path that names nothing should be.
        assertNull(file.get("tasks.item"));
        assertNull(file.get("tasks.9.count"));
    }

    @Test
    @DisplayName("a whole JSON value writes over a field or over a list element")
    void wholeValuesWrite() {
        JsonFile file = parse(Path.of("."), HAND_WRITTEN);

        JsonObject icon = new JsonObject();
        icon.addProperty("item", "minecraft:diamond");
        file.setJson("icon", icon);
        assertEquals("minecraft:diamond", file.text("icon.item", "none"),
                "an object field is replaced whole");

        JsonObject task = new JsonObject();
        task.addProperty("type", "tasked:checkmark");
        task.addProperty("title", "Did it");
        file.setJson("tasks.0", task);
        assertEquals("tasked:checkmark", file.text("tasks.0.type", ""),
                "a whole list element is replaced, not a member named \"0\" added to the root");
        assertEquals("Did it", file.text("tasks.0.title", ""));

        assertThrows(JsonFile.UnwritablePath.class, () -> file.setJson("tasks.9", task),
                "an index past the end is refused");
    }

    @Test
    @DisplayName("entries insert, remove and move by position, and the order is the order")
    void entriesMove() {
        JsonFile file = parse(Path.of("."), """
                { "title": "x", "tasks": [ { "type": "a" }, { "type": "b" }, { "type": "c" } ] }
                """);

        JsonObject inserted = new JsonObject();
        inserted.addProperty("type", "new");
        file.insert("tasks", 1, inserted);
        assertEquals("new", file.text("tasks.1.type", ""), "inserted at the index asked for");
        assertEquals("b", file.text("tasks.2.type", ""), "and the tail shifted right");
        assertEquals("c", file.text("tasks.3.type", ""));

        assertTrue(file.moveIndex("tasks", 1, 3));
        assertEquals("new", file.text("tasks.3.type", ""), "moved to the end");
        assertEquals("a", file.text("tasks.0.type", ""), "and everything before it shifted left");

        assertTrue(file.removeIndex("tasks", 0));
        assertEquals("b", file.text("tasks.0.type", ""));
        assertFalse(file.removeIndex("tasks", 9), "removing what is not there says so");
        assertFalse(file.moveIndex("tasks", 0, 9), "and so does moving to nowhere");

        // And a member that is not an array is refused rather than replaced.
        assertThrows(JsonFile.UnwritablePath.class, () -> file.insert("title", 0, inserted));
    }

    @Test
    @DisplayName("a value of the wrong type reads as absent rather than as a surprise")
    void wrongTypesReadAsAbsent() {        JsonFile file = parse(Path.of("."), """
                { "title": { "translate": "quest.title" }, "x": "left", "flag": 3 }
                """);

        assertEquals("fallback", file.text("title", "fallback"),
                "a translatable title is an object, and this accessor is for strings");
        assertEquals(0, file.number("x", 0), "a string where a number belongs");
        assertFalse(file.flag("flag", false), "a number where a boolean belongs");
        assertEquals("fallback", file.text("title", "fallback"), "and nothing threw");
    }

    @Test
    @DisplayName("string arrays read, append, replace and remove")
    void stringArrays() {
        JsonFile file = parse(Path.of("."), """
                { "description": ["one", "two"], "quests": [] }
                """);

        assertEquals(java.util.List.of("one", "two"), file.strings("description"));

        file.addString("quests", "a.json");
        file.addString("quests", "b.json");
        assertEquals(java.util.List.of("a.json", "b.json"), file.strings("quests"));

        assertTrue(file.removeString("quests", "a.json"));
        assertFalse(file.removeString("quests", "a.json"), "removing what is not there says so");
        assertEquals(java.util.List.of("b.json"), file.strings("quests"));

        file.setStrings("description", java.util.List.of("only"));
        assertEquals(java.util.List.of("only"), file.strings("description"));

        assertEquals(java.util.List.of(), file.strings("absent"), "a missing array is empty, not null");
    }

    @Test
    @DisplayName("text that is not a JSON object is refused rather than half-read")
    void malformedIsRefused() {
        Path path = Path.of("broken.json");
        assertThrows(RuntimeException.class, () -> JsonFile.parse(path, "[1, 2, 3]"));
        assertThrows(RuntimeException.class, () -> JsonFile.parse(path, "not json at all"));
        assertThrows(RuntimeException.class, () -> JsonFile.parse(path, "{ \"unterminated\": "));
        assertNotNull(JsonFile.parse(path, "{}"), "an empty object is a legal, if useless, file");
    }

    @Test
    @DisplayName("replaceWith swaps the tree and the text it was read from")
    void replaceWithRestores() {
        JsonFile file = parse(Path.of("."), HAND_WRITTEN);
        String original = file.json();

        file.setNumber("x", 400);
        assertTrue(file.dirty());

        file.replaceWith(original);
        assertEquals(original, file.json());
        // Clean here, and only because this text *is* the file's own: nothing was written in between, so
        // memory and disk agree. The editor-level case, where a save happens between the snapshot and
        // the undo, is the one that must stay dirty -- see `EditorOpsTest.undoing`.
        assertFalse(file.dirty(), "the tree is back to what the file holds, so nothing is pending");
        assertEquals(0, file.number("x", -1), 0.0001);
        assertNull(file.get("nothing.here"));
    }
}
