package dev.ellipog.tenet.quest;

import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.armature.api.data.Problems;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static dev.ellipog.tenet.quest.Fixtures.q;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loader reads a directory. That is the whole of its job.
 *
 * <h2>Why this file is about what the loader does <i>not</i> do</h2>
 *
 * <p>Because the same mistake was made twice, in opposite directions, and the test suite was green
 * both times.
 *
 * <p>The first version copied a questline out of the jar whenever the quest directory was
 * <b>absent</b>. A fresh install worked perfectly, which is why it looked finished. The fault was
 * invisible until a second questline was added to the jar: every existing install already had the
 * directory, so the new file was never written, and no rebuild or redeploy could change that. The
 * symptom was a quest book showing one chapter to somebody who had been told there were three.
 *
 * <p>And it could not have been caught here. These tests build a temporary directory, where the
 * directory genuinely <i>is</i> absent, so every shipped file was written and every assertion about
 * them passed. <b>The bug existed only in the transition from one version to the next — the one
 * state a test that builds its own fixture never occupies.</b>
 *
 * <p>The second version fixed that by recording which files had been offered, in a {@code .seeded}
 * marker, and offering the rest. Which worked, and was a bigger machine for a smaller problem: it
 * still had the mod writing content into a directory that belongs to whoever is playing.
 *
 * <p>The rule that replaced both is simpler and is the thing these tests pin: <b>the loader reads,
 * and only reads.</b> It never creates the directory, never writes a file, never rewrites one an
 * author has edited, and never deletes anything. A mod that installs example chapters into every
 * player's config directory has decided something that is not its to decide — the first thing a pack
 * author would have to do is delete somebody else's content — so the worked examples live in the
 * repository at {@code tenet/tools/quests}, and {@code tenet/tools/seed_quests.py} is what puts them
 * where somebody actually wants them.
 *
 * <h2>Why "writes nothing" is asserted by a whole-directory snapshot</h2>
 *
 * <p>Because the bug class is a file appearing or changing that nobody asked for, and a test that
 * checks a specific file's bytes only rules out the specific file it thought of. The {@code .seeded}
 * marker is the cautionary example: it was a real file, created in the quest directory, and a test
 * looking at the quest files would never have noticed it. So the assertion is the entire tree —
 * every name and every byte — before and after a load.
 */
@DisplayName("QuestLoader")
class QuestLoaderTest {

    @BeforeAll
    static void bootVanilla() {
        // TenetQuests' load path runs the validator, and the validator checks that every item id a
        // quest names exists in BuiltInRegistries.ITEM. An unbootstrapped registry does not merely
        // report everything missing -- it throws from inside a vanilla class initialiser. So this runs
        // first, and the difference between a test and a test that proves something is that vanilla
        // is actually there.
        MinecraftTestBootstrap.boot();
    }

    @TempDir
    Path temp;

    // ------------------------------------------------------------------
    // It does not create, and it does not write
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a missing quest directory is reported and NOT created")
    void aMissingDirectoryIsReportedAndNotCreated() {
        // This is the rule the class is named for, and the one it took two attempts to get right. A
        // loader that creates the directory is a loader that has started writing, and writing is how
        // you end up maintaining a copy of somebody else's content.
        Path quests = temp.resolve(QuestLoader.DIRECTORY);

        QuestLoader.Result result = QuestLoader.load(temp);

        assertFalse(Files.exists(quests),
                "the loader created " + quests + ". It must read and only read: a mod that makes this "
                        + "directory is a mod that is about to put something in it, which is how three "
                        + "example chapters ended up in every player's config directory");
        assertEquals(0, result.filesFound(), "there is nothing there to find");
        assertEquals(0, result.index().questCount());
        assertTrue(result.ok(),
                "a missing directory is a normal state, not an error -- Tenet ships no quests, so this "
                        + "is exactly what a fresh install looks like");

        // Reported, though, because a warning naming the full path is the useful half of what the old
        // code did, and its absence would leave an author with an empty quest book and no clue why.
        String warnings = render(result.problems());
        assertTrue(warnings.contains(quests.toString()),
                "the warning should name the directory it looked in, so the fix is obvious from the "
                        + "message alone. It said:\n" + warnings);
        assertTrue(result.problems().all().stream()
                        .allMatch(problem -> problem.severity() != DataProblem.Severity.ERROR),
                "and it must be a warning rather than an error. As an error it would fail every "
                        + "install on the first launch:\n" + warnings);
    }

    @Test
    @DisplayName("an empty directory is a normal state and loads as nothing")
    void anEmptyDirectoryIsFine() throws IOException {
        Files.createDirectories(temp.resolve(QuestLoader.DIRECTORY));

        QuestLoader.Result result = QuestLoader.load(temp);

        assertEquals(0, result.filesFound());
        assertEquals(0, result.index().questCount());
        assertTrue(result.ok(), () -> "an empty directory is not a failure:\n" + render(result.problems()));
    }

