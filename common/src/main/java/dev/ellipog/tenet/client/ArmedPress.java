package dev.ellipog.tenet.client;

/**
 * A press that needs a second press, within a window.
 *
 * <h2>Why this is a class rather than a long field</h2>
 *
 * <p>Because it is a rule — "a second press within three seconds confirms" — and a rule buried in a
 * screen's field is a rule nothing can test: {@code QuestBookScreen} needs a running Minecraft to
 * exist, so the difference between "armed" and "expired" would only ever be checked by a human
 * pressing a button and waiting. Here it is arithmetic on a clock the caller supplies, and
 * {@code ArmedPressTest} sweeps the boundaries.
 *
 * <p>The clock is a parameter rather than {@code Util.getMillis()} inside, for the same reason every
 * animation in the kit takes a time: a test that has to sleep to reach a state is slow when it passes
 * and flaky when the machine is loaded.
 *
 * <h2>What expires, and what that means for the label</h2>
 *
 * <p>An armed press that is never confirmed <b>lapses</b> — {@link #armed(long)} starts answering
 * false — so the control that says "press again" goes back to saying what it does. The alternative,
 * arming until something else is pressed, is how a player arms Disband, reads a member list for a
 * minute, and then disbands their party by aiming at what they thought was Leave.
 */
public final class ArmedPress {

    /** How long a first press stays armed. */
    public static final long WINDOW_MILLIS = 3000L;

    /**
     * Whether a first press is waiting for a second.
     *
     * <p>A flag of its own rather than "the stamp is not zero", which is what this used to be: a clock
     * reading exactly zero is a real reading (the first millisecond of a JVM), and with the stamp as the
     * sentinel the first press at that instant armed nothing and said it had not. Nobody could reach it
     * and nothing was wrong because of it, which is the kind of guard that is worth removing while it is
     * in front of you rather than remembering.
     */
    private boolean armed;

    /** When the arming press happened; only read while {@link #armed}. */
    private long armedAt;

    /**
     * Records a press.
     *
     * @return whether this press <b>confirms</b> — true for a second press inside the window, false
     *     for the arming press and for one after the window has lapsed
     */
    public boolean press(long nowMillis) {
        if (armed(nowMillis)) {
            disarm();
            return true;
        }
        armed = true;
        armedAt = nowMillis;
        return false;
    }

    /** Whether a first press is waiting for a second, right now. */
    public boolean armed(long nowMillis) {
        return armed && nowMillis - armedAt <= WINDOW_MILLIS;
    }

    /**
     * Whether an arming press has expired without being confirmed.
     *
     * <p>Its own question rather than {@code !armed(now)}, which cannot tell a lapsed press from an
     * idle one — and a caller restoring a label on "not armed" would restore it on every frame.
     */
    public boolean lapsed(long nowMillis) {
        return armed && !armed(nowMillis);
    }

    /** Forgets an armed press. Called by anything that means "no", and by a rebuild. */
    public void disarm() {
        armed = false;
        armedAt = 0L;
    }
}
