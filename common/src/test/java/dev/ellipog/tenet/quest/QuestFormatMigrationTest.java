package dev.ellipog.tenet.quest;

import dev.ellipog.armature.api.data.DataProblem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The acceptance test for the version-2 format: the same questline, written both ways, is the same
 * questline.
 *
 * <h2>What this is for, and why it is one test rather than several</h2>
 *
 * <p>The format changed shape rather than content. A chapter was one object nested inside a file; it is
 * now a folder with a manifest in it and one file per quest. Nothing about what a chapter <i>means</i>
 * changed — so the only thing that makes the change safe is that a reader cannot tell which layout a
 * questline came from.
 *
 * <p>That is a single property, and it is best asserted as one: build the same chapter both ways, load
 * each, and compare the results completely. A test per field would pass while a field was dropped from
 * one layout and not the other, because nothing would be comparing the two.
 *
 * <h2>What "identical" can and cannot mean for a file and a line</h2>
 *
 * <p>The spec asks for "identical file:line for the same deliberate typo", and taken literally that is
 * impossible — the whole point of the change is that a quest now lives in <b>a file of its own</b>, so
 * v1's {@code stone_age.json} cannot be v2's {@code punch_a_tree.json}. Asserting the strings were equal
 * would be asserting the feature had not been built.
 *
 * <p>So the invariant is stated as what it actually is, and it is the stronger reading: <b>for each
 * layout, the reported position names a file that exists and a line whose text really does contain the
 * typo'd key.</b> That is the property the per-declaration document exists to produce — a message whose
 * position is truthful — and it is checkable by reading the file back at the line the message claims.
 * A version of this that only compared line <i>numbers</i> would pass with the numbers pointing into the
 * wrong document entirely, which is precisely the failure that motivated the refactor.
 *
 * <h2>The trap this file also pins</h2>
 *
 * <p>{@link GroupOrder} is here because converting a pack is not a no-op in one respect, and it is the
 * one a person would not predict: version 1's group order is the order the groups appear in the file,
 * and version 2's is the order the <b>folders</b> sort. So a pack whose groups were written out of
 * alphabetical order reorders itself when converted — which moves the default chapter, the log, and the
 * preview's chapter indices. It is deliberate and unavoidable (there is no file above the group folders
 * to declare an order) and the test exists so that it is a thing the codebase says rather than a thing
 * somebody discovers.
 */
@DisplayName("a chapter, written both ways")
class QuestFormatMigrationTest {

    @BeforeAll
    static void bootVanilla() {
        // The validator resolves item ids against BuiltInRegistries.ITEM, and an unbootstrapped registry
        // throws from inside a vanilla class initialiser rather than reporting anything useful.
        MinecraftTestBootstrap.boot();
    }

    // ------------------------------------------------------------------
    // The fixtures: one chapter, two layouts
    // ------------------------------------------------------------------

    /** The three quests of the chapter, without the layout-specific wrapper either format needs. */
    private static final List<String> CHAPTER_QUESTS = List.of(
            """
            {
              "id": "punch_a_tree",
              "title": "Punch a Tree",
              "description": ["Everything worth having starts with a tree."],
              "icon": { "item": "minecraft:oak_log" },
              "x": 0, "y": 0,
              "tasks": [ { "type": "tenet:item", "item": "minecraft:oak_log", "count": 8 } ],
              "rewards": [ { "type": "tenet:item", "item": "minecraft:wooden_axe", "count": 1 } ]
            }
            """,
            """
            {
              "id": "make_a_table",
              "title": "Somewhere to Work",
              "subtitle": "A crafting table",
              "icon": { "item": "minecraft:crafting_table" },
              "x": 132, "y": 0,
              "dependsOn": ["punch_a_tree"],
              "tasks": [ { "type": "tenet:item", "item": "minecraft:crafting_table", "count": 1 } ],
              "rewards": [ { "type": "tenet:xp", "amount": 5 } ]
            }
            """,
            """
            {
              "id": "stone_tools",
              "title": "Stone Tools",
              "icon": { "item": "minecraft:stone_pickaxe" },
              "x": 264, "y": 0, "shape": "hexagon",
              "repeatable": true, "repeatCooldownTicks": 600,
              "dependsOn": ["make_a_table"],
              "tasks": [
                { "type": "tenet:item", "item": "minecraft:cobblestone", "count": 3 },
                { "type": "tenet:checkmark", "title": "Still here", "optional": true }
              ],
              "rewards": [ { "type": "tenet:item", "item": "minecraft:stone_pickaxe", "count": 1 } ]
            }
            """);

