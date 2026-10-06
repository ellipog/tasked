package dev.ellipog.tasked.client.dev;

import dev.ellipog.tasked.quest.DependencyStyle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A dependency line as geometry: the route it takes, the pixels it fills, its arrowheads, and how far a
 * point is from it.
 *
 * <h2>One path, four readers</h2>
 *
 * <p>The drawing fills it, the hover brightens it, the right-click hit-tests it, and the edge-drag's
 * rubber line promises it â€” and all four ask {@link #path} for the same list, because a route the hit
 * test disagreed with would be a line you cannot click on the pixels it is drawn with. That is the rule
 * this whole class exists to keep, and it is why nothing here knows about Minecraft: the geometry is a
 * list of screen points, and the screen turns it into calls.
 *
 * <h2>Everything is rectangles, so a line is a region</h2>
 *
 * <p>The renderer has no line primitive — everything is a rectangle — so a line is drawn as the set of
 * pixels it covers. {@link #steps} walks a segment one pixel at a time along its dominant axis (the
 * Bresenham idea without the error term: at this scale the staircase is one pixel wide either way), and
 * {@link #fills} turns a route and a weight into that set: the stroke's region, filled a screen row at a
 * time, with the rhythm applied along the route and the tones across it. See {@link #stroke} for the
 * shape of it and for the three attempts that came first.
 */
public final class LineArt {

    /** A screen point. */
    public record Point(int x, int y) {
    }

    /** What a rectangle contributes to a weighted line's shading. */
    public enum Tone {
        /** The line's own ink. */
        MAIN,
        /** A conduit's darker border. */
        EDGE,
        /** A conduit's lighter core. */
        CORE
    }

    /** One rectangle to fill, half-open like the renderer's: {@code x1..x2}, {@code y1..y2}. */
    public record Fill(int x1, int y1, int x2, int y2, Tone tone) {

        /** The ordinary case: a rectangle of the line's own ink. */
        public Fill(int x1, int y1, int x2, int y2) {
            this(x1, y1, x2, y2, Tone.MAIN);
        }
    }

    /** A line a click could be on: what it is, and the route it takes. */
    public record Candidate<T>(T id, List<Point> path) {
    }

    /** How close a pointer must be, in pixels, for a click to count as being on a line. */
    public static final int TOLERANCE = 4;

    private static final int DASH_ON = 6;
    private static final int DASH_OFF = 5;

    /** A dot's ink and the gap after it, for the dotted rhythm. */
    private static final int DOTTED_ON = 1;
    private static final int DOTTED_PERIOD = 4;

    /** The two gaps of the dash-dot rhythm: dash, gap, dot, gap. */
    private static final int DASH_DOT_GAP = 3;

    /** Hazard hatch marks: how far apart, and how long. */
    private static final int HAZARD_STEP = 8;
    private static final int HAZARD_BARB = 5;

    // ------------------------------------------------------------------
    // The rhythm at a weight
    //
    // The pattern lengths are measured along the route, so a six-pixel line with the one-pixel line's
    // rhythm would have its beads or dashes touching: a dotted conduit would draw as a solid slab and a
    // dashed one as a smear. Each length therefore grows with the weight, by enough that the off part of
    // the rhythm is always wider than the ink -- and every one of them answers the bare constant at width
    // one, which is what keeps a thin line's drawing identical to what it has always been.
    // ------------------------------------------------------------------

    private static int dashOn(int width) {
        return Math.max(DASH_ON, width + 2);
    }

    private static int dashOff(int width) {
        return Math.max(DASH_OFF, width + 2);
    }

    private static int dotPeriod(int width) {
        return Math.max(DOTTED_PERIOD, width + 3);
    }

    private static int dashDotGap(int width) {
        return Math.max(DASH_DOT_GAP, width + 2);
    }

    /** How long a chamfered corner's 45-degree cut is, before it is clamped to the arms meeting it. */
    private static final int CHAMFER = 6;

    /** A cut shorter than this is left square: a one-pixel nick reads as a fault, not a bevel. */
    private static final int MIN_CHAMFER = 2;

    /** How far outside its own node an arrowhead's tip sits, so the head is not drawn under it. */
    private static final int ARROW_GAP = 3;

    /** A chevron's wing length: how far back from the tip its two strokes reach. */
    private static final int ARROW_LENGTH = 6;

    /** A triangle head: how long, and how wide at its tail. */
    private static final int TRIANGLE_LENGTH = 6;
    private static final int TRIANGLE_HALF = 2;

    /** A diamond head: how long, and how wide at its middle. */
    private static final int DIAMOND_LENGTH = 7;
    private static final int DIAMOND_HALF = 2;

    private LineArt() {
    }

    // ------------------------------------------------------------------
    // Routes
    // ------------------------------------------------------------------

    /**
     * The points a line of this form passes through, from centre to centre.
     *
     * <p>Centres rather than rims: the nodes are drawn after the lines, so a line that reaches the middle
     * of a node is a line whose last few pixels are hidden â€” which is cheaper and steadier than asking
     * every shape for the point where a given direction leaves it, and leaves no gap when a node's shape
     * is not a rectangle. The arrowheads are the part that must stop at the rim, and they are placed by
     * walking back along the path.
     */
    public static List<Point> path(DependencyStyle.Form form, Point from, Point to) {
        return path(form, from, to, 0.2);
    }

    /** The same, with the curve's bow as a fraction of its chord. Only {@code CURVED} reads it. */
    public static List<Point> path(DependencyStyle.Form form, Point from, Point to, double bend) {
        return switch (form) {
            case ORTHOGONAL -> orthogonal(from, to);
            case CHAMFERED -> bevel(orthogonal(from, to));
            case STRAIGHT -> steps(from, to);
            case CURVED -> curve(from, to, bend);
        };
    }

    /**
     * The three-segment step this canvas always drew: out vertically, across, in vertically.
     *
     * <p>Vertical-first because a quest chain runs left to right, and a short vertical stub reads as a
     * branch where a long horizontal run would cross a neighbour's space.
     */
    private static List<Point> orthogonal(Point from, Point to) {
        List<Point> points = new ArrayList<>();
        points.add(from);
        if (from.y() != to.y()) {
            int midY = from.y() + (to.y() - from.y()) / 2;
            points.add(new Point(from.x(), midY));
            points.add(new Point(to.x(), midY));
        }
        points.add(to);
        return points;
    }

    /**
     * A route with every right angle cut at 45 degrees: the circuit-trace look.
     *
     * <p>Each corner is replaced by two points a fixed length back along its arms, joined by a diagonal
     * â€” exactly 45 degrees, because the arms are axis-aligned and the cut is the same length on both.
     * The cut is clamped to half of the shortest arm meeting the corner, so two bevels can never eat past
     * each other and a short stub keeps a square corner; a cut below {@link #MIN_CHAMFER} is not a bevel
     * but a nick, and is left square.
     */
    public static List<Point> bevel(List<Point> corners) {
        if (corners.size() < 3) {
            return corners;
        }
        List<Point> out = new ArrayList<>();
        out.add(corners.get(0));
        for (int i = 1; i < corners.size() - 1; i++) {
            Point before = out.get(out.size() - 1);
            Point corner = corners.get(i);
            Point next = corners.get(i + 1);
            double incoming = Math.hypot(corner.x() - before.x(), corner.y() - before.y());
            double outgoing = Math.hypot(next.x() - corner.x(), next.y() - corner.y());
            int cut = (int) Math.floor(Math.min(CHAMFER, Math.min(incoming, outgoing) / 2));
            if (cut < MIN_CHAMFER) {
                out.add(corner);
                continue;
            }
            out.add(new Point(corner.x() - Integer.signum(corner.x() - before.x()) * cut,
                    corner.y() - Integer.signum(corner.y() - before.y()) * cut));
            out.add(new Point(corner.x() + Integer.signum(next.x() - corner.x()) * cut,
                    corner.y() + Integer.signum(next.y() - corner.y()) * cut));
        }
        out.add(corners.get(corners.size() - 1));
        return out;
    }

    /** The existing three-segment route as fills, for callers that only want the rectangles. */
    public static List<Fill> orthogonalFills(Point from, Point to) {
        return fills(path(DependencyStyle.Form.ORTHOGONAL, from, to), null, null);
    }

    /**
     * A straight line as one point per pixel, walked along its longer axis.
     *
     * <p>Deduplicated and always including the exact endpoints, so the path a hit test is measured
     * against begins and ends where the line is drawn to.
     */
    public static List<Point> steps(Point from, Point to) {
        int dx = to.x() - from.x();
        int dy = to.y() - from.y();
        int count = Math.max(Math.abs(dx), Math.abs(dy));
        List<Point> points = new ArrayList<>(count + 1);
        for (int i = 0; i <= count; i++) {
            int x = count == 0 ? from.x() : from.x() + Math.round((float) dx * i / count);
            int y = count == 0 ? from.y() : from.y() + Math.round((float) dy * i / count);
            points.add(new Point(x, y));
        }
        points.set(0, from);
        points.set(points.size() - 1, to);
        return dedupe(points);
    }

    /**
     * A smooth bow between two nodes.
     *
     * <p>A quadratic whose control point is the chord's midpoint pushed sideways by a fifth of the
     * chord's length â€” pushed the same way relative to the direction of travel, so two edges that run in
     * opposite directions bow to opposite sides and a pair of quests that depend on each other reads as
     * two lines rather than one. The bow is what makes a curved chapter's tangles readable; a curve that
     * stayed near the chord would just look like a badly drawn straight line.
     */
    public static List<Point> curve(Point from, Point to) {
        return curve(from, to, 0.2);
    }

    /**
     * The same, with the bow given as a fraction of the chord.
     *
     * <p>The curve's own midpoint ends up at <b>half</b> the control point's offset â€” the property the
     * drag handle rests on, because the handle is drawn at the curve's midpoint and {@link #bendAt} is
     * the exact inverse of this.
     */
    public static List<Point> curve(Point from, Point to, double bend) {
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double length = Math.hypot(dx, dy);
        if (length < 1) {
            return List.of(from, to);
        }
        // Perpendicular to the chord, scaled to a fifth of its length.
        double bow = length * bend;
        double cx = (from.x() + to.x()) / 2.0 - dy / length * bow;
        double cy = (from.y() + to.y()) / 2.0 + dx / length * bow;

        int samples = Math.max(2, (int) Math.ceil(length / 2.0));
        List<Point> points = new ArrayList<>(samples + 1);
        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;
            double u = 1 - t;
            points.add(new Point(
                    (int) Math.round(u * u * from.x() + 2 * u * t * cx + t * t * to.x()),
                    (int) Math.round(u * u * from.y() + 2 * u * t * cy + t * t * to.y())));
        }
        points.set(0, from);
        points.set(points.size() - 1, to);
        return dedupe(points);
    }

    /**
     * The rectangles that draw this path.
     *
     * <h2>The walk, and why the first version was wrong</h2>
     *
     * <p>The path's points are <b>corners</b>, not pixels â€” an orthogonal route is four points and three
     * long segments. The first version tried to merge runs over that sparse list, and its index
     * arithmetic emitted a zero-length run at every corner (a one-pixel dot) while advancing past the
     * segment that followed, so an orthogonal line came out as a stub, a dot and nothing else. A solid
     * curve came out dotted for the same reason from the other end: sampled every couple of pixels, each
     * two-pixel step drew one pixel.
     *
     * <p>So the path is expanded first â€” {@link #walk} turns every segment into one point per pixel â€”
     * and every reader of the pixels works from that. A corner is then just a point where the axis
     * changes, and the route is continuous at every slope.
     *
     * <p>{@code dash} null or solid fills the whole run; a broken pattern alternates along the
     * <b>walk</b>, so the rhythm follows the route rather than the axes and a dash never straddles a
     * corner as two half-dashes. {@code weight} null or thin is one pixel; every heavier weight is the
     * same route drawn as a band that wide, with a conduit's rows carrying {@link Tone}s so its borders
     * can be inked darker and its core lighter.
     */
    public static List<Fill> fills(List<Point> path, DependencyStyle.Weight weight, DependencyStyle.Dash dash) {
        if (path.size() < 2) {
            return List.of();
        }
        List<Point> walk = walk(path);
        if (walk.size() < 2) {
            return List.of();
        }
        DependencyStyle.Dash pattern = dash == null ? DependencyStyle.Dash.SOLID : dash;
        DependencyStyle.Weight ink = weight == null ? DependencyStyle.Weight.THIN : weight;
        List<Fill> out = new ArrayList<>();
        if (pattern == DependencyStyle.Dash.DOUBLE) {
            // Two hairlines rather than one thick one: that is the look, and it is why this pattern
            // ignores the weight axis. A single-pixel run, drawn twice a pixel either side of itself.
            List<Fill> single = new ArrayList<>();
            stroke(path, DependencyStyle.Dash.SOLID, DependencyStyle.Weight.THIN, single);
            for (Fill fill : single) {
                out.add(shifted(fill, -1));
                out.add(shifted(fill, 1));
            }
            return out;
        }
        stroke(path, pattern, ink, out);
        if (pattern == DependencyStyle.Dash.HAZARD) {
            barbs(path, ink, out);
        }
        return out;
    }

    /**
     * The ink of a stroke of this weight along a route: the stroke's own region, filled a row at a time.
     *
     * <h2>Why this is a scanline fill and not a stack of shifted rectangles</h2>
     *
     * <p>Because that is the whole of the "gaps and holes" report, and the first attempts at fixing it
     * show why the obvious shapes do not work. The original drew a one-pixel run and copied it sideways
     * — downward for a horizontal run, rightward for a vertical one, and <i>diagonally</i> for a
     * single-pixel sample — so two of those rules disagreed at every corner (the square the two arms
     * shared was missing a quadrant) and the third made a line's apparent thickness depend on its slope.
     * Offsetting each of the width's rows as its own polyline fixes the corner and still leaves holes:
     * on a 45-degree route two adjacent offsets round onto the same pixels, so the band carries a dotted
     * line of gaps down its own middle. **A thick diagonal is not the union of parallel thin ones.** And
     * a single mitered outline round the whole route fixes both and introduces a third fault: the inner
     * side of a corner, where the miter's rails cross, is left as a notch the width of the join.
     *
     * <p>So the stroke is the region every renderer strokes: <b>one rectangle per segment, plus a wedge
     * at each corner</b>, filled by rows. The rectangles cover the inner side of every turn (they
     * overlap there, which a union does not mind), the wedges cover the outer side where two segments'
     * ends would otherwise leave a gap, and the row fill is what makes a diagonal whole — the region is
     * filled as the set of pixels it contains, at whatever angle, rather than as a pile of thin lines.
     *
     * <h2>The caps, and why they move half a pixel</h2>
     *
     * <p>A walk point is a pixel <i>corner</i> in this coordinate system: the route's last point is the
     * top-left of the stroke's last pixel, so a band that simply stopped there would leave that pixel —
     * the one the route ends on — outside the fill. Half a pixel along the route makes the cap cover the
     * pixel it is the cap of, and keeps the edge square to the path rather than to an axis.
     *
     * <h2>The rhythm, and the tones</h2>
     *
     * <p>The mask is read by <b>route index</b> and one band is filled per "on" stretch, so every dash is
     * its own region with its own square caps and the gap between two dashes is a real gap. A pixel's
     * tone comes from where it sits across the width — which only a conduit can see, so the per-pixel
     * work that asks is skipped entirely for every other weight. At width one there is no width to fill
     * and the band is the walk itself, byte for byte what it always was.
     */
    private static void stroke(List<Point> route, DependencyStyle.Dash pattern,
                               DependencyStyle.Weight weight, List<Fill> out) {
        List<Point> walk = walk(route);
        if (walk.size() < 2) {
            return;
        }
        int width = weight.width();
        int index = 0;
        while (index < walk.size()) {
            if (!onAt(pattern, index, width)) {
                index++;
                continue;
            }
            int end = index;
            while (end < walk.size() - 1 && onAt(pattern, end + 1, width)) {
                end++;
            }
            band(walk, index, end, weight, width, out);
            index = end + 1;
        }
    }

    /** One pixel: how far a run reaches past its last station, so the last pixel is part of it. */
    private static final double PIXEL = 1.0;

    /**
     * One "on" stretch of the route — {@code walk[from..to]} — as the region it inks.
     *
     * <p>A run that begins on the last point is still a pixel of the line: without that case the final
     * pixel of a diagonal or a curve is not drawn, which is how a line ended one pixel short of the node
     * it points at. A run of a single point — one dot of a dotted line — is a bead: one pixel along the
     * route and the whole width across it, which is what the cap extension gives a one-point run.
     */
    private static void band(List<Point> walk, int from, int to, DependencyStyle.Weight weight,
                             int width, List<Fill> out) {
        if (width <= 1) {
            // A hairline has no width to fill: it is the run's own pixels, merged so a straight one is
            // still a single rectangle.
            mergeRows(walk.subList(from, to + 1), Tone.MAIN, out);
            return;
        }
        // The rails are half a width either side — the continuous band *is* the width, which is what makes
        // a diagonal's area its width times its length — and the coverage rule below is what turns that
        // into exactly `width` rows of pixels: a pixel's centre counts when it is at or past the near rail
        // and short of the far one. Closed at the near end because a cap's own pixel has its centre on
        // that edge; open at the far end because a band of an even width has a rail exactly on a centre
        // wherever it is placed, and counting that one twice is how a two-pixel line became three.
        double near = -width / 2.0;
        double far = width / 2.0;
        double half = (width - 1) / 2.0;
        List<List<double[]>> shapes = new ArrayList<>();

        // Stations are the walk points themselves — pixel corners — and a pixel's span along the route
        // runs from its corner to the next one. So the run reaches one pixel past its last station: the
        // last pixel of the route is part of the stroke, and a band that stopped at the final corner left
        // it out, which is what "a line ends one pixel short of the node it points at" looks like.
        List<double[]> stations = new ArrayList<>(to - from + 1);
        for (int i = from; i <= to; i++) {
            double[] point = { walk.get(i).x(), walk.get(i).y() };
            double[] along = direction(walk, i);
            double push = i == to ? PIXEL : 0.0;
            stations.add(along == null ? point
                    : new double[] { point[0] + along[0] * push, point[1] + along[1] * push });
        }

        if (stations.size() == 1) {
            // One dot of a dotted line: a bead one pixel long along the route and the whole width across
            // it. One pixel, not two: a pixel's own span runs from its corner to the next one, so the bead
            // is the station and the step after it — the same span every other run's last station reaches
            // by. Two pixels here is a dotted line whose beads touch, which is a solid line.
            double[] along = direction(walk, from);
            if (along != null) {
                double[] point = stations.get(0);
                double[] across = { -along[1], along[0] };
                double[] b = { point[0] + along[0] * PIXEL, point[1] + along[1] * PIXEL };
                shapes.add(List.of(corner(point, across, far), corner(point, across, near),
                        corner(b, across, near), corner(b, across, far)));
            }
        }
        else {
            for (int i = 0; i < stations.size() - 1; i++) {
                double[] normal = normal(stations.get(i), stations.get(i + 1));
                if (normal == null) {
                    continue;
                }
                double[] a = stations.get(i);
                double[] b = stations.get(i + 1);
                shapes.add(List.of(corner(a, normal, far), corner(a, normal, near),
                        corner(b, normal, near), corner(b, normal, far)));
            }
            for (int i = 1; i < stations.size() - 1; i++) {
                double[] before = normal(stations.get(i - 1), stations.get(i));
                double[] after = normal(stations.get(i), stations.get(i + 1));
                if (before == null || after == null || wedgeIsBlind(before, after, far)) {
                    continue;
                }
                // The wedge between the two segments' ends: the outer half of a miter, and the only part
                // of a turn the two rectangles do not already cover between them.
                double[] here = stations.get(i);
                shapes.add(List.of(corner(here, before, far), corner(here, after, far),
                        corner(here, before, near), corner(here, after, near)));
            }
        }

        fillRows(shapes, walk, from, to, width, half, weight, out);
    }

    /**
     * Whether the wedge at a corner is under a pixel wide, and so not worth filling.
     *
     * <p>The gap two segments leave grows as half the width times the tangent of half the turn: a right
     * angle leaves several pixels, a chamfer about one, and the degree or two between two points of a
     * curve leaves none at all. Without this a curve would pay for a wedge at every one of its hundred
     * points, every frame — and every one of those wedges would be empty of everything but rounding.
     */
    private static boolean wedgeIsBlind(double[] before, double[] after, double rail) {
        double dot = before[0] * after[0] + before[1] * after[1];
        double turn = Math.acos(Math.max(-1, Math.min(1, dot)));
        double tangent = Math.tan(Math.min(turn, Math.PI / 2) / 2);
        return rail * tangent < 1.0;
    }

    /**
     * Fills a set of convex shapes by rows, one span at a time, and buckets every pixel into its tone.
     *
     * <h2>The crossings come from an edge table, not from every edge and every row</h2>
     *
     * <p>Each edge is asked only about the rows its own two ends span, so the whole fill costs what the
     * ink costs. The alternative — testing every edge against every row — is O(points × rows), which for
     * one long diagonal is a quarter of a million tests per frame.
     *
     * <p>Crossings are paired <b>within one shape</b>, because even-odd across several shapes is not the
     * same question: two rectangles that touch would pair their crossings against each other and leave
     * the middle of the band empty. The union of the shapes is taken as a union of spans instead.
     */
    private static void fillRows(List<List<double[]>> shapes, List<Point> walk, int from, int to,
                                 int width, double half, DependencyStyle.Weight weight, List<Fill> out) {
        Map<Integer, List<double[]>> spans = new HashMap<>();
        for (List<double[]> shape : shapes) {
            Map<Integer, List<Double>> crossings = new HashMap<>();
            for (int i = 0; i < shape.size(); i++) {
                double[] a = shape.get(i);
                double[] b = shape.get((i + 1) % shape.size());
                if (a[1] == b[1]) {
                    continue;       // a horizontal edge crosses no row's centre
                }
                int first = (int) Math.ceil(Math.min(a[1], b[1]) - 0.5);
                int last = (int) Math.ceil(Math.max(a[1], b[1]) - 0.5) - 1;
                for (int y = first; y <= last; y++) {
                    double t = (y + 0.5 - a[1]) / (b[1] - a[1]);
                    crossings.computeIfAbsent(y, key -> new ArrayList<>(4))
                            .add(a[0] + (b[0] - a[0]) * t);
                }
            }
            for (Map.Entry<Integer, List<Double>> row : crossings.entrySet()) {
                List<Double> xs = row.getValue();
                java.util.Collections.sort(xs);
                for (int i = 0; i + 1 < xs.size(); i += 2) {
                    spans.computeIfAbsent(row.getKey(), key -> new ArrayList<>(4))
                            .add(new double[] { xs.get(i), xs.get(i + 1) });
                }
            }
        }

        boolean toned = false;
        for (int chip = 1; chip < width && !toned; chip++) {
            toned = toneOf(weight, chip) != toneOf(weight, 0);
        }
        // Where the route is, per row, for the tones: only a conduit asks, and only its own band's rows.
        Map<Integer, List<Integer>> stations = new HashMap<>();
        if (toned) {
            for (int i = from; i <= to; i++) {
                stations.computeIfAbsent(walk.get(i).y(), key -> new ArrayList<>(4)).add(i);
            }
        }
        List<List<Point>> chips = new ArrayList<>(width);
        for (int chip = 0; chip < width; chip++) {
            chips.add(new ArrayList<>());
        }
        for (Map.Entry<Integer, List<double[]>> row : spans.entrySet()) {
            int y = row.getKey();
            List<double[]> ordered = row.getValue();
            ordered.sort(java.util.Comparator.comparingDouble(span -> span[0]));
            double reach = Double.NEGATIVE_INFINITY;
            for (double[] span : ordered) {
                // Overlapping spans are one span: a pixel claimed by two shapes is still one pixel, and
                // bucketing it twice would ink it twice.
                //
                // The interval is closed at the near edge and open at the far one, which is the same
                // rule the crossings were gathered with. Closed at the near edge so the pixel a cap
                // covers is in; open at the far one so a band whose far rail lands exactly on a pixel
                // centre does not take that pixel as well and come out a pixel too wide.
                int start = (int) Math.ceil(Math.max(span[0], reach) - 0.5);
                int stop = (int) Math.ceil(span[1] - 0.5) - 1;
                for (int x = start; x <= stop; x++) {
                    chips.get(chipAt(walk, stations, toned, half, width, x, y)).add(new Point(x, y));
                }
                reach = Math.max(reach, span[1]);
            }
        }
        for (int chip = 0; chip < width; chip++) {
            mergeRows(chips.get(chip), toneOf(weight, chip), out);
        }
    }

    /** A shape's corner: a point moved {@code offset} along a normal. */
    private static double[] corner(double[] point, double[] normal, double offset) {
        return new double[] { point[0] + normal[0] * offset, point[1] + normal[1] * offset };
    }

    /** The unit normal of a step, or null for a step with no length. */
    private static double[] normal(double[] from, double[] to) {
        double dx = to[0] - from[0];
        double dy = to[1] - from[1];
        double length = Math.hypot(dx, dy);
        if (length < 0.001) {
            return null;
        }
        return new double[] { -dy / length, dx / length };
    }

    /**
     * The direction the route runs at one walk point: through its neighbours, so a single-point run (a
     * dot) still knows which way the line it belongs to is going.
     */
    private static double[] direction(List<Point> walk, int index) {
        Point next = walk.get(Math.min(walk.size() - 1, index + 1));
        Point previous = walk.get(Math.max(0, index - 1));
        double dx = next.x() - previous.x();
        double dy = next.y() - previous.y();
        double length = Math.hypot(dx, dy);
        if (length < 0.001) {
            return null;
        }
        return new double[] { dx / length, dy / length };
    }

    /**
     * Which chip of the width a pixel belongs to: 0 at one rail and {@code width - 1} at the other.
     *
     * <p>Answered from the pixel's signed position across the route, using the nearest station and that
     * station's own normal — the same rule the band's rectangles are built with, so the tones and the
     * shape cannot disagree about where across it a pixel is. Only a weight with more than one tone ever
     * asks, which is the conduit alone: for every other weight the one bucket is bucket zero.
     */
    private static int chipAt(List<Point> walk, Map<Integer, List<Integer>> stations, boolean toned,
                              double half, int width, int x, int y) {
        if (!toned) {
            return 0;
        }
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int row = y - 3; row <= y + 3; row++) {
            for (int index : stations.getOrDefault(row, List.of())) {
                Point point = walk.get(index);
                double distance = Math.hypot(point.x() - x, point.y() - y);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = index;
                }
            }
        }
        if (best < 0) {
            return (width - 1) / 2;
        }
        Point here = walk.get(best);
        Point next = walk.get(Math.min(walk.size() - 1, best + 1));
        Point previous = walk.get(Math.max(0, best - 1));
        double dx = next.x() - previous.x();
        double dy = next.y() - previous.y();
        double length = Math.hypot(dx, dy);
        if (length < 0.001) {
            return (width - 1) / 2;
        }
        double across = ((x - here.x()) * -dy + (y - here.y()) * dx) / length;
        int chip = (int) Math.round(across + half);
        return Math.max(0, Math.min(width - 1, chip));
    }



    /**
     * One rectangle per run of one screen row, then rows with the same columns joined.
     *
     * <p>The two merges are what keep a straight run one fill per chip however long it is â€” the
     * renderer's primitive is a rectangle, so a line's 500 pixels must not be 500 calls. A horizontal run
     * merges across the row; a vertical run is one pixel per row, so the rows are joined into one tall
     * rectangle; a diagonal's rows differ and stay one pixel each, which is how a staircase is drawn.
     *
     * <p>The merge is <b>local to one chip</b>, and that is not tidiness: joining across chips would fuse
     * a conduit's dark border to its core into a single rectangle and lose the tone boundary that makes
     * it read as a pipe.
     */
    private static void mergeRows(List<Point> pixels, Tone tone, List<Fill> out) {
        List<Point> sorted = new ArrayList<>(pixels);
        sorted.sort(java.util.Comparator.comparingInt(Point::y).thenComparingInt(Point::x));
        List<Fill> spans = new ArrayList<>();
        int index = 0;
        while (index < sorted.size()) {
            int y = sorted.get(index).y();
            int start = sorted.get(index).x();
            int end = start;
            index++;
            while (index < sorted.size() && sorted.get(index).y() == y
                    && sorted.get(index).x() <= end + 1) {
                end = Math.max(end, sorted.get(index).x());
                index++;
            }
            Fill span = new Fill(start, y, end + 1, y + 1, tone);
            Fill last = spans.isEmpty() ? null : spans.get(spans.size() - 1);
            if (last != null && last.x1() == span.x1() && last.x2() == span.x2()
                    && last.y2() == span.y1()) {
                // The same columns as the row above and touching it: one column of ink, not a stack.
                spans.set(spans.size() - 1,
                        new Fill(last.x1(), last.y1(), last.x2(), span.y2(), tone));
            }
            else {
                spans.add(span);
            }
        }
        out.addAll(spans);
    }

    /**
     * Whether a pattern's ink is on this far along the route.
     *
     * <p>The dots are one pixel with a gap â€” tight enough to read as a dotted line rather than as a
     * dashed one â€” and the dash-dot is the classic long-short rhythm: six on, three off, one on, three
     * off. Every length is grown by the weight, so the off part of the rhythm stays wider than the ink
     * and a six-pixel dotted line does not draw as a slab.
     */
    private static boolean onAt(DependencyStyle.Dash pattern, int index, int width) {
        return switch (pattern) {
            case SOLID, DOUBLE, HAZARD -> true;
            case DASHED -> index % (dashOn(width) + dashOff(width)) < dashOn(width);
            case DOTTED -> index % dotPeriod(width) < DOTTED_ON;
            case DASH_DOT -> {
                int on = dashOn(width);
                int gap = dashDotGap(width);
                int at = index % (on + gap + DOTTED_ON + gap);
                yield at < on || at == on + gap;
            }
        };
    }

    /**
     * The path with one point per pixel: every segment walked with {@link #steps}, each following
     * segment's first point dropped because it is the point before it.
     *
     * <p>Public because it is the shape a test can assert against â€” "every walk point is covered by
     * exactly one fill" is the statement that solid means solid, and it needs the walk to say so.
     */
    public static List<Point> walk(List<Point> path) {
        List<Point> out = new ArrayList<>();
        for (int i = 0; i < path.size() - 1; i++) {
            List<Point> segment = steps(path.get(i), path.get(i + 1));
            for (int j = out.isEmpty() ? 0 : 1; j < segment.size(); j++) {
                out.add(segment.get(j));
            }
        }
        return dedupe(out);
    }

    /**
     * The same rectangle one step further along the band's own direction.
     *
     * <p>Used by the {@code DOUBLE} pattern alone, which is the one drawing that is still a handful of
     * shifted rectangles: two hairlines either side of the route. The step follows the run's shape â€”
     * down for a horizontal run, right for a vertical one, diagonally for a single-pixel sample â€” which
     * is exactly the rule the trunk used to apply to every weight, and the reason corners had holes.
     */
    private static Fill shifted(Fill fill, int steps) {
        int dx = 0;
        int dy = 0;
        if (fill.y2() - fill.y1() > 1 && fill.x2() - fill.x1() == 1) {
            dx = steps;
        }
        else if (fill.x2() - fill.x1() > 1 && fill.y2() - fill.y1() == 1) {
            dy = steps;
        }
        else {
            dx = steps;
            dy = steps;
        }
        return new Fill(fill.x1() + dx, fill.y1() + dy, fill.x2() + dx, fill.y2() + dy, fill.tone());
    }

    /** The tone of the {@code i}th chip: a conduit is a dark border, a body and a light core. */
    private static Tone toneOf(DependencyStyle.Weight weight, int i) {
        if (weight != DependencyStyle.Weight.CONDUIT) {
            return Tone.MAIN;
        }
        return switch (i) {
            case 0, 5 -> Tone.EDGE;
            case 1, 4 -> Tone.MAIN;
            default -> Tone.CORE;
        };
    }

    /**
     * The hatch marks of a hazard route: a short stroke at 45 degrees to the line, every few pixels.
     *
     * <p>Sampled on the corner path rather than the walk, so each barb knows the route's real direction
     * instead of a one-pixel staircase's â€” and drawn at the line's own weight, with the spacing and the
     * barb grown by it, because six-pixel barbs every eight pixels on a six-pixel trunk is a slab with
     * notches rather than hatching.
     */
    private static void barbs(List<Point> path, DependencyStyle.Weight weight, List<Fill> out) {
        double total = length(path);
        int width = weight.width();
        int step = HAZARD_STEP + (width - 1);
        int barb = HAZARD_BARB + (width - 1);
        for (double at = step; at < total - barb; at += step) {
            Point point = pointAt(path, at);
            double angle = tangentAt(path, at) + Math.PI / 4;
            Point end = new Point((int) Math.round(point.x() + Math.cos(angle) * barb),
                    (int) Math.round(point.y() + Math.sin(angle) * barb));
            stroke(List.of(point, end), DependencyStyle.Dash.SOLID, weight, out);
        }
    }

    // ------------------------------------------------------------------
    // Arrowheads
    // ------------------------------------------------------------------

    /**
     * The heads on a path, as fills.
     *
     * <p>The glyph and where it sits are separate axes now. A chevron is two walked strokes, a triangle
     * or a diamond is a filled wedge, and a dot is a bead; any of them can be posted at the target, at
     * both ends, once at the middle, or as a stream along the route. {@code fromHalf}/{@code toHalf} are
     * how far back from the final point the tip is placed â€” the head belongs at the node's rim, and the
     * node is drawn over the line, so a head left at the centre would simply be hidden.
     *
     * <p><b>The weight is the line's, and the head is drawn at it.</b> A glyph is not an ornament
     * independent of what it caps: a six-pixel conduit ending in a one-pixel chevron reads as a broken
     * line, and a triangle whose tail is narrower than its own trunk reads as a kink. So the head's
     * length, its half-width at the tail, its stroke and the standoff that keeps it off its node all
     * derive from the weight â€” and at {@link DependencyStyle.Weight#THIN} every one of them is the
     * number it always was, which is what keeps a hairline's arrows identical.
     */
    public static List<Fill> arrows(List<Point> path, DependencyStyle.ArrowHead head,
                                    DependencyStyle.ArrowPlace place, int spacing, int fromHalf,
                                    int toHalf) {
        return arrows(path, head, place, spacing, fromHalf, toHalf, DependencyStyle.Weight.THIN);
    }

    /** The same, with the head drawn at a weight. See the six-argument form for the whole story. */
    public static List<Fill> arrows(List<Point> path, DependencyStyle.ArrowHead head,
                                    DependencyStyle.ArrowPlace place, int spacing, int fromHalf,
                                    int toHalf, DependencyStyle.Weight weight) {
        if (head == null || head == DependencyStyle.ArrowHead.NONE || place == null || path.size() < 2) {
            return List.of();
        }
        DependencyStyle.Weight ink = weight == null ? DependencyStyle.Weight.THIN : weight;
        int width = ink.width();
        double total = length(path);
        // Each end uses **its own** node's half-size: one shared figure pushed a small node's head away
        // by its neighbour's bulk, which is how an arrow ended up floating in the middle of a line. The
        // weight's half joins it, because a six-pixel trunk reaches three pixels further out than a
        // hairline does and a head placed for the hairline would start under its own line.
        double standoff = ARROW_GAP + (width - 1) / 2.0;
        double arrival = toHalf + standoff;
        double departure = fromHalf + standoff;
        List<Fill> out = new ArrayList<>();
        boolean roomForArrival = total > arrival + reach(head, ink);
        boolean roomForDeparture = total > departure + reach(head, ink);
        if (place == DependencyStyle.ArrowPlace.STREAM) {
            double every = Math.max(Math.max(spacing, 1), reach(head, ink) * 3);
            if (!roomForArrival) {
                return out;
            }
            // The heads' distances, in the order they were always emitted: outward from the departure
            // end, then the arrival head. The old loop asked `pointAt` and `tangentAt` per chevron, and
            // each of those walks the whole path -- O(k*P) for a long line at this setting. Sampling
            // every distance in one walk is the same arithmetic per sample, run once.
            List<Double> backs = new ArrayList<>();
            for (double at = departure + every; at < total - arrival; at += every) {
                backs.add(total - at);
            }
            backs.add(arrival);
            emitHeads(path, backs, head, ink, out);
            return out;
        }
        if (place == DependencyStyle.ArrowPlace.MID) {
            // One head dead-centre, pointing the way the route runs there -- and nothing at the ends,
            // because "which way does this line run" is the whole of what the middle is saying.
            double at = total / 2;
            headAt(pointAt(path, at), tangentAt(path, at), head, ink, out);
            return out;
        }
        // A head that cannot fit outside its node is left off rather than drawn inside it: a short edge
        // with no arrow is honest, and a head buried in a node is the fault this placement exists for.
        if (roomForArrival) {
            head(path, arrival, false, head, ink, out);
        }
        if (place == DependencyStyle.ArrowPlace.BOTH && roomForDeparture) {
            head(path, departure, true, head, ink, out);
        }
        return out;
    }

    /**
     * The same heads, for a caller still holding the legacy axis â€” an old file, or an old server.
     *
     * <p>{@code many} keeps its old 24-pixel rhythm rather than jumping to the medium density: the
     * density axis was added to give authors a choice, not to redraw packs that had already made one.
     * The weight is THIN because this axis predates it: a pack that never named a weight has a hairline
     * line, and its arrows are hairlines too.
     */
    public static List<Fill> arrows(List<Point> path, DependencyStyle.Arrows arrows, int fromHalf,
                                    int toHalf) {
        if (arrows == null) {
            return List.of();
        }
        int spacing = arrows == DependencyStyle.Arrows.MANY
                ? DependencyStyle.LEGACY_STREAM_SPACING : DependencyStyle.ArrowDensity.MEDIUM.spacing();
        return arrows(path, DependencyStyle.headOf(arrows), DependencyStyle.placeOf(arrows), spacing,
                fromHalf, toHalf, DependencyStyle.Weight.THIN);
    }

    /** How far forward a glyph reaches from its tip, for the room check that keeps heads off nodes. */
    private static double reach(DependencyStyle.ArrowHead head, DependencyStyle.Weight weight) {
        int extra = weight.width() - 1;
        return switch (head) {
            case CHEVRON -> ARROW_LENGTH + extra;
            case TRIANGLE -> TRIANGLE_LENGTH + extra;
            case DIAMOND -> DIAMOND_LENGTH + extra;
            case DOT -> 2 + extra;
            case NONE -> 0;
        };
    }

    /**
     * Heads at several distances along a path, sampled in one walk and emitted in the given order.
     *
     * <p>{@code backs} are distances from the path's end, all arrival-facing â€” the shape a stream uses.
     * Emission order is the caller's list order rather than the sampling order, because the fills a
     * caller gets back are part of what it draws, and a reordered list would be a changed drawing even
     * where the pixels coincide.
     */
    private static void emitHeads(List<Point> path, List<Double> backs, DependencyStyle.ArrowHead head,
                                  DependencyStyle.Weight weight, List<Fill> out) {
        double[] distances = new double[backs.size()];
        double total = length(path);
        for (int i = 0; i < backs.size(); i++) {
            distances[i] = total - backs.get(i);
        }
        // Sampled in ascending distance so the walk advances once; emitted in the caller's order.
        Integer[] order = new Integer[distances.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, java.util.Comparator.comparingDouble(i -> distances[i]));
        double[] sorted = new double[distances.length];
        for (int i = 0; i < order.length; i++) {
            sorted[i] = distances[order[i]];
        }
        List<Point> tips = pointsAlong(path, sorted);
        List<Double> angles = tangentsAlong(path, sorted);
        Point[] tipOf = new Point[distances.length];
        double[] angleOf = new double[distances.length];
        for (int i = 0; i < order.length; i++) {
            tipOf[order[i]] = tips.get(i);
            angleOf[order[i]] = angles.get(i);
        }
        for (int i = 0; i < distances.length; i++) {
            headAt(tipOf[i], angleOf[i], head, weight, out);
        }
    }

    /**
     * The points at several distances, in one walk â€” the same arithmetic as {@link #pointAt} per
     * sample, with the distances ascending.
     */
    private static List<Point> pointsAlong(List<Point> path, double[] distances) {
        List<Point> out = new ArrayList<>(distances.length);
        if (path.isEmpty()) {
            for (int i = 0; i < distances.length; i++) {
                out.add(new Point(0, 0));
            }
            return out;
        }
        int next = 0;
        double walked = 0;
        for (int i = 0; i < path.size() - 1 && next < distances.length; i++) {
            Point a = path.get(i);
            Point b = path.get(i + 1);
            double step = Math.hypot(b.x() - a.x(), b.y() - a.y());
            while (next < distances.length && walked + step >= distances[next]) {
                double t = step == 0 ? 0 : (distances[next] - walked) / step;
                out.add(new Point((int) Math.round(a.x() + (b.x() - a.x()) * t),
                        (int) Math.round(a.y() + (b.y() - a.y()) * t)));
                next++;
            }
            walked += step;
        }
        // Past the end clamps to the last point, exactly as pointAt does.
        while (next < distances.length) {
            out.add(path.get(path.size() - 1));
            next++;
        }
        return out;
    }

    /**
     * The tangents at several distances, in one walk â€” the same arithmetic as {@link #tangentAt} per
     * sample, including its two rules: a segment shorter than a thousandth is skipped, and the last
     * non-empty segment answers for anything at or past the end.
     */
    private static List<Double> tangentsAlong(List<Point> path, double[] distances) {
        List<Double> out = new ArrayList<>(distances.length);
        if (path.size() < 2) {
            for (int i = 0; i < distances.length; i++) {
                out.add(0.0);
            }
            return out;
        }
        int next = 0;
        double walked = 0;
        for (int i = 0; i < path.size() - 1 && next < distances.length; i++) {
            Point a = path.get(i);
            Point b = path.get(i + 1);
            double step = Math.hypot(b.x() - a.x(), b.y() - a.y());
            if (step < 0.001) {
                continue;
            }
            while (next < distances.length
                    && (walked + step >= distances[next] || i == path.size() - 2)) {
                out.add(Math.atan2(b.y() - a.y(), b.x() - a.x()));
                next++;
            }
            walked += step;
        }
        while (next < distances.length) {
            out.add(0.0);
            next++;
        }
        return out;
    }

    /**
     * One head at {@code back} pixels from the end named by {@code atStart}.
     *
     * <p>Walked along the path so it points the way the line runs at that point, which is what makes an
     * arrow on an orthogonal route turn the corner correctly rather than pointing along the axes.
     */
    private static void head(List<Point> path, double back, boolean atStart, DependencyStyle.ArrowHead head,
                             DependencyStyle.Weight weight, List<Fill> out) {
        double total = length(path);
        double from = atStart ? back : total - back;
        if (from < 0 || from > total) {
            return;
        }
        Point tip = pointAt(path, from);
        // The path runs source to target, so the arrival head points along it and the departure head
        // points back at its own node.
        double angle = tangentAt(path, from) + (atStart ? Math.PI : 0);
        headAt(tip, angle, head, weight, out);
    }

    /** One head whose tip and direction are already known: the glyph's own shape, at a weight. */
    private static void headAt(Point tip, double angle, DependencyStyle.ArrowHead head,
                               DependencyStyle.Weight weight, List<Fill> out) {
        int extra = weight.width() - 1;
        switch (head) {
            case CHEVRON -> chevronAt(tip, angle, weight, out);
            case TRIANGLE -> wedgeAt(tip, angle, TRIANGLE_LENGTH + extra,
                    TRIANGLE_HALF + extra / 2, out);
            case DIAMOND -> diamondAt(tip, angle, DIAMOND_LENGTH + extra, DIAMOND_HALF + extra / 2, out);
            case DOT -> dotAt(tip, weight.width(), out);
            case NONE -> {
            }
        }
    }

    /**
     * One chevron whose tip and direction are already known: two wings, stroked at the line's weight.
     *
     * <p>Stroked rather than walked dot by dot, because a wing on a conduit has to be as thick as the
     * trunk it caps â€” two one-pixel scratches under a six-pixel line is what the head used to be, and
     * the report was "the arrow looks like it belongs to another line".
     */
    private static void chevronAt(Point tip, double angle, DependencyStyle.Weight weight, List<Fill> out) {
        for (int side = -1; side <= 1; side += 2) {
            double wing = angle + Math.PI + side * 0.5;
            Point wingEnd = new Point((int) Math.round(tip.x() + Math.cos(wing) * ARROW_LENGTH),
                    (int) Math.round(tip.y() + Math.sin(wing) * ARROW_LENGTH));
            // A walked stroke, not rounded dots: a wing is continuous at every angle instead of a dotted
            // line on the diagonals.
            stroke(List.of(tip, wingEnd), DependencyStyle.Dash.SOLID, weight, out);
        }
    }

    /**
     * A filled wedge, rows across the direction of travel: zero width at the tip, widest at the tail.
     *
     * <p>What a triangle head is at this scale; the rows are single pixels, because the renderer's
     * primitive is a rectangle and one pixel per column is the only way to keep an angled edge crisp.
     * Both the length and the half-width are the weight's, so a conduit's tail is wider than its trunk
     * and the two meet instead of the head looking pinched.
     */
    private static void wedgeAt(Point tip, double angle, int length, int half, List<Fill> out) {
        double ux = Math.cos(angle);
        double uy = Math.sin(angle);
        double px = -uy;
        double py = ux;
        for (int i = 0; i < length; i++) {
            int spread = Math.round(half * (float) i / (length - 1));
            rowAt(tip, ux, uy, px, py, i, spread, out);
        }
    }

    /** A rhombus head: two wedges back to back, widest in the middle. */
    private static void diamondAt(Point tip, double angle, int length, int half, List<Fill> out) {
        double ux = Math.cos(angle);
        double uy = Math.sin(angle);
        double px = -uy;
        double py = ux;
        for (int i = 0; i < length; i++) {
            int fromTip = Math.min(i, length - 1 - i);
            int spread = Math.round(half * (float) fromTip / (length / 2));
            rowAt(tip, ux, uy, px, py, i, spread, out);
        }
    }

    /** One row of a filled head: {@code i} pixels back from the tip, {@code spread} either side. */
    private static void rowAt(Point tip, double ux, double uy, double px, double py, int i, int spread,
                              List<Fill> out) {
        int cx = (int) Math.round(tip.x() - ux * i);
        int cy = (int) Math.round(tip.y() - uy * i);
        for (int s = -spread; s <= spread; s++) {
            int x = (int) Math.round(cx + px * s);
            int y = (int) Math.round(cy + py * s);
            out.add(new Fill(x, y, x + 1, y + 1));
        }
    }

    /**
     * A bead: five arms in a plus, each as thick as the line.
     *
     * <p>At one pixel that is exactly the five pixels this has always drawn â€” the middle and its four
     * neighbours â€” and it is the reason this is written as arms rather than as a filled disc: a conduit
     * capped with a one-pixel pinhead, or with a seven-pixel blob, both read as a fault rather than as a
     * "any one of these" marker.
     */
    private static void dotAt(Point tip, int width, List<Fill> out) {
        int arm = width / 2;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx != 0 && dy != 0) {
                    continue;       // a plus, not a square
                }
                int x = tip.x() + dx * (arm + 1) - arm;
                int y = tip.y() + dy * (arm + 1) - arm;
                out.add(new Fill(x, y, x + width, y + width));
            }
        }
    }

    // ------------------------------------------------------------------
    // Measuring
    // ------------------------------------------------------------------

    /** How far a point is from the nearest point of a path, in pixels. */
    public static double distance(List<Point> path, double x, double y) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < path.size() - 1; i++) {
            best = Math.min(best, segmentDistance(path.get(i), path.get(i + 1), x, y));
        }
        if (path.size() == 1) {
            best = Math.hypot(path.get(0).x() - x, path.get(0).y() - y);
        }
        return best;
    }

    /**
     * The nearest candidate within {@code tolerance}, or null.
     *
     * <p>Nearest wins rather than first-found, because a canvas draws lines that cross and a hit test
     * that answered with whichever happened to be checked first would make the crossing point a lottery.
     */
    public static <T> T nearest(List<Candidate<T>> candidates, double x, double y, int tolerance) {
        T best = null;
        double bestDistance = tolerance;
        for (Candidate<T> candidate : candidates) {
            double distance = distance(candidate.path(), x, y);
            if (distance <= bestDistance && (best == null || distance < bestDistance)) {
                best = candidate.id();
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * The nearest distance from a point to any of several paths â€” the hover's own reach when a line's
     * ink and the leashes out to its handles are separate arms.
     *
     * <p>{@code MAX_VALUE} for no arms at all, which is out of reach of everything.
     */
    public static double distanceToAny(List<List<Point>> paths, double x, double y) {
        double best = Double.MAX_VALUE;
        for (List<Point> path : paths) {
            best = Math.min(best, distance(path, x, y));
        }
        return best;
    }

    /** The point a given distance along the path, clamped to its ends. */
    public static Point pointAt(List<Point> path, double at) {
        if (path.isEmpty()) {
            return new Point(0, 0);
        }
        double walked = 0;
        for (int i = 0; i < path.size() - 1; i++) {
            Point a = path.get(i);
            Point b = path.get(i + 1);
            double step = Math.hypot(b.x() - a.x(), b.y() - a.y());
            if (walked + step >= at) {
                double t = step == 0 ? 0 : (at - walked) / step;
                return new Point((int) Math.round(a.x() + (b.x() - a.x()) * t),
                        (int) Math.round(a.y() + (b.y() - a.y()) * t));
            }
            walked += step;
        }
        return path.get(path.size() - 1);
    }

    /**
     * The bow that would put the curve's midpoint under this pointer: the inverse of {@link #curve}.
     *
     * <p>Signed by the side of the chord, so dragging to the other side flips the bow rather than
     * clamping at zero â€” the same sign {@code curve} reads, which is what makes the round trip exact.
     */
    public static double bendAt(Point from, Point to, double x, double y) {
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double length = Math.hypot(dx, dy);
        if (length < 1) {
            return 0;
        }
        double offset = ((x - from.x()) * -dy + (y - from.y()) * dx) / length;
        return 2 * offset / length;
    }

    /**
     * A bend the drag will accept: clamped to {@link DependencyStyle#MAX_BEND}, and snapped to exactly
     * zero inside a small dead zone.
     *
     * <p>The dead zone is the whole point of the snap: "straighten this curve" is a gesture somebody
     * performs by eye, and a hand that lands on 0.02 leaves a line that is neither bent nor straight.
     */
    public static double limitBend(double bend) {
        if (Math.abs(bend) < 0.05) {
            return 0;
        }
        return Math.max(-DependencyStyle.MAX_BEND, Math.min(DependencyStyle.MAX_BEND, bend));
    }

    /**
     * Where a line meets a node's own outline, walking outward along {@code towards}.
     *
     * <p>Uses the shape's own containment test rather than a circle of the node's half-size: a hexagon's
     * rim is a hexagon's and a square's is a square's, and a square's diagonal rim is nearly half as far
     * out again as the circle that fits inside it.
     */
    public static Point rimPoint(Point centre, Point towards, int size,
                                 java.util.function.BiPredicate<Integer, Integer> contains) {
        double dx = towards.x() - centre.x();
        double dy = towards.y() - centre.y();
        double length = Math.hypot(dx, dy);
        if (length < 0.5) {
            return centre;
        }
        double unitX = dx / length;
        double unitY = dy / length;
        Point last = centre;
        // The **position** is rounded, not the step. Rounding the step's two components independently
        // snapped the walk to eight directions -- a 20-degree direction became horizontal, 30 became 45 --
        // so the rim was found up to 22.5 degrees away from the one asked for, and on a small node that
        // error is a visible fraction of the node. `size` steps rather than `size / 2`, because a square's
        // diagonal rim is 0.71 * size from the centre: the shorter limit stopped inside the shape.
        for (int step = 1; step <= size; step++) {
            int x = (int) Math.round(centre.x() + unitX * step);
            int y = (int) Math.round(centre.y() + unitY * step);
            if (!contains.test(x, y)) {
                break;
            }
            last = new Point(x, y);
        }
        return last;
    }

    /** An explicit anchor: the point on the rim at this angle, 0 degrees east and growing clockwise. */
    public static Point anchorPoint(Point centre, int size, double degrees) {
        double radians = Math.toRadians(degrees);
        return new Point(
                (int) Math.round(centre.x() + Math.cos(radians) * size / 2.0),
                (int) Math.round(centre.y() + Math.sin(radians) * size / 2.0));
    }

    /** The angle of a pointer around a node: the inverse of {@link #anchorPoint}, for the drag. */
    public static double anchorAngle(Point centre, double x, double y) {
        return Math.toDegrees(Math.atan2(y - centre.y(), x - centre.x()));
    }

    /**
     * The direction the path runs at a point a given distance along it: the **segment's own direction**.
     *
     * <h2>Why not two points a few pixels apart</h2>
     *
     * <p>Because that is what a chevron used to do, and `pointAt` clamps at the path's ends: an arrival
     * head three pixels off a rim could sample the same point twice, and `atan2(0, 0)` is zero -- a
     * chevron drawn due east on a line running north. On a tight arc the short baseline also jittered
     * between pixel quantisations. The segment's direction is exact at every position, never degenerate
     * while the path has length, and does not care how sharp the curve is.
     */
    public static double tangentAt(List<Point> path, double at) {
        if (path.size() < 2) {
            return 0;
        }
        double walked = 0;
        for (int i = 0; i < path.size() - 1; i++) {
            Point a = path.get(i);
            Point b = path.get(i + 1);
            double step = Math.hypot(b.x() - a.x(), b.y() - a.y());
            if (step < 0.001) {
                continue;
            }
            // The last non-empty segment answers for anything at or past the end, which is where a
            // clamped `pointAt` would sit and is how the arrival head gets a real direction.
            if (walked + step >= at || i == path.size() - 2) {
                return Math.atan2(b.y() - a.y(), b.x() - a.x());
            }
            walked += step;
        }
        return 0;
    }

    /**
     * A cubic through two control points given in the chord's own frame: `[along, across]`, fractions of
     * the chord from the source and of its length perpendicular, signed. That is the frame the file
     * stores, so a split curve survives the nodes moving, resizing or another zoom.
     */
    public static List<Point> cubic(Point from, Point to, List<Double> fromHandle, List<Double> toHandle) {
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double length = Math.hypot(dx, dy);
        if (length < 1) {
            return List.of(from, to);
        }
        double ux = dx / length;
        double uy = dy / length;
        Point c1 = handlePoint(from, ux, uy, length, fromHandle);
        Point c2 = handlePoint(from, ux, uy, length, toHandle);
        int samples = Math.max(2, (int) Math.ceil(length / 2.0));
        List<Point> points = new java.util.ArrayList<>(samples + 1);
        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;
            double u = 1 - t;
            points.add(new Point(
                    (int) Math.round(u * u * u * from.x() + 3 * u * u * t * c1.x()
                            + 3 * u * t * t * c2.x() + t * t * t * to.x()),
                    (int) Math.round(u * u * u * from.y() + 3 * u * u * t * c1.y()
                            + 3 * u * t * t * c2.y() + t * t * t * to.y())));
        }
        points.set(0, from);
        points.set(points.size() - 1, to);
        return dedupe(points);
    }

    /** Where a `[along, across]` control point is on screen, given the chord's unit direction. */
    public static Point handlePoint(Point from, double ux, double uy, double length, List<Double> handle) {
        double along = handle.get(0) * length;
        double across = handle.get(1) * length;
        return new Point(
                (int) Math.round(from.x() + ux * along - uy * across),
                (int) Math.round(from.y() + uy * along + ux * across));
    }

    /**
     * The two control points that reproduce a quadratic bow as a cubic.
     *
     * <p>What "Split handles" writes, so the shape does not jump when the line becomes a cubic: at
     * `(1/3, 2b/3)` and `(2/3, 2b/3)` the cubic passes through the same middle point the bow does.
     */
    public static List<Double> equivalentFromHandle(double bend) {
        return List.of(1.0 / 3, 2 * bend / 3);
    }

    /** The target end's control point for a quadratic bow; see {@link #equivalentFromHandle}. */
    public static List<Double> equivalentToHandle(double bend) {
        return List.of(2.0 / 3, 2 * bend / 3);
    }

    /**
     * A pointer as `[along, across]` in the chord's frame â€” the exact inverse of {@link #handlePoint},
     * which is what makes a free control-point drag land where the hand is.
     */
    public static List<Double> chordFraction(Point from, Point to, double x, double y) {
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double length = Math.hypot(dx, dy);
        if (length < 1) {
            return List.of(0.0, 0.0);
        }
        double ux = dx / length;
        double uy = dy / length;
        double px = x - from.x();
        double py = y - from.y();
        return List.of((px * ux + py * uy) / length, (-px * uy + py * ux) / length);
    }

    /** How long a path is, in pixels. */
    public static double length(List<Point> path) {
        double total = 0;
        for (int i = 0; i < path.size() - 1; i++) {
            total += Math.hypot(path.get(i + 1).x() - path.get(i).x(),
                    path.get(i + 1).y() - path.get(i).y());
        }
        return total;
    }

    private static double segmentDistance(Point a, Point b, double x, double y) {
        double dx = b.x() - a.x();
        double dy = b.y() - a.y();
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared == 0) {
            return Math.hypot(a.x() - x, a.y() - y);
        }
        double t = Math.max(0, Math.min(1, ((x - a.x()) * dx + (y - a.y()) * dy) / lengthSquared));
        return Math.hypot(a.x() + t * dx - x, a.y() + t * dy - y);
    }

    private static List<Point> dedupe(List<Point> points) {
        List<Point> out = new ArrayList<>(points.size());
        for (Point point : points) {
            if (out.isEmpty() || !out.get(out.size() - 1).equals(point)) {
                out.add(point);
            }
        }
        return out;
    }
}
