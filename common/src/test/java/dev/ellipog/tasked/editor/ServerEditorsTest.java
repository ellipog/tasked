package dev.ellipog.tasked.editor;

import com.google.gson.JsonPrimitive;
import dev.ellipog.tasked.quest.MinecraftTestBootstrap;
import dev.ellipog.tasked.quest.QuestLoader;

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
    @DisplayName("a batch applied through the server is one step: one undo takes the whole gesture back")
    void aBatchThroughTheServer() throws IOException {
        // The path a bulk gesture actually travels: one payload, one op, one chapter's editor. What this
        // adds to `EditorOpsTest` is the server's half -- the cached editor is found by the chapter the
        // client named, and its history is the one the batch joins.
        EditorOps.Applied applied = editors.apply("first_steps", EditorOps.batch(List.of(
                new EditorOp.SetField("one", "title", new JsonPrimitive("First")),
                new EditorOp.Move("one", 128, 64))));

        assertTrue(applied.ok(), () -> "refused: " + applied.messages());
        assertTrue(titleOnDisk().contains("\"First\""), "both edits landed, and both are on the disk");

        assertTrue(editors.apply("first_steps", new EditorOp.Undo()).ok(), "and one undo is enough");
        assertTrue(titleOnDisk().contains("\"One\""));
        assertTrue(titleOnDisk().contains("\"x\": 0"), "the whole gesture is back, both edits of it");
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
        // An unknown field, not an unknown type: a missing item became a warning when the id was kept and
        // the row marked, and an unknown type became one when the dispatch learned to decode it to a
        // placeholder. This test needs a fault the loader truly refuses, and a field no type declares is
        // the one that remains an error.
        com.google.gson.JsonObject mystery = new com.google.gson.JsonObject();
        mystery.addProperty("type", "tasked:checkmark");
        mystery.addProperty("title", "Did it");
        mystery.addProperty("splines", 4);

        EditorOps.Applied refused = editors.apply("first_steps",
                new EditorOp.Insert("one", "tasks", 0, mystery));

        assertFalse(refused.ok());
        assertTrue(editors.isOpen("first_steps"), "the chapter is still the one being edited");
        assertEquals(before, titleOnDisk(), "and nothing was written");
    }

    @Test
    @DisplayName("a renamed chapter's editor follows it, history and all")
    void aRenamedChapterKeepsItsHistory() throws IOException {
        // The cache is keyed by chapter id and an editor is bound to a folder, so a rename breaks both
        // halves at once: the key is stale and the path is gone. What must survive is the history -- a
        // re-opened editor with an empty stack would make Ctrl+Z after a rename a key that does nothing,
        // which is exactly the fault this class was written to prevent for ordinary edits.
        assertTrue(editors.apply("first_steps", setTitle("Edited")).ok());

        EditorOps.Applied renamed = editors.apply("first_steps",
                new EditorOp.RenameChapter("first_steps", "renamed_chapter", null));

        assertTrue(renamed.ok(), renamed.messages().toString());
        assertFalse(editors.isOpen("first_steps"), "the editor under the old id is gone");
        assertTrue(editors.isOpen("renamed_chapter"), "one is open at the new id");

        // Undo is last-in-first-out, so the rename itself comes back first: the folder returns to its
        // old name and the cache follows it there -- which is the half that a stale editor would get
        // wrong, writing the next edit into a folder nobody named any more.
        EditorOps.Applied undid = editors.apply("renamed_chapter", new EditorOp.Undo());
        assertTrue(undid.ok(), undid.messages().toString());
        assertTrue(Files.isRegularFile(root.resolve("getting_started").resolve("first_steps")
                .resolve("chapter.json")), "the folder is back where it was");
        assertTrue(editors.isOpen("first_steps"),
                "and the cache is keyed where the folder actually is");

        // And the edit made before the rename is still behind it, at the id it now has.
        assertTrue(editors.apply("first_steps", new EditorOp.Undo()).ok(),
                "the title edit made before the rename is still undoable");
        assertFalse(titleOnDisk().contains("Edited"), titleOnDisk());
    }

    @Test
    @DisplayName("a blank session can create the first chapter of an empty tree")
    void aBlankSessionCreatesTheFirstChapter(@TempDir Path empty) throws IOException {
        // The empty-pack case: no chapter means no editor to open and no history to record on, so the op
        // arrives with an empty session. Creating a chapter is still something to do there, and it is
        // the only way in from the book.
        Path bare = empty.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(bare);
        ServerEditors bareEditors = new ServerEditors(() -> bare);

        EditorOps.Applied applied = bareEditors.apply("",
                new EditorOp.CreateChapter("", 0, "first", "First"));

        assertTrue(applied.ok(), applied.messages().toString());
        assertTrue(Files.isRegularFile(bare.resolve("first/chapter.json")), "the chapter exists");
        assertTrue(Files.isRegularFile(bare.resolve("index.json")),
                "and the root order names it, so the tree loads");
        QuestLoader.Result loaded = QuestLoader.load(empty);
        assertTrue(loaded.ok(), () -> "the created tree has to load cleanly:\n"
                + loaded.problems().all().stream().map(problem -> problem.render())
                        .reduce("", (a, b) -> a + "\n" + b));
        assertTrue(loaded.index().chapter("first").isPresent());
    }

    @Test
    @DisplayName("and a blank session still refuses an edit that needs a chapter")
    void aBlankSessionRefusesFieldEdits(@TempDir Path empty) throws IOException {
        Path bare = empty.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(bare);
        ServerEditors bareEditors = new ServerEditors(() -> bare);

        EditorOps.Applied applied = bareEditors.apply("", setTitle("x"));

        assertFalse(applied.ok());
        assertTrue(applied.messages().toString().contains("chapter"),
                "the refusal names what is missing: " + applied.messages());
    }
}
