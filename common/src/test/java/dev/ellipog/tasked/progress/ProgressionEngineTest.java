package dev.ellipog.tasked.progress;

import dev.ellipog.tasked.quest.Fixtures;
import dev.ellipog.tasked.quest.Quest;
import dev.ellipog.tasked.quest.QuestIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.ellipog.tasked.quest.Fixtures.q;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The progression rules.
 *
 * <h2>Why these are worth testing rather than playing</h2>
 *
 * <p>A quest that refuses to unlock looks the same whether the cause is a prerequisite mode, a
 * mistyped id, a cycle, or a repeatable quest still on cooldown. Playing finds the symptom; only a
 * test finds which rule produced it. And these are pure functions — quests in, state out — so
 * there is no reason to need a game to check them.
 *
 * <p>Everything here goes through the public API: build a quest index, hand the engine some
 * progress and a clock, read the states back. No reflection, no reaching inside.
 *
 * <h2>A naming rule for this file</h2>
 *
 * <p>The static helpers below are named <b>indexOf</b>, <b>completedQuests</b> and
 * <b>startedQuests</b> rather than the obvious {@code index}, {@code completed} and
 * {@code started}. That is not decoration: a method in an enclosing class is shadowed by any
 * method of the same name in a {@code @Nested} class, even one with a completely different
 * signature. A test called {@code started()} therefore silently redirected every
 * {@code started(index, "a")} call in that class to itself, and the compiler reported it as
 * "required: no arguments, found: QuestIndex,String" — which reads like a mistake at the call
 * site and is not. Naming the helpers unambiguously means no future test can reintroduce it.
 */
@DisplayName("ProgressionEngine")
class ProgressionEngineTest {

    /** An arbitrary game time. Cooldown tests add to it. */
    private static final long NOW = 10_000L;

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static QuestIndex indexOf(String... quests) {
        return Fixtures.indexOf(Fixtures.file(quests));
    }

    private static QuestIndex indexWithChapter(String chapterExtras, String... quests) {
        return Fixtures.indexOf(Fixtures.fileWithChapter(chapterExtras, quests));
    }

    private static QuestState stateOf(QuestIndex index, TeamProgress progress, String id) {
        return ProgressionEngine.resolve(index, progress, NOW).stateOf(Fixtures.quest(index, id));
    }

    /** Progress where the named quests have been finished. */
    private static TeamProgress completedQuests(QuestIndex index, String... ids) {
        TeamProgress progress = TeamProgress.empty();
        for (String id : ids) {
            progress = progress.put(Fixtures.quest(index, id),
                    QuestProgress.NONE.completedAt(NOW).withRewardsClaimed(true));
        }
        return progress;
    }

    /**
     * Progress where the named quests have been started but not finished.
     *
     * <p>Recorded as task progress rather than a state, because that is how the engine decides
     * between UNLOCKED and STARTED — the stored state field is not consulted for that.
     */
    private static TeamProgress startedQuests(QuestIndex index, String... ids) {
        TeamProgress progress = TeamProgress.empty();
        for (String id : ids) {
            progress = progress.put(Fixtures.quest(index, id), QuestProgress.NONE.recordTask(0, 1));
        }
        return progress;
    }

    // ------------------------------------------------------------------
    // Dependencies
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("with no dependencies")
    class NoDependencies {

        @Test
        @DisplayName("a quest is unlocked straight away")
        void unlocked() {
            QuestIndex index = indexOf(q("a").build());
            assertEquals(QuestState.UNLOCKED, stateOf(index, TeamProgress.empty(), "a"));
        }

        @Test
        @DisplayName("and STARTED, not merely unlocked, once a task has progress against it")
        void startedOnceATaskHasProgress() {
            QuestIndex index = indexOf(q("a").build());
            assertEquals(QuestState.STARTED, stateOf(index, startedQuests(index, "a"), "a"));
        }
    }

