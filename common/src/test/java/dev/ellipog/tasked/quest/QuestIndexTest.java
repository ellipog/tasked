package dev.ellipog.tasked.quest;

import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.armature.api.data.JsonDocument;
import dev.ellipog.armature.api.data.Problems;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static dev.ellipog.tasked.quest.Fixtures.q;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cross-file checks: identifiers, dependencies, and where quests sit.
 *
 * <h2>Why these are separate from the validator's tests</h2>
 *
 * <p>Because they answer a different question. {@code QuestValidator} decides whether one file is
 * well-formed; this decides whether a <i>set</i> of files makes sense together — whether an id is
 * taken, whether a {@code dependsOn} resolves, whether two quests are drawn on top of each other. A
 * single file cannot answer any of those, which is why an author finds out about them at load rather
 * than at save.
 *
 * <h2>The test that matters most here</h2>
 *
 * <p>{@link Crowding#closeTogetherInARowWarn}. That check was added because a screenshot showed three
 * quest titles interleaved into what looked like a corrupt string, and the cause was a questline
 * authored 64 pixels apart with titles about 90 pixels wide. The client now copes with that — it
 * truncates to the measured room and drops a label that would sit over a node — but a layout that
 * hides what the author wrote is worth reporting at load, and a check with no test is a check that
 * quietly stops working.
 */
class QuestIndexTest {

    /**
     * Starts enough of vanilla for the loader to check item ids.
     *
     * <p>Needed by the four tests that load the shipped examples, and it was not needed before them.
     * They used to decode files directly — which exercises the codecs and nothing else — and the
     * validator is the step that reads {@code BuiltInRegistries.ITEM}. Running it without this throws
     * from inside a vanilla class initialiser, naming a registry that has nothing to do with what is
     * under test, which is the failure mode {@link MinecraftTestBootstrap} documents at length.
     *
     * <p>About a second, once per JVM, shared with every other class that asks.
     */
    @org.junit.jupiter.api.BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    /**
     * A directory of this test's own, for the quest tree {@link #loadExamples} builds.
     *
     * <p>An instance field rather than a static one, so each test method gets a fresh directory: these
     * tests copy 139 files into it, and a shared one would mean a failed load leaving a half-built
     * tree — and a half-built tree is exactly the state that produces a misleading "duplicate id"
     * report on the next test rather than a clear failure.
     */
    @org.junit.jupiter.api.io.TempDir
    Path temp;

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * Indexes, returning the problems rather than discarding them.
     *
     * <p>{@link Fixtures#indexOf} throws the {@link Problems} away, which is right for the progression
     * tests — they want the index, not the complaints — and useless here. So this walks the same three
     * steps the loader walks, using only public API, and keeps the problems.
     */
    private static Problems problemsOf(String... jsons) {
        List<LoadedQuestFile> loaded = new ArrayList<>();
        for (int i = 0; i < jsons.length; i++) {
            String name = "test" + i + ".json";
            JsonDocument document = Fixtures.document(name, jsons[i]);
            loaded.add(new LoadedQuestFile(Path.of(name), name, document, Fixtures.decode(name, document)));
        }
        Problems problems = new Problems();
        QuestIndex.build(loaded, problems);
        return problems;
    }

    /** Every problem message, joined, for a single containment assertion. */
    private static String messages(Problems problems) {
        return problems.all().stream().map(DataProblem::render).reduce("", (a, b) -> a + "\n" + b);
    }

    private static void assertMentions(Problems problems, String text) {
        assertTrue(messages(problems).contains(text),
                "expected a problem mentioning '" + text + "', got:" + messages(problems));
    }

    private static void assertDoesNotMention(Problems problems, String text) {
        assertTrue(!messages(problems).contains(text),
                "did not expect a problem mentioning '" + text + "', got:" + messages(problems));
    }

    /** A title long enough to matter at the crowding threshold. 20 characters, about 120px. */
    private static final String LONG_ID = "punch_a_tree_forever";

    /** A second one, a different length so the check has to pick the wider of the two. */
    private static final String WIDER_ID = "collect_some_cobblestone";

    // ------------------------------------------------------------------
    // Identifiers
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("identifiers")
    class Identifiers {

        @Test
        @DisplayName("two quests with the same id are an error, even in one file")
        void duplicateIdIsAnError() {
            // Reported only here and not by the validator: one file has no idea what another file
            // declared, and the index is the one place that can see the whole set. Within a single
            // file the validator could see it, but a second rule about the same thing would be a
            // second rule that can disagree with the first.
            Problems problems = problemsOf(Fixtures.file(
                    q("twice").build(),
                    q("twice").build()));

            assertTrue(problems.hasErrors(), "a duplicate id is fatal:" + messages(problems));
            assertMentions(problems, "duplicate quest id \"twice\"");
        }

        @Test
        @DisplayName("an alias that another quest already uses as an id is an error")
        void aliasClashingWithAnIdIsAnError() {
            // The reason this is fatal rather than a warning: a lookup of "alpha" would be ambiguous,
            // and a lookup is what progress is keyed on. Ambiguity here is somebody's progress landing
            // on the wrong quest.
            Problems problems = problemsOf(Fixtures.file(
                    q("alpha").build(),
                    q("beta").alias("alpha").build()));

            assertTrue(problems.hasErrors(), "an ambiguous alias is fatal:" + messages(problems));
            assertMentions(problems, "is already used by another quest");
        }

        @Test
        @DisplayName("an alias declared twice on one quest is an error")
        void duplicateAliasOnOneQuestIsAnError() {
            Problems problems = problemsOf(Fixtures.file(
                    q("alpha").alias("old_name", "old_name").build()));

            assertTrue(problems.hasErrors(), "a repeated alias is fatal:" + messages(problems));
            assertMentions(problems, "declared twice on the same quest");
        }

        @Test
        @DisplayName("a chapter and a quest may share an id")
        void kindsDoNotCollide() {
            // Deliberately allowed, and worth a test: the three tables are separate for exactly this
            // reason, and collapsing them into one map would break it silently.
            Problems problems = problemsOf(Fixtures.fileWithChapter(
                    "\"id\": \"first_steps\",", q("first_steps").build()));

            assertTrue(!problems.hasErrors(), "an id may be reused across kinds:" + messages(problems));
        }
    }

    // ------------------------------------------------------------------
    // Dependencies
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("dependencies")
    class Dependencies {

        @Test
        @DisplayName("a dependency on a quest that does not exist is an error")
        void unresolvedDependencyIsAnError() {
            Problems problems = problemsOf(Fixtures.file(
                    q("b").dependsOn("nowhere").build()));

            assertTrue(problems.hasErrors(), "an unresolved dependency strands the quest:");
            assertMentions(problems, "no quest with id or alias \"nowhere\" exists");
            // The consequence, spelled out. "No such quest" is accurate and does not tell an author
            // why they should care.
            assertMentions(problems, "can never be unlocked");
        }

        @Test
        @DisplayName("a near miss suggests the id that was probably meant")
        void unresolvedDependencySuggestsANearMiss() {
            Problems problems = problemsOf(Fixtures.file(
                    q("punch_a_tree").build(),
                    q("b").dependsOn("punch_a_tre").build()));

            assertMentions(problems, "did you mean \"punch_a_tree\"?");
        }

        @Test
        @DisplayName("a quest that depends on itself is an error")
        void selfDependencyIsAnError() {
            Problems problems = problemsOf(Fixtures.file(
                    q("loop").dependsOn("loop").build()));

            assertTrue(problems.hasErrors(), "a self-dependency never unlocks:");
            assertMentions(problems, "depends on itself");
        }

        @Test
        @DisplayName("a dependency on another quest's alias resolves")
        void dependencyOnAnAliasResolves() {
            // The whole point of aliases: renaming a quest must not break the references to it, and a
            // reference by the old name has to keep working.
            Problems problems = problemsOf(Fixtures.file(
                    q("renamed").alias("old_name").build(),
                    q("b").dependsOn("old_name").build()));

            assertTrue(!problems.hasErrors(), "an alias should resolve:" + messages(problems));
            assertDoesNotMention(problems, "no quest with id or alias");
        }

        @Test
        @DisplayName("minRequired higher than the dependency count is an error")
        void minRequiredBeyondDependenciesIsAnError() {
            Problems problems = problemsOf(Fixtures.file(
                    q("a").build(),
                    q("b").dependsOn("a").minRequired(3).build()));

            assertTrue(problems.hasErrors(), "it can never be unlocked:");
            assertMentions(problems, "but there are only 1");
        }
    }

    // ------------------------------------------------------------------
    // Content smells
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("content smells")
    class ContentSmells {

        @Test
        @DisplayName("a quest with no tasks and no rewards warns")
        void emptyQuestWarns() {
            // A warning, not an error: it works, there is just nothing in it. It is almost always a
            // quest someone started writing and saved.
            Problems problems = problemsOf(Fixtures.file(q("stub").noTasks().build()));

            assertTrue(!problems.hasErrors(), "an empty quest still loads:");
            assertMentions(problems, "no tasks and no rewards");
            assertEquals(1, problems.warningCount(), "exactly one warning:" + messages(problems));
        }
    }

    // ------------------------------------------------------------------
    // Crowding
    // ------------------------------------------------------------------

    /**
     * The rule that came out of a screenshot.
     *
     * <p>Two quests in the same row, close enough together that their titles cannot both be drawn.
     * The client survives it — it measures the room and truncates — so this is a warning rather than
     * an error, and the point is that an author hears about it at load instead of at play.
     */
    @Nested
    @DisplayName("crowding")
    class Crowding {

        @Test
        @DisplayName("two long titles 64px apart in one row warn, if both are drawn")
        void closeTogetherInARowWarn() {
            // 64 is the spacing that produced the bug: three nodes 64px apart carrying titles around
            // 90-120px, so their labels were drawn through each other.
            //
            // `showTitle(true)` on both is not incidental detail -- it is what makes this the case the
            // check is about. Titles are off by default, so without it there would be nothing drawn in
            // that 64 pixels and nothing to collide.
            Problems problems = problemsOf(Fixtures.file(
                    q(LONG_ID).at(0, 0).showTitle(true).build(),
                    q(WIDER_ID).at(64, 0).showTitle(true).build()));

            assertTrue(!problems.hasErrors(), "crowding is a warning, not a failure:");
            assertMentions(problems, "pixels apart in the same row");
            assertMentions(problems, "will overlap and run together");
        }

        @Test
        @DisplayName("the same two quests 64px apart with neither name drawn do not warn")
        void unnamedQuestsCannotCrowd() {
            // The case the labels' new default created, and the reason the check had to change with it.
            // Two 20-character titles 64 pixels apart look exactly like the reported bug -- and nothing
            // at all is drawn between them, so there is nothing to report.
            //
            // A check that ignored this would fire on every tightly-packed chapter of unnamed nodes,
            // which is a layout the default now actively encourages.
            Problems problems = problemsOf(Fixtures.file(
                    q(LONG_ID).at(0, 0).build(),
                    q(WIDER_ID).at(64, 0).build()));

            assertDoesNotMention(problems, "pixels apart in the same row");
        }

        @Test
        @DisplayName("and one named beside one unnamed does not warn either")
        void oneNamedNeighbourIsNotCrowding() {
            // Half the pair is drawn, so the drawn one has the whole 64 pixels to itself. That is a
            // real difference from "both are drawn" rather than a technicality: the screen measures the
            // room from the names it is going to draw, so an unnamed neighbour contributes no
            // competition for the space.
            Problems problems = problemsOf(Fixtures.file(
                    q(LONG_ID).at(0, 0).showTitle(true).build(),
                    q(WIDER_ID).at(64, 0).build()));

            assertDoesNotMention(problems, "pixels apart in the same row");
        }

        @Test
        @DisplayName("the same two names at 132px apart do not warn")
        void generousSpacingDoesNotWarn() {
            // 132 is what the shipped example questline now uses. If this ever starts warning, the
            // threshold has drifted away from the label cap the client actually applies.
            Problems problems = problemsOf(Fixtures.file(
                    q(LONG_ID).at(0, 0).showTitle(true).build(),
                    q(WIDER_ID).at(132, 0).showTitle(true).build()));

            assertDoesNotMention(problems, "pixels apart in the same row");
        }

        @Test
        @DisplayName("short titles 64px apart do not warn, because they fit")
        void shortTitlesDoNotWarn() {
            // The check is about the titles, not about the spacing on its own. Three characters is
            // about 18px, so 64px of room is ample -- and warning here would be noise, which is how a
            // check gets ignored.
            Problems problems = problemsOf(Fixtures.file(
                    q("aaa").at(0, 0).showTitle(true).build(),
                    q("bbb").at(64, 0).showTitle(true).build()));

            assertDoesNotMention(problems, "pixels apart in the same row");
        }

        @Test
        @DisplayName("quests 64px apart in different rows do not warn")
        void differentRowsDoNotWarn() {
            // A label is drawn *below* its node, so two labels on different rows never meet however
            // close the nodes are horizontally. Comparing every pair rather than only row-mates would
            // report a branch as a fault.
            Problems problems = problemsOf(Fixtures.file(
                    q(LONG_ID).at(0, 0).showTitle(true).build(),
                    q(WIDER_ID).at(64, 64).showTitle(true).build()));

            assertDoesNotMention(problems, "pixels apart in the same row");
        }

        @Test
        @DisplayName("quests in different chapters do not warn")
        void differentChaptersDoNotWarn() {
            // Two chapters are two separate canvases, so the same coordinates in each are independent.
            // Keying rows by chapter as well as row is what makes that true.
            //
            // Two files, passed as two arguments. Concatenating them into one string would produce a
            // document that is not JSON at all, and the test would then be asserting about a parse
            // failure rather than about chapters -- which is how it was written the first time.
            Problems problems = problemsOf(
                    Fixtures.fileWithChapter("\"id\": \"one\",", q(LONG_ID).at(0, 0).showTitle(true).build()),
                    Fixtures.fileWithChapter("\"id\": \"two\",", q(WIDER_ID).at(64, 0).showTitle(true).build()));

            assertDoesNotMention(problems, "pixels apart in the same row");
        }

        @Test
        @DisplayName("quests at the same coordinates in different chapters are not duplicates")
        void samePositionInDifferentChaptersIsNotADuplicate() {
            // Every chapter starts at 0,0, which is what makes a new chapter easy to write -- and this
            // is the check that had to be keyed by chapter when a second shipped file started doing it.
            // Reporting the first quest of every chapter as stacked would make the natural layout
            // impossible, and the message would be about coordinates rather than about anything wrong.
            Problems problems = problemsOf(
                    Fixtures.fileWithChapter("\"id\": \"one\",", q(LONG_ID).at(0, 0).build()),
                    Fixtures.fileWithChapter("\"id\": \"two\",", q(WIDER_ID).at(0, 0).build()));

            assertDoesNotMention(problems, "is at the same position");
        }

        @Test
        @DisplayName("two quests at exactly the same spot are reported once, as a duplicate")
        void stackedQuestsAreNotAlsoReportedAsCrowded() {
            // A gap of 0 is a duplicate position, which checkDuplicatePositions already reports with a
            // better message. Reporting it twice -- once as stacked, once as crowded -- would train an
            // author to skim the output, and "these two titles will overlap" is not the interesting
            // part of two nodes being on top of each other.
            Problems problems = problemsOf(Fixtures.file(
                    q(LONG_ID).at(0, 0).showTitle(true).build(),
                    q(WIDER_ID).at(0, 0).showTitle(true).build()));

            assertMentions(problems, "is at the same position");
            assertDoesNotMention(problems, "pixels apart in the same row");
            assertEquals(1, problems.warningCount(), "exactly one warning:" + messages(problems));
        }

        @Test
        @DisplayName("a row of five reports each crowded neighbour pair")
        void everyCrowdedPairInARowIsReported() {
            // Each adjacent pair, not just the first. An author who fixes only the pair they were told
            // about would otherwise have to reload once per collision.
            Problems problems = problemsOf(Fixtures.file(
                    q(LONG_ID).at(0, 0).showTitle(true).build(),
                    q(WIDER_ID).at(64, 0).showTitle(true).build(),
                    q(LONG_ID + "_b").at(128, 0).showTitle(true).build()));

            long crowded = problems.all().stream()
                    .filter(problem -> problem.message().contains("pixels apart in the same row"))
                    .count();
            assertEquals(2, crowded, "two adjacent pairs:" + messages(problems));
        }
    }

    // ------------------------------------------------------------------
    // The mod ships nothing, and the examples are examples
    // ------------------------------------------------------------------

    /**
     * Where the worked examples live.
     *
     * <p><b>Not</b> in {@code src/main/resources}. They used to be: {@code /tasked/default_quests/} in
     * the jar, copied into {@code config/tasked/quests} by the loader. They moved out for two reasons,
     * and the first is the important one — a mod that installs three example chapters into every
     * player's config directory has decided something that is not its to decide, and the first thing a
     * pack author would have to do is delete somebody else's content.
     *
     * <p>The second is smaller and still real: a quest file in the jar is a file that has to keep
     * working forever against a format that is still moving, and every format change is a migration
     * for content nobody asked for.
     *
     * <p>So they are authoring documentation in the repository, and
     * {@code tasked/tools/seed_quests.py} is what copies them somewhere. An author reads them; a
     * player never sees them unless they ask, which is the whole difference.
     *
     * <p>Relative to the Gradle project directory, which is {@code tasked/common} — the same place the
     * old path was relative to, so the tests run from the same working directory either way.
     */
    private static final java.nio.file.Path EXAMPLES = java.nio.file.Path.of("..", "tools", "quests");

    /**
     * Loads the worked examples the way the mod does: copy them into a config directory, then run the
     * real loader over it.
     *
     * <h2>Why this goes through the loader rather than decoding the files itself</h2>
     *
     * <p>Because the examples are now a <b>folder tree</b>, and the only thing that knows how to turn a
     * folder tree into a questline is the loader. This used to build a {@link LoadedQuestFile} per file
     * by hand, which worked because version 1 puts one whole tree in one document — so "decode the
     * file" and "load the questline" were the same act, and a test was free to do the first directly.
     * Under version 2 they are not the same act at all: {@code group.json} decodes to a manifest holding
     * a list of <i>names</i> and nothing else, so a test that stopped at the codec would be asserting
     * about a tree with no chapters in it.
     *
     * <p>That is the same trap the seeding step had, one level up, and it fails silently in the same
     * direction: a manifest decodes perfectly and contributes nothing, so a test looping over the
     * examples would find every file, decode every one, and then assert about an empty questline.
     *
     * <h2>So it is the real path, including validation</h2>
     *
     * <p>{@link QuestLoader#load} parses, validates, decodes and indexes — every step the game runs.
     * These tests therefore now check something they could not before: that the shipped examples pass
     * the <b>validator</b>, not merely the codecs. That is strictly stronger, and it is why
     * {@link #bootVanilla()} sits at the top of this class: the validator resolves item ids against
     * {@code BuiltInRegistries.ITEM}, and an unbootstrapped registry does not report everything missing
     * — it throws from inside a vanilla class initialiser.
     *
     * <h2>The copy is not incidental</h2>
     *
     * <p>{@code QuestLoader} reads a directory and writes nothing, so a test that wants it to load
     * something has to put it there. That is exactly what {@code tasked/tools/seed_quests.py} does for a
     * player and what this does for a test. Copying rather than reading in place also keeps the
     * examples directory itself untouched, so no test can leave the repository dirty.
     *
     * <h2>Paths are preserved, and the underscore rule applies to every segment</h2>
     *
     * <p>The relative path is kept rather than flattened, because the layout <i>is</i> the format: a
     * quest called {@code punch_a_tree.json} at the quest root is not a quest at all, it is a version-1
     * file the loader will try to read as a whole tree.
     *
     * <p>And {@code _}-prefixed names are skipped at every segment, not just the last. The shipped
     * {@code _schema} folder is a <b>directory</b>, so a rule that tested only a file's own name would
     * copy all three schema files into the config directory — where the loader's own walk would then
     * skip them, so the mistake would be invisible from the test and visible only as three stray files
     * in somebody's install.
     *
     * @param configDir where to build the copy. Each caller passes a directory of its own, so two tests
     *     cannot collide over one config directory — and so a failed load cannot leave state behind for
     *     the next one.
     */
    private static QuestLoader.Result loadExamples(java.nio.file.Path configDir) throws java.io.IOException {
        java.nio.file.Path target = configDir.resolve(QuestLoader.DIRECTORY);
        try (var walk = java.nio.file.Files.walk(EXAMPLES)) {
            for (java.nio.file.Path source : walk.filter(java.nio.file.Files::isRegularFile).toList()) {
                java.nio.file.Path relative = EXAMPLES.relativize(source);
                if (isIgnored(relative)) {
                    continue;
                }
                java.nio.file.Path destination = target.resolve(relative.toString());
                java.nio.file.Files.createDirectories(destination.getParent());
                java.nio.file.Files.copy(source, destination);
            }
        }
        return QuestLoader.load(configDir);
    }

    /**
     * Whether a path under the examples directory is skipped.
     *
     * <p>Every segment, not just the last — the same rule {@code DeclaredPaths.isIgnored} states, and
     * for the same reason, which is the paragraph above. See {@link #loadExamples}.
     */
    private static boolean isIgnored(java.nio.file.Path relative) {
        for (java.nio.file.Path segment : relative) {
            if (segment.toString().startsWith("_")) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("the mod ships no quests of its own, in the source tree or on the classpath")
    void theModShipsNoQuests() {
        // The rule this round was about, pinned in the two places it could break.
        //
        // Both halves are needed. The source-tree check catches somebody putting the files back; the
        // classpath check catches a file that reached the built output some other way — a stale copy
        // in `build/resources`, or a resource added under a different path. Only the second one is a
        // check of what actually ends up in the jar.
        java.nio.file.Path inSource = java.nio.file.Path.of(
                "src", "main", "resources", "tasked", "default_quests");
        assertFalse(java.nio.file.Files.exists(inSource),
                "the mod is shipping quest files again, at " + inSource.toAbsolutePath()
                        + ". They belong in tasked/tools/quests, where they are authoring documentation"
                        + " rather than content installed into every player's config directory.");

        assertNull(QuestIndexTest.class.getResource("/tasked/default_quests/01_stone_age.json"),
                "a quest file is on the classpath, so it is in the jar. Delete it from"
                        + " src/main/resources -- the examples live in tasked/tools/quests now, and"
                        + " tasked/tools/seed_quests.py is what puts them in a config directory.");
    }

    @Test
    @DisplayName("every example questline loads with nothing wrong in it")
    void everyExampleQuestlineIsClean() throws java.io.IOException {
        // Read from the repository rather than a fixture, so the file an author is pointed at is the
        // one under test. `01_stone_age`'s spacing was 64 until the labelling bug, and this is what
        // stops it drifting back: a change that reintroduces crowding fails here rather than on
        // someone's screen.
        //
        // A loop over the directory rather than one file by name, because it used to read
        // `01_stone_age.json` alone — so a second example could have shipped with a broken dependency
        // in it and this test would have stayed green, wrong about the thing it was named for.
        assertTrue(java.nio.file.Files.isDirectory(EXAMPLES),
                "expected the examples at " + EXAMPLES.toAbsolutePath()
                        + " -- the tests run from the Gradle project directory, so this is relative to"
                        + " tasked/common");

        QuestLoader.Result loaded = loadExamples(temp.resolve("clean"));

        // Errors only, and this assertion is stricter than the one it replaces in the way that matters
        // while being looser in the way that does not.
        //
        // Looser: it used to demand that the problem list be *empty*. But it ran no validation, so there
        // was very little that could have appeared in that list — the check was close to tautological.
        // Now the validator runs, and "empty" would mean asserting something about the examples that is
        // not this test's business: a warning that two example quests stack their nodes is a legitimate
        // thing for example content to do, and failing on it would set the suite against whoever writes
        // the next example.
        //
        // Stricter: what must be true is that nothing in the examples is an *error* — a dangling
        // dependency, an id claimed twice, a field the codecs reject, a misspelled enum. That is what
        // `ok()` means, and unlike the old assertion it is a claim the loader is in a position to
        // contradict.
        assertTrue(loaded.ok(), "the examples should have nothing fatal in them, but reported:"
                + messages(loaded.problems()));

        // And not vacuous. Every assertion above passes on an empty index, which is precisely what a
        // broken copy step produces — see `loadExamples` on why the copy step is worth distrusting.
        assertTrue(loaded.filesFound() > 0,
                "no example files were found at all under " + EXAMPLES.toAbsolutePath());
        assertTrue(loaded.index().questCount() > 0,
                "the examples were read and contributed no quests, so either the copy step or the "
                        + "loader found nothing. Files found: " + loaded.filesFound());
    }

    @Test
    @DisplayName("the examples are four different designs, not one four times")
    void theExampleQuestlinesExerciseDifferentThings() throws java.io.IOException {
        // A test that says what the example content is *for*, so a future tidy-up cannot quietly turn
        // four demonstrations into four copies of the first one. The descriptions in the files already
        // claim all of this; this is the version a compiler reads.
        QuestLoader.Result loaded = loadExamples(temp.resolve("varied"));

        assertTrue(loaded.ok(), "fixture sanity -- the examples should be clean first:"
                + messages(loaded.problems()));

        QuestIndex index = loaded.index();
        List<Quest> all = index.quests().stream().map(QuestIndex.QuestEntry::quest).toList();

        // The mechanics, each of which exists in exactly one place and is named in the file that has it.
        assertTrue(all.stream().anyMatch(Quest::repeatable), "a repeatable quest");
        assertTrue(all.stream().anyMatch(quest -> quest.repeatCooldownTicks() > 0), "with a cooldown");
        assertTrue(all.stream().anyMatch(Quest::sequentialTasks), "a quest with sequential tasks");
        assertTrue(all.stream().anyMatch(Quest::invisible), "a hidden quest");
        assertTrue(all.stream().anyMatch(Quest::showTitle), "a quest whose name is drawn");
        assertTrue(all.stream().anyMatch(quest -> quest.exclusiveGroup().isPresent()), "an exclusive pair");
        assertTrue(all.stream().anyMatch(quest -> quest.minRequired() > 0), "an OR-gate");
        assertTrue(all.stream().anyMatch(quest -> quest.tasks().stream().anyMatch(QuestTask::optional)),
                "an optional task");
        assertTrue(all.stream().anyMatch(quest -> quest.tasks().isEmpty() == false
                        && quest.tasks().stream().anyMatch(task -> task instanceof
                        dev.ellipog.tasked.quest.task.ItemTask item
                        && item.consumes(false))),
                "a task that takes the items");

        // Every shape, because the shapes are the thing a still cannot show the difference between
        // unless the content actually varies -- which was the defect this project already had once.
        for (QuestShape shape : QuestShape.values()) {
            assertTrue(all.stream().anyMatch(quest -> quest.layout().shape() == shape),
                    "no example quest uses the " + shape + " shape, so nothing exercises it");
        }

        // And a chapter that is LINEAR, because the list order being the progression is a whole
        // mechanism that is otherwise never read by anything outside a test fixture.
        //
        // Asked of `index.chapters()` rather than by walking the files, which is what this did. That
        // walk was one of six copies of the same flattening loop, and the flattened result is exactly
        // what `chapters()` already is.
        boolean linear = index.chapters().stream()
                .map(QuestIndex.ChapterEntry::chapter)
                .anyMatch(chapter -> chapter.progressionMode() == ProgressionMode.LINEAR);
        assertTrue(linear, "no example chapter is LINEAR");

        // And the two defaults, by their absence: most quests draw no name and take no items, which is
        // what makes the exceptions in the files mean something.
        long named = all.stream().filter(Quest::showTitle).count();
        assertTrue(named < all.size() / 2,
                "most example quests should NOT draw their name -- that is the default, and a file where"
                        + " every quest opts in is not demonstrating anything");
    }

    @Test
    @DisplayName("the theme gallery's fifteen chapters are the same layout with different content")
    void theThemeGalleryIsComparable() throws java.io.IOException {
        // The fourth example has exactly one job: let someone switch theme and see what changed. That
        // only works if the three chapters differ in **nothing but their palette** -- otherwise a
        // difference on screen could be the theme or the content, and an exhibit that varies two things
        // at once demonstrates neither.
        //
        // So this asserts the two halves of that, and they pull in opposite directions on purpose:
        //
        //   - **Geometry identical**, quest for quest, position and shape and size and icon scale. That
        //     is what makes flipping between chapters a comparison rather than a new screen.
        //   - **Titles all different**, because three chapters with identical tiles would be one chapter
        //     written three times, which is the thing the test above this one exists to prevent.
        //
        // The interesting failure this catches is not a typo. It is somebody later "tidying" the third
        // chapter's positions because they looked arbitrary, which would silently turn the one piece of
        // content whose whole design is comparability into three unrelated chapters.
        QuestIndex index = loadExamples(temp.resolve("gallery")).index();

        // Found by group id rather than by file name, because there is no longer a file that *is* the
        // gallery: its fifteen chapters are fifteen folders, and the group's own manifest is what says
        // which ones they are. That indirection used to be invisible — a flat file held the whole tree,
        // so "the gallery" and "04_theme_gallery.json" were the same thing.
        //
        // `orElseThrow` rather than an index into a list, so a renamed group fails with the id it looked
        // for and the groups that exist, rather than with an IndexOutOfBounds on somebody's refactor.
        List<Chapter> chapters = index.group("theme_gallery")
                .orElseThrow(() -> new AssertionError("no chapter group called theme_gallery in the"
                        + " examples, so this test is looking at the wrong content. Groups present: "
                        + index.groups().stream().map(entry -> entry.group().id()).toList()))
                .group().chapters();
        // One chapter per shipped theme, asserted as a number rather than as "at least one". The
        // gallery is the only thing that demonstrates a theme by being clicked, so a chapter quietly
        // dropped makes a theme unreachable from the UI -- and nothing else in the build would notice,
        // because a theme nobody can select is still a perfectly valid theme.
        assertEquals(15, chapters.size(),
                "the theme gallery should be one chapter per shipped theme: " + chapters.stream()
                        .map(Chapter::id).toList());

        // Geometry, as strings, so a mismatch names the field it is in rather than printing two records
        // that differ somewhere the reader has to find.
        //
        // The quest's **id is deliberately absent** from this string, and that is the whole trick: the
        // three chapters have different content, so their ids differ by design. Including the id would
        // make the comparison fail for every chapter and the assertion would look like it was testing
        // something when it was testing "these are three different files".
        List<List<String>> geometries = new ArrayList<>();
        for (Chapter chapter : chapters) {
            List<String> geometry = new ArrayList<>();
            for (Quest quest : chapter.quests()) {
                QuestLayout layout = quest.layout();
                geometry.add(layout.x() + "," + layout.y() + " as " + layout.shape() + " " + layout.size()
                        + " icon " + layout.iconScale());
            }
            geometries.add(geometry);
        }
        assertEquals(6, geometries.get(0).size(),
                "each gallery chapter is six quests, so one of them lost or gained one: "
                        + geometries.get(0));

        // Every chapter against the first, named, so a failure says *which* chapter drifted rather
        // than printing fifteen identical-looking lists. Writing this out as a loop rather than fifteen
        // assertions is the same choice as the token registry: the invariant is "all of them agree",
        // and a hand-written list is one that the sixteenth theme is left out of.
        for (int i = 1; i < geometries.size(); i++) {
            assertEquals(geometries.get(0), geometries.get(i),
                    "chapter " + i + " ('" + chapters.get(i).id() + "') has different geometry from"
                            + " chapter 0 ('" + chapters.get(0).id() + "'), so a difference seen while"
                            + " flipping between them is not attributable to the theme");
        }

        // And the content is genuinely different, which is the other half. Without this the three
        // chapters would be one chapter written three times, which is the failure the test above this
        // one exists to prevent.
        Set<String> titles = new LinkedHashSet<>();
        Set<String> icons = new LinkedHashSet<>();
        for (Chapter chapter : chapters) {
            for (Quest quest : chapter.quests()) {
                // Resolved rather than read, because a title is a `QuestText` -- either a literal or a
                // translation key. Comparing the keys would pass for three chapters whose titles all
                // read the same to a player, which is the opposite of what this asserts.
                titles.add(quest.title().component().getString());
                icons.add(quest.icon().toString());
            }
        }
        assertEquals(90, titles.size(),
                "two gallery quests share a title, so two chapters are partly the same chapter");
        assertTrue(icons.size() >= 30,
                "the chapters reuse icons heavily, which makes them harder to tell apart than the"
                        + " theme already makes them: " + icons.size() + " distinct icons across"
                        + " 90 quests");

        // And the half that makes the whole file work: every chapter names a theme, and they are all
        // different.
        //
        // This is not a formality, and the first version of this file is why. It predated the theme
        // field and relied on a command instead, so clicking between its chapters changed nothing --
        // which is the reported bug, and it was reported as "they all look identical, just clicking
        // through them". A chapter with no `theme` is not a broken chapter; it is a chapter making no
        // claim. Several of them in a row is a gallery that demonstrates nothing, and only a test of
        // the *set* can see that, because every individual chapter is well-formed.
        List<String> themes = chapters.stream().map(chapter -> chapter.theme().orElse("")).toList();
        assertFalse(themes.contains(""),
                "a gallery chapter names no theme, so it looks identical to whichever chapter came"
                        + " before it: " + themes);
        assertEquals(chapters.size(), new LinkedHashSet<>(themes).size(),
                "two gallery chapters name the same theme, so one of the fifteen is demonstrated twice"
                        + " and a theme is missing from the gallery: " + themes);

        // And every name it uses is one this build has, which is the assertion that makes the count
        // above mean something. A gallery of fifteen chapters naming fifteen names, one of which is a
        // typo, passes the distinctness check and shows the player fourteen themes -- so the two
        // together are the property, not either on its own. `exampleThemesExist` below covers the same
        // ground for all four shipped files; this narrows it to the one where a missing theme is
        // invisible rather than merely wrong.
        for (String theme : themes) {
            assertNotNull(dev.ellipog.armature.client.ui.Themes.byName(theme),
                    "the gallery asks for a theme called '" + theme + "', which this build does not"
                            + " have. It has: " + dev.ellipog.armature.client.ui.Themes.names());
        }

        // And it covers every theme except one -- <b>and the exception is the point</b>.
        //
        // `default` is deliberately not a chapter, because the default theme *is* the frame these
        // chapters are read inside: a chapter asking for it would show a canvas identical to the
        // sidebar beside it, which demonstrates nothing and would read as the chapter having failed to
        // load. So the gallery is every theme apart from the one a player is already looking at.
        //
        // Written as a set difference against the catalogue rather than as a count, because the property
        // worth protecting is "a new theme cannot be added without a chapter" -- and a count of fifteen
        // passes just as happily when a sixteenth theme is added and a chapter is mistakenly pointed at
        // an older name. This fails, and names the theme that has nowhere to be seen.
        Set<String> shipped = dev.ellipog.armature.client.ui.Themes.ALL.stream()
                .map(dev.ellipog.armature.client.ui.Theme::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        shipped.remove("default");

        assertEquals(shipped, new LinkedHashSet<>(themes),
                "the gallery's chapters and the shipped themes have drifted apart. A theme in the left"
                        + " set has no chapter, so nothing lets a player see it; a name in the right set"
                        + " is a chapter asking for a theme that is not shipped. The only sanctioned"
                        + " difference is `default`, which is the frame rather than a chapter.");
    }

    @Test
    @DisplayName("every theme a shipped example names is one this build actually has")
    void exampleThemesExist() throws java.io.IOException {
        // The failure this catches is the quietest one in the whole theme feature, and it is worth
        // being explicit about why it needs a test at all.
        //
        // A chapter naming a theme that does not exist is *handled*: the client logs a line naming the
        // chapter and the name, and carries on with the player's own theme. Handled well, in fact --
        // refusing to open the chapter would be far worse. But handled silently from the author's
        // side, and the shipped examples are the one place where a typo would reach the player before
        // it reached anyone who could fix it.
        //
        // So the gallery's `theme` field is checked against the built-ins here rather than discovered
        // in a log. It is the same reasoning as the shape field that was parsed, validated and printed
        // by a command while nothing drew it: a value that only ever produces a warning at runtime is
        // a value that is wrong for a while before anyone notices.
        //
        // Read from the tools directory rather than from a resource, because that is where the
        // examples live -- they are documentation, and the mod deliberately ships no quests.
        Set<String> builtIn = dev.ellipog.armature.client.ui.Themes.ALL.stream()
                .map(dev.ellipog.armature.client.ui.Theme::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        QuestIndex index = loadExamples(temp.resolve("themes")).index();

        // Every chapter in every example, rather than a hand-written list of four file names. That list
        // was a thing that stops matching the content — and it did, the moment the examples became
        // folders: there is no `04_theme_gallery.json` to name any more. Reading the index means a fifth
        // example is covered the day it is added.
        //
        // `entry.file()` rather than the old flat name, so the message names the file that actually
        // holds the mistake — `theme_gallery/gallery_tome/chapter.json` rather than a file that contains
        // fifteen chapters and no longer exists.
        for (QuestIndex.ChapterEntry entry : index.chapters()) {
            Chapter chapter = entry.chapter();
            if (chapter.theme().isEmpty()) {
                continue;
            }
            assertTrue(builtIn.contains(chapter.theme().get()),
                    entry.file() + "'s chapter '" + chapter.id() + "' asks for a theme called '"
                            + chapter.theme().get() + "', which this build does not have. It has: "
                            + builtIn);
        }
    }
}
