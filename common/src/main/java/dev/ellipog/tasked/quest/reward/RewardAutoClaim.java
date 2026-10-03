package dev.ellipog.tasked.quest.reward;

import com.mojang.serialization.Codec;

import dev.ellipog.armature.api.data.Codecs;

/**
 * Whether, and how loudly, a reward is handed over the moment its quest is completed.
 *
 * <p>Borrowed whole from FTB Quests, including the names, because pack authors have these five words
 * in their fingers and a synonym would only be a translation table in somebody's head. The default
 * is not "on": a finished quest announcing a payout and handing it over in the same breath is a
 * choice an author makes, not one the mod makes for them.
 */
public enum RewardAutoClaim {

    /** Defer to the quest tree's own default. The state a reward that says nothing is in. */
    DEFAULT,

    /** Never automatic. The reward waits for a claim, which is what a claim button is for. */
    DISABLED,

    /** Granted on completion, with a notification. */
    ENABLED,

    /** Granted on completion, silently: no toast. */
    NO_TOAST,

    /** Granted on completion with no toast and no chat line, as if it had always been there. */
    INVISIBLE;

    public static final Codec<RewardAutoClaim> CODEC = Codecs.enumByName(RewardAutoClaim.class);

    /** The mode in force, given the quest tree's own default. */
    public RewardAutoClaim resolved(RewardAutoClaim fileDefault) {
        return this == DEFAULT ? fileDefault : this;
    }

    /** Whether this mode hands the reward over without being asked. */
    public boolean automatic() {
        return this == ENABLED || this == NO_TOAST || this == INVISIBLE;
    }

    /** Whether this mode tells the player it happened. */
    public boolean notifies() {
        return this == ENABLED;
    }
}