    @Nested
    @DisplayName("in the default ALL_COMPLETED mode")
    class AllCompleted {

        @Test
        @DisplayName("an unmet dependency keeps the quest locked")
        void lockedBeforeDependency() {
            QuestIndex index = indexOf(q("a").build(), q("b").dependsOn("a").build());
            assertEquals(QuestState.LOCKED, stateOf(index, TeamProgress.empty(), "b"));
        }

        @Test
        @DisplayName("completing it unlocks the dependent")
        void unlockedAfterDependency() {
            QuestIndex index = indexOf(q("a").build(), q("b").dependsOn("a").build());
            assertEquals(QuestState.UNLOCKED, stateOf(index, completedQuests(index, "a"), "b"));
        }

        @Test
        @DisplayName("with two dependencies, one of them is not enough")
        void needsEveryDependency() {
            QuestIndex index = indexOf(q("a").build(), q("b").build(), q("c").dependsOn("a", "b").build());

            assertEquals(QuestState.LOCKED, stateOf(index, completedQuests(index, "a"), "c"));
            assertEquals(QuestState.LOCKED, stateOf(index, completedQuests(index, "b"), "c"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, completedQuests(index, "a", "b"), "c"));
        }

        @Test
        @DisplayName("a dependency that does not exist leaves it locked rather than crashing")
        void missingDependency() {
            // The loader reports this as an error at load time. Locking is the safe reading: a
            // quest that cannot be unlocked is visibly broken, whereas one that unlocks for the
            // wrong reason is not.
            QuestIndex index = indexOf(q("a").dependsOn("nowhere").build());
            assertEquals(QuestState.LOCKED, stateOf(index, TeamProgress.empty(), "a"));
        }
    }

    @Nested
    @DisplayName("in ONE_COMPLETED mode")
    class OneCompleted {

        private QuestIndex paths() {
            return indexOf(q("a").build(), q("b").build(),
                    q("c").dependsOn("a", "b").prerequisiteMode("one_completed").build());
        }

        @Test
        @DisplayName("either dependency alone unlocks it")
        void oneIsEnough() {
            QuestIndex index = paths();
            assertEquals(QuestState.UNLOCKED, stateOf(index, completedQuests(index, "a"), "c"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, completedQuests(index, "b"), "c"));
        }

        @Test
        @DisplayName("neither done leaves it locked")
        void noneIsNotEnough() {
            QuestIndex index = paths();
            assertEquals(QuestState.LOCKED, stateOf(index, TeamProgress.empty(), "c"));
        }
    }

    @Nested
    @DisplayName("in ALL_STARTED mode")
    class AllStarted {

        @Test
        @DisplayName("a dependency that is merely unlocked does not count")
        void unlockedIsNotStarted() {
            QuestIndex index = indexOf(q("a").build(),
                    q("b").dependsOn("a").prerequisiteMode("all_started").build());
            assertEquals(QuestState.LOCKED, stateOf(index, TeamProgress.empty(), "b"));
        }

        @Test
        @DisplayName("one with task progress counts, without being complete")
        void taskProgressCounts() {
            QuestIndex index = indexOf(q("a").build(),
                    q("b").dependsOn("a").prerequisiteMode("all_started").build());
            assertEquals(QuestState.UNLOCKED, stateOf(index, startedQuests(index, "a"), "b"));
        }

        @Test
        @DisplayName("and a completed dependency counts too")
        void completedCounts() {
            QuestIndex index = indexOf(q("a").build(),
                    q("b").dependsOn("a").prerequisiteMode("all_started").build());
            assertEquals(QuestState.UNLOCKED, stateOf(index, completedQuests(index, "a"), "b"));
        }
    }

    @Nested
    @DisplayName("with minRequired")
    class MinRequired {

