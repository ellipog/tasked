package dev.ellipog.tenet.client.hud;

import dev.ellipog.armature.client.ui.kit.Measure;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * The pinned-quests panel's arithmetic: what it holds, how tall it is, and where each row sits.
 *
 * <h2>Why this is a class and not arithmetic in the painter</h2>
 *
 * <p>Because a painted panel cannot be asked a question. The HUD is drawn by a renderer that needs a live
 * client, so "the panel is as tall as what it holds", "the head's tasks are the only ones listed", "a long
 * title is truncated rather than allowed to widen the box off the window" and "the editor's empty sample is
 * still a box you can grab" are facts nothing could check if they lived in a draw call -- and every one of
 * them goes wrong silently, as a panel that grows without bound or an element that cannot be picked up.
 * Integers and strings here are swept by {@code PinnedPanelLayoutTest}, and the painter <b>builds itself
 * from them</b>: every rectangle drawn comes from a row of this list, so the test and the drawing cannot be
 * describing two different panels. That is the rule {@code HudLayout} and the book's own geometry were
 * extracted for.
 *
 * <h2>The rows carry their own text, already cut to fit</h2>
 *
 * <p>{@link Row#text()} is what will be drawn, not what was authored: the truncation happens here, where the
 * box's width and the font are both known, so the painter has no measurement left to do and no opportunity
 * to disagree with the box the test asserted. The row is then a left-to-right sequence -- an icon, then its
 * text, then a count, then a tick -- and the drawing advances by measuring those same strings, which is the
 * one place a second opinion could have crept in.
 *
 * <h2>What it is given, and what it deliberately is not</h2>
 *
 * <p>Data, not the game: a list of {@link Pin}, assembled one layer up from {@code ClientQuestCache} by the
 * painter, and a {@link Measure} for the font. No {@code ItemStack}, no screen, no window -- so the whole of
 * the panel's shape is assertable headless, and the icon a task draws cannot change where its row is. The
 * task's <i>index</i> travels on the row instead of its icon, which is what lets the painter find the stack
 * without this class ever holding one.
 *
 * <p>The <b>words</b> arrive resolved for the same reason the icon does not: this class has no business
 * knowing what language the player reads, and a sentence built here would be one a translator cannot reach.
 * The one exception is {@link Words#moreTasks}, a function rather than a string, because that sentence
 * carries a count nobody knows until the panel is laid out -- and a placeholder a translator can move is
 * better than a number glued into an English fragment.
 */
public final class PinnedPanelLayout {

    /** The inset on every side of the box. */
    public static final int PAD = 4;

    /** The clear space between two rows. */
    public static final int GAP = 2;

    /** A task's icon box, in pixels: the size the book's own task rows use. */
    public static final int ICON = 12;

    /** The gap between a task's icon and its text. */
    public static final int ICON_GAP = 5;

    /** The gap between a row's text and whatever follows it: a count, a tick or a chapter. */
    public static final int COLUMN_GAP = 4;

    /** What separates a quest's name from the chapter riding after it. */
    public static final String NOTE_SEPARATOR = "  \u00b7  ";

    /**
     * The tick a finished task wears.
     *
     * <p>One character, and one the font carries: {@code BookGeometry}'s own note lists the symbols this
     * font does <i>not</i> have, and a missing glyph is a box rather than a wrong colour. It is the same
     * character the book's prerequisite rows already draw.
     */
    public static final String TICK = "\u2714";

    /**
     * The narrowest the panel may be.
     *
     * <p>Wide enough for the several characters a title has to show before it is a smudge: below this the
     * panel is not a panel, and a title truncated to four letters is worse than a wide box on a small
     * window -- see {@link #MAX_WIDTH} for the other end.
     */
    public static final int MIN_WIDTH = 96;

    /** And the widest, because a HUD panel is not a book: a long title is truncated, not obeyed. */
    public static final int MAX_WIDTH = 190;

    /**
     * How many of the focused quest's tasks are listed before the rest are counted.
     *
     * <p>A quest may have twenty tasks, and a HUD element is not a scroll: past this the panel says how many
     * are left rather than growing past the bottom of somebody's window.
     */
    public static final int MAX_TASK_ROWS = 6;

    /** What a row is, which decides its order, its ink and nothing else. */
    public enum Kind {

        /** The panel's own name, at the top. */
        HEADER,

        /** One pinned quest's name, with its chapter after it. */
        TITLE,

        /** One task of the focused quest: its icon, its text, its count and its tick. */
        TASK,

        /** How many of the focused quest's tasks are not listed. */
        MORE,

        /** The focused quest is done. */
        COMPLETE,

        /** The editor's stand-in, when there is nothing pinned to draw. */
        SAMPLE
    }

    /**
     * One task, as the panel needs it.
     *
     * @param text     the sentence the row reads, already resolved
     * @param progress how far along it is
     * @param count    what it is counting to
     * @param done     whether it is finished, and so wears the tick
     */
    public record Task(String text, int progress, int count, boolean done) {
    }

    /**
     * One pinned quest, as the panel needs it.
     *
     * @param title    the quest's name
     * @param chapter  where it sits, drawn faint after the name; empty for none
     * @param complete whether the quest is done
     * @param tasks    its tasks, in the pack's order; empty for a quest with none
     */
    public record Pin(String title, String chapter, boolean complete, List<Task> tasks) {
    }

    /**
     * The panel's sentences, resolved by the caller. See the class note for why these are not built here.
     *
     * @param header    the panel's own name
     * @param complete  the word a finished focus wears
     * @param sample    what the editor draws when nothing is pinned
     * @param moreTasks "and N more", for the tasks past {@link #MAX_TASK_ROWS}
     */
    public record Words(String header, String complete, String sample, IntFunction<String> moreTasks) {
    }

    /**
     * One row, placed relative to the box's own top-left corner and already cut to fit.
     *
     * <p>The numbers are relative rather than on screen so that the panel can be moved without the layout
     * being recomputed -- a drag in the editor is then one addition, and there is no second place that could
     * measure the same content differently.
     *
     * @param text      what this row draws, truncated to the room it has
     * @param note      the faint second half of a title -- a quest's chapter -- or empty. Truncated too, and
     *                  cut against the title's <i>drawn</i> width, so the two cannot overlap
     * @param y         the row's own top
     * @param height    its own height: a line for a word, an icon's box for a title and a task
     * @param width     the content width every row is given, which is the box less its insets
     * @param taskIndex which task of the focused quest this row is, or -1. <b>The row carries it rather than
     *                  the position implying it</b>, because the painter needs that task's own icon and this
     *                  class deliberately never holds one: the lookup happens one layer up, and a row that
     *                  said "the third one" without saying which third would make the drawing depend on the
     *                  order this list happens to be built in
     * @param progress  a task's progress, and what the count column is made of
     * @param count     a task's target; one or less draws no count column, which is what the book does
     * @param done      for a task, whether it wears the tick; for a title, whether the quest is finished
     */
    public record Row(Kind kind, String text, String note, int y, int height, int width, int taskIndex,
                      int progress, int count, boolean done) {
    }

    /** The whole panel: its size, and every row in it. */
    public record Box(int width, int height, List<Row> rows) {

        /**
         * Whether there is nothing to draw.
         *
         * <p>The live answer for a player with nothing pinned: the element is on and it draws nothing at
         * all, rather than an empty frame. The editor never sees this, because its own face supplies the
         * sample.
         */
        public boolean empty() {
            return rows.isEmpty();
        }
    }

    private PinnedPanelLayout() {
    }

    /**
     * The panel for this content.
     *
     * @param pins    pinned quests, head first; an empty list is legal and is a player's usual state
     * @param words   the sentences, resolved
     * @param measure the font, which is the only thing here that knows how wide a character is
     * @param editor  whether this is the HUD editor's face, which draws a sample rather than nothing when
     *                there is nothing to draw -- a zero-sized element would be invisible <i>and</i>
     *                ungrabbable, which is the one arrangement that traps a player in that screen
     */
    public static Box box(List<Pin> pins, Words words, Measure measure, boolean editor) {
        if (pins.isEmpty() && !editor) {
            // See Box.empty: nothing pinned draws nothing. Asked here rather than by the painter, so that
            // "is this element empty" and "what does it hold" are one answer.
            return new Box(0, 0, List.of());
        }

        int line = Math.max(8, measure.lineHeight());
        int titleRow = Math.max(ICON, line) + GAP;
        List<Row> rows = new ArrayList<>();
        int cursor = PAD;

        cursor = add(rows, Kind.HEADER, words.header(), "", cursor, line, false, -1);
        if (pins.isEmpty()) {
            // The editor's sample: one line, so the element has a box and a grab.
            cursor = add(rows, Kind.SAMPLE, words.sample(), "", cursor, line, false, -1);
        }
        else {
            Pin head = pins.get(0);
            // The head's own title is not inked as done: its completion is the row below it, and a word and a
            // colour saying the same thing is the redundancy this table's kinds exist to avoid.
            cursor = add(rows, Kind.TITLE, head.title(), head.chapter(), cursor, titleRow, false, -1);
            int listed = Math.min(MAX_TASK_ROWS, head.tasks().size());
            for (int i = 0; i < listed; i++) {
                Task task = head.tasks().get(i);
                rows.add(new Row(Kind.TASK, task.text(), "", cursor, titleRow, 0, i,
                        task.progress(), task.count(), task.done()));
                cursor += titleRow + GAP;
            }
            if (head.tasks().size() > listed) {
                cursor = add(rows, Kind.MORE, words.moreTasks().apply(head.tasks().size() - listed), "",
                        cursor, line, false, -1);
            }
            if (head.complete) {
                cursor = add(rows, Kind.COMPLETE, words.complete(), "", cursor, line, false, -1);
            }
            // Every other pin is its name and nothing else, inked as done when it is. That is the panel's
            // whole answer to "collapsible" on a surface that cannot be clicked: the head is the focus, and
            // pinning something already pinned is what brings it forward.
            for (int i = 1; i < pins.size(); i++) {
                Pin pin = pins.get(i);
                cursor = add(rows, Kind.TITLE, pin.title(), pin.chapter(), cursor, titleRow, pin.complete(),
                        -1);
            }
        }

        // The last row's gap is not height, and the pad closes the box.
        int height = cursor - GAP + PAD;

        int widest = MIN_WIDTH;
        for (Row row : rows) {
            widest = Math.max(widest, wanted(row, measure) + PAD * 2);
        }
        int width = Math.min(widest, MAX_WIDTH);
        int content = width - PAD * 2;

        List<Row> placed = new ArrayList<>(rows.size());
        for (Row row : rows) {
            placed.add(truncated(row, content, measure));
        }
        return new Box(width, height, List.copyOf(placed));
    }

    /** Adds one text row and answers where the next one starts. */
    private static int add(List<Row> rows, Kind kind, String text, String note, int top, int height,
                           boolean done, int taskIndex) {
        rows.add(new Row(kind, text == null ? "" : text, note == null ? "" : note, top, height, 0, taskIndex,
                0, 0, done));
        return top + height + GAP;
    }

    /**
     * A task's count, as the row writes it.
     *
     * <p>Clamped, because a counter may overshoot its target and the row is a claim about the target rather
     * than about the counter -- the same reading {@code ClientQuestCache.taskDone} compares against.
     */
    public static String countText(Row row) {
        return Math.min(row.progress(), row.count()) + " / " + row.count();
    }

    /** Whether a task row draws a count at all: a task counting to one has nothing to count. */
    public static boolean hasCount(Row row) {
        return row.kind() == Kind.TASK && row.count() > 1;
    }

    /**
     * Everything a task row draws <b>after</b> its text, as one number.
     *
     * <p>One derivation for the two callers that need it -- the width the row wants, and the room its text
     * has left -- because those two are the same subtraction read in opposite directions, and a painter that
     * reserved its text one width and drew its count at another is the fault this method exists to make
     * impossible.
     */
    private static int afterText(Row row, Measure measure) {
        int width = 0;
        if (hasCount(row)) {
            width += COLUMN_GAP + measure.width(countText(row));
        }
        if (row.done()) {
            width += COLUMN_GAP + measure.width(TICK);
        }
        return width;
    }

    /** How wide one row wants to be, before the box's own clamp and its insets. */
    private static int wanted(Row row, Measure measure) {
        if (row.kind() == Kind.TASK) {
            return ICON + ICON_GAP + measure.width(row.text()) + afterText(row, measure);
        }
        int width = measure.width(row.text());
        if (!row.note().isEmpty()) {
            width += measure.width(NOTE_SEPARATOR) + measure.width(row.note());
        }
        return width;
    }

    /**
     * The same row with its two strings cut to the room the clamped box leaves them.
     *
     * <h2>The chapter never takes more than half the row</h2>
     *
     * <p>Because it is the decoration and the name is the row. Reserving the note's own width first has a
     * failure that only shows up on a long chapter name: the title is left with whatever is over — which at
     * one point was two pixels, so a quest drew a blank line on the panel and its chapter drew in full. Half
     * the row each is a rule rather than a tuned number, and the drawing note is cut to what is actually
     * left after the title, which is what stops the two overlapping when the title gives way.
     */
    private static Row truncated(Row row, int content, Measure measure) {
        String text;
        String note = row.note();
        if (row.kind() == Kind.TASK) {
            text = Measure.truncate(row.text(), Math.max(0, content - ICON - ICON_GAP
                    - afterText(row, measure)), measure);
        }
        else {
            int noteRoom = note.isEmpty() ? 0 : Math.min(
                    measure.width(NOTE_SEPARATOR) + measure.width(note), content / 2);
            text = Measure.truncate(row.text(), Math.max(0, content - noteRoom), measure);
            if (!note.isEmpty()) {
                int room = content - measure.width(text) - measure.width(NOTE_SEPARATOR);
                note = Measure.truncate(note, Math.max(0, room), measure);
            }
        }
        return new Row(row.kind(), text, note, row.y(), row.height(), content, row.taskIndex(),
                row.progress(), row.count(), row.done());
    }
}
