package dev.ellipog.tasked.client.dev;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a menu lands: at the pointer, and never off the window.
 *
 * <p>The reported fault was a menu that appeared on a fixed x whatever the click — the sidebar column's
 * edge — so every case here is about the pointer deciding, and about the four ways there may be no room
 * for it.
 */
@DisplayName("MenuPlacement")
class MenuPlacementTest {

    private static final int WIDTH = 150;
    private static final int HEIGHT = 100;
    private static final int OFFSET = 8;

    /** A generous window, so the only thing under test is which side of the pointer the panel lands. */
    private static MenuPlacement.Placed inWindow(int anchorX, int anchorY) {
        return MenuPlacement.place(anchorX, anchorY, WIDTH, HEIGHT, WIDTH, 0, 0, 1000, 800, OFFSET);
    }

    @Test
    @DisplayName("the panel opens at the pointer, not at some fixed edge")
    void atThePointer() {
        assertEquals(108, inWindow(100, 50).x(), "eight pixels right of a click at 100");
        assertEquals(58, inWindow(100, 50).y());
        assertEquals(308, inWindow(300, 50).x(), "and it follows the click");
        assertEquals(108, inWindow(100, 50).x(), "while a click near the left stays near the left");
    }

    @Test
    @DisplayName("near the right edge it opens to the left of the pointer instead")
    void flipsLeft() {
        MenuPlacement.Placed placed = inWindow(980, 50);

        assertTrue(placed.x() + WIDTH <= 1000, "inside the window: " + placed.x());
        assertEquals(980 - OFFSET - WIDTH, placed.x(), "and on the pointer's other side");
    }

    @Test
    @DisplayName("near the bottom it opens above the pointer instead")
    void flipsUp() {
        MenuPlacement.Placed placed = inWindow(100, 790);

        assertTrue(placed.y() + HEIGHT <= 800, "inside the window: " + placed.y());
        assertEquals(790 - OFFSET - HEIGHT, placed.y());
    }

    @Test
    @DisplayName("in the bottom-right corner it opens up and to the left, still inside")
    void theCorner() {
        MenuPlacement.Placed placed = inWindow(995, 795);

        assertTrue(placed.x() + WIDTH <= 1000, "x " + placed.x());
        assertTrue(placed.y() + HEIGHT <= 800, "y " + placed.y());
    }

    @Test
    @DisplayName("a window smaller than the panel keeps the panel's top-left visible rather than throwing")
    void aTinyWindowDegrades() {
        MenuPlacement.Placed placed = MenuPlacement.place(60, 60, WIDTH, HEIGHT, WIDTH, 0, 0, 100, 60,
                OFFSET);

        assertEquals(0, placed.x(), "the left edge wins, so the panel's own top-left is on screen");
        assertEquals(0, placed.y());
    }

    @Test
    @DisplayName("the submenu opens on whichever side has room, and the bridge can follow it")
    void theSubmenuSideFlips() {
        MenuPlacement.Placed roomy = inWindow(100, 50);
        assertTrue(roomy.submenuRight(), "with space to the right, it opens there");
        assertEquals(100 + OFFSET + WIDTH + OFFSET, MenuPlacement.submenuX(roomy, WIDTH, OFFSET),
                "beside the menu, not over it");

        // A menu already flipped to the left of the pointer has no room further left either, so it opens
        // to the right of the menu itself.
        MenuPlacement.Placed tight = MenuPlacement.place(900, 50, WIDTH, HEIGHT, WIDTH, 0, 0, 1000, 800,
                OFFSET);
        int submenuX = MenuPlacement.submenuX(tight, WIDTH, OFFSET);
        assertTrue(submenuX >= 0 && submenuX + WIDTH <= 1000, "the submenu is inside too: " + submenuX);
        assertFalse(tight.submenuRight() && submenuX + WIDTH > 1000,
                "the side it picked is the side that fits");
    }
}