        @Test
        @DisplayName("any two of three will do, in any combination")
        void countsInAnyOrder() {
            QuestIndex index = indexOf(q("a").build(), q("b").build(), q("c").build(),
                    q("d").dependsOn("a", "b", "c").minRequired(2).build());

            assertEquals(QuestState.LOCKED, stateOf(index, TeamProgress.empty(), "d"));
            assertEquals(QuestState.LOCKED, stateOf(index, completedQuests(index, "a"), "d"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, completedQuests(index, "a", "c"), "d"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, completedQuests(index, "b", "c"), "d"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, completedQuests(index, "a", "b", "c"), "d"));
        }

        @Test
        @DisplayName("it overrides the mode rather than combining with it")
        void overridesMode() {
            // ALL_COMPLETED would demand all three; minRequired says two. Two should win, since
            // that is the whole reason the field exists.
            QuestIndex index = indexOf(q("a").build(), q("b").build(), q("c").build(),
                    q("d").dependsOn("a", "b", "c").minRequired(2)
                            .prerequisiteMode("all_completed").build());
            assertEquals(QuestState.UNLOCKED, stateOf(index, completedQuests(index, "a", "b"), "d"));
        }

        @Test
        @DisplayName("a count larger than the dependency list still unlocks when all are done")
        void clampedToDependencyCount() {
            // The validator flags this as an error, because such a quest can never unlock. The
            // engine clamps rather than refusing to resolve, so a bad file still loads and every
            // other quest in it still works.
            QuestIndex index = indexOf(q("a").build(), q("b").build(),
                    q("c").dependsOn("a").minRequired(5).build());
            assertEquals(QuestState.UNLOCKED, stateOf(index, completedQuests(index, "a"), "c"));
        }
    }

    // ------------------------------------------------------------------
    // A cap on how many dependents may complete
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("with maxCompletableDependents")
    class Capped {

        /** One quest three branches depend on, and the cap is the test's variable. */
        private QuestIndex branches(int cap) {
            return indexOf(q("root").noTasks().maxCompletableDependents(cap).build(),
                    q("left").dependsOn("root").build(),
                    q("right").dependsOn("root").build(),
                    q("middle").dependsOn("root").build());
        }

        @Test
        @DisplayName("a cap of one lets one branch through and locks the others")
        void oneOfThree() {
            QuestIndex index = branches(1);
            TeamProgress progress = completedQuests(index, "left");

            assertEquals(QuestState.COMPLETED, stateOf(index, progress, "left"));
            assertEquals(QuestState.LOCKED, stateOf(index, progress, "right"),
                    "a second branch completed past the cap");
            assertEquals(QuestState.LOCKED, stateOf(index, progress, "middle"));
        }

        @Test
        @DisplayName("with nothing taken, every branch is still available")
        void allAvailableBeforeTheCapIsReached() {
            QuestIndex index = branches(2);
            // The root completed, so the branches are unlocked by their dependency -- which is what
            // this test is about: before the cap is reached, a cap changes nothing.
            TeamProgress progress = completedQuests(index, "root");

            assertEquals(QuestState.UNLOCKED, stateOf(index, progress, "left"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, progress, "right"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, progress, "middle"));
        }

        @Test
        @DisplayName("a cap of two leaves exactly one branch locked")
        void twoOfThree() {
            QuestIndex index = branches(2);
            TeamProgress progress = completedQuests(index, "left", "right");

            assertEquals(QuestState.COMPLETED, stateOf(index, progress, "left"));
            assertEquals(QuestState.COMPLETED, stateOf(index, progress, "right"));
            assertEquals(QuestState.LOCKED, stateOf(index, progress, "middle"));
        }

        @Test
        @DisplayName("a cap of zero is no cap at all")
        void zeroIsNoCap() {
            QuestIndex index = branches(0);
            TeamProgress progress = completedQuests(index, "root", "left", "right");

            assertEquals(QuestState.UNLOCKED, stateOf(index, progress, "middle"),
                    "a cap of zero should not lock anything");
        }

