package dev.ellipog.tenet.client.hud;

import dev.ellipog.tenet.client.BookGeometry;

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
 * <p>And the drag's own arithmetic, which is the fourth: it is the pointer less the grab the press took, so
 * it cannot drift. The rule that was there — the element's position plus the frame's rounded mouse movement
 * — is written out longhand below as the thing this is not, because that gap is invisible in one screenshot
 * and grows all drag long.
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

    @Test
    @DisplayName("a drag is the pointer less the grab, so the element keeps the offset it was grabbed by")
    void aDragIsThePointerLessTheGrab() {
        double grab = 6.4;
        // Every pointer that leaves the element on the window: its edge is the pointer less the grab, to the
        // pixel, and the offset it was grabbed by does not change from one end of the drag to the other.
        for (double pointer = grab; pointer <= 400; pointer += 0.25) {
            int at = HudLayout.dragged(pointer, grab, 1000);
            assertEquals(pointer - grab, at, 0.5,
                    "the pointer at " + pointer + " with a grab of " + grab);
        }
        // And the two ends, through the same clamp every other position here goes through.
        assertEquals(0, HudLayout.dragged(0, grab, 1000), "a pointer left of the grab parks at the edge");
        assertEquals(1000, HudLayout.dragged(5000, grab, 1000), "and one past the far end parks at the limit");
        assertEquals(0, HudLayout.dragged(grab, grab, 1000), "a pointer on the grab lands the edge on the pointer");
    }

    @Test
    @DisplayName("a drag that arrives in small steps lands where the last pointer says, and the deltas do not")
    void manySmallDragsLandWhereThePointerIs() {
        // The rule this replaced, written out: the element's position plus the frame's rounded movement.
        // The game hands a widget one delta per frame, already scaled into GUI pixels -- so at a GUI scale
        // of three, ten window pixels of movement is 3.333 GUI pixels, which rounds to three. That is a
        // third of a pixel lost every frame of the drag, and a slow drag rounds to nothing at all: an
        // element that sits still while the cursor moves. It was reported as "it follows but drifts".
        int byDeltas = 0;
        double pointer = 0;
        for (int frame = 0; frame < 60; frame++) {
            double next = pointer + 10.0 / 3.0;
            byDeltas += (int) Math.round(next - pointer);
            pointer = next;
        }

        assertEquals(200, HudLayout.dragged(pointer, 0, 1000),
                "the pointer has travelled two hundred pixels, and the element is there");
        assertEquals(180, byDeltas, "while the sum of the rounded deltas is twenty pixels short");
        assertTrue(byDeltas < HudLayout.dragged(pointer, 0, 1000),
                "which is the whole of the drift: a gap that only ever grows");
    }

    @Test
    @DisplayName("a drag past an edge parks the element on the window, and the grab survives it")
    void aDragIsClampedAtEveryEdge() {
        HudElement element = HudElement.INVENTORY_BUTTON;
        for (int width : WIDTHS) {
            for (int height : HEIGHTS) {
                int xLimit = Math.max(0, width - element.width());
                int yLimit = Math.max(0, height - element.height());
                for (double pointer : new double[] {-400, -0.5, 0, 33.5, 5000}) {
                    int x = HudLayout.dragged(pointer, 6.0, xLimit);
                    int y = HudLayout.dragged(pointer, 6.0, yLimit);
                    assertTrue(x >= 0 && x + element.width() <= Math.max(width, element.width()),
                            "a drag to " + pointer + " left the element at " + x + " on a " + width
                                    + "-wide window");
                    assertTrue(y >= 0 && y + element.height() <= Math.max(height, element.height()),
                            "a drag to " + pointer + " left the element at " + y + " on a " + height
                                    + "-tall window");
                }
                // The offset is kept rather than forgotten: the element is parked at the edge and comes back
                // with the pointer, where adding deltas to a clamped position would have made it stick.
                assertEquals(xLimit, HudLayout.dragged(5000, 0, xLimit), "the far edge is reachable");
                assertEquals(xLimit, HudLayout.dragged(5000, 6.0, xLimit),
                        "and a grab cannot push it past the edge either");
            }
        }
    }

    @Test
    @DisplayName("every element's sprite fits inside its box, inset on every side")
    void everySpriteFitsItsBox() {
        for (HudElement element : HudElement.values()) {
            assertTrue(element.iconInset() > 0,
                    element + " has no inset, so its sprite would touch its own edge");
            assertTrue(element.iconInset() * 2 <= Math.min(element.width(), element.height()),
                    element + " draws a " + (element.width() - element.iconInset() * 2) + "-pixel sprite in a "
                            + element.width() + "-pixel box");
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
