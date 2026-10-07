package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.progress.QuestState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which chapters a reader is shown.
 *
 * <h2>Why this is asserted rather than clicked through</h2>
 *
 * <p>Because every failure here is a <b>missing row</b>, and a missing row is the one symptom nobody can
 * tell from "that chapter does not exist": a reader has no way to report a chapter they never saw. The
 * rule itself is two lines, but it is two lines that used to be one wrong line — the old version only
 * asked whether the author had asked to be withheld, so a chapter of fifty quests that were all still
 * hidden drew a row into an empty canvas.
 */
@DisplayName("chapter visibility")
class ChapterVisibilityTest {

    /** A reader's answers, with sensible defaults, so a test says only what it is about. */
    private static final class Answers implements ChapterVisibility.Lookup {

        private final Map<String, Boolean> hides = new HashMap<>();
        private final Map<String, QuestState> states = new HashMap<>();
        private final Set<String> withVisibleQuests = new HashSet<>();

        Answers hides(String chapterId) {
            hides.put(chapterId, true);
            return this;
        }

        Answers state(String chapterId, QuestState state) {
            states.put(chapterId, state);
            return this;
        }

        /** The chapter has at least one quest the reader can see. */
        Answers shows(String chapterId) {
            withVisibleQuests.add(chapterId);
            return this;
        }

        @Override
        public boolean hidesUntilDependenciesComplete(String chapterId) {
            return hides.getOrDefault(chapterId, false);
        }

        @Override
        public QuestState state(String chapterId) {
            return states.getOrDefault(chapterId, QuestState.UNLOCKED);
        }

        @Override
        public boolean hasVisibleQuest(String chapterId) {
            return withVisibleQuests.contains(chapterId);
        }
    }

    private static boolean visible(Answers answers, String chapterId) {
        return ChapterVisibility.visible(chapterId, answers, false);
    }

    @Nested
    @DisplayName("a chapter with nothing to show")
    class NothingToShow {

        @Test
        @DisplayName("is not a row: fifty quests, none of them visible yet")
        void everyQuestStillHidden() {
            // The reported case, and the reason the rule exists: the chapter is real, holds real
            // content, and has nothing this reader may look at -- so a row is a door into an empty
            // canvas, which reads as a pack with missing content.
            Answers answers = new Answers().state("the_deep", QuestState.UNLOCKED);

            assertFalse(visible(answers, "the_deep"));
        }

        @Test
        @DisplayName("is not a row when it holds no quests at all")
        void emptyChapter() {
            // The state between creating a chapter and writing its first quest. The author's own screen
            // shows it -- see the authoring case below -- and a reader's book does not, because there is
            // nothing behind the row.
            assertFalse(visible(new Answers(), "brand_new"));
        }

        @Test
        @DisplayName("is a row the moment one quest becomes visible")
        void oneVisibleQuestIsEnough() {
            // Aggregation, not a count: one revealed quest among fifty is a chapter worth opening.
            Answers answers = new Answers().shows("the_deep");

            assertTrue(visible(answers, "the_deep"));
        }
    }

    @Nested
    @DisplayName("a chapter with an unmet gate")
    class Gated {

        @Test
        @DisplayName("is still a row by default, so the road ahead is on the map")
        void listedUnlessWithheld() {
            // The default, and the deliberate opposite of the rule above: a gated chapter whose quests
            // are drawn as locked nodes has something to show. The row is dimmed and its hover names what
            // it waits for -- see `chapterLocked` and `chapterGateLine` in the screen.
            Answers answers = new Answers().shows("the_deep").state("the_deep", QuestState.LOCKED);

            assertTrue(visible(answers, "the_deep"));
        }

        @Test
        @DisplayName("is withheld when its author asked for it")
        void withheldWhenAsked() {
            Answers answers = new Answers().shows("the_deep").state("the_deep", QuestState.LOCKED)
                    .hides("the_deep");

            assertFalse(visible(answers, "the_deep"));
        }

        @Test
        @DisplayName("and that flag stops mattering once the gate is met")
        void shownOnceOpen() {
            // The flag is "not until the gate is met", not "not until completed": once the chapter is
            // open -- or started, or finished -- the row is the ordinary one.
            for (QuestState open : new QuestState[] {
                    QuestState.UNLOCKED, QuestState.STARTED, QuestState.COMPLETED }) {
                Answers answers = new Answers().shows("the_deep").state("the_deep", open).hides("the_deep");
                assertTrue(visible(answers, "the_deep"), "withheld while " + open);
            }
        }

        @Test
        @DisplayName("a withheld chapter with nothing visible is hidden for both reasons at once")
        void bothRulesAgree() {
            // Not a third rule, but worth pinning: the two ways to be hidden overlap, and neither order
            // of asking can produce a visible answer.
            Answers answers = new Answers().state("the_deep", QuestState.LOCKED).hides("the_deep")
                    .shows("elsewhere");

            assertFalse(visible(answers, "the_deep"));
            assertTrue(visible(answers, "elsewhere"), "and the chapter beside it is unaffected");
        }
    }

    @Nested
    @DisplayName("an author")
    class Authoring {

        @Test
        @DisplayName("sees every chapter, including an empty one and one whose quests are all hidden")
        void seesEverything() {
            // The half that keeps the feature usable by the person who asked for it: a chapter that
            // vanished from the book while its content was still being written could not be edited at
            // all. Same split `questsIn` makes for quests.
            Answers answers = new Answers().state("the_deep", QuestState.LOCKED).hides("the_deep");

            assertTrue(ChapterVisibility.visible("the_deep", answers, true),
                    "a withheld chapter is still a row to an author");
            assertTrue(ChapterVisibility.visible("brand_new", answers, true),
                    "and so is one with nothing in it");
        }
    }

    @Test
    @DisplayName("no chapter at all is not a hidden chapter")
    void nullIsNotHidden() {
        // What a screen asks while the tree is still arriving, or for a book with no chapters. Answering
        // "hidden" there would shut a book over a fact nobody stated.
        assertTrue(ChapterVisibility.visible(null, new Answers(), false));
        assertTrue(ChapterVisibility.visible(null, new Answers(), true));
    }
}
