package dev.ellipog.tasked.quest.task;

import dev.ellipog.tasked.quest.TaskContext;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

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

    /**
     * Whether submitting this task by hand is something a <b>client</b> may ask for.
     *
     * <p>Separate from {@link #canSubmitByHand} because the two questions are different: the button's
     * visibility is one, and whether the server accepts the press is another. An observation task has
     * no button -- there is nothing to confirm, the watching is the work -- but its client does submit
     * on the player's behalf once the target has been looked at for long enough, so the server must
     * accept that press where it would refuse a hand-rolled one.
     */
    default boolean acceptsClientSubmit(T task) {
        return canSubmitByHand(task);
    }

    /**
     * Whether this task <b>takes</b> what it asks for when it is satisfied, rather than only checking
     * presence. The chapter's {@code defaultConsumeItems} is the fallback for a task that does not say.
     *
     * <p>Here rather than in the engine, where it used to be an {@code instanceof ItemTask}: the
     * engine should not grow a branch per consumable type, and the knowledge of what a task costs
     * belongs beside the knowledge of what it counts.
     */
    default boolean takesResources(T task, boolean chapterDefault) {
        return false;
    }

    /**
     * Taken when the task is satisfied: up to {@code count} of whatever it asks for, from this player,
     * answering how much was actually taken.
     *
     * <p>The return value matters under a pooled party: the caller walking several members has to know
     * when to stop asking, and "assume it took everything" is exactly the assumption that is false in
     * the case that walk exists for. A type that takes nothing answers zero.
     */
    default int take(T task, ServerPlayer player, int count) {
        return 0;
    }

    /**
     * Something died at this player's hand: how many steps of progress that is worth, zero for a type
     * that does not listen.
     *
     * <p>The event half of the contract, and the only one -- everything else is asked rather than
     * told. A kill task is the reason it exists: counting kills by polling a player's lifetime stats
     * can say how many zombies they have ever killed, but not whether the one that just died was
     * called "Bob" and carried a named sword.
     */
    default int onEntityDeath(T task, ServerPlayer killer, LivingEntity killed) {
        return 0;
    }
}
