package dev.ellipog.tenet.editor;

import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestFiles;
import dev.ellipog.tenet.quest.QuestLoader;
import dev.ellipog.tenet.quest.QuestSettings;
import dev.ellipog.tenet.quest.reward.RewardAutoClaim;
import org.junit.jupiter.api.BeforeAll;
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

/**
 * The structural edits, against a real directory.
 *
 * <h2>What is being asserted, and why it is the files</h2>
 *
 * <p>A move, a rename and a delete are facts about folders, so the assertions are folder questions:
 * where the chapter is now, what the two group manifests say, whether the index was created or left
 * alone. The failure this class exists to prevent is the one that has no in-game symptom until much
 * later — a manifest that still names a chapter nobody will load, or an index written on an edit that
 * did not need one, reordering a book that was never touched.
 *
 * <h2>And that undo is the same key</h2>
 *
 * <p>The structural edits join {@link QuestEditor}'s history, so one test here drives them through
 * {@link EditorOps} and presses the editor's own undo: delete, undo, and the chapter is back with its
 * group listing it again. That is the property the user asked for and the one that is easiest to lose
 * when the two halves live in different classes.
 */
@DisplayName("QuestStructure")
class QuestStructureTest {

    @BeforeAll
    static void bootVanilla() {
        // The validator checks that every item id a document names exists in BuiltInRegistries.ITEM, and
        // an unbootstrapped registry throws from inside a vanilla class initialiser rather than
        // reporting anything missing. So vanilla runs first, exactly as it would on a server.
        MinecraftTestBootstrap.boot();
    }

    @TempDir
    Path temp;

    private Path root() {
        return temp.resolve(QuestLoader.DIRECTORY);
    }

