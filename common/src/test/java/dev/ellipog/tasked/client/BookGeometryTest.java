package dev.ellipog.tasked.client;

import dev.ellipog.tasked.client.BookGeometry.Rect;
import dev.ellipog.tasked.client.BookGeometry.Zoom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The quest book's layout, tested without a game.
 *
 * <h2>What this exists to prevent</h2>
 *
 * <p>A screenshot showed the <b>Done</b> and <b>Open</b> buttons drawn on top of each other in the
 * bottom-right corner. The cause was that "near the bottom right" was written twice, as two
 * expressions that were equal only by coincidence:
 *
 * <pre>
 * Open: canvasRight() - 78,               canvasBottom() + 11
 * Done: panelLeft() + panelWidth() - 68,  panelTop() + panelHeight() - 24
 * </pre>
 *
 * <p>{@code canvasRight() == panelLeft() + panelWidth()}, so the two landed about ten pixels apart in
 * x and fifteen in y — which is to say, on top of each other.
 *
 * <p>The immediate fix was to compute each shared position once. <b>This is the durable fix.</b> The
 * arithmetic now lives in {@link BookGeometry}, which has no Minecraft in it, so the property that was
 * violated can be stated as a test and checked over every window size rather than at one:
 *
 * <pre>
 * no two controls overlap, and every control is inside the surface it belongs to
 * </pre>
 *
 * <p>Reproducing the old bug for the record: at 427x240 GUI pixels — the size the screenshot was
 * taken at — the old Open rectangle was {@code 329,185 70x18} and the old Done was
 * {@code 339,196 60x18}. Those intersect. Every test below that sweeps window sizes would have failed
 * on the old arithmetic.
 *
 * <h2>Why the sweep rather than a handful of cases</h2>
 *
 * <p>Because the failure mode is size-dependent. The developer's monitor is the one size that
 * certainly works, so a single-case test asserts the least interesting thing. The sweep here starts
 * deliberately smaller than any real window, because a layout that only holds together above
 * 600 pixels wide is a layout with a bug waiting for a laptop.
 */
class BookGeometryTest {

    /** The window the screenshot was taken in, at GUI scale 2. */
    private static final int SCREENSHOT_WIDTH = 427;
    private static final int SCREENSHOT_HEIGHT = 240;

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Every control's name and rectangle, for a screen of that size. */
    private static Map<String, Rect> controlsAt(int width, int height, int chapters, boolean hasOpen) {
        return new BookGeometry(width, height).controls(chapters, hasOpen);
    }

