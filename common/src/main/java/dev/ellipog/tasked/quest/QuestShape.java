package dev.ellipog.tasked.quest;

import com.mojang.serialization.Codec;
import dev.ellipog.armature.api.data.Codecs;
import dev.ellipog.armature.client.ui.shape.Shape;
import dev.ellipog.armature.client.ui.shape.Shapes;

/**
 * The outline a quest is drawn with, named as a quest file names it.
 *
 * <h2>This is a name, not a geometry</h2>
 *
 * <p>The shapes live in Armature as {@link Shapes}, where the arithmetic can be used by anything — a
 * button, a panel border, a theme. What is here is the <b>vocabulary a quest file writes</b>:
 * {@code "rounded"}, {@code "square"}, {@code "circle"}, {@code "diamond"}, {@code "hexagon"},
 * {@code "octagon"}, {@code "pentagon"}, {@code "gear"}, {@code "heart"}, {@code "tome"},
 * {@code "star"} and {@code "none"}. Every method below forwards to a {@link Shape}, and the indirection is deliberate
 * rather than vestigial: a data format needs a closed set of names that cannot change without someone
 * noticing, and an extensible geometry type needs the opposite. Those are two different jobs and this
 * class is the smaller one.
 *
 * <h2>Why the geometry moved, and what the move cost</h2>
 *
 * <p>It was all in this enum, and the shape of the mistake is worth recording because it is the same one
 * three times over in this project. A screen needed rounded corners, so the rounded corners were written
 * inside the screen's own shape enum — where nothing else could reach them. A reskin could not put a
 * radius in a data file. A button could not round its corners. A second screen would have written a
 * fourth copy of the circle equation, and the copies are where a transposed term lives undiscovered.
 *
 * <p>So the maths is Armature's, the names are Tasked's, and the tests split the same way: the invariant
 * sweeps over every shape at every size are {@code ShapeTest} in Armature, and what is left here is that
 * each name resolves to the right geometry and that an unknown name falls back rather than throwing.
 * **A test that stays behind when the code moves is a test of a copy**, which is why that split matters
 * rather than being tidiness.
 *
 * <h2>The field that was parsed, validated, printed and never used</h2>
 *
 * <p>Worth keeping, because it is the failure this class's javadoc used to describe as a feature. The
 * enum existed with a comment saying hit-testing "is done with maths rather than a precomputed pixel
 * mask". It was not: nothing called anything. The field was parsed from JSON, validated, printed by
 * {@code /tasked}, and the shipped example questline asked for {@code "circle"} and {@code "hexagon"} —
 * while the client drew a square and hit-tested a square, and the sync layer did not put {@code shape}
 * on the wire at all, so the client could not have honoured it even if something had tried.
 *
 * <p>A field that is parsed, validated and reported but never consumed is worse than a field that is
 * absent, because it reads as supported. The tell was a javadoc describing behaviour no test could
 * observe — which is the general form of that bug, and the reason the tests in Armature assert
 * invariants rather than numbers.
 *
 * <h2>{@link #NONE} is a presentation, not an outline</h2>
 *
 * <p>Every other name here draws a panel and is hit-tested by that panel's own pixels. {@code "none"}
 * draws no panel at all — the node is its icon — and is the one deliberate exception to "a click lands
 * on exactly the pixels that were drawn": its geometry is the square, so the icon fits the node and the
 * node is clickable, but there is no outline to land on. {@link #drawsPanel()} is how the drawing asks,
 * and it is a method on the name rather than a shape in Armature because "draw nothing" is not a
 * geometry — a rectangle is, and that is what it forwards to.
 */
public enum QuestShape {

    /** A rectangle with rounded corners. The default, and the one that packs into a grid best. */
    ROUNDED(Shapes.ROUNDED),

    /** A plain rectangle. The honest "no styling" choice, for a tree that should read as a grid. */
    SQUARE(Shapes.RECT),

    /** A circle. Good for a focal quest, poor for a dense tree. */
    CIRCLE(Shapes.CIRCLE),

    /** A diamond: points at the top and bottom. Reads as a focal or key node. */
    DIAMOND(Shapes.DIAMOND),

    /** A true flat-topped hexagon: a horizontal edge top and bottom, points at the sides. */
    HEXAGON(Shapes.HEXAGON),

    /** A regular octagon: equal straight chamfers on all four corners. Reads as cut stone. */
    OCTAGON(Shapes.OCTAGON),

    /** A regular pentagon, point up: five equal sides and a flat base. */
    PENTAGON(Shapes.PENTAGON),

    /** Eight wide-rooted trapezoidal teeth round a large hub. The busiest silhouette here. */
    GEAR(Shapes.GEAR),

    /** Two lobes and a point: the only shape whose rows have more than one span. */
    HEART(Shapes.HEART),

    /** A codex: a flat spine notched at head and tail, and a rounded fore-edge. */
    TOME(Shapes.TOME),

