package dev.ellipog.tasked.client.dev;

import dev.ellipog.tasked.client.dev.LineArt.Fill;
import dev.ellipog.tasked.client.dev.LineArt.Point;
import dev.ellipog.tasked.quest.DependencyStyle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The line layer's cost, and the two things that used to make it the frame's whole frame rate.
 *
 * <h2>The defect this pins</h2>
 *
 * <p>Every edge in a chapter was walked twice per frame — once to count its pixels for the dash rhythm and
 * once inside the stroker — and every band's pixels were then <b>copied and sorted</b> before being merged
 * into rectangles. A walk is one object per pixel of the route's length, so a chapter of a few hundred
 * edges allocated tens of thousands of objects every frame, twenty to sixty times a second. That is GC, and
 * it is why a busy chapter read 20 fps while an empty one read 220: none of it scales with what is <i>on
 * screen</i>, only with how much line the chapter contains.
 *
 * <p>The fix is that the rectangles for a route are remembered against the route itself. {@code fills} is a
 * pure function of the points, the weight and the dash, so there is nothing to invalidate — a moved node is
 * a different route, therefore a different key, with no revision to plumb through. What these tests assert
 * is the number that proves it: the first call walks, and every later one computes <b>no walk points at
 * all</b>.
 *
 * <h2>Why every route here is its own</h2>
 *
 * <p>Because the cache is static and the suite runs in one JVM: a route another test already drew would
 * answer from the cache and the "first call walks" assertion would read zero for the honest reason that it
 * was not the first call. Each test therefore draws lines at coordinates of its own, which is also what
 * makes the numbers below a measurement rather than a hope.
 */
@DisplayName("the line layer's remembered rectangles")
class LineArtFillCacheTest {

    /** A long orthogonal route at a base of the caller's choosing: three segments, two of them axes. */
    private static List<Point> route(int baseX, int baseY) {
        return LineArt.path(DependencyStyle.Form.ORTHOGONAL,
                new Point(baseX, baseY), new Point(baseX + 200, baseY + 120));
    }

    private static final DependencyStyle.Weight THIN = DependencyStyle.Weight.THIN;
    private static final DependencyStyle.Dash SOLID = DependencyStyle.Dash.SOLID;

    @Test
    @DisplayName("a route that has not moved is not walked a second time")
    void aStillRouteIsNotWalkedAgain() {
        // A base nothing else in the suite draws at, so the first call is genuinely the first.
        List<Point> path = route(1000, 2000);

        LineArt.drainWalked();
        List<Fill> first = LineArt.fills(path, THIN, SOLID);
        long firstWalks = LineArt.drainWalked();

        List<Fill> second = LineArt.fills(path, THIN, SOLID);
        long secondWalks = LineArt.drainWalked();

        assertEquals(first, second, "the same route must give the same rectangles");
        assertTrue(firstWalks > 100,
                "the first call walks the route, one point per pixel: " + firstWalks + " point(s)");
        assertEquals(0, secondWalks,
                "and the second pays nothing at all — no walk, so none of the per-pixel objects a walk "
                        + "allocates, which is the whole of what this round removed");
    }

    @Test
    @DisplayName("the heads are remembered too, and a route that moved is not a cache hit")
    void arrowsAreRememberedAndRoutesAreNotConfused() {
        List<Point> path = route(3000, 4000);

        LineArt.drainWalked();
        LineArt.arrows(path, DependencyStyle.ArrowHead.CHEVRON, DependencyStyle.ArrowPlace.TARGET,
                24, 0, 0);
        long sampled = LineArt.drainWalked();
        assertTrue(sampled > 0, "the first call samples the route to place the head: " + sampled);

        LineArt.arrows(path, DependencyStyle.ArrowHead.CHEVRON, DependencyStyle.ArrowPlace.TARGET,
                24, 0, 0);
        assertEquals(0, LineArt.drainWalked(), "and the second does not sample it again");

        // The safety property of keying on the route's own points: a node that moved gives different
        // points, which is a different key, so a stale set of rectangles cannot be handed back.
        List<Point> moved = List.of(new Point(3000, 4000), new Point(3060, 4000), new Point(3060, 4120),
                new Point(3200, 4120));
        assertNotEquals(LineArt.fills(path, THIN, SOLID), LineArt.fills(moved, THIN, SOLID),
                "a route that moved is a different drawing");
    }

