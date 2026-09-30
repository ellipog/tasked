package dev.ellipog.tasked.client.dev;

import dev.ellipog.armature.client.ui.ThemeToken;
import dev.ellipog.armature.client.ui.kit.Insets;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Stack;
import dev.ellipog.tasked.client.BookGeometry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The developer screen's composition: the mode's own switch, the appearance controls that were taken off
 * the quest book, and the theme editor's colour list.
 *
 * <h2>What this class is, and what the screen is</h2>
 *
 * <p>This is the arithmetic: where every row goes, how tall the list is, and where the three fixed bands
 * below it sit. The screen is the widgets and the drawing. The split is this project's rule rather than a
 * preference -- {@code QuestBookScreen} extends {@code Screen}, so nothing inside it can be instantiated
 * by a test, and the panel that was rewritten twice for arithmetic faults is the proof of what that
 * costs. Everything here is integers and rectangles, so a test can ask every question that matters
 * without a client.
 *
 * <h2>The colour list comes from the registry, not from a table here</h2>
 *
 * <p>{@link #colourRows()} walks {@link ThemeToken#ALL}, which is already in group order -- surfaces
 * first, then text, then the quest states and so on down to the control palette -- and emits a heading
 * whenever the group changes. A token added to Armature appears in this editor the day it is added,
 * which is the same property the merge and the file format have and for the same reason: a hand-written
 * list of forty-one names is a list that goes stale silently.
 *
 * <h2>Three bands below the list, and they do not scroll</h2>
 *
 * <p>The list is long by design, so it scrolls. The three rows under it are the editor's own controls --
 * what is selected, the four channels, and the actions -- and a control that scrolls away is a control
 * the reader has to hunt for. Their positions come from the card and the footer's own row height, in the
 * same arithmetic every modal in this mod uses, so the screen cannot disagree with the card about where
 * its bottom is.
 */
public final class DevLayout {

    // ------------------------------------------------------------------
    // Metrics
    // ------------------------------------------------------------------

    /** The card's preferred width. Wide, because a colour row is a name, a swatch and eight buttons. */
    public static final int WIDTH = 460;

    /** The title's own row at the top of the card: the line of text and the space under it. */
    public static final int TITLE_HEIGHT = 24;

    /** A group's heading. Shorter than a row: it is a label, not a control. */
    public static final int HEADING_HEIGHT = 13;

    /** A row: the same height as every other list in this mod, so the columns line up. */
    public static final int ROW_HEIGHT = 18;

    /** Between two rows of the same group. */
    public static final int ROW_GAP = 2;

    /** Before a group's heading, which is a bigger break than between two rows. */
    public static final int SECTION_GAP = 8;

    /** A row's button, and its inset from the row's right edge. The party panel's own numbers. */
    public static final int STRIP_WIDTH = 62;
    public static final int STRIP_INSET = 2;

    /** Between the eight channel buttons. */
    public static final int CHANNEL_GAP = 4;

    /** The footer's two left buttons. The width the back button uses, so the row reads as one design. */
    public static final int FOOTER_WIDTH = 88;

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    /** The card's title. Its key *is* its translation key -- see {@code PartyPanelLayout.TITLE}. */
    public static final String TITLE = "tasked.dev.title";

    /** The mode's own switch. */
    public static final String DEV = "dev";

    /** Which theme is in use, and the button that moves to the next one. */
    public static final String THEME = "theme";

    /** The motion switch: animation on or off. */
    public static final String MOTION = "motion";

    /** The line naming what the channel buttons will edit. */
    public static final String EDITING = "editing";

    /** Undoes every edit this session made. */
    public static final String RESET = "reset";

    /** Writes the edits out as a theme file and selects it. */
    public static final String SAVE = "save";

    /** Leaves the screen. */
    public static final String CLOSE = "close";

    /**
     * The eight channel buttons, in the order they are drawn.
     *
     * <p>One list, and both the placement and the action read it: an index into this list is the button.
     * Two lists -- one of labels and one of the operations -- would be one transposition away from a
     * button that adjusts green and says "B+".
     */
    public static final List<Channel> CHANNELS = List.of(
            new Channel("r-", "R", -1), new Channel("r+", "R", 1),
            new Channel("g-", "G", -1), new Channel("g+", "G", 1),
            new Channel("b-", "B", -1), new Channel("b+", "B", 1),
            new Channel("a-", "A", -1), new Channel("a+", "A", 1));

    /** One channel button: which channel, and which way. Its label in the screen is built from both. */
    public record Channel(String key, String channel, int step) {
    }

    private DevLayout() {
    }

    /** The key a colour row is placed under. */
    public static String tokenKey(String tokenId) {
        return "token:" + tokenId;
    }

    /** The token a colour row's key names, or null for a key that is not one. */
    public static String tokenId(String key) {
        return key != null && key.startsWith("token:") ? key.substring("token:".length()) : null;
    }

    /** The key a group's heading is placed under. */
    public static String groupKey(ThemeToken.Group group) {
        return "group:" + group.name();
    }

    // ------------------------------------------------------------------
    // The rows
    // ------------------------------------------------------------------

    /**
     * One row of the list.
     *
     * <h2>Three kinds, and the difference is where the control is</h2>
     *
     * <p>A {@code button} row is a label with a control in the strip at its right -- the mode, the theme
     * and the motion switches. A {@code row} is the control: pressing anywhere on it selects that
     * colour, which is what a list of forty-one of them wants, because a strip button per colour would
     * be forty-one buttons that all say the same word. A {@code heading} is drawn and nothing else.
     *
     * <p>Same shape as {@code PartyPanelLayout.Action}, deliberately: this is the second screen to need
     * "a list of rows with a control beside some of them", and the second time an idiom is repeated is
     * when it is worth writing down that it is one.
     */
    public record Action(String key, String label, String buttonLabel, boolean heading) {

        /** A label with a button in the strip at its right. */
        public static Action button(String key, String label, String buttonLabel) {
            return new Action(key, label, buttonLabel, false);
        }

        /** A row that is itself the control. */
        public static Action row(String key, String label) {
            return new Action(key, label, null, false);
        }

        /** A group's name. Drawn, not pressed. */
        public static Action heading(String key, String label) {
            return new Action(key, label, null, true);
        }

        /** Whether this row carries a button of its own. */
        public boolean hasButton() {
            return buttonLabel != null && !heading;
        }

        /** Whether the row itself is pressable. */
        public boolean isControl() {
            return !heading && buttonLabel == null;
        }
    }

    /**
     * The colour list: a heading per group, then one row per token, in the registry's own order.
     *
     * <p>Labels are the tokens' own, so a rename in the catalogue renames it here. The swatch and the hex
     * are not rows -- the screen draws them from the colour each token currently resolves to, which is
     * the thing being edited and cannot come from a static list.
     */
    public static List<Action> colourRows() {
        List<Action> rows = new ArrayList<>();
        ThemeToken.Group last = null;
        for (ThemeToken token : ThemeToken.ALL) {
            if (token.group() != last) {
                rows.add(Action.heading(groupKey(token.group()), token.group().label()));
                last = token.group();
            }
            rows.add(Action.row(tokenKey(token.id()), token.label()));
        }
        return List.copyOf(rows);
    }

    /**
     * The list's elements, in order, as an unbuilt stack.
     *
     * <p>The gaps go <b>before</b> each row after the first rather than after each row, so the layout does
     * not end with one: a trailing gap places no slot, so {@code Layout.height()} would not count it and
     * the list would report itself shorter than it is -- the fault the party panel was rewritten for,
     * arriving by the same route.
     */
    public static Stack stack(List<Action> rows) {
        Objects.requireNonNull(rows, "rows");

        Stack stack = Stack.stack();
        for (int i = 0; i < rows.size(); i++) {
            Action row = rows.get(i);
            if (i > 0) {
                stack.gap(row.heading() ? SECTION_GAP : ROW_GAP);
            }
            if (row.heading()) {
                stack.row(row.key(), HEADING_HEIGHT);
            }
            else {
                stack.row(row.key(), ROW_HEIGHT, row.hasButton() ? buttonRoom() : Insets.NONE);
            }
        }
        return stack;
    }

    /** The list built in a column of {@code width}: the rows placed, and the height they come to. */
    public static Layout build(List<Action> rows, int width, Measure measure) {
        Objects.requireNonNull(measure, "measure");
        return stack(rows).build(Math.max(0, width), measure);
    }

    /** The room a row's button strip needs, as an inset on the row. */
    private static Insets buttonRoom() {
        return new Insets(0, 0, STRIP_WIDTH + STRIP_INSET * 2, 0);
    }

    /**
     * Where a row's button goes, given the row's own slot.
     *
     * <h2>In the gap the row reserved, not inside the row</h2>
     *
     * <p>A {@code STRETCH} row is narrowed by its insets -- the slot is the <i>content</i> area -- so the
     * room {@link #buttonRoom()} reserved is the strip to the right of the slot. The button goes there:
     * at the slot's right edge plus the inset, against the column's edge. The first version placed it
     * inside the slot, the way {@code PartyRoster.removeSlot} does, and the test caught what that looks
     * like: a button floating sixty-six pixels from the card's edge while its own row's label ran the
     * full width beside it.
     *
     * <p>That is the same shape {@code PartyPanelLayout.buttonStrip} uses, and the same reasoning: the
     * two are the placements that read as a column of buttons, and the roster's is the one that reads as
     * a word with a button after it.
     */
    public static Slot strip(Slot row) {
        Objects.requireNonNull(row, "row");
        int width = Math.min(STRIP_WIDTH, Math.max(0, row.width()));
        return new Slot(row.key(), row.right() + STRIP_INSET, row.y(), width, row.height());
    }

    // ------------------------------------------------------------------
    // The card
    // ------------------------------------------------------------------

    /**
     * The card, the region the list scrolls in, and the three bands under it.
     *
     * @param card     the whole panel
     * @param title    the title's own row at the top of the card
     * @param body     where the list lives: the card's body, less the title and the three bands below
     * @param editing  the line naming the selected colour and its hex value
     * @param channels the eight channel buttons' band
     * @param footer   Reset, Save and Close, keyed by {@link #RESET}, {@link #SAVE} and {@link #CLOSE}
     */
    public record Frame(BookGeometry.Rect card, BookGeometry.Rect title, BookGeometry.Rect body,
                        BookGeometry.Rect editing, BookGeometry.Rect channels,
                        Map<String, BookGeometry.Rect> footer) {
    }

    /**
     * The frame, from the window.
     *
     * <p>The card is the largest modal this window allows, rather than a size that fits its content: a
     * tool screen that shrank and grew as a list of forty-one rows scrolled would be a panel that moved
     * its own footer under the pointer. The three bands are placed from the card's bottom upward and the
     * title from its top, so the list's height is what is left over -- one subtraction rather than a sum
     * that has to agree with the card's.
     */
    public static Frame frame(int screenWidth, int screenHeight) {
        BookGeometry geometry = new BookGeometry(screenWidth, screenHeight);
        BookGeometry.Rect card = geometry.modalFramed(geometry.modal().height(), WIDTH);
        BookGeometry.Rect inner = BookGeometry.modalBody(card);

        int control = BookGeometry.OVERLAY_CONTROL_HEIGHT;
        Map<String, BookGeometry.Rect> footer = footer(card);
        BookGeometry.Rect channels = BookGeometry.Rect.at(inner.x(),
                footer.get(RESET).y() - SECTION_GAP - control, inner.width(), control);
        BookGeometry.Rect editing = BookGeometry.Rect.at(inner.x(),
                channels.y() - ROW_GAP - ROW_HEIGHT, inner.width(), ROW_HEIGHT);
        BookGeometry.Rect title = BookGeometry.Rect.at(inner.x(), inner.y(), inner.width(), TITLE_HEIGHT);
        BookGeometry.Rect body = BookGeometry.Rect.at(inner.x(), title.bottom(), inner.width(),
                Math.max(0, editing.y() - SECTION_GAP - title.bottom()));

        return new Frame(card, title, body, editing, channels, footer);
    }

    /**
     * The footer's three controls: Reset and Save at the left, Close at the right.
     *
     * <p>The two on the left give up width before they give up their row. A card at the smallest window
     * this can be drawn in is narrower than three of {@link #FOOTER_WIDTH} plus Close, and the first
     * version simply placed them and overlapped Save and Close by twenty pixels -- which the band test
     * found at 320x200 and no other size, because every larger window has room. The same fallback the
     * modal's own footer uses, applied the other way round: there Close moves up a row, and here the
     * buttons that are not against the edge shrink, because Close's position is the one a reader's eye
     * expects and a button that has grown narrower still reads as itself.
     */
    public static Map<String, BookGeometry.Rect> footer(BookGeometry.Rect card) {
        Map<String, BookGeometry.Rect> out = new LinkedHashMap<>();
        int height = BookGeometry.OVERLAY_CONTROL_HEIGHT;
        int y = card.bottom() - BookGeometry.MODAL_INSET - height;

        int inner = Math.max(0, card.width() - BookGeometry.MODAL_INSET * 2);
        // Two buttons and two gaps share what Close leaves, so the width is one expression and both
        // buttons read it. `max(0, ...)` because a window can be dragged to nothing.
        int width = Math.min(FOOTER_WIDTH,
                Math.max(0, (inner - BookGeometry.BACK_WIDTH - BookGeometry.ROW_GAP * 2) / 2));

        BookGeometry.Rect reset = BookGeometry.Rect.at(card.x() + BookGeometry.MODAL_INSET, y, width, height);
        out.put(RESET, reset);
        out.put(SAVE, BookGeometry.Rect.at(reset.right() + BookGeometry.ROW_GAP, y, width, height));
        out.put(CLOSE, BookGeometry.Rect.at(card.right() - BookGeometry.MODAL_INSET - BookGeometry.BACK_WIDTH,
                y, BookGeometry.BACK_WIDTH, height));
        return out;
    }

    /** Where the nth channel button goes, across the band. */
    public static BookGeometry.Rect channel(int index, BookGeometry.Rect band) {
        int count = CHANNELS.size();
        int width = Math.max(0, (band.width() - CHANNEL_GAP * (count - 1)) / count);
        return BookGeometry.Rect.at(band.x() + index * (width + CHANNEL_GAP), band.y(), width, band.height());
    }
}
