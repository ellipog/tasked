package dev.ellipog.tenet.client.viewer;

/**
 * When a viewer should be asked to rebuild itself, given how often its content has moved.
 *
 * <h2>Why a viewer needs this at all</h2>
 *
 * <p>Because one viewer's rebuild is not a small thing. EMI's is <b>every recipe in the game</b>: its
 * registration entry point is handed a registry and asked to fill it, for every mod's categories, not
 * only this mod's pages. So a rebuild is worth several hundred milliseconds of a worker thread, and the
 * content this watches — a quest tree — moves <i>per edit</i>: dragging a node, holding a count
 * stepper, typing into a field each commit. Asking for a rebuild per revision is therefore a rebuild per
 * keystroke, and the cost lands on a player who is doing something else entirely.
 *
 * <h2>What "settled" means, exactly</h2>
 *
 * <p>The revision is watched, and a rebuild is asked for on the tick that it has been the <i>same</i>
 * revision for {@code settleTicks} consecutive ticks. A revision seen for the first time starts that
 * run rather than asking immediately — so a burst that changes the revision every tick asks for
 * nothing at all until it stops, and then asks once. The degenerate case is deliberate and worth
 * naming: a {@code settleTicks} of one asks on the first sighting, which is the behaviour of having no
 * debounce whatever.
 *
 * <h2>One request per revision, and never a retry</h2>
 *
 * <p>A revision that has been asked for is never asked for again. That is not tidiness — a rebuild can
 * fail, and a watch that retried would retry every tick for as long as the failure lasted, which is a
 * worse version of the problem it exists to solve. The next <i>change</i> is the honest retry point,
 * and it arrives as a new revision by definition.
 *
 * <p>Game-free, so the timing rule can be asserted at a settle of two rather than waited out at sixty
 * ticks: the class takes a tick and answers, and the caller is what owns the clock.
 */
public final class ViewerReloadWatch {

    /** How long the current revision has held still, in ticks. */
    private int settled;

    /** The revision being watched, and the one a rebuild has been asked for. */
    private long watched = Long.MIN_VALUE;
    private long requested = Long.MIN_VALUE;

    private final int settleTicks;

    /**
     * @param settleTicks how many consecutive ticks one revision must hold before a rebuild is asked
     *                    for; one asks immediately, and less than one is refused rather than treated as
     *                    a faster debounce, because "settle for no ticks" is not a rule anyone means
     */
    public ViewerReloadWatch(int settleTicks) {
        if (settleTicks < 1) {
            throw new IllegalArgumentException("a settle of at least one tick: " + settleTicks);
        }
        this.settleTicks = settleTicks;
    }

    /**
     * One tick. Whether a rebuild should be asked for now.
     *
     * @param registered the revision the viewer last built from — read from wherever the viewer
     *                   announces it, which is not always this thread, so it is passed in rather than
     *                   remembered here
     * @param revision   the content's revision as it stands
     */
    public boolean due(long registered, long revision) {
        if (revision == registered || revision == requested) {
            // Nothing to ask for: the viewer has built this revision, or a rebuild for it is already
            // in flight. Either way the run is over, and a later change starts a new one.
            settled = 0;
            return false;
        }
        if (revision != watched) {
            // A revision this watch has not seen: it starts the run rather than asking, which is the
            // whole of the debounce. One edit in a quiet second still settles; a burst never does.
            watched = revision;
            settled = 1;
        }
        else {
            settled++;
        }
        if (settled < settleTicks) {
            return false;
        }
        requested = revision;
        return true;
    }
}