        @Test
        @DisplayName("a dependent that completed before the cap was reached keeps its completion")
        void completedDependentsKeepTheirCompletion() {
            // The cap decides which branches are still available, not which ones a player has already
            // taken: a completed quest that lost its completion would lose its rewards with it.
            QuestIndex index = branches(1);
            TeamProgress progress = completedQuests(index, "left", "right");

            assertEquals(QuestState.COMPLETED, stateOf(index, progress, "left"));
            assertEquals(QuestState.COMPLETED, stateOf(index, progress, "right"),
                    "a cap turned a completed branch back into a locked one");
            assertEquals(QuestState.LOCKED, stateOf(index, progress, "middle"));
        }

        @Test
        @DisplayName("the cap counts a dependent named by alias, and one in another chapter")
        void theCapCountsEveryDependent() {
            // A cap is a statement about the graph, and the graph crosses files: a dependent that named
            // the root by alias, or that lives in another chapter, is still a dependent.
            QuestIndex index = Fixtures.indexOf(Fixtures.fileWithChapters(
                    Fixtures.chapter("one", q("root").noTasks().alias("the_root")
                                    .maxCompletableDependents(1).build(),
                            q("left").dependsOn("the_root").build()),
                    Fixtures.chapter("two", q("right").dependsOn("root").build())));
            TeamProgress progress = completedQuests(index, "left");

            assertEquals(QuestState.LOCKED, stateOf(index, progress, "right"),
                    "a dependent in another chapter was not counted against the cap");
        }
    }

    // ------------------------------------------------------------------
    // Exclusive branches
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("with an exclusive group")
    class Exclusive {

        private QuestIndex paths() {
            return indexOf(q("sword").exclusiveGroup("spec").build(),
                    q("pick").exclusiveGroup("spec").build(),
                    q("neither").exclusiveGroup("spec").build());
        }

        @Test
        @DisplayName("taking one path locks the others, permanently")
        void onePathLocksTheRest() {
            QuestIndex index = paths();
            TeamProgress progress = completedQuests(index, "sword");

            assertEquals(QuestState.COMPLETED, stateOf(index, progress, "sword"));
            assertEquals(QuestState.LOCKED, stateOf(index, progress, "pick"));
            assertEquals(QuestState.LOCKED, stateOf(index, progress, "neither"));
        }

        @Test
        @DisplayName("with nothing taken, every path is available")
        void allAvailableUntilOneIsTaken() {
            QuestIndex index = paths();
            TeamProgress empty = TeamProgress.empty();
            assertEquals(QuestState.UNLOCKED, stateOf(index, empty, "sword"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, empty, "pick"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, empty, "neither"));
        }

        @Test
        @DisplayName("a repeatable quest in the group is not locked by its own completion")
        void aRepeatableQuestDoesNotLockItself() {
            // The group's key is recorded for every quest that is satisfied for its dependents, which
            // includes the one that just completed. A repeatable quest therefore reached its own key the
            // moment its first round ended and was locked out of its second, permanently -- the group is
            // meant to lock the siblings, and a quest is not its own sibling.
            QuestIndex index = indexOf(q("daily").repeatable(true).repeatCooldownTicks(600)
                            .exclusiveGroup("spec").build(),
                    q("once").exclusiveGroup("spec").build());
            Quest daily = Fixtures.quest(index, "daily");
            Quest once = Fixtures.quest(index, "once");

            ProgressionEngine.Resolution resolution =
                    ProgressionEngine.resolve(index, completedQuests(index, "daily"), NOW + 600);

            assertEquals(QuestState.STARTED, resolution.stateOf(daily),
                    "the cooldown elapsed, so the quest is playable again rather than locked by its own "
                            + "group");
            assertEquals(QuestState.LOCKED, resolution.stateOf(once),
                    "and its sibling is still locked, which is what the group is for");
        }

