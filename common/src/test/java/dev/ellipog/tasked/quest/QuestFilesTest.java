package dev.ellipog.tasked.quest;

import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.armature.api.data.Problems;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The folder rules, asserted against a real directory.
 *
 * <h2>Why this is worth more here than anywhere else in the loader</h2>
 *
 * <p>Because everything {@link QuestFiles} does, it does by producing an <i>error message</i>. A
 * validator has tests that a file decodes; this has tests that a message says the right thing about
 * the right file, and a message is the one artefact in a codebase that no compiler, no signature and
 * no reading can check. An author who has moved a chapter folder and typed the old name into a
 * manifest gets exactly one piece of output to work from, and if it names the wrong side of the
 * disagreement it sends them to the wrong file.
 *
 * <h2>A real temporary directory, not a mocked filesystem</h2>
 *
 * <p>Because most of what is being tested is <i>the filesystem agreeing with itself</i>: whether a
 * name in a manifest resolves to a folder that is actually there, whether an underscore-prefixed
 * folder is walked into, whether two folders come back in name order. A mock would be a description
 * of a filesystem written by whoever wrote the mock, so it would pass the tests the implementation
 * already passes and fail exactly the ones nobody thought of.
 *
 * <h2>Every fixture is written out in full</h2>
 *
 * <p>No helper that conjures a valid group from a couple of arguments. A test here is about a
 * <i>tree shape</i>, and a builder would hide the shape behind a method call — so a case that turns on
 * a folder being listed but absent would read as a builder call rather than as a directory listing.
 * The few lines of JSON each fixture needs are the readable part.
 */
class QuestFilesTest {

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * Writes a file, creating its parent folders.
     *
     * <p>Returns the path so a caller can name it in an assertion. Deliberately not a builder and
     * deliberately not cleverer than this: see the class note.
     */
    private static Path write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    /** A group manifest. {@code chapters} is raw JSON so a test can write a malformed one. */
    private static String group(String id, String chapters) {
        return """
                { "$schema": "../_schema/group.schema.json",
                  "id": "%s",
                  "title": "%s",
                  "chapters": %s }
                """.formatted(id, id, chapters);
    }

    /** A chapter manifest. */
    private static String chapter(String id, String quests) {
        return """
                { "$schema": "../../_schema/chapter.schema.json",
                  "id": "%s",
                  "title": "%s",
                  "quests": %s }
                """.formatted(id, id, quests);
    }

    /** The root manifest. {@code entries} is raw JSON so a test can write a malformed one. */
    private static String index(String entries) {
        return """
                { "$schema": "./_schema/index.schema.json",
                  "entries": %s }
                """.formatted(entries);
    }

    /** A whole quest. Only {@code id} matters to discovery — decoding is the loader's business. */
    private static String quest(String id) {
        return """
                { "$schema": "../../../_schema/quest.schema.json",
                  "id": "%s",
                  "title": "%s" }
                """.formatted(id, id);
    }

    /**
     * The tree every other case is a variation on.
     *
     * <pre>
     *   _schema/group.schema.json     shipped, skipped
     *   getting_started/
     *     group.json                  lists ["first_steps"]
     *     first_steps/
     *       chapter.json              lists ["punch_a_tree.json"]
     *       punch_a_tree.json
     * </pre>
     */
    private static Path wellFormedTree(Path root) throws IOException {
        write(root, "_schema/group.schema.json", "{}");
        write(root, "getting_started/group.json",
                group("getting_started", "[\"first_steps\"]"));
        write(root, "getting_started/first_steps/chapter.json",
                chapter("first_steps", "[\"punch_a_tree.json\"]"));
        write(root, "getting_started/first_steps/punch_a_tree.json", quest("punch_a_tree"));
        return root;
    }

