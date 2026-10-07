package dev.ellipog.tenet.client.viewer;

/**
 * The colours a viewer page draws in.
 *
 * <h2>Why these are fixed and not the book's theme</h2>
 *
 * <p>A viewer draws on its own surface — EMI's, JEI's and REI's recipe cards are light, near-white
 * panels — and the book's theme colours are chosen for the book's own canvas, which a pack can make
 * dark, parchment or anything else. Painting themed colours onto a viewer's card produced exactly the
 * failure a playtest caught: light grey text on an off-white card, legible only by squinting. So the
 * page carries its own small palette, tuned for a light surface, and the theme stays where the theme
 * applies.
 *
 * <p>Every value is opaque ARGB. The status colours are dark enough to carry white pill text.
 */
public final class PagePalette {

    /** Body text: vanilla's own GUI dark grey, which is what an item's tooltip uses. */
    public static final int TEXT = 0xFF404040;
    /** Secondary text — headings, counts, a claimed reward's label. */
    public static final int MUTED = 0xFF707070;
    /** A progress bar's unfilled track. */
    public static final int BAR_TRACK = 0xFF9E9E9E;
    /** A finished thing: a completed quest, a done task. */
    public static final int COMPLETE = 0xFF2E7D32;
    /** Work in progress. */
    public static final int PROGRESS = 0xFFB26A00;
    /** Nothing done yet, and nothing blocking it. */
    public static final int AVAILABLE = 0xFF1565C0;
    /** Something a condition or an unmet prerequisite holds shut. */
    public static final int LOCKED = 0xFF7A2E2E;
    /** Text on a status pill. */
    public static final int PILL_TEXT = 0xFFFFFFFF;
    /** A translucent wash over a clickable region on hover. */
    public static final int HOVER = 0x22000000;
    /** A row icon's slot inset: the darker edge. */
    public static final int SLOT_EDGE = 0xFF373737;
    /** A row icon's slot inset: the fill the item sits on. */
    public static final int SLOT_FILL = 0xFF8B8B8B;

    private PagePalette() {
    }
}
