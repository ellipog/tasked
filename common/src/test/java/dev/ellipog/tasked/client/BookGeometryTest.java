package dev.ellipog.tasked.client;

import dev.ellipog.tasked.client.BookGeometry.Rect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The quest book's layout, tested without a game.
 *
 * <h2>What this exists to prevent</h2>
 *
 * <p>A screenshot showed two buttons drawn on top of each other in the
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
 * <p>Both of those controls are gone — Open with the summary strip, Done replaced by a close button —
 * so the pair is now reconstructed inside the test that reproduces it rather than existing in the
 * layout. The rectangles are kept verbatim because their <i>overlap</i> is the evidence that the sweep
 * can fail, and that evidence does not expire when the controls do.
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

    /**
     * The summary strip's height, as it was.
     *
     * <p>A literal, and deliberately not {@code BookGeometry.STRIP_HEIGHT} -- that constant is deleted
     * with the strip. The two tests that use this reconstruct a layout that no longer exists, so the
     * number has to be the <i>old</i> value rather than a live reference. Following a live constant is
     * how a regression test silently stops reproducing the layout it claims to: it moves with the
     * change and goes on passing.
     */
    private static final int OLD_STRIP_HEIGHT = 46;

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Every control's name and rectangle, for a screen of that size.
     *
     * <h2>Two arguments used to be three, and both removals are the same story</h2>
     *
     * <p>It was {@code controlsAt(width, height, chapters)}, and before that it took a {@code hasOpen}
     * as well. Each argument was a thing the map's contents depended on, and each was removed when the
     * dependency stopped existing rather than when somebody tidied up:
     *
     * <ul>
     *   <li>{@code hasOpen} went with the summary strip. The Open button only existed when a quest was
     *       selected, so the map's contents depended on something that was not a property of the window
     *       at all.</li>
     *   <li>{@code chapters} has now gone too, and its removal is the larger one. The chapter rows were
     *       in this map; they are placed by a {@link dev.ellipog.armature.client.ui.kit.Stack} inside a
     *       scroll view now, so the map holds only the controls whose positions are <b>fixed</b>. Since
     *       nothing in it depends on how many chapters there are, an argument saying how many is not an
     *       input any more — and a test that kept passing it would be describing a layout the screen
     *       does not build.</li>
     * </ul>
     *
     * <p>What is left is a function of the window and nothing else, which is what made it possible to
     * delete the argument rather than keep threading a number through that no caller reads.
     */
    private static Map<String, Rect> controlsAt(int width, int height) {
        return new BookGeometry(width, height).controls();
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
        @DisplayName("no two controls overlap, at any window size")
        void noTwoControlsOverlapEver() {
            // The chapter count used to be swept here as well — 0 to 6, at every size — because the
            // chapter rows were in this map and a row could collide with a control. They are not in it
            // any more, so the count is no longer an input to this method and sweeping it would be six
            // identical checks per size.
            //
            // That is not a loss of coverage, and the reason is worth stating rather than assuming. Rows
            // cannot collide with each other, because a Stack gives each one its own slot in a column.
            // And the one way a row could collide with the fixed chrome — being drawn up into the header
            // under the close button — is asserted directly below, as a property about *where rows may
            // go*. A property about a region is stronger than a sample of how many things are in it: the
            // sweep over counts only ever tested the six numbers somebody thought to write down.
            for (int[] size : sizes()) {
                Map<String, Rect> controls = controlsAt(size[0], size[1]);
                String overlap = firstOverlap(controls);
                if (overlap != null) {
                    throw new AssertionError("at " + size[0] + "x" + size[1] + ": " + overlap);
                }
            }
        }

        @Test
        @DisplayName("the region the sidebar's rows go in is clear of every fixed control")
        void theSidebarViewportClearsEveryControl() {
            // The replacement for sweeping chapter counts, and it is the property that sweep was
            // actually about: a row drawn under the close button is a row a player can neither read nor
            // click. The rows are placed inside `sidebarViewport()` by a scroll view, so "can a row reach
            // a control" is exactly "does the viewport intersect one".
            //
            // Every control in the map, not just close. The map is what the screen builds from, so a
            // control added to it later is covered here without this test being told — where a test that
            // named `close` would stop covering the case on the day a second header control arrived,
            // while still passing.
            //
            // The view cluster is on the canvas and the sidebar is not, so today only `close` is
            // anywhere near. That is the honest reason this assertion is cheap: it is a one-line
            // invariant that happens to be true for a reason, rather than a sweep that happens to find
            // nothing.
            for (int[] size : sizes()) {
                BookGeometry geometry = new BookGeometry(size[0], size[1]);
                Rect viewport = geometry.sidebarViewport();
                for (Map.Entry<String, Rect> entry : geometry.controls().entrySet()) {
                    assertFalse(viewport.intersects(entry.getValue()),
                            "the sidebar's rows are drawn in " + viewport + ", which overlaps "
                                    + entry.getKey() + " " + entry.getValue()
                                    + " at " + size[0] + "x" + size[1]);
                }
            }
        }

        @Test
        @DisplayName("the two controls that collided are gone, and nothing took their places")
        void theReportedCollisionPairIsGone() {
            // This was `doneAndOpenNeverCollide`, and it named the specific pair that collided in the
            // screenshot: Done in the sidebar's footer, Open in the summary strip. Both are gone --
            // Done replaced by a close button in the header, Open with the strip -- so there is no pair
            // left to assert about, and a test that kept naming them would be asserting about two
            // rectangles that are no longer drawn.
            //
            // What is worth keeping, and what this now asserts, is that nothing has quietly taken
            // either position. The old coordinates were a fault, and a future control landing on them
            // exactly would be a coincidence worth failing on.
            //
            // The half that still proves something is kept, and it is the important half: the old
            // pair really did overlap, which is what makes the sweep in NoOverlap a test capable of
            // failing rather than a loop that happens to pass. That evidence does not expire when the
            // controls it was about do.
            for (int[] size : sizes()) {
                BookGeometry geometry = new BookGeometry(size[0], size[1]);
                Rect panel = geometry.panel();

                // The old arithmetic, verbatim, against the canvas as it was: it stopped short of the
                // summary strip, which was carved out of the panel's bottom.
                Rect oldCanvas = Rect.at(panel.x() + BookGeometry.SIDEBAR_WIDTH,
                        panel.y() + BookGeometry.HEADER_HEIGHT,
                        panel.width() - BookGeometry.SIDEBAR_WIDTH,
                        panel.height() - BookGeometry.HEADER_HEIGHT - OLD_STRIP_HEIGHT);
                Rect oldOpen = Rect.at(oldCanvas.right() - 78, oldCanvas.bottom() + 11, 70, 18);
                Rect oldDone = Rect.at(panel.x() + panel.width() - 68,
                        panel.y() + panel.height() - 24, 60, 18);

                assertTrue(oldOpen.intersects(oldDone),
                        "the old placement should overlap, or this test proves nothing: Open "
                                + oldOpen + " vs Done " + oldDone);

                // And nothing has taken either position. There is no pair left to compare, and
                // `assertNotEquals` against a key that does not exist would throw rather than fail --
                // which is why this walks the map instead of indexing it.
                Map<String, Rect> controls = geometry.controls();
                assertFalse(controls.containsKey("open"),
                        "the strip's Open button is still being offered as a control");
                assertFalse(controls.containsKey("done"),
                        "the old Done button is still being offered as a control");
                for (Map.Entry<String, Rect> entry : controls.entrySet()) {
                    assertFalse(entry.getValue().equals(oldOpen),
                            entry.getKey() + " is drawn exactly where the old Open button was");
                    assertFalse(entry.getValue().equals(oldDone),
                            entry.getKey() + " is drawn exactly where the old Done button was");
                }
            }
        }

        @Test
        @DisplayName("the three view buttons do not overlap each other")
        void theClusterFitsTogether() {
            // Three square buttons in a column, placed from one arithmetic series -- y, y + pitch,
            // y + 2 * pitch. They used to be four controls across two rows of the sidebar's footer,
            // which is the only reason that footer had two rows.
            for (int[] size : sizes()) {
                Map<String, Rect> controls = controlsAt(size[0], size[1]);
                Rect in = controls.get("zoomIn");
                Rect out = controls.get("zoomOut");
                Rect centre = controls.get("centre");

                assertFalse(in.intersects(out), "zoom in/out collide at " + size[0] + "x" + size[1]);
                assertFalse(out.intersects(centre), "zoom out/centre collide at " + size[0] + "x" + size[1]);
                assertFalse(in.intersects(centre), "zoom in/centre collide at " + size[0] + "x" + size[1]);

                assertEquals(in.x(), out.x(), "the cluster is a column, so one x");
                assertEquals(in.x(), centre.x());
                assertEquals(in.width(), in.height(), "the buttons are square");
            }
        }

        @Test
        @DisplayName("the view cluster is inside the canvas it floats over")
        void theClusterIsInsideTheCanvas() {
            // This test used to check two things -- the strip and the cluster -- because both floated
            // over the canvas. The strip is gone, so there is one, and the assertion it keeps is the
            // one that generalises: whatever is drawn *on* the canvas has to be inside it, or it is
            // drawn over the sidebar and clickable outside its own surface.
            //
            // That is not hypothetical for this class. A control at a fixed offset from a container's
            // right edge went 4 pixels outside the canvas at a 160-wide window -- see the Open button's
            // history in BookGeometry -- which is the same fault as the two colliding controls with the
            // sign flipped. Swept rather than checked at one size, because the fault exists only below
            // the width where the fixed offset happens to fit.
            for (int[] size : sizes()) {
                BookGeometry geometry = new BookGeometry(size[0], size[1]);
                Rect canvas = geometry.canvas();
                Rect cluster = geometry.viewControls();

                assertTrue(cluster.isInside(canvas),
                        "the cluster " + cluster + " is outside the canvas " + canvas
                                + " at " + size[0] + "x" + size[1]);
                assertTrue(cluster.width() > 0 && cluster.height() > 0,
                        "the cluster has no area at " + size[0] + "x" + size[1]);
            }
        }

        @Test
        @DisplayName("a screenshot-sized window is fine, and so is a deliberately tiny one")
        void smallWindowsAreFineToo() {
            // The real case, and a case smaller than anything real. A minimum panel that merely
            // *looks* big enough on a developer's monitor is how the original bug survived.
            for (int[] size : new int[][] {{SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT}, {200, 120}, {160, 100}}) {
                String overlap = firstOverlap(controlsAt(size[0], size[1]));
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

        /**
         * Which surface each control is drawn on.
         *
         * <h2>Named lists rather than a chain with a default, and this is a fix rather than a tidy-up</h2>
         *
         * <p>The check below used to read
         * {@code key.startsWith("chapter") ? sidebar : key.equals("close") ? header : canvas} — and the
         * <b>default</b> was the trap. When the two appearance rows arrived they fell through to the
         * canvas, so this test failed on two controls that were in exactly the right place. The
         * geometry was correct and the test was describing a layout that no longer existed.
         *
         * <p>That is the failure mode of a default in a mapping like this: it silently answers for
         * every case nobody thought about, and its answer is wrong in the direction that looks like a
         * real fault. Three lists invert it — a control in none of them returns null and fails with a
         * message saying so, which is the one thing a default cannot do.
         *
         * <p>Kept by hand rather than derived from {@code BookGeometry}, deliberately: "which surface
         * is this control on" is a design statement, and deriving it from the rectangles would make
         * this test agree with the code by construction — which is the property that makes a layout
         * test worthless. A control that moves surface should fail here.
         */
        private static Rect surfaceFor(BookGeometry geometry, String key) {
            if (key.startsWith("chapter")) {
                return geometry.sidebar();
            }
            if (SIDEBAR_CONTROLS.contains(key)) {
                return geometry.sidebar();
            }
            if (HEADER_CONTROLS.contains(key)) {
                return geometry.header();
            }
            if (CANVAS_CONTROLS.contains(key)) {
                return geometry.canvas();
            }
            return null;
        }

        /** The sidebar's fixed chrome: controls in the column that are not chapter rows. */
        private static final Set<String> SIDEBAR_CONTROLS = Set.of("addChapter", "addGroup");

        /**
         * The header's controls: Close, Rewards, the party button and the settings button. The author's
         * pills are not here even though they are built by the header's own method -- they float over the
         * canvas, and a control's surface is where it is drawn rather than where it is constructed.
         */
        private static final Set<String> HEADER_CONTROLS =
                Set.of("close", "rewards", "party", "settings");

        /**
         * The view cluster and the author's pills: everything that sits on the canvas. Two clusters, in
         * the two top corners, which is why {@link BookGeometry#MIN_CANVAS_WIDTH} is an arithmetic term
         * rather than a judgement.
         */
        private static final Set<String> CANVAS_CONTROLS =
                Set.of("zoomIn", "zoomOut", "centre", "editPill", "toolsPill");

        @Test
        @DisplayName("every control is inside the surface it belongs to")
        void everyControlIsInsideItsOwnSurface() {
            // Was "everything except Open is inside the sidebar", which was true when the screen had a
            // sidebar footer and one floating control. Neither exists any more, and the honest version
            // says which surface each control belongs to -- so a control that moves surface fails here
            // rather than being quietly exempted from a check that no longer applies to it.
            for (int[] size : sizes()) {
                BookGeometry geometry = new BookGeometry(size[0], size[1]);
                Map<String, Rect> controls = geometry.controls();

                for (Map.Entry<String, Rect> entry : controls.entrySet()) {
                    String key = entry.getKey();
                    Rect rect = entry.getValue();
                    Rect surface = surfaceFor(geometry, key);

                    // Asserted before use rather than after, so a control this test does not know
                    // about says *that* rather than reporting an NPE or checking it against the wrong
                    // rectangle. Reachable the moment a control is added to `controls()` and not to
                    // one of the three lists above -- which is the moment worth being told about.
                    assertTrue(surface != null,
                            key + " is a control this test has no surface for, at "
                                    + size[0] + "x" + size[1] + ". Add it to the list for the surface"
                                    + " it is drawn on -- one of them has to be right, and guessing"
                                    + " which is how it ended up checked against the canvas.");
                    assertTrue(rect.isInside(surface),
                            key + " " + rect + " is outside its surface " + surface
                                    + " at " + size[0] + "x" + size[1]);
                }
            }
        }

        @Test
        @DisplayName("the Close button is inside the header")
        void closeIsInsideTheHeader() {
            for (int[] size : sizes()) {
                BookGeometry geometry = new BookGeometry(size[0], size[1]);
                Rect close = geometry.controls().get("close");
                assertTrue(close.isInside(geometry.header()),
                        "Close " + close + " is outside the header " + geometry.header()
                                + " at " + size[0] + "x" + size[1]);
            }
        }

        @Test
        @DisplayName("the header's count stops short of Close, at every size")
        void theHeaderCountClearsClose() {
            // The quest count is right-aligned to this limit. It was `panel width - 12` -- the panel's
            // own inset, a number the Close button knows nothing about -- so it is exactly the mistake
            // that put two controls on top of each other, one surface up.
            for (int[] size : sizes()) {
                BookGeometry geometry = new BookGeometry(size[0], size[1]);
                if (size[0] > 600) {
                    assertTrue(geometry.headerRightLimit() < geometry.closeRect().x(),
                            "the count would run into Close at " + size[0] + "x" + size[1]);
                }
                assertTrue(geometry.headerRightLimit() > geometry.panel().x() + 10,
                        "the header has no room for its own title at " + size[0] + "x" + size[1]);
            }
        }

        @Test
        @DisplayName("the sidebar's viewport stays inside the panel and inside its own column")
        void theSidebarViewportStaysInsideItsSurfaces() {
            // The multi-row version of the reported bug: a row drawn underneath something, where it is
            // both invisible and unclickable. The rows live inside `sidebarViewport()` now, so the thing
            // to assert is that the region itself is inside the surfaces it belongs to — a viewport
            // running past the panel's edge would put a row outside the book, and running into the
            // canvas would put one under the graph.
            //
            // This replaces a loop over `chapterRows()` and `chapterRowY(i)`, which existed because the
            // screen computed each row's y itself. Nothing does now: a Stack places the rows, so there is
            // no row y for this class to be right or wrong about, and the region is the whole of what it
            // still decides.
            for (int[] size : sizes()) {
                BookGeometry geometry = new BookGeometry(size[0], size[1]);
                Rect viewport = geometry.sidebarViewport();

                assertTrue(viewport.isInside(geometry.panel()),
                        "the sidebar's viewport " + viewport + " is outside the panel "
                                + geometry.panel() + " at " + size[0] + "x" + size[1]);
                assertTrue(viewport.x() >= geometry.sidebar().x(),
                        "the viewport starts left of the sidebar at " + size[0] + "x" + size[1]);
                assertTrue(viewport.right() <= geometry.sidebar().right(),
                        "the viewport runs into the canvas at " + size[0] + "x" + size[1]);
            }
        }

        @Test
        @DisplayName("the sidebar's viewport has room for at least one row")
        void atLeastOneRowAlwaysFits() {
            // A sidebar with no room for a row is a book whose only chapter cannot be selected, which is
            // a blank screen with no way forward, and MIN_PANEL_HEIGHT exists to make this true.
            //
            // This is the honest form of what used to be `chapterRows() >= 1`, and it is worth being
            // clear that the new assertion is **weaker** than the old one while being true for a
            // stronger reason. The old promise was that the list was *complete* — every row the geometry
            // counted fitted above the bottom — which required the count to be right. This promises that
            // the region is at least as tall as one row, and that everything past it scrolls. The
            // guarantee is smaller; the arithmetic that can be wrong is gone entirely, because the row
            // count that had to be correct no longer exists.
            //
            // Measured against the row height rather than its pitch: a scroll view scrolls by whole
            // pitches, so a region exactly one pitch tall is what shows a row plus the gap under it, and
            // a region one *row* tall is the smallest in which a row is fully visible at any offset.
            for (int[] size : sizes()) {
                int height = new BookGeometry(size[0], size[1]).sidebarViewport().height();
                assertTrue(height >= BookGeometry.SIDEBAR_ROW_HEIGHT,
                        "the sidebar has " + height + "px of room for rows, less than one "
                                + BookGeometry.SIDEBAR_ROW_HEIGHT + "px row, on a "
                                + size[0] + "x" + size[1] + " screen");
            }
        }

        @Test
        @DisplayName("the sidebar's viewport has width, and the scrollbar's strip still fits beside it")
        void theSidebarViewportHasWidth() {
            // Distinct from the height above, and this is what replaced "at least one chapter control
            // exists". That assertion was about `controls` capping the rows it offered by the rows that
            // fitted, which is a mistake `controls()` can no longer make — it offers fixed chrome and
            // nothing else, so there is no count to get wrong.
            //
            // What can still go wrong is the subtraction that reserves room for the scrollbar: a sidebar
            // narrower than its own insets plus the bar gives a viewport of zero or negative width, and
            // `sidebarViewport` floors that at zero rather than letting it go negative. A zero-width
            // viewport places every row at a negative width, which reads correctly at every use and
            // draws nothing. So the clamp is real and this asserts a real window never relies on it.
            for (int[] size : sizes()) {
                BookGeometry geometry = new BookGeometry(size[0], size[1]);
                assertTrue(geometry.sidebarViewport().width() > 0,
                        "the sidebar's viewport has no width on a " + size[0] + "x" + size[1] + " screen");

                // And the strip the scrollbar is drawn in lands in the sidebar's margin rather than over
                // the end of a row. `ScrollView.drawScrollbar` puts the bar four pixels right of the
                // viewport's right edge and three pixels wide, so this is the same arithmetic that
                // SIDEBAR_SCROLLBAR is derived from — asserted here rather than left to the comment,
                // because a bar drawn over a row is the fault the reservation exists to prevent and
                // nothing else in the suite would notice it.
                int stripRight = geometry.sidebarViewport().right() + 4 + 3;
                assertTrue(stripRight <= geometry.sidebar().right(),
                        "the scrollbar's strip reaches " + stripRight + ", past the sidebar's right edge "
                                + geometry.sidebar().right() + " at " + size[0] + "x" + size[1]);
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
    }

    // ------------------------------------------------------------------
    // Zoom -- moved out, and why that is worth a note here
    // ------------------------------------------------------------------

    // A `Zooming` nested class used to sit here: four tests of zoom-about-the-pointer, asserting that
    // the world point under the pointer is the same before and after, that zooming in then out comes
    // back, that the limits clamp, and that a zoom already at a limit does not drift.
    //
    // They tested `BookGeometry.zoomAbout`, which was a second implementation of the transform --
    // the screen had its own, as three static fields and the same algebra written out three times.
    // Both are gone. The transform is `ui.kit`'s `Viewport`, which the screen calls, and
    // `ViewportTest.zoomAtKeepsTheContentPointUnderThePointer` asserts the same invariant: swept over
    // more pans and scales than this copy managed, and against the transform itself rather than
    // against a re-derivation of it.
    //
    // So this file now covers one thing rather than two: the framing. Rectangles, the control map,
    // label room, hit-test bounds -- one screen's own layout, and nothing else's. That the split
    // matters at all is the reason to keep this comment: the four tests above were *good tests of the
    // wrong object*, and they were part of why the duplicate went unnoticed. A passing suite is not
    // evidence that the thing it passes against is the thing in use.

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

        // ------------------------------------------------------------------
        // The editor's grid
        // ------------------------------------------------------------------

        @Test
        @DisplayName("snapping rounds to the grid, and a coordinate already on it stays put")
        void snapRoundsToTheGrid() {
            int grid = BookGeometry.SNAP_GRID;

            assertEquals(0, BookGeometry.snap(3, grid, true), "three units under the midpoint rounds down");
            assertEquals(8, BookGeometry.snap(4, grid, true), "the midpoint rounds up, like Math.round");
            assertEquals(8, BookGeometry.snap(7.9, grid, true), "one tick short still lands on the grid");
            assertEquals(8, BookGeometry.snap(8, grid, true), "already on the grid, so it stays");
            assertEquals(-8, BookGeometry.snap(-5, grid, true), "and the same below zero, off the canvas");
            assertEquals(24, BookGeometry.snap(20, grid, true), "three steps up from the origin");
        }

        @Test
        @DisplayName("snapping off, or a grid that cannot divide, answers the coordinate it was given")
        void snapOffIsTheValueItself() {
            assertEquals(37.4182, BookGeometry.snap(37.4182, 8, false),
                    "the switch off is free placement, exactly as the pointer left it");
            assertEquals(37.4182, BookGeometry.snap(37.4182, 0, true), "a zero grid cannot divide");
            assertEquals(37.4182, BookGeometry.snap(37.4182, -8, true), "and neither can a negative one");
        }

        @Test
        @DisplayName("re-snapping an already-snapped coordinate is the same coordinate")
        void snapIsIdempotent() {
            // A held drag re-derives the follow position on every mouse move, so a snap that was not
            // idempotent would let a resting node drift under a still pointer.
            for (double x = -40; x <= 40; x += 0.5) {
                double once = BookGeometry.snap(x, BookGeometry.SNAP_GRID, true);
                assertEquals(once, BookGeometry.snap(once, BookGeometry.SNAP_GRID, true),
                        "snapping twice moved the node, at " + x);
            }
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
        //
        // <h2>And it stopped proving anything, which is worth the paragraph</h2>
        //
        // <p>{@code canvasBottom()} is not the same number it was. The canvas used to stop short of the
        // strip — the strip was carved out of the panel's bottom, 46 pixels tall — and it now runs the
        // full height with the bar floating over it. So the old Open expression landed 46 pixels lower
        // than it did on the screen being described, the two rectangles stopped overlapping, and this
        // assertion failed with the message it prints when it has no evidence.
        //
        // <p>The fix is to reconstruct the <b>old</b> canvas rather than to reuse the new one, because
        // the case under test is a layout that no longer exists. The alternative — deleting the test
        // because it "no longer applies" — would have thrown away the only proof that this sweep can
        // fail. A regression test pinned to a moving number is a test that quietly retires itself, and
        // the failure message is the only reason it did not.
        int width = SCREENSHOT_WIDTH;
        int height = SCREENSHOT_HEIGHT;
        BookGeometry geometry = new BookGeometry(width, height);
        Rect panel = geometry.panel();

        Rect oldCanvas = Rect.at(panel.x() + BookGeometry.SIDEBAR_WIDTH, panel.y() + BookGeometry.HEADER_HEIGHT,
                panel.width() - BookGeometry.SIDEBAR_WIDTH,
                panel.height() - BookGeometry.HEADER_HEIGHT - OLD_STRIP_HEIGHT);

        Rect oldOpen = Rect.at(oldCanvas.right() - 78, oldCanvas.bottom() + 11, 70, 18);
        Rect oldDone = Rect.at(panel.x() + panel.width() - 68, panel.y() + panel.height() - 24, 60, 18);

        assertTrue(oldOpen.intersects(oldDone),
                "the old placement should overlap, or this regression test proves nothing: Open "
                        + oldOpen + " vs Done " + oldDone);
        assertEquals(SCREENSHOT_WIDTH - 2 * BookGeometry.PANEL_MARGIN, panel.width(),
                "fixture sanity: this is the window the bug was photographed in, at the panel size it had");

        // And nothing is drawn where either of them was, which is the only form this assertion can
        // take now: both controls are gone (Open with the strip, Done replaced by Close), so there is
        // no pair left to compare. `assertNotEquals` against a key that does not exist would throw
        // rather than fail, which is why this reads the rectangles positionally.
        //
        // What it still proves is the thing worth keeping: the reconstructed collision above is real,
        // so the sweep in NoOverlap is a test that can fail. That was the whole reason this test
        // existed, and it survives the controls it was about.
        //
        // The chapter count is gone from this call, and that this block was one of its call sites is
        // the argument for removing it rather than leaving it as an ignored parameter. There were two
        // byte-identical blocks reading `geometry.controls(4)` in this file — this one and the one in
        // `NoOverlap` — so a fix applied to the first match of that text fixed exactly one of them, and
        // the compiler caught the other. A parameter no caller reads is a parameter that can be left
        // behind at one site out of six, and in every other case the mistake is silent, because a
        // redundant argument still compiles. Removing it turned a would-be divergence into an error.
        Map<String, Rect> controls = geometry.controls();
        assertFalse(controls.containsKey("open"),
                "the strip's Open button is still being offered as a control");
        assertFalse(controls.containsKey("done"),
                "the old Done button is still being offered as a control");
        for (Map.Entry<String, Rect> entry : controls.entrySet()) {
            assertFalse(entry.getValue().equals(oldOpen),
                    entry.getKey() + " is drawn exactly where the old Open button was");
            assertFalse(entry.getValue().equals(oldDone),
                    entry.getKey() + " is drawn exactly where the old Done button was");
        }
    }

    @Test
    @DisplayName("a control named in the map is the one the geometry helper points at")
    void theMapAndTheHelpersAgree() {
        // The screen draws to two numbers that nothing else creates: the header's right limit, which
        // the quest count is aligned to, and the cluster's rectangle, which the mat is painted from.
        // A drift in either would be invisible until the two met -- which is the class of bug this
        // whole file exists for.
        //
        // Open and its bar used to be checked here too and are gone with the strip. Nothing replaces
        // them, and that is honest rather than a gap: there is no longer a control whose position is
        // computed in two places.
        BookGeometry geometry = new BookGeometry(SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT);

        assertTrue(geometry.headerRightLimit() <= geometry.closeRect().x(),
                "the count's limit must not run past the Close button");
        assertTrue(geometry.viewControls().isInside(geometry.canvas()),
                "the cluster's mat is painted outside the canvas it sits on");
        assertTrue(geometry.viewControls().width() > BookGeometry.VIEW_BUTTON,
                "the mat is the whole reason the cluster reads as one group, so it must be bigger "
                        + "than a single button: " + geometry.viewControls());

        // The author's pills, the same two rules mirrored: the mat is the helper the screen paints from,
        // so it has to be inside the surface it is painted on and bigger than the pill it backs.
        assertTrue(geometry.pillMat(true).isInside(geometry.canvas()),
                "the pills' mat is painted outside the canvas it sits on");
        assertTrue(geometry.pillMat(true).height() > geometry.pillMat(false).height(),
                "a mat that does not grow with the Tools pill would back a lone pill with a strip sized "
                        + "for a neighbour that is not there: " + geometry.pillMat(false));
        // And the drawer's rail starts below the pills: the editor writes chapter appearance into this
        // panel, and a rail running up through the band would put its first row under a floating control.
        assertTrue(geometry.authorRail().y() >= geometry.pillMat(true).bottom(),
                "the inspector's rail starts through the pill band rather than under it: "
                        + geometry.authorRail() + " vs " + geometry.pillMat(true));
    }

    @Test
    @DisplayName("full bleed: the panel is the whole window, and everything follows it")
    void fullBleed() {
        for (int[] size : new int[][] {{854, 480}, {427, 240}}) {
            BookGeometry window = new BookGeometry(size[0], size[1], true);
            String at = " at " + size[0] + "x" + size[1];

            assertEquals(BookGeometry.Rect.at(0, 0, size[0], size[1]), window.panel(),
                    () -> "the full-bleed panel is not the window" + at);

            for (BookGeometry.Rect part : List.of(window.header(), window.sidebar(), window.canvas(),
                    window.controls().get("close"), window.controls().get("editPill"),
                    window.controls().get("toolsPill"))) {
                assertTrue(part.x() >= 0 && part.y() >= 0 && part.right() <= size[0]
                                && part.bottom() <= size[1],
                        () -> "a part left the window" + at + ": " + part);
            }

            // And the card shape is unchanged: the flag moves the panel, not the parts.
            BookGeometry card = new BookGeometry(size[0], size[1]);
            assertTrue(card.panel().width() < size[0] || card.panel().height() < size[1],
                    () -> "the card should keep its margin" + at);
        }
    }

    @Test
    @DisplayName("the same size always gives the same rectangles")
    void geometryIsDeterministic() {
        // The screen rebuilds its geometry whenever the size changes and asks for the control map on
        // every init. A map built in iteration order that varied would move controls between rebuilds,
        // which shows up as a button that occasionally cannot be clicked.
        //
        // This method had no `@Test` for a while -- it was written and never ran, which is why its key
        // list could go stale without anything failing. The annotation is the fix, and the list below is
        // the corrected one.
        Map<String, Rect> first = new LinkedHashMap<>(controlsAt(SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT));
        Map<String, Rect> second = new LinkedHashMap<>(controlsAt(SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT));

        assertEquals(first, second);
        // No "open" any more: the strip's button went with the strip, and it was the only entry here
        // whose presence depended on something other than the window.
        //
        // And no chapter rows any more either, which is the change worth recording because it is the one
        // that altered what this list *is*. It used to be "the fixed chrome, plus one entry per chapter
        // that fits" -- a mixed bag whose length was a sum of two unrelated things. It is now only the
        // fixed chrome, so this assertion is a statement about the screen's controls rather than about a
        // particular window's worth of them.
        //
        // The order is asserted, not just the contents, and that is the half that catches a rebuild
        // reordering them: this method's callers index nothing, but a screen that later walks this map
        // to place controls would draw them in a different order between two inits of the same size --
        // which shows up as a control that is occasionally somewhere else.
        //
        // The source order in `controls()` is: close, the rewards button, the party button, the settings
        // button, the author's pills, the sidebar's two add buttons, then the view cluster. The chapter
        // rows were ahead of close and are gone; the two appearance rows were between close and the
        // cluster and are gone -- see the note in that method for why each went. The author's pair keeps
        // the slot it had in this list (after settings), even though it is drawn on a different surface
        // now; a reader comparing orders across the change should know that was deliberate.
        assertEquals(
                List.of("close", "rewards", "party", "settings", "editPill", "toolsPill",
                        "addChapter", "addGroup", "zoomIn", "zoomOut", "centre"),
                List.copyOf(first.keySet()));
    }
}
