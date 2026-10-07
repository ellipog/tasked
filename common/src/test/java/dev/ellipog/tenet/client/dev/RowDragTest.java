package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.client.BookGeometry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The row drag's arithmetic: which gap a pointer stands at, where the row lands, and where the line
 * is drawn.
 *
 * <h2>What is asked here, and what cannot be</h2>
 *
 * <p>The three questions every drag has to answer, and all three are integers. The screen owns the
 * gesture -- the threshold, the live flag, the op on release -- but what a pointer position <i>means</i>
 * is here, asserted over rows the test can see, because "it dropped one row too high" is arithmetic
 * and not a mouse.
 */
@DisplayName("the row drag's arithmetic")
class RowDragTest {

    /** Three rows with the one-pixel gap the inspector's own layout leaves between them. */
    private static final List<BookGeometry.Rect> ROWS = List.of(
            BookGeometry.Rect.at(0, 10, 100, 18),
            BookGeometry.Rect.at(0, 29, 100, 18),
            BookGeometry.Rect.at(0, 48, 100, 18));

    @Test
    @DisplayName("the gap is counted at each row's middle, so the top half belongs to the gap above")
    void theGapIsCountedByMidpoints() {
        assertEquals(0, RowDrag.gapAt(ROWS, 10), "above the first middle: before it");
        assertEquals(0, RowDrag.gapAt(ROWS, 18), "still above the first middle");
        assertEquals(1, RowDrag.gapAt(ROWS, 20), "past the first middle: between one and two");
        assertEquals(2, RowDrag.gapAt(ROWS, 38), "at the second middle counts as after it");
        assertEquals(3, RowDrag.gapAt(ROWS, 60), "past the last middle: the end");
    }

    @Test
    @DisplayName("a pointer past either end clamps to that end")
    void theEndsClamp() {
        assertEquals(0, RowDrag.gapAt(ROWS, -100));
        assertEquals(3, RowDrag.gapAt(ROWS, 1000));
    }

    @Test
    @DisplayName("the row lands at the gap counted without itself")
    void theRowLandsWithoutItself() {
        assertEquals(2, RowDrag.finalIndex(1, 3), "dragged down to the end: one below its own gap");
        assertEquals(0, RowDrag.finalIndex(1, 0), "dragged to the top");
        assertEquals(1, RowDrag.finalIndex(1, 1), "its own gap is its own place");
        assertEquals(1, RowDrag.finalIndex(1, 2), "and so is the gap just under it");
        assertEquals(2, RowDrag.finalIndex(2, 3), "the last row dropped on the end does not move");
    }

    @Test
    @DisplayName("the line is drawn on the gap: the row's edge at the ends, the seam between rows inside")
    void theLineSitsOnTheGap() {
        assertEquals(10, RowDrag.indicatorY(ROWS, 0));
        assertEquals(66, RowDrag.indicatorY(ROWS, 3), "the end of the list is the last row's bottom edge");
        assertEquals((28 + 29) / 2, RowDrag.indicatorY(ROWS, 1));
        assertEquals((47 + 48) / 2, RowDrag.indicatorY(ROWS, 2));
    }

    @Test
    @DisplayName("no rows is no drag: every answer refuses rather than inventing an index")
    void anEmptyListIsNoDrag() {
        assertEquals(-1, RowDrag.gapAt(List.of(), 50));
        assertEquals(-1, RowDrag.indicatorY(List.of(), 0));
    }
}