    /** The chapter's own fields, minus its quest list — the part both layouts declare identically. */
    private static final String CHAPTER_HEAD = """
              "id": "first_steps",
              "title": "First Steps",
              "description": ["Where everybody starts.", "", "No, really."],
              "icon": { "item": "minecraft:crafting_table" },
              "progressionMode": "linear",
              "defaultPrerequisiteMode": "one_completed",
              "defaultConsumeItems": true,
              "theme": "copper",
            """;

    /** The group's own fields, minus its chapter list. */
    private static final String GROUP_HEAD = """
              "id": "getting_started",
              "title": "Getting Started",
              "description": "Where everybody starts.",
              "collapsedByDefault": true,
            """;

    /**
     * The same chapter as one version-1 file: everything nested, one document.
     *
     * @param questJsons the quests to inline
     */
    private static String flatFile(List<String> questJsons) {
        return """
                {
                  "$schema": "../tenet-quests.schema.json",
                  "version": 1,
                  "chapterGroups": [
                    {
                %s
                      "chapters": [
                        {
                %s
                          "quests": [ %s ]
                        }
                      ]
                    }
                  ]
                }
                """.formatted(GROUP_HEAD.indent(6), CHAPTER_HEAD.indent(8), String.join(", ", questJsons));
    }

    /** Writes the same chapter as a folder tree, and returns the config directory it went into. */
    private static Path writeFolders(Path configDir, List<String> questJsons) throws IOException {
        Path group = questRoot(configDir).resolve("getting_started");
        Path chapter = group.resolve("first_steps");
        Files.createDirectories(chapter);

        Files.writeString(group.resolve(QuestFiles.GROUP_MANIFEST), """
                {
                  "$schema": "../_schema/group.schema.json",
                %s
                  "chapters": ["first_steps"]
                }
                """.formatted(GROUP_HEAD.indent(2).stripTrailing()));

        // The quest file names the manifest declares, in order. Named after the quest ids so a failure
        // message names something a reader can recognise, and in the same order as the flat fixture so
        // the two trees are comparable position by position.
        List<String> names = List.of("punch_a_tree.json", "make_a_table.json", "stone_tools.json");

        Files.writeString(chapter.resolve(QuestFiles.CHAPTER_MANIFEST), """
                {
                  "$schema": "../../_schema/chapter.schema.json",
                %s
                  "quests": ["%s"]
                }
                """.formatted(CHAPTER_HEAD.indent(2).stripTrailing(), String.join("\", \"", names)));

        for (int i = 0; i < names.size(); i++) {
            Files.writeString(chapter.resolve(names.get(i)), questJsons.get(i));
        }
        return configDir;
    }

    /** Writes the same chapter as a single flat file, and returns the config directory it went into. */
    private static Path writeFlat(Path configDir, List<String> questJsons) throws IOException {
        Files.createDirectories(questRoot(configDir));
        Files.writeString(questRoot(configDir).resolve("stone_age.json"), flatFile(questJsons));
        return configDir;
    }

    /**
     * A config directory with an empty quest root inside it, and returns the <b>config directory</b>.
     *
     * <h2>Why the return value is not the quest root</h2>
     *
     * <p>Because {@link QuestLoader#load} appends {@code tenet/quests} to whatever it is handed. Handing
     * it the quest root is the mistake this helper's return value exists to prevent, and it is a quiet
     * one: the loader finds no directory, warns, and returns an <b>empty</b> index. Nothing throws. Every
     * comparison then compares two empty trees, which passes.
     *
     * <p>It is worth being blunt about that failure mode, because this helper had it and the test did not
     * notice: ten assertions failed, but the one that actually named the problem was
     * {@code theComparisonCoversTheWholeTree} insisting the render have five lines in it. The equality
     * assertion at the centre of the acceptance test — the one this whole file is for — passed happily
     * while comparing nothing to nothing. Which is the argument for the anti-vacuity guard, made by the
     * guard.
     */
    private static Path configDir(Path configDir) throws IOException {
        Files.createDirectories(questRoot(configDir));
        return configDir;
    }

