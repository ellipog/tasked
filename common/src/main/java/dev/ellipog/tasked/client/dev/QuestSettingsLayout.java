package dev.ellipog.tasked.client.dev;

import com.google.gson.JsonObject;

import dev.ellipog.armature.client.ui.inspect.InspectLayout;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Stack;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.tasked.client.BookGeometry;
import dev.ellipog.tasked.quest.PrerequisiteMode;
import dev.ellipog.tasked.quest.QuestShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The quest settings page's arithmetic: the frame, the rows, the swatch grid, and the sliders.
 *
 * <h2>Why this is a class and not the screen's own sums</h2>
 *
 * <p>Because everything worth being wrong about here is arithmetic on integers — does a swatch's
 * rectangle contain the point the press landed on, does a slider's knob sit where the value says, does
 * the page still lay out when the window is small enough for the card to be at its minimum — and
 * arithmetic on integers is the thing this project's tests can hold without a client. The screen draws
 * what this says and asks it where the controls are; the drawing itself is {@code QuestSettingsPanel}'s.
 *
 * <h2>One derivation for the drawing and the press</h2>
 *
 * <p>{@link #cellRect} is how a swatch is drawn and {@link #cellAt} is how it is pressed, and the second
 * is defined in terms of the first's arithmetic rather than beside it. A grid drawn at one spacing and
 * hit at another is a grid an author cannot aim at, and the failure reads as a broken mouse rather than
 * as arithmetic — which is the same argument the item picker's rows and the tools panel's hotspots are
 * built on.
 *
 * <h2>What the page is</h2>
 *
 * <p>A live preview on the left, a column of controls on the right that scrolls when it does not fit,
 * and a line at the bottom that says what the control under the pointer is for. The rows come from
 * {@link #rows()} in one list, so the drawing, the widgets and the hit tests cannot disagree about what
 * the page contains — which is the fault {@code QuestPanelLayout} exists to prevent for the inspector.
 */
public final class QuestSettingsLayout {

    /** The preview column's width. Wide enough for a 96-pixel node and its name. */
    public static final int PREVIEW_WIDTH = 176;

    /** Between the preview column and the controls. */
    public static final int COLUMN_GAP = 8;

    /** A section's name. */
    public static final int HEADING_HEIGHT = 16;

    /** One control row. */
    public static final int ROW_HEIGHT = 20;

    /** One swatch: the shape, and its name under it. */
    public static final int SWATCH_HEIGHT = 46;

    /** Swatches across, at the normal card width. */
    public static final int SWATCH_COLUMNS = 4;

    /** The room a control's own widgets take at the right of its row. */
    public static final int STRIP_WIDTH = 132;

    /** How far a strip is inset from its row's right edge. */
    public static final int STRIP_INSET = 2;

    /** The help line under everything. */
    public static final int HELP_HEIGHT = 15;

    /** The line under the preview: what is being shown, and at what scale. */
    public static final int CAPTION_HEIGHT = 12;

    /** A slider's track height, and its knob. */
    public static final int TRACK_HEIGHT = 4;
    public static final int KNOB_WIDTH = 5;
    public static final int KNOB_HEIGHT = 12;

    /** A stepper's arrow box, and the value's room beside it. */
    public static final int STEPPER_WIDTH = 16;
    public static final int STEPPER_HEIGHT = 14;
    public static final int VALUE_WIDTH = 34;

    /** How much padding the node keeps inside the preview pane. */
    public static final int PREVIEW_PAD = 12;

    /** The size range the file format allows. The slider spans it, logarithmically. */
    public static final int MIN_SIZE = 16;
    public static final int MAX_SIZE = 512;

    /** The rotation's bounds, in degrees: a full turn is the shape itself, so 360 is written as 0. */
    public static final int MIN_ROTATION = 0;
    public static final int MAX_ROTATION = 359;

    /** The icon scale's range, re-exported from the geometry that computes the fit. */
    public static final double MIN_ICON_SCALE = QuestShape.MIN_ICON_SCALE;
    public static final double MAX_ICON_SCALE = QuestShape.MAX_ICON_SCALE;

    /** The row that adds every selected canvas node as a prerequisite. */
    public static final String DEPENDENCY_SELECTED = "dep:selected";

    /** The prefix of a prerequisite's own row, matching the inspector's own key scheme. */
    public static final String DEPENDENCY_PREFIX = QuestPanelLayout.DEPENDENCY_PREFIX;

    /**
     * The requirement's values, in the order the picker cycles them.
     *
     * <p>The empty string is the chapter's default, and it is first because it is the state a quest
     * starts in -- FTB Quests has the same three-way choice and calls it "Default". A picker rather than
     * a text box, because the four modes are a closed set and a typo in a text box is only refused by
     * the server.
     */
    public static List<String> requirementChoices() {
        List<String> choices = new ArrayList<>();
        choices.add("");
        for (PrerequisiteMode mode : PrerequisiteMode.values()) {
            choices.add(mode.name().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(choices);
    }

    /** The value a step lands on, wrapping at both ends. Empty is the chapter default. */
    public static String cycleRequirement(String current, int step) {
        List<String> choices = requirementChoices();
        int at = choices.indexOf(current == null ? "" : current.toLowerCase(Locale.ROOT));
        if (at < 0) {
            at = 0;
        }
        return choices.get(Math.floorMod(at + step, choices.size()));
    }

    /** A requirement value as a person reads it, with the chapter's default named where it applies. */
    public static String requirementLabel(String value, String chapterDefault) {
        if (value == null || value.isEmpty()) {
            return "Chapter default (" + chapterDefault.replace('_', ' ') + ")";
        }
        return value.replace('_', ' ');
    }

    /**
     * The auto-claim modes this row cycles, the unset state first.
     *
     * <p>The unset state and the file's own {@code "default"} mean the same thing — defer to the
     * chapter — so only one of them is offered: two choices that do the same thing is a picker with a
     * trap in it. The modes themselves are the reward-level vocabulary, so an author meets one set of
     * words wherever auto-claim is configured.
     */
    public static final List<String> AUTO_CLAIM_CHOICES =
            List.of("", "disabled", "enabled", "no_toast", "invisible");

    /** The value a step lands on, wrapping. Empty means "the chapter decides". */
    public static String cycleAutoClaim(String current, int step) {
        int at = AUTO_CLAIM_CHOICES.indexOf(current == null ? "" : current.toLowerCase(Locale.ROOT));
        if (at < 0) {
            at = 0;
        }
        return AUTO_CLAIM_CHOICES.get(Math.floorMod(at + step, AUTO_CLAIM_CHOICES.size()));
    }

    /** An auto-claim value as a person reads it; the unset state names what it defers to. */
    public static String autoClaimLabel(String value) {
        return value == null || value.isEmpty() ? "Chapter default" : value.replace('_', ' ');
    }

    private QuestSettingsLayout() {
    }

    /**
     * One row of the page.
     *
     * <p>{@code kind} is what the row <i>is</i>, not what it looks like: the drawing switches on it, the
     * screen builds a widget for the ones that need one, and the hit test asks it whether a press is the
     * row's business. {@code label} is the words beside the control.
     */
    public record Row(String key, Kind kind, String label) {

        public enum Kind {
            /** A section's name; nothing to press. */
            HEADING,
            /** One value of a closed set, cycled by its arrows. See {@link #requirementChoices}. */
            CHOICE,
            /** One prerequisite: its title, and a control in the strip that removes it. */
            DEPENDENCY,
            /** A row that is itself a button. */
            ACTION,
            /** The swatch grid: one block of rows, not a control. */
            SHAPE_GRID,
            /** A track with a knob, plus steppers for the exact value. */
            SLIDER,
            /** Two arrows and a value, with a typed field between them. */
            STEPPER,
            /** A two-state control. */
            SWITCH,
            /** A text field. */
            FIELD,
            /** A read-only value. */
            VALUE,
            /** The icon, and a button that opens the item picker. */
            ICON
        }
    }

    /**
     * The page's rows, in order.
     *
     * <p>One list, read by the layout, the drawing and the press. The order is the order of use: the
     * shape first because it is what an author opens this page for, then the size and the icon, then
     * placement, then the rules, then the identity extras that are almost never touched.
     */
    public static List<Row> rows() {
        return rows(new JsonObject());
    }

    /**
     * The page's rows for one quest, in order.
     *
     * <p>One list, read by the layout, the drawing and the press. The order is the order of use: the
     * shape first because it is what an author opens this page for, then the size and the icon, then
     * placement, then the dependencies and rules, then the identity extras that are almost never
     * touched.
     *
     * <p>Takes the quest because the dependency section has <b>one row per prerequisite</b>, which a
     * fixed list cannot say. The screen rebuilds on every replica arrival, so a prerequisite added by
     * any of the three paths appears in the list as soon as the server has it.
     */
    public static List<Row> rows(JsonObject quest) {
        return rows(quest, id -> id, 0);
    }

    /**
     * The page's rows for one quest, with the dependency list named and the selection counted.
     *
     * @param titles  what a prerequisite is called on screen -- the client cache's title, or the id when
     *     the cache has never heard of it. A function rather than a map because "unknown id" is a real
     *     answer that has to be produced for every miss, and a map that is missing a key produces null.
     * @param selected how many canvas nodes the "Add selected" row would add, for its label. Zero draws
     *     the row dimmed; the press is refused either way, so the label and the behaviour agree.
     */
    public static List<Row> rows(JsonObject quest, Function<String, String> titles, int selected) {
        if (quest == null) {
            // No replica yet: the fixed rows still lay out, and the dependency list is empty rather
            // than a crash. The page is redrawn when the replica arrives.
            quest = new JsonObject();
        }
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("h:shape", Row.Kind.HEADING, "Shape"));
        rows.add(new Row("shape", Row.Kind.SHAPE_GRID, "Shape"));
        rows.add(new Row("rotation", Row.Kind.SLIDER, "Rotation"));
        rows.add(new Row("h:size", Row.Kind.HEADING, "Size and icon"));
        rows.add(new Row("size", Row.Kind.SLIDER, "Size"));
        rows.add(new Row("iconScale", Row.Kind.SLIDER, "Icon scale"));
        rows.add(new Row("icon", Row.Kind.ICON, "Icon"));
        rows.add(new Row("showTitle", Row.Kind.SWITCH, "Show title"));
        rows.add(new Row("h:placement", Row.Kind.HEADING, "Placement"));
        rows.add(new Row("x", Row.Kind.STEPPER, "X"));
        rows.add(new Row("y", Row.Kind.STEPPER, "Y"));
        rows.add(new Row("h:dependencies", Row.Kind.HEADING, "Dependencies"));
        // One row per prerequisite, then the three ways to add one. The list is the point of the
        // section: an author reads what a quest needs before changing what it needs.
        for (String dependency : QuestPanelLayout.strings(quest, "dependsOn")) {
            // The key keeps the id -- that is what the press needs -- and the label is the title,
            // because an author recognises "Smelt Iron" and not "smelt_iron".
            rows.add(new Row(DEPENDENCY_PREFIX + dependency, Row.Kind.DEPENDENCY,
                    titles.apply(dependency)));
        }
        rows.add(new Row(QuestPanelLayout.DEPENDENCY_ADD, Row.Kind.FIELD, "Add by id"));
        rows.add(new Row(QuestPanelLayout.DEPENDENCY_PICK, Row.Kind.ACTION, "Pick on the canvas"));
        rows.add(new Row(DEPENDENCY_SELECTED, Row.Kind.ACTION,
                selected > 0 ? "Add selected (" + selected + ")" : "Add selected"));
        // The rule the list is judged by, then the two counts that qualify it. FTB Quests' three
        // controls, in its order: the requirement, the minimum, and the cap on dependents.
        rows.add(new Row("prerequisiteMode", Row.Kind.CHOICE, "Requirement"));
        rows.add(new Row("minRequired", Row.Kind.STEPPER, "Min required"));
        rows.add(new Row("maxCompletableDependents", Row.Kind.STEPPER, "Max dependents"));
        rows.add(new Row("exclusiveGroup", Row.Kind.FIELD, "Exclusive group"));
        rows.add(new Row("h:visibility", Row.Kind.HEADING, "Visibility"));
        rows.add(new Row("invisible", Row.Kind.SWITCH, "Invisible"));
        rows.add(new Row("invisibleUntilTasks", Row.Kind.STEPPER, "Visible after tasks"));
        rows.add(new Row("hideUntilDependenciesComplete", Row.Kind.SWITCH, "Hide until deps done"));
        rows.add(new Row("hideUntilDependenciesVisible", Row.Kind.SWITCH, "Hide until deps shown"));
        rows.add(new Row("hideDependencyLines", Row.Kind.SWITCH, "Hide dependency lines"));
        rows.add(new Row("hideTextUntilComplete", Row.Kind.SWITCH, "Hide text until done"));
        rows.add(new Row("hideDetailsUntilStartable", Row.Kind.SWITCH, "Hide details until startable"));
        rows.add(new Row("h:rules", Row.Kind.HEADING, "Rules"));
        rows.add(new Row("repeatable", Row.Kind.SWITCH, "Repeatable"));
        rows.add(new Row("repeatCooldownTicks", Row.Kind.STEPPER, "Repeat cooldown"));
        rows.add(new Row("sequentialTasks", Row.Kind.SWITCH, "Sequential tasks"));
        // The quest rung of the auto-claim ladder, as a closed set the picker cycles: the unset state
        // means "the chapter decides", which is why it is first and labelled with what it defers to.
        rows.add(new Row("autoClaim", Row.Kind.CHOICE, "Auto-claim"));
        rows.add(new Row("h:identity", Row.Kind.HEADING, "Identity extras"));
        rows.add(new Row("id", Row.Kind.VALUE, "Id"));
        rows.add(new Row("aliases", Row.Kind.FIELD, "Aliases"));
        return List.copyOf(rows);
    }

    /** The swatch grid's block height: as many rows of swatches as the shapes need. */
    public static int gridHeight() {
        int count = QuestShape.values().length;
        int lines = (count + SWATCH_COLUMNS - 1) / SWATCH_COLUMNS;
        return lines * SWATCH_HEIGHT;
    }

    /**
     * The page's rectangles, from the card's body.
     *
     * <p>The preview is a fixed column on the left; the controls take the rest and scroll; the help line
     * is pinned along the bottom, because a line that scrolls away is a line that is not there when the
     * pointer is over the control it describes. Every rectangle is clamped so a card at its minimum size
     * produces empty rectangles rather than negative ones — the same rule the panels follow, and for the
     * same reason: a negative width draws backwards.
     */
    public record Frame(BookGeometry.Rect preview, BookGeometry.Rect caption,
                        BookGeometry.Rect controls, BookGeometry.Rect help) {

        public static Frame of(BookGeometry.Rect body) {
            int helpHeight = Math.min(HELP_HEIGHT, Math.max(0, body.height()));
            BookGeometry.Rect help = BookGeometry.Rect.at(body.x(), body.bottom() - helpHeight,
                    Math.max(0, body.width()), helpHeight);

            int tall = Math.max(0, body.height() - helpHeight - 2);
            int previewWidth = Math.min(PREVIEW_WIDTH, Math.max(0, (body.width() - COLUMN_GAP) / 2));
            int captionHeight = Math.min(CAPTION_HEIGHT, Math.max(0, tall - 24));
            BookGeometry.Rect caption = BookGeometry.Rect.at(body.x(),
                    body.y() + tall - captionHeight, previewWidth, captionHeight);
            BookGeometry.Rect preview = BookGeometry.Rect.at(body.x(), body.y(), previewWidth,
                    Math.max(0, tall - captionHeight - 2));
            BookGeometry.Rect controls = BookGeometry.Rect.at(body.x() + previewWidth + COLUMN_GAP,
                    body.y(), Math.max(0, body.width() - previewWidth - COLUMN_GAP), tall);
            return new Frame(preview, caption, controls, help);
        }
    }

    /** The rows laid out into the controls column, so a scrolling view can place and cull them. */
    public static Layout build(List<Row> rows, int width, Measure measure) {
        Stack stack = Stack.stack();
        boolean first = true;
        for (Row row : rows) {
            if (!first) {
                stack.gap(row.kind() == Row.Kind.HEADING ? 8 : 1);
            }
            first = false;
            switch (row.kind()) {
                case HEADING -> stack.row(row.key(), HEADING_HEIGHT);
                case SHAPE_GRID -> stack.row(row.key(), gridHeight());
                // Every row is full width, and the control's strip is taken from its right end by
                // `strip`. Reserving the strip as a row *inset* as well -- which is what the inspector
                // does -- narrows the slot and then takes the strip out of the narrowed slot, so the
                // controls land a whole strip's width short of the column's edge and the label is
                // truncated into the space that was reserved for it. One reserve, not two.
                case VALUE, SLIDER, STEPPER, SWITCH, FIELD, ICON, CHOICE, DEPENDENCY, ACTION ->
                        stack.row(row.key(), ROW_HEIGHT);
            }
        }
        return stack.build(Math.max(0, width), measure);
    }

    /** Where a row's control goes: in the room at the right of its row, against the column's edge. */
    public static Slot strip(Slot row) {
        int width = Math.min(STRIP_WIDTH, Math.max(0, row.width() - STRIP_INSET));
        return new Slot(row.key(), row.right() - width, row.y(), width, row.height());
    }

    // ------------------------------------------------------------------
    // The swatch grid
    // ------------------------------------------------------------------

    /** How many swatches fit across a grid of the given width, at least one. */
    public static int columns(int gridWidth) {
        return Math.max(1, Math.min(SWATCH_COLUMNS, gridWidth / 40));
    }

    /**
     * One swatch's rectangle, in the grid block's own coordinates.
     *
     * <p>Every cell is the same size, so the grid is a division rather than a running x — which is what
     * makes {@link #cellAt} the same arithmetic backwards instead of a second walk.
     */
    public static BookGeometry.Rect cellRect(Slot grid, int index, int count) {
        int columns = columns(grid.width());
        int cellWidth = Math.max(1, grid.width() / columns);
        int row = index / columns;
        int column = index % columns;
        int x = grid.x() + column * cellWidth;
        int y = grid.y() + row * SWATCH_HEIGHT;
        int width = column == columns - 1
                ? Math.max(0, grid.right() - x) : cellWidth;
        int height = Math.min(SWATCH_HEIGHT, Math.max(0, grid.bottom() - y));
        return BookGeometry.Rect.at(x, y, Math.max(0, width), Math.max(0, height));
    }

    /** Which swatch a point is over, or -1. The drawing's own arithmetic, run backwards. */
    public static int cellAt(Slot grid, double px, double py, int count) {
        if (px < grid.x() || px >= grid.right() || py < grid.y() || py >= grid.bottom()) {
            return -1;
        }
        for (int index = 0; index < count; index++) {
            if (cellRect(grid, index, count).contains(px, py)) {
                return index;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // The sliders
    // ------------------------------------------------------------------

    /**
     * A slider's track: between the down arrow and the value, and clear of both.
     *
     * <p>The track is the slider's own; the arrows either side of it are the exact-value steppers, so a
     * value the track cannot reach — a size of 17 on a logarithmic track — is still one press away.
     */
    public static BookGeometry.Rect track(Slot strip) {
        int left = strip.x() + STEPPER_WIDTH + 4;
        int right = strip.right() - STEPPER_WIDTH - VALUE_WIDTH - 4;
        int y = strip.y() + (strip.height() - TRACK_HEIGHT) / 2;
        return BookGeometry.Rect.at(left, y, Math.max(0, right - left), TRACK_HEIGHT);
    }

    /** Where a slider's knob sits for a value, as a rectangle centred on it. */
    public static BookGeometry.Rect knob(BookGeometry.Rect track, double value, double min, double max,
                                         boolean logarithmic) {
        int x = knobX(track, value, min, max, logarithmic);
        int y = track.y() + track.height() / 2 - KNOB_HEIGHT / 2;
        return BookGeometry.Rect.at(x - KNOB_WIDTH / 2, y, KNOB_WIDTH, KNOB_HEIGHT);
    }

    /** The x a value's knob is centred on, in the track's own coordinates. */
    public static int knobX(BookGeometry.Rect track, double value, double min, double max,
                            boolean logarithmic) {
        double fraction = fractionOf(value, min, max, logarithmic);
        return track.x() + (int) Math.round(fraction * Math.max(0, track.width() - 1));
    }

    /**
     * The value a point on a track names.
     *
     * <p>Logarithmic when asked, and that is the right shape for a size: a node goes from 16 to 512, and
     * on a linear track the useful 16..64 would be a fifth of the travel while a doubling of the size
     * would be a different distance at every scale. On a logarithmic track a doubling is the same
     * distance everywhere, which is what the eye expects of a size.
     */
    public static int valueAt(BookGeometry.Rect track, double px, int min, int max, boolean logarithmic) {
        int span = Math.max(1, track.width() - 1);
        double fraction = Math.max(0, Math.min(1, (px - track.x()) / (double) span));
        double value = logarithmic
                ? min * Math.pow(max / (double) min, fraction)
                : min + fraction * (max - min);
        return Math.max(min, Math.min(max, (int) Math.round(value)));
    }

    /**
     * The value a <b>screen</b> point names on a row's slider.
     *
     * <h2>Why this is a method here and not three lines in the screen</h2>
     *
     * <p>Because the screen got it wrong in the one way that is invisible in the drawing and obvious in
     * the hand: it derived the track from the <b>layout's</b> slot — whose x is a distance across the
     * column, counted from zero — and compared that against a <b>screen</b> pointer. Every press in the
     * column was therefore far to the right of the track it thought it was on, so the value clamped to
     * the maximum on the first pixel of a drag and stayed there. The drawing had been mapped through the
     * viewport; the input had not.
     *
     * <p>So the mapping is a function of (the row's layout slot, the viewport the page is drawn through,
     * the pointer) and nothing else, which makes it something a test can hold: a press at the track's
     * left end names the minimum and one at its right end the maximum, with the track computed in the
     * same space the pointer is in.
     */
    public static int valueAtScreen(Slot layoutSlot, Viewport column, double mouseX, int min, int max,
                                    boolean logarithmic) {
        Slot onScreen = InspectLayout.onScreen(column, strip(layoutSlot));
        return valueAt(track(onScreen), mouseX, min, max, logarithmic);
    }

    /** Where a value sits on its track, 0 at the left and 1 at the right. */
    public static double fractionOf(double value, double min, double max, boolean logarithmic) {
        double clamped = Math.max(min, Math.min(max, value));
        if (logarithmic) {
            return Math.log(clamped / min) / Math.log(max / min);
        }
        return (clamped - min) / (max - min);
    }

    // ------------------------------------------------------------------
    // The steppers
    // ------------------------------------------------------------------

    /** One of a stepper's two arrows, in the strip's own coordinates: what is drawn and hit alike. */
    public static BookGeometry.Rect stepperBox(Slot strip, String way) {
        int x = way.equals("down") ? strip.x() : strip.right() - STEPPER_WIDTH;
        int y = strip.y() + (strip.height() - STEPPER_HEIGHT) / 2;
        return BookGeometry.Rect.at(x, y, STEPPER_WIDTH, STEPPER_HEIGHT);
    }

    /** Which way a press at a point steps a row: -1, +1, or null. The drawing's own boxes. */
    public static Integer stepperStepAt(Slot strip, double px, double py) {
        if (stepperBox(strip, "down").contains(px, py)) {
            return -1;
        }
        if (stepperBox(strip, "up").contains(px, py)) {
            return 1;
        }
        return null;
    }

    /** Where a stepper's typed value sits: between the arrows, clear of both. */
    public static BookGeometry.Rect valueBox(Slot strip) {
        int left = strip.x() + STEPPER_WIDTH + 2;
        int width = Math.max(0, strip.right() - STEPPER_WIDTH - 2 - left);
        return BookGeometry.Rect.at(left, strip.y() + 2, width, Math.max(0, strip.height() - 4));
    }

    /**
     * Where a prerequisite's remove control sits: the right of the strip, square.
     *
     * <p>Square and small, and at the far right of every row so the column of x's lines up -- a control
     * that moves with the length of the title beside it is a control an author has to hunt for.
     */
    public static BookGeometry.Rect removeBox(Slot strip) {
        int side = Math.min(12, strip.height());
        return BookGeometry.Rect.at(strip.right() - side, strip.y() + (strip.height() - side) / 2,
                side, side);
    }

    /** Whether a press removes the prerequisite on this row. The box the drawing uses. */
    public static boolean removeAt(Slot strip, double px, double py) {
        return removeBox(strip).contains(px, py);
    }

    /** Where an action row's button sits: the whole strip, less a pixel at each end. */
    public static BookGeometry.Rect actionBox(Slot strip) {
        return BookGeometry.Rect.at(strip.x(), strip.y() + 1, strip.width(),
                Math.max(0, strip.height() - 2));
    }

    /** Whether a press lands on an action row's button. */
    public static boolean actionAt(Slot strip, double px, double py) {
        return actionBox(strip).contains(px, py);
    }

    /** Where a switch's track sits: at the right of the strip, with room for its label to the left. */
    public static BookGeometry.Rect switchTrack(Slot strip) {
        int width = 26;
        int height = 12;
        return BookGeometry.Rect.at(strip.right() - width, strip.y() + (strip.height() - height) / 2,
                width, height);
    }
}
