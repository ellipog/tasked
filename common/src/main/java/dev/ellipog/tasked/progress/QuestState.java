package dev.ellipog.tasked.progress;

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

    /** Dependencies are not satisfied. Nothing a player does can advance it. */
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
}
