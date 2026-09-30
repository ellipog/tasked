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

    public static final int TITLE_HEIGHT = 14;
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

    public static final String TITLE = "tasked.tools.title";

    /** The switches. Their labels are the state; the button is the change. */
    public static final String EDIT = "edit";
    public static final String MOTION = "motion";

    /** The one foldable section. Pressing its heading folds it. */
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
     * @param title    the title line
     * @param feedback the one-line status under it
     * @param preview  the live preview
     * @param list     where the scrolling sections live: between the preview and the band
     * @param swatch   the selected colour's name, hex and swatch
     * @param channels the two channel lines, as one band
     * @param actions  Revert and Save
     */
    public record Frame(BookGeometry.Rect panel, BookGeometry.Rect title, BookGeometry.Rect feedback,
                        BookGeometry.Rect preview, BookGeometry.Rect list, BookGeometry.Rect swatch,
                        BookGeometry.Rect channels, BookGeometry.Rect actions) {
    }

    /**
     * The frame, from the canvas the panel floats over.
     *
     * <p>Everything is placed from the panel's own rectangle, and the list's height is what is left over
     * after the fixed bands -- one subtraction, so a band added here cannot be forgotten by a caller and
     * cannot overlap the list.
     */
    public static Frame frame(BookGeometry.Rect canvas) {
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

        BookGeometry.Rect title = take(x, cursor, inner, TITLE_HEIGHT, floor);
        cursor = title.bottom();
        BookGeometry.Rect feedback = take(x, cursor, inner, FEEDBACK_HEIGHT, floor);
        cursor = feedback.bottom();

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

        return new Frame(panel, title, feedback, preview, list, swatch, channels, actions);
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
            HEADING
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

        public boolean hasButton() {
            return kind == Kind.SWITCH && buttonLabel != null;
        }

        public boolean isControl() {
            return kind == Kind.ROW;
        }

        public boolean isHeading() {
            return kind == Kind.HEADING;
        }
    }

    /**
     * The list's rows: the two switches, then the colours.
     *
     * @param editOn      what the Edit switch says
     * @param motionOn    what the Motion switch says
     * @param coloursOpen whether the colour section is unfolded
     */
    public static List<Action> rows(boolean editOn, boolean motionOn, boolean coloursOpen) {
        List<Action> rows = new ArrayList<>();
        rows.add(Action.toggle(EDIT, "Edit mode", editOn ? "On" : "Off"));
        rows.add(Action.toggle(MOTION, "Motion", motionOn ? "On" : "Off"));

        rows.add(Action.heading(COLOUR_SECTION, (coloursOpen ? "\u25be " : "\u25b8 ") + "Colours"));
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
                case ROW -> stack.row(row.key(), ROW_HEIGHT);
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
                          BookGeometry.Rect text, BookGeometry.Rect track, BookGeometry.Rect row,
                          BookGeometry.Rect item, BookGeometry.Rect button, BookGeometry.Rect tooltip) {
    }

    /** How much of the sample's card is left as margin. Nothing may touch a border. */
    public static final int SAMPLE_INSET = 4;

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
        int textTop = raised.bottom() + 2;
        BookGeometry.Rect text = part(card.x() + SAMPLE_INSET, textTop,
                card.width() - SAMPLE_INSET * 2, 22, card);

        int line2 = text.y() + 20;
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
        BookGeometry.Rect tooltip = BookGeometry.Rect.at(card.x() + SAMPLE_INSET + 4, card.y() - 14, 56, 12);

        return new Preview(preview, nodeA, nodeB, line, card, raised, text, track, row, item, button,
                tooltip);
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