        @Test
        @DisplayName("groups in different chapters do not collide, even with the same name")
        void scopedToChapter() {
            // Two chapters, each with a "spec" group. Completing one chapter's quest must not lock
            // the other chapter's -- the group name is scoped by the chapter that declares it, and
            // this is the test that says so.
            String json = """
                    {
                      "version": 1,
                      "chapterGroups": [
                        {
                          "id": "group",
                          "title": "Group",
                          "chapters": [
                            { "id": "first", "title": "First", "quests": [ %s ] },
                            { "id": "second", "title": "Second", "quests": [ %s ] }
                          ]
                        }
                      ]
                    }
                    """.formatted(
                    q("one").exclusiveGroup("spec").build(),
                    q("two").exclusiveGroup("spec").build());

            QuestIndex index = Fixtures.indexOf(json);
            TeamProgress progress = completedQuests(index, "one");

            assertEquals(QuestState.COMPLETED, stateOf(index, progress, "one"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, progress, "two"));
        }
    }

    // ------------------------------------------------------------------
    // Linear vs flexible
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("in a linear chapter")
    class Linear {

        private static final String LINEAR = "\"progressionMode\": \"linear\",";

        @Test
        @DisplayName("the order of the quest list is the progression, with no dependsOn anywhere")
        void orderIsTheChain() {
            QuestIndex index = indexWithChapter(LINEAR,
                    q("one").build(), q("two").build(), q("three").build());

            assertEquals(QuestState.UNLOCKED, stateOf(index, TeamProgress.empty(), "one"));
            assertEquals(QuestState.LOCKED, stateOf(index, TeamProgress.empty(), "two"));

            TeamProgress afterOne = completedQuests(index, "one");
            assertEquals(QuestState.UNLOCKED, stateOf(index, afterOne, "two"));
            assertEquals(QuestState.LOCKED, stateOf(index, afterOne, "three"));

            assertEquals(QuestState.UNLOCKED,
                    stateOf(index, completedQuests(index, "one", "two"), "three"));
        }

        @Test
        @DisplayName("an explicit dependency still applies on top of the order")
        void explicitDependencyStillCounts() {
            // Linear adds a constraint; it does not replace what a quest already declared. Here
            // "extra" sits between "one" and "two", so "two" waits for both.
            QuestIndex index = indexWithChapter(LINEAR,
                    q("one").build(),
                    q("extra").build(),
                    q("two").dependsOn("extra").build());

            assertEquals(QuestState.LOCKED, stateOf(index, TeamProgress.empty(), "two"));
            assertEquals(QuestState.LOCKED, stateOf(index, completedQuests(index, "one"), "two"),
                    "one is not enough: extra comes first");
            assertEquals(QuestState.UNLOCKED,
                    stateOf(index, completedQuests(index, "one", "extra"), "two"));
        }

        @Test
        @DisplayName("in the default FLEXIBLE mode, order means nothing")
        void flexibleIgnoresOrder() {
            QuestIndex index = indexWithChapter("",
                    q("one").build(), q("two").build(), q("three").build());
            TeamProgress empty = TeamProgress.empty();

            assertEquals(QuestState.UNLOCKED, stateOf(index, empty, "one"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, empty, "two"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, empty, "three"));
        }
    }

    // ------------------------------------------------------------------
    // Repeatable quests
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a repeatable quest")
    class Repeatable {

        @Test
        @DisplayName("stays completed while its cooldown runs, and reports how long is left")
        void cooldownHolds() {
            QuestIndex index = indexOf(q("daily").repeatable(true).repeatCooldownTicks(600).build());
            Quest daily = Fixtures.quest(index, "daily");

            ProgressionEngine.Resolution resolution =
                    ProgressionEngine.resolve(index, completedQuests(index, "daily"), NOW + 100);

            assertEquals(QuestState.COMPLETED, resolution.stateOf(daily));
            assertEquals(500, resolution.cooldownOf(daily), "600 ticks of cooldown, 100 elapsed");
        }

