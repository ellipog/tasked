package dev.ellipog.tasked.client.hud;

import dev.ellipog.tasked.client.BookGeometry;

/**
 * Where the HUD editor's parts go: the rows panel, and every element's box.
 *
 * <h2>Why this is a class and not arithmetic in the screen</h2>
 *
 * <p>Because a screen cannot be asked a question. {@code HudEditScreen} needs a live client to exist, so
 * "a dragged element is always somewhere on the window" and "the rows do not overlap each other" are facts
 * nothing could check if they lived in its methods -- and they are exactly the facts that go wrong silently,
 * as a control drawn one row too low or an element left half off the window. Integers and rectangles here
 * are asserted over a sweep of window sizes by {@code HudLayoutTest}, and the screen <b>builds itself from
 * them</b>: every rectangle drawn, hit-tested and dragged comes from one of these methods, so the test and
 * the screen cannot be describing two different layouts. That is the rule the book's own geometry was
 * extracted for.
 *
 * <h2>One coordinate space</h2>
 *
 * <p>An element's position is its position in the window, which is where the HUD is measured from too. The
 * rows panel is chrome the game never sees, so it is kept out of the way -- anchored to the bottom-left,
 * with its own height following how many elements there are -- and nothing converts between the two: a
 * position the editor writes is a position the game draws at, at any window size, which is the property
 * that was missing when a stored offset was measured from a ghost panel the editor had invented.
 */
public final class HudLayout {

    /** The margin between the chrome and the window's edges. */
    public static final int INSET = 4;

    /** The rows panel: wide enough for a label line and, under it, a switch with two buttons. */
    public static final int CHROME_WIDTH = 190;

    /** One row is a label line and a control line. */
    public static final int LABEL_LINE = 12;

    /** The same, for the line holding the switch and the two buttons. */
    public static final int CONTROL_LINE = 18;

    /** The gap between one element's row and the next. */
    public static final int ROW_GAP = 6;

    /** One element's whole row. */
    public static final int ROW_HEIGHT = LABEL_LINE + CONTROL_LINE + ROW_GAP;

    /** The strip at the foot of the chrome, holding Done. */
    public static final int FOOT_HEIGHT = 26;

    /** The switch's own size, from {@code ArmatureSwitch}: a compile-time constant, so this stays game-free. */
    public static final int SWITCH_WIDTH = 22;

    /** The same, vertically. */
    public static final int SWITCH_HEIGHT = 12;

    /** Move and Reset, side by side at the right of a row's control line. */
    public static final int BUTTON_WIDTH = 46;

    /** The gap between them. */
    public static final int BUTTON_GAP = 4;

    /**
     * The narrowest the rows can be and still hold their controls.
     *
     * <p>A switch, two buttons, the gaps between them and the panel's own margins. Below this the editor
     * cannot be drawn honestly -- a control the player cannot reach is worse than a panel that overlaps
     * something -- so the panel stops shrinking here and overflows instead, which is what a window this small
     * gets. Declared after the parts it is made of, because a constant cannot refer forward.
     */
    public static final int MIN_CHROME_WIDTH =
            INSET + SWITCH_WIDTH + BUTTON_GAP + BUTTON_WIDTH * 2 + BUTTON_GAP + INSET;

    private HudLayout() {
    }

    /**
     * The rows panel, in the bottom-left corner. Its height follows how many elements there are, and its
     * width gives way to a narrow window before its controls do.
     *
     * <p>The label line shrinks first -- it is a name, and a truncated name is still a name -- which is what
     * keeps <i>Done</i> and the two buttons on the window at sizes well below the panel's natural width.
     */
    public static BookGeometry.Rect chrome(int screenWidth, int screenHeight, int rows) {
        int height = chromeHeight(rows);
        int width = Math.max(MIN_CHROME_WIDTH, Math.min(CHROME_WIDTH, screenWidth - INSET * 2));
        return BookGeometry.Rect.at(INSET, Math.max(0, screenHeight - INSET - height), width, height);
    }

    /** The whole height the chrome needs for this many rows, footer included. */
    public static int chromeHeight(int rows) {
        int counted = Math.max(1, rows);
        return INSET * 2 + counted * ROW_HEIGHT + FOOT_HEIGHT;
    }

    /**
     * One coordinate, held inside the window.
     *
     * <p>The single place a position is made legal, used by the editor and by the real control on the
     * inventory screen alike -- so the two can never disagree about where an element ends up, which is the
     * fault the book's own two colliding buttons were. It reads the window size it is given rather than
     * anything remembered, so a resize is answered on the next frame.
     */
    public static int placed(int wanted, int limit) {
        return Math.max(0, Math.min(wanted, limit));
    }

    /**
     * An element's box: where it is, pulled onto the window.
     *
     * <p>What a drop asks -- the pointer put the box <i>here</i>, so what is written is where the element
     * visibly is rather than where the cursor went.
     */
    public static BookGeometry.Rect boxAt(HudElement element, int screenWidth, int screenHeight, int x, int y) {
        return BookGeometry.Rect.at(
                placed(x, screenWidth - element.width()),
                placed(y, screenHeight - element.height()),
                element.width(), element.height());
    }

    /** One row's label line. */
    public static BookGeometry.Rect label(int index, BookGeometry.Rect chrome) {
        return BookGeometry.Rect.at(chrome.x() + INSET, rowTop(index, chrome),
                Math.max(0, chrome.width() - INSET * 2), LABEL_LINE);
    }

    /** The show/hide switch of one row, at the left of its control line. */
    public static BookGeometry.Rect toggle(int index, BookGeometry.Rect chrome) {
        return BookGeometry.Rect.at(chrome.x() + INSET,
                controlTop(index, chrome) + (CONTROL_LINE - SWITCH_HEIGHT) / 2,
                SWITCH_WIDTH, SWITCH_HEIGHT);
    }

    /** The Move button of one row. */
    public static BookGeometry.Rect move(int index, BookGeometry.Rect chrome) {
        return BookGeometry.Rect.at(reset(index, chrome).x() - BUTTON_GAP - BUTTON_WIDTH,
                controlTop(index, chrome), BUTTON_WIDTH, CONTROL_LINE);
    }

    /** The Reset button of one row, against the chrome's right inset. */
    public static BookGeometry.Rect reset(int index, BookGeometry.Rect chrome) {
        return BookGeometry.Rect.at(chrome.right() - INSET - BUTTON_WIDTH, controlTop(index, chrome),
                BUTTON_WIDTH, CONTROL_LINE);
    }

    /** Done, in the chrome's foot. */
    public static BookGeometry.Rect done(BookGeometry.Rect chrome) {
        return BookGeometry.Rect.at(chrome.right() - INSET - BUTTON_WIDTH,
                chrome.bottom() - INSET - CONTROL_LINE, BUTTON_WIDTH, CONTROL_LINE);
    }

    private static int rowTop(int index, BookGeometry.Rect chrome) {
        return chrome.y() + INSET + index * ROW_HEIGHT;
    }

    private static int controlTop(int index, BookGeometry.Rect chrome) {
        return rowTop(index, chrome) + LABEL_LINE;
    }
}
