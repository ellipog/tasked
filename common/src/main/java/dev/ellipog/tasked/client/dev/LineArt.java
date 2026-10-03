package dev.ellipog.tasked.client.dev;

import dev.ellipog.tasked.quest.DependencyStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * A dependency line as geometry: the route it takes, the pixels it fills, its arrowheads, and how far a
 * point is from it.
 *
 * <h2>One path, four readers</h2>
 *
 * <p>The drawing fills it, the hover brightens it, the right-click hit-tests it, and the edge-drag's
 * rubber line promises it — and all four ask {@link #path} for the same list, because a route the hit
 * test disagreed with would be a line you cannot click on the pixels it is drawn with. That is the rule
 * this whole class exists to keep, and it is why nothing here knows about Minecraft: the geometry is a
 * list of screen points, and the screen turns it into calls.
 *
 * <h2>Why diagonal lines are walked rather than filled</h2>
 *
 * <p>The renderer has no line primitive — everything is a rectangle — so a diagonal or a curve is a
 * sequence of small fills. {@link #steps} walks a segment one pixel at a time along its dominant axis
 * (the Bresenham idea without the error term: at this scale the staircase is one pixel wide either way),
 * and {@link #fills} merges runs of collinear steps back into single rectangles so a straight horizontal
 * line stays one call.
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
     * of a node is a line whose last few pixels are hidden — which is cheaper and steadier than asking
     * every shape for the point where a given direction leaves it, and leaves no gap when a node's shape
     * is not a rectangle. The arrowheads are the part that must stop at the rim, and they are placed by
     * walking back along the path.
     */
    public static List<Point> path(DependencyStyle.Form form, Point from, Point to) {
        return path(form, from, to, 0.2);
    }

    /** The same, with the curve's bow as a fraction of its chord. Only {@code CURVED} and {@code RADIAL} read it. */
    public static List<Point> path(DependencyStyle.Form form, Point from, Point to, double bend) {
        return switch (form) {
            case ORTHOGONAL -> orthogonal(from, to);
            case CHAMFERED -> bevel(orthogonal(from, to));
            case STRAIGHT -> steps(from, to);
            case STEPPED -> stepped(from, to);
            case CURVED -> curve(from, to, bend);
            case RADIAL -> radial(from, to, bend);
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
     * Out horizontally, one vertical step, in horizontally — the Z broken at the chord's midpoint.
     *
     * <p>What "Stepped" means here: the vertical jog happens at the midpoint of the span rather than at
     * each end, so a column-to-column flow reads as one clean break. {@link #orthogonal} is the same
     * shape transposed — vertical stubs at the ends, the long run between them — and which one looks
     * tidier is a fact about the chapter's own layout.
     */
    private static List<Point> stepped(Point from, Point to) {
        List<Point> points = new ArrayList<>();
        points.add(from);
        // Both offsets have to exist or there is no Z at all: a line already on the target's row or
        // column would otherwise grow a zero-length jog and two duplicate points.
        if (from.x() != to.x() && from.y() != to.y()) {
            int midX = from.x() + (to.x() - from.x()) / 2;
            points.add(new Point(midX, from.y()));
            points.add(new Point(midX, to.y()));
        }
        points.add(to);
        return points;
    }

    /**
     * A route with every right angle cut at 45 degrees: the circuit-trace look.
     *
     * <p>Each corner is replaced by two points a fixed length back along its arms, joined by a diagonal
     * — exactly 45 degrees, because the arms are axis-aligned and the cut is the same length on both.
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
     * chord's length — pushed the same way relative to the direction of travel, so two edges that run in
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
     * <p>The curve's own midpoint ends up at <b>half</b> the control point's offset — the property the
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
     * A true circular arc, bowed by the same bend fraction a curve reads.
     *
     * <p>The arc's middle lands where the quadratic's does — {@code bend * chord / 2} off the chord — so
     * switching a line between curved and radial does not jump, and {@link #bendAt} stays the exact
     * inverse of both. The centre sits on the chord's perpendicular bisector on the far side of the bow,
     * at the radius that passes through both ends and that middle point. At the bend limit the arc is
     * still under half a circle, which is why the minor arc is always the one drawn.
     */
    public static List<Point> radial(Point from, Point to, double bend) {
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double length = Math.hypot(dx, dy);
        if (length < 1 || Math.abs(bend) < 1e-6) {
            return List.of(from, to);
        }
        double sagitta = bend * length / 2;
        double half = length / 2;
        double radius = (half * half + sagitta * sagitta) / (2 * Math.abs(sagitta));
        double offset = radius - Math.abs(sagitta);
        double nx = -dy / length;
        double ny = dx / length;
        double side = Math.signum(sagitta);
        double centreX = (from.x() + to.x()) / 2.0 - nx * side * offset;
        double centreY = (from.y() + to.y()) / 2.0 - ny * side * offset;
        double startAngle = Math.atan2(from.y() - centreY, from.x() - centreX);
        double endAngle = Math.atan2(to.y() - centreY, to.x() - centreX);
        double sweep = endAngle - startAngle;
        while (sweep > Math.PI) {
            sweep -= 2 * Math.PI;
        }
        while (sweep < -Math.PI) {
            sweep += 2 * Math.PI;
        }
        double arc = radius * Math.abs(sweep);
        int samples = Math.max(2, (int) Math.ceil(arc / 2.0));
        List<Point> points = new ArrayList<>(samples + 1);
        for (int i = 0; i <= samples; i++) {
            double angle = startAngle + sweep * i / samples;
            points.add(new Point(
                    (int) Math.round(centreX + Math.cos(angle) * radius),
                    (int) Math.round(centreY + Math.sin(angle) * radius)));
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
     * <p>The path's points are <b>corners</b>, not pixels — an orthogonal route is four points and three
     * long segments. The first version tried to merge runs over that sparse list, and its index
     * arithmetic emitted a zero-length run at every corner (a one-pixel dot) while advancing past the
     * segment that followed, so an orthogonal line came out as a stub, a dot and nothing else. A solid
     * curve came out dotted for the same reason from the other end: sampled every couple of pixels, each
     * two-pixel step drew one pixel.
     *
     * <p>So the path is expanded first — {@link #walk} turns every segment into one point per pixel —
     * and the merge runs over that. A corner is then just a point where the axis changes, the run before
     * it ends on it and the run after it starts on it, and a solid line is continuous at every slope
     * because every walk point is covered by exactly one fill.
     *
     * <p>{@code dash} null or solid fills the whole run; a broken pattern alternates along the
     * <b>walk</b>, so the rhythm follows the route rather than the axes and a dash never straddles a
     * corner as two half-dashes. {@code weight} null or thin is one pixel; every heavier weight is the
     * same run drawn as a band of parallel chips, and a conduit's chips carry {@link Tone}s so its
     * borders can be inked darker and its core lighter.
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
            runs(walk, DependencyStyle.Dash.SOLID, DependencyStyle.Weight.THIN, single);
            for (Fill fill : single) {
                out.add(shifted(fill, -1));
                out.add(shifted(fill, 1));
            }
            return out;
        }
        runs(walk, pattern, ink, out);
        if (pattern == DependencyStyle.Dash.HAZARD) {
            barbs(path, out);
        }
        return out;
    }

    /**
     * The merged rectangles of one walk under one pattern and weight.
     *
     * <p>One loop for every rhythm and every weight, because the drawing, the preview cell and the tests
     * must not each grow their own idea of what a pattern means.
     */
    private static void runs(List<Point> walk, DependencyStyle.Dash pattern, DependencyStyle.Weight weight,
                             List<Fill> out) {
        int index = 0;
        while (index < walk.size()) {
            if (!onAt(pattern, index)) {
                index++;
                continue;
            }
            if (index == walk.size() - 1) {
                // A run that begins on the last point has no next point to merge with, and it is still a
                // pixel of the line: without this the final pixel of a diagonal or a curve is simply not
                // drawn, which is how a line ends one pixel short of the node it points at.
                emit(walk, index, index, out, weight);
                break;
            }
            int end = index;
            while (end < walk.size() - 1 && onAt(pattern, end + 1) && onAxis(walk, index, end + 1)) {
                end++;
            }
            emit(walk, index, end, out, weight);
            index = end + 1;
        }
    }

    /**
     * The path with one point per pixel: every segment walked with {@link #steps}, each following
     * segment's first point dropped because it is the point before it.
     *
     * <p>Public because it is the shape a test can assert against — "every walk point is covered by
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
     * Whether a pattern's ink is on this far along the walk.
     *
     * <p>The dots are one pixel with a three-pixel gap — tight enough to read as a dotted line rather
     * than as a dashed one — and the dash-dot is the classic long-short rhythm: six on, three off, one
     * on, three off.
     */
    private static boolean onAt(DependencyStyle.Dash pattern, int index) {
        return switch (pattern) {
            case SOLID, DOUBLE, HAZARD -> true;
            case DASHED -> index % (DASH_ON + DASH_OFF) < DASH_ON;
            case DOTTED -> index % DOTTED_PERIOD < DOTTED_ON;
            case DASH_DOT -> {
                int at = index % (DASH_ON + DASH_DOT_GAP + DOTTED_ON + DASH_DOT_GAP);
                yield at < DASH_ON || at == DASH_ON + DASH_DOT_GAP;
            }
        };
    }

    /** Whether the points {@code start..next} of the walk all lie on one axis. */
    private static boolean onAxis(List<Point> walk, int start, int next) {
        Point first = walk.get(start);
        Point previous = walk.get(next - 1);
        Point end = walk.get(next);
        return (first.y() == previous.y() && previous.y() == end.y())
                || (first.x() == previous.x() && previous.x() == end.x());
    }

    /** One merged rectangle for the points {@code from..to} of the walk, as a band of one-pixel chips. */
    private static void emit(List<Point> path, int from, int to, List<Fill> out,
                             DependencyStyle.Weight weight) {
        Point a = path.get(from);
        Point b = path.get(to);
        Fill base;
        if (a.y() == b.y()) {
            base = new Fill(Math.min(a.x(), b.x()), a.y(), Math.max(a.x(), b.x()) + 1, a.y() + 1);
        }
        else if (a.x() == b.x()) {
            base = new Fill(a.x(), Math.min(a.y(), b.y()), a.x() + 1, Math.max(a.y(), b.y()) + 1);
        }
        else {
            // A diagonal or a curve's sample: one pixel per point, and the walk covers them all.
            base = new Fill(a.x(), a.y(), a.x() + 1, a.y() + 1);
        }
        int width = weight == null ? 1 : weight.width();
        for (int i = 0; i < width; i++) {
            Fill band = shifted(base, i);
            out.add(new Fill(band.x1(), band.y1(), band.x2(), band.y2(), toneOf(weight, i)));
        }
    }

    /**
     * The same rectangle one step further along the band's own direction.
     *
     * <p>The step follows the run's shape: down for a horizontal run, right for a vertical one, and
     * diagonally for a curve's sample — the three cases the old "thick twin" had, now the unit every
     * heavier weight is built from.
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
     * instead of a one-pixel staircase's.
     */
    private static void barbs(List<Point> path, List<Fill> out) {
        double total = length(path);
        for (double at = HAZARD_STEP; at < total - HAZARD_BARB; at += HAZARD_STEP) {
            Point point = pointAt(path, at);
            double angle = tangentAt(path, at) + Math.PI / 4;
            Point end = new Point((int) Math.round(point.x() + Math.cos(angle) * HAZARD_BARB),
                    (int) Math.round(point.y() + Math.sin(angle) * HAZARD_BARB));
            for (Point step : steps(point, end)) {
                out.add(new Fill(step.x(), step.y(), step.x() + 1, step.y() + 1));
            }
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
     * how far back from the final point the tip is placed — the head belongs at the node's rim, and the
     * node is drawn over the line, so a head left at the centre would simply be hidden.
     */
    public static List<Fill> arrows(List<Point> path, DependencyStyle.ArrowHead head,
                                    DependencyStyle.ArrowPlace place, int spacing, int fromHalf,
                                    int toHalf) {
        if (head == null || head == DependencyStyle.ArrowHead.NONE || place == null || path.size() < 2) {
            return List.of();
        }
        double total = length(path);
        // Each end uses **its own** node's half-size: one shared figure pushed a small node's head away
        // by its neighbour's bulk, which is how an arrow ended up floating in the middle of a line.
        double arrival = toHalf + ARROW_GAP;
        double departure = fromHalf + ARROW_GAP;
        List<Fill> out = new ArrayList<>();
        boolean roomForArrival = total > arrival + reach(head);
        boolean roomForDeparture = total > departure + reach(head);
        if (place == DependencyStyle.ArrowPlace.STREAM) {
            double every = Math.max(Math.max(spacing, 1), reach(head) * 3);
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
            emitHeads(path, backs, head, out);
            return out;
        }
        if (place == DependencyStyle.ArrowPlace.MID) {
            // One head dead-centre, pointing the way the route runs there -- and nothing at the ends,
            // because "which way does this line run" is the whole of what the middle is saying.
            double at = total / 2;
            headAt(pointAt(path, at), tangentAt(path, at), head, out);
            return out;
        }
        // A head that cannot fit outside its node is left off rather than drawn inside it: a short edge
        // with no arrow is honest, and a head buried in a node is the fault this placement exists for.
        if (roomForArrival) {
            head(path, arrival, false, head, out);
        }
        if (place == DependencyStyle.ArrowPlace.BOTH && roomForDeparture) {
            head(path, departure, true, head, out);
        }
        return out;
    }

    /**
     * The same heads, for a caller still holding the legacy axis — an old file, or an old server.
     *
     * <p>{@code many} keeps its old 24-pixel rhythm rather than jumping to the medium density: the
     * density axis was added to give authors a choice, not to redraw packs that had already made one.
     */
    public static List<Fill> arrows(List<Point> path, DependencyStyle.Arrows arrows, int fromHalf,
                                    int toHalf) {
        if (arrows == null) {
            return List.of();
        }
        int spacing = arrows == DependencyStyle.Arrows.MANY
                ? DependencyStyle.LEGACY_STREAM_SPACING : DependencyStyle.ArrowDensity.MEDIUM.spacing();
        return arrows(path, DependencyStyle.headOf(arrows), DependencyStyle.placeOf(arrows), spacing,
                fromHalf, toHalf);
    }

    /** How far forward a glyph reaches from its tip, for the room check that keeps heads off nodes. */
    private static double reach(DependencyStyle.ArrowHead head) {
        return switch (head) {
            case CHEVRON -> ARROW_LENGTH;
            case TRIANGLE -> TRIANGLE_LENGTH;
            case DIAMOND -> DIAMOND_LENGTH;
            case DOT -> 2;
            case NONE -> 0;
        };
    }

    /**
     * Heads at several distances along a path, sampled in one walk and emitted in the given order.
     *
     * <p>{@code backs} are distances from the path's end, all arrival-facing — the shape a stream uses.
     * Emission order is the caller's list order rather than the sampling order, because the fills a
     * caller gets back are part of what it draws, and a reordered list would be a changed drawing even
     * where the pixels coincide.
     */
    private static void emitHeads(List<Point> path, List<Double> backs, DependencyStyle.ArrowHead head,
                                  List<Fill> out) {
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
            headAt(tipOf[i], angleOf[i], head, out);
        }
    }

    /**
     * The points at several distances, in one walk — the same arithmetic as {@link #pointAt} per
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
     * The tangents at several distances, in one walk — the same arithmetic as {@link #tangentAt} per
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
                             List<Fill> out) {
        double total = length(path);
        double from = atStart ? back : total - back;
        if (from < 0 || from > total) {
            return;
        }
        Point tip = pointAt(path, from);
        // The path runs source to target, so the arrival head points along it and the departure head
        // points back at its own node.
        double angle = tangentAt(path, from) + (atStart ? Math.PI : 0);
        headAt(tip, angle, head, out);
    }

    /** One head whose tip and direction are already known: the glyph's own shape. */
    private static void headAt(Point tip, double angle, DependencyStyle.ArrowHead head, List<Fill> out) {
        switch (head) {
            case CHEVRON -> chevronAt(tip, angle, out);
            case TRIANGLE -> wedgeAt(tip, angle, TRIANGLE_LENGTH, TRIANGLE_HALF, out);
            case DIAMOND -> diamondAt(tip, angle, out);
            case DOT -> dotAt(tip, out);
            case NONE -> {
            }
        }
    }

    /** One chevron whose tip and direction are already known: two walked wings, as fills. */
    private static void chevronAt(Point tip, double angle, List<Fill> out) {
        for (int side = -1; side <= 1; side += 2) {
            double wing = angle + Math.PI + side * 0.5;
            Point wingEnd = new Point((int) Math.round(tip.x() + Math.cos(wing) * ARROW_LENGTH),
                    (int) Math.round(tip.y() + Math.sin(wing) * ARROW_LENGTH));
            // A walked stroke, not rounded dots: a wing is continuous at every angle instead of a dotted
            // line on the diagonals.
            for (Point point : steps(tip, wingEnd)) {
                out.add(new Fill(point.x(), point.y(), point.x() + 1, point.y() + 1));
            }
        }
    }

    /**
     * A filled wedge, rows across the direction of travel: zero width at the tip, widest at the tail.
     *
     * <p>What a triangle head is at this scale; the rows are single pixels, because the renderer's
     * primitive is a rectangle and one pixel per column is the only way to keep an angled edge crisp.
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
    private static void diamondAt(Point tip, double angle, List<Fill> out) {
        double ux = Math.cos(angle);
        double uy = Math.sin(angle);
        double px = -uy;
        double py = ux;
        for (int i = 0; i < DIAMOND_LENGTH; i++) {
            int fromTip = Math.min(i, DIAMOND_LENGTH - 1 - i);
            int spread = Math.round(DIAMOND_HALF * (float) fromTip / (DIAMOND_LENGTH / 2));
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

    /** A bead: five pixels in a plus, round enough at this scale and distinct from every other head. */
    private static void dotAt(Point tip, List<Fill> out) {
        out.add(new Fill(tip.x(), tip.y(), tip.x() + 1, tip.y() + 1));
        out.add(new Fill(tip.x() - 1, tip.y(), tip.x(), tip.y() + 1));
        out.add(new Fill(tip.x() + 1, tip.y(), tip.x() + 2, tip.y() + 1));
        out.add(new Fill(tip.x(), tip.y() - 1, tip.x() + 1, tip.y()));
        out.add(new Fill(tip.x(), tip.y() + 1, tip.x() + 1, tip.y() + 2));
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
     * The nearest distance from a point to any of several paths — the hover's own reach when a line's
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
     * clamping at zero — the same sign {@code curve} reads, which is what makes the round trip exact.
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
     * A pointer as `[along, across]` in the chord's frame — the exact inverse of {@link #handlePoint},
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