        @Test
        @DisplayName("becomes playable again once the cooldown has elapsed")
        void cooldownElapsed() {
            QuestIndex index = indexOf(q("daily").repeatable(true).repeatCooldownTicks(600).build());
            Quest daily = Fixtures.quest(index, "daily");

            ProgressionEngine.Resolution resolution =
                    ProgressionEngine.resolve(index, completedQuests(index, "daily"), NOW + 600);

            assertEquals(QuestState.STARTED, resolution.stateOf(daily));
            assertEquals(0, resolution.cooldownOf(daily));
        }

        @Test
        @DisplayName("with no cooldown, it is playable again immediately")
        void noCooldown() {
            QuestIndex index = indexOf(q("daily").repeatable(true).build());
            Quest daily = Fixtures.quest(index, "daily");

            assertEquals(QuestState.STARTED,
                    ProgressionEngine.resolve(index, completedQuests(index, "daily"), NOW).stateOf(daily));
        }

        @Test
        @DisplayName("a non-repeatable quest stays completed however long passes")
        void notRepeatableStaysDone() {
            QuestIndex index = indexOf(q("once").build());
            Quest once = Fixtures.quest(index, "once");

            assertEquals(QuestState.COMPLETED,
                    ProgressionEngine.resolve(index, completedQuests(index, "once"), NOW + 100_000_000)
                            .stateOf(once));
        }

        @Test
        @DisplayName("a repeatable quest satisfies its dependents from the first completion")
        void dependentsStaySatisfied() {
            // Otherwise a chain following a repeatable quest would lock again every time the
            // player redid it, which is not what anyone means by repeatable.
            QuestIndex index = indexOf(q("daily").repeatable(true).repeatCooldownTicks(600).build(),
                    q("after").dependsOn("daily").build());
            Quest daily = Fixtures.quest(index, "daily");

            TeamProgress done = completedQuests(index, "daily");
            assertTrue(ProgressionEngine.satisfiedForDependents(daily, done));
            assertEquals(QuestState.UNLOCKED, stateOf(index, done, "after"));
        }
    }

    // ------------------------------------------------------------------
    // Task completion rules
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("task completion rules")
    class Tasks {

        @Test
        @DisplayName("every task not marked optional has to be done")
        void allMandatory() {
            QuestIndex index = indexOf(q("a").tasks(3).build());
            Quest a = Fixtures.quest(index, "a");

            assertFalse(ProgressionEngine.tasksSatisfied(a, QuestProgress.NONE));
            assertFalse(ProgressionEngine.tasksSatisfied(a, QuestProgress.NONE.recordTask(0, 1)));
            assertFalse(ProgressionEngine.tasksSatisfied(a,
                    QuestProgress.NONE.recordTask(0, 1).recordTask(1, 1)));
            assertTrue(ProgressionEngine.tasksSatisfied(a,
                    QuestProgress.NONE.recordTask(0, 1).recordTask(1, 1).recordTask(2, 1)));
        }

        @Test
        @DisplayName("when every task is optional, any one of them finishes the quest")
        void allOptional() {
            // A quest where nothing is required would otherwise complete itself the instant it
            // unlocked, which is why this has to be a rule rather than falling out of the count.
            QuestIndex index = indexOf(q("a").tasks(3).optional(0).optional(1).optional(2).build());
            Quest a = Fixtures.quest(index, "a");

            assertFalse(ProgressionEngine.tasksSatisfied(a, QuestProgress.NONE));
            assertTrue(ProgressionEngine.tasksSatisfied(a, QuestProgress.NONE.recordTask(1, 1)));
        }

