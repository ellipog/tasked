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
 * <h2>The shape of the screen, and what changed</h2>
 *
 * <pre>
 * +--------------------------------------------------------------+
 * | Quest Book                       20 quests . 100%      [ x ] |  header, close at the right
 * +-------------+------------------------------------------------+
 * | First Steps | [+]                                            |
 * | Toolsmith   | [-]                                            |
 * | Desert Road | [o]                                            |
 * |             |                                                |
 * |             |          (the graph, the whole canvas)         |
 * +-------------+------------------------------------------------+
 * </pre>
 *
 * <p>Two things moved, and each replaced something that was spending space without earning it:
 *
 * <ul>
 *   <li><b>The sidebar footer is gone.</b> It held four controls across two rows — zoom in, zoom out,
 *       re-centre and Done — in 116 pixels of a 132-pixel column. They are map controls, so they now
 *       sit on the map, as small square icon buttons in the canvas's top-left corner. That is where a
 *       player looks for them, it costs the chapter list no room at all, and it frees both footer
 *       rows.</li>
 *   <li><b>Done is now a close button in the header</b>, top-right, beside the quest count. A modal
 *       panel is closed by the thing in its corner, and the header had a mostly empty right end.</li>
 * </ul>
 *
 * <h2>And the summary strip is gone entirely</h2>
 *
 * <p>There used to be a third thing here: a bar across the bottom describing the selected quest with
 * an <b>Open</b> button. It went through two forms — a 46-pixel band reserved below the graph, then a
 * floating bar that appeared only when something was selected — and both were waste, for a reason
 * neither form addressed.
 *
 * <p><b>Clicking a node already opens the quest.</b> That is what a graph UI does, and the code has
 * done it since the pan/select/open gesture was written: a press that does not move selects
 * <i>and</i> opens, so the strip was a second route to a place you were already standing. A summary
 * of what you are looking at is only useful if you are not looking at it, and you just clicked it.
 *
 * <p>So the canvas is the whole area below the header, and there is nothing to keep clear of. That
 * also removes the class's only reason to distinguish a <i>canvas</i> from a <i>view port</i>: content
 * is centred in the canvas, because there is nothing on top of it.
 *
 * <h2>Screens are smaller than you think</h2>
 *
 * <p>Minecraft's GUI space is the window divided by the GUI scale, and an auto scale on a small
 * window gives remarkably little of it: the screenshot this was debugged from was
 * <b>427 × 240</b> GUI pixels. At that size the panel is 387 × 200. Anything that assumes 800 × 480
 * works on the developer's monitor and breaks on a laptop.
 *
 * <p>So the panel has a minimum size that can physically hold its own chrome, and below that minimum
 * the panel is allowed to run off the edge of the screen rather than overlapping itself. Off-screen is
 * clipped and obviously wrong; overlapping controls look like a rendering fault and are not obviously
 * anything.
 */
public final class BookGeometry {

    // ------------------------------------------------------------------
    // Dimensions
    // ------------------------------------------------------------------

    /** The chapter list down the left. */
    public static final int SIDEBAR_WIDTH = 132;

    /** The title bar across the top of the panel. Holds the title, the count and Close. */
    public static final int HEADER_HEIGHT = 26;

    /** A control's height. One number, so a row of them lines up. */
    public static final int ROW_HEIGHT = 18;

    /** Between two stacked controls. */
    public static final int ROW_GAP = 4;

    /** Between the panel's edge and the controls inside it. */
    public static final int EDGE = 8;


    /** The vertical pitch of a chapter row. Also its height plus its gap. */
    public static final int CHAPTER_ROW_PITCH = 22;

    /** Between the header and the first chapter row. */
    public static final int CHAPTER_GAP = 6;

    /** The gap inside the full-screen overlay, between its edge and its content. */
    public static final int OVERLAY_MARGIN = 24;

    // --- the view cluster ----------------------------------------------------

    /**
     * One button of the view cluster, and so the cluster's width.
     *
     * <p>Square and small, because it is a map control: the graph is the content and these are a tool
     * for looking at it. They used to be 30 pixels wide with the word "Centre" in one of them, which
     * is a label earning its keep in a footer and not on a map.
     */
    public static final int VIEW_BUTTON = ROW_HEIGHT;

