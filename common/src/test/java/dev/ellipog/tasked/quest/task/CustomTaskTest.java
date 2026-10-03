package dev.ellipog.tasked.quest.task;

import dev.ellipog.tasked.quest.QuestTask;
import dev.ellipog.tasked.quest.TaskCommon;
import dev.ellipog.tasked.quest.TaskContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The custom task: its handler registry, and the four questions the engine asks a task.
 *
 * <h2>Why the behaviour is read through the registry</h2>
 *
 * <p>{@code TaskTypes.behaviourOf} is the wrapper the engine actually calls, and its own javadoc explains
 * what went wrong the one time a method was not forwarded by hand: an item task's {@code takesResources}
 * read as false and consuming tasks silently stopped consuming. So every assertion here goes through the
 * registry rather than through {@link CustomTask#BEHAVIOUR}, and a method added to {@code TaskBehaviour}
 * without a line in that wrapper fails here rather than in a playtest.
 *
 * <p>The handlers registered in these tests do not look at the context -- which is what lets a test with no
 * game in it ask the whole question. Each test uses its own id, so their order does not matter.
 */
@DisplayName("the custom task")
class CustomTaskTest {

    /** A handler that answers the same number every time, and does not touch the context. */
    private static void registerCounter(String id, int progress) {
        CustomTask.CustomTasks.register(id, (task, context) -> progress);
    }

    private static CustomTask task(String id, int value) {
        return new CustomTask(new TaskCommon(false, 20), id, value);
    }

    /** The behaviour the engine reads: the registered one, through the widening wrapper. */
    private static TaskBehaviour<QuestTask> behaviourOf(CustomTask task) {
        return TaskTypes.behaviourOf(task)
                .orElseThrow(() -> new AssertionError(task.id() + " is not a registered type"));
    }

    @Test
    @DisplayName("what the author asked for is the requirement, whatever the handler counts")
    void requiredIsTheDocument() {
        registerCounter("test:required", 0);
        CustomTask task = task("test:required", 7);

        assertEquals(7, behaviourOf(task).required(task),
                "the number in the file is the bar; the handler only says how far along the player is");
    }

    @Test
    @DisplayName("a registered handler's number is what the engine reads as progress")
    void aHandlerMeasures() {
        registerCounter("test:counted", 3);
        CustomTask task = task("test:counted", 5);

        assertEquals(3, behaviourOf(task).current(task, null));
    }

    @Test
    @DisplayName("an id nothing provides is no progress and no crash, on every question")
    void absentHandlersAreQuiet() {
        CustomTask absent = task("test:absent", 5);

        assertEquals(0, behaviourOf(absent).current(absent, null),
                "a handler that is not installed is not an error: the task is inert, not broken");
        assertFalse(behaviourOf(absent).canSubmitByHand(absent),
                "and a task nothing measures does not offer a Submit button that could not work");
        assertFalse(behaviourOf(absent).takesResources(absent, true),
                "and it takes nothing, chapter default or not");
    }

    @Test
    @DisplayName("a handler that wants a Submit button gets one, through the registry's wrapper")
    void theHandInButtonIsTheHandlersToGive() {
        CustomTask.CustomTasks.register("test:handin", new CustomTask.CustomTasks.Handler() {
            @Override
            public int current(CustomTask task, TaskContext context) {
                return 1;
            }

            @Override
            public boolean canSubmitByHand(CustomTask task) {
                return true;
            }
        });
        CustomTask task = task("test:handin", 1);

        assertTrue(behaviourOf(task).canSubmitByHand(task),
                "the override has to survive TaskTypes.widenBehaviour, which forwards every method by hand");
        assertEquals(0, behaviourOf(task("test:handin-absent", 1)).current(task("test:handin-absent", 1), null),
                "and another id is unaffected by it");
    }

    @Test
    @DisplayName("no progress is ever taken: a custom task names nothing to take")
    void nothingIsEverTaken() {
        registerCounter("test:takes", 9);
        CustomTask task = task("test:takes", 1);

        assertFalse(behaviourOf(task).takesResources(task, true),
                "the engine can only take a resource it can name; a handler that wants a cost pairs itself "
                        + "with an item task");
    }

    @Test
    @DisplayName("the registry registers, replaces, and answers for what it holds")
    void theRegistry() {
        registerCounter("test:registry", 1);
        assertTrue(CustomTask.CustomTasks.ids().contains("test:registry"));
        assertEquals(1, CustomTask.CustomTasks.handler("test:registry").orElseThrow()
                .current(task("test:registry", 1), null));

        // Re-registering replaces: a script reload is exactly this, and refusing would make an edit cycle
        // impossible.
        registerCounter("test:registry", 4);
        assertEquals(4, CustomTask.CustomTasks.handler("test:registry").orElseThrow()
                .current(task("test:registry", 1), null));

        assertTrue(CustomTask.CustomTasks.handler("test:nobody").isEmpty(),
                "an id nothing registered answers empty rather than throwing");
        assertThrows(NullPointerException.class,
                () -> CustomTask.CustomTasks.register("test:null-handler", null));
    }
}
