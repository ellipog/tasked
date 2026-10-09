package dev.ellipog.tenet.client.hud;

import dev.ellipog.tenet.client.BookGeometry;

/**
 * Where the player's own inventory panel is, in the window's own pixels.
 *
 * <h2>Why this is arithmetic rather than a field read</h2>
 *
 * <p>The panel's corner lives in {@code AbstractContainerScreen} as {@code leftPos}/{@code topPos}.
 * For the inventory it is recomputed rather than read: the size is a constant and the centring is
 * public knowledge (see below), so no mixin is needed for it. A chest or a modded interface has no
 * constants to recompute from, so those read the live fields through
 * {@code ContainerPanelAccessor} instead -- see {@code ContainerPanel}. But the corner is not a
 * secret -- {@code AbstractContainerScreen.init}
 * centres it as {@code (width - imageWidth) / 2}, and {@code InventoryScreen.init} is the only
 * override that matters here: with the recipe book open on a wide enough window the panel is pushed
 * left to {@code 177 + (width - imageWidth - 200) / 2} instead. Both halves of that are public
 * knowledge -- the recipe book's own {@code isVisible} is public, and "narrow" is {@code width < 379}
 * -- so the live corner is recomputed from the same numbers the game centres from, rather than read
 * from a field nothing here can see.
 *
 * <p>That recomputation is also what keeps the editor honest. The first panel anchor failed because
 * the editor invented a ghost panel with a corner that was not the real panel's corner. The ghost
 * now draws from this same method, so the editor's corner and the game's corner agree by
 * construction whenever the recipe book is closed -- and when it is open the button follows the live
 * panel, which is the behaviour the first version wanted.
 *
 * <h2>Why the sizes are constants</h2>
 *
 * <p>Read from the 1.21.1 sources, not guessed: the container default is 176x166
 * ({@code AbstractContainerScreen}'s own field initialisers), creative overrides to 195x136, and
 * survival keeps the default. A constant that stops matching the game reads as a button that sits a
 * few pixels off where it should -- visible, and fixable in one place, which is why there is one
 * place.
 */
public final class InventoryPanel {

    /** The survival inventory's panel, from {@code AbstractContainerScreen}'s defaults. */
    public static final int SURVIVAL_WIDTH = 176;

    /** The same, vertically. */
    public static final int SURVIVAL_HEIGHT = 166;

    /** The creative inventory's panel, which overrides both. */
    public static final int CREATIVE_WIDTH = 195;

    /** The same, vertically. */
    public static final int CREATIVE_HEIGHT = 136;

    /**
     * Below this window width the recipe book shares the window rather than pushing the panel.
     *
     * <p>From {@code InventoryScreen.init}: {@code widthTooNarrow = width < 379}, and a narrow window
     * centres the panel like the book were closed.
     */
    public static final int NARROW_BELOW = 379;

    /**
     * The gap the Under preset leaves between the panel's bottom edge and the button's top, so a
     * button put under the inventory in one press does not touch it.
     */
    public static final int UNDER_GAP = 4;

    private InventoryPanel() {
    }

    /**
     * The panel's box in the window, from the numbers the game centres from.
     *
     * @param screenWidth  the window, in GUI pixels
     * @param screenHeight the same, vertically
     * @param survival     the survival inventory rather than the creative one
     * @param bookOpen     the recipe book is visible; only read on survival, where only it matters
     */
    public static BookGeometry.Rect rect(int screenWidth, int screenHeight, boolean survival,
                                         boolean bookOpen) {
        int panelWidth = survival ? SURVIVAL_WIDTH : CREATIVE_WIDTH;
        int panelHeight = survival ? SURVIVAL_HEIGHT : CREATIVE_HEIGHT;
        int left;
        if (survival && bookOpen && screenWidth >= NARROW_BELOW) {
            left = 177 + (screenWidth - panelWidth - 200) / 2;
        }
        else {
            left = (screenWidth - panelWidth) / 2;
        }
        return BookGeometry.Rect.at(left, (screenHeight - panelHeight) / 2, panelWidth, panelHeight);
    }

    /**
     * Reads a panel-anchored offset against a different panel, keeping outside gaps outside.
     *
     * <p>The file holds one offset, measured from the inventory panel's corner: that is what the HUD
     * editor writes, against its survival ghost, and what the two inventory screens re-base on their
     * live corners. A chest is taller than that panel and a beacon is wider, so the same offset read
     * against the same corner lands inside a bigger panel: a button put 4 pixels under the inventory
     * draws 52 pixels inside a double chest. What survives the move is the gap, not the number.
     *
     * <p>So an offset outside the inventory panel is reinterpreted as a gap from the nearest edge of
     * the panel it is read against: above stays the same pixels above the top, below stays the same
     * pixels below the bottom, and likewise left and right. An offset inside keeps its pixels from the
     * corner, which tracks the panel art the way a slot does. On a panel the same size as the
     * inventory's every branch answers the offset it was given, so this is the identity there.
     *
     * <p>Game-free, like everything else here: two ints in, two ints out, no screen.
     *
     * @param dx          the stored horizontal offset, from the inventory panel's corner
     * @param dy          the stored vertical offset, from the inventory panel's corner
     * @param panelWidth  the width of the panel being read against
     * @param panelHeight the height of the panel being read against
     * @return {@code {x, y}}, the offset from the new panel's corner
     */
    public static int[] againstPanel(int dx, int dy, int panelWidth, int panelHeight) {
        int x = dx < 0 ? dx
                : dx >= SURVIVAL_WIDTH ? panelWidth + (dx - SURVIVAL_WIDTH)
                : dx;
        int y = dy < 0 ? dy
                : dy >= SURVIVAL_HEIGHT ? panelHeight + (dy - SURVIVAL_HEIGHT)
                : dy;
        return new int[] {x, y};
    }

    /**
     * The offset that puts a button centred under the panel with room to breathe.
     *
     * <p>Measured from the panel's top-left corner, like every inventory-anchored position: the x
     * centres a button of the given width under a panel of the given width, and the y clears the
     * panel's bottom edge by {@link #UNDER_GAP}. Integer division rounds the half pixel down, which
     * is one pixel left of exact on odd differences -- invisible on a 16-pixel button.
     *
     * @return {@code {x, y}}, the offset to store
     */
    public static int[] underOffset(int panelWidth, int panelHeight, int buttonWidth) {
        return new int[] {(panelWidth - buttonWidth) / 2, panelHeight + UNDER_GAP};
    }
}
