package dev.ellipog.tasked.quest.reward;

import dev.ellipog.armature.api.data.TypeSpec;
import dev.ellipog.tasked.quest.ItemRef;
import dev.ellipog.tasked.quest.QuestReward;

/**
 * A kind of reward, as a registered type.
 *
 * <p>Mirrors {@link dev.ellipog.tasked.quest.task.QuestTaskType}, including carrying the behaviour on
 * the type, and for the same reasons.
 */
public interface QuestRewardType<T extends QuestReward> extends TypeSpec<T> {

    /** The item shown in the quest's detail panel to represent this reward. */
    ItemRef icon();

    /** How this type hands the reward over. */
    RewardBehaviour<T> behaviour();

    /**
     * What this reward gives, as a client should draw it.
     *
     * <p>Abstract rather than defaulted, for the reason given on
     * {@link dev.ellipog.tasked.quest.task.QuestTaskType#display}: a default would let a new reward
     * type render as a blank line, and the person who wrote it would be the last to find out.
     */
    RewardDisplay display(T reward);
}