    /**
     * The quest root inside a config directory: where files are written, and what a display name is
     * relative to.
     *
     * <p>Kept as its own accessor because two callers legitimately want opposite ends of this path: the
     * loader wants the config directory, and anything that writes a file directly — or resolves a
     * problem's {@code file()} — wants the root.
     */
    private static Path questRoot(Path configDir) {
        return configDir.resolve(QuestLoader.DIRECTORY);
    }

    // ------------------------------------------------------------------
    // The comparison itself
    // ------------------------------------------------------------------

    /**
     * The whole tree as text, one line per property, so a difference names the field it is in.
     *
     * <p>Walks {@code groups()} → {@code chapters()} → {@code quests()}, which is <b>declaration
     * order</b> — and for a single group that order is the same in both layouts, so the two renders are
     * comparable line for line. That is exactly why this fixture has one group: see {@link GroupOrder}
     * for what a second one would do to the comparison.
     *
     * <p>Every field a quest, a chapter or a group can carry is in here, deliberately. A comparison that
     * checked the ids and titles would pass while a conversion silently dropped
     * {@code defaultConsumeItems}, which is a file that loads and behaves differently — the worse
     * outcome, because nothing reports it.
     */
    private static List<String> render(QuestIndex index) {
        List<String> out = new ArrayList<>();
        for (QuestIndex.GroupEntry groupEntry : index.groups()) {
            ChapterGroup group = groupEntry.group();
            out.add("group " + group.id() + " | " + group.title().value()
                    + " | collapsed=" + group.collapsedByDefault()
                    + " | aliases=" + group.aliases()
                    + " | paragraphs=" + group.description().size());
            for (Chapter chapter : group.chapters()) {
                out.add("  chapter " + chapter.id() + " | " + chapter.title().value()
                        + " | " + chapter.progressionMode()
                        + " | " + chapter.defaultPrerequisiteMode()
                        + " | consume=" + chapter.defaultConsumeItems()
                        + " | theme=" + chapter.theme().orElse("-")
                        + " | icon=" + chapter.icon().describe()
                        + " | paragraphs=" + chapter.description().size());
                for (Quest quest : chapter.quests()) {
                    // Every field is labelled, including the ones whose values are short and unmistakable
                    // (`at`, `shape`). An unlabelled value in a list of `|`-separated values is a diff
                    // nobody can read: two layouts disagreeing about a shape would print as one word
                    // changing place in a line of nine, and the reader has to count separators to find
                    // out which field moved. The label costs six characters and names the field.
                    out.add("    quest " + quest.id() + " | " + quest.title().value()
                            + " | subtitle=" + quest.subtitle().map(QuestText::value).orElse("-")
                            + " | at " + quest.layout().x() + "," + quest.layout().y()
                            + " | shape=" + quest.layout().shape()
                            + " | size=" + quest.layout().size()
                            + " | iconScale=" + quest.layout().iconScale()
                            + " | icon=" + quest.icon().describe()
                            + " | depends=" + quest.dependencies().stream().map(QuestRef::id).toList()
                            + " | tasks=" + quest.tasks().size()
                            + " | rewards=" + quest.rewards().size()
                            + " | repeatable=" + quest.repeatable()
                            + " | cooldown=" + quest.repeatCooldownTicks()
                            + " | showTitle=" + quest.showTitle()
                            + " | invisible=" + quest.invisible()
                            + " | paragraphs=" + quest.description().size());
                }
            }
        }
        return out;
    }

    private static List<String> loadAndRender(Path configDir) {
        QuestLoader.Result result = QuestLoader.load(configDir);
        assertTrue(result.ok(), () -> "the fixture did not load cleanly:\n" + renderProblems(result));
        return render(result.index());
    }

