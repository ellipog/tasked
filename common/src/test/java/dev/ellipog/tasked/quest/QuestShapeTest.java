package dev.ellipog.tasked.quest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The node shapes' geometry.
 *
 * <h2>Why this class needed tests before it needed anything else</h2>
 *
 * <p>{@link QuestShape} spent a while being parsed, validated, printed by {@code /tasked} and
 * requested by the shipped questline — while nothing read it, the client drew a square, and
 * {@code QuestSync} did not even put the field on the wire. Its javadoc claimed hit-testing "is done
 * with maths", which no test could have observed, because there was no maths.
 *
 * <p>So the point of these tests is not to pin down the numbers. It is to assert the <b>invariants</b>
 * that make the three uses agree — the drawing, the hit test and the icon inset all come from one
 * {@link QuestShape#span} table — and to make the shape's own description of itself checkable. A
 * future shape that returns a gap or an inverted span fails here rather than producing a node nobody
 * can click.
 *
 * <h2>The sizes swept, and why they are not round numbers</h2>
 *
 * <p>A zoomed node can be any size, and the awkward cases are the small ones and the odd ones: at 12
 * pixels a circle's top row computes to zero width, and at an odd size a symmetric shape can come out
 * one pixel lopsided. So the sweep runs 1..80, every shape, every row.
 */
class QuestShapeTest {

    /** Every shape, so a new one cannot be forgotten in a sweep by not being listed. */
    private static final List<QuestShape> ALL = List.of(QuestShape.values());

    /** Sizes swept by the invariant tests. Every size from 1 to 80, then some large odd ones. */
    private static List<Integer> sizes() {
        List<Integer> out = new ArrayList<>();
        for (int size = 1; size <= 80; size++) {
            out.add(size);
        }
        out.addAll(List.of(97, 128, 199, 512));
        return out;
    }

    // ------------------------------------------------------------------
    // Invariants, over every shape and size
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("no span is ever empty or outside the square")
        void spansAreNeverEmptyOrOutOfBounds() {
            // The single most important property. A zero-width row is a visible gap in the outline,
            // and a span past the edge is a node drawn over its neighbour. Both would appear only at
            // particular sizes, which is exactly the kind of bug a person finds on one monitor.
            for (QuestShape shape : ALL) {
                for (int size : sizes()) {
                    for (int row = 0; row < size; row++) {
                        int[] span = shape.span(row, size);
                        assertNotNull(span, shape + " returned null for row " + row + " of " + size);
                        assertEquals(2, span.length);
                        assertTrue(span[0] >= 0, shape + " " + size + " row " + row + ": from < 0");
                        assertTrue(span[0] < span[1],
                                shape + " " + size + " row " + row + ": empty span " + span[0] + ".." + span[1]);
                        assertTrue(span[1] <= size,
                                shape + " " + size + " row " + row + ": to " + span[1] + " past the edge");
                    }
                }
            }
        }

        @Test
        @DisplayName("a row outside the shape has no span")
        void rowsOutsideHaveNoSpan() {
            for (QuestShape shape : ALL) {
                assertNull(shape.span(-1, 20), shape + " had a span above itself");
                assertNull(shape.span(20, 20), shape + " had a span at its bottom edge");
                assertNull(shape.span(0, 0), shape + " had a span in a zero-size square");
            }
        }

        @Test
        @DisplayName("every shape is symmetric top to bottom")
        void shapesAreVerticallySymmetric() {
            // True of all four by intent, and cheap to check. The first ROUNDED implementation measured
            // its corner depth from the top edge only, which rounded the top two corners and left the
            // bottom two square -- a fault that is obvious in a picture and invisible in a test that
            // does not ask.
            for (QuestShape shape : ALL) {
                for (int size : sizes()) {
                    for (int row = 0; row < size; row++) {
                        int[] top = shape.span(row, size);
                        int[] bottom = shape.span(size - 1 - row, size);
                        assertEquals(top[0], bottom[0],
                                shape + " " + size + ": row " + row + " and its mirror disagree on from");
                        assertEquals(top[1], bottom[1],
                                shape + " " + size + ": row " + row + " and its mirror disagree on to");
                    }
                }
            }
        }

        @Test
        @DisplayName("no row is wider than the one nearer the middle")
        void widthNarrowsTowardTheEdges() {
            // Monotonicity, which maxIconInset's search depends on: it checks only the two edge rows of
            // a candidate square because every row between them is at least as wide. If that stopped
            // being true, the icon would overflow on a bulge in the middle -- and the code would look
            // correct, because the assumption is documented rather than asserted.
            for (QuestShape shape : ALL) {
                for (int size : sizes()) {
                    for (int row = 1; row < size / 2; row++) {
                        int[] outer = shape.span(row - 1, size);
                        int[] inner = shape.span(row, size);
                        assertTrue(inner[0] <= outer[0] && inner[1] >= outer[1],
                                shape + " " + size + ": row " + row + " is narrower than row " + (row - 1));
                    }
                }
            }
        }

        @Test
        @DisplayName("a point is inside exactly when it is in the span for its row")
        void containsAgreesWithSpan() {
            // The property that makes the hit test trustworthy, asserted directly rather than assumed:
            // whatever span says to fill, contains says is clickable. A separate inequality would agree
            // almost everywhere and differ by a pixel at the edges, which shows up as a node that
            // refuses a click on its own border.
            for (QuestShape shape : ALL) {
                for (int size : List.of(12, 26, 33, 48, 64)) {
                    for (int row = 0; row < size; row++) {
                        int[] span = shape.span(row, size);
                        for (int col = 0; col < size; col++) {
                            boolean inSpan = col >= span[0] && col < span[1];
                            assertEquals(inSpan, shape.containsLocal(col + 0.5, row + 0.5, size),
                                    shape + " " + size + " at " + col + "," + row);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("a point outside the square is never inside, including just above it")
        void pointsOutsideAreNeverInside() {
            // The negative-coordinate case specifically. A cast to int truncates towards zero, so
            // local y of -0.4 lands on row 0 and reads as inside -- which is how a click a fraction
            // above a node selects it. floor does not, and this is the test that says so.
            for (QuestShape shape : ALL) {
                assertTrue(!shape.containsLocal(-0.4, 5.0, 48), shape + " accepted a negative x");
                assertTrue(!shape.containsLocal(5.0, -0.4, 48), shape + " accepted a negative y");
                assertTrue(!shape.containsLocal(-1.0, -1.0, 48), shape + " accepted outside");
                assertTrue(!shape.containsLocal(48.0, 5.0, 48), shape + " accepted x past the edge");
                assertTrue(!shape.containsLocal(5.0, 48.0, 48), shape + " accepted y past the edge");
            }
        }
    }

    // ------------------------------------------------------------------
    // What each shape actually looks like
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("each shape")
    class Shapes {

        @Test
        @DisplayName("ROUNDED is full width through its middle and cut at the corners")
        void roundedHasCutCorners() {
            int size = 48;
            int[] middle = QuestShape.ROUNDED.span(size / 2, size);
            assertEquals(0, middle[0], "the middle row should reach the left edge");
            assertEquals(size, middle[1], "the middle row should reach the right edge");

            int[] top = QuestShape.ROUNDED.span(0, size);
            assertTrue(top[0] > 0, "the top row should be cut in");
            assertTrue(top[1] < size, "the top row should be cut in");
            assertEquals(top[0], size - top[1], "the two corners should match");
        }

        @Test
        @DisplayName("ROUNDED's corner radius is what its name says")
        void roundedCornerIsAQuarterCircle() {
            // At 48 pixels the radius is 12, so the cut at the top row is the full radius and the cut
            // is gone by row 12. Asserted because it is the difference between a rounded rectangle and
            // a slightly clipped square, which is hard to tell apart in a small picture.
            int size = 48;
            assertEquals(12, QuestShape.ROUNDED.span(0, size)[0], "the top row should be cut by the radius");
            assertEquals(0, QuestShape.ROUNDED.span(12, size)[0], "the cut should be gone by the radius");
        }

        @Test
        @DisplayName("CIRCLE is widest through the middle and narrowest at the top")
        void circleIsRound() {
            int size = 48;
            int[] middle = QuestShape.CIRCLE.span(size / 2, size);
            int[] top = QuestShape.CIRCLE.span(0, size);

            assertTrue(middle[1] - middle[0] > size * 0.9, "the middle should be nearly full width");
            assertTrue(top[1] - top[0] < size * 0.4, "the top row should be a narrow cap");
            // The middle row of a 48-pixel circle should reach both edges within the rounding of the
            // pixel-centre measurement.
            assertTrue(middle[0] <= 1 && middle[1] >= size - 1, "the middle should touch both edges");
        }

        @Test
        @DisplayName("CIRCLE's inscribed square is size/sqrt(2), as a pencil would give")
        void circleInscribedSquareMatchesTheMaths() {
            // The strongest available check on maxIconInset, because there is an independent closed
            // form: the largest square inside a circle of diameter d has side d/sqrt(2), so the inset
            // is (d - d/sqrt(2))/2. For 48 that is 7.03, so 7.
            for (int size : List.of(24, 48, 96)) {
                int inset = QuestShape.CIRCLE.maxIconInset(size);
                double expected = (size - size / Math.sqrt(2)) / 2.0;
                assertTrue(Math.abs(inset - expected) <= 1.5,
                        "circle " + size + " gave inset " + inset + ", expected about " + expected);
            }
        }

        @Test
        @DisplayName("HEXAGON is full width through its middle and tapers in a straight line")
        void hexagonTapersLinearly() {
            int size = 48;
            int middle = QuestShape.HEXAGON.span(size / 2, size)[0];
            assertEquals(0, middle, "the middle should reach the edge");

            // A straight taper, not an arc: the cut should fall by the same amount on each of the first
            // few rows. A circle's would not -- it starts slow and accelerates -- and that is the whole
            // visual difference between a hexagon and a rounded rectangle at this size.
            int a = QuestShape.HEXAGON.span(0, size)[0];
            int b = QuestShape.HEXAGON.span(1, size)[0];
            int c = QuestShape.HEXAGON.span(2, size)[0];
            assertEquals(b - c, a - b, "the taper should be linear");
            assertTrue(a > c, "the taper should be narrowing towards the edge");
        }

        @Test
        @DisplayName("TOME has a flat spine on the left and a rounded fore-edge on the right")
        void tomeIsABookWithAFlatSpine() {
            // The asymmetry is the read, and the first version had it backwards: it gave the *left* the
            // larger radius, producing a quarter cut out of the top-left -- at 48 pixels, a bar floating
            // right of centre. Six pixels of asymmetry sounded plausible and looked like a broken shape.
            int size = 48;
            for (int row = 0; row < size; row++) {
                assertEquals(0, QuestShape.TOME.span(row, size)[0],
                        "row " + row + ": the spine should be a straight edge at x=0");
            }
            assertEquals(size, QuestShape.TOME.span(size / 2, size)[1], "the middle should reach the right");
            assertTrue(QuestShape.TOME.span(0, size)[1] < size, "the top should be cut on the right");
        }

        @Test
        @DisplayName("TOME is visibly narrower at its corners than ROUNDED is")
        void tomeIsDistinctFromRounded() {
            // Two shapes that differ by a pixel are two shapes an author cannot tell apart, and one of
            // them is then pointless. The fore-edge radius is a third of the size against ROUNDED's
            // quarter, so the corners must differ by a clear margin.
            int size = 48;
            int tomeCut = size - QuestShape.TOME.span(0, size)[1];
            int roundedCut = QuestShape.ROUNDED.span(0, size)[0];
            assertTrue(tomeCut > roundedCut + 3,
                    "TOME's corner cut " + tomeCut + " is too close to ROUNDED's " + roundedCut);
        }
    }

    // ------------------------------------------------------------------
    // The icon inset
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the icon inset")
    class IconInset {

        @Test
        @DisplayName("the square at the inset fits inside the shape")
        void theIconSquareFits() {
            for (QuestShape shape : ALL) {
                for (int size : List.of(12, 20, 26, 33, 48, 64, 128)) {
                    int inset = shape.maxIconInset(size);
                    for (int row = inset; row <= size - 1 - inset; row++) {
                        int[] span = shape.span(row, size);
                        assertTrue(span[0] <= inset && span[1] >= size - inset,
                                shape + " " + size + " inset " + inset + ": row " + row
                                        + " span " + span[0] + ".." + span[1] + " does not cover it");
                    }
                }
            }
        }

        @Test
        @DisplayName("one pixel less does not fit, so the answer is the largest and not merely an answer")
        void theIconSquareIsAsLargeAsItCanBe() {
            // The tightness assertion, and the one that catches a search running the wrong way. The
            // first implementation returned on the first *failure*, which for a circle returned 0 --
            // a full-size icon hanging well outside the outline. "Fits" alone would have passed that.
            for (QuestShape shape : ALL) {
                for (int size : List.of(12, 26, 48, 64)) {
                    int inset = shape.maxIconInset(size);
                    if (inset == 0) {
                        continue;   // already the largest possible
                    }
                    int smaller = inset - 1;
                    boolean fits = true;
                    for (int row = smaller; row <= size - 1 - smaller; row++) {
                        int[] span = shape.span(row, size);
                        if (span[0] > smaller || span[1] < size - smaller) {
                            fits = false;
                            break;
                        }
                    }
                    assertTrue(!fits, shape + " " + size + ": inset " + smaller
                            + " also fits, so " + inset + " was not the largest");
                }
            }
        }

        @Test
        @DisplayName("a circle needs a bigger inset than a rounded rectangle")
        void aCircleNeedsMoreRoomThanARoundedRectangle() {
            // The reason the inset cannot be one constant, stated as a test. A circle at 48 wants 7 and
            // a rounded rectangle wants 4: one number for both would either spill the icon outside the
            // circle or waste a fifth of the rounded rectangle.
            for (int size : List.of(24, 48, 96, 128)) {
                assertTrue(QuestShape.CIRCLE.maxIconInset(size) > QuestShape.ROUNDED.maxIconInset(size),
                        "at " + size + " the circle should need more inset than the rounded rectangle");
            }
        }

        @Test
        @DisplayName("the inset never eats the whole node, at any size a node can be drawn at")
        void iconInsetAlwaysLeavesRoom() {
            // size >= 3, not >= 2, and the boundary matters. At 2 pixels there is no shape to speak of
            // and the correct inset is 0 -- which this method's first version clamped up to 1, so the
            // box came out as 2 - 2 = 0. That floor was in the code while the javadoc above it claimed
            // there was no floor: a comment and its code disagreeing, which is the failure this whole
            // round has been about.
            //
            // Nothing below MIN_ITEM_BOX reaches an icon anyway -- the screen checks the box first and
            // draws a plain block for a small node -- so this asserts the property that matters:
            // whatever the inset is, there is a non-empty box left for it to sit in.
            for (QuestShape shape : ALL) {
                assertEquals(0, shape.iconInset(2), shape + " at size 2 should give no inset, not a floor");
                for (int size = 3; size <= 80; size++) {
                    int inset = shape.iconInset(size);
                    assertTrue(size - inset * 2 >= 1,
                            shape + " " + size + ": inset " + inset + " leaves a box of "
                                    + (size - inset * 2) + " pixels");
                }
            }
        }

        @Test
        @DisplayName("a node the screen would draw an icon in has room for one")
        void nodesBigEnoughForAnIconHaveRoomForOne() {
            // The screen's own threshold, asserted against every shape: if the box would be at least
            // MIN_ITEM_BOX, it is non-empty for every shape -- so the guard and the geometry cannot
            // disagree about which nodes get an item.
            int minItemBox = 12;
            for (QuestShape shape : ALL) {
                for (int size = 12; size <= 80; size++) {
                    int box = size - shape.iconInset(size) * 2;
                    if (box >= minItemBox) {
                        assertTrue(box > 0, shape + " " + size + " would draw a " + box + "-pixel item");
                    }
                }
            }
        }

        @Test
        @DisplayName("a size too small to hold anything gives zero rather than throwing")
        void degenerateSizesDoNotThrow() {
            // <p>A screen must not crash because a quest file said size 1 -- the validator bounds the
            // real range, but this class is also reached by a payload from a server that might not.
            for (QuestShape shape : ALL) {
                assertEquals(0, shape.maxIconInset(0), shape + " at size 0");
                assertEquals(0, shape.maxIconInset(1), shape + " at size 1");
                assertEquals(0, shape.maxIconInset(2), shape + " at size 2");
            }
        }
    }
}