    /** Between two buttons of the cluster. Small, because they are one control group. */
    public static final int VIEW_GAP = 2;

    /** The height of the three stacked buttons. */
    public static final int VIEW_COLUMN_HEIGHT = VIEW_BUTTON * 3 + VIEW_GAP * 2;

    /**
     * How far the cluster's backing panel extends past the buttons.
     *
     * <p>The buttons have their own fills, so this is not what makes them visible — it is what makes
     * them read as <b>one</b> cluster rather than as three controls that happen to be stacked. Three
     * pixels, and the three of them sit inside a single raised panel.
     */
    public static final int VIEW_MAT = 3;

    /**
     * The smallest panel that can hold its own chrome, derived rather than written out.
     *
     * <p>Two constraints, and the taller one wins:
     *
     * <ul>
     *   <li><b>The sidebar</b> needs the header, a gap, one chapter row, <b>the two appearance rows</b>
     *       and the bottom edge: {@code CHAPTER_GAP + ROW_HEIGHT + ROW_GAP + ROW_HEIGHT + ROW_GAP +
     *       ROW_HEIGHT + EDGE}. A chapter list with no rows is a book whose only chapter cannot be
     *       selected, which is a blank screen with no way forward.</li>
     *   <li><b>The canvas</b> needs the view cluster, which is the tallest thing that sits on it:
     *       {@code (EDGE - VIEW_MAT) + (VIEW_COLUMN_HEIGHT + 2 * VIEW_MAT) + EDGE}. Without this the
     *       cluster can run past the bottom of a short canvas, which is the same class of fault as the
     *       chapter row drawn underneath a footer.</li>
     * </ul>
     *
     * <p>Changing {@link #VIEW_BUTTON} therefore cannot silently invalidate it. The first version of
     * this constant was the sidebar term alone, and it was right only because the footer happened to be
     * shorter than the chapter list.
     *
     * <p><b>The sidebar term grew when the appearance rows arrived, and it had to.</b> The theme and
     * motion controls are anchored to the panel's bottom rather than to the chapter list, so on a panel
     * shorter than this they would be drawn over the chapter rows — the reported overlap bug, in the
     * one place the previous minimum no longer covered.
     *
     * <p>The arithmetic, since it is now close: the sidebar needs {@code 6 + 18 + 4 + 18 + 4 + 18 + 8}
     * = <b>76</b> and the canvas needs {@code 5 + (54 + 4 + 6) + 8} = <b>77</b>. So the canvas still
     * decides it, <b>by one pixel</b> — the total is unchanged from before the rows were added, which
     * is luck rather than design and worth knowing before trimming either term. Remove a {@code ROW_GAP},
     * a {@code ROW_HEIGHT} or a pixel of {@code EDGE} and the sidebar crosses under the canvas, at
     * which point {@code chapterRows()} would be computing against a panel its own minimum does not
     * guarantee. It would still return 1, and that is the point: the failure mode is silent.
     */
    public static final int MIN_PANEL_HEIGHT = HEADER_HEIGHT + Math.max(
            CHAPTER_GAP + ROW_HEIGHT + ROW_GAP + ROW_HEIGHT + ROW_GAP + ROW_HEIGHT + EDGE,
            (EDGE - VIEW_MAT) + (VIEW_COLUMN_HEIGHT + VIEW_MAT * 2) + EDGE);

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
    private final Rect header;
    private final Rect canvas;
    private final Rect sidebar;
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
        this.header = Rect.at(panelLeft, panelTop, panelWidth, HEADER_HEIGHT);

        // The canvas is everything below the header, to the bottom of the panel. Nothing floats over
        // it any more: the summary strip went, so there is no band to reserve and no view port to keep
        // clear of one.
        this.canvas = Rect.at(panelLeft + SIDEBAR_WIDTH, panelTop + HEADER_HEIGHT,
                panelWidth - SIDEBAR_WIDTH, panelHeight - HEADER_HEIGHT);
        this.sidebar = Rect.at(panelLeft, panelTop + HEADER_HEIGHT, SIDEBAR_WIDTH,
                panelHeight - HEADER_HEIGHT);
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

