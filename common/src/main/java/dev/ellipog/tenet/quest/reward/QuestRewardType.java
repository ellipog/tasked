package dev.ellipog.tenet.quest.reward;

import dev.ellipog.armature.api.data.TypeSpec;
import dev.ellipog.tenet.quest.ItemRef;
import dev.ellipog.tenet.quest.QuestReward;

/**
 * A kind of reward, as a registered type.
 *
 * <p>Mirrors {@link dev.ellipog.tenet.quest.task.QuestTaskType}, including carrying the behaviour on
 * the type, and for the same reasons.
 */
public interface QuestRewardType<T extends QuestReward> extends TypeSpec<T> {

    /** The item shown in the quest's detail panel to represent this reward. */
    ItemRef icon();

    /** How this type hands the reward over. */
    RewardBehaviour<T> behaviour();

    /**
     * A fresh instance of this type, for the editor's Add picker. See
     * {@link dev.ellipog.tenet.quest.task.QuestTaskType#defaults()} for why it lives on the type.
     */
    T defaults();

    /**
     * What this reward gives, as a client should draw it.
     *
     * <p>Abstract rather than defaulted, for the reason given on
     * {@link dev.ellipog.tenet.quest.task.QuestTaskType#display}: a default would let a new reward
     * type render as a blank line, and the person who wrote it would be the last to find out.
     */
    RewardDisplay display(T reward);

    /**
     * The fields as the in-game editor draws them: their order, their labels, and each one's control.
     *
     * <p>Declared by the type, so a new reward type is a new form rather than a new branch in the screen
     * -- and an addon's type is tailored by the same registration this mod's types use. A type that
     * declares nothing gets a form derived from {@link #fields()}; see
     * {@link dev.ellipog.tenet.quest.task.QuestTaskType#editor()}.
     */
    default java.util.List<dev.ellipog.tenet.quest.EditorField> editor() {
        return java.util.List.of();
    }
}
