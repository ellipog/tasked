package dev.ellipog.tenet.progress;

import dev.ellipog.tenet.quest.PrerequisiteMode;

/**
 * How far a quest has got, for one team.
 *
 * <p>Ordered, and the ordering is load-bearing: a dependency in {@code ALL_STARTED} mode asks
 * "is this at least started", which is a comparison against {@link #ordinal()}. So these constants
 * must stay in this order — locked, then unlocked, then started, then completed.
 *
 * <p>{@link #STARTED} earns its place rather than being decorative. Without it, "all of these
 * started" and "all of these completed" would be the same question, and a quest chain where several
 * quests run alongside each other could not be expressed. A quest becomes STARTED the moment any one
 * of its tasks is satisfied, or when a player starts it deliberately.
 */
public enum QuestState {

    /**
     * Dependencies are not satisfied. Nothing a player does can advance it — unless the quest is
     * flexible ({@code flexibleProgress} or its chapter's default), in which case tasks accumulate
     * while the gate is shut and completion waits for it to open. Flexible quests therefore never
     * resolve to this while they are measurable; they read UNLOCKED or STARTED instead.
     */
    LOCKED,

    /** Dependencies are satisfied. Nothing done yet. */
    UNLOCKED,

    /** At least one task is satisfied. */
    STARTED,

    /** Done. In a repeatable quest this is the state between completions. */
    COMPLETED;

    /** Whether this state has reached at least {@code other}. */
    public boolean isAtLeast(QuestState other) {
        return ordinal() >= other.ordinal();
    }

    /** Whether a player can make progress on it right now. */
    public boolean isPlayable() {
        return this == UNLOCKED || this == STARTED;
    }

    /** Lowercase name, for commands and logs. */
    public String label() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * The state one dependency must reach for a rule to count it.
     *
     * <h2>Why this is here rather than on {@link PrerequisiteMode}</h2>
     *
     * <p>Because there are now three readers of it — the quest resolver, the client's
     * {@code DependencyProgress}, and {@code ChapterStates} for a chapter's own gate — and three copies
     * of "started or completed" is three answers to one question. The way that fails is a canvas that
     * colours a prerequisite line the engine considers unmet, which is the exact fault the mode's own
     * {@code requiredCount} was moved out of the client for.
     *
     * <p>It lives on this enum rather than on the mode because the answer is a state, and because the
     * layering runs one way: {@code progress} knows about {@code quest}, and the mode is a vocabulary
     * record that should not have to know how far a dependency has got.
     */
    public static QuestState bar(PrerequisiteMode mode) {
        return mode.countsWhenStarted() ? STARTED : COMPLETED;
    }
}
