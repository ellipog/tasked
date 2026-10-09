package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.progress.QuestState;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The visibility rules, asserted where a test can see them.
 *
 * <p>Each flag gets its own case, and the two that are more than a lookup get the cases that make them
 * more than a lookup: {@code hideUntilDependenciesVisible} recurses through a chain and terminates on a
 * cycle, and {@code invisibleUntilTasks} only does anything underneath {@code invisible}. Those are the
 * three ways a flag like this goes wrong -- it hides too much, it hides forever, or it does nothing at
 * all -- and each of them is a rule a screenshot cannot distinguish from a working one.
 */
@DisplayName("The visibility rules")
class QuestVisibilityTest {

    /** One quest's flags, as the lookup would answer them. */
    private record Flags(boolean invisible, int invisibleUntilTasks,
                         boolean hideUntilDependenciesComplete, boolean hideUntilDependenciesVisible,
                         List<String> dependencies) {

        static Flags plain() {
            return new Flags(false, 0, false, false, List.of());
        }

        static Flags hidden() {
            return new Flags(true, 0, false, false, List.of());
        }

        static Flags hiddenUntilVisible(String... dependencies) {
            return new Flags(false, 0, false, true, List.of(dependencies));
        }
    }

    /** A lookup over a fixed set of quests, with every id it does not know reading as a plain quest. */
    private static QuestVisibility.Lookup lookup(Map<String, Flags> quests) {
        return new QuestVisibility.Lookup() {
            private Flags flags(String id) {
                return quests.getOrDefault(id, Flags.plain());
            }

            @Override
            public boolean invisible(String id) {
                return flags(id).invisible();
            }

            @Override
            public int invisibleUntilTasks(String id) {
                return flags(id).invisibleUntilTasks();
            }

            @Override
            public boolean hideUntilDependenciesComplete(String id) {
                return flags(id).hideUntilDependenciesComplete();
            }

            @Override
            public boolean hideUntilDependenciesVisible(String id) {
                return flags(id).hideUntilDependenciesVisible();
            }

            @Override
            public List<String> dependencies(String id) {
                return flags(id).dependencies();
            }
        };
    }

    private static boolean visible(Map<String, Flags> quests, String id,
                                   Function<String, QuestState> states,
                                   Function<String, Boolean> ruleMet,
                                   Function<String, Integer> taskProgress) {
        return QuestVisibility.visible(id, lookup(quests), states, ruleMet, taskProgress);
    }

    private static Function<String, QuestState> locked() {
        return id -> QuestState.LOCKED;
    }

    private static Function<String, Boolean> unmet() {
        return id -> false;
    }

    private static Function<String, Integer> noProgress() {
        return id -> 0;
    }

    @Nested
    @DisplayName("invisible and its task count")
    class Invisible {

        @Test
        @DisplayName("an invisible quest is hidden until it is completed")
        void hiddenUntilCompleted() {
            Map<String, Flags> quests = Map.of("egg", Flags.hidden());
            assertFalse(visible(quests, "egg", locked(), unmet(), noProgress()), "a locked invisible quest");
            assertFalse(visible(quests, "egg", id -> QuestState.STARTED, unmet(), noProgress()),
                    "an invisible quest that is under way is still hidden");
            assertTrue(visible(quests, "egg", id -> QuestState.COMPLETED, unmet(), noProgress()),
                    "a completed quest must show itself, or the flag would lose content for good");
        }

        @Test
        @DisplayName("invisibleUntilTasks does nothing unless invisible is also set")
        void theCountNeedsTheFlag() {
            // The count is a qualifier, not a second way to be hidden. A quest with only the count is a
            // plain visible quest -- and an author who set the count before deciding to hide the quest
            // should not lose it from the canvas in the meantime.
            Map<String, Flags> quests = Map.of("egg", new Flags(false, 3, false, false, List.of()));
            assertTrue(visible(quests, "egg", locked(), unmet(), noProgress()),
                    "a count without `invisible` hid the quest");
        }

        @Test
        @DisplayName("with invisible set, the count is the reveal")
        void theCountReveals() {
            Map<String, Flags> quests = Map.of("egg", new Flags(true, 3, false, false, List.of()));
            assertFalse(visible(quests, "egg", locked(), unmet(), id -> 2), "two of three tasks");
            assertTrue(visible(quests, "egg", locked(), unmet(), id -> 3), "three of three tasks");
            assertTrue(visible(quests, "egg", locked(), unmet(), id -> 9),
                    "progress past the count should not hide it again");
        }
    }

    @Nested
    @DisplayName("hidden by prerequisites")
    class ByPrerequisites {

        @Test
        @DisplayName("hideUntilDependenciesComplete follows the rule, not a count of completions")
        void followsTheRule() {
            Map<String, Flags> quests = Map.of("a",
                    new Flags(false, 0, true, false, List.of("b", "c")));
            // The rule is whatever the caller says it is -- `DependencyProgress` knows about `minRequired`
            // and the started-based modes, and this flag must not grow a second, worse copy of it.
            assertFalse(visible(quests, "a", locked(), unmet(), noProgress()),
                    "hidden while the rule is unmet");
            assertTrue(visible(quests, "a", locked(), id -> true, noProgress()),
                    "shown the moment the rule is met, even though nothing is completed");
        }

