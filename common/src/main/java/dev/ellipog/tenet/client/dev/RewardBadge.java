package dev.ellipog.tenet.client.dev;

import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.shape.Shape;

/**
 * The reward badge: a round mark pinned to a node's outline, carrying how many rewards are waiting.
 *
 * <h2>Where it goes, and why not the corner</h2>
 *
 * <p>The first version sat at the node's bounding-box corner, and on anything but a square that is
 * empty space — a star's badge floated beside the point it was supposed to be attached to. So the
 * anchor is the silhouette's own furthest point up and to the right: the row's rightmost covered
 * pixel, maximising {@code x - y}. That is a square's corner, a circle's 45-degree point, a star's
 * upper-right arm edge and a heart's right lobe, with no per-shape table — and a concave notch is
 * never that point, so the badge cannot land in one.
 *
 * <h2>Why it is round, and why it shrinks</h2>
 *
 * <p>Round because that is what a badge is: a square's corners read as a second node's corner, and a
 * disc has room to hold a digit with padding — three pixels either side and two above and below at the
 * full size. Nine pixels was tried first and the digit pressed against the ring; eleven is the size at
 * which it reads as a coin with a number on it rather than as a number in a box. Below the size where
 * the digit fits inside the ring it becomes a dot, and it shrinks with the node, so a zoomed-out graph
 * does not grow coins half the size of the nodes they mark. The sidebar counts quests with something waiting, and the quest's card or the rewards inbox counts the rewards themselves.
 *
 * <h2>Why the caller passes the colours</h2>
 *
 * <p>This class draws pixels, not policy: which corner, which theme tokens and when a badge appears
 * are the screen's decisions, and keeping them out of here is what lets the geometry be asserted
 * against a recorder with no screen at all.
 */
public final class RewardBadge {

    /** The badge's diameter at full size: a 3x5 digit with room to breathe. */
    public static final int FULL_DIAMETER = 11;

    /** The dot's diameter, for a node too small to carry the digit. */
    public static final int DOT_DIAMETER = 5;

    /** The node size at which the badge stops being a dot and starts carrying the count. */
    public static final int DIGIT_NODE_SIZE = 36;

    /** A digit's cell: three wide and five tall, centred in the disc. */
    public static final int DIGIT_WIDTH = 3;
    public static final int DIGIT_HEIGHT = 5;

    /** The count above which the badge shows a dot instead of a digit. */
    public static final int MAX_DIGIT = 9;

    private RewardBadge() {
    }

    /**
     * The digits one to nine, as rows of three bits each, most significant bit on the left.
     *
     * <p>A table rather than a font: nine shapes, drawn once, and a caller that needed a different
     * glyph would be inventing a font — which this is deliberately not.
     */
    private static final int[][] DIGITS = {
            {0b010, 0b110, 0b010, 0b010, 0b111},   // 1
            {0b111, 0b001, 0b111, 0b100, 0b111},   // 2
            {0b111, 0b001, 0b111, 0b001, 0b111},   // 3
            {0b101, 0b101, 0b111, 0b001, 0b001},   // 4
            {0b111, 0b100, 0b111, 0b001, 0b111},   // 5
            {0b111, 0b100, 0b111, 0b101, 0b111},   // 6
            {0b111, 0b001, 0b010, 0b010, 0b010},   // 7
            {0b111, 0b101, 0b111, 0b101, 0b111},   // 8
            {0b111, 0b101, 0b111, 0b001, 0b111},   // 9
    };

    /** A point in the node's own local coordinates. */
    public record Point(int x, int y) {
    }

    /** The diameter this badge draws at for a node of this screen size. */
    public static int diameterFor(int nodeSize) {
        return nodeSize >= DIGIT_NODE_SIZE ? FULL_DIAMETER : DOT_DIAMETER;
    }

    /**
     * The outline point the badge hangs off: the covered pixel furthest up and to the right.
     *
     * <p>The shape's own span table, which is the table the panel is drawn from — so the anchor is on
     * the pixels the player can see, rotation included, rather than on a bounding box that a circle,
     * diamond or star does not fill. On a tie (a diamond's top tip and right point score the same)
     * the rightmost wins, because a badge beside a point reads better than one above it.
     *
     * @return node-local coordinates; the top-right corner for a shape that covers nothing
     */
    public static Point anchor(Shape geometry, int size) {
        int bestX = size - 1;
        int bestY = 0;
        int bestScore = Integer.MIN_VALUE;
        for (int row = 0; row < size; row++) {
            int[] spans = geometry.spans(row, size);
            if (spans == null || spans.length == 0) {
                continue;
            }
            // The rightmost covered pixel of the row: the last span's end, minus one because the end
            // is exclusive. `spans` is a flat array of from/to pairs, and a shape may have several.
            int x = spans[spans.length - 1] - 1;
            int score = x - row;
            if (score > bestScore || (score == bestScore && x > bestX)) {
                bestScore = score;
                bestX = x;
                bestY = row;
            }
        }
        return new Point(bestX, bestY);
    }

    /**
     * Draws the badge for one node: a ringed disc pinned to the outline, with the count inside.
     *
     * <p>A count below one draws nothing — there is no badge for nothing waiting.
     */
    public static void draw(GuiRenderer r, Shape geometry, int nodeX, int nodeY, int nodeSize,
                            int count, int fill, int ring, int ink) {
        if (count < 1) {
            return;
        }
        int diameter = diameterFor(nodeSize);
        int radius = diameter / 2;
        Point anchor = anchor(geometry, nodeSize);
        // Just outside the outline along the same diagonal, overlapping it by about a pixel: a badge
        // that floats reads as a second object, and one touching the silhouette reads as a mark on it.
        int step = Math.max(1, Math.round((radius - 1) * 0.7071F));
        int cx = nodeX + anchor.x() + step;
        int cy = nodeY + anchor.y() - step;

        // Border as the whole disc, the fill inset over it — the order `ArmatureTheme.panel` uses,
        // which at this size is the only way to get a ring rather than a two-colour smudge.
        disc(r, cx, cy, radius, ring);
        disc(r, cx, cy, radius - 1, fill);

        if (diameter < FULL_DIAMETER) {
            // A dot: a digit inside this ring would touch its edges, and the sidebar has the count of quests.
            return;
        }
        if (count > MAX_DIGIT) {
            // Two digits at this size are a smudge; a bullet says "several", and the quest's card
            // says how many.
            r.fill(cx - 1, cy - 1, cx + 2, cy + 2, ink);
            return;
        }
        int[] digit = DIGITS[count - 1];
        int left = cx - DIGIT_WIDTH / 2;
        int top = cy - DIGIT_HEIGHT / 2;
        for (int row = 0; row < DIGIT_HEIGHT; row++) {
            for (int col = 0; col < DIGIT_WIDTH; col++) {
                if ((digit[row] & (1 << (DIGIT_WIDTH - 1 - col))) != 0) {
                    r.fill(left + col, top + row, left + col + 1, top + row + 1, ink);
                }
            }
        }
    }

    /** One filled disc: a row per pixel of height, each row's half-width taken from the circle. */
    private static void disc(GuiRenderer r, int cx, int cy, int radius, int colour) {
        for (int dy = -radius; dy <= radius; dy++) {
            int half = (int) Math.round(Math.sqrt((double) (radius * radius - dy * dy)));
            r.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, colour);
        }
    }
}
