package dev.ellipog.tasked.quest;

import net.minecraft.resources.ResourceLocation;

/**
 * Something a player receives for completing a quest.
 *
 * <p>Mirrors {@link QuestTask}, and is separate from it for a reason worth stating: a reward is
 * granted <b>once</b> and must be idempotent, while a task is evaluated repeatedly. Sharing a hierarchy
 * between the two looks tidy and then has to be unpicked the first time a reward needs to record that
 * it has already been claimed.
 *
 * <p>Like {@code QuestTask}, this holds no codec field — see the note there for the initialisation
 * cycle that caused. The codec is at
 * {@link dev.ellipog.tasked.quest.reward.RewardTypes#dispatchCodec()}.
 */
public interface QuestReward {

    /** Which registered type this is. The value of the JSON {@code "type"} field. */
    ResourceLocation type();
}