        @Test
        @DisplayName("hideUntilDependenciesVisible reveals a chain one link at a time")
        void revealsThroughAChain() {
            // a is hidden until one of its prerequisites is visible; b is hidden until one of *its* is;
            // c is a plain quest and so always visible. The chain must light up from c backwards.
            Map<String, Flags> quests = Map.of(
                    "a", Flags.hiddenUntilVisible("b"),
                    "b", Flags.hiddenUntilVisible("c"),
                    "c", Flags.plain());
            assertTrue(visible(quests, "a", locked(), unmet(), noProgress()),
                    "the chain did not reveal itself from its visible end");

            Map<String, Flags> cut = Map.of(
                    "a", Flags.hiddenUntilVisible("b"),
                    "b", Flags.hidden(),
                    "c", Flags.plain());
            assertFalse(visible(cut, "a", locked(), unmet(), noProgress()),
                    "a dependency that is itself hidden must not reveal its dependent");
        }

        @Test
        @DisplayName("a quest with no prerequisites and this flag is visible, like its sibling flag")
        void anEmptyRuleIsMet() {
            // This test used to assert the opposite, with the reasoning that "an author who set this flag
            // on a root quest meant to hide it". That reasoning is what changed, and the case for it is
            // worth keeping: an empty *rule* is met everywhere else in this file — `hideUntilDependenciesComplete`
            // on a root quest shows it, because `requiredCount` of zero dependencies is zero — so the two
            // sibling flags disagreed about the same empty list. An author who picked the wrong one of two
            // adjacent names got a quest that never appeared, with no message and nothing on the canvas to
            // explain it. "Hide this root quest" already has a flag whose name says so: `invisible`.
            Map<String, Flags> quests = Map.of("a", Flags.hiddenUntilVisible());
            assertTrue(visible(quests, "a", locked(), unmet(), noProgress()),
                    "with no prerequisites to be revealed by, the empty rule is satisfied -- which is what "
                            + "the other flag about prerequisites already says");
        }

        @Test
        @DisplayName("a cycle terminates, and terminates visible")
        void aCycleDoesNotHideForever() {
            // Two quests that each wait for the other would otherwise be hidden for ever, with no error
            // and nothing on screen to explain the empty chapter. The loader reports the cycle; the
            // client shows the quests.
            Map<String, Flags> quests = Map.of(
                    "a", Flags.hiddenUntilVisible("b"),
                    "b", Flags.hiddenUntilVisible("a"));
            assertTrue(visible(quests, "a", locked(), unmet(), noProgress()), "the walk did not terminate");
            assertTrue(visible(quests, "b", locked(), unmet(), noProgress()), "the walk did not terminate");
        }

        @Test
        @DisplayName("a completed quest is shown whatever the hiding flags say")
        void completedWins() {
            Map<String, Flags> quests = Map.of("a",
                    new Flags(false, 0, true, true, List.of("b")));
            assertTrue(visible(quests, "a", id -> QuestState.COMPLETED, unmet(), noProgress()),
                    "the rule is unmet and the dependency is unknown, and the quest is done: it shows");
        }
    }

    @Nested
    @DisplayName("the card's two")
    class TheCard {

        @Test
        @DisplayName("hideTextUntilComplete withholds the description until the quest is done")
        void text() {
            assertFalse(QuestVisibility.showsText(true, QuestState.LOCKED));
            assertFalse(QuestVisibility.showsText(true, QuestState.STARTED));
            assertTrue(QuestVisibility.showsText(true, QuestState.COMPLETED));
            assertTrue(QuestVisibility.showsText(false, QuestState.LOCKED),
                    "without the flag the description is always readable");
        }

        @Test
        @DisplayName("hideDetailsUntilStartable withholds the tasks until the quest can be started")
        void details() {
            assertFalse(QuestVisibility.showsDetails(true, QuestState.LOCKED));
            assertTrue(QuestVisibility.showsDetails(true, QuestState.UNLOCKED));
            assertTrue(QuestVisibility.showsDetails(true, QuestState.STARTED));
            assertTrue(QuestVisibility.showsDetails(false, QuestState.LOCKED));
        }

        @Test
        @DisplayName("hideDependencyLines is the flag, inverted once")
        void lines() {
            assertTrue(QuestVisibility.drawsDependencyLines(false));
            assertFalse(QuestVisibility.drawsDependencyLines(true));
        }

        @Test
        @DisplayName("hideDependentLines is the outgoing flag, inverted once beside it")
        void outgoingLines() {
            assertTrue(QuestVisibility.drawsDependentLines(false),
                    "without the flag the lines a quest sends on are drawn");
            assertFalse(QuestVisibility.drawsDependentLines(true),
                    "with it the quest is drawn and its outgoing lines are not");
        }
    }
}
