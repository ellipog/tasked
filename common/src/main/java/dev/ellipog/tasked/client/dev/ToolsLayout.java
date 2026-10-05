package dev.ellipog.tasked.client.dev;

import dev.ellipog.armature.client.ui.CanvasBackground;
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
 * The tools panel's composition: a preview, the mode switches, the palette list, the shape and colour
 * sections, and the band that edits one colour.
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
 * <h2>The two things that make it a tool rather than a word list</h2>
 *
 * <p>Each answers a complaint from the first playtest, and each is a <i>rect</i> here rather than a
 * drawing: the panel is game-free and testable, and the drawing is {@code ToolsPanel}'s.
 *
 * <ul>
 *   <li><b>Sections that fold</b>: the palette list and the colours are headings you can collapse,
 *       because forty-three rows and sixteen palettes in one column is a wall.</li>
 *   <li><b>Controls on the rows</b>: a number is dragged or typed where it is named, a colour is a chip
 *       that opens the picker, and the two actions that write the player's own theme sit at the foot of
 *       the book tab.</li>
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

    /** The tab strip: the Book panel and the Chapter panel, side by side above the list. */
    public static final int TAB_HEIGHT = 16;
    /** One row of a list: a theme, a colour, or a switch. */
    public static final int ROW_HEIGHT = 16;
    public static final int ROW_GAP = 1;
    public static final int HEADING_HEIGHT = 13;
    public static final int SECTION_GAP = 7;

    /** A switch row is taller: it has a control in it rather than being one. */
    public static final int SWITCH_HEIGHT = 20;

    /** The actions row's height: Revert and Save, side by side at the book tab's foot. */
    public static final int ACTION_ROW = 20;

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    /**
     * Which panel the dock is showing.
     *
     * <p>An enum rather than a boolean, because "which tab" is a name with a label and a reason, and a
     * boolean would have the call sites reading {@code true} as one of them until someone decides which.
     *
     * <p>Each tab is about one target and nothing else. <b>Quest Book</b> is the book's own look: the
     * switches, the palette, the colours, the radius and the canvas — all the player's. <b>Chapter</b> is
     * the open chapter: its content and, above it, its own appearance, the same controls writing the
     * chapter's {@code theme} and {@code themePatch}. The target follows from the tab; there is no
     * switch that moves one tab's edits onto the other's file.
     */
    public enum Tab {
        BOOK("tasked.dev.tab.book"),
        CHAPTER("tasked.dev.tab.chapter");

        private final String label;

        Tab(String label) {
            this.label = label;
        }

        /** The key the tab strip shows. See {@code Labels.of} for where it is resolved. */
        public String label() {
            return label;
        }
    }

    /** The switches. Their labels are the state; the button is the change. */
    public static final String MOTION = "motion";
    public static final String SNAP = "snap";

    /**
     * Whether the sidebar's chapter rows draw a completion bar.
     *
     * <p>Beside the other general switches rather than among the theme's rows: it is a reading
     * preference about the book, not a colour, and it persists in the client's own file the way Snap
     * does.
     */
    public static final String PROGRESS = "progress";

    /**
     * The two switch values, as keys.
     *
     * <p>The player-facing screen's own pair, not a second copy: the chapter panel's toggles already draw
     * these two words, and a "On"/"Off" of the editor's own would be the same word translated twice --
     * which is how one of them ends up stale.
     */
    public static final String ON = "tasked.screen.on";
    public static final String OFF = "tasked.screen.off";

    /**
     * The book's identity: the pack's name and icon for the book itself, from {@code index.json}.
     *
     * <p>Not appearance: these write the pack's own file rather than any theme, which is why they sit
     * in their own fold at the top of the book tab and say "the pack's book" rather than "your look".
     */
    public static final String BOOK_SECTION = "section:book";
    public static final String BOOK_TITLE = "book:title";
    public static final String BOOK_ICON = "book:icon";

    /**
     * The chapter tab's appearance fold: everything under it writes the chapter's own theme patch.
     *
     * <p>A fold rather than a second mode: the chapter tab already carries the chapter's content, and
     * the appearance sections are the same ones the book tab shows — see
     * {@link #appearanceRows}.
     */
    public static final String APPEARANCE_SECTION = "section:appearance";

    /**
     * The shape section: one row, the corner radius, and it is selected through the band like a colour.
     */
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
     * The canvas surface's controls: the pattern, whatever that pattern's own numbers are, and the two
     * rows that act on the lot.
     *
     * <p>Their own section under Shape, and steppers for the same reason the radius is one: the
     * arrows belong in the row, beside the value they move. The space row is the exception -- two
     * values, so the whole row toggles and no arrows are drawn.
     *
     * <p>The section folds like the others, and it is the longest one when an image is in it: pattern,
     * texture, fit, tile, opacity, the live strip and the copy are a wall on a panel that already
     * carries the palette and the colours. See {@link #canvasRows} for which rows a given background
     * gets.
     */
    public static final String CANVAS_SECTION = "section:canvas";
    public static final String CANVAS_PATTERN = "canvas:pattern";
    public static final String CANVAS_SPACING = "canvas:spacing";
    public static final String CANVAS_SPACE = "canvas:space";
    public static final String CANVAS_SIZE = "canvas:size";
    public static final String CANVAS_DENSITY = "canvas:density";
    public static final String CANVAS_DIRECTION = "canvas:direction";
    public static final String CANVAS_TILE = "canvas:tile";
    public static final String CANVAS_FIT = "canvas:fit";
    public static final String CANVAS_TEXTURE = "canvas:texture";
    public static final String CANVAS_OPACITY = "canvas:opacity";
    public static final String CANVAS_COPY = "canvas:copy";

    /**
     * The palette list: the one place a theme is chosen, and it is the author's.
     *
     * <p>A list of this kind sat here once and was removed — <i>"a theme is a set of colours, and the
     * colours are the section"</i> — and it is back because the reason it was redundant is gone: a row
     * carries swatches now, so the list shows what each palette <b>looks like</b> rather than repeating
     * bare names. It is also the only selector there is: choosing a theme can undo a pack's whole planned
     * look, which is the pack author's decision and not a player's, so it lives behind the edit
     * permission with the rest of this panel and the player-facing card does not carry it.
     */
    public static final String PALETTE_SECTION = "section:palette";
    public static final String COLOUR_SECTION = "section:colours";

    /**
     * Whether a heading is one that folds — the predicate both the panel and the screen must read.
     *
     * <h2>Why this is a predicate and not a comment</h2>
     *
     * <p>"Pressable heading" was decided twice: the panel drew a {@code ▼} marker from the section's
     * open flag, and the screen gave a heading a widget from a hand-written list of three keys. The two
     * disagreed — the Canvas heading was drawn promising a fold and had no widget behind it, so no press
     * could fold it, and the Quest Book heading was the same. One predicate, used by the drawing and by
     * the widget pass and asserted to cover every section, is what makes the next heading unable to
     * repeat it.
     */
    public static boolean folds(String key) {
        return APPEARANCE_SECTION.equals(key) || BOOK_SECTION.equals(key)
                || CANVAS_SECTION.equals(key) || COLOUR_SECTION.equals(key)
                || PALETTE_SECTION.equals(key);
    }

    /**
     * The help a row offers on hover, or null for a row that says it itself.
     *
     * <h2>Why a table and not a field on the row</h2>
     *
     * <p>The same shape {@code QuestSettingsPanel.HELP} uses, and for the same reason: a row is
     * key, label and kind, and help is a property of the key. A table keeps it out of the record — the
     * rows are built in a dozen places and none of them should have to remember a sentence — and makes
     * "every control has help" a thing a test can assert rather than a thing a reader hopes.
     *
     * <p>Colour rows and group names are deliberately absent: a token's row is a swatch that shows the
     * colour it edits and the preview rings the part it paints, so a sentence would be a third
     * description of the same thing. Palette rows share one, because the list is one decision.
     */
    private static final Map<String, String> HELP = Map.ofEntries(
            Map.entry(MOTION, "tasked.dev.tools.help.motion"),
            Map.entry(SNAP, "tasked.dev.tools.help.snap"),
            Map.entry(PROGRESS, "tasked.dev.tools.help.progress"),
            Map.entry(PALETTE_SECTION, "tasked.dev.tools.help.palette"),
            Map.entry(SHAPE_SECTION, "tasked.dev.tools.help.shape"),
            Map.entry(RADIUS, "tasked.dev.tools.help.radius"),
            Map.entry(CANVAS_SECTION, "tasked.dev.tools.help.canvas"),
            Map.entry(CANVAS_PATTERN, "tasked.dev.tools.help.canvas_pattern"),
            Map.entry(CANVAS_TEXTURE, "tasked.dev.tools.help.canvas_texture"),
            Map.entry(CANVAS_FIT, "tasked.dev.tools.help.canvas_fit"),
            Map.entry(CANVAS_TILE, "tasked.dev.tools.help.canvas_tile"),
            Map.entry(CANVAS_SIZE, "tasked.dev.tools.help.canvas_size"),
            Map.entry(CANVAS_DENSITY, "tasked.dev.tools.help.canvas_density"),
            Map.entry(CANVAS_DIRECTION, "tasked.dev.tools.help.canvas_direction"),
            Map.entry(CANVAS_SPACING, "tasked.dev.tools.help.canvas_spacing"),
            Map.entry(CANVAS_SPACE, "tasked.dev.tools.help.canvas_space"),
            Map.entry(CANVAS_OPACITY, "tasked.dev.tools.help.canvas_opacity"),
            Map.entry(CANVAS_COPY, "tasked.dev.tools.help.canvas_copy"),
            Map.entry(COLOUR_SECTION, "tasked.dev.tools.help.colours"),
            Map.entry(BOOK_SECTION, "tasked.dev.tools.help.book"),
            Map.entry(BOOK_TITLE, "tasked.dev.tools.help.book_title"),
            Map.entry(BOOK_ICON, "tasked.dev.tools.help.book_icon"),
            Map.entry(APPEARANCE_SECTION, "tasked.dev.tools.help.appearance"));

    /** The help a row key offers, or null. Palette rows share one; colour rows have none. */
    public static String help(String key) {
        if (key == null) {
            return null;
        }
        if (paletteId(key) != null) {
            return "tasked.dev.tools.help.palette_row";
        }
        if (tokenKey("canvasPattern").equals(key)) {
            return "tasked.dev.tools.help.canvas_ink";
        }
        return HELP.get(key);
    }

    /**
     * A row under a point: its key, and the help it offers.
     *
     * <p>The key travels with the help because the caller needs both — the help to draw and the row to
     * hang it on — and finding the row twice is how the two would come to disagree.
     */
    public record Hovered(String key, String help) {
    }

    /**
     * Which row is under a point, or null.
     *
     * <p>Through {@link #onScreen}, the same mapping the drawing uses, so the row a tooltip describes is
     * the row the pointer is over and not the one at the same offset in a list that has scrolled.
     */
    public static Hovered helpAt(List<Action> rows, Layout layout, Viewport view,
                                 int mouseX, int mouseY) {
        if (rows == null || layout == null || view == null) {
            return null;
        }
        for (Action row : rows) {
            Slot slot = layout.slot(row.key());
            if (slot == null) {
                continue;
            }
            String help = help(row.key());
            if (help != null && onScreen(view, slot).contains(mouseX, mouseY)) {
                return new Hovered(row.key(), help);
            }
        }
        return null;
    }

    /** A colour's row key. */
    public static String tokenKey(String tokenId) {
        return "token:" + tokenId;
    }

    /** The colour a row key names, or null for a key that is not one. */
    public static String tokenId(String key) {
        return key != null && key.startsWith("token:") ? key.substring("token:".length()) : null;
    }

    /** A palette row's key. */
    public static String paletteKey(String paletteId) {
        return "palette:" + paletteId;
    }

    /** The palette a row key names, or null for a key that is not one. */
    public static String paletteId(String key) {
        return key != null && key.startsWith("palette:") ? key.substring("palette:".length()) : null;
    }

    /**
     * One palette a picker offers: the id that names it, and the label a row shows.
     *
     * <p>A pair rather than a bare name because the two differ — {@code high_contrast} is shown as "High
     * Contrast" — and because a record keeps this class game-free: the screen builds the list from
     * {@code Themes.everything()}, and a test builds one by hand.
     */
    public record Palette(String id, String label) {

        public Palette {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
        }
    }

    private ToolsLayout() {
    }

    // ------------------------------------------------------------------
    // The panel and its bands
    // ------------------------------------------------------------------

    /**
     * The drawer, and the fixed bands inside it.
     *
     * @param panel    the whole floating column
     * @param tabs     the tab strip: the Book panel and the Chapter panel
     * @param list     where the scrolling sections live
     * @param actions  Revert and Save (book tab only: a chapter's edits are the chapter file's own)
     */
    public record Frame(BookGeometry.Rect panel, BookGeometry.Rect tabs, BookGeometry.Rect list,
                        BookGeometry.Rect actions) {
    }

    /** The frame for the book tab. */
    public static Frame frame(BookGeometry.Rect rail) {
        return frame(rail, Tab.BOOK);
    }

    /**
     * The frame, from the rail the panel floats in, shaped by the tab it is for.
     *
     * <h2>Four bands, and the list is what is left</h2>
     *
     * <p>Every band is placed from the panel's own rectangle, top to bottom, and the list takes the
     * remainder — one subtraction, so a band added here cannot overlap it. It used to be nine: the sample
     * card, the colour band with its swatch and channel steppers, and the chapter's identity ribbon all
     * left for the redesign, and what replaced them is not only "fewer rows" but "the controls live on the
     * rows". The one asymmetry left is the actions row, which is the book tab's alone: Revert and Save
     * write the player's own theme file, and a chapter's edits are the chapter file's own, undone with
     * the editor's undo.
     *
     * <p>The rail, not the canvas: the drawer starts below the author's pills (see
     * {@code BookGeometry.authorRail}), so the pills never move when it opens and the drawer's first row
     * is not under a control floating over it.
     */
    public static Frame frame(BookGeometry.Rect rail, Tab tab) {
        // Wide as designed, narrow rather than absent on a rail with no room for it, and never wider
        // than the rail it is docked to -- a panel hanging off the left edge of a small window is what
        // the first version of this did, and its own test said so.
        int width = Math.min(Math.min(WIDTH, Math.max(MIN_WIDTH, rail.width() - 40)),
                Math.max(0, rail.width()));
        int height = Math.max(0, rail.height() - GAP * 2);
        BookGeometry.Rect panel = BookGeometry.Rect.at(
                Math.max(rail.x(), rail.right() - width - GAP), rail.y() + GAP, width, height);

        // **One pass, top to bottom, each band taking what is left.** It was two stacks -- the header
        // downward and the band upward -- which is fine on a tall panel and wrong on a short one: the two
        // met in the middle and overlapped, and a rail too small for the panel's own chrome is a
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

        // No status line under the strip: every message that used to land there also goes to a toast
        // (see `QuestBookScreen.status`), so the band was a second copy of a thing the player had
        // already been told.
        int listTop = cursor + SECTION_GAP;
        BookGeometry.Rect actions = empty(x, cursor);
        int listFloor = floor;
        if (tab == Tab.BOOK) {
            actions = take(x, floor - ACTION_ROW, inner, ACTION_ROW, floor);
            listFloor = Math.max(listTop, actions.y() - GAP);
        }
        BookGeometry.Rect list = take(x, listTop, inner, Math.max(0, listFloor - listTop), floor);
        return new Frame(panel, tabs, list, actions);
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
     * The tab strip's two buttons, side by side in the band above the status line.
     *
     * <p>Equal halves with the panel's own gap between them, because the two tabs are peers -- neither is
     * "the panel" and the other is "the other thing" -- and the strip is the band's whole width, so a
     * wider panel widens both tabs. It is the arrangement the drawer had before the redesign's short-lived
     * title menu, restored because a dropdown that hides one of two options is a worse control than the
     * two options themselves.
     */
    public static BookGeometry.Rect tabBook(BookGeometry.Rect tabs) {
        return BookGeometry.Rect.at(tabs.x(), tabs.y(),
                Math.max(0, (tabs.width() - GAP) / 2), tabs.height());
    }

    /** The Chapter half, in what the Book half leaves. */
    public static BookGeometry.Rect tabChapter(BookGeometry.Rect tabs) {
        BookGeometry.Rect book = tabBook(tabs);
        return BookGeometry.Rect.at(book.right() + GAP, tabs.y(),
                Math.max(0, tabs.right() - book.right() - GAP), tabs.height());
    }

    /**
     * One row of the list.
     *
     * <h2>The kinds, and what each one says about where the control is</h2>
     *
     * <p>A {@code switch} is a label with a small button in a strip at its right; a {@code row} is itself
     * the control, which is what sixteen themes and forty-three colours want, because a strip button per row
     * would be sixteen buttons all saying the same word; a {@code heading} is drawn, and in this panel it is
     * also pressable -- it folds its section.
     *
     * <p>The four numbered kinds are the redesign's: a {@code field} is a label and a value you can drag
     * or type ({@code ScrubField}); a {@code pair} is two of those sharing one line, with the second half
     * in {@link #right()} -- a field or a choice, whichever the pair needs; a {@code choice} is a label and
     * a word chosen from a list, opened by the triangle in its box ({@code ChoiceField}); and a {@code chip}
     * is a colour as an inline swatch whose press opens the picker. None of them is pressed by being
     * selected, which is what the old {@code ROW} meant for colours -- the redesign's whole point is that
     * the control is at the row rather than in a footer under it.
     */
    public record Action(String key, String label, String buttonLabel, Kind kind, Action right) {

        public enum Kind {
            /** A label with a button in the strip at its right. */
            SWITCH,
            /** The whole row is the control. */
            ROW,
            /** A section's name, which folds it. */
            HEADING,
            /** A label and a scrubbable number. */
            FIELD,
            /** Two halves on one line: this row, and {@link Action#right()}. */
            PAIR,
            /** A label and a value chosen from a short list. */
            CHOICE,
            /** A label and an inline colour chip. */
            CHIP
        }

        public static Action toggle(String key, String label, String buttonLabel) {
            return new Action(key, label, buttonLabel, Kind.SWITCH, null);
        }

        public static Action row(String key, String label) {
            return new Action(key, label, null, Kind.ROW, null);
        }

        public static Action heading(String key, String label) {
            return new Action(key, label, null, Kind.HEADING, null);
        }

        /** A numeric row: its label, and a field across the row's own width. */
        public static Action field(String key, String label) {
            return new Action(key, label, null, Kind.FIELD, null);
        }

        /**
         * Two halves on one line, left first.
         *
         * <p>The left half carries the pair's own key, because a stack row is found by one key and the
         * widget placed there is one widget (see {@code ScrubPairField}); the second half is reached
         * through {@link #right()}. Both halves are ordinary single-row actions, so a pair of a field and
         * a choice is written from the same two factories a full-width row would use.
         */
        public static Action pair(Action left, Action right) {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
            return new Action(left.key(), left.label(), null, Kind.PAIR, right);
        }

        /** A label and a value chosen from a list. The screen supplies the options. */
        public static Action choice(String key, String label) {
            return new Action(key, label, null, Kind.CHOICE, null);
        }

        /** A label and an inline colour chip. */
        public static Action chip(String key, String label) {
            return new Action(key, label, null, Kind.CHIP, null);
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

        /** Whether the row is edited through a widget rather than by being pressed. */
        public boolean isField() {
            return kind == Kind.FIELD || kind == Kind.PAIR;
        }

        public boolean isChoice() {
            return kind == Kind.CHOICE;
        }

        public boolean isChip() {
            return kind == Kind.CHIP;
        }
    }

    /** How much of a field row's width its label keeps. */
    public static final int LABEL_ROOM = 44;

    /**
     * A colour chip's box: swatch, hex and alpha, against the row's right edge.
     *
     * <p>Here rather than in the drawing because the press that opens the picker is tested with it too: a
     * chip drawn in one place and hit-tested from another is the pair of descriptions this class exists to
     * prevent. The width is what the three texts need; on a narrow row the hex gives up its room rather
     * than the chip giving up its right edge, so a column of chips stays a column.
     */
    public static BookGeometry.Rect chip(Slot row) {
        Objects.requireNonNull(row, "row");
        int width = Math.min(132, Math.max(0, row.width() - LABEL_ROOM));
        return BookGeometry.Rect.at(row.right() - width, row.y(), width, row.height());
    }

    /** Between the two halves of a pair row, so their boxes do not touch. */
    public static final int PAIR_GAP = 6;

    /** The left half of a pair row. See {@link #pairRight}. */
    public static Slot pairLeft(Slot row) {
        Objects.requireNonNull(row, "row");
        int half = Math.max(0, (row.width() - PAIR_GAP) / 2);
        return new Slot(row.key(), row.x(), row.y(), half, row.height());
    }

    /** The right half, under the second action's key. The split the pair widget itself uses. */
    public static Slot pairRight(Slot row, Object key) {
        Slot left = pairLeft(row);
        int x = left.right() + PAIR_GAP;
        return new Slot(key, x, row.y(), Math.max(0, row.right() - x), row.height());
    }

    /**
     * The book's own rows: the fold's heading, then the pack's name and icon while it is open.
     *
     * <p>Their own entry point rather than part of {@link #appearanceRows}: the appearance sections
     * write a theme, and these write the pack's {@code index.json} — the one place this panel edits a
     * file that is not the player's own look. Kept together here so the tab that shows them has one
     * list to build from, and so a reader of this class can see the whole of what a book row is.
     */
    public static List<Action> bookRows(boolean open) {
        List<Action> rows = new ArrayList<>();
        rows.add(Action.heading(BOOK_SECTION,
                (open ? "\u25bc " : "\u203a ") + "tasked.dev.tools.book"));
        if (open) {
            rows.add(Action.row(BOOK_TITLE, "tasked.dev.tools.book_title"));
            rows.add(Action.row(BOOK_ICON, "tasked.dev.tools.book_icon"));
        }
        return List.copyOf(rows);
    }

    /**
     * Where a field's own control goes: the row's right-hand side, after the label's room.
     *
     * <p>The counterpart of {@link #strip} for a row that is a text field rather than a button: the
     * label is drawn by the panel in the room this keeps, and the field is placed in what is left.
     */
    public static Slot valueField(Slot row, int labelRoom) {
        Objects.requireNonNull(row, "row");
        int room = Math.min(labelRoom, row.width());
        return new Slot(row.key(), row.x() + room, row.y(), Math.max(0, row.width() - room), row.height());
    }

    // ------------------------------------------------------------------
    // The texture row's three controls
    // ------------------------------------------------------------------

    /**
     * The texture row is a field and two boxes: the file's own picture, the id, and a browse button.
     *
     * <h2>Why the row carries three controls where the book's field carries one</h2>
     *
     * <p>The book's name and icon are typed or picked with one control each, because one of them is a
     * name and the other a thing you can see in the button. A texture is both at once -- a long id that
     * is only readable when typed or pasted, and a picture that is only judgeable when seen -- so the
     * field keeps its place for the paste and the boxes carry what the id cannot say. The three are
     * derived here together, so the drawing and the hit test cannot disagree about which is where.
     */
    public static final int TEXTURE_THUMB = 14;

    /** The browse button's width, matching a stepper's arrows so the row's right edge stays a column. */
    public static final int TEXTURE_BROWSE = 16;

    /** The picture box, between the label and the field. */
    public static Slot textureThumb(Slot row) {
        Objects.requireNonNull(row, "row");
        int size = Math.min(TEXTURE_THUMB, Math.max(0, row.height()));
        return new Slot("thumb", row.x() + LABEL_ROOM, row.y() + (row.height() - size) / 2,
                size, size);
    }

    /** The browse button, against the row's right edge. */
    public static Slot textureBrowse(Slot row) {
        Objects.requireNonNull(row, "row");
        int right = row.right() - STRIP_INSET;
        int left = Math.max(row.x(), right - TEXTURE_BROWSE);
        return new Slot("browse", left, row.y(), Math.max(0, right - left), row.height());
    }

    /** The id's field, between the picture and the browse button. */
    public static Slot textureField(Slot row) {
        Slot thumb = textureThumb(row);
        Slot browse = textureBrowse(row);
        int x = thumb.right() + 3;
        return new Slot(row.key(), x, row.y(), Math.max(0, browse.x() - 3 - x), row.height());
    }

    /** Whether a press at a point is on the texture row's browse button. */
    public static boolean textureBrowseAt(Viewport view, Slot row, double mouseX, double mouseY) {
        return row != null && textureBrowse(onScreen(view, row)).contains((int) mouseX, (int) mouseY);
    }

    /**
     * The book tab's list: the three switches, then the appearance sections.
     *
     * @param motionOn    what the Motion switch says
     * @param snapOn      what the Snap switch says
     * @param progressOn  what the Progress switch says
     * @param coloursOpen whether the colour section is unfolded
     */
    public static List<Action> rows(boolean motionOn, boolean snapOn, boolean progressOn,
                                    boolean coloursOpen) {
        return rows(motionOn, snapOn, progressOn, coloursOpen, List.of(), true,
                CanvasBackground.NONE, true, null);
    }

    /**
     * The same, with the palette list where it belongs.
     *
     * <p>The list sits after the mode switches and before Shape: the switches are about how the panel
     * works, and the palette is the first decision about the content the sections below edit. A caller
     * with no themes to offer passes an empty list and gets no section at all, rather than a heading
     * over nothing.
     *
     * @param palette     the palettes a picker offers, in catalogue order
     * @param paletteOpen whether the palette section is unfolded
     */
    public static List<Action> rows(boolean motionOn, boolean snapOn, boolean progressOn,
                                    boolean coloursOpen, List<Palette> palette, boolean paletteOpen) {
        return rows(motionOn, snapOn, progressOn, coloursOpen, palette, paletteOpen,
                CanvasBackground.NONE, true, null);
    }

    /**
     * The same, with the panel's actual target behind the appearance rows.
     *
     * <p>The background and the copy label are the screen's to supply because they are the target's:
     * a chapter and the player's own look can carry different patterns, and the copy row's label says
     * whether it is waiting for a confirmation. See {@link #canvasRows}.
     */
    public static List<Action> rows(boolean motionOn, boolean snapOn, boolean progressOn,
                                    boolean coloursOpen, List<Palette> palette, boolean paletteOpen,
                                    CanvasBackground background, boolean canvasOpen, String copyLabel) {
        List<Action> rows = new ArrayList<>();
        rows.add(Action.toggle(MOTION, "tasked.dev.tools.motion", motionOn ? ON : OFF));
        rows.add(Action.toggle(SNAP, "tasked.dev.tools.snap", snapOn ? ON : OFF));
        // The book's own switch, beside the rest: a reading preference like motion rather than an edit.
        rows.add(Action.toggle(PROGRESS, "tasked.dev.tools.progress", progressOn ? ON : OFF));
        rows.addAll(appearanceRows(palette, paletteOpen, coloursOpen, background, canvasOpen, copyLabel));
        return List.copyOf(rows);
    }

    /**
     * The appearance sections, shared by both tabs.
     *
     * <p>Palette, Shape, Canvas and Colours, in the order they have always been: what look this is,
     * the one knob that is not a colour, the surface, and then the colours themselves. Both tabs show
     * exactly this; the difference between them is which theme the values are read from and written to,
     * which the screen decides, not the layout.
     */
    public static List<Action> appearanceRows(List<Palette> palette, boolean paletteOpen,
                                              boolean coloursOpen, CanvasBackground background,
                                              boolean canvasOpen, String copyLabel) {
        Objects.requireNonNull(palette, "palette");
        List<Action> rows = new ArrayList<>();

        if (!palette.isEmpty()) {
            rows.add(Action.heading(PALETTE_SECTION,
                    (paletteOpen ? "\u25bc " : "\u203a ") + "tasked.dev.tools.palette"));
            if (paletteOpen) {
                for (Palette option : palette) {
                    rows.add(Action.row(paletteKey(option.id()), option.label()));
                }
            }
        }

        // Shape before colours: the one knob that is not a colour, then the palette.
        rows.add(Action.heading(SHAPE_SECTION, "tasked.dev.tools.shape"));
        // A field row: the number is dragged or typed at the row, rather than stepped by arrows and
        // selected for a band. See `ScrubField`.
        rows.add(Action.field(RADIUS, "tasked.dev.tools.radius"));

        // The canvas's surface: what is drawn over the canvas colour. Under Shape because it is the
        // same kind of decision -- the look of the surface rather than one of its colours -- and the
        // colours section below can then stay a list of swatches.
        rows.addAll(canvasRows(background, canvasOpen, copyLabel));

        // The marker is a filled triangle and a single angle: the disclosure pair this font carries. The
        // empty triangles it started as (`\u25be`/`\u25b8`) are not in it and drew as boxes -- see
        // `BookGeometry.TOOLS_PILL_WIDTH` for how the set of available glyphs was measured.
        rows.add(Action.heading(COLOUR_SECTION,
                (coloursOpen ? "\u25bc " : "\u203a ") + "tasked.dev.tools.colours"));
        if (coloursOpen) {
            ThemeToken.Group last = null;
            for (ThemeToken token : ThemeToken.ALL) {
                if (token.id().equals("canvasPattern")
                        && background.kind() != CanvasBackground.Kind.IMAGE) {
                    // The canvas section's Ink row is this token's row whenever the canvas draws in
                    // ink; listing it again here would be one token with two rows -- and a layout
                    // cannot hold two rows under one key. An image uses the token only for the tint's
                    // alpha, which the Opacity row owns, so the colours list keeps the row for it --
                    // every token has exactly one row either way.
                    continue;
                }
                if (token.group() != last) {
                    // A heading, not a row: a group's name is drawn and does nothing, and a pressable row
                    // that selects nothing is worse than plain text.
                    rows.add(Action.heading("group:" + token.group().name(), token.group().label()));
                    last = token.group();
                }
                rows.add(Action.chip(tokenKey(token.id()), token.label()));
            }
        }
        return List.copyOf(rows);
    }

    /**
     * The canvas section's rows: the pattern, whatever numbers that pattern's own kind carries, the
     * anchoring and ink every flat pattern shares, and the two rows that act on the lot -- the live
     * strip and the copy.
     *
     * <h2>One table, and it is exact</h2>
     *
     * <p>Which rows a background gets is a property of the background, not of the drawing: a dot grid
     * has no direction to flip, an image has no spacing to anchor, and a covered image has no tile to
     * size. The table is written once, here, so the panel that draws the rows and the screen that
     * presses them cannot disagree about which exist -- a row shown but not handled is a control that
     * lies, and one handled but not shown is a control nobody can reach.
     *
     * <p>The strip and the copy are always there, whatever the kind: the first is how the pattern is
     * judged and the second is how the chapters are made to match, and neither is about a number the
     * kind above it owns. The ink is a colour row, not a new key -- it is {@code canvasPattern}, and
     * the selection machinery already knows what to do with one.
     *
     * @param background the background in force for the panel's target
     * @param canvasOpen whether the section is unfolded; folded, it is the heading and nothing else
     * @param copyLabel  the label the copy row carries -- the caller passes the armed variant while
     *     the copy is armed, and null for the plain one
     */
    public static List<Action> canvasRows(CanvasBackground background, boolean canvasOpen,
                                          String copyLabel) {
        Objects.requireNonNull(background, "background");
        List<Action> rows = new ArrayList<>();
        rows.add(Action.heading(CANVAS_SECTION,
                (canvasOpen ? "\u25bc " : "\u203a ") + "tasked.dev.tools.canvas_section"));
        if (!canvasOpen) {
            return List.copyOf(rows);
        }

        rows.add(Action.choice(CANVAS_PATTERN, "tasked.dev.tools.canvas_pattern"));

        CanvasBackground.Kind kind = background.kind();
        if (kind == CanvasBackground.Kind.IMAGE) {
            // An image is the one kind whose look is a file: what to draw, how it meets the rectangle,
            // and -- only when it repeats -- the width one repeat is drawn at.
            rows.add(Action.row(CANVAS_TEXTURE, "tasked.dev.canvas.texture"));
            rows.add(Action.choice(CANVAS_FIT, "tasked.dev.canvas.fit"));
            if (background.image().fit() == CanvasBackground.Fit.TILE) {
                rows.add(Action.field(CANVAS_TILE, "tasked.dev.canvas.tile"));
            }
            // No ink row and no anchor: an image's strength is the file's own alpha, and it is pinned to
            // the screen. Opacity is still offered -- it scales that alpha -- and it takes the full row
            // because there is no anchor to pair it with.
            rows.add(Action.field(CANVAS_OPACITY, "tasked.dev.canvas.opacity"));
        }
        else {
            // The four procedural kinds share a mark size; the hatch has a direction and the speckle a
            // density, and `none` has nothing to size because it draws nothing. The anchoring and the
            // ink are the procedural half's own.
            if (kind == CanvasBackground.Kind.HATCH) {
                rows.add(Action.choice(CANVAS_DIRECTION, "tasked.dev.canvas.direction"));
            }
            if (kind == CanvasBackground.Kind.SPECKLE) {
                rows.add(Action.field(CANVAS_DENSITY, "tasked.dev.canvas.density"));
            }

            // The compact scalar lines the redesign asks for: one pair each. A half takes the whole row
            // when its partner is absent -- a size with nothing to space, an opacity with no anchor --
            // and a `none` background keeps spacing and opacity, which still apply to whatever pattern
            // is chosen next.
            Action size = kind == CanvasBackground.Kind.NONE
                    ? null : Action.field(CANVAS_SIZE, "tasked.dev.canvas.size");
            Action spacing = Action.field(CANVAS_SPACING, "tasked.dev.tools.canvas_spacing");
            rows.add(size == null ? spacing : Action.pair(size, spacing));

            rows.add(Action.pair(Action.field(CANVAS_OPACITY, "tasked.dev.canvas.opacity"),
                    Action.choice(CANVAS_SPACE, "tasked.dev.tools.canvas_space")));
            rows.add(Action.chip(tokenKey("canvasPattern"), "tasked.dev.canvas.ink"));
        }

        // The live strip is gone: the canvas itself is the preview now, and it follows the drag before
        // anything is written -- see the screen's preview draft. A second, smaller sample beside it was
        // a picture of the thing the author was already looking at.
        rows.add(Action.row(CANVAS_COPY, copyLabel == null || copyLabel.isEmpty()
                ? "tasked.dev.canvas.copy" : copyLabel));
        return List.copyOf(rows);
    }

    /**
     * The chapter tab's appearance part: the fold's heading, then the sections while it is open.
     *
     * <p>The heading is the whole part's fold, so an author who came for the chapter's content can put
     * the appearance away with one press -- and the sections under it are the same list the book tab
     * uses, so the two tabs cannot drift apart.
     */
    public static List<Action> chapterAppearanceRows(List<Palette> palette, boolean appearanceOpen,
                                                     boolean paletteOpen, boolean coloursOpen,
                                                     CanvasBackground background, boolean canvasOpen,
                                                     String copyLabel) {
        Objects.requireNonNull(palette, "palette");
        List<Action> rows = new ArrayList<>();
        rows.add(Action.heading(APPEARANCE_SECTION,
                (appearanceOpen ? "\u25bc " : "\u203a ") + "tasked.dev.tools.appearance"));
        if (appearanceOpen) {
            rows.addAll(appearanceRows(palette, paletteOpen, coloursOpen, background, canvasOpen,
                    copyLabel));
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
                // Every other kind is one row tall: a field (or a pair of them sharing the line), a
                // choice, a chip.
                case ROW, FIELD, PAIR, CHOICE, CHIP -> stack.row(row.key(), ROW_HEIGHT);
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
