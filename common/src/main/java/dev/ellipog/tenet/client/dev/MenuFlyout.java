package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.client.BookGeometry;
import dev.ellipog.tenet.quest.DependencyStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * A style flyout: the preview-cell rows that replaced the line menu's text-only submenus.
 *
 * <h2>Why this is a class and not the screen's own sums</h2>
 *
 * <p>The same reason {@link MenuPlacement} is one: every question worth being wrong about here is
 * arithmetic on integers — how tall a row of previews is, which cell a press landed on, whether the
 * point is on the flyout at all — and arithmetic on integers is what this project's tests can hold
 * without a client. The screen draws what this says and asks it what a press means; the drawing itself
 * is {@link MenuFlyoutArt}'s.
 *
 * <h2>Why the rows are a model rather than a list of labels</h2>
 *
 * <p>The line menu's old submenus were text rows and nothing else, because that is all a Minecraft menu
 * row was. A style is not a list of names, though — "Circuit (45° chamfer)" is a shape, and the fastest
 * way to recognise a shape is to see it. A cell carries the value it writes, whether it is the value in
 * force, and what its preview shows, so the drawing and the press read one list.
 *
 * <h2>Depth stays two</h2>
 *
 * <p>One main menu, one flyout. The Arrows flyout holds all three arrow axes as three rows rather than
 * nested submenus, because a Minecraft GUI's nested flyouts close if the pointer strays a pixel off
 * their bounding box — the exact complaint this redesign answers.
 */
public final class MenuFlyout {

    /** One cell's preview: which axis it shows, and the value it shows. */
    public sealed interface Preview {
        record Form(DependencyStyle.Form form) implements Preview { }

        record Head(DependencyStyle.ArrowHead head) implements Preview { }

        record Place(DependencyStyle.ArrowPlace place) implements Preview { }

        record Density(DependencyStyle.ArrowDensity density) implements Preview { }

        record Pattern(DependencyStyle.Dash pattern) implements Preview { }

        record Weight(DependencyStyle.Weight weight) implements Preview { }
    }

    /** One pressable swatch: what it writes, whether it is the value in force, and what it shows. */
    public record Cell(String label, Runnable action, boolean selected, Preview preview) {
    }

    /** A flyout row. */
    public sealed interface Row {

        /** A plain text row, or an unpressable one when {@code action} is null. */
        record Text(String label, Runnable action) implements Row {
        }

        /**
         * A labelled row of preview cells.
         *
         * @param reset   clears the row's axis back to the chapter default, or null when the line already
         *                stands on the default and there is nothing to clear
         * @param enabled whether the row can be pressed at all; a row another axis has switched off is
         *                drawn faint rather than hidden, so the flyout does not change shape under the
         *                pointer when a choice above it changes
         */
        record Cells(String label, List<Cell> cells, Runnable reset, boolean enabled) implements Row {
            public Cells {
                cells = List.copyOf(cells);
            }
        }
    }

    /** A plain text row. */
    public static Row text(String label, Runnable action) {
        return new Row.Text(label, action);
    }

    /** A row of preview cells with no reset chip. */
    public static Row cells(String label, List<Cell> cells, boolean enabled) {
        return new Row.Cells(label, cells, null, enabled);
    }

    /** A plain text row. */
    public static final int ROW_HEIGHT = 16;

    /** A cells row's label band: the axis's name, and the reset chip's room. */
    public static final int LABEL_HEIGHT = 11;

    /** One preview cell: the drawing, and its caption under it. */
    public static final int CELL_HEIGHT = 24;

    /** The inset between a row's edge and its first cell. */
    public static final int PAD = 3;

    /** A flyout's width: wide enough for four previews across. */
    public static final int FLYOUT_WIDTH = 164;

    /** The reset chip at the right of a label band. */
    public static final int RESET_WIDTH = 11;
    public static final int RESET_HEIGHT = 9;

    /** How many cells sit on one line: four for a short row, three for a long one, so six read as 3x2. */
    public static int columns(int count) {
        return count <= 4 ? Math.max(1, count) : 3;
    }

    /** One row's height. */
    public static int rowHeight(Row row) {
        if (row instanceof Row.Text) {
            return ROW_HEIGHT;
        }
        Row.Cells cells = (Row.Cells) row;
        int count = cells.cells().size();
        int columns = columns(count);
        int lines = Math.max(1, (count + columns - 1) / columns);
        return LABEL_HEIGHT + lines * CELL_HEIGHT;
    }