    /** The first overlapping pair, or null if none. Names the two, so a failure is actionable. */
    private static String firstOverlap(Map<String, Rect> controls) {
        List<String> names = new ArrayList<>(controls.keySet());
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                Rect a = controls.get(names.get(i));
                Rect b = controls.get(names.get(j));
                if (a.intersects(b)) {
                    return names.get(i) + " " + a + " overlaps " + names.get(j) + " " + b;
                }
            }
        }
        return null;
    }

    /** A sweep of sizes, from too small to bigger than any monitor, at deliberately odd steps. */
    private static List<int[]> sizes() {
        List<int[]> out = new ArrayList<>();
        for (int width = 160; width <= 1400; width += 7) {
            for (int height = 100; height <= 1000; height += 11) {
                out.add(new int[] {width, height});
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // The property that was violated
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("controls do not overlap")
    class NoOverlap {

        @Test
        @DisplayName("no two controls overlap, at any window size and any chapter count")
        void noTwoControlsOverlapEver() {
            for (int[] size : sizes()) {
                for (int chapters = 0; chapters <= 6; chapters++) {
                    for (boolean hasOpen : new boolean[] {true, false}) {
                        Map<String, Rect> controls = controlsAt(size[0], size[1], chapters, hasOpen);
                        String overlap = firstOverlap(controls);
                        if (overlap != null) {
                            throw new AssertionError("at " + size[0] + "x" + size[1]
                                    + " with " + chapters + " chapters"
                                    + (hasOpen ? " and a selection" : "")
                                    + ": " + overlap);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("Done and Open are never near each other — the reported bug")
        void doneAndOpenNeverCollide() {
            // The two controls that collided, asserted directly and with a margin, so a failure names
            // the reported symptom rather than a generic rectangle clash. They sit on different
            // surfaces either side of the sidebar divider, so "near each other" is never correct.
            for (int[] size : sizes()) {
                Map<String, Rect> controls = controlsAt(size[0], size[1], 4, true);
                Rect done = controls.get("done");
                Rect open = controls.get("open");

                assertFalse(done.intersects(open),
                        "Done " + done + " and Open " + open + " overlap at " + size[0] + "x" + size[1]);
                assertTrue(BookGeometry.clearOf(done, open, 4),
                        "Done " + done + " and Open " + open + " are within four pixels at "
                                + size[0] + "x" + size[1]);
            }
        }

        @Test
        @DisplayName("the three zoom controls do not overlap each other")
        void theFooterRowFitsAcross() {
            // Three controls across 116 pixels of sidebar. They are placed from one arithmetic series
            // -- 30, gap, 30, gap, the rest -- so the row is flush at both ends by construction.
            for (int[] size : sizes()) {
                Map<String, Rect> controls = controlsAt(size[0], size[1], 2, false);
                Rect in = controls.get("zoomIn");
                Rect out = controls.get("zoomOut");
                Rect centre = controls.get("centre");

                assertFalse(in.intersects(out), "zoom in/out collide at " + size[0] + "x" + size[1]);
                assertFalse(out.intersects(centre), "zoom out/centre collide at " + size[0] + "x" + size[1]);
                assertEquals(BookGeometry.SIDEBAR_WIDTH - BookGeometry.EDGE,
                        centre.right() - new BookGeometry(size[0], size[1]).panel().x(),
                        "the footer row should be flush with the sidebar's right edge");
            }
        }

        @Test
        @DisplayName("a screenshot-sized window is fine, and so is a deliberately tiny one")
        void smallWindowsAreFineToo() {
            // The real case, and a case smaller than anything real. A minimum panel that merely
            // *looks* big enough on a developer's monitor is how the original bug survived.
            for (int[] size : new int[][] {{SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT}, {200, 120}, {160, 100}}) {
                String overlap = firstOverlap(controlsAt(size[0], size[1], 5, true));
                assertTrue(overlap == null, "at " + size[0] + "x" + size[1] + ": " + overlap);
            }
        }

        @Test
        @DisplayName("the overlay's Submit and Back stack rather than collide on a narrow window")
        void overlayControlsNeverCollide() {
            for (int width = 160; width <= 1400; width += 7) {
                for (int height = 100; height <= 1000; height += 11) {
                    for (boolean hasSubmit : new boolean[] {true, false}) {
                        String overlap = firstOverlap(
                                new BookGeometry(width, height).overlayControls(hasSubmit));
                        assertTrue(overlap == null, "overlay at " + width + "x" + height + ": " + overlap);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Everything inside the surface it belongs to
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("controls are inside their surface")
    class Containment {

        @Test
        @DisplayName("the chapter list and the footer are inside the sidebar")
        void sidebarControlsAreInsideTheSidebar() {
            for (int[] size : sizes()) {
                BookGeometry geometry = new BookGeometry(size[0], size[1]);
                Map<String, Rect> controls = geometry.controls(6, true);
                Rect sidebar = geometry.sidebar();

                for (Map.Entry<String, Rect> entry : controls.entrySet()) {
                    if (!entry.getKey().equals("open")) {
                        assertTrue(entry.getValue().isInside(sidebar),
                                entry.getKey() + " " + entry.getValue() + " is outside the sidebar "
                                        + sidebar + " at " + size[0] + "x" + size[1]);
                    }
                }
            }
        }

        @Test
        @DisplayName("the Open button is inside the strip")
        void openButtonIsInsideTheStrip() {
            for (int[] size : sizes()) {
                BookGeometry geometry = new BookGeometry(size[0], size[1]);
                Rect open = geometry.controls(1, true).get("open");
                assertTrue(open.isInside(geometry.strip()),
                        "Open " + open + " is outside the strip " + geometry.strip()
                                + " at " + size[0] + "x" + size[1]);
            }
        }

        @Test
        @DisplayName("every chapter row is above the footer")
        void chapterRowsStopAboveTheFooter() {
            // The multi-row version of the reported bug: a chapter drawn underneath a button, where
            // it is both invisible and unclickable. The row count is derived from where the footer
            // actually starts, so this cannot drift.
            for (int[] size : sizes()) {
                BookGeometry geometry = new BookGeometry(size[0], size[1]);
                int footer = geometry.footerRow1Y();

                for (int i = 0; i < geometry.chapterRows(); i++) {
                    int bottom = geometry.chapterRowY(i) + BookGeometry.ROW_HEIGHT;
                    assertTrue(bottom <= footer,
                            "chapter row " + i + " ends at " + bottom + ", past the footer at " + footer
                                    + " on a " + size[0] + "x" + size[1] + " screen");
                }
            }
        }

        @Test
        @DisplayName("at least one chapter can always be shown")
        void atLeastOneChapterRowAlwaysFits() {
            // A chapter list with zero rows is a book whose only chapter cannot be selected, which is
            // a blank screen with no way forward. The minimum panel height exists to make this true.
            for (int[] size : sizes()) {
                assertTrue(new BookGeometry(size[0], size[1]).chapterRows() >= 1,
                        "no chapter row fits on a " + size[0] + "x" + size[1] + " screen");
            }
        }

        @Test
        @DisplayName("at least one chapter row is actually offered as a control")
        void oneChapterControlAlwaysExists() {
            // Distinct from the row count: `controls` caps the rows it offers by the rows that fit, so
            // a mistake there would show a row and offer nothing to click.
            for (int[] size : sizes()) {
                assertTrue(new BookGeometry(size[0], size[1]).controls(1, false).containsKey("chapter0"),
                        "no chapter control on a " + size[0] + "x" + size[1] + " screen");
            }
        }

        @Test
        @DisplayName("the canvas has room to draw in")
        void theCanvasIsNotDegenerate() {
            for (int[] size : sizes()) {
                BookGeometry geometry = new BookGeometry(size[0], size[1]);
                assertTrue(geometry.canvas().width() >= BookGeometry.MIN_CANVAS_WIDTH,
                        "canvas is " + geometry.canvas().width() + " wide at " + size[0] + "x" + size[1]);
                assertTrue(geometry.canvas().height() > 0,
                        "canvas has no height at " + size[0] + "x" + size[1]);
            }
        }

        @Test
        @DisplayName("the strip's text stops before the Open button")
        void stripTextStopsBeforeTheButton() {
            // The strip's title and summary are drawn to this limit. If it were computed past the
            // button, a long quest title would run underneath it -- which reads as a rendering fault
            // rather than as a title that is simply too long.
            for (int[] size : sizes()) {
                BookGeometry geometry = new BookGeometry(size[0], size[1]);
                int limit = geometry.stripTextLimit();
                int buttonLeft = geometry.stripButtonX() - (geometry.canvas().x() + 10);

                assertTrue(limit < buttonLeft,
                        "text limit " + limit + " does not stop short of the button at " + buttonLeft
                                + " on a " + size[0] + "x" + size[1] + " screen");
            }
        }
    }

    // ------------------------------------------------------------------
    // Zoom
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("zoom about the pointer")
    class Zooming {

        /** The invariant: the world point under the pointer is the same before and after. */
        private static void assertPointerIsFixed(Rect canvas, double px, double py,
                                                 Zoom before, Zoom after) {
            double worldBefore = (px - canvas.x() - before.panX()) / before.zoom();
            double worldAfter = (px - canvas.x() - after.panX()) / after.zoom();
            double worldYBefore = (py - canvas.y() - before.panY()) / before.zoom();
            double worldYAfter = (py - canvas.y() - after.panY()) / after.zoom();

            assertEquals(worldBefore, worldAfter, 0.5 / after.zoom(),
                    "the world x under the pointer moved");
            assertEquals(worldYBefore, worldYAfter, 0.5 / after.zoom(),
                    "the world y under the pointer moved");
        }

        @Test
        @DisplayName("the point under the pointer stays under the pointer")
        void zoomKeepsThePointUnderThePointer() {
            // The property that makes a graph UI feel right, and the one most often got wrong: get it
            // backwards and the zoom appears to run away from the cursor. Checked from many starting
            // pans and zooms, at every corner of the canvas and in the middle, because an error in the
            // algebra often cancels at the centre.
            Rect canvas = new BookGeometry(SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT).canvas();

            for (int panX = -400; panX <= 400; panX += 137) {
                for (int panY = -300; panY <= 300; panY += 113) {
                    for (float zoom : new float[] {0.4F, 0.75F, 1.0F, 1.5F, 2.1F}) {
                        for (double[] pointer : new double[][] {
                                {canvas.x() + 1, canvas.y() + 1},
                                {canvas.x() + canvas.width() / 2.0, canvas.y() + canvas.height() / 2.0},
                                {canvas.right() - 2, canvas.bottom() - 2}}) {
                            Zoom before = new Zoom(panX, panY, zoom);
                            Zoom after = BookGeometry.zoomAbout(pointer[0], pointer[1], canvas,
                                    panX, panY, zoom, 1.15F, 0.35F, 2.2F);
                            assertPointerIsFixed(canvas, pointer[0], pointer[1], before, after);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("zooming in and then out returns to where it started")
        void zoomIsReversible() {
            // Within a pixel of rounding, which is as close as integer pans can get. Worth asserting
            // because a sign error in one direction only -- the easiest mistake to make here -- passes
            // any test that zooms in only.
            Rect canvas = new BookGeometry(SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT).canvas();
            double px = canvas.x() + 120;
            double py = canvas.y() + 90;

            Zoom start = new Zoom(40, -30, 1.0F);
            Zoom in = BookGeometry.zoomAbout(px, py, canvas, start.panX(), start.panY(), start.zoom(),
                    1.25F, 0.35F, 2.2F);
            Zoom back = BookGeometry.zoomAbout(px, py, canvas, in.panX(), in.panY(), in.zoom(),
                    1F / 1.25F, 0.35F, 2.2F);

            assertEquals(start.zoom(), back.zoom(), 1.0E-4F);
            assertTrue(Math.abs(start.panX() - back.panX()) <= 1, "pan x drifted");
            assertTrue(Math.abs(start.panY() - back.panY()) <= 1, "pan y drifted");
        }

        @Test
        @DisplayName("zoom stops at its limits rather than running away")
        void zoomIsClamped() {
            Rect canvas = new BookGeometry(SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT).canvas();

            Zoom zoom = new Zoom(0, 0, 1.0F);
            for (int i = 0; i < 40; i++) {
                zoom = BookGeometry.zoomAbout(100, 100, canvas, zoom.panX(), zoom.panY(), zoom.zoom(),
                        1.25F, 0.35F, 2.2F);
            }
            assertEquals(2.2F, zoom.zoom(), 1.0E-4F, "zoom in should stop at the maximum");

            for (int i = 0; i < 80; i++) {
                zoom = BookGeometry.zoomAbout(100, 100, canvas, zoom.panX(), zoom.panY(), zoom.zoom(),
                        0.8F, 0.35F, 2.2F);
            }
            assertEquals(0.35F, zoom.zoom(), 1.0E-4F, "zoom out should stop at the minimum");
        }

        @Test
        @DisplayName("a zoom already at its limit changes nothing at all")
        void zoomAtTheLimitDoesNotDrift() {
            // The early return matters: without it, zooming further at the limit would recompute the
            // pan from an unchanged zoom, and rounding would walk the canvas by a pixel per scroll
            // event until the questline had wandered off the screen.
            Rect canvas = new BookGeometry(SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT).canvas();
            Zoom atMax = new Zoom(123, -45, 2.2F);
            Zoom result = BookGeometry.zoomAbout(200, 150, canvas, atMax.panX(), atMax.panY(),
                    atMax.zoom(), 1.25F, 0.35F, 2.2F);

            assertEquals(atMax, result, "a clamped zoom must not move the pan");
        }
    }

    // ------------------------------------------------------------------
    // Label room and hit testing
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("labels and hit testing")
    class LabelsAndHits {

        @Test
        @DisplayName("columns 64 apart give each label 56 pixels, and 56 is less than 64")
        void crowdedColumnsStillLeaveMeasurableRoom() {
            // The bug behind the garbled text: a questline authored 64 pixels apart carrying titles
            // about 90 pixels wide. Room is the narrowest gap less a margin either side, so 64 - 8 = 56.
            //
            // The mind that wrote this test first asserted 0 here, and the comment claimed the screen
            // "draws no labels at all". That was wrong, and the true mechanism is the more interesting
            // one. At 56 the labels are not skipped -- each is *truncated to 56 pixels*, and 56 is
            // strictly less than the 64 the nodes are apart. So two labels cannot touch.
            //
            // What used to happen is the thing to keep in view: whole 90-pixel titles were centred on
            // nodes 64 apart, ran through each other, and read on screen as one corrupted string --
            // "Punch a SomewherStone To...". Three correct titles, drawn where they could not fit.
            assertEquals(56, BookGeometry.labelRoom(List.of(0, 64, 128), 4, 120));
        }

        @Test
        @DisplayName("room is always strictly less than the gap, so two labels can never touch")
        void roomIsAlwaysLessThanTheGap() {
            // The invariant the whole mechanism rests on, and the reason it is asserted over a sweep
            // rather than at one value. If room ever equalled or exceeded the gap, two adjacent labels
            // drawn to their full room would meet or overlap -- reintroducing exactly the reported
            // symptom, with the code looking correct at any single point tested.
            int gap = 4;
            for (int step = 10; step <= 400; step += 3) {
                for (int margin = 1; margin <= 8; margin++) {
                    int room = BookGeometry.labelRoom(List.of(0, step), margin, 120);
                    assertTrue(room < step || room == 120,
                            "gap " + step + " margin " + margin + " gave room " + room
                                    + ", which is not less than the gap");
                }
            }
            // And the same thing said plainly, at the value from the bug report.
            assertTrue(BookGeometry.labelRoom(List.of(0, 64), gap, 120) < 64);
        }

        @Test
        @DisplayName("columns far enough apart give the label the full cap")
        void generousColumnsAllowTheFullCap() {
            assertEquals(120, BookGeometry.labelRoom(List.of(0, 400), 4, 120));
        }

        @Test
        @DisplayName("the narrowest gap decides, and the gap either side is subtracted")
        void theNarrowestGapDecides() {
            // 0, 300, 364: the answer comes from the narrow pair and not the wide one, so a single
            // crowded pair in an otherwise airy row is what limits every label in it.
            assertEquals(56, BookGeometry.labelRoom(List.of(0, 300, 364), 4, 120));
            // With no margin the whole gap is available: 64, not 68. The first version of this test
            // said 68, which is 4 more than the arithmetic can produce and was simply mis-written.
            assertEquals(64, BookGeometry.labelRoom(List.of(0, 300, 364), 0, 120));
        }

        @Test
        @DisplayName("room below the minimum is too small to draw, and only a tiny gap gives zero")
        void roomUnderTheMinimumMeansNoLabels() {
            // Two thresholds, and they are different ones -- conflating them is what made the first
            // version of this test wrong. `labelRoom` returns zero only when a gap is no wider than the
            // two margins together (8 pixels at the usual margin). In between sits a band where the
            // room is positive and still too narrow to be worth drawing, and there the *screen* skips
            // the labels because the room is under MIN_LABEL_WIDTH.
            assertEquals(12, BookGeometry.labelRoom(List.of(0, 20), 4, 120));
            assertTrue(BookGeometry.labelRoom(List.of(0, 20), 4, 120) < BookGeometry.MIN_LABEL_WIDTH,
                    "20px apart should be too narrow to label");

            // Only these are zero: nodes all but stacked.
            assertEquals(0, BookGeometry.labelRoom(List.of(0, 8), 4, 120), "exactly the margins");
            assertEquals(0, BookGeometry.labelRoom(List.of(0, 4), 4, 120), "closer than the margins");
        }

        @Test
        @DisplayName("one column gets the full cap, and an empty list gets the same")
        void singleColumnUsesTheCap() {
            // Fewer than two columns means no neighbour to crowd, so the cap applies. That covers the
            // empty list too: there is nothing to draw, the caller iterates no quests, and the answer
            // is moot -- so it is the same branch rather than a special case with no consequences.
            assertEquals(120, BookGeometry.labelRoom(List.of(500), 4, 120));
            assertEquals(120, BookGeometry.labelRoom(List.of(), 4, 120));
        }

        @Test
        @DisplayName("hit testing picks the last drawn, which is the one on top")
        void hitTestPicksTheTopmost() {
            // Quests may legitimately share a position -- the index warns about it -- and picking the
            // first would select whichever was authored earlier rather than the one you can see.
            List<Rect> rects = List.of(Rect.at(0, 0, 40, 40), Rect.at(20, 20, 40, 40));

            assertEquals(1, BookGeometry.hitTest(rects, 30, 30), "the later rectangle is on top");
            assertEquals(0, BookGeometry.hitTest(rects, 5, 5), "only the first covers this point");
            assertEquals(-1, BookGeometry.hitTest(rects, 100, 100), "nothing there");
        }

        @Test
        @DisplayName("hit testing is exclusive at the far edge, so adjacent nodes do not both match")
        void hitTestEdgesAreExclusive() {
            Rect rect = Rect.at(10, 10, 20, 20);

            assertTrue(rect.contains(10, 10), "the top-left corner is inside");
            assertTrue(rect.contains(29, 29), "one pixel inside the far edge is inside");
            assertFalse(rect.contains(30, 30), "the far edge itself is outside");
            assertFalse(rect.contains(9, 15), "one pixel before the left edge is outside");
        }

        @Test
        @DisplayName("two rectangles sharing only an edge do not count as overlapping")
        void touchingIsNotOverlapping() {
            // What makes the overlap assertions usable: two controls drawn edge to edge are touching,
            // not colliding, and a test that called that a collision would be wrong and would have to
            // be weakened until it caught nothing.
            Rect left = Rect.at(0, 0, 10, 10);
            Rect right = Rect.at(10, 0, 10, 10);

            assertFalse(left.intersects(right));
            assertTrue(left.intersects(Rect.at(9, 0, 10, 10)), "one pixel of overlap is overlap");
        }
    }

    // ------------------------------------------------------------------
    // The reported bug, reproduced from the old arithmetic
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the old placement is rejected, so this test would have caught the bug")
    void theOldPlacementWouldFail() {
        // The old expressions, kept as arithmetic so that this test proves the sweep above is capable
        // of failing. Without it, a test that has never seen a failure is a test nobody should trust:
        // an assertion with a wrong comparison, or a loop that runs zero times, passes silently.
        //
        // These are verbatim from the first version of QuestBookScreen:
        //     Open: canvasRight() - 78,               canvasBottom() + 11
        //     Done: panelLeft() + panelWidth() - 68,  panelTop() + panelHeight() - 24
        int width = SCREENSHOT_WIDTH;
        int height = SCREENSHOT_HEIGHT;
        BookGeometry geometry = new BookGeometry(width, height);

        Rect canvas = geometry.canvas();
        Rect panel = geometry.panel();

        Rect oldOpen = Rect.at(canvas.right() - 78, canvas.bottom() + 11, 70, 18);
        Rect oldDone = Rect.at(panel.x() + panel.width() - 68, panel.y() + panel.height() - 24, 60, 18);

        assertTrue(oldOpen.intersects(oldDone),
                "the old placement should overlap, or this regression test proves nothing: Open "
                        + oldOpen + " vs Done " + oldDone);

        // And the new placement, at the same window size, does not.
        Map<String, Rect> controls = geometry.controls(4, true);
        assertFalse(controls.get("open").intersects(controls.get("done")));
        assertNotEquals(oldOpen, controls.get("open"), "Open should have moved");
        assertNotEquals(oldDone, controls.get("done"), "Done should have moved");
    }

    @Test
    @DisplayName("a control named in the map is the one the geometry helper points at")
    void theMapAndTheHelpersAgree() {
        // The screen draws text into the strip using stripButtonX/Y and creates the Open control from
        // the map. If those two disagreed, the button would be drawn in one place and clickable in
        // another -- which is the same class of bug as the original, and harder to see.
        BookGeometry geometry = new BookGeometry(SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT);
        Rect open = geometry.controls(1, true).get("open");

        assertEquals(geometry.stripButtonX(), open.x());
        assertEquals(geometry.stripButtonY(), open.y());
    }

    @Test
    @DisplayName("the same size always gives the same rectangles")
    void geometryIsDeterministic() {
        // The screen rebuilds its geometry whenever the size changes and asks for the control map on
        // every init. A map built in iteration order that varied would move controls between rebuilds,
        // which shows up as a button that occasionally cannot be clicked.
        Map<String, Rect> first = new LinkedHashMap<>(controlsAt(SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT, 3, true));
        Map<String, Rect> second = new LinkedHashMap<>(controlsAt(SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT, 3, true));

        assertEquals(first, second);
        assertEquals(List.of("chapter0", "chapter1", "chapter2", "zoomIn", "zoomOut", "centre", "done", "open"),
                List.copyOf(first.keySet()));
    }
}
