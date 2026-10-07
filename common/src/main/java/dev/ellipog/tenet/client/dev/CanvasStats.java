package dev.ellipog.tenet.client.dev;

/**
 * What the canvas currently holds: how much it is drawing, and how often it was rebuilt.
 *
 * <h2>Why these numbers are worth a line of their own</h2>
 *
 * <p>The frame counters say what was <i>drawn</i>; none of them says what was <i>there</i>. So a reading of
 * `fills 4034` cannot be compared with another until you know whether the canvas held 70 nodes or 960, and the
 * whole point of the baseline table is comparing readings. The same gap hides the thing B19 is about: a drag
 * re-stamps the canvas every frame, and nothing on the overlay says so — the counters look much the same
 * whether the canvas was rebuilt once or sixty times.
 *
 * <h2>Why a holder rather than reading the screen</h2>
 *
 * <p>{@code CountingRenderer} cannot see {@code QuestBookScreen}: the counters are written from the render
 * path, which is below the screen rather than inside it, and a counter that reached up into a screen would be
 * a second thing to keep true about which screen is open. The screen publishes here and the overlay reads, the
 * same shape {@code LineArt.drainWalked} and {@code CacheHits} already use.
 *
 * <p><b>Published on a rebuild, not per frame.</b> These describe the canvas's contents, which only change
 * when it is rebuilt — so a still canvas pays nothing for them, and a reading taken while nothing moved is the
 * previous rebuild's answer rather than a stale one. {@link #rebuilds} is the exception and is deliberately
 * cumulative: it is the number that says a drag was re-stamping, and a per-second delta of it is the reading.
 */
public final class CanvasStats {

    /** Nothing has been stamped yet, so every reading is zero rather than absent. */
    private static volatile int nodes;
    private static volatile int edges;
    private static volatile int named;
    private static volatile int visible;
    private static volatile long rebuilds;

    private CanvasStats() {
    }

    /**
     * Called by the screen each time it rebuilds the canvas.
     *
     * @param nodes   quests in the chapter on screen
     * @param edges   dependency lines it built, after the cull
     * @param named   nodes whose title is drawn, which is the label pass's own input
     * @param visible nodes that survived the viewport cull
     */
    public static void published(int nodes, int edges, int named, int visible) {
        CanvasStats.nodes = nodes;
        CanvasStats.edges = edges;
        CanvasStats.named = named;
        CanvasStats.visible = visible;
        rebuilds++;
    }

    /** Quests in the chapter on screen. */
    public static int nodes() {
        return nodes;
    }

    /** Dependency lines built for this canvas. */
    public static int edges() {
        return edges;
    }

    /** Nodes whose title the label pass considers. */
    public static int named() {
        return named;
    }

    /** Nodes that survived the viewport cull — the ones actually drawn. */
    public static int visible() {
        return visible;
    }

    /** How many times the canvas has been rebuilt since the client started. Cumulative; see the class note. */
    public static long rebuilds() {
        return rebuilds;
    }

    /** Forgets everything: called when the client leaves the world. */
    public static void clear() {
        nodes = 0;
        edges = 0;
        named = 0;
        visible = 0;
        rebuilds = 0;
    }
}
