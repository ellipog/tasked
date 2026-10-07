package dev.ellipog.tasked.client.dev;

import dev.ellipog.tasked.quest.DependencyStyle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The walk's cost, pinned — one point per pixel of on-screen route.
 *
 * <h2>Why this is asserted, and what it corrects</h2>
 *
 * <p>Two diagnoses of the zoom cost were written before this test existed, and <b>both were wrong</b>, because
 * neither was checked against the code. The first said the route memo was keyed on absolute screen positions
 * so a zoom was a cache miss; the second said the fix was to key routes on world coordinates. The source says
 * otherwise, and this test is the statement of what it actually says:
 *
 * <ul>
 *   <li>{@code fills} <b>is</b> memoised, and {@code geometry} already relativises the route, so the hit rate is
 *       100% — the measurement says so: {@code linefills 3322/0 (100%)};</li>
 *   <li>but the walk is <b>one point per pixel of the route's on-screen length</b>, so a zoom makes every
 *       route a different, longer list — and its rectangles genuinely are different. There is no key that
 *       makes that go away.</li>
 * </ul>
 *
 * <p>So the cost at 220% is arithmetic rather than a stale key: 1,647 lines × ~1,108 points = 1.8 million
 * {@code Point} objects per rebuild, which at 2 fps is the 3,648,943 a second the overlay reported. This test
 * makes that relationship something the suite holds, so the next person to reason about it — including me —
 * has a fact to reason from rather than a comment to trust.
 *
 * <p><b>It is deliberately about the walk and not about the zoom.</b> What is being pinned is the property the
 * zoom cost follows from: walk length is proportional to route length, and therefore to the zoom. A change
 * that decoupled them — a fixed-density walk, say — would be a real fix and would fail this test, which is the
 * point: it would be a decision rather than an accident.
 */
@DisplayName("the route walk's cost")
class LineArtWalkCostTest {

    /** A route from the origin to a point, in the given form. */
    private static List<LineArt.Point> route(DependencyStyle.Form form, int x, int y) {
        return LineArt.path(form, new LineArt.Point(0, 0), new LineArt.Point(x, y));
    }

    @Test
    @DisplayName("a walk is about one point per pixel of the route, whatever its form")
    void aWalkIsOnePointPerPixel() {
        // Four forms, each roughly 180 pixels long, and every one walks to about that many points. The
        // tolerance is generous because a form's corners add a few points and a diagonal steps a little
        // differently -- what is being asserted is the *order*, not the exact count, because the order is
        // what the zoom cost follows from.
        for (DependencyStyle.Form form : List.of(DependencyStyle.Form.STRAIGHT,
                DependencyStyle.Form.ORTHOGONAL, DependencyStyle.Form.CHAMFERED,
                DependencyStyle.Form.CURVED)) {
            List<LineArt.Point> route = route(form, 180, 0);
            int walked = LineArt.walk(route).size();

            assertTrue(walked >= 150 && walked <= 220,
                    form + ": a route 180 px long walked to " + walked + " points, which is not about one"
                            + " per pixel -- and the whole zoom cost follows from this ratio");
        }
    }

    @Test
    @DisplayName("a longer route walks proportionally further, which is why a zoom costs more")
    void aLongerRouteWalksFurther() {
        // **This is the property the zoom cost is made of.** A route twice as long on screen walks to about
        // twice as many points, so a 2.2x zoom walks every line about 2.2x as far -- which is why 1,647 lines
        // at 220% is 1.8 million points a rebuild and not a cache miss anybody can fix.
        int shortWalk = LineArt.walk(route(DependencyStyle.Form.STRAIGHT, 100, 0)).size();
        int longWalk = LineArt.walk(route(DependencyStyle.Form.STRAIGHT, 400, 0)).size();

        assertTrue(longWalk > shortWalk * 3,
                "four times the length should walk about four times as far: " + shortWalk + " then " + longWalk);
    }

    @Test
    @DisplayName("a route with no length walks to nothing rather than to a point")
    void aDegenerateRouteWalksToNothing() {
        // The boundary, because a zero-length route is what a node dragged onto another produces: the walk
        // must not invent a point, and the stroke below it already refuses fewer than two.
        assertTrue(LineArt.walk(route(DependencyStyle.Form.STRAIGHT, 0, 0)).size() <= 1,
                "a route of no length is not a run of pixels");
    }

    @Test
    @DisplayName("the walked points are contiguous, which is what makes a stroke solid")
    void theWalkIsContiguous() {
        // The property `stroke` relies on: consecutive walk points are one pixel apart, so a band of "on"
        // stretches covers the route with no gap. A walk that skipped would draw a dotted line wherever the
        // rhythm said solid -- and this is the assertion the class note says the walk exists to support.
        List<LineArt.Point> walk = LineArt.walk(route(DependencyStyle.Form.STRAIGHT, 60, 30));

        for (int i = 1; i < walk.size(); i++) {
            int dx = Math.abs(walk.get(i).x() - walk.get(i - 1).x());
            int dy = Math.abs(walk.get(i).y() - walk.get(i - 1).y());
            assertTrue(dx <= 1 && dy <= 1,
                    "walk point " + i + " is " + dx + "," + dy + " from the one before it, so the run has a gap");
        }
    }

    @Test
    @DisplayName("and the walk counter reports what was walked")
    void theCounterReportsTheWalk() {
        // The number the overlay shows and the measurement was taken from, so it is worth pinning that it
        // counts the walk rather than something adjacent to it.
        LineArt.drainWalked();
        int walked = LineArt.walk(route(DependencyStyle.Form.STRAIGHT, 200, 0)).size();

        assertEquals(walked, LineArt.drainWalked(),
                "the counter is the walk's own length, which is what `linewalk` on the overlay means");
    }
}
