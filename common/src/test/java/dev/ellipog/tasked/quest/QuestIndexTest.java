package dev.ellipog.tasked.quest;

import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.armature.api.data.JsonDocument;
import dev.ellipog.armature.api.data.Problems;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.ellipog.tasked.quest.Fixtures.q;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        @DisplayName("two long titles 64px apart in one row warn")
        void closeTogetherInARowWarn() {
            // 64 is the spacing that produced the bug: three nodes 64px apart carrying titles around
            // 90-120px, so their labels were drawn through each other.
            Problems problems = problemsOf(Fixtures.file(
                    q(LONG_ID).at(0, 0).build(),
                    q(WIDER_ID).at(64, 0).build()));

            assertTrue(!problems.hasErrors(), "crowding is a warning, not a failure:");
            assertMentions(problems, "pixels apart in the same row");
            assertMentions(problems, "will overlap and run together");
        }

        @Test
        @DisplayName("the same two titles at 132px apart do not warn")
        void generousSpacingDoesNotWarn() {
            // 132 is what the shipped example questline now uses. If this ever starts warning, the
            // threshold has drifted away from the label cap the client actually applies.
            Problems problems = problemsOf(Fixtures.file(
                    q(LONG_ID).at(0, 0).build(),
                    q(WIDER_ID).at(132, 0).build()));

            assertDoesNotMention(problems, "pixels apart in the same row");
        }

        @Test
        @DisplayName("short titles 64px apart do not warn, because they fit")
        void shortTitlesDoNotWarn() {
            // The check is about the titles, not about the spacing on its own. Three characters is
            // about 18px, so 64px of room is ample -- and warning here would be noise, which is how a
            // check gets ignored.
            Problems problems = problemsOf(Fixtures.file(
                    q("aaa").at(0, 0).build(),
                    q("bbb").at(64, 0).build()));

            assertDoesNotMention(problems, "pixels apart in the same row");
        }

        @Test
        @DisplayName("quests 64px apart in different rows do not warn")
        void differentRowsDoNotWarn() {
            // A label is drawn *below* its node, so two labels on different rows never meet however
            // close the nodes are horizontally. Comparing every pair rather than only row-mates would
            // report a branch as a fault.
            Problems problems = problemsOf(Fixtures.file(
                    q(LONG_ID).at(0, 0).build(),
                    q(WIDER_ID).at(64, 64).build()));

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
                    Fixtures.fileWithChapter("\"id\": \"one\",", q(LONG_ID).at(0, 0).build()),
                    Fixtures.fileWithChapter("\"id\": \"two\",", q(WIDER_ID).at(64, 0).build()));

            assertDoesNotMention(problems, "pixels apart in the same row");
        }

        @Test
        @DisplayName("two quests at exactly the same spot are reported once, as a duplicate")
        void stackedQuestsAreNotAlsoReportedAsCrowded() {
            // A gap of 0 is a duplicate position, which checkDuplicatePositions already reports with a
            // better message. Reporting it twice -- once as stacked, once as crowded -- would train an
            // author to skim the output, and "these two titles will overlap" is not the interesting
            // part of two nodes being on top of each other.
            Problems problems = problemsOf(Fixtures.file(
                    q(LONG_ID).at(0, 0).build(),
                    q(WIDER_ID).at(0, 0).build()));

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
                    q(LONG_ID).at(0, 0).build(),
                    q(WIDER_ID).at(64, 0).build(),
                    q(LONG_ID + "_b").at(128, 0).build()));

            long crowded = problems.all().stream()
                    .filter(problem -> problem.message().contains("pixels apart in the same row"))
                    .count();
            assertEquals(2, crowded, "two adjacent pairs:" + messages(problems));
        }
    }

    // ------------------------------------------------------------------
    // The shipped questline
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the shipped example questline reports nothing at all")
    void theShippedExampleIsClean() {
        // Read from the mod's own resources rather than a fixture, so the file a fresh install seeds
        // is the one under test. Its spacing was 64 until the labelling bug, and this is what stops it
        // drifting back: a change to the example that reintroduces crowding fails here rather than on
        // someone's screen.
        String json = readResource("/tasked/default_quests/01_stone_age.json");
        Problems problems = problemsOf(json);

        assertTrue(problems.isEmpty(), "the shipped questline should be clean, but reported:"
                + messages(problems));
    }

    private static String readResource(String path) {
        try (var stream = QuestIndexTest.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new AssertionError("no resource at " + path
                        + " - it should be in tasked/common/src/main/resources");
            }
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        catch (java.io.IOException e) {
            throw new AssertionError("could not read " + path, e);
        }
    }
}