    /** The title bar, which holds the title, the quest count and Close. */
    public Rect header() {
        return header;
    }

    /** The graph, inside the panel. */
    public Rect canvas() {
        return canvas;
    }

    /** The chapter list, inside the panel. */
    public Rect sidebar() {
        return sidebar;
    }

    /** The full-screen quest view. */
    public Rect overlay() {
        return overlay;
    }

    // ------------------------------------------------------------------
    // The positions that the drawing and the controls both need
    // ------------------------------------------------------------------

    /** Where the chapter list starts. */
    public int chapterListTop() {
        return panel.y() + HEADER_HEIGHT + CHAPTER_GAP;
    }

    /**
     * How many chapter rows fit above the bottom of the panel.
     *
     * <p>At least one, always — a chapter list with no rows is a list a player cannot use, and a book
     * whose only chapter cannot be selected is a blank screen. {@link #MIN_PANEL_HEIGHT} guarantees
     * the row fits.
     *
     * <p>Derived from the panel's bottom rather than from a footer, because there is no footer any
     * more. The old version measured from {@code footerRow1Y()}, which is where a chapter could be
     * drawn underneath a button — the reported bug, one dimension over.
     *
     * <p>Measured to <b>{@link #themeRect()}</b> rather than to the panel's bottom, which is the same
     * fix applied to the one thing now in that space. The appearance rows are anchored down there, so
     * counting rows to the panel's edge would draw the last chapter underneath them; the chapter list
     * has to know what is below it, and asking the row where it starts is a better description of that
     * than subtracting a constant and hoping.
     */
    public int chapterRows() {
        int room = (themeRect().y() - ROW_GAP) - chapterListTop() - ROW_HEIGHT;
        return Math.max(1, room / CHAPTER_ROW_PITCH + 1);
    }

    /** The y of chapter row {@code index}. Not clamped; callers check {@link #chapterRows()}. */
    public int chapterRowY(int index) {
        return chapterListTop() + index * CHAPTER_ROW_PITCH;
    }

    /** The close button: a row-height square in the header, against the panel's right edge. */
    public Rect closeRect() {
        return Rect.at(panel.right() - EDGE - ROW_HEIGHT,
                panel.y() + (HEADER_HEIGHT - ROW_HEIGHT) / 2, ROW_HEIGHT, ROW_HEIGHT);
    }

    /**
     * Where the header's right-hand text has to stop, so the quest count does not run under Close.
     *
     * <p>One expression, used by the drawing and by nothing else — but it is here rather than in the
     * screen because that is the rule this class exists to enforce, and because the alternative is a
     * second {@code panelWidth() - 12} that agrees until somebody moves the button.
     */
    public int headerRightLimit() {
        return closeRect().x() - 10;
    }

    /**
     * The motion control: the bottom row of the sidebar.
     *
     * <p>Anchored to the panel's bottom rather than placed under the chapter list, and the reason is
     * the layout bug this class exists because of. Anything positioned "after the list" moves when the
     * list grows, so the last chapter row and a control below it are one edit apart from colliding.
     * Anchored from below, the two grow towards each other and {@link #chapterRows()} decides where
     * they meet — one place, checked by the sweep.
     */
    public Rect motionRect() {
        return Rect.at(panel.x() + EDGE, panel.bottom() - EDGE - ROW_HEIGHT, sidebarInner(), ROW_HEIGHT);
    }

    /**
     * The theme control: the row above {@link #motionRect()}.
     *
     * <h2>These two live on the sidebar, and that is a decision about what a theme is</h2>
     *
     * <p>They were commands. {@code /tasked theme tome} ran on the <i>server</i>: in single player that
     * is the same process as the client so it appeared to work, and on a dedicated server it changed a
     * field in a process with no window — which is why it needed an {@code isClient()} guard to avoid
     * being a lie. A control that has to defend against the side it runs on is a control on the wrong
     * side.
     *
     * <p>A theme is a preference for the person looking at the screen. It is the same kind of thing as
     * a volume slider: it belongs in the interface, next to the other one, and it should be reachable
     * without knowing a command exists. Two rows at the foot of the sidebar is where a screen puts
     * settings that apply to the whole screen, and it costs the chapter list the least room of any
     * position on the panel.
     *
     * <p>Side by side was the other option and it does not fit: the sidebar is 132 pixels and these
     * labels are a word each. Stacked, each is a full row and reads as its own setting.
     */
    public Rect themeRect() {
        Rect motion = motionRect();
        return Rect.at(motion.x(), motion.y() - ROW_GAP - ROW_HEIGHT, motion.width(), ROW_HEIGHT);
    }

