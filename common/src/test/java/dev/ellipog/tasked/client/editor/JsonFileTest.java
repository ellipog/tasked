package dev.ellipog.tasked.client.editor;

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
        assertEquals(-1, file.number("tasks[0].count", -1), 0.0001,
                "an array is not addressable by path, so that falls back too");

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
    @DisplayName("a value of the wrong type reads as absent rather than as a surprise")
    void wrongTypesReadAsAbsent() {
        JsonFile file = parse(Path.of("."), """
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
        assertFalse(file.dirty(), "a restored state is a written state, not an unsaved one");
        assertEquals(0, file.number("x", -1), 0.0001);
        assertNull(file.get("nothing.here"));
    }
}
