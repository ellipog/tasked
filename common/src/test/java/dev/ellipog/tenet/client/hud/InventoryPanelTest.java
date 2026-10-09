package dev.ellipog.tenet.client.hud;

import dev.ellipog.tenet.client.BookGeometry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the player's own inventory panel is, over a sweep of window sizes.
 *
 * <h2>Why the game's own centring is asserted rather than trusted</h2>
 *
 * <p>The corner cannot be read -- {@code leftPos} and {@code topPos} are protected, and this project
 * has no mixins to borrow them through -- so it is recomputed from the numbers the game centres
 * from. A recomputation is a copy, and a copy drifts: the tests below pin the copy against the two
 * sources it was transcribed from ({@code AbstractContainerScreen.init}'s centre, and
 * {@code RecipeBookComponent.updateScreenPosition}'s pushed-left branch), at every swept size, so a
 * vanilla change or a transcription slip reads as a failing test rather than as a button a few pixels
 * off where it should be.
 */
@DisplayName("the inventory panel's corner")
class InventoryPanelTest {

    private static final int[] WIDTHS = {176, 240, 320, 427, 640, 854, 1280, 1920};
    private static final int[] HEIGHTS = {120, 166, 180, 240, 300, 480, 720, 1080};

    @Test
    @DisplayName("a closed book centres the survival panel at every size")
    void survivalCentres() {
        for (int width : WIDTHS) {
            for (int height : HEIGHTS) {
                BookGeometry.Rect panel = InventoryPanel.rect(width, height, true, false);
                assertEquals(BookGeometry.Rect.at((width - 176) / 2, (height - 166) / 2, 176, 166),
                        panel, "survival at " + width + "x" + height);
            }
        }
    }

    @Test
    @DisplayName("creative centres its own wider, shorter panel at every size")
    void creativeCentres() {
        for (int width : WIDTHS) {
            for (int height : HEIGHTS) {
                BookGeometry.Rect panel = InventoryPanel.rect(width, height, false, false);
                assertEquals(BookGeometry.Rect.at((width - 195) / 2, (height - 136) / 2, 195, 136),
                        panel, "creative at " + width + "x" + height);
            }
        }
    }

    @Test
    @DisplayName("an open recipe book pushes the survival panel left on a wide window")
    void bookPushesLeftWhenWide() {
        for (int width : WIDTHS) {
            if (width < InventoryPanel.NARROW_BELOW) {
                continue;
            }
            for (int height : HEIGHTS) {
                BookGeometry.Rect panel = InventoryPanel.rect(width, height, true, true);
                assertEquals(177 + (width - 176 - 200) / 2, panel.x(),
                        "pushed left at " + width + "x" + height);
                assertEquals((height - 166) / 2, panel.y(), "but never pushed down");
                assertEquals(176, panel.width());
                assertEquals(166, panel.height());
            }
        }
    }

    @Test
    @DisplayName("a narrow window centres like the book were closed")
    void narrowIgnoresTheBook() {
        for (int width : WIDTHS) {
            if (width >= InventoryPanel.NARROW_BELOW) {
                continue;
            }
            for (int height : HEIGHTS) {
                assertEquals(InventoryPanel.rect(width, height, true, false),
                        InventoryPanel.rect(width, height, true, true),
                        "narrow at " + width + "x" + height);
            }
        }
    }

    @Test
    @DisplayName("the Under preset centres a button under the panel with room to breathe")
    void underOffsetsCentre() {
        int[] offset = InventoryPanel.underOffset(176, 166, 16);
        assertEquals((176 - 16) / 2, offset[0], "centred under survival");
        assertEquals(166 + InventoryPanel.UNDER_GAP, offset[1], "clear of its bottom edge");

        int[] creative = InventoryPanel.underOffset(195, 136, 16);
        assertEquals((195 - 16) / 2, creative[0], "and centred under creative");
        assertEquals(136 + InventoryPanel.UNDER_GAP, creative[1]);
    }

    @Test
    @DisplayName("reading against the same size is the offset it was given")
    void sameSizeIsIdentity() {
        for (int[] offset : new int[][] {{0, 0}, {80, 170}, {-20, -8}, {180, 200}, {8, 8}}) {
            int[] read = InventoryPanel.againstPanel(offset[0], offset[1], 176, 166);
            assertEquals(offset[0], read[0], "x of " + offset[0] + "," + offset[1]);
            assertEquals(offset[1], read[1], "y of " + offset[0] + "," + offset[1]);
        }
    }

    @Test
    @DisplayName("below the inventory stays below a taller chest by the same gap")
    void belowKeepsItsGap() {
        // The Under preset: centred, 4 clear of the inventory's bottom edge.
        int[] under = InventoryPanel.underOffset(176, 166, 16);
        // A double chest is 56 taller: the button must move down 56 with its bottom edge.
        int[] chest = InventoryPanel.againstPanel(under[0], under[1], 176, 222);
        assertEquals(under[0], chest[0], "still centred, both panels 176 wide");
        assertEquals(222 + InventoryPanel.UNDER_GAP, chest[1], "4 clear of the chest instead");
    }

    @Test
    @DisplayName("above the inventory stays above a taller chest by the same gap")
    void aboveKeepsItsGap() {
        int[] read = InventoryPanel.againstPanel(80, -20, 176, 222);
        assertEquals(80, read[0]);
        assertEquals(-20, read[1], "20 above either top edge");
    }

    @Test
    @DisplayName("beside the inventory stays beside a wider panel by the same gap")
    void besideKeepsItsGap() {
        int[] left = InventoryPanel.againstPanel(-12, 40, 230, 219);
        assertEquals(-12, left[0], "12 left of either left edge");
        assertEquals(40, left[1], "inside vertically, so from the corner");
        int[] right = InventoryPanel.againstPanel(180, 40, 230, 219);
        assertEquals(230 + 4, right[0], "4 right of the wider panel");
        assertEquals(40, right[1]);
    }

    @Test
    @DisplayName("inside the panel tracks the new corner, and edges belong to the gap side")
    void insideAndEdges() {
        int[] inside = InventoryPanel.againstPanel(8, 8, 176, 222);
        assertEquals(8, inside[0]);
        assertEquals(8, inside[1], "pixels from the corner either way");
        int[] touchingBottom = InventoryPanel.againstPanel(80, 166, 176, 222);
        assertEquals(222, touchingBottom[1], "on the edge counts as below, gap zero");
        int[] touchingRight = InventoryPanel.againstPanel(176, 40, 230, 219);
        assertEquals(230, touchingRight[0], "on the edge counts as beside, gap zero");
    }

    @Test
    @DisplayName("an offset placed and read back is the offset it was")
    void offsetsRoundTrip() {
        for (int width : WIDTHS) {
            for (int height : HEIGHTS) {
                for (boolean survival : new boolean[] {true, false}) {
                    for (boolean book : new boolean[] {true, false}) {
                        BookGeometry.Rect panel =
                                InventoryPanel.rect(width, height, survival, book);
                        for (int[] offset : new int[][] {{0, 0}, {80, 170}, {-20, -20}, {500, 900}}) {
                            BookGeometry.Rect box = HudLayout.boxAtInventory(panel.x(), panel.y(),
                                    offset[0], offset[1], 16, 16, width, height);
                            assertEquals(16, box.width());
                            assertEquals(16, box.height());
                            // The clamp is the window's: on a window big enough for both, the box is
                            // the corner plus the offset to the pixel.
                            if (panel.x() + offset[0] >= 0
                                    && panel.x() + offset[0] + 16 <= width
                                    && panel.y() + offset[1] >= 0
                                    && panel.y() + offset[1] + 16 <= height) {
                                assertEquals(panel.x() + offset[0], box.x());
                                assertEquals(panel.y() + offset[1], box.y());
                                assertEquals(offset[0], HudLayout.inventoryX(box.x(), panel.x()));
                                assertEquals(offset[1], HudLayout.inventoryY(box.y(), panel.y()));
                            }
                            assertTrue(box.x() >= 0 && box.right() <= Math.max(width, 16),
                                    "on the window at " + width + "x" + height + ": " + box);
                            assertTrue(box.y() >= 0 && box.bottom() <= Math.max(height, 16));
                        }
                    }
                }
            }
        }
    }
}
