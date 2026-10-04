package dev.ellipog.tasked.client.dev;

import dev.ellipog.armature.client.ui.shape.Shape;
import dev.ellipog.armature.client.ui.shape.Shapes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The badge's anchor, its disc, and its nine digits.
 *
 * <h2>Why a recorder rather than an eye</h2>
 *
 * <p>A badge is nine pixels across: at that size "the three is really a three" is not something a
 * screenshot settles, and a table entry that is wrong is one bit off in one row. The anchor is worse —
 * a badge at the wrong point on a star looks *almost* right, which is exactly the report that produced
 * this class. The recorder keeps every pixel with its colour, so both are asserted.
 */
@DisplayName("RewardBadge")
class RewardBadgeTest {

    private static final int FILL = 0xFFFFCC00;
    private static final int RING = 0xFF8A6D00;
    private static final int INK = 0xFF000000;

    /** A square outline, the way a square node's own span table reads. */
    private static Shape square() {
        return Shapes.ofSpans((row, size) -> new int[] {0, size});
    }

    /** A diamond: every row is narrower away from the middle, so the corners of its box are empty. */
    private static Shape diamond() {
        return Shapes.ofSpans((row, size) -> {
            int half = size / 2;
            int width = half - Math.abs(row - half);
            return width <= 0 ? null : new int[] {half - width, half + width + 1};
        });
    }

    /** The single-pixel fills of one colour, as "x,y" strings, in the order they were drawn. */
    private static List<String> pixels(RecordingRenderer r, int argb) {
        List<String> out = new ArrayList<>();
        for (RecordingRenderer.Fill fill : r.fills()) {
            if (fill.argb() == argb && fill.right() - fill.left() == 1 && fill.bottom() - fill.top() == 1) {
                out.add(fill.left() + "," + fill.top());
            }
        }
        return out;
    }

    /** Whether some fill of exactly this colour covers this pixel. */
    private static boolean painted(RecordingRenderer r, int x, int y, int argb) {
        return r.fills().stream().anyMatch(fill -> fill.argb() == argb && fill.left() <= x
                && fill.right() > x && fill.top() <= y && fill.bottom() > y);
    }

    @Test
    @DisplayName("the anchor is the silhouette's furthest point up and to the right")
    void theAnchorHugsTheOutline() {
        // A square: its own corner. The old badge's rule, and the only shape where it was right.
        assertEquals(new RewardBadge.Point(47, 0), RewardBadge.anchor(square(), 48));

        // A diamond: the right point, not the top tip. Both score the same on `x - y`, and the tie is
        // broken towards the right because a badge beside a point reads better than one above it.
        RewardBadge.Point onDiamond = RewardBadge.anchor(diamond(), 21);
        assertEquals(new RewardBadge.Point(20, 10), onDiamond);
        assertTrue(diamond().containsLocal(onDiamond.x(), onDiamond.y(), 21),
                "and the anchor must be on the shape, not on the box around it");
        assertFalse(diamond().containsLocal(20, 0, 21),
                "while the box's corner -- where the old rule put it -- is empty space");
    }

    @Test
    @DisplayName("the badge is a ringed disc pinned to that anchor")
    void theBadgeIsRoundAndPinned() {
        RecordingRenderer r = new RecordingRenderer();
        RewardBadge.draw(r, square(), 0, 0, 48, 3, FILL, RING, INK);

        // The square's corner is (47, 0); an eleven-pixel disc steps three pixels out along the
        // diagonal.
        int cx = 50;
        int cy = -3;
        assertTrue(painted(r, cx, cy, FILL), "the disc's middle is filled");
        assertTrue(painted(r, cx - 5, cy, RING), "and its leftmost pixel is the ring");
        assertTrue(painted(r, cx - 4, cy, FILL), "with the fill inset over it");
        assertFalse(r.covered(cx - 5, cy - 5, cx - 4, cy - 4),
                "the corner of the badge's own box is empty, so it is a disc and not a square");

        // The badge overlaps the outline by a pixel or so rather than floating: the anchor pixel is
        // inside the badge's footprint.
        assertTrue(r.covered(47, 0, 48, 1), "the badge must touch the point it hangs off");
    }

    @Test
    @DisplayName("a digit is 3x5 in the middle, and every digit is distinct")
    void digitsAreDrawnAndDistinct() {
        RecordingRenderer r = new RecordingRenderer();
        RewardBadge.draw(r, square(), 0, 0, 48, 1, FILL, RING, INK);

        // One: a stem with a foot and a flag, centred on the disc at (50, -3).
        assertEquals(List.of("50,-5", "49,-4", "50,-4", "50,-3", "50,-2", "49,-1", "50,-1", "51,-1"),
                pixels(r, INK));

        List<List<String>> shapes = new ArrayList<>();
        for (int count = 1; count <= RewardBadge.MAX_DIGIT; count++) {
            RecordingRenderer each = new RecordingRenderer();
            RewardBadge.draw(each, square(), 0, 0, 48, count, FILL, RING, INK);
            List<String> ink = pixels(each, INK);

            assertTrue(ink.size() >= 4 && ink.size() <= RewardBadge.DIGIT_WIDTH * RewardBadge.DIGIT_HEIGHT,
                    "digit " + count + " has an implausible number of pixels: " + ink.size());
            assertFalse(shapes.contains(ink), "digit " + count + " repeats an earlier shape: " + ink);
            shapes.add(ink);
        }
    }

    @Test
    @DisplayName("a node too small for the digit gets a dot, and more than nine gets a bullet")
    void smallNodesAndBigCounts() {
        assertEquals(RewardBadge.DOT_DIAMETER, RewardBadge.diameterFor(20),
                "a zoomed-out node gets the dot");
        RecordingRenderer dot = new RecordingRenderer();
        RewardBadge.draw(dot, square(), 0, 0, 20, 3, FILL, RING, INK);
        assertTrue(painted(dot, 20, -1, FILL), "the dot is filled at its middle");
        assertEquals(0, pixels(dot, INK).size(),
                "and carries no digit, because a 3x5 digit would touch the ring's edges");

        RecordingRenderer many = new RecordingRenderer();
        RewardBadge.draw(many, square(), 0, 0, 48, 12, FILL, RING, INK);
        assertTrue(many.fills().stream().anyMatch(fill -> fill.argb() == INK
                        && fill.right() - fill.left() == 3 && fill.bottom() - fill.top() == 3),
                "more than nine is a bullet: two digits at this size are a smudge");
    }

    @Test
    @DisplayName("nothing waiting draws nothing")
    void nothingWaitingDrawsNothing() {
        RecordingRenderer r = new RecordingRenderer();
        RewardBadge.draw(r, square(), 0, 0, 48, 0, FILL, RING, INK);

        assertEquals(0, r.fills().size());
    }
}
