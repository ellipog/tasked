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

import static dev.ellipog.tasked.quest.Fixtures.chapterWith;
import static dev.ellipog.tasked.quest.Fixtures.fileWithChapters;
import static dev.ellipog.tasked.quest.Fixtures.q;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A chapter's own gate, checked across the whole pack.
 *
 * <h2>Why every case here is an error rather than a warning</h2>
 *
 * <p>Because each one produces a chapter that can never be opened, and that is the quietest failure a
 * questline has: no log line, no symptom while the pack is being written, and a player looking at a
 * chapter that stays shut. The message is expected to carry the consequence as well as the fault, so
 * these assertions are on the wording a person will read rather than on a count.
 *
 * <p>What cannot be checked here is <b>cycles</b>: they are the graph pass in {@code ProgressionEngine}
 * and are asserted in {@code ProgressionEngineTest} (the chains) and {@code QuestLoaderTest} (that the
 * author is actually told).
 */
@DisplayName("chapter dependencies")
class ChapterDependencyTest {

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** Indexes, keeping the problems — the same walk {@link QuestLoader} takes. */
    private static Problems problemsOf(String... jsons) {
        Problems problems = new Problems();
        List<LoadedQuestFile> loaded = new ArrayList<>();
        for (int i = 0; i < jsons.length; i++) {
            String name = "test" + i + ".json";
            JsonDocument document = Fixtures.document(name, jsons[i]);
            loaded.add(new LoadedQuestFile(Path.of(name), name, document, Fixtures.decode(name, document)));
        }
        QuestIndex.build(loaded, problems);
        return problems;
    }

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

    /** Two chapters in one group, the second waiting on the first. */
    private static String[] twoChapters(String firstExtras, String secondExtras) {
        return new String[] {
                fileWithChapters(
                        chapterWith("first", firstExtras, q("a").build()),
                        chapterWith("second", secondExtras, q("b").build())),
        };
    }

    // ------------------------------------------------------------------
    // The references
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("references")
    class References {

        @Test
        @DisplayName("a chapter waiting on a chapter that does not exist is an error, with a suggestion")
        void danglingChapterReference() {
            Problems problems = problemsOf(twoChapters("", "\"dependsOn\": [\"frist\"],"));

            assertTrue(problems.hasErrors(), "it can never be opened:" + messages(problems));
            assertMentions(problems, "no chapter with id or alias \"frist\" exists");
            assertMentions(problems, "did you mean \"first\"?");
            assertMentions(problems, "this chapter can never be opened");
        }

        @Test
        @DisplayName("a chapter waiting on itself is an error")
        void selfDependency() {
            // The same fault as a quest depending on itself, one level up, and the same reason: no
            // amount of play satisfies it.
            Problems problems = problemsOf(twoChapters("", "\"dependsOn\": [\"second\"],"));

            assertTrue(problems.hasErrors(), "it can never be opened:" + messages(problems));
            assertMentions(problems, "this chapter waits on itself (\"second\")");
        }

        @Test
        @DisplayName("an alias resolves a chapter reference")
        void aliasesResolve() {
            // The whole reason a reference is a name rather than a folder path: renaming a chapter must
            // not orphan the chapters that waited on it, exactly as renaming a quest must not orphan the
            // quests that depend on it.
            Problems problems = problemsOf(fileWithChapters(
                    chapterWith("first", "\"aliases\": [\"original\"], \"completesWhen\": [\"a\"],",
                            q("a").build()),
                    chapterWith("second", "\"dependsOn\": [\"original\"],", q("b").build())));

            assertTrue(!problems.hasErrors(), "an alias should resolve:" + messages(problems));
        }

        @Test
        @DisplayName("a chapter and a quest may share an id, and a chapter reference means the chapter")
        void theTwoNamespacesAreSeparate() {
            // `QuestIndex` keys chapters and quests in separate tables and says a shared id is legal --
            // which is exactly why a reference is a `ChapterRef` and not a string that could be either.
            // A quest named `twin` and a chapter named `twin` are both in this pack, and the reference
            // resolves to the chapter: if it resolved to the quest, this would be an error rather than a
            // working two-chapter pack.
            Problems problems = problemsOf(fileWithChapters(
                    chapterWith("twin", "\"completesWhen\": [\"twin\"],", q("twin").build()),
                    chapterWith("other", "\"dependsOn\": [\"twin\"],", q("b").build())));

            assertTrue(!problems.hasErrors(),
                    "the reference resolves to the chapter, so nothing is wrong:" + messages(problems));
        }

        @Test
        @DisplayName("a completion naming a quest that does not exist is an error")
        void danglingCompletion() {
            Problems problems = problemsOf(twoChapters("\"completesWhen\": [\"nowhere\"],", ""));

            assertTrue(problems.hasErrors(), "it never reports completed:" + messages(problems));
            assertMentions(problems, "no quest with id or alias \"nowhere\" exists");
            assertMentions(problems, "never reports completed");
        }

        @Test
        @DisplayName("minRequired above the number of chapter dependencies is an error")
        void minRequiredAboveTheList() {
            Problems problems = problemsOf(twoChapters("",
                    "\"dependsOn\": [\"first\"], \"minRequired\": 2,"));

            assertTrue(problems.hasErrors(), "it can never be opened:" + messages(problems));
            assertMentions(problems, "minRequired is 2 but there are only 1 chapter dependencies");
        }
    }

    // ------------------------------------------------------------------
    // Completed edges need a declared completion
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a completed bar needs a completesWhen")
    class CompletedEdges {

