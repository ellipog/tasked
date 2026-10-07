package dev.ellipog.tenet.quest;

import dev.ellipog.armature.api.data.Codecs;
import com.mojang.serialization.Codec;

/**
 * How many of a quest's dependencies must be satisfied before it unlocks.
 *
 * <p>Stored on the quest, and defaulted per chapter, so a chapter can say "everything here is linear
 * unless a quest says otherwise" instead of every quest repeating it.
 *
 * <p>{@code minRequired} on a quest replaces the <b>count</b> when it is set, which is how "any three
 * of these five" is expressed — the one shape a fixed set of modes cannot cover. It does not replace
 * the mode: the mode still decides the bar each dependency must reach, so {@code one_started} with
 * {@code minRequired} 2 is "any two of these, started".
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

    /**
     * How many dependencies must be satisfied, given a mode, a {@code minRequired} and a list size.
     *
     * <p>Here rather than on {@link Quest} because <b>the client needs it too</b>: the card says "2 of 3
     * met" and the canvas colours a line by whether the rule is met, and a second copy of this
     * arithmetic in the client would be a second answer to "how many is enough" -- the exact class of
     * fault this project keeps finding. The server's {@code Quest.requiredCount} calls this.
     *
     * <p>{@code minRequired} wins when set, and is clamped to the list: a file that asks for five of
     * three is an error the loader reports, and until it is fixed the quest behaves as "all three"
     * rather than as unreachable.
     */
    public static int requiredCount(PrerequisiteMode mode, int minRequired, int dependencyCount) {
        if (minRequired > 0) {
            return Math.min(minRequired, dependencyCount);
        }
        return switch (mode) {
            case ALL_COMPLETED, ALL_STARTED -> dependencyCount;
            case ONE_COMPLETED, ONE_STARTED -> dependencyCount == 0 ? 0 : 1;
        };
    }

    /**
     * Whether this mode counts a dependency that is merely started, as opposed to completed.
     *
     * <p>Also shared, for the same reason: the client colours a dependency line and ticks a card row by
     * this answer, and a client that assumed "completed" would draw a satisfied prerequisite as unmet
     * under two of the four modes.
     */
    public boolean countsWhenStarted() {
        return this == ALL_STARTED || this == ONE_STARTED;
    }
}
