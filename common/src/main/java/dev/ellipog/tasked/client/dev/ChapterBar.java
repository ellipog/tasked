package dev.ellipog.tasked.client.dev;

import dev.ellipog.armature.client.render.GuiRenderer;

/**
 * A chapter's completion as a hairline bar, for the sidebar's rows.
 *
 * <h2>Why a bar rather than the dial that came before it</h2>
 *
 * <p>An eleven-pixel ring was tried first and read as clutter: too small to be a gauge, too busy to
 * be decoration. A one-pixel strip along the row's own bottom edge is legible at a glance, matches
 * the progress bars the quest card already draws, and — the part that matters — costs no layout:
 * the row keeps its eighteen pixels, the selection pill wraps the bar, and the scroll pitch stays
 * whatever it was. A two-pixel version was tried between the two and read as a green speck; the
 * hairline is what the row's scale actually supports.
 *
 * <h2>What is drawn, and when</h2>
 *
 * <p>At zero it draws nothing at all: a track along every untouched chapter is a groove nobody asked
 * for, and the bar appearing is itself the news that a chapter is underway. Past zero the track is
 * drawn and the done share laid over it, at least one pixel wide — a single finished quest in a long
 * chapter must not round away to nothing.
 *
 * <p>No colours are chosen here. The caller hands in the chapter theme's own tokens (its
 * {@code complete} and its {@code recessed}, shaded for hover or selection), which is what lets a
 * chapter's palette and the player's edits flow through unchanged.
 */
public final class ChapterBar {

    /** The bar's height in pixels: the hairline along the bottom of a row. */
    public static final int HEIGHT = 1;

    private ChapterBar() {
    }

    /**
     * Draws one bar.
     *
     * <p>The rectangle is the caller's, already inset from the row it belongs to — the art knows how
     * to fill and how to quantise, not where a sidebar puts its margins.
     *
     * @param fraction how much is done, 0..1; clamped
     */
    public static void draw(GuiRenderer r, int x, int y, int width, int height, float fraction,
                            int fill, int track) {
        if (width <= 0 || height <= 0) {
            return;
        }
        float done = fraction < 0F ? 0F : (fraction > 1F ? 1F : fraction);
        if (done <= 0F) {
            return;
        }
        int filled = Math.round(width * done);
        if (filled <= 0) {
            filled = 1;
        }
        if (filled < width) {
            // The track only when it will show: a full bar that first painted a track under itself
            // would be one invisible fill and one lie in a recorder's list.
            r.fill(x, y, x + width, y + height, track);
        }
        r.fill(x, y, x + filled, y + height, fill);
    }
}
