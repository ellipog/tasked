package dev.ellipog.tasked.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where everything on the quest book screen goes — and nothing else.
 *
 * <h2>Why this is a separate class with no Minecraft in it</h2>
 *
 * <p>Because the bug that produced it could not be tested. Two controls were drawn on top of each
 * other in the bottom-right corner, and the cause was that "near the bottom right" had been written
 * <b>twice</b>, as two expressions that were equal only by coincidence:
 *
 * <pre>
 * Open: canvasRight() - 78,               canvasBottom() + 11
 * Done: panelLeft() + panelWidth() - 68,  panelTop() + panelHeight() - 24
 * </pre>
 *
 * <p>{@code canvasRight() == panelLeft() + panelWidth()}, so the two x values were ten pixels apart
 * and the two y values about fifteen. The fix was to compute each shared position once — but that
 * fix lived inside {@code QuestBookScreen}, which extends {@code Screen} and needs a running
 * Minecraft to instantiate. So the property "these controls do not overlap" was still not testable,
 * and a future edit to those private helpers would have re-broken it with nothing to notice.
 *
 * <p>This class holds only that arithmetic: rectangles, and integers. No {@code GuiGraphics}, no
 * {@code Minecraft}, no rendering. It loads and runs in a plain JVM, so
 * {@code BookGeometryTest} can assert the properties that matter —
 * <b>no two controls overlap, and every control is inside the surface it belongs to</b> — across a
 * sweep of window sizes. That is a test of the exact failure that was reported.
 *
 * <h2>Screens are smaller than you think</h2>
 *
 * <p>Minecraft's GUI space is the window divided by the GUI scale, and an auto scale on a small
 * window gives remarkably little of it: the screenshot this was debugged from was
 * <b>427 × 240</b> GUI pixels. At that size the panel is 387 × 200. Anything that assumes 800 × 480
 * works on the developer's monitor and breaks on a laptop.
 *
 * <p>So the panel has a minimum size that can physically hold its own chrome — a header, one chapter
 * row, and two footer rows — and below that minimum the panel is allowed to run off the edge of the
 * screen rather than overlapping itself. Off-screen is clipped and obviously wrong; overlapping
 * controls look like a rendering fault and are not obviously anything.
 */
public final class BookGeometry {

    // ------------------------------------------------------------------
    // Dimensions
    // ------------------------------------------------------------------

    /** The chapter list down the left. */
    public static final int SIDEBAR_WIDTH = 132;

    /** The title bar across the top of the panel. */
    public static final int HEADER_HEIGHT = 26;

    /** The selected quest's summary, under the canvas. */
    public static final int STRIP_HEIGHT = 46;

    /** A control's height. One number, so a row of them lines up. */
    public static final int ROW_HEIGHT = 18;

    /** Between the two footer rows. */
    public static final int ROW_GAP = 4;

    /** Between the panel's edge and the controls inside it. */
    public static final int EDGE = 8;

    /** The strip's Open button, and so what the strip's text has to stop short of. */
    public static final int OPEN_WIDTH = 72;

    /** The vertical pitch of a chapter row. Also its height plus its gap. */
    public static final int CHAPTER_ROW_PITCH = 22;

    /** Between the last chapter row and the first footer row. */
    public static final int CHAPTER_GAP = 6;

    /** The gap inside the full-screen overlay, between its edge and its content. */
    public static final int OVERLAY_MARGIN = 24;

    /**
     * The smallest panel that can hold its own chrome: a header, one chapter row, and two footer
     * rows, with the gaps between them.
     *
     * <p>Derived rather than written out, so that changing {@link #ROW_HEIGHT} cannot silently
     * invalidate it. If this is smaller than the sum of the parts, the chapter list and the footer
     * are drawn in the same place — which is the reported bug, one dimension over.
     */
    public static final int MIN_PANEL_HEIGHT = HEADER_HEIGHT + CHAPTER_GAP + ROW_HEIGHT
            + CHAPTER_GAP + ROW_HEIGHT + ROW_GAP + ROW_HEIGHT + EDGE;