    /**
     * The backing panel behind the three view buttons.
     *
     * <p>Three pixels larger than the buttons on every side, and it is what makes them read as one
     * cluster rather than three controls that happen to be stacked.
     */
    public Rect viewControls() {
        return Rect.at(canvas.x() + EDGE - VIEW_MAT, canvas.y() + EDGE - VIEW_MAT,
                VIEW_BUTTON + VIEW_MAT * 2, VIEW_COLUMN_HEIGHT + VIEW_MAT * 2);
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
     * <p>Note what is <b>not</b> here: nothing named for the footer that used to hold these, no control
     * at all whose only job is to close a panel that Escape already closes, and nothing for the summary
     * strip that used to sit across the bottom. Close is one square in the header, and the four former
     * footer controls are three map buttons and that square.
     *
     * @param chapters how many chapters there are to show
     */
    public Map<String, Rect> controls(int chapters) {
        Map<String, Rect> out = new LinkedHashMap<>();
        int left = panel.x() + EDGE;
        int inner = sidebarInner();

        // The chapter rows, top down, only as many as fit. Naming them chapter0, chapter1 ... rather
        // than a list, because a test that fails should say which control collided.
        int rows = Math.min(Math.max(0, chapters), chapterRows());
        for (int i = 0; i < rows; i++) {
            out.put("chapter" + i, Rect.at(left, chapterRowY(i), inner, ROW_HEIGHT));
        }

        // Close, in the header's right corner.
        out.put("close", closeRect());

        // The two appearance rows, at the foot of the sidebar. Added here rather than by the screen so
        // the overlap sweep covers them: they are the controls most likely to collide with the chapter
        // list, because they are the only ones that push into it from below. Their labels are not
        // fixed -- the theme row says whichever theme is in force -- so the width is the sidebar's
        // rather than measured from any particular name.
        out.put("theme", themeRect());
        out.put("motion", motionRect());

        // The view cluster, top-left inside the canvas. A column, so it reads as one tool group and
        // leaves the canvas's width for the graph.
        int vx = canvas.x() + EDGE;
        int vy = canvas.y() + EDGE;
        out.put("zoomIn", Rect.at(vx, vy, VIEW_BUTTON, VIEW_BUTTON));
        out.put("zoomOut", Rect.at(vx, vy + VIEW_BUTTON + VIEW_GAP, VIEW_BUTTON, VIEW_BUTTON));
        out.put("centre", Rect.at(vx, vy + (VIEW_BUTTON + VIEW_GAP) * 2, VIEW_BUTTON, VIEW_BUTTON));

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

    // A `Zoom` record and a `zoomAbout` method used to live here, with four tests of their own in
    // BookGeometryTest. They are gone, and the reason is worth keeping because it is the rule this
    // whole round was about rather than tidiness.
    //
    // They were a second implementation of zoom-about-the-pointer. The first was in
    // QuestBookScreen itself, as three static fields and the same algebra written out twice more --
    // in the pan, in the centring. The transform is `ui.kit`'s `Viewport` now, which the screen
    // calls, and `ViewportTest.zoomAtKeepsTheContentPointUnderThePointer` asserts the same
    // invariant this copy's suite did -- swept over more pans and scales than it managed, and
    // asserted against the transform rather than against a re-derivation of it.
    //
    // So this copy had no callers. Nothing said so: it compiled, its tests passed, and a reader
    // would reasonably have taken it for the one the screen uses. That is the failure mode of a
    // duplicate — not that it is wrong, but that it is *plausible*, and it stays plausible after
    // the thing it duplicates has moved on.
    //
    // What stays here is the framing: rectangles, the control map, label room, hit-test bounds.
    // That is one screen's own layout and nothing else's. Content-to-screen mapping is not framing,
    // it is a viewport, and it lives in the kit where a scrolling list can use it too.

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