    private static String messages(Problems problems) {
        return problems.all().stream()
                .map(DataProblem::render)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private static void assertMentions(Problems problems, String text) {
        assertTrue(messages(problems).contains(text),
                "expected a problem mentioning '" + text + "', got:" + messages(problems));
    }

    /** The display names of every declaration of one kind, in tree order. */
    private static List<String> displays(QuestFiles.Discovery found, QuestFiles.Kind kind) {
        return found.of(kind).stream().map(QuestFiles.Declaration::display).toList();
    }

    // ------------------------------------------------------------------
    // The happy path, first, so every failure below is a difference from it
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a well-formed tree")
    class WellFormed {

        @Test
        @DisplayName("is found, clean, and in tree order")
        void aWellFormedTreeIsFoundClean(@TempDir Path root) throws IOException {
            // Asserted before any failure case, because every failure below is defined as a difference
            // from this -- and a suite where only the failures are pinned can pass while the successful
            // path produces nothing at all.
            QuestFiles.Discovery found = QuestFiles.discover(wellFormedTree(root));

            assertTrue(found.ok(), messages(found.problems()));
            assertTrue(found.problems().isEmpty(),
                    "a clean tree should warn about nothing either:" + messages(found.problems()));

            assertEquals(List.of("getting_started/group.json"), displays(found, QuestFiles.Kind.GROUP));
            assertEquals(List.of("getting_started/first_steps/chapter.json"),
                    displays(found, QuestFiles.Kind.CHAPTER));
            assertEquals(List.of("getting_started/first_steps/punch_a_tree.json"),
                    displays(found, QuestFiles.Kind.QUEST));
        }

        @Test
        @DisplayName("gives each declaration the folder it came from and the thing above it")
        void declarationsCarryTheirLineage(@TempDir Path root) throws IOException {
            // The fields a caller rebuilds the tree from. A declaration whose parentDisplay was wrong
            // would place a chapter under the wrong group with nothing in the tree looking amiss, so
            // these are asserted rather than assumed.
            QuestFiles.Discovery found = QuestFiles.discover(wellFormedTree(root));

            QuestFiles.Declaration group = found.of(QuestFiles.Kind.GROUP).get(0);
            assertNull(group.parentDisplay(), "a group is at the root, so it has nothing above it");
            assertEquals("getting_started", group.folderName());
            assertEquals("getting_started", group.declaredId());
            assertEquals(List.of("first_steps"), group.declaredChildren());

            QuestFiles.Declaration chap = found.of(QuestFiles.Kind.CHAPTER).get(0);
            assertEquals("getting_started/group.json", chap.parentDisplay());
            assertEquals("first_steps", chap.folderName());
            assertEquals(List.of("punch_a_tree.json"), chap.declaredChildren());

            QuestFiles.Declaration leaf = found.of(QuestFiles.Kind.QUEST).get(0);
            assertEquals("getting_started/first_steps/chapter.json", leaf.parentDisplay(),
                    "a quest hangs off its chapter's manifest, not off the folder");
            assertEquals("punch_a_tree", leaf.declaredId());
        }

        @Test
        @DisplayName("is depth-first: a group, then its chapters, then each chapter's quests")
        void declarationsAreDepthFirst(@TempDir Path root) throws IOException {
            // The order the loader decodes in, and the order a walker with a stack by parentDisplay
            // reconstructs the tree from. Worth pinning because it is not obvious from the code that
            // walks the folder -- the group is pushed *before* its children are visited, which is what
            // makes it depth-first rather than breadth-first.
            write(root, "g/group.json", group("g", "[\"one\", \"two\"]"));
            write(root, "g/one/chapter.json", chapter("one", "[\"a.json\"]"));
            write(root, "g/one/a.json", quest("a"));
            write(root, "g/two/chapter.json", chapter("two", "[\"b.json\"]"));
            write(root, "g/two/b.json", quest("b"));

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertEquals(List.of(
                            "g/group.json",
                            "g/one/chapter.json",
                            "g/one/a.json",
                            "g/two/chapter.json",
                            "g/two/b.json"),
                    found.declarations().stream().map(QuestFiles.Declaration::display).toList());
        }

        @Test
        @DisplayName("reports the declared order of quests, which is a LINEAR chapter's progression")
        void declaredQuestOrderIsKept(@TempDir Path root) throws IOException {
            // Not cosmetic. `Chapter.indexOf` compares by reference, so a chapter assembled from files
            // in a different order than the manifest declares produces a LINEAR chapter whose list is
            // not the progression -- and it fails *open*, unlocking every quest at once, with no error
            // and no log line. The order this returns is the only thing that stands between those two.
            write(root, "g/group.json", group("g", "[\"one\"]"));
            write(root, "g/one/chapter.json", chapter("one", "[\"third.json\", \"first.json\", \"second.json\"]"));
            write(root, "g/one/third.json", quest("third"));
            write(root, "g/one/first.json", quest("first"));
            write(root, "g/one/second.json", quest("second"));

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertEquals(List.of("g/one/third.json", "g/one/first.json", "g/one/second.json"),
                    displays(found, QuestFiles.Kind.QUEST),
                    "the manifest's order, not the folder's -- which is what makes it the progression");
            assertEquals(List.of("third.json", "first.json", "second.json"),
                    found.of(QuestFiles.Kind.CHAPTER).get(0).declaredChildren());
        }

        @Test
        @DisplayName("an absent root is an empty discovery, not an error")
        void anAbsentRootIsNotAnError(@TempDir Path root) {
            // QuestLoader reports an absent directory, because it is the caller that knows what that
            // means for a fresh install. This class's contract is "say what is there", and "nothing is
            // there" is a valid answer to it -- so a second message here would double the report.
            QuestFiles.Discovery found = QuestFiles.discover(root.resolve("nowhere"));

            assertTrue(found.ok());
            assertTrue(found.problems().isEmpty());
            assertTrue(found.declarations().isEmpty());
            assertEquals(0, found.size());
        }
    }

    // ------------------------------------------------------------------
    // The rule that keeps the mod's own folder from being an error
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("underscore-prefixed names")
    class Underscore {

        @Test
        @DisplayName("a shipped _schema folder is skipped, and produces nothing at all")
        void theSchemaFolderIsInvisible(@TempDir Path root) throws IOException {
            // The trap this rule exists for, and it is the difference between a mod that starts and a
            // mod that error-walls on a folder it shipped itself. `_schema/` sits at the root beside the
            // content, so a walker without this rule reports every file inside it as unlisted content --
            // "your install is broken", about a directory the mod wrote.
            QuestFiles.Discovery found = QuestFiles.discover(wellFormedTree(root));

            assertTrue(found.problems().isEmpty(), messages(found.problems()));
            assertFalse(displays(found, QuestFiles.Kind.FLAT_V1).contains("_schema/group.schema.json"),
                    "the schema file must not be reported as a version-1 quest file");
            assertEquals(3, found.size(),
                    "one group, one chapter and one quest -- nothing from _schema at all");
        }

        @Test
        @DisplayName("an underscore-prefixed folder inside a group is skipped rather than reported unlisted")
        void notesBesideContentAreSkipped(@TempDir Path root) throws IOException {
            // The convention's second use, and the one an author reaches for: a folder of notes beside
            // the chapters it describes. Reported as unlisted, it would make the convention useless
            // exactly where it is most wanted.
            write(root, "g/group.json", group("g", "[\"one\"]"));
            write(root, "g/one/chapter.json", chapter("one", "[]"));
            write(root, "g/_notes/scratch.json", "{}");
            write(root, "g/_todo.txt", "write more chapters");

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertTrue(found.problems().isEmpty(),
                    "a `_`-prefixed folder and file beside content are skipped, not reported:"
                            + messages(found.problems()));
        }

        @Test
        @DisplayName("but a manifest naming an underscore-prefixed child is an error, because the walk skips it")
        void declaringAnIgnoredNameIsAnError(@TempDir Path root) throws IOException {
            // The other direction, and the reason `resolveSibling` refuses an ignored name even when
            // something of that name is sitting there. It would be skipped everywhere else, so a
            // declaration pointing at it is a reference to something the rest of the system cannot see.
            write(root, "g/group.json", group("g", "[\"_hidden\"]"));
            write(root, "g/_hidden/chapter.json", chapter("_hidden", "[]"));

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertFalse(found.ok(), "declaring an ignored name is fatal:" + messages(found.problems()));
            assertMentions(found.problems(), "_hidden");
            assertEmpty(found, QuestFiles.Kind.CHAPTER,
                    "and the chapter it names is not walked, because nothing can refer to it");
        }
    }

    // ------------------------------------------------------------------
    // Folder name versus declared id
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("folder name and declared id")
    class FolderNameIsTheId {

        @Test
        @DisplayName("a group folder whose manifest disagrees names both sides")
        void aGroupFolderNameMismatchNamesBothSides(@TempDir Path root) throws IOException {
            // Drift in the one place a reader would never look: the tree and the manifest agree about
            // everything except which thing they are describing. A message naming only one side sends
            // the reader to check a file that is already correct.
            write(root, "getting_started/group.json", group("first_steps", "[\"one\"]"));

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertFalse(found.ok());
            assertMentions(found.problems(), "declares id \"first_steps\"");
            assertMentions(found.problems(), "is called \"getting_started\"");
        }

        @Test
        @DisplayName("a chapter folder whose manifest disagrees does too")
        void aChapterFolderNameMismatchNamesBothSides(@TempDir Path root) throws IOException {
            write(root, "g/group.json", group("g", "[\"one\"]"));
            write(root, "g/one/chapter.json", chapter("two", "[]"));

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertFalse(found.ok());
            assertMentions(found.problems(), "declares id \"two\"");
            assertMentions(found.problems(), "is called \"one\"");
        }

        @Test
        @DisplayName("and the folder is still read, with the folder name winning")
        void aMismatchStillReadsTheFolder(@TempDir Path root) throws IOException {
            // The deliberate half of the choice, and the one worth pinning: the folder name is what
            // every path in the tree is built from, so it is the one that has to be right. Skipping the
            // group entirely would hide the rest of an otherwise-fine questline behind one wrong string
            // -- and would take the chapter's own errors down with it.
            write(root, "getting_started/group.json", group("first_steps", "[\"one\"]"));
            write(root, "getting_started/one/chapter.json", chapter("one", "[\"a.json\"]"));
            write(root, "getting_started/one/a.json", quest("a"));

            QuestFiles.Discovery found = QuestFiles.discover(root);
            QuestFiles.Declaration group = found.of(QuestFiles.Kind.GROUP).get(0);

            // Both halves, kept apart, because the whole point of reporting a mismatch is naming the
            // two sides of it: `declaredId` is what the file wrote and `folderName` is where it sits.
            // Collapsing them into one accessor would make the message unable to say which was which.
            assertEquals("first_steps", group.declaredId(), "what the manifest says");
            assertEquals("getting_started", group.folderName(), "and where the manifest actually is");

            // And the one that *wins*, which is the assertion this test exists for. Every path in the
            // tree is built from the folder, so `id()` is the folder name -- and if it were the
            // declared string instead, a group whose manifest had a typo would be filed under an id
            // that no path on disk agrees with.
            assertEquals("getting_started", group.id(),
                    "the folder name is what the tree is built from, so it is what id() reports");

            assertEquals(List.of("getting_started/one/a.json"), displays(found, QuestFiles.Kind.QUEST),
                    "and the quests under it still load -- one wrong string is not a dead group");
        }

        @Test
        @DisplayName("a manifest with no id at all is an error, and nothing is walked below it")
        void aMissingIdIsFatal(@TempDir Path root) throws IOException {
            // Unlike a mismatch, this one has no folder name to fall back on for the *message* -- the
            // field the whole check is about is absent -- and a group without an id cannot be referred
            // to by anything.
            write(root, "g/group.json", """
                    { "title": "no id here", "chapters": ["one"] }
                    """);
            write(root, "g/one/chapter.json", chapter("one", "[]"));

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertFalse(found.ok(), messages(found.problems()));
            assertMentions(found.problems(), "missing required field id");
            assertEmpty(found, QuestFiles.Kind.CHAPTER,
                    "nothing under a group with no id is walked, because nothing could name it");
        }
    }

    // ------------------------------------------------------------------
    // Manifest drift, both directions
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("manifest drift")
    class Drift {

        @Test
        @DisplayName("a child declared in the manifest and absent from the tree is an error")
        void aDanglingDeclarationIsAnError(@TempDir Path root) throws IOException {
            // One direction: the manifest names a folder nobody created. Reported with the name, the
            // folder it was resolved against, and what was expected -- three things, because "a folder
            // is missing" without them is a message that sends the reader through a directory listing.
            write(root, "g/group.json", group("g", "[\"one\", \"missing\"]"));
            write(root, "g/one/chapter.json", chapter("one", "[\"a.json\"]"));
            write(root, "g/one/a.json", quest("a"));

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertFalse(found.ok());
            assertMentions(found.problems(), "\"missing\"");
            assertMentions(found.problems(), "is declared here");
            assertMentions(found.problems(), "folder");

            // And the rest of the group still loads. One bad reference is not a dead group -- which is
            // the difference this makes to an author with a half-written questline.
            assertEquals(List.of("g/one/a.json"), displays(found, QuestFiles.Kind.QUEST));
        }

        @Test
        @DisplayName("and it is reported against the name that is not there, not against the manifest")
        void aDanglingDeclarationIsReportedAgainstTheChild(@TempDir Path root) throws IOException {
            // **The assertion the loader's per-file gate rests on.** `QuestLoader.assemble` refuses a
            // declaration whose own file carries an error -- so if this problem is *filed* against
            // `g/group.json`, one deleted chapter folder costs the group and every chapter it lists,
            // and one deleted quest file costs the chapter. Filing it against the child is the whole
            // fix, it is a single field on the problem, and nothing else in this suite can see it.
            write(root, "g/group.json", group("g", "[\"one\", \"missing\"]"));
            write(root, "g/one/chapter.json", chapter("one", "[\"a.json\"]"));
            write(root, "g/one/a.json", quest("a"));

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertEquals(List.of("g/missing"), found.problems().all().stream()
                            .filter(problem -> problem.severity() == DataProblem.Severity.ERROR)
                            .map(DataProblem::file)
                            .distinct()
                            .toList(),
                    "the error names the folder that is not there, and only that folder. A problem "
                            + "filed against g/group.json is what drops the group:\n"
                            + messages(found.problems()));
            assertEquals(List.of("g/one/a.json"), displays(found, QuestFiles.Kind.QUEST),
                    "and the chapter that is there is still read");
        }

        @Test
        @DisplayName("a child present in the tree and absent from the manifest is an error")
        void anUnlistedChildIsAnError(@TempDir Path root) throws IOException {
            // The other direction, and the quieter one: a folder that is *there* and not mentioned. Its
            // quests will never load, and from inside the file that holds them nothing looks wrong --
            // which is why the message says what the consequence is rather than only naming the folder.
            write(root, "g/group.json", group("g", "[\"one\"]"));
            write(root, "g/one/chapter.json", chapter("one", "[]"));
            write(root, "g/two/chapter.json", chapter("two", "[]"));

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertFalse(found.ok());
            assertMentions(found.problems(), "\"two\"");
            assertMentions(found.problems(), "will never load");

            // And the unlisted chapter was not read, which is the half of the message that matters.
            // The walk visits the names a manifest *declares*, so an unreferenced folder is not merely
            // unreported -- it is genuinely never opened, and asserting that here is what stops a later
            // change from "helpfully" reading it and leaving the message describing a file that loaded.
            assertEquals(List.of("g/group.json", "g/one/chapter.json"),
                    found.declarations().stream().map(QuestFiles.Declaration::display).toList(),
                    "the unlisted chapter folder should not have been walked at all");
        }
    }

    // ------------------------------------------------------------------
    // Order at the top, which nothing declares
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("root order")
    class RootOrder {

        @Test
        @DisplayName("groups come back in folder-name order, because nothing declares one")
        void groupsAreOrderedByFolderName(@TempDir Path root) throws IOException {
            // There is no file above the group folders to declare their order, so folder-name order is
            // the only order available and the only one a person can predict.
            //
            // The consequence is worth stating where the rule is, because it surprises the first time:
            // **renaming a group folder can reorder the book**, and the default chapter with it. That is
            // why this is asserted rather than left to the file system's own order, which is neither
            // stable nor alphabetical.
            write(root, "zzz_last/group.json", group("zzz_last", "[]"));
            write(root, "aaa_first/group.json", group("aaa_first", "[]"));
            write(root, "mmm_middle/group.json", group("mmm_middle", "[]"));

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertTrue(found.ok(), messages(found.problems()));
            assertEquals(List.of("aaa_first/group.json", "mmm_middle/group.json", "zzz_last/group.json"),
                    displays(found, QuestFiles.Kind.GROUP));
        }

        @Test
        @DisplayName("and a file mixed in with the folders does not disturb it")
        void aFlatFileDoesNotDisturbTheOrder(@TempDir Path root) throws IOException {
            // A version-1 file can sit at the root beside group folders, because the two formats are
            // meant to coexist forever. So the walk has to handle both in one pass and report each as
            // its own kind -- not choose one format for the directory.
            write(root, "aaa_first/group.json", group("aaa_first", "[]"));
            write(root, "legacy.json", """
                    { "version": 1, "chapterGroups": [] }
                    """);
            write(root, "zzz_last/group.json", group("zzz_last", "[]"));

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertTrue(found.ok(), messages(found.problems()));
            assertEquals(List.of("aaa_first/group.json", "zzz_last/group.json"),
                    displays(found, QuestFiles.Kind.GROUP));
            assertEquals(List.of("legacy.json"), displays(found, QuestFiles.Kind.FLAT_V1));
        }
    }

    // ------------------------------------------------------------------
    // Version-1 files, which are read forever
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a version-1 flat file")
    class VersionOne {

        @Test
        @DisplayName("is recognised by being a file at the root, not by its version field")
        void aFlatFileIsRecognisedByPosition(@TempDir Path root) throws IOException {
            // Recognised by *position* rather than by the `version` value, and that is the deliberate
            // choice: a file written before versions existed has no `version` at all, and one written by
            // hand may have it wrong. What actually distinguishes the two formats is whether the root
            // entry is a folder tree or a single document -- so this fixture has no `version` field,
            // which is exactly the shape the version-field rule would have got wrong.
            write(root, "old.json", """
                    { "chapterGroups": [ { "id": "g", "title": "G", "chapters": [] } ] }
                    """);

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertTrue(found.ok(), messages(found.problems()));
            assertEquals(List.of("old.json"), displays(found, QuestFiles.Kind.FLAT_V1));
        }

        @Test
        @DisplayName("carries its document, so a later problem can name a line inside it")
        void aFlatFileKeepsItsDocument(@TempDir Path root) throws IOException {
            // The same argument `LoadedQuestFile` makes at length. Discovery parses; the loader decodes.
            // Throwing the document away here would mean re-reading the file to place a caret, which is
            // two parses of one file that could disagree about a line number.
            String json = """
                    {
                      "chapterGroups": [
                        { "id": "g", "title": "G", "chapters": [] }
                      ]
                    }
                    """;
            write(root, "old.json", json);

            QuestFiles.Declaration flat = QuestFiles.discover(root).of(QuestFiles.Kind.FLAT_V1).get(0);

            assertNotNull(flat.document());
            assertEquals("$.chapterGroups[0].id", flat.document().nearestLocation("$.chapterGroups[0].id").path());

            // The line is *derived from the fixture* rather than written as a number, and the
            // derivation is the stronger assertion of the two: it checks that the parser's idea of a
            // line agrees with the reader's, where a literal only checks that both agree with a guess.
            //
            // The guess was wrong, and how it was wrong is worth keeping. This read `4` and the parser
            // says `3`, because **a text block's content begins on the line after the opening delimiter
            // with no blank line in it** -- the newline after `"""` is not content. So the eye, reading
            // the source, counts one line more than the JSON has. Which is precisely the distinction
            // this test exists to pin: the number a problem prints has to be a line of the *file*, not
            // a line of the Java that happens to contain it.
            int declaredLine = 1;
            for (String line : json.split("\n", -1)) {
                if (line.contains("\"id\": \"g\"")) {
                    break;
                }
                declaredLine++;
            }
            assertEquals(3, declaredLine, "fixture sanity -- the id is on line 3 of the JSON");
            assertEquals(declaredLine, flat.document().nearestLocation("$.chapterGroups[0].id").line(),
                    "the id sits on line " + declaredLine + " of the fixture, so a problem reported here"
                            + " points at the line the author would count in their editor");
        }

        @Test
        @DisplayName("and a flat file that will not parse is reported at its own line")
        void aBrokenFlatFileIsReportedWithItsLine(@TempDir Path root) throws IOException {
            write(root, "broken.json", """
                    {
                      "chapterGroups": [
                    """);

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertFalse(found.ok());
            assertMentions(found.problems(), "broken.json");
            assertEquals(1, found.problems().forFile("broken.json").size(),
                    "one message, not a cascade of them:" + messages(found.problems()));
        }
    }

    // ------------------------------------------------------------------
    // Things that are not the right shape
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("folders that are not what they claim")
    class NotTheRightShape {

        @Test
        @DisplayName("a folder with no group.json says so, and says how to opt out")
        void aFolderWithoutAManifestIsReported(@TempDir Path root) throws IOException {
            // Reported rather than ignored, because a folder of content sitting where content is read is
            // almost certainly meant to be one. The message names the way out -- the `_` prefix -- so
            // the fix does not require guessing a convention.
            write(root, "not_a_group/readme.txt", "I am a folder of notes, not a group");

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertFalse(found.ok());
            assertMentions(found.problems(), "is not a chapter group");
            assertMentions(found.problems(), "there is no group.json");
            assertMentions(found.problems(), "_");
        }

        @Test
        @DisplayName("a chapter folder with no chapter.json says so")
        void aChapterFolderWithoutAManifestIsReported(@TempDir Path root) throws IOException {
            write(root, "g/group.json", group("g", "[\"one\"]"));
            write(root, "g/one/loose_file.txt", "a chapter folder with no manifest in it");

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertFalse(found.ok());
            assertMentions(found.problems(), "is not a chapter");
            assertMentions(found.problems(), "there is no chapter.json");
        }

        @Test
        @DisplayName("a manifest whose child list is an object rather than a list is told which it is")
        void aNonArrayChildListNamesTheFault(@TempDir Path root) throws IOException {
            // The classic hand-editing mistake: an element copied out of the old nested format into a
            // list that now holds names. Reported as "expected a list of names, found an object" rather
            // than as "expected an array", because the second names the wrong fault -- the list is
            // there, and what is in it is a chapter where a name belongs.
            write(root, "g/group.json", group("g", "[ { \"id\": \"one\" } ]"));

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertFalse(found.ok());
            assertMentions(found.problems(), "expected the name of a chapter");
            assertMentions(found.problems(), "found an object");
        }

        @Test
        @DisplayName("a manifest whose chapters key is a string is a different message again")
        void aStringChildListIsItsOwnProblem(@TempDir Path root) throws IOException {
            write(root, "g/group.json", group("g", "\"one.json\""));

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertFalse(found.ok());
            assertMentions(found.problems(), "expected a list of names");
            assertMentions(found.problems(), "found a string");
        }

        @Test
        @DisplayName("a chapter's quests list naming something that is a folder is reported as the wrong kind")
        void theWrongKindOfThingIsReported(@TempDir Path root) throws IOException {
            // A quest `file` that is a folder. Reported by name resolution as the wrong kind of thing,
            // where accepting it would mean the loader tries to read a directory as JSON.
            write(root, "g/group.json", group("g", "[\"one\"]"));
            write(root, "g/one/chapter.json", chapter("one", "[\"a.json\"]"));
            Files.createDirectories(root.resolve("g/one/a.json"));

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertFalse(found.ok());
            assertMentions(found.problems(), "\"a.json\"");
            assertMentions(found.problems(), "is a folder");
            assertEmpty(found, QuestFiles.Kind.QUEST, "and nothing is decoded as a quest");
        }

        @Test
        @DisplayName("a loose file beside a chapter manifest is ignored, because it cannot have been anything else")
        void aLooseFileInAChapterIsIgnored(@TempDir Path root) throws IOException {
            // Asymmetric with the group level on purpose, and the reason is worth stating: beside a
            // chapter manifest the only kind of thing that could have been meant is a quest, and a
            // `.txt` is plainly not one. Beside a *group* manifest the missing thing could have been a
            // chapter folder, so a file there is worth reporting -- it might be a chapter someone
            // flattened by accident.
            write(root, "g/group.json", group("g", "[\"one\"]"));
            write(root, "g/one/chapter.json", chapter("one", "[\"a.json\"]"));
            write(root, "g/one/a.json", quest("a"));
            write(root, "g/one/notes.txt", "scratch");

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertTrue(found.problems().isEmpty(),
                    "a non-JSON file beside a chapter is not content and not an error:"
                            + messages(found.problems()));
        }
    }

    // ------------------------------------------------------------------
    // What the dump needs, and a check that it agrees with the declarations
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the quest list")
    class QuestList {

        @Test
        @DisplayName("every quest file in tree order, which is what the geometry dump is told")
        void questDisplaysAreInTreeOrder(@TempDir Path root) throws IOException {
            // The one thing `GeometryDump`'s tree mode consumes. It exists so the preview is *told*
            // which files are quests rather than learning the folder rules and becoming a third
            // description of them -- so this list is the contract between the two.
            write(root, "b_group/group.json", group("b_group", "[\"one\"]"));
            write(root, "b_group/one/chapter.json", chapter("one", "[\"b1.json\"]"));
            write(root, "b_group/one/b1.json", quest("b1"));
            write(root, "a_group/group.json", group("a_group", "[\"one\"]"));
            write(root, "a_group/one/chapter.json", chapter("one", "[\"a1.json\"]"));
            write(root, "a_group/one/a1.json", quest("a1"));

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertEquals(List.of("a_group/one/a1.json", "b_group/one/b1.json"), found.questDisplays(),
                    "group order first, then declaration order inside each");
            assertEquals(found.of(QuestFiles.Kind.QUEST).size(), found.questDisplays().size(),
                    "and it is the same set as the quest declarations, in the same order");
        }
    }

    @Nested
    @DisplayName("index.json, the manifest that declares the root")
    class IndexManifest {

        @Test
        @DisplayName("declares the order of the groups, overruling folder names")
        void theIndexDeclaresGroupOrder(@TempDir Path root) throws IOException {
            // The whole reason the manifest exists: before it, a group's place was its folder name, so
            // moving one meant renaming it -- which is an id change, not a reorder.
            write(root, "zzz_last/group.json", group("zzz_last", "[]"));
            write(root, "aaa_first/group.json", group("aaa_first", "[]"));
            write(root, "index.json", index("[{\"group\": \"zzz_last\"}, {\"group\": \"aaa_first\"}]"));

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertTrue(found.ok(), messages(found.problems()));
            assertEquals(List.of("zzz_last/group.json", "aaa_first/group.json"),
                    displays(found, QuestFiles.Kind.GROUP));
        }

        @Test
        @DisplayName("a chapter listed at the root belongs to no group")
        void aRootChapterIsWalkedWithoutAGroup(@TempDir Path root) throws IOException {
            // The case the folder format could not express at all: a chapter with no group above it.
            // Its declaration carries a null parent, which is what the loader reads as "no group" and
            // what makes it a root row in the sidebar.
            write(root, "loose/chapter.json", chapter("loose", "[\"only.json\"]"));
            write(root, "loose/only.json", quest("only"));
            write(root, "grouped/group.json", group("grouped", "[]"));
            write(root, "index.json",
                    index("[{\"chapter\": \"loose\"}, {\"group\": \"grouped\"}]"));

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertTrue(found.ok(), messages(found.problems()));
            assertEquals(List.of("loose/chapter.json"), displays(found, QuestFiles.Kind.CHAPTER));
            assertEquals(List.of("loose/only.json"), displays(found, QuestFiles.Kind.QUEST));
            assertEquals(List.of("grouped/group.json"), displays(found, QuestFiles.Kind.GROUP));
            assertNull(found.of(QuestFiles.Kind.CHAPTER).get(0).parentDisplay(),
                    "the root is not a manifest, and null is how the loader tells a loose chapter apart");
        }

        @Test
        @DisplayName("a version-1 file can be listed by name, so a mixed tree keeps working")
        void aFlatFileCanBeListed(@TempDir Path root) throws IOException {
            // Discovery refuses to leave a root file unaccounted for, and a version-1 file has no
            // folder to be named as a group or chapter -- so the index names it as a file. Without
            // this entry a tree that gained an index would stop loading its old flat content.
            write(root, "legacy.json", """
                    { "version": 1, "chapterGroups": [] }
                    """);
            write(root, "index.json", index("[{\"file\": \"legacy.json\"}]"));

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertTrue(found.ok(), messages(found.problems()));
            assertEquals(List.of("legacy.json"), displays(found, QuestFiles.Kind.FLAT_V1));
        }

        @Test
        @DisplayName("anything at the root it does not list is reported")
        void unlistedContentIsReported(@TempDir Path root) throws IOException {
            // The same rule a group's chapters and a chapter's quests already live under: content the
            // walk will not read is invisible, and only the author can say whether it is a mistake or
            // a note. The asymmetry with the unindexed walk is deliberate -- without a manifest there
            // is nothing to be listed in, so nothing to report.
            write(root, "listed/group.json", group("listed", "[]"));
            write(root, "forgotten/group.json", group("forgotten", "[]"));
            write(root, "index.json", index("[{\"group\": \"listed\"}]"));

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertFalse(found.ok());
            assertMentions(found.problems(), "index.json does not mention it");
            assertMentions(found.problems(), "forgotten");
            assertEquals(List.of("listed/group.json"), displays(found, QuestFiles.Kind.GROUP),
                    "and the listed content still loads");
        }

        @Test
        @DisplayName("a lang folder is Tasked's own storage, not content the index forgot")
        void theLocaleFolderIsNotReported(@TempDir Path root) throws IOException {
            // Reserved by name for the same reason `reward_tables` is, and it has to be: the walk
            // reports every root entry `index.json` does not mention, so a folder that is deliberately
            // not book content is an error on every load until it is named here. A pack shipping
            // translations would be told its own lang folder is a mistake, on every reload, forever.
            write(root, "listed/group.json", group("listed", "[]"));
            write(root, "lang/en_us.json", "{ \"quest.a.title\": \"A\" }");
            write(root, "index.json", index("[{\"group\": \"listed\"}]"));

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertTrue(found.ok(), () -> "a lang folder is not a mistake:\n" + messages(found.problems()));
            assertFalse(messages(found.problems()).contains("lang"),
                    "the folder must not be named in any problem:\n" + messages(found.problems()));
            // And it is found by the loader's own walk, which is what reads it.
            assertEquals(List.of(root.resolve("lang").resolve("en_us.json")), QuestFiles.localeFiles(root));
        }

        @Test
        @DisplayName("an entry that resolves to nothing is reported against the entry")
        void anUnresolvableEntryIsReported(@TempDir Path root) throws IOException {
            write(root, "index.json", index("[{\"group\": \"missing\"}]"));

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertFalse(found.ok());
            assertMentions(found.problems(), "missing");
            // Reported against the entry itself, not against a folder that does not exist.
            assertMentions(found.problems(), "index.json");
        }

        @Test
        @DisplayName("deleted folders are skipped at every level, listed or not")
        void deletedFoldersAreSkipped(@TempDir Path root) throws IOException {
            // A recoverable delete is a rename to `<name>.deleted`, for a chapter and for a group. The
            // walk has to skip the suffix everywhere -- and the unlisted-content sweep must not report
            // it either, or undoing a delete would require editing the manifest by hand.
            write(root, "gone.deleted/group.json", group("gone", "[]"));
            write(root, "live/group.json", group("live", "[\"kept\"]"));
            write(root, "live/kept/chapter.json", chapter("kept", "[]"));
            write(root, "live/old_chapter.deleted/chapter.json", chapter("old_chapter", "[]"));
            write(root, "index.json", index("[{\"group\": \"live\"}]"));

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertTrue(found.ok(), messages(found.problems()));
            assertEquals(List.of("live/group.json"), displays(found, QuestFiles.Kind.GROUP));
            assertEquals(List.of("live/kept/chapter.json"), displays(found, QuestFiles.Kind.CHAPTER));
        }

        @Test
        @DisplayName("an index.json that will not parse is read as if it were absent, and says so")
        void anUnreadableIndexFallsBackToFolderNames(@TempDir Path root) throws IOException {
            // **The whole book used to empty here.** Every other file in this loader fails open -- a
            // broken quest costs that quest, a broken chapter costs that chapter -- and this one file,
            // whose only job is to declare the order of the root, took every group, every chapter and
            // every quest with it, live, on the next reload. Absence has always been a supported state
            // for it: a tree with no index is read by folder name, which is what the shipped example is.
            write(root, "pack/group.json", group("pack", "[\"one\"]"));
            write(root, "pack/one/chapter.json", chapter("one", "[\"a.json\"]"));
            write(root, "pack/one/a.json", quest("a"));
            write(root, "index.json", "{ \"entries\": [ ");

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertFalse(found.ok(), "the file is still reported: " + messages(found.problems()));
            assertMentions(found.problems(), "read as if it");
            assertEquals(List.of("pack/group.json"), displays(found, QuestFiles.Kind.GROUP),
                    "the tree is read by folder name instead of not being read at all");
            assertEquals(List.of("pack/one/chapter.json"), displays(found, QuestFiles.Kind.CHAPTER));
            assertEquals(List.of("pack/one/a.json"), displays(found, QuestFiles.Kind.QUEST));
            assertEquals(1, found.problems().all().stream()
                            .filter(problem -> problem.file().equals("index.json")
                                    && problem.severity() == DataProblem.Severity.ERROR)
                            .count(),
                    "reported once -- the fallback must not read the same file again as a version-1 "
                            + "file:\n" + messages(found.problems()));
        }

        @Test
        @DisplayName("an entries key that is not a list is the same, because neither says what the root is")
        void anEntriesThatIsNotAListFallsBack(@TempDir Path root) throws IOException {
            write(root, "pack/group.json", group("pack", "[]"));
            write(root, "index.json", index("{}"));

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertFalse(found.ok());
            assertMentions(found.problems(), "expected a list of entries");
            assertMentions(found.problems(), "read as if it");
            assertEquals(List.of("pack/group.json"), displays(found, QuestFiles.Kind.GROUP));
        }

        @Test
        @DisplayName("an empty entries list is a declaration, and loads an empty book")
        void anEmptyEntriesListIsNotAFallback(@TempDir Path root) throws IOException {
            // The boundary on the other side, and it is deliberate rather than incidental: an empty list
            // is a *statement* that the book is empty, and reading it as absent would load every folder
            // the author had just taken out. The folder here is deliberately one the empty list excludes,
            // so the two answers are distinguishable: the index's own sweep reports it, and it does not
            // load. (It is an error, which is why this does not assert `found.ok()` -- excluding content
            // at the root is an error, and that rule is older than this one.)
            write(root, "pack/group.json", group("pack", "[]"));
            write(root, "index.json", index("[]"));

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertFalse(messages(found.problems()).contains("read as if it"),
                    "the empty list was believed, so there is nothing to fall back from:\n"
                            + messages(found.problems()));
            assertMentions(found.problems(), "index.json does not mention it");
            assertEquals(List.of(), displays(found, QuestFiles.Kind.GROUP),
                    "and the folder it left out did not load");
        }

        @Test
        @DisplayName("entries that are declared are still in charge, even when every one of them fails")
        void declaredEntriesDoNotFallBack(@TempDir Path root) throws IOException {
            // The boundary that must not move. A manifest that *declares* a root is believed, even when
            // what it declares cannot be resolved -- that is per-entry tolerance, and falling back here
            // would load every folder the manifest deliberately left out.
            write(root, "kept/group.json", group("kept", "[]"));
            write(root, "index.json", index("[{\"group\": \"missing\"}]"));

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertFalse(found.ok());
            assertMentions(found.problems(), "index.json does not mention it");
            assertEquals(List.of(), displays(found, QuestFiles.Kind.GROUP),
                    "the index was in charge, so the folder it left out did not load");
        }

        @Test
        @DisplayName("a root chapter needs an index, and the fallback reports it as the not-a-group it is")
        void aRootChapterNeedsAnIndex(@TempDir Path root) throws IOException {
            // The one layout the fallback cannot serve, pinned rather than discovered later: with no
            // readable manifest there is nothing left that says a folder at the root is a chapter rather
            // than a malformed group. Reading it as a chapter anyway would make "no index" mean two
            // different things depending on *how* the file became unreadable.
            write(root, "loose/chapter.json", chapter("loose", "[\"only.json\"]"));
            write(root, "loose/only.json", quest("only"));
            write(root, "index.json", "{ \"entries\": [ ");

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertMentions(found.problems(), "is not a chapter group");
            assertEquals(List.of(), displays(found, QuestFiles.Kind.CHAPTER));
        }
    }

    // ------------------------------------------------------------------
    // The reward tables' folder
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the reward tables' folder")
    class RewardTables {

        @Test
        @DisplayName("is skipped by the walk, with no index to list it in")
        void reservedFolderWithoutAnIndex(@TempDir Path root) throws IOException {
            wellFormedTree(root);
            write(root, "reward_tables/loot.json", "{}");

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertTrue(found.ok(), messages(found.problems()));
            assertEquals(3, found.size(), "the tables' folder is not a declaration of any kind");
            assertEquals(List.of("getting_started/group.json"), displays(found, QuestFiles.Kind.GROUP));
        }

        @Test
        @DisplayName("is legal beside an index that does not list it -- it is not book content")
        void reservedFolderBesideAnIndex(@TempDir Path root) throws IOException {
            // The rule that makes this matter: once index.json exists, everything at the root it does
            // not name is an error. Without the reservation, creating a reward table would error-wall
            // a perfectly good tree.
            write(root, "listed/group.json", group("listed", "[]"));
            write(root, "reward_tables/loot.json", "{}");
            write(root, "index.json", index("[{\"group\": \"listed\"}]"));

            QuestFiles.Discovery found = QuestFiles.discover(root);

            assertTrue(found.ok(), "an unlisted reward_tables folder must not be the unlisted-content "
                    + "error it would be without the reservation: " + messages(found.problems()));
            assertEquals(List.of("listed/group.json"), displays(found, QuestFiles.Kind.GROUP));
        }

        @Test
        @DisplayName("its files are read by the table reader, name-sorted, and nothing else is")
        void tableFilesAreListed(@TempDir Path root) throws IOException {
            write(root, "reward_tables/b_second.json", "{}");
            write(root, "reward_tables/a_first.json", "{}");
            write(root, "reward_tables/notes.txt", "not a table");
            write(root, "reward_tables/old.json.deleted", "{}");

            List<Path> files = QuestFiles.rewardTableFiles(root);

            assertEquals(List.of("a_first.json", "b_second.json"),
                    files.stream().map(path -> path.getFileName().toString()).toList(),
                    "json files in name order, the deleted one and the note left alone");
        }

        @Test
        @DisplayName("an absent folder is an empty list, not an error")
        void absentFolderIsEmpty(@TempDir Path root) {
            assertEquals(List.of(), QuestFiles.rewardTableFiles(root));
        }
    }

    // ------------------------------------------------------------------
    // Helpers that assert a shape rather than a string
    // ------------------------------------------------------------------

    /** Asserts a kind produced no declarations at all. */
    private static void assertEmpty(QuestFiles.Discovery found, QuestFiles.Kind kind, String why) {
        assertEquals(List.of(), displays(found, kind), why);
    }

    // ------------------------------------------------------------------
    // Every id in the tree, which is not the same question as what will load
    // ------------------------------------------------------------------

    /**
     * The id pool an editor mints against.
     *
     * <h2>Why these are the tests that matter for it</h2>
     *
     * <p>Because the two questions look identical on a well-formed pack and diverge exactly where a pack is
     * broken — and a broken pack is when a minted id must not collide. Every case below is a file
     * {@code discover} deliberately does not read, whose id is nonetheless written down and must therefore
     * be in the pool. The pool being too <i>small</i> is the failure that costs something: two quests under
     * one id share one progress record, and the second becomes unreachable while still being drawn.
     */
    @Nested
    @DisplayName("every id in the tree")
    class EveryId {

        private java.util.Set<String> idsOf(Path root) {
            return QuestFiles.allQuestIds(root, new Problems());
        }

        @Test
        @DisplayName("a well-formed tree gives its quest ids, and not the ids of its manifests")
        void wellFormed(@TempDir Path root) throws IOException {
            // The manifests are the half worth pinning: `group.json` and `chapter.json` declare an `id`
            // each, and neither is a quest's. Reading them would reserve a chapter's own name as a quest
            // name, which is legal today -- the index keeps the three kinds apart on purpose.
            assertEquals(java.util.Set.of("punch_a_tree"), idsOf(wellFormedTree(root)));
        }

        @Test
        @DisplayName("a quest file nothing lists is in the pool, though the loader never reads it")
        void unlistedAndNested(@TempDir Path root) throws IOException {
            // Both are reported as errors by the walk and both are invisible to `discover`'s quest list:
            // an unlisted file is never parsed, and a nested folder is skipped before it is entered. The
            // author fixing those errors is exactly who must not then find a collision the editor made.
            write(root, "getting_started/group.json", group("getting_started", "[\"first_steps\"]"));
            write(root, "getting_started/first_steps/chapter.json",
                    chapter("first_steps", "[\"listed.json\"]"));
            write(root, "getting_started/first_steps/listed.json", quest("listed"));
            write(root, "getting_started/first_steps/unlisted.json", quest("unlisted"));
            write(root, "getting_started/first_steps/nested/deep.json", quest("deep"));

            QuestFiles.Discovery found = QuestFiles.discover(root);
            assertFalse(found.ok(), "the walk does report both, which is why they are easy to leave broken");
            assertEquals(List.of("getting_started/first_steps/listed.json"),
                    displays(found, QuestFiles.Kind.QUEST), "and reads only the listed one");

            assertEquals(java.util.Set.of("listed", "unlisted", "deep"), idsOf(root),
                    "while the pool holds all three, because all three declare an id on disk");
        }

        @Test
        @DisplayName("a version-1 flat file's quests are in the pool, nested as they are")
        void flatFileQuests(@TempDir Path root) throws IOException {
            // **The case a converted pack is made of.** A flat file is one whole tree in one document, so
            // its root declares no id at all -- discovery reports it as a single FLAT_V1 declaration, and
            // reading only `$.id` would see none of the quests inside it. A pack imported from another mod
            // is where the flat layout and a freshly minted id meet.
            write(root, "legacy.json", """
                    { "version": 1,
                      "chapterGroups": [
                        { "id": "group", "title": "Group",
                          "chapters": [
                            { "id": "chapter", "title": "Chapter",
                              "quests": [
                                { "id": "punch_a_tree", "title": "Punch a Tree" },
                                { "id": "make_a_table", "title": "Make a Table" }
                              ] }
                          ] }
                      ] }
                    """);

            assertEquals(java.util.Set.of("punch_a_tree", "make_a_table"), idsOf(root),
                    "the nested quests, and not the file's own name");
        }

        @Test
        @DisplayName("a file with no id is in the pool under its file name")
        void aFileWithNoId(@TempDir Path root) throws IOException {
            // The editor keys an id-less file by its stem too -- see `QuestEditor.reloadQuests` -- so a
            // create that read only declared ids could still land on one.
            write(root, "getting_started/first_steps/nameless.json", "{ \"title\": \"Nameless\" }");

            assertEquals(java.util.Set.of("nameless"), idsOf(root));
        }

        @Test
        @DisplayName("the names the loader skips are skipped here too, and a broken file is not fatal")
        void skippedNamesAndBrokenFiles(@TempDir Path root) throws IOException {
            write(root, "_schema/quest.schema.json", quest("a_schema_is_not_a_quest"));
            write(root, "_notes.json", quest("a_note_is_not_a_quest"));
            write(root, "reward_tables/loot.json", quest("a_table_is_not_a_quest"));
            write(root, "getting_started/first_steps/gone.json.deleted", quest("gone"));
            write(root, "getting_started/first_steps/broken.json", "{ not json at all");
            write(root, "getting_started/first_steps/real.json", quest("real"));

            assertEquals(java.util.Set.of("real"), idsOf(root),
                    "a skipped name contributes nothing, and a file that will not parse contributes"
                            + " nothing rather than failing the scan -- the load reports it at its own line");
        }
    }
}
