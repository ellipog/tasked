package dev.ellipog.tasked.quest;

import dev.ellipog.armature.api.data.Codecs;
import com.mojang.serialization.Codec;

/**
 * The outline a quest is drawn with on the graph canvas — and the geometry of that outline.
 *
 * <h2>One span table, used by the drawing, the hit test and the icon</h2>
 *
 * <p>Everything here comes from {@link #span(int, int)}: the horizontal extent of the shape on one row
 * of its bounding square. The renderer walks the rows and fills each span; the hit test asks whether
 * the pointer is inside the span for its row; the icon inset asks which square fits inside every row
 * it covers. So <b>a click lands on exactly the pixels that were drawn, and the icon sits inside
 * them</b>, by construction rather than by three pieces of arithmetic agreeing.
 *
 * <p>This is the same rule as the icon and its box, and the two colliding buttons, and the label and
 * its room: a shape and "is this point in the shape" are one number, and everything else is derived.
 * A hit test written separately from the drawing is one that disagrees with what a player can see —
 * and the disagreement shows up as a node that cannot be clicked near its edges, which reads as an
 * input bug rather than as a geometry bug.
 *
 * <h2>Why this class existed for a while without doing any of that</h2>
 *
 * <p>Worth recording, because the shape of the mistake is common. The enum was created with a javadoc
 * saying hit-testing "is done with maths rather than a precomputed pixel mask". It was not: nothing
 * called anything. The field was parsed from JSON, validated, printed by {@code /tasked}, and the
 * shipped example questline asked for {@code "circle"} and {@code "hexagon"} — while the client drew a
 * square and hit-tested a square. Meanwhile {@code QuestSync} did not put {@code shape} on the wire at
 * all, so the client could not have honoured it even if something had tried.
 *
 * <p>A field that is parsed, validated and reported but never consumed is worse than a field that is
 * absent, because it reads as supported. The tell was that the javadoc described behaviour no test
 * could observe — which is the general form of this bug, and the reason the tests below assert the
 * invariants rather than the numbers.
 *
 * <h2>Why not a pixel mask, which is what FTB Quests does</h2>
 *
 * <p>A mask buffer per node ties every node to a square grid and costs memory per shape; these are a
 * few multiplications per row. Worth saying because masks exist for a good reason — arbitrary art —
 * and the trade only makes sense here because these are four fixed primitives.
 *
 * <h2>Small sizes are the real constraint</h2>
 *
 * <p>A node can be drawn as small as 12 pixels, and at that size a circle and a hexagon are four
 * distinguishable rows. Every span is therefore clamped to stay at least one pixel wide, so a shape
 * never disappears or inverts at a small size — a node that renders as nothing is worse than a node
 * that renders as a square.
 */
public enum QuestShape {

    /** A rectangle with rounded corners. The default, and the one that packs into a grid best. */
    ROUNDED,

    /** A circle. Good for a focal quest, poor for a dense tree. */
    CIRCLE,

    /** A hexagon. Reads as "hex tech", and tiles without gaps. */
    HEXAGON,

    /** A book seen from the front. For chapter entry points. */
    TOME;

    public static final Codec<QuestShape> CODEC = Codecs.enumByName(QuestShape.class);

    /**
     * A shape by its JSON name, falling back rather than throwing.
     *
     * <p>For the client, which reads a name off the wire. A payload from a server running a newer
     * version can name a shape this client has never heard of, and the choice is between a node drawn
     * as the default shape and a screen that throws while a player is standing in front of it. The
     * default is obviously right, and it is a different decision from the validator's — there, an
     * unknown name is an error, because the author can still fix it.
     */
    public static QuestShape byName(String name, QuestShape fallback) {
        if (name == null || name.isEmpty()) {
            return fallback;
        }
        for (QuestShape shape : values()) {
            if (shape.name().equalsIgnoreCase(name)) {
                return shape;
            }
        }
        return fallback;
    }