    @Test
    @DisplayName("a load changes nothing at all in the directory it reads")
    void aLoadWritesNothing() throws IOException {
        // The whole rule, in one assertion. An author's directory after a load is byte-for-byte and
        // entry-for-entry what it was before it -- no quest file rewritten, no `.seeded` marker, no
        // directory conjured into existence anywhere.
        Path quests = temp.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(quests);

        // Three files rather than one: a good one, one whose content is emphatically not what the mod
        // ships, and a `_`-prefixed one the loader is meant to skip. Between them they cover every way
        // the loader could decide to touch something.
        Files.writeString(quests.resolve("a.json"), Fixtures.file(q("a").build()));
        Files.writeString(quests.resolve("b.json"), "{\"mine\": true}\n");
        Files.writeString(quests.resolve("_notes.json"), Fixtures.file(q("hidden").build()));

        // The whole temporary directory, not just the quest directory inside it. That is the stronger
        // assertion and it costs one walk: it covers a file appearing *beside* the quests as well as
        // one appearing among them. The `.seeded` marker this replaced lived inside the quest
        // directory, so a narrower snapshot would have caught that particular mistake -- and a rule
        // stated as "do not write quest files" is a rule that a future marker somewhere else would
        // satisfy while still being the same fault.
        Map<String, byte[]> before = snapshot(temp);
        QuestLoader.load(temp);
        Map<String, byte[]> after = snapshot(temp);

        assertEquals(before.keySet(), after.keySet(),
                "the set of files under the config directory changed during a load. A file that appears "
                        + "is the mod writing content nobody asked for; a file that disappears is worse. "
                        + "Before: " + before.keySet() + ", after: " + after.keySet());
        for (String name : before.keySet()) {
            assertArrayEquals(before.get(name), after.get(name),
                    name + " was rewritten by a load. Files here belong to whoever wrote them, and a "
                            + "loader that edits them destroys work on every upgrade");
        }

        // And the load still did its actual job, so this is not passing because it read nothing.
        assertEquals(1, QuestLoader.load(temp).index().questCount(),
                "exactly one file in this directory is a quest, and it should still load");
    }

    @Test
    @DisplayName("the directory the loader reads does not exist until something puts it there")
    void theLoaderNeverCreatesTheDirectory() {
        // The same rule from the other side, because the two versions of this bug were both about
        // creation rather than about content: one created the directory and filled it, the other
        // created it and filled it with a marker.
        assertFalse(Files.exists(temp.resolve("tenet")));

        QuestLoader.load(temp);
        QuestLoader.load(temp);

        // Not even after two loads, and not even with the warning having been raised twice.
        assertFalse(Files.exists(temp.resolve("tenet")),
                "the parent directory was created by loading twice. Nothing in the load path may create "
                        + "a directory, at any depth");
    }

    // ------------------------------------------------------------------
    // What it does read
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a quest file in the directory is found, decoded and indexed")
    void aFileIsRead() throws IOException {
        Path quests = temp.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(quests);
        Files.writeString(quests.resolve("a.json"), Fixtures.file(q("a").build()));

        QuestLoader.Result result = QuestLoader.load(temp);

        assertEquals(1, result.filesFound());
        assertEquals(1, result.filesDecoded(), "it should decode, not just be found");
        assertEquals(1, result.index().questCount());
        assertTrue(result.ok(), () -> "a well-formed file should load cleanly:\n" + render(result.problems()));
        assertTrue(result.index().quest("a").isPresent(),
                "and the quest in it should be findable by id, which is what everything downstream does");
    }

    @Test
    @DisplayName("`_`-prefixed files are skipped, so an author can keep notes beside their work")
    void underscoreFilesAreSkipped() throws IOException {
        Path quests = temp.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(quests);
        Files.writeString(quests.resolve("a.json"), Fixtures.file(q("a").build()));
        // Valid JSON, and a valid quest. Skipped on the name alone, which is what makes it usable for
        // the deliberately-broken fixtures in the test combos: rename one to drop the underscore and
        // it loads.
        Files.writeString(quests.resolve("_disabled.json"), Fixtures.file(q("b").build()));

        QuestLoader.Result result = QuestLoader.load(temp);

        assertEquals(1, result.filesFound(), "the underscore file should not even be reported as found");
        assertEquals(1, result.index().questCount());
        assertTrue(result.index().quest("b").isEmpty(), "and its quest must not be in the index");
    }

