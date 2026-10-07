package dev.ellipog.tenet.client;

/**
 * How much of a node the canvas draws, decided by how far out it is zoomed.
 *
 * <h2>Why the canvas needs this at all</h2>
 *
 * <p>Zooming out is the one gesture that makes the canvas <b>more</b> expensive per unit of what a player
 * can read: every node in the chapter comes on screen at once, and each one is still drawn in full — an
 * item rendered through the game's own 3-D pipeline, a ring, a wash, a title, a badge. At 0.2× a node is
 * twelve pixels across, an item icon inside it is nine, and the answer to "which quest is this" is not on
 * the canvas at all: it is the hover caption, which names whatever the pointer is over. So the fine
 * detail is not merely cheap to drop, it is <b>illegible</b> — and it is the expensive half of a frame at
 * exactly the zoom where the most nodes are visible.
 *
 * <h2>The tiers, and what each one gives up</h2>
 *
 * <ul>
 *   <li>{@link #FULL} — everything, unchanged. This is what every zoom at or above
 *       {@link #CARTOON_BELOW} draws, and therefore what every screenshot, preview and still in this
 *       project contains.</li>
 *   <li>{@link #CARTOON} — no item icons and no hover or selection ring. The icon becomes the
 *       state-coloured stand-in {@code QuestNodeArt} already draws when a node has no item, which keeps a
 *       node's state readable as a colour; a one-pixel ring round a twelve-pixel node is a thicker border
 *       rather than a cue.</li>
 *   <li>{@link #BLOCKS} — those, and no titles and no reward badges either. What is left is the outline
 *       and the wash: a map of where things are, which is what a zoomed-out canvas is for.</li>
 * </ul>
 *
 * <h2>The numbers, and why they are where they are</h2>
 *
 * <p>Both thresholds sit strictly below the zooms the project has pictures of. The preview harness draws
 * the book at <b>1.0×</b>, and the golden stills are compared byte for byte, so a tier that fired there
 * would move every image and turn an acceptance gate into a re-baselining exercise. Below the thresholds
 * is where the pixels say the detail has stopped being readable rather than merely small: at 0.5 a
 * forty-eight-pixel node is twenty-four across and its icon is eighteen, and at 0.3 it is fourteen and
 * ten.
 *
 * <p>Game-free and three constants, so the rule can be asserted at every zoom in a test rather than
 * judged by looking at a canvas.
 */
public enum CanvasDetail {

    /** Everything, at every zoom a person reads at. */
    FULL,

    /** Node colour and outline, without the rings. */
    CARTOON,

    /** The shape of the chapter: outlines and washes, with no text and no badges. */
    BLOCKS;

    /** The default below which no ring is drawn; the client's own value lives in {@link CanvasSettings}. */
    public static final float CARTOON_BELOW = CanvasSettings.DEFAULT_RINGS_BELOW;

    /** The default below which no title is drawn; likewise the client's. */
    public static final float BLOCKS_BELOW = CanvasSettings.DEFAULT_BLOCKS_BELOW;

    /**
     * The tier one zoom draws at, from the client's configured thresholds.
     *
     * <p>See {@link #of(float, float, float)} for the rule and {@link CanvasSettings} for where the numbers
     * come from — and for why the icons are no longer one of the things a tier decides.
     */
    public static CanvasDetail of(float scale) {
        return of(scale, CanvasSettings.ringsBelow(), CanvasSettings.blocksBelow());
    }

    /**
     * The rule itself, with the thresholds as arguments: pure, so a test can hold them still.
     *
     * <p>Two thresholds rather than three, and the missing one is the point of this round. An <b>icon</b> is
     * refused by the size of the box it would fill, which is a property of the node — a landmark has room at
     * any zoom and a small node runs out at a specific size — so it was never a question about zoom at all,
     * and asking it here cost a screenshot to notice: a huge gear node drawn as an empty outline with its
     * stand-in block, because a *different* node somewhere in the same frame would have been a smudge. What
     * remains a zoom question is decoration whose size does not scale with the node: a ring (one pixel at any
     * size, so a ring at 0.2× is a border rather than a cue) and a title (text is pixel-sized, so the
     * question is crowding rather than legibility — and `labelRoom` already refuses a label that will not
     * fit, which makes the tier a cheap early-out for a rule the label pass applies anyway).
     */
    public static CanvasDetail of(float scale, float ringsBelow, float blocksBelow) {
        if (scale < blocksBelow) {
            return BLOCKS;
        }
        return scale < ringsBelow ? CARTOON : FULL;
    }

    /** Whether a hover or selection ring is drawn. */
    public boolean rings() {
        return this == FULL;
    }

    /** Whether node titles are measured and drawn. */
    public boolean labels() {
        return this != BLOCKS;
    }

    /** Whether reward badges are washed over the nodes. */
    public boolean badges() {
        return this != BLOCKS;
    }
}
