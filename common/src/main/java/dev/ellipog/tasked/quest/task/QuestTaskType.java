package dev.ellipog.tasked.quest.task;

import dev.ellipog.armature.api.data.TypeSpec;
import dev.ellipog.tasked.quest.ItemRef;
import dev.ellipog.tasked.quest.QuestTask;

/**
 * A kind of task, as a registered type.
 *
 * <p>Public, and public on purpose: this is how another mod adds a task type. Tasked registers two
 * and would like there to be twenty more.
 *
 * <p>The behaviour is part of the type rather than a field on each task, because how a task is
 * evaluated follows from what kind of task it is. An addon registering a type registers how it
 * works; it does not have to be looked up separately, and it cannot go missing.
 */
public interface QuestTaskType<T extends QuestTask> extends TypeSpec<T> {

    /**
     * The item shown in the quest's detail panel to represent this task — a knowledge book for a
     * checkmark, a chest for an item task.
     *
     * <p>Kept on the type rather than on each task, so the icon is chosen once by whoever wrote the
     * type rather than repeated in every quest file.
     */
    ItemRef icon();

    /** How this type decides whether a task is satisfied. */
    TaskBehaviour<T> behaviour();

    /**
     * What this task asks for, as a client should draw it.
     *
     * <p>Required rather than defaulted on purpose. A default would mean a new task type silently
     * rendering as a blank row with a generic icon, and the person who wrote the type would be the
     * last to find out — they would have to open the quest book to notice. Making it abstract means
     * the compiler asks the question at the moment the type is written, which is when the answer is
     * in hand.
     */
    TaskDisplay display(T task);
}