    @Test
    @DisplayName("a broken file is reported, and the rest of the directory still loads")
    void oneBadFileDoesNotStopTheRest() throws IOException {
        // The other promise the class comment makes: a questline of ten files with one broken should
        // still load the other nine. The difference between a typo being a nuisance and it being a
        // dead server.
        Path quests = temp.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(quests);
        Files.writeString(quests.resolve("a.json"), Fixtures.file(q("a").build()));
        Files.writeString(quests.resolve("broken.json"), "{ this is not json at all ");

        // The third file's identifiers are written out here rather than taken from `Fixtures.file()`,
        // and the reason is worth keeping because the first version of this test failed on it.
        //
        // `file()` hardcodes a group id of "group" and a chapter id of "chapter", so two files built
        // with it declare the *same* identifiers. The index correctly reports that as a duplicate
        // against the second file -- so `filesWithErrors` came back as 2, not 1, and the failure read
        // like a loader bug when the loader was right and the fixture was wrong:
        //
        //     c.json:5:13: error: duplicate chapter group id "group" - already used by a.json:4:5
        //     c.json:9:17: error: duplicate chapter id "chapter" - already used by a.json:8:9
        //
        // Distinct ids are what this test means by "two good files": a fixture that trips a real check
        // on its way to testing something else does not test what it says it does.
        //
        // Note also which file is blamed. Both messages name c.json, the second claimant, and not the
        // file that got there first -- which is the right answer and worth not breaking.
        Files.writeString(quests.resolve("c.json"), """
                { "version": 1, "chapterGroups": [ { "id": "group2", "title": "Group", "chapters": [
                    { "id": "chapter2", "title": "Chapter", "quests": [ %s ] } ] } ] }
                """.formatted(q("c").build()));

        QuestLoader.Result result = QuestLoader.load(temp);

        assertEquals(3, result.filesFound(), "all three should be found, including the broken one");
        assertEquals(2, result.filesDecoded(), "and the two good ones should decode");
        assertEquals(2, result.index().questCount());
        assertFalse(result.ok(), "a file with an error must be reported, or a typo is a silent mystery");

        // The problems are in the message as well as asserted on, because the count is a number and
        // the useful question is *which* files it counted. A failure here that printed only "expected
        // 1 but was 2" would send the next reader to the wrong place -- which is exactly what happened
        // when this was first written.
        String rendered = render(result.problems());
        assertEquals(List.of("broken.json"), filesWithErrors(result),
                "the error should be counted against the file that has it. Problems were:\n" + rendered);

        assertTrue(rendered.contains("broken.json"),
                "the error should name the file, which is the whole point of carrying positions "
                        + "through the pipeline. It said:\n" + rendered);
    }

    @Test
    @DisplayName("a chapter listed at the root loads with no group, which the folder format could not express")
    void aRootChapterLoadsWithNoGroup() throws IOException {
        // The end of the path discovery's root-chapter case starts: a loose chapter is a real chapter
        // in the index, and its quests carry the empty group id the client already draws as a root
        // row. Before this, a chapter outside a group was not loadable by any arrangement of folders.
        Path quests = temp.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(quests.resolve("loose"));
        Files.writeString(quests.resolve("loose/chapter.json"), """
                { "$schema": "../../_schema/chapter.schema.json",
                  "id": "loose", "title": "Loose", "quests": ["only.json"] }
                """);
        Files.writeString(quests.resolve("loose/only.json"), """
                { "$schema": "../../../_schema/quest.schema.json",
                  "id": "only", "title": "Only" }
                """);
        Files.writeString(quests.resolve("index.json"), """
                { "$schema": "./_schema/index.schema.json",
                  "entries": [ { "chapter": "loose" } ] }
                """);

        QuestLoader.Result result = QuestLoader.load(temp);

        assertTrue(result.ok(), () -> "a loose chapter should load cleanly:\n" + render(result.problems()));
        assertEquals(1, result.index().questCount());
        QuestIndex.QuestEntry entry = result.index().quest("only").orElseThrow();
        assertEquals("", entry.groupId(),
                "no group is the empty id, which is what the client draws as a root row");
        assertTrue(result.index().chapter("loose").isPresent(),
                "and the chapter itself is in the index even though it is the only one, which is what "
                        + "lets a chapter with no group exist at all");
    }