    private static String renderProblems(QuestLoader.Result result) {
        return result.problems().all().stream()
                .map(DataProblem::render)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    // ------------------------------------------------------------------
    // The assertion
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("produces the same questline")
    class Identical {

        @Test
        @DisplayName("every field of every group, chapter and quest matches between the two layouts")
        void bothLayoutsProduceTheSameTree(@TempDir Path temp) throws IOException {
            // The acceptance test. Same content, two shapes, one result — and the comparison walks every
            // property a questline has, so a field dropped by either layout fails here rather than
            // becoming a behaviour difference nobody can trace back to the format change.
            List<String> flat = loadAndRender(writeFlat(configDir(temp.resolve("flat")), CHAPTER_QUESTS));
            List<String> folders = loadAndRender(
                    writeFolders(configDir(temp.resolve("folders")), CHAPTER_QUESTS));

            assertEquals(flat, folders,
                    "the same chapter written as a flat file and as a folder produced different trees."
                            + "\n  flat:    " + flat + "\n  folders: " + folders);
        }

        @Test
        @DisplayName("and the comparison is not vacuous — the render has every field in it")
        void theComparisonCoversTheWholeTree(@TempDir Path temp) throws IOException {
            // A guard on the test above, and it is the one that matters most in a file like this: if the
            // fixture stopped loading, or both loads returned an empty tree, `assertEquals(List.of(),
            // List.of())` would pass and report that the two layouts agree. Asserting the shape of what
            // was compared is what makes the equality above mean something.
            List<String> rendered = loadAndRender(
                    writeFolders(configDir(temp.resolve("v2")), CHAPTER_QUESTS));

            assertEquals(1 + 1 + 3, rendered.size(),
                    "one group, one chapter and three quests, and nothing else: " + rendered);
            assertTrue(rendered.get(0).startsWith("group getting_started"), rendered.get(0));
            assertTrue(rendered.get(1).startsWith("  chapter first_steps"), rendered.get(1));

            // And a spot check that the non-default values actually came through, because a render that
            // printed every field as "-" would compare equal to itself.
            assertTrue(rendered.get(0).contains("collapsed=true"), "the group's flag should have survived");
            assertTrue(rendered.get(1).contains("consume=true"), "the chapter's default should have survived");
            assertTrue(rendered.get(1).contains("theme=copper"), "and its theme");

            // The third quest carries the non-default values, so both checks land on the one line. Pinned
            // by name first: an index on its own is how a spot check silently starts checking the wrong
            // row, and the assertion would still pass while proving nothing about the quest intended.
            String thirdQuest = rendered.get(4);
            assertTrue(thirdQuest.contains("quest stone_tools"), thirdQuest);
            assertTrue(thirdQuest.contains("cooldown=600"), "a quest's cooldown: " + thirdQuest);
            assertTrue(thirdQuest.contains("shape=HEXAGON"), "and a non-default shape: " + thirdQuest);
        }

        @Test
        @DisplayName("both layouts report the same quest count and the same lookup answers")
        void lookupsAgreeToo(@TempDir Path temp) throws IOException {
            // The tree comparison walks the lists; this checks the *maps* beside them, which are built
            // from the same walk and could in principle be populated differently. A quest findable in one
            // layout and not the other would break every dependency, every command and every stored
            // progress lookup, while the rendered tree looked identical.
            QuestLoader.Result flat = QuestLoader.load(
                    writeFlat(configDir(temp.resolve("flat")), CHAPTER_QUESTS));
            QuestLoader.Result folders = QuestLoader.load(
                    writeFolders(configDir(temp.resolve("folders")), CHAPTER_QUESTS));

            assertEquals(flat.index().questCount(), folders.index().questCount());
            assertEquals(flat.index().chapterCount(), folders.index().chapterCount());
            assertEquals(flat.index().groupCount(), folders.index().groupCount());

            for (String id : List.of("punch_a_tree", "make_a_table", "stone_tools")) {
                assertTrue(flat.index().quest(id).isPresent(), "flat should find " + id);
                assertTrue(folders.index().quest(id).isPresent(), "folders should find " + id);
                assertEquals(flat.index().quest(id).orElseThrow().chapterId(),
                        folders.index().quest(id).orElseThrow().chapterId(),
                        id + " should resolve to the same chapter in both layouts");
            }

            assertTrue(folders.index().chapter("first_steps").isPresent());
            assertTrue(folders.index().group("getting_started").isPresent());

            // And the dependency resolves, which is the thing that would break if the two layouts
            // disagreed about an id: a dependsOn that points at nothing locks its quest forever, with a
            // message naming a quest that is right there in the file.
            assertTrue(flat.index().quest("make_a_table").orElseThrow().quest().dependencies().stream()
                            .allMatch(ref -> folders.index().quest(ref.id()).isPresent()),
                    "every dependency in the flat tree should resolve in the folder tree");
        }

        @Test
        @DisplayName("a version-1 file is still read, even beside the new folders")
        void theTwoFormatsCoexist(@TempDir Path temp) throws IOException {
            // The formats are not exclusive, and that is a requirement rather than a convenience: a pack
            // converts one group at a time, and the half-converted state has to load.
            Path configDir = configDir(temp);
            writeFolders(configDir, CHAPTER_QUESTS);
            Files.writeString(questRoot(configDir).resolve("legacy.json"), flatFile(List.of(
                    """
                    { "id": "punch_a_tree", "title": "Punch a Tree",
                      "tasks": [ { "type": "tenet:checkmark", "title": "t" } ] }
                    """)));

            QuestLoader.Result result = QuestLoader.load(configDir);

            // A duplicate id, because the fixture's legacy file reuses punch_a_tree -- which is exactly
            // what a half-converted pack would hit, and the message has to name both files.
            assertFalse(result.ok(), "a duplicate id across two formats is an error:\n" + renderProblems(result));
            String problems = renderProblems(result);
            assertTrue(problems.contains("duplicate quest id \"punch_a_tree\""), problems);
            assertTrue(problems.contains("legacy.json") && problems.contains("punch_a_tree.json"),
                    "the message should name both files, since they are the two sides of the clash:\n"
                            + problems);

            // And both formats were read, which is the point: the clash is only visible because a flat
            // file and a folder file were both understood.
            assertTrue(result.index().quest("punch_a_tree").isPresent(),
                    "the first claimant is still in the index, so the tree is usable despite the clash");
        }
    }

    // ------------------------------------------------------------------
    // The trap
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("root order, which is the one thing that changes")
    class GroupOrder {

        /**
         * Two groups, named in the opposite order to the order they are written in.
         *
         * <p>{@code zzz_written_first} appears before {@code aaa_written_second} in the file, and sorts
         * the other way. That is the whole fixture: the two layouts have genuinely different sources of
         * truth for this order, so a pack whose groups are written out of alphabetical order reorders
         * itself when it is converted.
         */
        private static final String OUT_OF_ORDER_FLAT = """
                {
                  "version": 1,
                  "chapterGroups": [
                    { "id": "zzz_written_first", "title": "Written First", "chapters": [] },
                    { "id": "aaa_written_second", "title": "Written Second", "chapters": [] }
                  ]
                }
                """;

        @Test
        @DisplayName("version 1 keeps the order the groups are written in")
        void flatKeepsWrittenOrder(@TempDir Path temp) throws IOException {
            Path configDir = configDir(temp);
            Files.writeString(questRoot(configDir).resolve("two_groups.json"), OUT_OF_ORDER_FLAT);

            QuestLoader.Result result = QuestLoader.load(configDir);

            assertEquals(List.of("zzz_written_first", "aaa_written_second"),
                    result.index().groups().stream().map(entry -> entry.group().id()).toList(),
                    "a flat file's group order is the only order it can express, so it is the one used");
        }

        @Test
        @DisplayName("version 2 uses folder-name order, which need not be the same order")
        void foldersUseNameOrder(@TempDir Path temp) throws IOException {
            // The deliberate difference, and its reason: there is no file above the group folders to
            // declare their order, so the only order available is the one the folders sort in — and it is
            // the only order a person can predict from looking at the directory.
            Path configDir = configDir(temp);
            Path root = questRoot(configDir);
            for (String id : List.of("zzz_written_first", "aaa_written_second")) {
                Files.createDirectories(root.resolve(id));
                Files.writeString(root.resolve(id).resolve(QuestFiles.GROUP_MANIFEST), """
                        { "id": "%s", "title": "%s", "chapters": [] }
                        """.formatted(id, id));
            }

            QuestLoader.Result result = QuestLoader.load(configDir);

            assertEquals(List.of("aaa_written_second", "zzz_written_first"),
                    result.index().groups().stream().map(entry -> entry.group().id()).toList(),
                    "group folders come back in folder-name order, whatever order they were created in");
        }

        @Test
        @DisplayName("so the same two groups come back in different orders, and that is the documented cost")
        void theOrdersDifferAndThatIsKnown(@TempDir Path temp) throws IOException {
            // Asserted as a *difference* rather than left implicit, because it is the one respect in which
            // converting is not a no-op. What it moves: the default chapter (which is the first group's
            // first chapter, so a player's opening screen), the order of `logTree`, and the preview's
            // chapter indices. What it does not move: anything about what a quest means.
            //
            // Worth being plain about the alternative rather than defending this one: version 1 could have
            // been given a manifest at the quest root naming its group folders in order. That is a fourth
            // kind of file and a fourth set of rules, to preserve an order that version 1's own authors
            // had no particular reason to care about — and the one group whose position matters is drawn
            // first by a fresh install either way. So the folder name decides, and this test says so.
            List<String> flat = loadGroupIds(writeFlatTwoGroups(temp.resolve("flat")));
            List<String> folders = loadGroupIds(writeFolderTwoGroups(temp.resolve("folders")));

            assertEquals(List.of("zzz_written_first", "aaa_written_second"), flat);
            assertEquals(List.of("aaa_written_second", "zzz_written_first"), folders);
            assertNotEquals(flat, folders,
                    "if these ever become equal, either the fixtures stopped being out of order or the "
                            + "folder order has stopped being folder-name order -- and the note above is "
                            + "then stale");
        }

        @Test
        @DisplayName("and the chapters within a group are unaffected, because the manifest declares those")
        void chapterOrderIsDeclared(@TempDir Path temp) throws IOException {
            // The contrast that makes the group case worth explaining: a group's <i>chapters</i> are listed
            // in its manifest, so their order is author-chosen and converting cannot move them. Only the
            // top level has no file above it, and only the top level is therefore decided by name.
            Path configDir = configDir(temp);
            Path group = questRoot(configDir).resolve("getting_started");
            Files.createDirectories(group);
            Files.writeString(group.resolve(QuestFiles.GROUP_MANIFEST), """
                    { "id": "getting_started", "title": "Getting Started",
                      "chapters": ["zzz_second", "aaa_first"] }
                    """);
            for (String id : List.of("zzz_second", "aaa_first")) {
                Files.createDirectories(group.resolve(id));
                Files.writeString(group.resolve(id).resolve(QuestFiles.CHAPTER_MANIFEST), """
                        { "id": "%s", "title": "%s", "quests": [] }
                        """.formatted(id, id));
            }

            QuestLoader.Result result = QuestLoader.load(configDir);

            assertEquals(List.of("zzz_second", "aaa_first"),
                    result.index().chapters().stream().map(entry -> entry.chapter().id()).toList(),
                    "the manifest's order, not the folders' -- which is what makes it the author's");
        }

        private static Path writeFlatTwoGroups(Path temp) throws IOException {
            Path configDir = configDir(temp);
            Files.writeString(questRoot(configDir).resolve("two_groups.json"), OUT_OF_ORDER_FLAT);
            return configDir;
        }

        private static Path writeFolderTwoGroups(Path temp) throws IOException {
            Path configDir = configDir(temp);
            Path root = questRoot(configDir);
            for (String id : List.of("zzz_written_first", "aaa_written_second")) {
                Files.createDirectories(root.resolve(id));
                Files.writeString(root.resolve(id).resolve(QuestFiles.GROUP_MANIFEST), """
                        { "id": "%s", "title": "%s", "chapters": [] }
                        """.formatted(id, id));
            }
            return configDir;
        }

        private static List<String> loadGroupIds(Path configDir) {
            QuestLoader.Result result = QuestLoader.load(configDir);
            assertTrue(result.ok(), () -> "fixture should load cleanly:\n" + renderProblems(result));
            return result.index().groups().stream().map(entry -> entry.group().id()).toList();
        }
    }

    // ------------------------------------------------------------------
    // Where a problem points
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a problem's position")
    class Positions {

        /**
         * The deliberate typo: {@code titl} where {@code title} belongs, on a quest that otherwise loads.
         *
         * <p>An unknown field is the right mistake for this, and the reason is in {@code QuestValidator}'s
         * own rationale — it is the one a codec cannot report at all, because a codec ignores fields it
         * does not recognise. So it is the mistake whose position only this project's validator can
         * produce, which makes it the mistake worth proving.
         */
        private static final String TYPO_QUEST = """
                {
                  "id": "make_a_table",
                  "titl": "Somewhere to Work"
                }
                """;

        @Test
        @DisplayName("names a file that exists and a line that really carries the typo, in both layouts")
        void bothLayoutsPointAtATrueLine(@TempDir Path temp) throws IOException {
            // Stated as truthfulness rather than as string equality, and the reason is in the class note:
            // a quest is a file of its own in version 2, so the two file names cannot be equal, and a test
            // asserting they were would be asserting the change had not been made.
            //
            // What *is* checkable, and is much stronger than comparing numbers: the message names a file
            // on disk, and the line it names contains the key it is complaining about. That is the
            // property the whole per-declaration document exists for — with one document per file, a
            // position is a position in the file that was read rather than in a tree that exists nowhere.
            Path flatConfig = configDir(temp.resolve("flat"));
            writeFlat(flatConfig, List.of(CHAPTER_QUESTS.get(0), TYPO_QUEST, CHAPTER_QUESTS.get(2)));

            Path folderConfig = configDir(temp.resolve("folders"));
            writeFolders(folderConfig, List.of(CHAPTER_QUESTS.get(0), TYPO_QUEST, CHAPTER_QUESTS.get(2)));

            DataProblem fromFlat = typoProblem(QuestLoader.load(flatConfig));
            DataProblem fromFolders = typoProblem(QuestLoader.load(folderConfig));

            assertProblemIsTrue(fromFlat, questRoot(flatConfig), "flat");
            assertProblemIsTrue(fromFolders, questRoot(folderConfig), "folders");

            // And they are genuinely different positions, which is the feature rather than a shortcoming:
            // the version-1 message can only name the whole flat file, because the quest has no file of
            // its own to name.
            assertNotEquals(fromFlat.file(), fromFolders.file(),
                    "the two messages should name different files -- version 2 puts each quest in its own, "
                            + "so a message that named the same file in both would mean the position was "
                            + "still being computed from the tree rather than from the document");
        }

        @Test
        @DisplayName("and in the folder layout it names the quest's own file, not the manifestation it came from")
        void theFolderLayoutNamesTheQuestFile(@TempDir Path temp) throws IOException {
            // The specific win, asserted on its own: a problem inside one quest names that quest's file.
            // Under the old arrangement — one document per file, with positions computed as
            // `$.chapterGroups[g].chapters[c].quests[q]` — a quest in a version-2 file has no such path,
            // so the position would have fallen back to the root of whatever document happened to be
            // first, and the reader would have been sent to a group manifest to fix a quest typo.
            Path config = configDir(temp.resolve("v2"));
            writeFolders(config, List.of(CHAPTER_QUESTS.get(0), TYPO_QUEST, CHAPTER_QUESTS.get(2)));

            DataProblem problem = typoProblem(QuestLoader.load(config));

            assertEquals("getting_started/first_steps/make_a_table.json", problem.file(),
                    "the typo is in that file and nowhere else: " + problem.render());
            assertTrue(Files.exists(questRoot(config).resolve(problem.file())),
                    problem.file() + " should be a file an author can open");
        }

        @Test
        @DisplayName("a problem in a chapter manifest names the chapter manifest, at its own line")
        void aManifestProblemNamesItsOwnFile(@TempDir Path temp) throws IOException {
            // The other half of the per-declaration arrangement: a chapter's own mistake, in the chapter's
            // own document. Under one-document-per-file this would have had to be reported against
            // whatever the group manifest was, which is a file with nothing wrong in it.
            Path config = configDir(temp.resolve("v2"));
            Path root = questRoot(config);
            Path group = root.resolve("getting_started");
            Path chapter = group.resolve("first_steps");
            Files.createDirectories(chapter);

            Files.writeString(group.resolve(QuestFiles.GROUP_MANIFEST), """
                    { "id": "getting_started", "title": "Getting Started", "chapters": ["first_steps"] }
                    """);
            Files.writeString(chapter.resolve(QuestFiles.CHAPTER_MANIFEST), """
                    {
                      "id": "first_steps",
                      "title": "First Steps",
                      "progressionMode": "lineaer",
                      "quests": []
                    }
                    """);

            QuestLoader.Result result = QuestLoader.load(config);

            assertFalse(result.ok(), renderProblems(result));
            DataProblem problem = result.problems().all().stream()
                    .filter(candidate -> candidate.message().contains("progressionMode")
                            || candidate.message().contains("is not one of"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no problem about the enum:\n"
                            + renderProblems(result)));

            assertEquals("getting_started/first_steps/chapter.json", problem.file(),
                    "the misspelling is in that file: " + problem.render());
            assertLineContains(root, problem, "lineaer");
        }

        /** Reads a file back at the line a problem claims, and asserts the claimed text is really there. */
        private static void assertLineContains(Path root, DataProblem problem, String expected) {
            Path file = root.resolve(problem.file());
            assertTrue(Files.exists(file), problem.file() + " does not exist, so the message names "
                    + "a file an author cannot open");

            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            }
            catch (IOException e) {
                throw new AssertionError("could not read back " + problem.file(), e);
            }

            assertTrue(problem.line() >= 1 && problem.line() <= lines.size(),
                    problem.file() + " has " + lines.size() + " line(s) and the message claims line "
                            + problem.line());

            String claimed = lines.get(problem.line() - 1);
            assertTrue(claimed.contains(expected),
                    "the message points at " + problem.file() + ":" + problem.line()
                            + ", and that line is:\n    " + claimed
                            + "\nwhich does not contain '" + expected + "'. The reported position has to be "
                            + "a line of a file the author can open, or it sends them somewhere the mistake "
                            + "is not.");
        }

        private static void assertProblemIsTrue(DataProblem problem, Path root, String layout) {
            assertTrue(problem.severity() == DataProblem.Severity.ERROR,
                    layout + ": an unknown field is fatal, got " + problem.severity());
            assertLineContains(root, problem, "titl");
        }

        /**
         * The one problem that is <i>about the typo'd key</i>, picked out of the two a typo produces.
         *
         * <h2>Why the filter has to name the message and not the word</h2>
         *
         * <p>Because a misspelt {@code titl} produces <b>two</b> problems, and {@code "titl"} is a
         * substring of {@code "title"}. So does the filter here, which means a filter looking for
         * {@code "titl"} matches both of them:
         *
         * <ul>
         *   <li>{@code unknown field "titl"} — at the typo'd key, which is the problem this test is
         *       about, and the one whose position the per-declaration document exists to get right.</li>
         *   <li>{@code missing required field title} — at the enclosing object, because there is no
         *       position for a field that is not there. {@link
         *       dev.ellipog.armature.api.data.JsonDocument#nearestLocation} walks up to the object that
         *       should have held it, which is the right answer to a question the reader is asking
         *       ("where is this object?") and the wrong one for this assertion.</li>
         * </ul>
         *
         * <p>Both are correct, and they arrive sorted by line — so a loose filter takes whichever is
         * nearer the top of the file and reports a position that belongs to the other one. That is not
         * hypothetical: this helper did exactly that, and the assertion it fed then failed on a
         * <b>correct</b> message and read like a bug in the position code. Hence matching the whole
         * message, which cannot be satisfied by the wrong problem.
         *
         * <p>Worth keeping the note: a test that goes looking for a problem by the text of the mistake
         * has to be specific about <i>which</i> problem it means, because the format reports the same
         * mistake from two angles and one of those angles reports at the enclosing object by design.
         */
        private static DataProblem typoProblem(QuestLoader.Result result) {
            assertFalse(result.ok(), () -> "the typo should have been reported at all:\n"
                    + renderProblems(result));
            return result.problems().all().stream()
                    .filter(candidate -> candidate.message().contains("unknown field \"titl\""))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no problem naming the typo'd key:\n"
                            + renderProblems(result)));
        }
    }
}