    /** Enough canvas to be worth showing beside the sidebar. */
    public static final int MIN_CANVAS_WIDTH = 80;

    public static final int MIN_PANEL_WIDTH = SIDEBAR_WIDTH + MIN_CANVAS_WIDTH;

    /** The largest the panel gets, however big the window is. */
    public static final int MAX_PANEL_WIDTH = 800;

    public static final int MAX_PANEL_HEIGHT = 480;

    /** How far the panel is inset from the window's edge, when the window is big enough for that. */
    public static final int PANEL_MARGIN = 20;

    // --- the full-screen overlay ---------------------------------------------

    /**
     * The height of a control in the overlay's footer. Taller than a sidebar row: the overlay is the
     * screen where a player hands something in, so its controls are the primary thing on it.
     */
    public static final int OVERLAY_CONTROL_HEIGHT = 20;

    /**
     * Submit's width, and Back's.
     *
     * <p>Named rather than written at the call site because the screen used to pass <b>130</b> here
     * while this class said <b>120</b>. That is the whole reason this class exists: two descriptions of
     * one control, which agree until somebody edits one. The overlap test would have passed on 120
     * while the screen drew 130 and ran into Back — a green test guarding a layout that was not on
     * screen, which is worse than no test, because it would have been believed.
     */
    public static final int SUBMIT_WIDTH = 130;

    public static final int BACK_WIDTH = 88;

    // --- labels --------------------------------------------------------------

    /** A node label is never wider than this, however much room there is. */
    public static final int MAX_LABEL_WIDTH = 120;

    /** Below this there is no room for a readable label, so none are drawn. */
    public static final int MIN_LABEL_WIDTH = 30;

    /** Kept between a label and the node beside it. */
    public static final int LABEL_GAP = 4;

    // ------------------------------------------------------------------
    // A rectangle, and the three questions anything asks of one
    // ------------------------------------------------------------------

    /**
     * A rectangle in GUI coordinates.
     *
     * <p>{@link #intersects} is <b>exclusive</b> at the edges: two rectangles that share only an edge
     * do not intersect. That is what makes it usable for "do these two controls collide" — a pair
     * drawn edge to edge is touching, not overlapping, and reporting it as a collision would make the
     * test useless.
     */
    public record Rect(int x, int y, int width, int height) {

        public static Rect at(int x, int y, int width, int height) {
            return new Rect(x, y, width, height);
        }

        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }

        public boolean intersects(Rect other) {
            return x < other.right() && other.x < right()
                    && y < other.bottom() && other.y < bottom();
        }

        /**
         * Whether a point is inside. {@code double} because the mouse coordinates are, and a
         * {@code double} silently truncated to an int puts the pointer one pixel off at a boundary.
         */
        public boolean contains(double px, double py) {
            return px >= x && px < right() && py >= y && py < bottom();
        }

        /** Whether this rectangle is wholly inside {@code other}. */
        public boolean isInside(Rect other) {
            return x >= other.x && y >= other.y && right() <= other.right() && bottom() <= other.bottom();
        }

