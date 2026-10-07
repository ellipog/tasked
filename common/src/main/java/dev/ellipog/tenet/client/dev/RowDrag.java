package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.client.BookGeometry;

import java.util.List;

/**
 * The row drag's arithmetic: which gap a pointer stands at, where the dragged row lands, and where the
 * line is drawn.
 *
 * <h2>One gesture for three lists</h2>
 *
 * <p>A quest's tasks, its rewards and a chapter's quest list are three lists in two different panels,
 * and the drag is one class because the arithmetic is one arithmetic: rows are rectangles with tops and
 * bottoms, and a pointer is a y. What differs -- which op goes on the wire when the pointer lets go --
 * is the screen's, and it is the only thing that differs.
 *
 * <p>The gap a pointer names is counted at each row's <b>middle</b>, not at its edges: a drop "on" a
 * row's top half means "before it", which is what makes dropping feel like it lands between rows
 * rather than inside one. The final index drops the dragged row from the count, because the row itself
 * is not in the list it is being inserted into -- without that, every downward drag lands one short.
 */
public final class RowDrag {

    /**
     * The insertion gap a pointer at this y stands at: 0 before the first row, {@code rows.size()}
     * after the last.
     *
     * <p>Returns -1 for no rows at all, which is not the same answer as 0: a drag over an empty list
     * has nowhere to land, and a caller reading 0 would move something into nothing.
     */
    public static int gapAt(List<BookGeometry.Rect> rows, double y) {
        if (rows.isEmpty()) {
            return -1;
        }
        int gap = 0;
        for (BookGeometry.Rect row : rows) {
            if (y >= (row.y() + row.bottom()) / 2.0) {
                gap++;
            }
            else {
                break;
            }
        }
        return gap;
    }

    /**
     * Where a row dragged from {@code from} ends up when it is let go at {@code gap}.
     *
     * <p>The gap was counted with the row still in the list; taking it out shifts every gap below it
     * up by one, and that shift is the whole of this method. A row dropped at its own gap -- or at the
     * gap just below it, which is the same place -- comes back as {@code from}: the caller compares
     * and sends nothing.
     */
    public static int finalIndex(int from, int gap) {
        return gap > from ? gap - 1 : gap;
    }

    /**
     * The y the insertion line is drawn at: the seam between two rows inside the list, and the outer
     * edge at either end — so the line for "before everything" sits on the first row's top, and the
     * line for "after everything" on the last row's bottom.
     */
    public static int indicatorY(List<BookGeometry.Rect> rows, int gap) {
        if (rows.isEmpty()) {
            return -1;
        }
        if (gap <= 0) {
            return rows.get(0).y();
        }
        if (gap >= rows.size()) {
            return rows.get(rows.size() - 1).bottom();
        }
        return (rows.get(gap - 1).bottom() + rows.get(gap).y()) / 2;
    }

    private RowDrag() {
    }
}