        @Test
        @DisplayName("a mix requires the mandatory ones and ignores the optional one")
        void mixed() {
            QuestIndex index = indexOf(q("a").tasks(3).optional(2).build());
            Quest a = Fixtures.quest(index, "a");

            assertTrue(ProgressionEngine.tasksSatisfied(a,
                            QuestProgress.NONE.recordTask(0, 1).recordTask(1, 1)),
                    "tasks 0 and 1 are mandatory; 2 is optional and not needed");
            assertFalse(ProgressionEngine.tasksSatisfied(a, QuestProgress.NONE.recordTask(0, 1)),
                    "only one of the two mandatory tasks is done");
        }

        @Test
        @DisplayName("a quest with no tasks is completable immediately")
        void noTasks() {
            QuestIndex index = indexOf(q("a").noTasks().build());
            assertTrue(ProgressionEngine.tasksSatisfied(Fixtures.quest(index, "a"), QuestProgress.NONE));
        }

        @Test
        @DisplayName("sequential tasks lock everything after the first unfinished one")
        void sequential() {
            QuestIndex index = indexOf(q("a").tasks(3).sequentialTasks(true).build());
            Quest a = Fixtures.quest(index, "a");

            QuestProgress none = QuestProgress.NONE;
            assertTrue(a.isTaskUnlocked(0, i -> ProgressionEngine.isTaskSatisfied(a, i, none)));
            assertFalse(a.isTaskUnlocked(1, i -> ProgressionEngine.isTaskSatisfied(a, i, none)));

            QuestProgress firstDone = none.recordTask(0, 1);
            assertTrue(a.isTaskUnlocked(1, i -> ProgressionEngine.isTaskSatisfied(a, i, firstDone)));
            assertFalse(a.isTaskUnlocked(2, i -> ProgressionEngine.isTaskSatisfied(a, i, firstDone)));
        }

        @Test
        @DisplayName("without sequentialTasks, every task is available at once")
        void notSequential() {
            QuestIndex index = indexOf(q("a").tasks(3).build());
            Quest a = Fixtures.quest(index, "a");

            // The predicate is deliberately always-false: it should not even be consulted.
            assertTrue(a.isTaskUnlocked(2, i -> false));
        }

        @Test
        @DisplayName("recorded progress never goes backwards")
        void progressIsMonotonic() {
            // A consuming task reports zero the moment the items are taken, so without this the
            // task would un-complete itself on the next evaluation.
            QuestProgress progress = QuestProgress.NONE.recordTask(0, 5);
            assertEquals(5, progress.recordTask(0, 2).progressOf(0), "a lower reading must not lower it");
            assertEquals(8, progress.recordTask(0, 8).progressOf(0));
            assertEquals(5, progress.recordTask(1, 3).progressOf(0), "a different task must not disturb it");
        }
    }

    // ------------------------------------------------------------------
    // Dependency cycles
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a dependency cycle")
    class Cycles {

        private static QuestIndex ring() {
            return indexOf(q("a").dependsOn("c").build(),
                    q("b").dependsOn("a").build(),
                    q("c").dependsOn("b").build());
        }

        @Test
        @DisplayName("leaves every quest in it locked, rather than hanging or overflowing")
        void resolvesSafely() {
            QuestIndex index = ring();
            ProgressionEngine.Resolution resolution =
                    ProgressionEngine.resolve(index, TeamProgress.empty(), NOW);

            assertEquals(QuestState.LOCKED, resolution.stateOf(Fixtures.quest(index, "a")));
            assertEquals(QuestState.LOCKED, resolution.stateOf(Fixtures.quest(index, "b")));
            assertEquals(QuestState.LOCKED, resolution.stateOf(Fixtures.quest(index, "c")));
        }

        @Test
        @DisplayName("is reported as a chain that closes the loop")
        void detected() {
            List<List<String>> cycles = ProgressionEngine.findCycles(ring());

            assertEquals(1, cycles.size(), "one loop, reported once");
            List<String> cycle = cycles.get(0);
            assertEquals(cycle.get(0), cycle.get(cycle.size() - 1),
                    "the chain should end where it started: " + cycle);
            assertTrue(cycle.containsAll(List.of("a", "b", "c")),
                    "it should name every quest in the loop: " + cycle);
        }

