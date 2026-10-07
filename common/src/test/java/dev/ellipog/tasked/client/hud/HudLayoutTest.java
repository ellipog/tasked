package dev.ellipog.tasked.client.hud;

import dev.ellipog.tasked.client.BookGeometry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HUD editor's arithmetic, over a sweep of window sizes.
 *
 * <h2>Why these properties</h2>
 *
 * <p>Because each of them is a way an editor goes wrong without saying so, and none can be seen in a
 * screenshot taken at one window size:
 *
 * <ul>
 *   <li>an element must be somewhere on the window at every size, however it was dragged or however the file
 *       was hand-edited -- a control at a coordinate from a larger window is a control that has vanished;</li>
 *   <li>the rows panel must be on the window and its rows must not overlap each other, which is the fault
 *       that reads as a rendering glitch rather than a layout one;</li>
 *   <li>and the default position of every element must be on the window at the smallest size a client can
 *       have, because a default is what somebody sees before they have moved anything.</li>
 * </ul>
 *
 * <p>The windows swept are the ones a client can actually have: 176x120 GUI pixels at the smallest -- a
 * window narrower and shorter than any container panel -- up to 1920x1080.
 *
 * <h2>What is deliberately not asserted here</h2>
 *
 * <p>Nothing about where an element is <i>relative to a panel</i>. An earlier version of this class did:
 * positions were offsets from the inventory panel's corner so the button travelled with the panel when the
 * recipe book slid it across the window. That made the editor draw a ghost of a panel it did not have, with
 * a corner that was not the real panel's corner, and the two spaces disagreed -- so the anchor went, and with
 * it every test about it. A window position is the whole of the model now.
 */
@DisplayName("the HUD editor's layout")
class HudLayoutTest {

    private static final int[] WIDTHS = {176, 240, 320, 427, 640, 854, 1280, 1920};
    private static final int[] HEIGHTS = {120, 166, 180, 240, 300, 480, 720, 1080};

    @Test
    @DisplayName("every element is on the window, wherever it was put")
    void anElementIsAlwaysOnTheWindow() {
        HudElement element = HudElement.INVENTORY_BUTTON;
        for (int width : WIDTHS) {
            for (int height : HEIGHTS) {
                for (int x : new int[] {-999, -1, 0, 7, 100, 5000}) {
                    for (int y : new int[] {-60, 0, 33, 4000}) {
                        BookGeometry.Rect box = HudLayout.boxAt(element, width, height, x, y);
                        assertTrue(box.x() >= 0 && box.right() <= Math.max(width, element.width()),
                                "x " + x + " at " + width + " wide landed at " + box);
                        assertTrue(box.y() >= 0 && box.bottom() <= Math.max(height, element.height()),
                                "y " + y + " at " + height + " tall landed at " + box);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("each element's default position is on the window at every size")
    void defaultsFitEveryWindow() {
        for (HudElement element : HudElement.values()) {
            for (int width : WIDTHS) {
                for (int height : HEIGHTS) {
                    BookGeometry.Rect box = HudLayout.boxAt(element, width, height,
                            element.defaultX(), element.defaultY());
                    assertTrue(box.x() >= 0 && box.right() <= Math.max(width, element.width()),
                            element + "'s default x is off the window at " + width + "x" + height + ": " + box);
                    assertTrue(box.y() >= 0 && box.bottom() <= Math.max(height, element.height()),
                            element + "'s default y is off the window at " + width + "x" + height + ": " + box);
                }
            }
        }
    }

    @Test
    @DisplayName("the rows panel is on the window, gives way to a narrow one, and grows with every element")
    void theChromeFitsAndGrows() {
        for (int width : WIDTHS) {
            for (int height : HEIGHTS) {
                for (int rows = 1; rows <= 6; rows++) {
                    BookGeometry.Rect chrome = HudLayout.chrome(width, height, rows);
                    assertTrue(chrome.x() >= 0 && chrome.y() >= 0, "the rows start off the window: " + chrome);
                    assertTrue(chrome.width() <= HudLayout.CHROME_WIDTH,
                            "the rows are never wider than they need to be: " + chrome);

                    if (width >= HudLayout.MIN_CHROME_WIDTH + HudLayout.INSET * 2) {
                        // A window with room for the controls: then nothing may be off it, because a control
                        // the player cannot reach is worse than a panel that overlaps something.
                        assertTrue(chrome.right() <= width, "the rows run off the right edge at " + width
                                + ": " + chrome);
                        assertTrue(chrome.bottom() <= Math.max(height, chrome.height()),
                                "the rows run off the bottom at " + height + ": " + chrome);
                    }
                    else {
                        // Narrower than the controls need. The panel stops shrinking at its floor and the
                        // window gets an overlapping edge, which is the honest outcome -- but it still starts
                        // at the margin rather than somewhere arbitrary.
                        assertEquals(HudLayout.MIN_CHROME_WIDTH, chrome.width(),
                                "below the floor the panel keeps its controls: " + chrome);
                    }

                    if (rows > 1) {
                        assertTrue(chrome.height() > HudLayout.chrome(width, height, rows - 1).height(),
                                "an extra element has to make room for itself");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("the rows are inside their panel and never overlap each other")
    void theRowsStayInsideTheirPanel() {
        for (int rows = 1; rows <= 6; rows++) {
            BookGeometry.Rect chrome = HudLayout.chrome(640, 480, rows);
            BookGeometry.Rect previous = null;
            for (int index = 0; index < rows; index++) {
                BookGeometry.Rect label = HudLayout.label(index, chrome);
                BookGeometry.Rect toggle = HudLayout.toggle(index, chrome);
                BookGeometry.Rect move = HudLayout.move(index, chrome);
                BookGeometry.Rect reset = HudLayout.reset(index, chrome);

                for (BookGeometry.Rect box : new BookGeometry.Rect[] {label, toggle, move, reset}) {
                    assertTrue(inside(box, chrome), "row " + index + "'s " + box + " is outside " + chrome);
                }
                assertFalse(overlaps(toggle, move), "the switch and Move collide on row " + index);
                assertFalse(overlaps(move, reset), "Move and Reset collide on row " + index);
                assertFalse(overlaps(label, toggle), "the label runs into the switch on row " + index);
                if (previous != null) {
                    assertFalse(overlaps(previous, label), "row " + index + " starts before row " + (index - 1)
                            + " has finished");
                }
                previous = label;
            }
            assertTrue(inside(HudLayout.done(chrome), chrome), "Done is inside the foot");
            for (int index = 0; index < rows; index++) {
                assertFalse(overlaps(HudLayout.done(chrome), HudLayout.move(index, chrome)),
                        "Done collides with a row's Move at " + rows + " rows");
            }
        }
    }

    private static boolean overlaps(BookGeometry.Rect one, BookGeometry.Rect other) {
        return one.x() < other.right() && other.x() < one.right()
                && one.y() < other.bottom() && other.y() < one.bottom();
    }

    private static boolean inside(BookGeometry.Rect box, BookGeometry.Rect outer) {
        return box.x() >= outer.x() && box.y() >= outer.y()
                && box.right() <= outer.right() && box.bottom() <= outer.bottom();
    }
}
