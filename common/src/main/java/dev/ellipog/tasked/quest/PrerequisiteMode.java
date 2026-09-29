package dev.ellipog.tasked.quest;

import dev.ellipog.armature.api.data.Codecs;
import com.mojang.serialization.Codec;

/**
 * How many of a quest's dependencies must be satisfied before it unlocks.
 *
 * <p>Stored on the quest, and defaulted per chapter, so a chapter can say "everything here is linear
 * unless a quest says otherwise" instead of every quest repeating it.
 *
 * <p>{@code minRequired} on a quest overrides all of these when it is set, which is how "any three of
 * these five" is expressed — the one shape that a fixed set of modes cannot cover.
 */
public enum PrerequisiteMode {

    /** Every dependency must be completed. The usual choice for a linear chain. */
    ALL_COMPLETED,

    /** Any one dependency unlocks this. For branches that converge. */
    ONE_COMPLETED,

    /** Every dependency must at least be started. For quests that run alongside each other. */
    ALL_STARTED,

    /** Any one dependency must be started. */
    ONE_STARTED;

    public static final Codec<PrerequisiteMode> CODEC = Codecs.enumByName(PrerequisiteMode.class);
}