        @Test
        @DisplayName("is reported once, not once per quest that can reach it")
        void reportedOnce() {
            // A three-quest ring can be entered from any of the three. Without the dedupe this
            // would be three identical messages, and a real questline has more.
            assertEquals(1, ProgressionEngine.findCycles(ring()).size());
        }

        @Test
        @DisplayName("is found even when it runs across two files")
        void acrossFiles() {
            QuestIndex index = Fixtures.indexOf(
                    Fixtures.file(q("p").dependsOn("q").build()),
                    Fixtures.file(q("q").dependsOn("p").build()));

            List<List<String>> cycles = ProgressionEngine.findCycles(index);
            assertEquals(1, cycles.size());
            assertTrue(cycles.get(0).containsAll(List.of("p", "q")), "got " + cycles.get(0));
        }

        @Test
        @DisplayName("a graph with no loop reports none")
        void noneWhenAcyclic() {
            QuestIndex index = indexOf(q("a").build(), q("b").dependsOn("a").build(),
                    q("c").dependsOn("a").build(), q("d").dependsOn("b", "c").build());
            assertTrue(ProgressionEngine.findCycles(index).isEmpty());
        }

        @Test
        @DisplayName("a self-dependency is a cycle of one")
        void selfDependency() {
            QuestIndex index = indexOf(q("a").dependsOn("a").build());
            assertEquals(QuestState.LOCKED, stateOf(index, TeamProgress.empty(), "a"));
        }
    }

    // ------------------------------------------------------------------
    // Aliases
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("aliases")
    class Aliases {

        @Test
        @DisplayName("let a dependency name an old id, so renaming a quest breaks nothing")
        void dependencyByAlias() {
            QuestIndex index = indexOf(q("punch_a_tree").alias("punch").build(),
                    q("make_a_table").dependsOn("punch").build());

            assertEquals(QuestState.LOCKED, stateOf(index, TeamProgress.empty(), "make_a_table"));
            assertEquals(QuestState.UNLOCKED,
                    stateOf(index, completedQuests(index, "punch_a_tree"), "make_a_table"));
        }

        @Test
        @DisplayName("resolve through the index, by id or by alias")
        void lookupByEither() {
            QuestIndex index = indexOf(q("punch_a_tree").alias("punch", "tree").build());

            assertTrue(index.quest("punch_a_tree").isPresent());
            assertTrue(index.quest("punch").isPresent());
            assertTrue(index.quest("tree").isPresent());
            assertEquals(Fixtures.quest(index, "punch_a_tree"), Fixtures.quest(index, "punch"));
        }

        @Test
        @DisplayName("do not create extra quests in the listing")
        void aliasesDoNotDuplicate() {
            // The lookup table holds one entry per name, so counting it would count a quest once
            // per alias. questCount() walks the files instead, and this is the test that says so.
            QuestIndex index = indexOf(q("a").alias("x", "y", "z").build());
            assertEquals(1, index.questCount());
        }
    }

    // ------------------------------------------------------------------
    // Counting
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the resolution counts what is unlocked and what is complete")
    void counts() {
        QuestIndex index = indexOf(q("a").build(), q("b").dependsOn("a").build(),
                q("c").dependsOn("b").build());

        ProgressionEngine.Resolution empty = ProgressionEngine.resolve(index, TeamProgress.empty(), NOW);
        assertEquals(1, empty.unlockedCount(), "only 'a' to begin with");
        assertEquals(0, empty.completedCount());

        ProgressionEngine.Resolution afterA =
                ProgressionEngine.resolve(index, completedQuests(index, "a"), NOW);
        assertEquals(2, afterA.unlockedCount(), "'a' is done and 'b' has opened");
        assertEquals(1, afterA.completedCount());
    }
}