        @Override
        public String toString() {
            return x + "," + y + " " + width + "x" + height;
        }
    }

    // ------------------------------------------------------------------
    // The screen this was built for
    // ------------------------------------------------------------------

    private final int screenWidth;
    private final int screenHeight;
    private final Rect panel;
    private final Rect canvas;
    private final Rect sidebar;
    private final Rect strip;
    private final Rect overlay;

    public BookGeometry(int screenWidth, int screenHeight) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;

        // Clamped to a minimum, and allowed to exceed the window below it. A window smaller than this
        // cannot show the book sensibly at any layout, so the choice is between a panel that runs off
        // the edge -- which is clipped, and obviously the window being too small -- and a panel whose
        // own contents overlap, which looks like a bug in the drawing code.
        int panelWidth = clamp(Math.min(MAX_PANEL_WIDTH, screenWidth - PANEL_MARGIN * 2),
                MIN_PANEL_WIDTH, MAX_PANEL_WIDTH);
        int panelHeight = clamp(Math.min(MAX_PANEL_HEIGHT, screenHeight - PANEL_MARGIN * 2),
                MIN_PANEL_HEIGHT, MAX_PANEL_HEIGHT);

        // Integer division, so a panel of odd width sits one pixel further left than right rather
        // than leaving a half pixel. Every other rectangle is derived from these two, so an odd
        // panel width is a one-pixel asymmetry everywhere and never a rounding drift.
        int panelLeft = (screenWidth - panelWidth) / 2;
        int panelTop = (screenHeight - panelHeight) / 2;

        this.panel = Rect.at(panelLeft, panelTop, panelWidth, panelHeight);
        this.canvas = Rect.at(panelLeft + SIDEBAR_WIDTH, panelTop + HEADER_HEIGHT,
                panelWidth - SIDEBAR_WIDTH, panelHeight - HEADER_HEIGHT - STRIP_HEIGHT);
        this.sidebar = Rect.at(panelLeft, panelTop + HEADER_HEIGHT, SIDEBAR_WIDTH,
                panelHeight - HEADER_HEIGHT);
        this.strip = Rect.at(canvas.x(), canvas.bottom(), canvas.width(), STRIP_HEIGHT);
        this.overlay = Rect.at(OVERLAY_MARGIN, OVERLAY_MARGIN,
                Math.max(MIN_PANEL_WIDTH, screenWidth - OVERLAY_MARGIN * 2),
                Math.max(MIN_PANEL_HEIGHT, screenHeight - OVERLAY_MARGIN * 2));
    }

    public int screenWidth() {
        return screenWidth;
    }

    public int screenHeight() {
        return screenHeight;
    }

    /** The whole book. */
    public Rect panel() {
        return panel;
    }

    /** The graph, inside the panel. */
    public Rect canvas() {
        return canvas;
    }

    /** The chapter list, inside the panel. */
    public Rect sidebar() {
        return sidebar;
    }

    /** The selected quest's summary, under the canvas. */
    public Rect strip() {
        return strip;
    }

    /** The full-screen quest view. */
    public Rect overlay() {
        return overlay;
    }

    // ------------------------------------------------------------------
    // The positions that the drawing and the controls both need
    // ------------------------------------------------------------------

    /** The y of the lower footer row, which holds Done. */
    public int footerRow2Y() {
        return panel.bottom() - EDGE - ROW_HEIGHT;
    }

    /** The y of the upper footer row, which holds the zoom controls. */
    public int footerRow1Y() {
        return footerRow2Y() - ROW_GAP - ROW_HEIGHT;
    }

    /** Where the chapter list starts. */
    public int chapterListTop() {
        return panel.y() + HEADER_HEIGHT + CHAPTER_GAP;
    }

    /**
     * How many chapter rows fit above the footer.
     *
     * <p>At least one, always — a chapter list with no rows is a list a player cannot use, and a
     * book whose only chapter cannot be selected is a blank screen. {@link #MIN_PANEL_HEIGHT}
     * guarantees the row fits.
     */
    public int chapterRows() {
        return Math.max(1, (footerRow1Y() - CHAPTER_GAP - chapterListTop()) / CHAPTER_ROW_PITCH);
    }

    /** The y of chapter row {@code index}. Not clamped; callers check {@link #chapterRows()}. */
    public int chapterRowY(int index) {
        return chapterListTop() + index * CHAPTER_ROW_PITCH;
    }

    /** The x of the strip's Open button, against the panel's right edge. */
    public int stripButtonX() {
        return canvas.right() - EDGE - OPEN_WIDTH;
    }

    /** The y of the strip's Open button, centred in the strip. */
    public int stripButtonY() {
        return strip.y() + (STRIP_HEIGHT - ROW_HEIGHT) / 2;
    }

    /** Where the strip's text has to stop, so a long title does not run under the Open button. */
    public int stripTextLimit() {
        return stripButtonX() - (canvas.x() + 10) - 10;
    }

    /** The width available inside the sidebar, between its two edges. */
    private int sidebarInner() {
        return SIDEBAR_WIDTH - EDGE * 2;
    }

    // ------------------------------------------------------------------
    // The controls
    // ------------------------------------------------------------------

    /**
     * Every control on the book screen, by name.
     *
     * <p>This is the method the screen and the test share, and that sharing is the whole point. The
     * screen creates its widgets from these rectangles; the test asserts they do not overlap. A
     * layout change therefore cannot move a control without the test seeing it.
     *
     * @param chapters how many chapters there are to show
     * @param hasOpen  whether a quest is selected, which is what the strip's Open button needs
     */
    public Map<String, Rect> controls(int chapters, boolean hasOpen) {
        Map<String, Rect> out = new LinkedHashMap<>();
        int left = panel.x() + EDGE;
        int inner = sidebarInner();

        // The chapter rows, top down, only as many as fit. Naming them chapter0, chapter1 ... rather
        // than a list, because a test that fails should say which control collided.
        int rows = Math.min(Math.max(0, chapters), chapterRows());
        for (int i = 0; i < rows; i++) {
            out.put("chapter" + i, Rect.at(left, chapterRowY(i), inner, ROW_HEIGHT));
        }

        // The footer's upper row: three controls that add up to the sidebar's inner width exactly.
        // 30 + 4 + 30 + 4 + (inner - 68) == inner, so the row is flush at both ends -- derived rather
        // than three numbers that happen to add up today.
        out.put("zoomIn", Rect.at(left, footerRow1Y(), 30, ROW_HEIGHT));
        out.put("zoomOut", Rect.at(left + 34, footerRow1Y(), 30, ROW_HEIGHT));
        out.put("centre", Rect.at(left + 68, footerRow1Y(), inner - 68, ROW_HEIGHT));

        // And the lower row, which is why there are two rows: four controls do not fit across 116px.
        out.put("done", Rect.at(left, footerRow2Y(), inner, ROW_HEIGHT));

        if (hasOpen) {
            out.put("open", Rect.at(stripButtonX(), stripButtonY(), OPEN_WIDTH, ROW_HEIGHT));
        }
        return out;
    }

    /**
     * Every control on the full-screen overlay, by name.
     *
     * <p>Stacked when the overlay is too narrow for both side by side — rather than letting Submit
     * and Back collide on a small window, which is the same bug in a third place. A narrow window is
     * a real case here: the overlay is only {@code screenWidth - 48} wide, so a 427-wide window gives
     * it 379 and the two fit; a 300-wide one gives 252 and they would not.
     */
    public Map<String, Rect> overlayControls(boolean hasSubmit) {
        Map<String, Rect> out = new LinkedHashMap<>();
        Rect box = overlay;
        int height = OVERLAY_CONTROL_HEIGHT;
        int rowY = box.bottom() - EDGE - height;

        Rect back = Rect.at(box.right() - EDGE - height / 2 - BACK_WIDTH, rowY, BACK_WIDTH, height);

        if (hasSubmit) {
            Rect submit = Rect.at(box.x() + EDGE + 8, rowY, SUBMIT_WIDTH, height);
            if (submit.intersects(back)) {
                // Not enough room on one row. Back moves up by its own height plus the usual gap,
                // keeping its right alignment, and Submit keeps the bottom-left corner it is read from
                // -- after the tasks and rewards, which is where the overlay's text ends.
                back = Rect.at(back.x(), rowY - height - ROW_GAP, BACK_WIDTH, height);
            }
            out.put("submit", submit);
        }

        out.put("back", back);
        return out;
    }

    // ------------------------------------------------------------------
    // Pure arithmetic the screen also uses
    // ------------------------------------------------------------------

    /** A pan and zoom. */
    public record Zoom(int panX, int panY, float zoom) {
    }

    /**
     * Applies a zoom factor while keeping the world point under the pointer fixed — the whole of
     * "scroll to zoom about the cursor".
     *
     * <p>Convert the pointer to a world coordinate, zoom, and solve for the pan that puts that same
     * world coordinate back under the pointer. Two lines of algebra, and getting them wrong is what
     * makes a zoom appear to run away from the cursor — the most common complaint about a graph UI.
     *
     * <p>Here rather than in the screen so the invariant itself can be asserted: after this call,
     * the distance between the pointer and the world point it was over is zero to within the
     * rounding of an integer pan. That is a property of these numbers alone and needs no renderer.
     */
    public static Zoom zoomAbout(double pointerX, double pointerY, Rect canvas,
                                 int panX, int panY, float zoom, float factor,
                                 float minZoom, float maxZoom) {
        float next = Math.min(Math.max(zoom * factor, minZoom), maxZoom);
        if (next == zoom || factor <= 0F) {
            return new Zoom(panX, panY, zoom);
        }

        double worldX = (pointerX - canvas.x() - panX) / zoom;
        double worldY = (pointerY - canvas.y() - panY) / zoom;
        int nextPanX = (int) Math.round((pointerX - canvas.x()) - worldX * next);
        int nextPanY = (int) Math.round((pointerY - canvas.y()) - worldY * next);
        return new Zoom(nextPanX, nextPanY, next);
    }

    /**
     * The horizontal room a label may take, given the columns of nodes that are on screen.
     *
     * <h2>The invariant that makes crowding survivable</h2>
     *
     * <p>The result is <b>strictly less than the narrowest gap between columns</b>, because a margin is
     * subtracted at each side. That one fact is what stops two labels touching: each is truncated to
     * the room, and every room is narrower than the distance between the nodes it labels.
     *
     * <p>So a questline authored 64 pixels apart carrying 90-pixel titles yields 56-pixel labels, not
     * overlapping ones. That is the fix for the reported symptom — three whole titles centred on nodes
     * 64 apart ran through each other and read as one corrupted string — and it is why the answer here
     * is 56 rather than 0.
     *
     * <h2>Two thresholds, and they are not the same one</h2>
     *
     * <p>This returns zero only when a gap is no wider than the two margins together — 8 pixels at the
     * usual margin of 4. Between that and a comfortable gap sits a band where the room is positive and
     * still too narrow to be worth drawing; there the caller skips the labels, on its own
     * {@link #MIN_LABEL_WIDTH} test. Conflating the two is an easy mistake and was made in this file's
     * own first test.
     *
     * <p>Fewer than two columns returns {@link #MAX_LABEL_WIDTH}: there is no neighbour to crowd. For
     * an empty list that is moot — the caller iterates no quests — so it is the same branch rather than
     * a special case with no consequences.
     */
    public static int labelRoom(List<Integer> columnXs, int labelGap, int maxWidth) {
        List<Integer> sorted = new ArrayList<>(columnXs);
        sorted.sort(Integer::compareTo);

        if (sorted.size() < 2) {
            // One column, so no neighbour to crowd. The cap still applies.
            return maxWidth;
        }
        int gap = Integer.MAX_VALUE;
        for (int i = 1; i < sorted.size(); i++) {
            gap = Math.min(gap, sorted.get(i) - sorted.get(i - 1));
        }
        return clamp(gap - labelGap * 2, 0, maxWidth);
    }

    /**
     * Which of {@code rects} the pointer is in, or -1.
     *
     * <p>Backwards, so the last drawn — and therefore the topmost — wins. Two quests may legitimately
     * be at the same position, which the index reports as a warning, and picking the first would
     * select whichever happened to be authored earlier rather than the one you can see.
     */
    public static int hitTest(List<Rect> rects, double px, double py) {
        for (int i = rects.size() - 1; i >= 0; i--) {
            if (rects.get(i).contains(px, py)) {
                return i;
            }
        }
        return -1;
    }

    /** Whether two controls are clear of each other *and* of a margin, for a sanity check. */
    public static boolean clearOf(Rect a, Rect b, int margin) {
        return !a.intersects(Rect.at(b.x() - margin, b.y() - margin,
                b.width() + margin * 2, b.height() + margin * 2));
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }
}