    /** The whole flyout's height, its padding included. */
    public static int height(List<Row> rows) {
        int total = 6;
        for (Row row : rows) {
            total += rowHeight(row);
        }
        return total;
    }

    /** The width a set of rows needs: a cells row needs the preview grid, a text-only flyout the menu's own. */
    public static int width(List<Row> rows) {
        for (Row row : rows) {
            if (row instanceof Row.Cells) {
                return FLYOUT_WIDTH;
            }
        }
        return 150;
    }

    /** A placed flyout: its panel rectangle, its rows' rectangles, and the content they came from. */
    public record Placed(BookGeometry.Rect panel, List<BookGeometry.Rect> rows, List<Row> content) {

        /** Whether a point is on the flyout at all — inside it and on nothing still keeps it open. */
        public boolean contains(double px, double py) {
            return panel.contains(px, py);
        }
    }

    /** Places a flyout with its top-left at {@code x, y}, rows stacked downwards. */
    public static Placed place(int x, int y, List<Row> rows) {
        int width = width(rows);
        List<BookGeometry.Rect> rects = new ArrayList<>(rows.size());
        int cursor = y + 3;
        for (Row row : rows) {
            rects.add(BookGeometry.Rect.at(x + 2, cursor, width - 4, rowHeight(row)));
            cursor += rowHeight(row);
        }
        return new Placed(BookGeometry.Rect.at(x, y, width, height(rows)), List.copyOf(rects),
                List.copyOf(rows));
    }

    /**
     * One cell's rectangle inside its row.
     *
     * <p>Every cell on a line is the same size, so the grid is a division rather than a running x —
     * which is what makes the hit test the same arithmetic backwards instead of a second walk.
     */
    public static BookGeometry.Rect cellRect(BookGeometry.Rect row, int index, int count) {
        int columns = columns(count);
        int cellWidth = Math.max(1, row.width() / columns);
        int line = index / columns;
        int column = index % columns;
        int x = row.x() + column * cellWidth;
        int y = row.y() + LABEL_HEIGHT + line * CELL_HEIGHT;
        int width = column == columns - 1 ? Math.max(0, row.right() - x) : cellWidth;
        int height = Math.min(CELL_HEIGHT, Math.max(0, row.bottom() - y));
        return BookGeometry.Rect.at(x, y, Math.max(0, width), Math.max(0, height));
    }

    /** The reset chip's rectangle, or null when the row has no reset to offer. */
    public static BookGeometry.Rect resetRect(BookGeometry.Rect row, Row content) {
        if (!(content instanceof Row.Cells cells) || cells.reset() == null) {
            return null;
        }
        return BookGeometry.Rect.at(row.right() - PAD - RESET_WIDTH, row.y() + 1, RESET_WIDTH,
                RESET_HEIGHT);
    }

    /** What a press at a point means. */
    public sealed interface Hit {

        /** A cell or a reset chip: run it and leave the flyout open, so a combination tunes in one visit. */
        record Tune(Runnable run) implements Hit {
        }

        /** A text row: run it and close, the way every text row has always behaved. */
        record Choose(Runnable run) implements Hit {
        }

        /** On the flyout, on nothing pressable: absorb the press. */
        record None() implements Hit {
        }
    }

    /**
     * What a press at a point means, or null when the point is not on the flyout at all.
     *
     * <p>Null is the distinction the screen needs: outside closes the menu, inside does not.
     */
    public static Hit hit(Placed placed, double px, double py) {
        if (!placed.contains(px, py)) {
            return null;
        }
        for (int i = 0; i < placed.rows().size(); i++) {
            BookGeometry.Rect rect = placed.rows().get(i);
            Row row = placed.content().get(i);
            if (!rect.contains(px, py)) {
                continue;
            }
            if (row instanceof Row.Text text) {
                return text.action() == null ? new Hit.None() : new Hit.Choose(text.action());
            }
            Row.Cells cells = (Row.Cells) row;
            BookGeometry.Rect reset = resetRect(rect, row);
            if (reset != null && reset.contains(px, py)) {
                return new Hit.Tune(cells.reset());
            }
            if (!cells.enabled()) {
                return new Hit.None();
            }
            for (int c = 0; c < cells.cells().size(); c++) {
                if (cellRect(rect, c, cells.cells().size()).contains(px, py)) {
                    return new Hit.Tune(cells.cells().get(c).action());
                }
            }
            return new Hit.None();
        }
        return new Hit.None();
    }
}