        @Test
        @DisplayName("all_completed onto a chapter with no completesWhen is an error")
        void completedWithoutACompletion() {
            // The fault no single file can see, and the one that reads as a chapter sitting shut: the
            // dependent asks its predecessor to be finished, and the predecessor never says what
            // finished means.
            Problems problems = problemsOf(twoChapters("", "\"dependsOn\": [\"first\"],"));

            assertTrue(problems.hasErrors(), "it can never be opened:" + messages(problems));
            assertMentions(problems, "only 0 of them declare a completesWhen");
            assertMentions(problems, "\"first\" declares no completesWhen");
        }

        @Test
        @DisplayName("declaring the completion is enough")
        void aDeclaredCompletionSatisfiesTheEdge() {
            Problems problems = problemsOf(twoChapters("\"completesWhen\": [\"a\"],",
                    "\"dependsOn\": [\"first\"],"));

            assertTrue(!problems.hasErrors(), "the edge can be reached:" + messages(problems));
            assertDoesNotMention(problems, "completesWhen");
        }

        @Test
        @DisplayName("one_completed needs one of them to be completable, not all")
        void oneCompletedCountsTheCompletableOnes() {
            // Counted rather than checked per edge, and this is the case that makes that the honest
            // question: with two dependencies and `one_completed`, a single declared completion is
            // enough for the gate to open -- so reporting the other one would be a false alarm.
            Problems problems = problemsOf(fileWithChapters(
                    chapterWith("first", "\"completesWhen\": [\"a\"],", q("a").build()),
                    chapterWith("second", "", q("b").build()),
                    chapterWith("third", "\"dependsOn\": [\"first\", \"second\"],"
                            + " \"prerequisiteMode\": \"one_completed\",", q("c").build())));

            assertTrue(!problems.hasErrors(), "one completable dependency is enough:" + messages(problems));
        }

        @Test
        @DisplayName("a started bar needs no completion declared at all")
        void startedBarsNeedNothing() {
            // "Opened" is what a started-based bar asks for, and every chapter reaches that on its own
            // gate -- so demanding a completesWhen here would refuse a file that works.
            Problems problems = problemsOf(twoChapters("",
                    "\"dependsOn\": [\"first\"], \"prerequisiteMode\": \"all_started\","));

            assertTrue(!problems.hasErrors(), "a started bar is satisfiable:" + messages(problems));
        }

        @Test
        @DisplayName("a chapter with no dependencies is never asked for a completion")
        void noDependenciesNoDemand() {
            Problems problems = problemsOf(twoChapters("", ""));

            assertTrue(!problems.hasErrors(), "nothing to wait on:" + messages(problems));
            assertEquals(0, problems.errorCount(), messages(problems));
        }
    }

    // ------------------------------------------------------------------
    // What the codec actually reads
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the fields")
    class Fields {

        @Test
        @DisplayName("the gate is read off the chapter, flat beside its title")
        void theGateDecodes() {
            // The MapCodec's whole point: an author writes these beside the chapter's title rather than
            // nested in a rules object. Decoding is what says the two shapes the format has -- the
            // version-1 chapter and the folder manifest -- read the same five fields, since both go
            // through this one codec.
            QuestIndex index = Fixtures.indexOf(Fixtures.fileWithChapters(
                    chapterWith("first", "\"completesWhen\": [\"a\"],", q("a").build()),
                    chapterWith("second", "\"dependsOn\": [\"first\"],"
                            + " \"prerequisiteMode\": \"one_started\", \"minRequired\": 1,"
                            + " \"hideUntilDependenciesComplete\": true,"
                            + " \"defaultHideUntilDependenciesComplete\": true,"
                            + " \"defaultHideUntilDependenciesVisible\": true,", q("b").build())));

            ChapterRules rules = index.chapter("second").orElseThrow().chapter().rules();
            assertEquals(List.of("first"),
                    rules.dependsOn().stream().map(ChapterRef::id).toList());
            assertEquals(PrerequisiteMode.ONE_STARTED, rules.prerequisiteMode());
            assertEquals(1, rules.minRequired());
            assertTrue(rules.hideUntilDependenciesComplete(), "the hiding flag travels");
            assertEquals(1, rules.requiredCount(), "one of one, which minRequired agrees with");
            assertTrue(rules.waits(), "and it does wait on something");
            assertTrue(rules.defaultHideUntilDependenciesComplete(),
                    "the quests' own default is a separate field from the row-hiding one above");
            assertTrue(rules.defaultHideUntilDependenciesVisible());

            ChapterRules first = index.chapter("first").orElseThrow().chapter().rules();
            assertEquals(List.of("a"),
                    first.completesWhen().stream().map(QuestRef::id).toList());
            assertEquals(PrerequisiteMode.ALL_COMPLETED, first.prerequisiteMode(),
                    "the gate's own mode defaults to the one that asks the most");
        }

        @Test
        @DisplayName("a chapter that says nothing about its gate gets the defaults")
        void defaults() {
            QuestIndex index = Fixtures.indexOf(
                    Fixtures.fileWithChapters(chapterWith("only", "", q("a").build())));

            ChapterRules rules = index.chapter("only").orElseThrow().chapter().rules();

            assertEquals(ChapterRules.DEFAULT, rules);
            assertTrue(!rules.waits(), "nothing waited on, so nothing to satisfy");
            assertEquals(0, rules.requiredCount(), "an empty rule is met");
            assertTrue(!rules.hideUntilDependenciesComplete(),
                    "shown until asked otherwise: a chapter a reader cannot see is one that looks missing");
        }
    }
}
