package dev.ellipog.tasked.client.dev;

import dev.ellipog.armature.client.ui.ThemeToken;
import dev.ellipog.armature.client.ui.kit.Insets;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Stack;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.tasked.client.BookGeometry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The tools panel's composition: a preview, two switches, a theme list, the colours, and the band that
 * edits one of them.
 *
 * <h2>Docked beside the canvas, not a modal in the middle</h2>
 *
 * <p>It was a centred modal opened by a key of its own, and both halves of that were wrong for the job: a
 * theme editor's whole point is watching the thing you are changing, and a modal covers it. The panel
 * floats over the canvas's right-hand side, inside the book, so the graph a colour belongs to is on screen
 * while the colour changes.
 *
 * <p>It <b>overlays</b> the canvas rather than shrinking it, and that is the one place this departs from
 * "docked". Shrinking the canvas would re-clamp the pan and the zoom under the author's hands every time
 * the panel opened or closed — the thing they were looking at would move — and the panel is a tool for a
 * few minutes at a time. The cost is that nodes under it are hidden while it is open, which closing it
 * restores.
 *
 * <h2>The four things that make it a tool rather than a word list</h2>
 *
 * <p>Each answers a complaint from the first playtest, and each is a <i>rect</i> here rather than a
 * drawing: the panel is game-free and testable, and the drawing is {@code ToolsPanel}'s.
 *
 * <ul>
 *   <li><b>A live preview</b> ({@link #PREVIEW} pixels of the panel's top, fixed so it never scrolls
 *       away): a node in the current shape, a panel strip, three lines of text and a button, all drawn in
 *       the theme as it stands. It is what turns "Raised strip" into <i>that bit</i>.</li>
 *   <li><b>Sections that fold</b>: the theme list and the colours are headings you can collapse, because
 *       forty-one rows and sixteen themes in one column is a wall.</li>
 *   <li><b>A band that shows values</b>: the selected colour's name and hex, four channels with their
 *       numbers beside two-step buttons, Revert for that colour alone, and one save that names its
 *       file.</li>
 *   <li><b>A feedback line</b> under the title, so what just happened does not have to be found in the
 *       chat behind a panel.</li>
 * </ul>
 */
public final class ToolsLayout {

    // ------------------------------------------------------------------
    // Metrics
    // ------------------------------------------------------------------

    /** The panel's width. Narrow enough to leave most of a canvas, wide enough for a name and a hex. */
    public static final int WIDTH = 300;

    /** Between the panel and the canvas' edges, and between the panel's own bands. */
    public static final int GAP = 6;

    /** The narrowest panel worth drawing. Below this the canvas keeps its room and the panel clamps. */
    public static final int MIN_WIDTH = 180;

    public static final int TAB_HEIGHT = 16;
    public static final int FEEDBACK_HEIGHT = 12;
    public static final int PREVIEW_HEIGHT = 96;

    /** One row of a list: a theme, a colour, or a switch. */
    public static final int ROW_HEIGHT = 16;
    public static final int ROW_GAP = 1;
    public static final int HEADING_HEIGHT = 13;
    public static final int SECTION_GAP = 7;

    /** A switch row is taller: it has a control in it rather than being one. */
    public static final int SWITCH_HEIGHT = 20;

    /** The band's own rows: the swatch line, two channel lines, the actions. */
    public static final int SWATCH_ROW = 26;
    public static final int CHANNEL_ROW = 18;
    public static final int ACTION_ROW = 20;

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    /** The tab strip, which replaced the panel's title row: two panels in the one dock. */
    public static final String TAB_THEME = "tab:theme";
    public static final String TAB_CHAPTER = "tab:chapter";

    /**
     * Which panel the dock is showing.
     *
     * <p>An enum rather than a boolean, because "which tab" is a name with a label and a reason, and a
     * boolean would have the call sites reading {@code true} as Theme until someone decides otherwise.
     * The Quest panel shows the selected quest's own fields; the Theme panel is the tools this panel
     * started as. Neither keeps private state the other could make stale: both draw from the same
     * selection the canvas holds.
     */
    public enum Tab {
        THEME("Theme"),
        CHAPTER("Chapter");

        private final String label;

        Tab(String label) {
            this.label = label;
        }

        /** The name the tab strip shows. */
        public String label() {
            return label;
        }
    }

    /** The switches. Their labels are the state; the button is the change. */
    public static final String EDIT = "edit";
    public static final String MOTION = "motion";
    public static final String SNAP = "snap";

    /** The one foldable section. Pressing its heading folds it. */
    /** The shape section: one row, the corner radius, and it is selected through the band like a colour. */
    public static final String SHAPE_SECTION = "section:shape";

    /**
     * The radius's row key.
     *
     * <p>It was briefly selectable -- a click put the radius in the band, which then showed one line of
     * steppers. That was a second step for one number, and the report said so: the arrows belong in the
     * row, beside the number they move.
     */
    public static final String RADIUS = "shape:radius";

    /**
     * A palette list used to sit above the colours, and it is gone.
     *
     * <p>A theme <b>is</b> a set of colours, so a list of themes beside a list of colours offered the same
     * decision twice -- and one that would load sixteen palettes over whatever an author had already
     * changed. Starting from another palette is a hand-edit of {@code config/armature/themes}, which is
     * where a palette belongs; the panel edits the colours in front of it.
     */
    private static final String UNUSED_THEME_SECTION = "section:theme";
    public static final String COLOUR_SECTION = "section:colours";

    /** The band's actions. */
    public static final String REVERT = "revert";
    public static final String SAVE = "save";

    /** A colour's row key. */
    public static String tokenKey(String tokenId) {
        return "token:" + tokenId;
    }

    /** The colour a row key names, or null for a key that is not one. */
    public static String tokenId(String key) {
        return key != null && key.startsWith("token:") ? key.substring("token:".length()) : null;
    }

    private ToolsLayout() {
    }

    // ------------------------------------------------------------------
    // The panel and its bands
    // ------------------------------------------------------------------

    /**
     * The panel, and the fixed bands inside it.
     *
     * @param panel    the whole floating column
     * @param tabs     the tab strip, which replaced the title row: two tabs, Theme and Quest
     * @param feedback the one-line status under the strip
     * @param preview  the live preview (theme tab only)
     * @param list     where the scrolling sections live
     * @param swatch   the selected colour's name, hex and swatch (theme tab only)
     * @param channels the two channel lines, as one band (theme tab only)
     * @param actions  Revert and Save (theme tab only)
     */
    public record Frame(BookGeometry.Rect panel, BookGeometry.Rect tabs, BookGeometry.Rect feedback,
                        BookGeometry.Rect preview, BookGeometry.Rect list, BookGeometry.Rect swatch,
                        BookGeometry.Rect channels, BookGeometry.Rect actions) {
    }

    /** The frame for the theme tab: the tab strip, the preview, the sections and the band. */
    public static Frame frame(BookGeometry.Rect canvas) {
        return frame(canvas, Tab.THEME);
    }

    /**
     * The frame, from the canvas the panel floats over, shaped by the tab it is for.
     *
     * <p>Everything is placed from the panel's own rectangle, and the list's height is what is left over
     * after the fixed bands -- one subtraction, so a band added here cannot be forgotten by a caller and
     * cannot overlap the list.
     *
     * <p>The <b>quest tab</b> takes the bands away that are about a colour -- the preview, the swatch,
     * the channels and the actions -- and gives their room to the list, because its sections are a wall
     * by design: identity, placement, rules, dependencies, tasks and rewards. The bands that remain are
     * {@link #EMPTY}, so a caller that asks for one of them gets a rectangle that is inside the panel
     * and contains nothing, rather than a null to test for at every use.
     */
    public static Frame frame(BookGeometry.Rect canvas, Tab tab) {
        // Wide as designed, narrow rather than absent on a canvas with no room for it, and never wider
        // than the canvas it is docked to -- a panel hanging off the left edge of a small window is what
        // the first version of this did, and its own test said so. `MIN_WIDTH` is what the panel wants;
        // the canvas is what it has.
        int width = Math.min(Math.min(WIDTH, Math.max(MIN_WIDTH, canvas.width() - 40)),
                Math.max(0, canvas.width()));
        int height = Math.max(0, canvas.height() - GAP * 2);
        // The x is clamped too, because the gap kept from the canvas' right edge can push the panel out
        // on a canvas narrower than the gap.
        BookGeometry.Rect panel = BookGeometry.Rect.at(
                Math.max(canvas.x(), canvas.right() - width - GAP), canvas.y() + GAP, width, height);

        // **One pass, top to bottom, each band taking what is left.** It was two stacks -- the header
        // downward and the band upward -- which is fine on a tall panel and wrong on a short one: the two
        // met in the middle and overlapped, and a canvas too small for the panel's own chrome is a
        // perfectly ordinary thing to open (a small window, a resized game). Here every band is placed
        // after the one above it and clamped to the panel, so the order is a property of the code rather
        // than of the numbers -- and a band with no room left collapses to nothing instead of landing on
        // top of another.
        int inner = Math.max(0, width - GAP * 2);
        int x = panel.x() + GAP;
        int cursor = panel.y() + GAP;
        int floor = panel.bottom() - GAP;

        BookGeometry.Rect tabs = take(x, cursor, inner, TAB_HEIGHT, floor);
        cursor = tabs.bottom();
        BookGeometry.Rect feedback = take(x, cursor, inner, FEEDBACK_HEIGHT, floor);
        cursor = feedback.bottom();

        if (tab == Tab.CHAPTER) {
            return new Frame(panel, tabs, feedback, empty(x, cursor),
                    take(x, cursor, inner, Math.max(0, floor - cursor), floor),
                    empty(x, cursor), empty(x, cursor), empty(x, cursor));
        }

        // What the band will need, so the list can take everything else and the band lands at the bottom
        // of a panel with room to spare.
        int channelsHeight = CHANNEL_ROW * 2 + ROW_GAP;
        int band = SWATCH_ROW + GAP + channelsHeight + GAP + ACTION_ROW;
        int middle = Math.max(0, floor - band - (cursor + SECTION_GAP));
        BookGeometry.Rect preview = take(x, cursor, inner, Math.min(PREVIEW_HEIGHT, middle), floor);
        cursor = preview.bottom();
        BookGeometry.Rect list = take(x, cursor + (preview.height() > 0 ? SECTION_GAP : 0), inner,
                Math.max(0, middle - preview.height() - (preview.height() > 0 ? SECTION_GAP : 0)), floor);
        cursor = list.bottom() + SECTION_GAP;

        BookGeometry.Rect swatch = take(x, cursor, inner, SWATCH_ROW, floor);
        cursor = swatch.bottom() + GAP;
        BookGeometry.Rect channels = take(x, cursor, inner, channelsHeight, floor);
        cursor = channels.bottom() + GAP;
        BookGeometry.Rect actions = take(x, cursor, inner, ACTION_ROW, floor);

        return new Frame(panel, tabs, feedback, preview, list, swatch, channels, actions);
    }

    /** An empty band: inside whatever rectangle it is asked for, and holding nothing. */
    private static BookGeometry.Rect empty(int x, int y) {
        return BookGeometry.Rect.at(x, y, 0, 0);
    }

    /**
     * A band, given the cursor and how tall it would like to be: never past the panel's floor.
     *
     * <p>The <b>y</b> is clamped as well as the height, and that is not belt and braces: once a panel is
     * too short for the bands above it, the cursor walks past the floor, and a zero-height band placed
     * there is a rectangle outside its own container -- which is exactly what the band test found on an
     * 80x60 canvas. Two clamps, one invariant: every band is inside the panel.
     */
    private static BookGeometry.Rect take(int x, int y, int width, int wanted, int floor) {
        int at = Math.min(y, floor);
        return BookGeometry.Rect.at(x, at, width, Math.max(0, Math.min(wanted, floor - at)));
    }

    // ------------------------------------------------------------------
    // The rows
    // ------------------------------------------------------------------

    /**
     * The tab strip's two buttons, side by side in the band the title used to occupy.
     *
     * <p>Equal halves with the panel's own gap between them, because two tabs of one dock are peers --
     * neither is "the panel" and the other is "the other thing". The strip is the band's whole width, so
     * a wider panel widens both tabs and the strip never stops reading as the panel's top row.
     */
    public static BookGeometry.Rect tabTheme(BookGeometry.Rect tabs) {
        return BookGeometry.Rect.at(tabs.x(), tabs.y(),
                Math.max(0, (tabs.width() - GAP) / 2), tabs.height());
    }

    public static BookGeometry.Rect tabQuest(BookGeometry.Rect tabs) {
        BookGeometry.Rect theme = tabTheme(tabs);
        return BookGeometry.Rect.at(theme.right() + GAP, tabs.y(),
                Math.max(0, tabs.right() - theme.right() - GAP), tabs.height());
    }

    /**
     * One row of the list.
     *
     * <p>Three kinds, and the difference is where the control is: a {@code switch} is a label with a small
     * button in a strip at its right; a {@code row} is itself the control, which is what sixteen themes
     * and forty-one colours want, because a strip button per row would be sixteen buttons all saying the
     * same word; a {@code heading} is drawn, and in this panel it is also pressable -- it folds its
     * section.
     */
    public record Action(String key, String label, String buttonLabel, Kind kind) {

        public enum Kind {
            /** A label with a button in the strip at its right. */
            SWITCH,
            /** The whole row is the control. */
            ROW,
            /** A section's name, which folds it. */
            HEADING,
            /** A label whose own controls sit inside the row. */
            STEPPER
        }

        public static Action toggle(String key, String label, String buttonLabel) {
            return new Action(key, label, buttonLabel, Kind.SWITCH);
        }

        public static Action row(String key, String label) {
            return new Action(key, label, null, Kind.ROW);
        }

        public static Action heading(String key, String label) {
            return new Action(key, label, null, Kind.HEADING);
        }

        /** A label with its own controls in the row -- placed from {@link ToolsLayout#stepper}. */
        public static Action stepper(String key, String label) {
            return new Action(key, label, null, Kind.STEPPER);
        }

        public boolean hasButton() {
            return kind == Kind.SWITCH && buttonLabel != null;
        }

        public boolean isControl() {
            return kind == Kind.ROW;
        }

        public boolean isHeading() {
            return kind == Kind.HEADING;
        }

        /** Whether this row places its own controls rather than being one. */
        public boolean isStepper() {
            return kind == Kind.STEPPER;
        }
    }

    /**
     * The list's rows: the three switches, then the colours.
     *
     * @param editOn      what the Edit switch says
     * @param motionOn    what the Motion switch says
     * @param snapOn      what the Snap switch says
     * @param coloursOpen whether the colour section is unfolded
     */
    public static List<Action> rows(boolean editOn, boolean motionOn, boolean snapOn,
                                    boolean coloursOpen) {
        List<Action> rows = new ArrayList<>();
        rows.add(Action.toggle(EDIT, "Edit mode", editOn ? "On" : "Off"));
        rows.add(Action.toggle(MOTION, "Motion", motionOn ? "On" : "Off"));
        rows.add(Action.toggle(SNAP, "Snap", snapOn ? "On" : "Off"));

        // Shape before colours: the two knobs that are not a colour, then the palette.
        rows.add(Action.heading(SHAPE_SECTION, "Shape"));
        // A stepper row rather than a selectable one: its controls are placed from `stepper(row)` and it
        // takes no selection, so the band stays about colours.
        rows.add(Action.stepper(RADIUS, "Border radius"));

        // The marker is a filled triangle and a single angle: the disclosure pair this font carries. The
        // empty triangles it started as (`\u25be`/`\u25b8`) are not in it and drew as boxes -- see
        // `BookGeometry.TOOLS_BUTTON_WIDTH` for how the set of available glyphs was measured.
        rows.add(Action.heading(COLOUR_SECTION, (coloursOpen ? "\u25bc " : "\u203a ") + "Colours"));
        if (coloursOpen) {
            ThemeToken.Group last = null;
            for (ThemeToken token : ThemeToken.ALL) {
                if (token.group() != last) {
                    // A heading, not a row: a group's name is drawn and does nothing, and a pressable row
                    // that selects nothing is worse than plain text.
                    rows.add(Action.heading("group:" + token.group().name(), token.group().label()));
                    last = token.group();
                }
                rows.add(Action.row(tokenKey(token.id()), token.label()));
            }
        }
        return List.copyOf(rows);
    }

    /** The list as an unbuilt stack. See {@code DevLayout.stack} for why the gaps go before each row. */
    public static Stack stack(List<Action> rows) {
        Objects.requireNonNull(rows, "rows");

        Stack stack = Stack.stack();
        for (int i = 0; i < rows.size(); i++) {
            Action row = rows.get(i);
            if (i > 0) {
                stack.gap(row.isHeading() ? SECTION_GAP : ROW_GAP);
            }
            switch (row.kind()) {
                case HEADING -> stack.row(row.key(), HEADING_HEIGHT);
                case SWITCH -> stack.row(row.key(), SWITCH_HEIGHT, stripRoom());
                case ROW, STEPPER -> stack.row(row.key(), ROW_HEIGHT);
            }
        }
        return stack;
    }

    public static Layout build(List<Action> rows, int width, Measure measure) {
        Objects.requireNonNull(measure, "measure");
        return stack(rows).build(Math.max(0, width), measure);
    }

    /** How wide a switch's button is, and its inset from the row's right edge. */
    public static final int STRIP_WIDTH = 40;
    public static final int STRIP_INSET = 2;

    private static Insets stripRoom() {
        return new Insets(0, 0, STRIP_WIDTH + STRIP_INSET * 2, 0);
    }

    /**
     * Where a switch's button goes: in the gap its row reserved, against the column's edge.
     *
     * <p>The same placement {@code DevLayout.strip} uses, and deliberately not the one
     * {@code PartyRoster.removeSlot} uses -- a {@code STRETCH} row is narrowed by its insets, so the room
     * is <i>outside</i> the slot.
     */
    public static Slot strip(Slot row) {
        Objects.requireNonNull(row, "row");
        int width = Math.min(STRIP_WIDTH, Math.max(0, row.width()));
        return new Slot(row.key(), row.right() + STRIP_INSET, row.y(), width, row.height());
    }

    // ------------------------------------------------------------------
    // The sample in the preview
    // ------------------------------------------------------------------

    /**
     * The parts of the preview's sample: a node pair on a canvas and a quest popover under them.
     *
     * <h2>Why this is here and not in the drawing</h2>
     *
     * <p>Because the first version put the arithmetic in {@code ToolsPanel} and the parts overflowed
     * their own rectangle -- a button standing on the card's border, a reward row running past it -- and no
     * test could see it, because a drawing method cannot be called without a client. Geometry this class's,
     * colour the panel's: the same split as every other rectangle in either mod, and the parts are asserted
     * to be inside the preview and inside one another's containers.
     */
    public record Preview(BookGeometry.Rect canvas, BookGeometry.Rect nodeA, BookGeometry.Rect nodeB,
                          BookGeometry.Rect line, BookGeometry.Rect card, BookGeometry.Rect raised,
                          BookGeometry.Rect text, BookGeometry.Rect track, BookGeometry.Rect thumb,
                          BookGeometry.Rect row, BookGeometry.Rect item, BookGeometry.Rect button,
                          BookGeometry.Rect tooltip) {
    }

    /** How much of the sample's card is left as margin. Nothing may touch a border. */
    public static final int SAMPLE_INSET = 4;

    /**
     * The sample's line height: the title sits at the top of the text block, the body one pitch below.
     *
     * <p>One number rather than a 10 written at the three places that need it -- the height of the text
     * block, the baseline the body is drawn on, and which of the two a click landed on. The three were 22,
     * 20 and 10 before, and the disagreement was a part two pixels taller than its own ink.
     */
    public static final int LINE_PITCH = 10;

    /**
     * A part of the sample, kept inside the card it belongs to.
     *
     * <p>The same rule the panel's own bands learned: a part is clamped in <b>both</b> dimensions and in
     * its <b>position</b>, not only in its size. A zero-height reward row placed below the card's bottom is
     * a part outside its container -- which the sample's test found twice, at 288x96 and at 200x70, and
     * which no amount of reading the arithmetic had caught.
     */
    private static BookGeometry.Rect part(int x, int y, int width, int height,
                                          BookGeometry.Rect inside) {
        return part(x, y, width, height, inside, SAMPLE_INSET);
    }

    /**
     * The same, with the inset named.
     *
     * <p>Because the inset is the *container's* margin and not a constant of the sample: an item sits one
     * pixel inside its reward row, and a row sits four inside the card. A single inset made the item's
     * clamp land three pixels below a one-pixel-tall row -- which the test found, again.
     */
    private static BookGeometry.Rect part(int x, int y, int width, int height,
                                          BookGeometry.Rect inside, int inset) {
        // The *position* is clamped to the container's own edges and the *size* to the inset, and the two
        // clamps are deliberately different. Clamping the position to the inset as well is what kept a
        // zero-height reward row's item one pixel outside it: a part with no size still has to be
        // somewhere inside, and `inside.bottom()` is somewhere inside.
        int left = Math.max(inside.x(), Math.min(x, inside.right()));
        int top = Math.max(inside.y(), Math.min(y, inside.bottom()));
        int w = Math.max(0, Math.min(width, inside.right() - inset - left));
        int h = Math.max(0, Math.min(height, inside.bottom() - inset - top));
        return BookGeometry.Rect.at(left, top, w, h);
    }

    /** The sample, placed inside the rectangle it is given. */
    public static Preview previewParts(BookGeometry.Rect preview) {
        int x = preview.x();
        int y = preview.y();
        int w = preview.width();
        int h = preview.height();

        int node = Math.max(10, Math.min(24, h / 4));
        int nodeY = y + h / 5 - node / 2;
        int firstX = x + Math.max(4, w / 10);
        int secondX = x + w / 2;
        BookGeometry.Rect nodeA = BookGeometry.Rect.at(firstX, nodeY, node, node);
        BookGeometry.Rect nodeB = BookGeometry.Rect.at(secondX, nodeY, node, node);
        BookGeometry.Rect line = BookGeometry.Rect.at(nodeA.right(), nodeY + node / 2 - 1,
                Math.max(2, secondX - nodeA.right()), 2);

        BookGeometry.Rect card = BookGeometry.Rect.at(x + Math.max(2, w / 12), y + h / 2,
                Math.max(0, w - Math.max(4, w / 6)), Math.max(0, h / 2 - 8));
        BookGeometry.Rect raised = BookGeometry.Rect.at(card.x() + 1, card.y() + 1,
                Math.max(0, card.width() - 2), Math.min(11, Math.max(0, card.height() - 2)));
        // Clamped like everything else here: a sample eight pixels tall cannot hold three lines of text
        // and a reward row, and an unclamped height is a part outside its own card.
        //
        // Exactly two line pitches tall, and that is a fix rather than tidiness: at 22 the rectangle was two
        // pixels taller than the ink it holds, which put the reward row's top two pixels *inside the text* --
        // so a click on the row's first pixel answered "body", and the parts' own overlap test could not see
        // it because both were inside the card.
        int textTop = raised.bottom() + 2;
        BookGeometry.Rect text = part(card.x() + SAMPLE_INSET, textTop,
                card.width() - SAMPLE_INSET * 2, LINE_PITCH * 2, card);

        int line2 = text.y() + LINE_PITCH * 2;
        BookGeometry.Rect row = part(text.x(), line2, card.width() - SAMPLE_INSET * 2 - 50, 12, card);
        // The item is inside the row whatever the row's height is, which on a short sample is two pixels.
        // A fixed ten-pixel square is a thing that only fits the sample it was written for.
        int itemSize = Math.max(0, Math.min(10, Math.min(row.width(), row.height()) - 2));
        BookGeometry.Rect item = part(row.x() + 1, row.y() + 1, itemSize, itemSize, row, 1);
        // Height-clamped like the text: the button shares the reward row's line, and on a short sample
        // that line is a couple of pixels from the card's bottom -- which is exactly where the first
        // version put a fourteen-pixel button, eight pixels of it below the card.
        BookGeometry.Rect button = part(card.right() - SAMPLE_INSET - 44, row.y(), 44, 14, card);
        // Three pixels wide, ending *at* the inset rather than two pixels short of it: the arithmetic that
        // matters is `right <= card.right - SAMPLE_INSET`, and writing it as the x is how the first
        // version was one pixel out.
        BookGeometry.Rect track = part(card.right() - SAMPLE_INSET - 3, card.y() + 4, 3,
                card.height() - 8, card);
        // The grip, the top third of the track. Here rather than only in the drawing because the drawing is
        // not the only thing that needs it: the grip and the track are two different colours, and a click
        // has to be able to tell which one it landed on.
        BookGeometry.Rect thumb = BookGeometry.Rect.at(track.x(), track.y(), track.width(),
                Math.max(3, track.height() / 3));
        BookGeometry.Rect tooltip = BookGeometry.Rect.at(card.x() + SAMPLE_INSET + 4, card.y() - 14, 56, 12);

        return new Preview(preview, nodeA, nodeB, line, card, raised, text, track, thumb, row, item, button,
                tooltip);
    }

    /** A part of the sample, and the colour that paints it. */
    public record Hotspot(String token, BookGeometry.Rect rect) {
    }

    /**
     * Which colour the sample is offering at a point, or null when it is offering none.
     *
     * <h2>The sample is a map, so it can be read with the pointer</h2>
     *
     * <p>It was a picture: fifteen colours on a card, and the only way to find out which one painted the
     * button was to guess from the list below it. Pointing at a part now says which part it is, and pressing
     * it selects that colour and scrolls the list to its row — *"allow me to click the things in the preview
     * thing at the top to instantly kinda get me to it in the colours menu"*.
     *
     * <p><b>The most specific part wins</b>, and the order below is the whole of that rule: the item sits
     * inside the reward row inside the card, and the tooltip sits over the card's top edge, so a search that
     * took the card first would leave three parts unreachable. The canvas is last because it is what is left
     * when nothing else is under the pointer.
     *
     * <h2>One colour per part, and which one</h2>
     *
     * <p>Every part is painted by more than one token — a button has a face and an edge, a card a fill and
     * a border — and a click can only mean one of them. The choice is the token the part is <i>about</i>, and
     * it agrees with the ring the panel draws for a selected colour's group ({@code ToolsPanel.regionOf}),
     * so the two ways of pointing at the same part cannot disagree. The sample's two nodes carry the two
     * state colours deliberately, which is why both are reachable.
     *
     * <p>The others are one scroll away in the list rather than clickable here: a node's fill, the card's
     * border, the row's hover tint, the tooltip's border, and {@code faint}, which shares the body's line
     * and cannot be told apart from it without measuring a font.
     */
    public static Hotspot hotspotAt(BookGeometry.Rect preview, double mouseX, double mouseY) {
        Preview sample = previewParts(preview);
        int pitch = Math.min(LINE_PITCH, sample.text().height());
        List<Hotspot> parts = List.of(
                new Hotspot("title", BookGeometry.Rect.at(sample.text().x(), sample.text().y(),
                        sample.text().width(), pitch)),
                new Hotspot("body", BookGeometry.Rect.at(sample.text().x(), sample.text().y() + pitch,
                        sample.text().width(), Math.max(0, sample.text().height() - pitch))),
                new Hotspot("scrollThumb", sample.thumb()),
                new Hotspot("scrollTrack", sample.track()),
                new Hotspot("recessed", sample.item()),
                new Hotspot("edge", sample.button()),
                new Hotspot("raised", sample.raised()),
                new Hotspot("rowHover", sample.row()),
                new Hotspot("tooltipFill", sample.tooltip()),
                new Hotspot("available", sample.nodeA()),
                new Hotspot("complete", sample.nodeB()),
                new Hotspot("lineDone", sample.line()),
                new Hotspot("panel", sample.card()),
                new Hotspot("canvas", sample.canvas()));
        for (Hotspot part : parts) {
            if (part.rect().contains(mouseX, mouseY)) {
                return part;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // The band's controls
    // ------------------------------------------------------------------

    /**
     * The eight channel buttons, two channels to a line, and the four values that sit between them.
     *
     * <p>Two channels per line rather than eight buttons abreast, because the number is the point: the
     * first version had buttons that showed nothing, so a press changed a hex code you had to read
     * afterwards. Here each channel is {@code ◂ 46 ▸} in its own half of the line.
     *
     * @return one slot per channel stepper and one per value, keyed {@code "beat:<id>"} -- the value's
     *     rectangle is what the screen draws the number into
     */
    public static Map<String, Slot> beats(BookGeometry.Rect channels) {
        Map<String, Slot> out = new LinkedHashMap<>();
        int lineHeight = CHANNEL_ROW;
        int half = Math.max(0, (channels.width() - GAP) / 2);
        for (int i = 0; i < 4; i++) {
            int line = i / 2;
            int column = i % 2;
            int x = channels.x() + column * (half + GAP);
            int y = channels.y() + line * (lineHeight + ROW_GAP);
            String channel = CHANNELS.get(i);

            // The line reads `R  ◂ 46 ▸`: a letter band, then the two buttons with the number between
            // them. The letter is drawn by the panel rather than being a button's label, because a
            // fourteen-pixel button cannot hold one.
            int letter = 12;
            int button = 16;
            out.put("down:" + channel,
                    new Slot("down:" + channel, x + letter, y, button, lineHeight));
            out.put("beat:" + channel, new Slot("beat:" + channel, x + letter + button, y,
                    Math.max(0, half - letter - button * 2), lineHeight));
            out.put("up:" + channel,
                    new Slot("up:" + channel, x + half - button, y, button, lineHeight));
        }
        return out;
    }

    /** The channels, in the order the band draws them. */
    public static final List<String> CHANNELS = List.of("R", "G", "B", "A");

    /**
     * The box the selected colour's hex code is typed into, or null when nothing is selected.
     *
     * <p>On the swatch's own line, at its right, where the hex value used to be *drawn*: it was a display
     * of the answer, and the report was that the answer should be editable in place -- *"hex code direct
     * injection, like text field editing on hex"*.
     */
    public static BookGeometry.Rect hexField(BookGeometry.Rect swatch, boolean anySelection) {
        if (!anySelection) {
            return null;
        }
        int width = Math.min(96, Math.max(0, swatch.width() - 60));
        return BookGeometry.Rect.at(swatch.right() - width, swatch.y() + 3, width, 18);
    }

    /**
     * A row's own stepper: `- value +`, inside the row, at its right.
     *
     * <p>The shape's row is edited in place rather than by selecting it and using the band, which was the
     * report: *"instead of selecting then adding, just have the number in middle of 2 arrows that make it
     * go up or down"*. Two arrows and a number is a control that needs no explanation and no second step.
     */
    public static Map<String, Slot> stepper(Slot row) {
        int button = 16;
        int number = 22;
        int right = row.right() - STRIP_INSET;
        int left = Math.max(row.x(), right - (button * 2 + number));
        Map<String, Slot> out = new LinkedHashMap<>();
        out.put("down", new Slot("down", left, row.y(), button, row.height()));
        out.put("up", new Slot("up", right - button, row.y(), button, row.height()));
        return out;
    }

    /** Where a stepper's number is drawn: between its two buttons. */
    public static Slot stepperValue(Slot row) {
        Map<String, Slot> buttons = stepper(row);
        Slot down = buttons.get("down");
        Slot up = buttons.get("up");
        return new Slot("value", down.right(), row.y(), Math.max(0, up.x() - down.right()), row.height());
    }

    /**
     * A slot where it will be drawn: the list's own coordinates put through the viewport it is drawn in.
     *
     * <p>The one mapping -- and now literally one, since the property inspector arrived with the same
     * need and the method moved to {@code InspectLayout}: a row's slot is in the list's space, its y a
     * distance down the content, not down the screen, and a caller that forgets places its controls
     * outside the panel entirely. The fault it exists to end was real here first: the radius stepper's
     * arrows were built at the list's own coordinates and landed outside the panel, and the report was
     * *"border radius stepper thing wasnt there"*.
     */
    public static Slot onScreen(Viewport view, Slot slot) {
        return dev.ellipog.armature.client.ui.inspect.InspectLayout.onScreen(view, slot);
    }

    /**
     * Which way a press at a screen point steps the radius: {@code -1}, {@code +1}, or null for a miss.
     *
     * <p>Here rather than in the screen, and there is one description of where the arrows are: they are
     * {@link #stepper}'s, which derives them from the row's own rectangle — so whichever space the row is
     * in, the arrows are in it too. The caller maps the row and hands it over; the panel calls the same
     * method with the row rectangle it is drawing. A second description is how the first version managed to
     * place them out of sight while the hit test still found them.
     */
    public static Integer radiusStepAt(Viewport view, Slot radiusRow, double mouseX, double mouseY) {
        if (radiusRow == null) {
            return null;
        }
        Map<String, Slot> arrows = stepper(onScreen(view, radiusRow));
        for (String way : List.of("down", "up")) {
            if (arrows.get(way).contains((int) mouseX, (int) mouseY)) {
                return way.equals("down") ? -1 : 1;
            }
        }
        return null;
    }

    /** Revert and Save, side by side in the actions row. */
    public static BookGeometry.Rect revert(BookGeometry.Rect actions) {
        return BookGeometry.Rect.at(actions.x(), actions.y(), Math.max(0, (actions.width() - GAP) / 2),
                actions.height());
    }

    public static BookGeometry.Rect save(BookGeometry.Rect actions) {
        BookGeometry.Rect revert = revert(actions);
        return BookGeometry.Rect.at(revert.right() + GAP, actions.y(),
                Math.max(0, actions.right() - revert.right() - GAP), actions.height());
    }
}
