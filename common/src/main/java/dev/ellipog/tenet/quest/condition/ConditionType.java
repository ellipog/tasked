package dev.ellipog.tenet.quest.condition;

import dev.ellipog.armature.api.data.TypeSpec;
import dev.ellipog.tenet.quest.EditorField;
import dev.ellipog.tenet.quest.ItemRef;

import java.util.List;

/**
 * A condition type's public handle, as registered.
 *
 * <p>The condition half of {@link dev.ellipog.tenet.quest.task.QuestTaskType}: what reads a condition
 * of this type, how it is evaluated, how it is drawn, and what an empty one starts as.
 */
public interface ConditionType<T extends QuestCondition> extends TypeSpec<T> {

    /** What represents this condition in a listing or a picker. */
    ItemRef icon();

    /** How this condition decides. */
    ConditionBehaviour<T> behaviour();

    /** A fresh instance of this type, for the editor's Add picker. */
    T defaults();

    /** What this condition asks for, as a client should draw it. */
    ConditionDisplay display(T condition);

    /**
     * The fields of this type as the editor draws them.
     *
     * <p>The derived form when the type declared none — {@code EditorSpecs} guesses from the field
     * names — which is a floor rather than a goal.
     */
    default List<EditorField> editor() {
        return List.of();
    }
}