    @Test
    @DisplayName("a pan is an exact translation of the same rectangles, not a redraw")
    void aPanIsATranslation() {
        // The property the drawing relies on now: an edge's rectangles are asked for *relative to its own
        // first point*, and the caller adds that point back. So for every route, and every pan, offsetting
        // the relative rectangles must equal what the absolute route produces — exactly, not approximately,
        // because a fill off by a pixel is a line drawn in the wrong place and there is no test in the
        // previews that would notice one edge among a thousand.
        //
        // It also has to be true of the *heads*, which sample the route: a chevron is placed by walking it.
        List<List<Point>> routes = List.of(
                LineArt.path(DependencyStyle.Form.ORTHOGONAL, new Point(0, 0), new Point(180, 90)),
                LineArt.path(DependencyStyle.Form.CHAMFERED, new Point(0, 0), new Point(-140, 60)),
                LineArt.path(DependencyStyle.Form.STRAIGHT, new Point(0, 0), new Point(90, 90)),
                LineArt.path(DependencyStyle.Form.CURVED, new Point(0, 0), new Point(120, -70)));

        for (List<Point> route : routes) {
            for (Point pan : List.of(new Point(0, 0), new Point(37, -11), new Point(-250, 480))) {
                List<Point> moved = new java.util.ArrayList<>(route.size());
                for (Point point : route) {
                    moved.add(new Point(point.x() + pan.x(), point.y() + pan.y()));
                }

                // 1. The cache-hit property: the relative rectangles of a panned route are *identical* to
                //    the route's own, which is what makes a pan cost one subtraction per point.
                assertEquals(LineArt.fills(route, THIN, SOLID), LineArt.fillsRelative(moved, THIN, SOLID),
                        "relative rectangles changed when " + route + " was panned by " + pan);

                // 2. And the drawing's arithmetic: adding the origin back must reproduce exactly what the
                //    absolute route draws. This is the assertion that says the picture did not change —
                //    a fill off by a pixel is a line in the wrong place, and nothing in the previews would
                //    notice one edge among a thousand.
                assertEquals(LineArt.fills(moved, THIN, SOLID),
                        shifted(LineArt.fillsRelative(moved, THIN, SOLID), moved.get(0)),
                        "the drawn rectangles for " + route + " panned by " + pan);

                assertEquals(LineArt.arrows(moved, DependencyStyle.ArrowHead.CHEVRON,
                                DependencyStyle.ArrowPlace.TARGET, 24, 0, 0),
                        shifted(LineArt.arrowsRelative(moved, DependencyStyle.ArrowHead.CHEVRON,
                                DependencyStyle.ArrowPlace.TARGET, 24, 0, 0), moved.get(0)),
                        "the drawn heads for " + route + " panned by " + pan);
            }
        }
    }

    /** The same rectangles, moved — what the drawing computes by adding the origin as it fills. */
    private static List<Fill> shifted(List<Fill> fills, Point by) {
        List<Fill> out = new ArrayList<>(fills.size());
        for (Fill fill : fills) {
            out.add(new Fill(fill.x1() + by.x(), fill.y1() + by.y(),
                    fill.x2() + by.x(), fill.y2() + by.y(), fill.tone()));
        }
        return out;
    }

    @Test
    @DisplayName("a still chapter does no line work at all, however many edges it has")
    void aChapterOfStillEdgesStopsWalking() {
        // The scene the report is about: many edges, nothing moving. Every route is walked once as it is
        // first drawn and never again, so the second frame's line work is zero whatever the edge count —
        // which is the property a frame count cannot show and this can.
        List<List<Point>> chapter = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            chapter.add(route(20_000 + i * 23, 30_000 + i * 11));
        }

        long[] walked = new long[2];
        for (int frame = 0; frame < 2; frame++) {
            LineArt.drainWalked();
            for (List<Point> edge : chapter) {
                LineArt.fills(edge, THIN, SOLID);
                LineArt.arrows(edge, DependencyStyle.ArrowHead.CHEVRON, DependencyStyle.ArrowPlace.TARGET,
                        24, 0, 0);
            }
            walked[frame] = LineArt.drainWalked();
        }

        assertTrue(walked[0] > 10_000,
                "the first frame walks the chapter: " + walked[0] + " point(s) — tens of thousands of "
                        + "objects, which was the 20 fps");
        assertEquals(0, walked[1],
                "and every later frame of a still chapter walks nothing: " + walked[1] + " point(s)");
        // Printed rather than only asserted, so the figures are quotable in the notes: the first number is
        // what every frame used to cost, twice over, for this chapter.
        System.out.printf("still chapter of %d edge(s): first frame %d walk point(s), second frame %d%n",
                chapter.size(), walked[0], walked[1]);
    }
}
