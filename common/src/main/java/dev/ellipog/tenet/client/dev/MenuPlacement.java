package dev.ellipog.tenet.client.dev;

/**
 * Where a floating panel goes: at the pointer, and inside the window.
 *
 * <h2>Why this is arithmetic rather than four lines at the call site</h2>
 *
 * <p>Because "at the pointer, flipping when there is no room" is the same rule for the sidebar's menu
 * and the canvas's, and the first version of it was neither: the x came from the sidebar column's right
 * edge, so a canvas menu appeared beside the sidebar however far right the click was, and the vertical
 * branch clamped to the bottom instead of opening above the pointer. It was wrong in a way no amount of
 * looking at the screen would attribute to the placement code — the menu was simply "over there".
 *
 * <p>So it is one function, with the four cases written out and tested: below-right of the pointer when
 * there is room, flipped left near the right edge, flipped up near the bottom, and clamped when the
 * window is smaller than the panel — the last being degradation rather than an error, because a window
 * that small is already clipping the book.
 */
public final class MenuPlacement {

    /** Where the panel goes, and which side of it the submenu opens on. */
    public record Placed(int x, int y, boolean submenuRight) {
    }

    /**
     * Places a panel for an anchor.
     *
     * @param anchorX     the pointer
     * @param windowLeft   the window's usable bounds, inset by the caller's margin
     * @param submenuWidth how wide the submenu is, for deciding which side it opens on
     * @param offset      the gap between the pointer and the panel, and between the panels
     */
    public static Placed place(int anchorX, int anchorY, int width, int height, int submenuWidth,
                               int windowLeft, int windowTop, int windowRight, int windowBottom,
                               int offset) {
        int x = anchorX + offset;
        if (x + width > windowRight) {
            // No room to the right of the pointer, so open to its left — which is what keeps a menu on
            // the far side of the canvas from hanging off it.
            x = anchorX - offset - width;
        }
        x = clamp(x, windowLeft, windowRight - width);

        int y = anchorY + offset;
        if (y + height > windowBottom) {
            y = anchorY - offset - height;
        }
        y = clamp(y, windowTop, windowBottom - height);

        // The submenu prefers the menu's right; it flips to the left when the pair would leave the
        // window, and it is decided here so the bridge that keeps it open can follow the same answer.
        boolean submenuRight = x + width + offset + submenuWidth <= windowRight;
        return new Placed(x, y, submenuRight);
    }

    /** The submenu's x for a placed menu: beside it, on whichever side {@link #place} chose. */
    public static int submenuX(Placed placed, int width, int offset) {
        return placed.submenuRight() ? placed.x() + width + offset : placed.x() - offset - width;
    }

    /** Low wins when the window is smaller than the panel: the panel's top-left stays visible. */
    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(value, high));
    }

    private MenuPlacement() {
    }
}