    /**
     * The horizontal extent of this shape on {@code row} of a {@code size}-pixel square.
     *
     * @param row 0 to {@code size - 1}, measured from the <b>top</b>
     * @param size the square's width and height
     * @return {@code {from, to}} in local coordinates, {@code from} inclusive and {@code to} exclusive,
     *     so the caller fills {@code from} to {@code to - 1}; or {@code null} when the row is outside
     *     the shape. Never returns an empty span — a zero-width row is clamped to one pixel, because a
     *     gap in a drawn outline reads as a rendering fault rather than as a small shape.
     */
    public int[] span(int row, int size) {
        if (size <= 0 || row < 0 || row >= size) {
            return null;
        }

        int from;
        int to;

        switch (this) {
            case ROUNDED -> {
                // A quarter-circle corner of radius r, by the circle equation: at depth d from the top
                // or bottom edge, the corner cuts in by r - sqrt(r^2 - (r - d)^2).
                int r = Math.max(1, size / 4);
                int inset = cornerCut(row, size, r);
                from = inset;
                to = size - inset;
            }
            case CIRCLE -> {
                // The half-width at this row, from the same equation, measured to the pixel centre so
                // an odd size comes out symmetric.
                double centre = (size - 1) / 2.0;
                double radius = size / 2.0;
                double dy = row - centre;
                double dx = Math.sqrt(Math.max(0.0, radius * radius - dy * dy));
                from = (int) Math.round(centre - dx);
                to = (int) Math.round(centre + dx) + 1;
            }
            case HEXAGON -> {
                // Flat-topped: the full width through the middle, tapering by a straight line to the
                // top and bottom edges. The straight taper is what makes it a hexagon rather than a
                // rounded rectangle, so it must not be a circle arc.
                int taper = Math.max(1, size / 4);
                int depth = Math.min(row, size - 1 - row);
                int inset = depth >= taper ? 0 : (int) Math.round((taper - depth) * (size / 4.0) / taper);
                from = inset;
                to = size - inset;
            }
            case TOME -> {
                // A book seen from the front: the spine is a straight vertical edge on the left, and
                // the fore-edge is rounded on the right. That asymmetry is the whole read -- a shape
                // rounded equally on both sides is ROUNDED and says nothing about a book.
                //
                // The first version of this gave the *left* the larger radius, which produced a shape
                // with a quarter cut out of its top-left rather than a book: at 48 pixels the top row
                // ran from x=24 to x=40, a bar floating right of centre. Six pixels of asymmetry
                // sounded right and looked like a broken shape, which is the cost of writing geometry
                // without drawing it.
                int fore = Math.max(1, size / 3);
                int cut = cornerCut(row, size, fore);
                from = 0;
                to = size - cut;
            }
            default -> {
                from = 0;
                to = size;
            }
        }

        // Clamp into the square, and keep at least one pixel: at 12 pixels a CIRCLE's top row computes
        // to a zero-width span, and a zero-width row is a hole in the outline.
        from = Math.max(0, Math.min(from, size - 1));
        to = Math.max(from + 1, Math.min(to, size));
        return new int[] {from, to};
    }

    /**
     * How far a corner of radius {@code r} cuts in on {@code row}, measuring from the nearer edge.
     *
     * <p>Shared by ROUNDED and TOME because they are the same corner at two radii, and two copies of
     * the circle equation is two chances to transpose a term. The `depth` line is what makes the
     * rounding symmetric top and bottom — the first ROUNDED implementation measured depth from the top
     * only, which rounded the top two corners and left the bottom two square.
     */
    private static int cornerCut(int row, int size, int r) {
        int depth = Math.min(row, size - 1 - row);
        if (depth >= r) {
            return 0;
        }
        double dy = r - depth;
        return r - (int) Math.round(Math.sqrt(Math.max(0.0, (double) r * r - dy * dy)));
    }