    /** A four-point star: tips at the cardinals, and curved sides pinching in between them. */
    STAR(Shapes.STAR),

    /**
     * No panel at all: the icon alone, on the canvas.
     *
     * <p>Its geometry is the square, so the icon's fit and the node's clickable area are the node's own
     * box — see the class note for why this one shape is allowed to break the click-equals-drawing rule.
     */
    NONE(Shapes.RECT);

    public static final Codec<QuestShape> CODEC = Codecs.enumByName(QuestShape.class);

    /**
     * How much of the node an icon is asked to fill, before the outline caps it.
     *
     * <p>Bounded in one place, because two places is how a number and its validation come to disagree.
     * {@code QuestLayout}'s codec and {@code QuestValidator} both read these, so there is no second copy
     * to drift — and they are re-exported from {@link Shape} rather than restated, since that is where
     * the fit is computed and therefore where the bounds belong.
     */
    public static final double MIN_ICON_SCALE = Shape.MIN_ICON_SCALE;

    /** The whole node, capped by the outline: corner to corner on a square, unchanged from what shipped. */
    public static final double MAX_ICON_SCALE = Shape.MAX_ICON_SCALE;

    private final Shape geometry;

    QuestShape(Shape geometry) {
        this.geometry = geometry;
    }

    /** The geometry this name refers to, for a caller that wants the shape rather than a span. */
    public Shape geometry() {
        return geometry;
    }

    /**
     * Whether this shape draws a panel.
     *
     * <p>False for {@link #NONE} only: a node with no outline, whose icon is the whole of it. The
     * drawing reads this to skip the panel, the hover ring and the state wash; the hit test does not,
     * because a node nobody can click is not a shape choice, it is a broken node.
     */
    public boolean drawsPanel() {
        return this != NONE;
    }

    /**
     * A shape by its JSON name, falling back rather than throwing.
     *
     * <p>For the client, which reads a name off the wire. A payload from a server running a newer version
     * can name a shape this client has never heard of, and the choice is between a node drawn as the
     * default and a screen that throws while a player is standing in front of it. The default is
     * obviously right, and it is a different decision from the validator's — there, an unknown name is an
     * error, because the author can still fix it.
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

    // ------------------------------------------------------------------
    // Delegation. Every one of these is the geometry's answer, not this class's.
    // ------------------------------------------------------------------

    /**
     * The horizontal extents of this shape on {@code row} of a {@code size}-pixel square.
     *
     * @return {@code {from, to, …}} in local coordinates, each {@code from} inclusive and {@code to}
     *     exclusive, or {@code null} when the row is outside the shape. Never empty, never outside the
     *     square, always sorted and never overlapping — all four are the interface's job, so no shape
     *     here can get them wrong. More than one pair is a row with more than one piece of material,
     *     which is a heart's notch and a gear's teeth.
     */
    public int[] spans(int row, int size) {
        return geometry.spans(row, size);
    }

    /** Whether a point is inside a node of this shape, in the node's own coordinates. */
    public boolean containsLocal(double localX, double localY, int size) {
        return geometry.containsLocal(localX, localY, size);
    }

    /** Whether a point is inside a node of this shape, given the node's corner. */
    public boolean contains(double px, double py, int x, int y, int size) {
        return geometry.contains(px, py, x, y, size);
    }

    /**
     * The smallest inset whose square lies wholly inside this shape — the largest icon that fits.
     *
     * <p>Derived from the spans rather than a constant, so it cannot disagree with what is drawn or
     * clickable. At 48 pixels the four original shapes want 4, 7, 6 and 5, and 7 for a circle is exactly
     * the inscribed square, {@code size/√2}, which is the arithmetic arriving at the answer a pencil would.
     */
    public int maxIconInset(int size) {
        return geometry.maxInset(size);
    }

    /** The same, with no floor. See {@code Shape.maxInset} for why the distinction matters at 2 pixels. */
    public int iconInset(int size) {
        return geometry.maxInset(size);
    }

    /**
     * The box an icon fills on a node — the inset applied to both the position and the size.
     *
     * <p>One method rather than two numbers at the call site, because the two numbers apart is a bug
     * that shipped: the size came from the inset and the position from a constant, so a 36-pixel item
     * was drawn 3 pixels in from the corner instead of 6 and sat up and left of centre with its corner
     * through the outline.
     *
     * @return {@code {x, y, box}} — the icon's corner and its width, all three from one inset
     */
    public int[] iconBox(int nodeX, int nodeY, int size) {
        return geometry.iconBox(nodeX, nodeY, size);
    }

    /** The same, at a fraction of the largest that fits. Clamped, since this is also reached off the wire. */
    public int[] iconBox(int nodeX, int nodeY, int size, double scale) {
        return geometry.iconBox(nodeX, nodeY, size, scale);
    }
}
