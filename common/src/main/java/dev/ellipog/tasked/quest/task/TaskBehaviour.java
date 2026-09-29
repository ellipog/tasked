package dev.ellipog.tasked.quest.task;

import dev.ellipog.tasked.quest.TaskContext;

/**
 * How a task type decides whether a task is satisfied.
 *
 * <p>Two numbers, and the engine does the rest. That is the whole contract, and keeping it this small
 * is what makes every task type a pure function of the world: it cannot record progress, cannot grant
 * anything, and cannot depend on being asked in a particular order.
 *
 * <h2>Why two numbers rather than a boolean</h2>
 *
 * <p>Because partial progress is worth showing. "You have 5 of 8 logs" is the single most useful
 * line in a quest book, and a {@code boolean isDone(player)} cannot express it. An item task with a
 * count reports a count; a checkmark reports 0 or 1.
 *
 * <p>{@link #required} is asked once and cached by the caller — it should not read the world, only
 * the task's own fields. {@link #current} is asked whenever the task is evaluated, which for an item
 * task means walking an inventory, so it is called on the task's own auto-submit interval rather
 * than every tick.
 *
 * <h2>Recorded progress only ever goes up</h2>
 *
 * <p>The engine keeps the best {@link #current} it has seen for a task and never lowers it. That
 * matters because of consumption: a player submits eight logs, the items are taken, and the next
 * evaluation reports zero. Without the monotonic rule the task would un-complete itself. So
 * {@code current} answers "how much do they appear to have <i>now</i>", and the engine decides what
 * that means over time.
 */
public interface TaskBehaviour<T> {

    /**
     * How much counts as done. Must not read the world — only the task's own fields.
     *
     * <p>A task whose requirement cannot be met — an item that no longer exists, a structure type
     * that was removed — returns a value no {@link #current} can reach, so the quest sits visibly
     * incomplete rather than silently completing itself.
     */
    int required(T task);

    /** How much the player currently has towards it. */
    int current(T task, TaskContext context);

    /**
     * Whether the player can submit this task by hand, as opposed to it completing on its own.
     *
     * <p>True for a checkmark, which has nothing to observe and only completes when someone says so.
     * True for an item task that consumes, where a player may prefer to hand the items over rather
     * than have them taken. False for an item task that only checks presence — there is nothing to
     * confirm, and a submit button that does nothing is worse than no button.
     */
    default boolean canSubmitByHand(T task) {
        return true;
    }
}