    /**
     * Whether a point is inside a node of this shape.
     *
     * <p>Asks {@link #span} — the same method the renderer fills from — so the answer is about the
     * pixels actually on screen.
     */
    public boolean contains(double px, double py, int x, int y, int size) {
        return containsLocal(px - x, py - y, size);
    }

    /** The same test in the node's own coordinates, for callers that have already subtracted. */
    public boolean containsLocal(double localX, double localY, int size) {
        if (localX < 0 || localY < 0) {
            return false;
        }
        // floor, not a cast: a cast truncates towards zero, so a local y of -0.4 would land on row 0
        // and read as inside -- which is how a click just above a node would select it.
        int[] span = span((int) Math.floor(localY), size);
        return span != null && localX >= span[0] && localX < span[1];
    }

    /**
     * The smallest inset whose square lies wholly inside this shape — which is the largest icon that
     * fits.
     *
     * <h2>Why this cannot be a constant</h2>
     *
     * <p>The corner of a square is outside a circle of the same size, so one fixed inset either
     * overflows the outline on a circle or wastes a fifth of the area on a rounded rectangle. At 48
     * pixels the four shapes want 4, 7, 6 and 5 — and 7 for a circle is exactly the inscribed square,
     * {@code size/√2}, which is the arithmetic arriving at the answer a pencil would.
     *
     * <p>Derived from the spans, so it cannot disagree with what is drawn or clickable.
     *
     * <h2>Which rows have to be checked, and how the search runs</h2>
     *
     * <p>Only the top and bottom rows of the candidate square: every shape here is widest at its
     * vertical middle and narrows monotonically to either end, so the narrowest row the square covers
     * is always one of its two edges.
     *
     * <p>The search runs <b>upwards from zero and returns the first inset that fits</b>, because a
     * smaller required span is easier to satisfy — so the predicates turn true once and stay true, and
     * the first success is the largest square. The first version returned on the first *failure*
     * instead, which for a circle returned 0 and would have drawn a full-size icon hanging well
     * outside the outline. Getting the direction of a monotone search backwards is the whole bug, and
     * it is why {@code maxIconInsetIsTight} below asserts both halves: that the answer fits, and that
     * one pixel less does not.
     */
    public int maxIconInset(int size) {
        if (size <= 2) {
            return 0;
        }
        for (int inset = 0; inset < size / 2; inset++) {
            int top = inset;
            int bottom = size - 1 - inset;
            if (rowCovers(top, size, inset) && rowCovers(bottom, size, inset)) {
                return inset;
            }
        }
        // Unreachable for the four shapes above: a single centre pixel always fits. Returned rather
        // than thrown so a malformed size cannot crash a screen.
        return Math.max(0, size / 2 - 1);
    }

    /** Whether {@code row}'s span contains every column from {@code inset} to {@code size - inset - 1}. */
    private boolean rowCovers(int row, int size, int inset) {
        int[] span = span(row, size);
        return span != null && span[0] <= inset && span[1] >= size - inset;
    }

    /**
     * What the icon inset should be for a node of this shape and size.
     *
     * <p>Delegates, with <b>no floor</b>. A floor is the tempting thing to add — "at least one pixel,
     * surely" — and it is wrong: at a size of 2 an inset of 1 leaves a box of zero, and the icon is
     * drawn outside the shape it is supposed to be inside. This method's own first version had
     * {@code Math.max(1, …)} in it while the paragraph you are reading claimed no floor existed,
     * which is how the comment and the code came to disagree.
     *
     * <p>Small nodes are handled where they belong instead: the screen only draws an item when the box
     * is at least {@code MIN_ITEM_BOX} wide, and draws a plain block otherwise. So a node too small to
     * hold an icon is a decision about drawing, not about geometry — and this method stays the exact
     * answer to "what fits".
     */
    public int iconInset(int size) {
        return maxIconInset(size);
    }
}