    private void write(String relative, String json) throws IOException {
        Path file = root().resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    private static String group(String id, String chapters) {
        return """
                { "$schema": "../_schema/group.schema.json",
                  "id": "%s", "title": "%s", "chapters": %s }
                """.formatted(id, id, chapters);
    }

    private static String chapter(String id, String quests) {
        return """
                { "$schema": "../../_schema/chapter.schema.json",
                  "id": "%s", "title": "%s", "quests": %s }
                """.formatted(id, id, quests);
    }

    private static String quest(String id, String extra) {
        return """
                { "$schema": "../../../_schema/quest.schema.json",
                  "id": "%s", "title": "%s"%s }
                """.formatted(id, id, extra);
    }

    /** Two groups: {@code alpha} holding one chapter with one quest, {@code beta} holding an empty one. */
    private void tree() throws IOException {
        write("alpha/group.json", group("alpha", "[\"one\"]"));
        write("alpha/one/chapter.json", chapter("one", "[\"first.json\"]"));
        write("alpha/one/first.json", quest("first", ""));
        write("beta/group.json", group("beta", "[\"two\"]"));
        write("beta/two/chapter.json", chapter("two", "[]"));
    }

    private String groupChapters(String group) throws IOException {
        return Files.readString(root().resolve(group).resolve(QuestFiles.GROUP_MANIFEST),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");
    }

    private String indexText() throws IOException {
        return Files.readString(root().resolve(QuestFiles.INDEX_MANIFEST), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");
    }

    // ------------------------------------------------------------------
    // Moving
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a chapter moved to another group is listed by neither the old manifest nor a stale path")
    void movingBetweenGroupsRewritesBothManifests() throws IOException {
        tree();

        QuestStructure.Outcome outcome = QuestStructure.moveChapter(root(), "one", "beta", 0);

        assertTrue(outcome.ok(), outcome.refusal());
        assertTrue(Files.isRegularFile(root().resolve("beta/one/chapter.json")),
                "the chapter folder is in the group it moved to");
        assertFalse(Files.exists(root().resolve("alpha/one")), "and no longer where it was");
        assertTrue(groupChapters("alpha").contains("\"chapters\": []"),
                "the old group's list drops it: " + groupChapters("alpha"));
        assertTrue(groupChapters("beta").contains("\"chapters\": [ \"one\", \"two\" ]")
                        || groupChapters("beta").contains("\"chapters\": [\"one\",\"two\"]"),
                "the new group's list takes it at the position it was dropped: " + groupChapters("beta"));
        assertFalse(Files.exists(root().resolve(QuestFiles.INDEX_MANIFEST)),
                "no index is written for a move between two groups, which needs none");
    }

    @Test
    @DisplayName("a chapter moved to no group roots it, and bootstrap-writes the index")
    void movingToNoGroupBootstrapsTheIndex() throws IOException {
        tree();
        // The tree has no index.json -- every pack written before this feature is in that state. Moving
        // a chapter to the root is the first edit that needs one, so the edit writes it.
        QuestStructure.Outcome outcome = QuestStructure.moveChapter(root(), "one", "", 0);

        assertTrue(outcome.ok(), outcome.refusal());
        assertTrue(Files.isRegularFile(root().resolve("one/chapter.json")), "the chapter is at the root");
        assertFalse(Files.exists(root().resolve("alpha/one")));
        assertEquals(true, Files.exists(root().resolve(QuestFiles.INDEX_MANIFEST)),
                "the root order has to be declared now, so the index exists");
        assertTrue(indexText().contains("\"chapter\": \"one\""), indexText());
    }

    @Test
    @DisplayName("a structural edit keeps what it does not own -- the settings block survives")
    void structuralEditsPreserveTheSettingsBlock() throws IOException {
        tree();
        // An author's index: the tree's own settings beside the entry order the editor owns. The first
        // version of the index writer rebuilt the file from `entries` alone and silently deleted the
        // block on the first move, which is a data-loss bug with no symptom until a payout behaves
        // differently and nobody can say why.
        write(QuestFiles.INDEX_MANIFEST, """
                {
                  "$schema": "../_schema/index.schema.json",
                  "settings": { "defaultAutoClaim": "enabled", "defaultTeamReward": true },
                  "entries": [ { "group": "alpha" }, { "group": "beta" } ]
                }
                """);

        QuestStructure.Outcome outcome = QuestStructure.moveGroup(root(), "beta", 0);

        assertTrue(outcome.ok(), outcome.refusal());
        String index = indexText();
        assertTrue(index.contains("\"defaultAutoClaim\""),
                "the settings block is not the editor's to delete: " + index);
        assertTrue(index.contains("\"defaultTeamReward\": true"), index);
        assertEquals(RewardAutoClaim.ENABLED, QuestSettings.load(root()).defaultAutoClaim(),
                "and the loader reads the same settings back after the edit");
        assertTrue(index.contains("\"group\": \"beta\""), "the edit still happened: " + index);
    }

    @Test
    @DisplayName("the first group reorder writes the order the tree already had, with one change")
    void groupOrderBootstrapsFromWhatWasThere() throws IOException {
        tree();

        QuestStructure.Outcome outcome = QuestStructure.moveGroup(root(), "beta", 0);

        assertTrue(outcome.ok(), outcome.refusal());
        String index = indexText();
        assertTrue(index.indexOf("\"group\": \"beta\"") < index.indexOf("\"group\": \"alpha\""),
                "beta was moved to the front: " + index);
        // And the loader reads it in that order, which is the only assertion that matters.
        List<String> groups = QuestFiles.discover(root()).of(QuestFiles.Kind.GROUP).stream()
                .map(QuestFiles.Declaration::id).toList();
        assertEquals(List.of("beta", "alpha"), groups);
    }

    @Test
    @DisplayName("an entries list that is not a list is read as absent, so an edit cannot write the order away")
    void anUnusableEntriesListBootstrapsFromWhatWasThere() throws IOException {
        tree();
        // A hand-edit that leaves valid JSON whose `entries` is not a list -- a file the loader reports
        // and reads as if it were not there. The editor used to read it as an *empty* entry list, and a
        // structural write writes what it read: the first drag replaced the root order with nothing at
        // all, silently, and every later load would read the book in folder-name order. The disk is the
        // only remaining record of the order, so the disk is what answers, exactly as it does when the
        // file is not there at all.
        write(QuestFiles.INDEX_MANIFEST, "{ \"entries\": {} }");

        QuestStructure.Outcome outcome = QuestStructure.moveGroup(root(), "beta", 0);

        assertTrue(outcome.ok(), outcome.refusal());
        String index = indexText();
        assertTrue(index.contains("\"group\": \"alpha\""),
                "the group the unusable list did not name is still declared: " + index);
        assertTrue(index.indexOf("\"group\": \"beta\"") < index.indexOf("\"group\": \"alpha\""),
                "and the move happened: " + index);
        List<String> groups = QuestFiles.discover(root()).of(QuestFiles.Kind.GROUP).stream()
                .map(QuestFiles.Declaration::id).toList();
        assertEquals(List.of("beta", "alpha"), groups,
                "the loader reads the order the edit wrote");
    }

    @Test
    @DisplayName("a root chapter moved into a group leaves no dangling index entry")
    void movingARootChapterIntoAGroupClearsTheIndex() throws IOException {
        // The root chapter was listed in index.json; moving it into a group moves its folder, and the
        // entry that named it at the root is then a name with nothing behind it. Missing that removal is
        // not cosmetic: the loader refuses a declaration that does not resolve, so the reload after the
        // drop reports an error and the move reads as broken -- while the same drag between two groups,
        // which never touches the index, works.
        write("group/group.json", group("group", "[]"));
        write("loose/chapter.json", chapter("loose", "[]"));
        write("index.json", """
                { "$schema": "./_schema/index.schema.json",
                  "entries": [ { "group": "group" }, { "chapter": "loose" } ] }
                """);

        QuestStructure.Outcome outcome = QuestStructure.moveChapter(root(), "loose", "group", 0);

        assertTrue(outcome.ok(), outcome.refusal());
        assertTrue(Files.isRegularFile(root().resolve("group/loose/chapter.json")));
        assertFalse(indexText().contains("\"chapter\": \"loose\""),
                "the index still names it at the root: " + indexText());
        QuestLoader.Result loaded = QuestLoader.load(temp);
        assertTrue(loaded.ok(), "the tree has to load cleanly after the drop:\n"
                + loaded.problems().all().stream().map(problem -> problem.render())
                        .reduce("", (a, b) -> a + "\n" + b));
        assertEquals("group", loaded.index().chapter("loose").orElseThrow().groupId());
    }

    @Test
    @DisplayName("a stale root entry does not survive the next edit that writes the index")
    void aStaleIndexEntryIsHealedByAWrite() throws IOException {
        // What the first version of the move left behind: `{"chapter":"gone"}` at the root with the
        // chapter's folder inside a group. The loader reports it on every load; here the editor is the
        // one that knows, and the next write to the index drops the line instead of carrying it.
        write("group/group.json", group("group", "[\"moved\"]"));
        write("group/moved/chapter.json", chapter("moved", "[]"));
        write("index.json", """
                { "$schema": "./_schema/index.schema.json",
                  "entries": [ { "group": "group" }, { "chapter": "gone" } ] }
                """);

        QuestStructure.Outcome outcome = QuestStructure.moveChapter(root(), "moved", "", 0);

        assertTrue(outcome.ok(), outcome.refusal());
        assertFalse(indexText().contains("\"gone\""),
                "the entry names a chapter the root has not got, and the write has to say what is true: "
                        + indexText());
        assertTrue(indexText().contains("\"moved\""), indexText());
        QuestLoader.Result loaded = QuestLoader.load(temp);
        assertTrue(loaded.ok(), () -> "and the tree loads cleanly after it:\n"
                + loaded.problems().all().stream().map(problem -> problem.render())
                        .reduce("", (a, b) -> a + "\n" + b));
    }

    @Test
    @DisplayName("a name already listed is placed, not listed twice")
    void placingAnEntryDoesNotDuplicateIt() throws IOException {
        // Two root chapters, and the second is asked to move to the top: without the removeIf in
        // `insertEntry` the list would hold it twice, which the loader refuses on the next load.
        write("one/chapter.json", chapter("one", "[]"));
        write("two/chapter.json", chapter("two", "[]"));
        write("index.json", """
                { "$schema": "./_schema/index.schema.json",
                  "entries": [ { "chapter": "one" }, { "chapter": "two" } ] }
                """);

        QuestStructure.Outcome outcome = QuestStructure.moveChapter(root(), "two", "", 0);

        assertTrue(outcome.ok(), outcome.refusal());
        String index = indexText();
        assertEquals(1, index.split("\"two\"", -1).length - 1, "listed exactly once: " + index);
        int at = index.indexOf("\"two\"");
        assertTrue(at < index.indexOf("\"one\""), "and where the drop put it: " + index);
    }

    // ------------------------------------------------------------------
    // Creating
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a new chapter is a real, empty chapter under the group it was asked for")
    void creatingAChapter() throws IOException {
        tree();

        QuestStructure.Outcome outcome = QuestStructure.createChapter(root(), "beta", 0, "three", "Three");

        assertTrue(outcome.ok(), outcome.refusal());
        String manifest = Files.readString(root().resolve("beta/three/chapter.json"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        assertTrue(manifest.contains("\"id\":\"three\""), manifest);
        assertTrue(manifest.contains("\"quests\":[]"),
                "the new chapter is empty, which is the state it exists to make possible: " + manifest);
        assertTrue(groupChapters("beta").contains("three"), groupChapters("beta"));
        // And the loader accepts it, which is the whole point of validating before writing.
        assertTrue(QuestLoader.load(temp).index().chapter("three").isPresent(),
                "the new chapter is in the index");
    }

    @Test
    @DisplayName("an id the loader could never read back is refused before anything is written")
    void refusingImpossibleIds() throws IOException {
        tree();

        QuestStructure.Outcome outcome = QuestStructure.createGroup(root(), "_hidden", "Hidden");

        assertFalse(outcome.ok());
        assertTrue(outcome.refusal().contains("_"), outcome.refusal());
        assertFalse(Files.exists(root().resolve("_hidden")), "nothing was created");
        assertFalse(Files.exists(root().resolve(QuestFiles.INDEX_MANIFEST)), "and nothing was written");
    }

    @Test
    @DisplayName("a group id that is a path is refused before anything is resolved")
    void aGroupIdThatIsAPathIsRefused() throws IOException {
        // The id arrives from a payload and is joined to the root to make a path: to find the group's
        // manifest and, for the edits that write one, to write beside it. A name that is really a path
        // resolved outside the quest tree, and the only thing stopping it doing harm was that a
        // `group.json` has to exist at the traversed place. The rule is asked first now, as the table
        // side's own is.
        tree();

        QuestStructure.Outcome created = QuestStructure.createChapter(root(), "../..", 0, "stray", null);
        assertFalse(created.ok(), "a group is named by a bare folder name");
        assertTrue(created.refusal().contains("only a-z"), created.refusal());
        assertFalse(Files.exists(root().getParent().resolve("stray")), "and nothing was made outside");

        QuestStructure.Outcome moved = QuestStructure.moveChapter(root(), "one", "../..", 0);
        assertFalse(moved.ok(), "the same rule on the way in as on the way out");
        assertTrue(Files.isRegularFile(root().resolve("alpha/one/chapter.json")), "and nothing moved");
    }

    // ------------------------------------------------------------------
    // Renaming
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a rename moves the folder, changes the id and keeps the old one as an alias")
    void renamingAChapter() throws IOException {
        tree();

        QuestStructure.Outcome outcome = QuestStructure.renameChapter(root(), "one", "uno", "Uno");

        assertTrue(outcome.ok(), outcome.refusal());
        assertTrue(Files.isRegularFile(root().resolve("alpha/uno/chapter.json")));
        assertFalse(Files.exists(root().resolve("alpha/one")));
        String manifest = Files.readString(root().resolve("alpha/uno/chapter.json"), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("\"id\": \"uno\""), manifest);
        assertTrue(manifest.contains("aliases"), "the old id is kept so a reference to it still resolves: "
                + manifest);
        assertTrue(manifest.contains("\"one\""), manifest);
        assertTrue(groupChapters("alpha").contains("uno"), groupChapters("alpha"));
    }

    @Test
    @DisplayName("a chapter may not be made under a name another chapter used to have")
    void aCreateRefusesAnotherChaptersAlias() throws IOException {
        // A rename keeps the old id as an alias, and the loader claims ids and aliases in one namespace: an
        // id that is another chapter's alias is a duplicate, and the later entry is not loaded at all. The
        // check was against folder names, so the editor accepted this -- from the UI, in two steps -- and
        // the loader dropped the new chapter.
        tree();
        assertTrue(QuestStructure.renameChapter(root(), "two", "delta", null).ok());

        QuestStructure.Outcome refused = QuestStructure.createChapter(root(), "beta", 0, "two", "Two");

        assertFalse(refused.ok(), "the old name is still claimed");
        assertTrue(refused.refusal().contains("old name"), refused.refusal());
        assertFalse(Files.exists(root().resolve("beta/two")), "so nothing was created");
    }

    @Test
    @DisplayName("renaming a chapter back to a name it used to have is allowed")
    void renamingBackToAFormerIdIsAllowed() throws IOException {
        // The other half, and the reason the check needs to know which entry is being renamed: a rename back
        // is the ordinary way to undo a rename, and the name it is going back to is its own alias.
        tree();
        assertTrue(QuestStructure.renameChapter(root(), "two", "delta", null).ok());

        QuestStructure.Outcome back = QuestStructure.renameChapter(root(), "delta", "two", null);

        assertTrue(back.ok(), "renaming back is not a collision with itself: " + back.refusal());
        assertTrue(Files.isRegularFile(root().resolve("beta/two/chapter.json")), "the folder is back");
        assertFalse(Files.exists(root().resolve("beta/delta")), "and the one it had is gone");
    }

    // ------------------------------------------------------------------
    // Duplicating
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a duplicated chapter re-ids its quests and rewires their sibling dependencies")
    void duplicatingAChapter() throws IOException {
        write("alpha/group.json", group("alpha", "[\"one\"]"));
        write("alpha/one/chapter.json", chapter("one", "[\"first.json\", \"second.json\"]"));
        write("alpha/one/first.json", quest("first", ""));
        write("alpha/one/second.json", quest("second", ", \"dependsOn\": [\"first\"]"));

        QuestEditor editor = QuestEditor.open(root(), "one").orElseThrow();
        EditorOps.Applied outcome = EditorOps.apply(editor,
                new EditorOp.DuplicateChapter("one", "one_copy", "One Copy"));

        assertTrue(outcome.ok(), outcome.messages().toString());
        assertTrue(Files.isRegularFile(root().resolve("alpha/one_copy/chapter.json")));
        String copy = Files.readString(root().resolve("alpha/one_copy/second_copy.json"),
                StandardCharsets.UTF_8);
        assertTrue(copy.contains("\"id\": \"second_copy\""), copy);
        assertTrue(copy.contains("first_copy"),
                "the copy's dependency points at the copy, not at the original: " + copy);
        // The originals are untouched, which is the other half of "duplicate".
        String original = Files.readString(root().resolve("alpha/one/second.json"), StandardCharsets.UTF_8);
        assertTrue(original.contains("\"first\""), original);
        assertFalse(original.contains("first_copy"), original);

        // And the whole duplicate is one undo: the copy folder goes, the original stays.
        assertTrue(editor.undo(), "the duplicate is on the history");
        assertFalse(Files.exists(root().resolve("alpha/one_copy")),
                "undoing a duplicate takes the whole copy, not just its files");
        assertTrue(Files.isRegularFile(root().resolve("alpha/one/chapter.json")), "the original stays");
    }

    @Test
    @DisplayName("a duplicated chapter's links follow their quests through the copy")
    void duplicatingAChapterRemapsItsLinks() throws IOException {
        // A link is standing in for a quest, so a target inside the copy is the copy: a duplicated
        // chapter whose markers mirror the originals while its own quests diverge would rot silently,
        // with nothing anywhere saying which node a press opens. A target outside the copy has no
        // re-id and is left alone, which is the only correct reading of it.
        write("alpha/group.json", group("alpha", "[\"one\"]"));
        write("alpha/one/chapter.json", """
                { "$schema": "../../_schema/chapter.schema.json",
                  "id": "one", "title": "one", "quests": ["first.json", "second.json"],
                  "links": [ { "id": "inward", "quest": "first", "x": 0, "y": 0 },
                             { "id": "outward", "quest": "elsewhere", "x": 64, "y": 0 } ] }
                """);
        write("alpha/one/first.json", quest("first", ""));
        write("alpha/one/second.json", quest("second", ""));

        QuestEditor editor = QuestEditor.open(root(), "one").orElseThrow();
        EditorOps.Applied outcome = EditorOps.apply(editor,
                new EditorOp.DuplicateChapter("one", "one_copy", "One Copy"));

        assertTrue(outcome.ok(), outcome.messages().toString());
        String manifest = Files.readString(root().resolve("alpha/one_copy/chapter.json"),
                StandardCharsets.UTF_8);
        assertTrue(manifest.contains("\"quest\": \"first_copy\""),
                "the inward link follows its quest: " + manifest);
        assertTrue(manifest.contains("\"quest\": \"elsewhere\""),
                "the outward link still points where it always did: " + manifest);
        assertTrue(manifest.contains("\"id\": \"inward\"") && manifest.contains("\"id\": \"outward\""),
                "and the links keep their own ids, like elements do: " + manifest);
    }

    @Test
    @DisplayName("two files declaring one id are copied as two, and the manifest matches the files")    void duplicatingAChapterWithTwoQuestsOfOneIdCopiesBoth() throws IOException {
        // The format allows two files to declare one id -- the load reports it and drops the second at the
        // index -- and the copy's fresh ids were re-derived by matching the source id: both matched the first
        // entry, so one copy overwrote the other while the manifest listed a file that was never written.
        write("alpha/group.json", group("alpha", "[\"one\"]"));
        write("alpha/one/chapter.json", chapter("one", "[\"first.json\", \"second.json\"]"));
        write("alpha/one/first.json", quest("twin", ""));
        write("alpha/one/second.json", quest("twin", ", \"dependsOn\": [\"twin\"]"));

        QuestEditor editor = QuestEditor.open(root(), "one").orElseThrow();
        EditorOps.Applied outcome = EditorOps.apply(editor,
                new EditorOp.DuplicateChapter("one", "one_copy", "One Copy"));

        assertTrue(outcome.ok(), outcome.messages().toString());
        String manifest = Files.readString(root().resolve("alpha/one_copy/chapter.json"),
                StandardCharsets.UTF_8);
        assertTrue(manifest.contains("twin_copy.json"), manifest);
        assertTrue(manifest.contains("twin_copy2.json"), manifest);
        assertTrue(Files.isRegularFile(root().resolve("alpha/one_copy/twin_copy.json")),
                "both files the manifest names are there");
        assertTrue(Files.isRegularFile(root().resolve("alpha/one_copy/twin_copy2.json")));
        String second = Files.readString(root().resolve("alpha/one_copy/twin_copy2.json"),
                StandardCharsets.UTF_8);
        assertTrue(second.contains("\"twin_copy\""),
                "a reference to the shared id resolves to the first claim, as the loader resolves it: "
                        + second);
    }

    @Test
    @DisplayName("a duplicated group's quests wait on the copies, across chapters")
    void duplicatingAGroupRemapsItsCrossChapterReferences() throws IOException {
        // **The copy that was not self-contained.** A quest in the copied chapter 2 depending on one in the
        // copied chapter 1 is a dependency between the quests being copied, and the remap was built per
        // chapter -- so the copy went on waiting on the original, and two roads followed the other pack's
        // chapters instead of one self-contained copy.
        write("alpha/group.json", group("alpha", "[\"one\", \"two\"]"));
        write("alpha/one/chapter.json", chapter("one", "[\"first.json\"]"));
        write("alpha/one/first.json", quest("first", ""));
        write("alpha/two/chapter.json", chapter("two", "[\"second.json\"]"));
        write("alpha/two/second.json", quest("second", ", \"dependsOn\": [\"first\"]"));

        QuestEditor editor = QuestEditor.open(root(), "one").orElseThrow();
        EditorOps.Applied outcome = EditorOps.apply(editor,
                new EditorOp.DuplicateGroup("alpha", "alpha_copy", "Alpha Copy"));

        assertTrue(outcome.ok(), outcome.messages().toString());
        String copied = Files.readString(root().resolve("alpha_copy/two_copy/second_copy.json"),
                StandardCharsets.UTF_8);
        assertTrue(copied.contains("\"first_copy\""),
                "the copy waits on the copy in the other chapter: " + copied);
        assertFalse(copied.contains("\"first\""),
                "and not on the original, which is what self-contained means: " + copied);
        String original = Files.readString(root().resolve("alpha/two/second.json"), StandardCharsets.UTF_8);
        assertTrue(original.contains("\"first\""), original);
    }

    // ------------------------------------------------------------------
    // Deleting, and the same Ctrl+Z
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a delete puts the folder aside, and the editor's own undo puts it back")
    void deletingAChapterIsRecoverableAndUndoable() throws IOException {
        tree();
        QuestEditor editor = QuestEditor.open(root(), "one").orElseThrow();

        EditorOps.Applied applied = EditorOps.apply(editor, new EditorOp.DeleteChapter("one"));

        assertTrue(applied.ok(), applied.messages().toString());
        assertFalse(Files.exists(root().resolve("alpha/one")), "the chapter is out of the tree");
        assertTrue(Files.isRegularFile(root().resolve("alpha/one.deleted/chapter.json")),
                "and its whole folder is beside it, under the deleted suffix");
        assertTrue(groupChapters("alpha").contains("\"chapters\": []"), groupChapters("alpha"));

        // One Ctrl+Z, the same key that undoes a field edit.
        assertTrue(editor.undo(), "the delete is on the editor's history");
        assertTrue(Files.isRegularFile(root().resolve("alpha/one/first.json")),
                "undo brought the chapter back with its quests");
        assertFalse(Files.exists(root().resolve("alpha/one.deleted")), "and took the aside copy away");
        assertTrue(groupChapters("alpha").contains("one"), groupChapters("alpha"));

        // And forward again, so a redo lands where the delete did.
        assertTrue(editor.redo());
        assertFalse(Files.exists(root().resolve("alpha/one")));
    }

    @Test
    @DisplayName("a numbered aside is never declared as a root entry, however the index is written")
    void aNumberedAsideIsNotWrittenIntoTheIndex() throws IOException {
        // **The failure this hid.** `aside()` numbers a second set-aside rather than overwrite the first —
        // `one.deleted`, then `one.deleted.2` — and this mod's own undo writes the numbered one: create a
        // chapter, undo it, create it again, undo it again. The folder holds a `chapter.json`, so a skip
        // rule that only knew the plain suffix read it as a root chapter, and `bootEntries` — which is
        // what a tree with no index uses to learn the root's order — carried it into the file the next
        // structural edit writes. A deleted chapter came back as book content the loader then refused.
        tree();
        write("one.deleted/chapter.json", chapter("one", "[]"));
        write("one.deleted.2/chapter.json", chapter("one", "[]"));

        QuestEditor editor = QuestEditor.open(root(), "one").orElseThrow();
        EditorOps.Applied applied = EditorOps.apply(editor, new EditorOp.CreateGroup("gamma", "Gamma"));
        assertTrue(applied.ok(), applied.messages().toString());

        String index = indexText();
        assertFalse(index.contains("deleted"),
                "a tombstone is not an entry, in either spelling: " + index);
        assertTrue(index.contains("\"gamma\""), "and the edit itself is what was written: " + index);
    }

    @Test
    @DisplayName("undoing a create twice sets the folder aside twice, and destroys neither copy")
    void undoingACreateTwiceKeepsBothCopies() throws IOException {
        // **How the numbered spelling is reached, with no delete anywhere in the story.** Create a group,
        // Ctrl+Z, create it again, Ctrl+Z: `aside()` will not overwrite the copy the first undo made, so
        // the second lands beside it as `<name>.deleted.2`. That route is the reason the skip rule has to
        // answer for the numbered form — it is not an exotic state, it is two presses of the same key.
        tree();
        QuestEditor editor = QuestEditor.open(root(), "one").orElseThrow();

        assertTrue(EditorOps.apply(editor, new EditorOp.CreateGroup("gamma", "Gamma")).ok());
        assertTrue(editor.undo(), "the create is on the history");
        assertTrue(Files.isRegularFile(root().resolve("gamma.deleted/group.json")),
                "the folder is put aside rather than erased");

        assertTrue(EditorOps.apply(editor, new EditorOp.CreateGroup("gamma", "Gamma")).ok(),
                "the name is free again, because a tombstone is not content");
        assertTrue(editor.undo());
        assertTrue(Files.isRegularFile(root().resolve("gamma.deleted/group.json")),
                "the first copy is untouched");
        assertTrue(Files.isRegularFile(root().resolve("gamma.deleted.2/group.json")),
                "and the second is beside it, which is what numbering is for");
        assertFalse(Files.exists(root().resolve("gamma")), "neither undo left the live folder behind");
    }

    @Test
    @DisplayName("a set-aside chapter is put back by name, and the restore is one Ctrl+Z")
    void restoresASetAsideChapter() throws IOException {
        // The inverse of the delete above, asked for by name rather than by history -- which is the whole
        // point of it: the history is the server's memory and the copy is on disk.
        tree();
        QuestEditor editor = QuestEditor.open(root(), "one").orElseThrow();
        assertTrue(EditorOps.apply(editor, new EditorOp.DeleteChapter("one")).ok());

        EditorOps.Applied restored = EditorOps.apply(editor,
                new EditorOp.RestoreRemoved("alpha/one.deleted"));

        assertTrue(restored.ok(), restored.messages().toString());
        assertTrue(Files.isRegularFile(root().resolve("alpha/one/first.json")),
                "the folder is back with its quests inside");
        assertFalse(Files.exists(root().resolve("alpha/one.deleted")), "and the copy is gone");
        assertTrue(groupChapters("alpha").contains("one"), groupChapters("alpha"));

        // One Ctrl+Z, the same key as ever: a restore is a structural edit like any other.
        assertTrue(editor.undo());
        assertFalse(Files.exists(root().resolve("alpha/one")));
        assertTrue(Files.isDirectory(root().resolve("alpha/one.deleted")), "and it is set aside again");
    }

    @Test
    @DisplayName("a set-aside group is put back, index entry and all")
    void restoresASetAsideGroup() throws IOException {
        tree();
        QuestEditor editor = QuestEditor.open(root(), "one").orElseThrow();
        assertTrue(EditorOps.apply(editor, new EditorOp.DeleteGroup("beta")).ok());
        assertTrue(Files.isDirectory(root().resolve("beta.deleted")));

        EditorOps.Applied restored = EditorOps.apply(editor,
                new EditorOp.RestoreRemoved("beta.deleted"));

        assertTrue(restored.ok(), restored.messages().toString());
        assertTrue(Files.isRegularFile(root().resolve("beta/two/chapter.json")), "the group is back");
        assertTrue(indexText().contains("\"beta\""), "and the index names it again: " + indexText());
    }

    @Test
    @DisplayName("a restore refuses a name that is taken, and anything that is not set aside")
    void aRestoreRefusesWhatItCannotDo() throws IOException {
        tree();
        QuestEditor editor = QuestEditor.open(root(), "one").orElseThrow();
        assertTrue(EditorOps.apply(editor, new EditorOp.DeleteChapter("one")).ok());
        Files.createDirectories(root().resolve("alpha/one"));

        EditorOps.Applied taken = EditorOps.apply(editor,
                new EditorOp.RestoreRemoved("alpha/one.deleted"));
        assertFalse(taken.ok(), "the folder would have been overwritten");
        assertTrue(taken.messages().get(0).contains("already"), taken.messages().toString());
        assertTrue(Files.isDirectory(root().resolve("alpha/one.deleted")), "and the copy stays put");

        assertFalse(EditorOps.apply(editor, new EditorOp.RestoreRemoved("alpha/one")).ok(),
                "a live folder is not a tombstone");
        assertFalse(EditorOps.apply(editor, new EditorOp.RestoreRemoved("../outside")).ok(),
                "nor is anything outside the root");
    }

    @Test
    @DisplayName("a book setting is written into index.json, and every other declaration survives")
    void aBookSettingIsWritten() throws IOException {
        tree();
        // The file is the fixture's own here: a declaration the editor does not own, beside the settings
        // block, has to survive the read-modify-write the same way it survives a structural edit -- the
        // lesson `indexFile`'s own comment records about the settings block being wiped by the first
        // structural write.
        Path index = root().resolve("index.json");
        Files.writeString(index, "{\"futureKey\": \"kept\", \"entries\": []}");

        assertTrue(QuestStructure.setIndexSetting(root(), "bookTitle", "The Orrery Ledger"));
        String written = Files.readString(index);
        assertTrue(written.contains("The Orrery Ledger"), written);
        assertTrue(written.contains("futureKey"), "an unknown root key is carried over: " + written);
        assertEquals("The Orrery Ledger", QuestSettings.load(root()).bookTitle(),
                "and the loader reads it back");

        assertTrue(QuestStructure.setIndexSetting(root(), "bookTitle", null), "and it can be removed");
        assertEquals("", QuestSettings.load(root()).bookTitle());
        assertTrue(Files.readString(index).contains("futureKey"), "the rest is still there");

        // And a key the loader does not know is refused rather than written into a file whose schema
        // would then refuse the whole index.
        assertFalse(QuestStructure.setIndexSetting(root(), "notAField", "x"));
        assertFalse(Files.readString(index).contains("notAField"), Files.readString(index));
    }
}