    @Test
    @DisplayName("a chapter with no quests is still a chapter in the index")
    void anEmptyChapterSurvivesTheLoad() throws IOException {
        // The state between creating a chapter and writing its first quest. It has to be a real chapter
        // the whole way through, or the sidebar -- which now draws its rows from the chapter list -- 
        // would show a row that nothing downstream can resolve.
        Path quests = temp.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(quests.resolve("waiting"));
        Files.writeString(quests.resolve("waiting/chapter.json"), """
                { "$schema": "../../_schema/chapter.schema.json",
                  "id": "waiting", "title": "Waiting For A Quest", "quests": [] }
                """);
        Files.writeString(quests.resolve("index.json"), """
                { "$schema": "./_schema/index.schema.json",
                  "entries": [ { "chapter": "waiting" } ] }
                """);

        QuestLoader.Result result = QuestLoader.load(temp);

        assertTrue(result.ok(), () -> "an empty chapter is a normal state:\n" + render(result.problems()));
        assertEquals(0, result.index().questCount());
        assertTrue(result.index().chapter("waiting").isPresent(),
                "the chapter is in the index with no quests under it");
        assertEquals(1, result.index().chapterCount());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Every entry under {@code root}, name to bytes.
     *
     * <p>Bytes and not just names, because "the file is still there" is satisfied by a file that was
     * overwritten with different content — which is the more likely half of the bug.
     */
    private static Map<String, byte[]> snapshot(Path root) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = new ArrayList<>(walk.filter(Files::isRegularFile).toList());
            paths.sort(Comparator.comparing(Path::toString));
            for (Path path : paths) {
                out.put(root.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Reward tables: beside the book, not inside it
    // ------------------------------------------------------------------

    @Test
    @DisplayName("tables load from their reserved folder, validated like any other file")
    void rewardTablesLoad() throws IOException {
        Path quests = temp.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(quests.resolve(QuestFiles.REWARD_TABLES_DIRECTORY));
        Files.writeString(quests.resolve("reward_tables/loot.json"), """
                {
                  "emptyWeight": 0,
                  "lootSize": 1,
                  "entries": [
                    { "weight": 1,
                      "reward": { "type": "tenet:item", "item": "minecraft:diamond", "count": 1 } }
                  ]
                }
                """, StandardCharsets.UTF_8);

        QuestLoader.Result result = QuestLoader.load(temp);

        assertTrue(result.ok(), () -> "a clean table is not a problem:\n" + render(result.problems()));
        assertTrue(result.rewardTables().containsKey("loot"),
                "the file name, without the suffix, is the table's id: " + result.rewardTables().keySet());
        assertEquals(1, result.rewardTables().get("loot").entryCount());
        assertEquals(0, result.index().questCount(), "and a table is not a quest");
    }

    @Test
    @DisplayName("a table whose name starts with `_` is a note, not a table")
    void underscoredRewardTablesAreSkipped() throws IOException {
        // The `_` rule is the loader's promise that a file beside the content is not content. The
        // reward-table reader did not apply it, so a draft in that folder became a table whose id was
        // `_draft` -- synced to every client and nameable by a reward's `table` field.
        Path quests = temp.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(quests.resolve(QuestFiles.REWARD_TABLES_DIRECTORY));
        Files.writeString(quests.resolve("reward_tables/_draft.json"), """
                { "lootSize": 1,
                  "entries": [ { "weight": 1,
                    "reward": { "type": "tenet:item", "item": "minecraft:diamond" } } ] }
                """, StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("reward_tables/loot.json"), """
                { "lootSize": 1,
                  "entries": [ { "weight": 1,
                    "reward": { "type": "tenet:item", "item": "minecraft:emerald" } } ] }
                """, StandardCharsets.UTF_8);

        QuestLoader.Result result = QuestLoader.load(temp);

        assertTrue(result.ok(), () -> "a draft table is not a problem:\n" + render(result.problems()));
        assertEquals(Set.of("loot"), result.rewardTables().keySet(),
                "the `_`-prefixed file is skipped like every other `_`-prefixed name, and the other one "
                        + "still loads");
        assertFalse(QuestFiles.rewardTableFiles(temp.resolve(QuestLoader.DIRECTORY))
                        .stream().anyMatch(path -> path.getFileName().toString().startsWith("_")),
                "and the discovery walk does not list it either");
    }

    @Test
    @DisplayName("a folder the group does not list does not cost the chapters it does list")
    void anUnlistedGroupFolderKeepsTheGroup() throws IOException {
        // **The same finding one level over, and found the same way: in a pack on disk, not in a fixture.**
        // A converted pack had a folder copied into itself -- `pack/pack/...`, two complete copies -- and
        // because the unlisted-folder error was attached to `group.json`, `assemble` refused the group and
        // threw away all fourteen chapters its list *did* name, plus every quest under them. The author's
        // whole questline vanished because of one stray folder, and the message said only that the stray
        // folder would not load. A dropped group never reaches the index, so it also hid every index-level
        // fault inside it: 135 duplicate ids in that pack were never reported either.
        Path quests = temp.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(quests.resolve("pack/one"));
        Files.createDirectories(quests.resolve("pack/stray/one"));
        Files.writeString(quests.resolve("pack/group.json"),
                "{ \"id\": \"pack\", \"title\": \"Pack\", \"chapters\": [\"one\"] }", StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("pack/one/chapter.json"),
                "{ \"id\": \"one\", \"title\": \"One\", \"quests\": [\"a.json\"] }", StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("pack/one/a.json"),
                "{ \"id\": \"a\", \"title\": \"A\" }", StandardCharsets.UTF_8);
        // The stray copy, complete enough to be a pack of its own -- which is what makes it invisible.
        Files.writeString(quests.resolve("pack/stray/group.json"),
                "{ \"id\": \"stray\", \"title\": \"Stray\", \"chapters\": [\"one\"] }", StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("pack/stray/one/chapter.json"),
                "{ \"id\": \"one\", \"title\": \"One\", \"quests\": [\"b.json\"] }", StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("pack/stray/one/b.json"),
                "{ \"id\": \"b\", \"title\": \"B\" }", StandardCharsets.UTF_8);

        QuestLoader.Result result = QuestLoader.load(temp);

        assertFalse(result.ok(), "the stray folder is still reported:\n" + render(result.problems()));
        assertTrue(render(result.problems()).contains("stray")
                        && render(result.problems()).contains("will never load"),
                "and the message still says what it costs, naming the folder:\n" + render(result.problems()));
        assertTrue(!filesWithErrors(result).contains("pack/group.json"),
                "but it is not an error against the group's manifest, which is what dropped the group: "
                        + filesWithErrors(result));
        assertEquals(1, result.index().groupCount(), "the group is in the tree");
        assertEquals(1, result.index().chapterCount(), "so is the chapter its list names");
        assertEquals(1, result.index().questCount(), "and the quest under it, which used to vanish");
        assertEquals("a", result.index().quests().get(0).quest().id());
    }

    @Test
    @DisplayName("a quest file the chapter does not list does not cost the quests it does list")
    void anUnlistedQuestFileKeepsTheChapter() throws IOException {
        // The same shape one level down, and it was left unfixed when the group's was fixed: an unlisted
        // *quest file* was reported against `chapter.json`, so the chapter was refused and every quest it
        // listed went with it. A chapter of forty lost to one stray file is the same fault as a group of
        // fourteen lost to one stray folder, and the fix is the same -- report it against the file.
        Path quests = temp.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(quests.resolve("pack/one"));
        Files.writeString(quests.resolve("pack/group.json"),
                "{ \"id\": \"pack\", \"title\": \"Pack\", \"chapters\": [\"one\"] }", StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("pack/one/chapter.json"),
                "{ \"id\": \"one\", \"title\": \"One\", \"quests\": [\"a.json\"] }", StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("pack/one/a.json"),
                "{ \"id\": \"a\", \"title\": \"A\" }", StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("pack/one/stray.json"),
                "{ \"id\": \"b\", \"title\": \"B\" }", StandardCharsets.UTF_8);

        QuestLoader.Result result = QuestLoader.load(temp);

        assertFalse(result.ok(), "the stray file is still reported:\n" + render(result.problems()));
        assertTrue(!filesWithErrors(result).contains("pack/one/chapter.json"),
                "not against the chapter's manifest, which is what dropped the chapter: "
                        + filesWithErrors(result));
        assertEquals(1, result.index().chapterCount(), "the chapter is in the tree");
        assertEquals(1, result.index().questCount(), "and the quest its list names");
        assertEquals("a", result.index().quests().get(0).quest().id());
    }

    @Test
    @DisplayName("a quest file the chapter lists but which is not there does not cost the quests it does list")
    void aMissingQuestFileKeepsTheChapter() throws IOException {
        // The same shape as the unlisted case above, one direction over -- and this is the direction
        // that actually happens, because deleting a file in Explorer, a rename done in one place, or a
        // bad merge all leave the manifest naming something the disk no longer has. The failure used to
        // be filed against `chapter.json`, and a manifest with an error against it is refused, so `a`
        // and `b` went with `gone` -- two quests lost to one deletion, and a chapter of forty lost to
        // one file.
        Path quests = temp.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(quests.resolve("pack/one"));
        Files.writeString(quests.resolve("pack/group.json"),
                "{ \"id\": \"pack\", \"title\": \"Pack\", \"chapters\": [\"one\"] }", StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("pack/one/chapter.json"),
                "{ \"id\": \"one\", \"title\": \"One\", \"quests\": [\"a.json\", \"gone.json\", \"b.json\"] }",
                StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("pack/one/a.json"),
                "{ \"id\": \"a\", \"title\": \"A\" }", StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("pack/one/b.json"),
                "{ \"id\": \"b\", \"title\": \"B\" }", StandardCharsets.UTF_8);
        // `gone.json` is named by the manifest and deliberately not written: a file deleted by hand.

        QuestLoader.Result result = QuestLoader.load(temp);

        assertFalse(result.ok(), "a declared name that resolves to nothing is still an error:\n"
                + render(result.problems()));
        assertEquals(List.of("pack/one/gone.json"), filesWithErrors(result),
                "filed against the name that is not there, and against nothing else:\n"
                        + render(result.problems()));
        assertEquals(1, result.index().chapterCount(), "the chapter is in the tree");
        assertEquals(2, result.index().questCount(),
                "and both quests the list names and the disk still has");
        assertTrue(result.index().quest("a").isPresent() && result.index().quest("b").isPresent());
    }

    @Test
    @DisplayName("a chapter folder the group lists but which is not there does not cost the chapters it does")
    void aMissingChapterFolderKeepsTheGroup() throws IOException {
        // The group level, where the cost was worse: one missing chapter folder took the group and
        // every chapter its list *did* name, so a group of fourteen lost thirteen to one deletion.
        Path quests = temp.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(quests.resolve("pack/one"));
        Files.writeString(quests.resolve("pack/group.json"),
                "{ \"id\": \"pack\", \"title\": \"Pack\", \"chapters\": [\"one\", \"gone\"] }",
                StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("pack/one/chapter.json"),
                "{ \"id\": \"one\", \"title\": \"One\", \"quests\": [\"a.json\"] }", StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("pack/one/a.json"),
                "{ \"id\": \"a\", \"title\": \"A\" }", StandardCharsets.UTF_8);

        QuestLoader.Result result = QuestLoader.load(temp);

        assertFalse(result.ok());
        assertEquals(List.of("pack/gone"), filesWithErrors(result),
                "the folder that is not there is what is blamed:\n" + render(result.problems()));
        assertEquals(1, result.index().groupCount(), "the group is in the tree");
        assertEquals(1, result.index().chapterCount(), "so is the chapter its list names");
        assertEquals(1, result.index().questCount(), "and the quest under it");
    }

    @Test
    @DisplayName("a declared name that cannot be used costs that name, not the chapter")
    void anUnusableDeclaredNameKeepsTheChapter() throws IOException {
        // A name with a separator in it: a declared name is one segment resolved against its own folder,
        // so there is no path it could have resolved to, and nothing to blame but the name. It gets its
        // own token for that reason -- the message quotes it -- because the alternative is that one
        // slip of a string costs the chapter, which is the fault being fixed here in its purest form.
        Path quests = temp.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(quests.resolve("pack/one"));
        Files.writeString(quests.resolve("pack/group.json"),
                "{ \"id\": \"pack\", \"title\": \"Pack\", \"chapters\": [\"one\"] }", StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("pack/one/chapter.json"),
                "{ \"id\": \"one\", \"title\": \"One\", \"quests\": [\"a.json\", \"sub/b.json\"] }",
                StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("pack/one/a.json"),
                "{ \"id\": \"a\", \"title\": \"A\" }", StandardCharsets.UTF_8);

        QuestLoader.Result result = QuestLoader.load(temp);

        assertFalse(result.ok());
        assertEquals(List.of("sub/b.json"), filesWithErrors(result),
                "the name as written is what is blamed:\n" + render(result.problems()));
        assertTrue(render(result.problems()).contains("path separator"),
                "and the message says what is wrong with it:\n" + render(result.problems()));
        assertEquals(1, result.index().chapterCount(), "the chapter still loads");
        assertEquals(1, result.index().questCount(), "with the quest beside it");
    }

    @Test
    @DisplayName("a root chapter declared only by an index.json that will not parse does not load")
    void aRootChapterNeedsAnIndex() throws IOException {
        // The one layout the fallback cannot serve, pinned rather than left to be discovered: with the
        // manifest unreadable there is nothing left that says a root folder is a chapter rather than a
        // malformed group, so it is reported as one. Reading it as a chapter anyway would make "no
        // index" mean two different things depending on how the file became unreadable.
        Path quests = temp.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(quests.resolve("loose"));
        Files.writeString(quests.resolve("loose/chapter.json"),
                "{ \"id\": \"loose\", \"title\": \"Loose\", \"quests\": [\"only.json\"] }",
                StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("loose/only.json"),
                "{ \"id\": \"only\", \"title\": \"Only\" }", StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("index.json"), "{ \"entries\": [ ", StandardCharsets.UTF_8);

        QuestLoader.Result result = QuestLoader.load(temp);

        assertFalse(result.ok());
        assertTrue(render(result.problems()).contains("not a chapter group"),
                "the cost is stated rather than silent:\n" + render(result.problems()));
        assertEquals(0, result.index().chapterCount(), "and the chapter does not load");
        assertEquals(0, result.index().questCount());
    }

    @Test
    @DisplayName("a group whose manifest id disagrees still loads, with the folder name winning")
    void aMismatchedGroupStillLoads() throws IOException {
        // The whole of finding 5: the mismatch was reported as an *error*, and the loader refuses a
        // declaration with any error against it -- so the group, its chapter and every quest under them
        // vanished, while the javadoc, the manual and the discovery test all promised the folder wins.
        Path quests = temp.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(quests.resolve("getting_started/one"));
        Files.writeString(quests.resolve("getting_started/group.json"),
                "{ \"id\": \"first_steps\", \"title\": \"A group\", \"chapters\": [\"one\"] }",
                StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("getting_started/one/chapter.json"),
                "{ \"id\": \"one\", \"title\": \"One\", \"quests\": [\"a.json\"] }", StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("getting_started/one/a.json"),
                "{ \"id\": \"a\", \"title\": \"A\" }", StandardCharsets.UTF_8);

        QuestLoader.Result result = QuestLoader.load(temp);

        assertTrue(result.ok(), () -> "a mismatched id is not fatal:\n" + render(result.problems()));
        assertTrue(filesWithErrors(result).isEmpty(),
                "it must not be an error against the group's manifest, which is what dropped the group: "
                        + filesWithErrors(result));
        assertTrue(render(result.problems()).contains("first_steps")
                        && render(result.problems()).contains("getting_started"),
                "and it is still reported, naming both sides:\n" + render(result.problems()));
        assertEquals(1, result.index().groupCount(), "the group is in the tree");
        assertEquals(1, result.index().chapterCount(), "so is its chapter");
        assertEquals(1, result.index().questCount(), "and the quest under it, which used to vanish");
        assertEquals("a", result.index().quests().get(0).quest().id());
    }

    @Test
    @DisplayName("a chapter cycle is reported with the chain, naming both ways out of it")
    void chapterCycleIsReported() throws IOException {
        // A chapter cycle is the quietest failure a questline has: every file is fine on its own, no
        // quest refuses to unlock, and the only symptom is a chapter that never opens. So the check is
        // only worth having if the author is actually told -- which is what this asserts, one level
        // above the graph pass that finds the loop.
        Path quests = temp.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(quests.resolve("one"));
        Files.createDirectories(quests.resolve("two"));
        Files.writeString(quests.resolve("index.json"), """
                { "entries": [ { "chapter": "one" }, { "chapter": "two" } ] }
                """, StandardCharsets.UTF_8);
        // Each waits on the other, and each declares what finishes it -- so the only fault in the pack
        // is the loop itself, and the message under test is not one of the other chapter checks firing.
        Files.writeString(quests.resolve("one/chapter.json"), """
                { "id": "one", "title": "One", "dependsOn": ["two"], "completesWhen": ["b"],
                  "quests": ["a.json"] }
                """, StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("one/a.json"),
                "{ \"id\": \"a\", \"title\": \"A\" }", StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("two/chapter.json"), """
                { "id": "two", "title": "Two", "dependsOn": ["one"], "completesWhen": ["a"],
                  "quests": ["b.json"] }
                """, StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("two/b.json"),
                "{ \"id\": \"b\", \"title\": \"B\" }", StandardCharsets.UTF_8);

        QuestLoader.Result result = QuestLoader.load(temp);

        assertFalse(result.ok(), "a chapter cycle is fatal to the chapters in it");
        String messages = render(result.problems());
        assertTrue(messages.contains("circular chapter dependency"),
                "the loop should be named as one:\n" + messages);
        assertTrue(messages.contains("one") && messages.contains("two"),
                "with both chapters in the chain:\n" + messages);
        assertTrue(messages.contains("completesWhen"),
                "and the way out that is not a dependsOn, since that is the edge a reader would not "
                        + "have thought of:\n" + messages);
    }

    @Test
    @DisplayName("a malformed table is reported at its own file, and the book still loads")
    void malformedRewardTableIsReported() throws IOException {
        Path quests = temp.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(quests.resolve(QuestFiles.REWARD_TABLES_DIRECTORY));
        Files.writeString(quests.resolve("reward_tables/broken.json"),
                "{ \"entries\": \"not a list\" }", StandardCharsets.UTF_8);

        QuestLoader.Result result = QuestLoader.load(temp);

        assertFalse(result.ok(), "a table whose entries are not a list is refused");
        assertTrue(render(result.problems()).contains("broken.json"),
                "reported against the table's own file:\n" + render(result.problems()));
        assertTrue(result.rewardTables().isEmpty(), "and it does not half-load");
    }

    @Test
    @DisplayName("a reward pointing at a table that is not there is reported, naming the id")
    void missingRewardTableIsReported() throws IOException {
        Path quests = temp.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(quests.resolve("chapter"));
        Files.writeString(quests.resolve("index.json"), """
                { "entries": [ { "chapter": "chapter" } ] }
                """, StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("chapter/chapter.json"), """
                { "id": "chapter", "title": "Chapter", "quests": ["q.json"] }
                """, StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("chapter/q.json"), """
                { "id": "q", "title": "Q",
                  "tasks": [ { "type": "tenet:checkmark", "title": "done" } ],
                  "rewards": [ { "type": "tenet:loot", "table": "dungeon" } ] }
                """, StandardCharsets.UTF_8);

        QuestLoader.Result result = QuestLoader.load(temp);

        assertFalse(result.ok(), "a reward naming a table that is not loaded is an error");
        String messages = render(result.problems());
        assertTrue(messages.contains("dungeon"),
                "the message names the table it could not find: " + messages);
        assertTrue(messages.contains(QuestFiles.REWARD_TABLES_DIRECTORY),
                "and where such a table would go: " + messages);
    }

    @Test
    @DisplayName("a reference inside an inline table is checked too, which nothing did before")
    void danglingReferenceInsideAnInlineTableIsReported() throws IOException {
        // The gap this closes: the check read a quest's top-level rewards and stopped, so a reward
        // whose own table pointed at a table that does not exist loaded without a word.
        Path quests = temp.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(quests.resolve("chapter"));
        Files.writeString(quests.resolve("index.json"), """
                { "entries": [ { "chapter": "chapter" } ] }
                """, StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("chapter/chapter.json"), """
                { "id": "chapter", "title": "Chapter", "quests": ["q.json"] }
                """, StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("chapter/q.json"), """
                { "id": "q", "title": "Q",
                  "tasks": [ { "type": "tenet:checkmark", "title": "done" } ],
                  "rewards": [ { "type": "tenet:loot", "inline": { "entries": [
                    { "weight": 1, "reward": { "type": "tenet:random", "table": "nowhere" } } ] } } ] }
                """, StandardCharsets.UTF_8);

        QuestLoader.Result result = QuestLoader.load(temp);

        assertFalse(result.ok(), "the inline table's reference dangles");
        assertTrue(render(result.problems()).contains("nowhere"),
                "and is reported by name:\n" + render(result.problems()));
    }

    @Test
    @DisplayName("tables that reference each other in a loop are an error with the whole chain")
    void tableCyclesAreReported() throws IOException {
        Path quests = temp.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(quests.resolve(QuestFiles.REWARD_TABLES_DIRECTORY));
        Files.writeString(quests.resolve("reward_tables/a.json"), """
                { "entries": [ { "weight": 1,
                    "reward": { "type": "tenet:random", "table": "b" } } ] }
                """, StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("reward_tables/b.json"), """
                { "entries": [ { "weight": 1,
                    "reward": { "type": "tenet:random", "table": "a" } } ] }
                """, StandardCharsets.UTF_8);

        QuestLoader.Result result = QuestLoader.load(temp);

        assertFalse(result.ok(), "a loop is an error, like a circular dependsOn");
        String messages = render(result.problems());
        assertTrue(messages.contains("circular table reference"), messages);
        assertTrue(messages.contains("a -> b -> a") || messages.contains("b -> a -> b"),
                "the whole loop is printed, so the author can see which link to cut: " + messages);
        assertTrue(result.rewardTables().containsKey("a"),
                "and the tables still load: the error is the author's to fix, not a refusal to serve");
    }

    @Test
    @DisplayName("a reference to a table that exists but did not load says which of the two it is")
    void aReferenceToARefusedTableSaysSo() throws IOException {
        // A choice entry is refused where it is written (an error, the same rule as an entry's
        // `conditions`), so the file does not load -- and the table that rolls into it would otherwise
        // be told "no reward table named inner", which reads as a lie about a file that is right there.
        Path quests = temp.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(quests.resolve(QuestFiles.REWARD_TABLES_DIRECTORY));
        Files.writeString(quests.resolve("reward_tables/outer.json"), """
                { "entries": [ { "weight": 1,
                    "reward": { "type": "tenet:random", "table": "inner" } } ] }
                """, StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("reward_tables/inner.json"), """
                { "entries": [ { "weight": 1, "reward": { "type": "tenet:choice",
                    "inline": { "entries": [ { "weight": 1,
                      "reward": { "type": "tenet:item", "item": "minecraft:gold_ingot" } } ] } } } ] }
                """, StandardCharsets.UTF_8);

        QuestLoader.Result result = QuestLoader.load(temp);

        String messages = render(result.problems());
        assertTrue(result.problems().all().stream().anyMatch(problem ->
                        problem.file().equals("inner.json")
                                && problem.message().contains("cannot be an entry in a table")),
                "the choice entry is an error where it is written: " + messages);
        assertTrue(messages.contains("inner.json did not load"),
                "and the table that points at it is told why its reference is dead: " + messages);
    }

    /** The distinct file names carrying an error, in the order they were reported. */
    private static List<String> filesWithErrors(QuestLoader.Result result) {
        return result.problems().all().stream()
                .filter(problem -> problem.severity() == DataProblem.Severity.ERROR)
                .map(DataProblem::file)
                .distinct()
                .toList();
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual, String message) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual, message);
    }

    private static String render(Problems problems) {
        return problems.all().stream()
                .map(DataProblem::render)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("(no problems at all)");
    }
}
