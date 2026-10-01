package dev.ellipog.tasked.editor;

import com.google.gson.JsonPrimitive;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The server's open chapters: opened on demand, kept, and dropped by a reload.
 *
 * <h2>The property this class exists for</h2>
 *
 * <p>A history that survives between operations. An editor opened per request would undo nothing — every op
 * would arrive at a fresh model with an empty stack — and the fault would look like Ctrl+Z being broken
 * rather than like the server having forgotten anything, which is why it is asserted here rather than assumed:
 * two edits, then two undos, and the value is back to what the author started with.
 *
 * <p>The second property is the opposite one: a reload <i>may</i> have changed the files underneath, so the
 * cached chapters go and the next op reads them again. Both are about the same question — whether what the
 * server is holding is still what is on the disk — and they are the two answers it can have.
 */
@DisplayName("The server's open chapters")
class ServerEditorsTest {

    private static final String MANIFEST = """
            {
              "$schema": "../../_schema/chapter.schema.json",
              "id": "first_steps",
              "title": "First Steps",
              "quests": [ "one.json" ]
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

    private Path root;
    private ServerEditors editors;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.boot();
    }

    @BeforeEach
    void chapter(@TempDir Path dir) throws IOException {
        root = dir.resolve("quests");
        Path folder = root.resolve("getting_started").resolve("first_steps");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("chapter.json"), MANIFEST, StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("one.json"), ONE, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("getting_started").resolve("group.json"), """
                { "id": "getting_started", "title": "Getting Started", "chapters": [ "first_steps" ] }
                """, StandardCharsets.UTF_8);

        editors = new ServerEditors(() -> root);
    }

    private static EditorOp setTitle(String title) {
        return new EditorOp.SetField("one", "title", new JsonPrimitive(title));
    }

    private String titleOnDisk() throws IOException {
        return Files.readString(root.resolve("getting_started").resolve("first_steps").resolve("one.json"),
                StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a chapter opens on the first op about it, and the history carries on across ops")
    void theHistorySurvives() throws IOException {
        assertFalse(editors.isOpen("first_steps"), "nothing is open until something is edited");

        assertTrue(editors.apply("first_steps", setTitle("Second")).ok());
        assertTrue(editors.isOpen("first_steps"));
        assertTrue(editors.apply("first_steps", new EditorOp.Move("one", 128, 64)).ok());

        // The two undos are the assertion: the first takes back the move, the second the rename, which can
        // only happen if both ops were applied to the *same* instance.
        assertTrue(editors.apply("first_steps", new EditorOp.Undo()).ok());
        assertTrue(editors.apply("first_steps", new EditorOp.Undo()).ok());

        assertTrue(titleOnDisk().contains("\"One\""), "the file is back to what the author started with");
        assertTrue(titleOnDisk().contains("\"x\": 0"), "and so is the position");
    }

    @Test
    @DisplayName("a reload drops the open chapters, so the next op reads the files again")
    void forgetStartsAgain() {
        assertTrue(editors.apply("first_steps", setTitle("Second")).ok());

        editors.forget();

        assertFalse(editors.isOpen("first_steps"));
        // Nothing to undo, because the model that remembered the edit is gone: an undo across a reload would
        // be the server contradicting files it has not read.
        EditorOps.Applied undone = editors.apply("first_steps", new EditorOp.Undo());
        assertFalse(undone.ok());
        assertFalse(undone.messages().isEmpty(), "and it says why rather than doing nothing quietly");
    }

    @Test
    @DisplayName("a replica carries each quest's own tree, opened by the read itself")
    void theReplicaIsTheFiles() {
        var all = editors.replica("first_steps");

        assertFalse(all == null || all.isEmpty());
        assertEquals(1, all.size(), "one quest in this fixture");
        var one = all.getAsJsonObject("one");
        assertEquals("One", one.get("title").getAsString());
        assertEquals("minecraft:oak_log", one.getAsJsonObject("icon").get("item").getAsString());
        assertTrue(editors.isOpen("first_steps"), "a read opens the chapter it read");

        assertNull(editors.replica("nowhere"));
        assertNull(editors.replica(null));
    }

    @Test
    @DisplayName("a chapter that is not there, or no chapter at all, is a refusal with a sentence")
    void refusals() {
        EditorOps.Applied missing = editors.apply("nowhere", setTitle("Second"));
        assertFalse(missing.ok());
        assertEquals(1, missing.messages().size());
        assertFalse(editors.isOpen("nowhere"), "a refusal does not open anything");

        assertFalse(editors.apply(null, setTitle("Second")).ok());
        assertFalse(editors.apply("  ", setTitle("Second")).ok());
    }

    @Test
    @DisplayName("an edit the validator refuses leaves the chapter open and the disk untouched")
    void aRefusalKeepsTheChapter() throws IOException {
        String before = titleOnDisk();

        EditorOps.Applied refused = editors.apply("first_steps", new EditorOp.SetField("one", "icon.item",
                new JsonPrimitive("minecraft:not_a_real_item")));

        assertFalse(refused.ok());
        assertTrue(editors.isOpen("first_steps"), "the chapter is still the one being edited");
        assertEquals(before, titleOnDisk(), "and nothing was written");
    }
}
