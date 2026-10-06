package dev.ellipog.tasked.client.dev;

import dev.ellipog.tasked.client.BookGeometry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The table browser's arithmetic: the search box, the rows, and where a pointer lands.
 *
 * <h2>One derivation for the drawing and the press</h2>
 *
 * <p>The house rule, and the reason this is a class rather than a handful of numbers in the screen: a
 * row drawn at one rectangle and hit at another is a row nobody can press, and the failure reads as a
 * broken mouse rather than as arithmetic.
 *
 * <h2>What the first version got wrong, and what that taught this one</h2>
 *
 * <p>It had a toolbar band holding the search box <i>and</i> two buttons, and it sized the search box by
 * the width of three 22-pixel row buttons — so the search box covered the "New" button, and the field
 * (a real widget, drawn over the card) also took its clicks. The buttons are the card's own footer
 * controls now, which is where every other card in this book puts them, and the search box has the whole
 * band to itself: two things cannot overlap if only one of them is there.
 *
 * <p>The row buttons were 22 pixels wide for the words "Edit" and "Copy", so each label ran out of its
 * own box and across the row's detail text. A button is as wide as its word now, and the detail text is
 * measured against the space that is left rather than against the card's edge.
 *
 * <p>Game-free, so the arithmetic is testable without a window — the same split the item picker uses
 * between {@code ItemPickerLayout} and the screen.
 */
public final class TableBrowserLayout {

    /** The search box's height. */
    public static final int SEARCH_HEIGHT = 20;

    /** Between the search box and the list under it. */
    public static final int SEARCH_GAP = 4;

    /** One table's row: an icon, a name, a detail, and the three buttons at its end. */
    public static final int ROW_HEIGHT = 22;

    /** The three buttons on a row, from its right edge: Edit, Copy, Delete. Sized to their words. */
    public static final int EDIT_WIDTH = 34;
    public static final int COPY_WIDTH = 34;
    public static final int DELETE_WIDTH = 14;

    /** The icon box on a row. */
    public static final int ICON = 16;

    /** Between two controls. */
    public static final int GAP = 4;

    /** How much of a row the detail text may take, at most: the id and the entry count. */
    public static final int DETAIL_WIDTH = 150;

    /**
     * The width the list gives up on its right edge for the scrollbar.
     *
     * <p>The same arrangement the item picker states at length ({@code ItemPickerLayout.SCROLLBAR}) and
     * for the same reason: three pixels of bar drawn at the list's edge is three pixels of every row,
     * and this list's rows end in three buttons. Reserving the strip here is what keeps the bar off the
     * Delete button rather than on it — and it is the layout that has to say so, because the row
     * rectangles, the buttons, the clip and the bar all derive from this rectangle.
     */
    public static final int SCROLLBAR = 5;

    /** What a row is: the "none" row, or a table. */
    public enum Kind {
        /** Clears the reference: a reward does not have to roll a table. */
        NONE,
        TABLE
    }

    /**
     * One row: what it is, and what it says.
     *
     * @param current whether this is the table the reward already rolls, which the row says so
     */
    public record Row(Kind kind, String id, String title, String iconId, int entries, boolean current) {

        /** A row for a table. */
        public static Row table(String id, String title, String iconId, int entries, boolean current) {
            return new Row(Kind.TABLE, id, title, iconId, entries, current);
        }

        /** The row that clears the reference. */
        public static Row none() {
            return new Row(Kind.NONE, "", "None", "", 0, false);
        }
    }

    /** Where the search box is, where the list is, and the strip the bar is drawn in. */
    public record Frame(BookGeometry.Rect search, BookGeometry.Rect list, BookGeometry.Rect scrollbar) {

        public static Frame of(BookGeometry.Rect body) {
            BookGeometry.Rect search = BookGeometry.Rect.at(body.x(), body.y(), body.width(),
                    SEARCH_HEIGHT);
            BookGeometry.Rect band = BookGeometry.Rect.at(body.x(), search.bottom() + SEARCH_GAP,
                    body.width(), Math.max(0, body.height() - SEARCH_HEIGHT - SEARCH_GAP));
            return new Frame(search,
                    BookGeometry.Rect.at(band.x(), band.y(), Math.max(0, band.width() - SCROLLBAR),
                            band.height()),
                    BookGeometry.Rect.at(band.right() - SCROLLBAR, band.y(),
                            Math.min(SCROLLBAR, band.width()), band.height()));
        }
    }

    /**
     * The rows a browser shows: the "none" row, then the tables the query matches.
     *
     * <p>The none row is always first and always present: it is how a reward that rolls nothing gets
     * that way, and burying it behind a search would make "clear this" something an author has to
     * remember rather than something they can see.
     */
    public static List<Row> rows(List<Row> tables, String query) {
        List<Row> rows = new ArrayList<>();
        rows.add(Row.none());
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (Row table : tables) {
            if (needle.isEmpty()
                    || table.title().toLowerCase(Locale.ROOT).contains(needle)
                    || table.id().toLowerCase(Locale.ROOT).contains(needle)) {
                rows.add(table);
            }
        }
        return List.copyOf(rows);
    }

    /**
     * How tall the whole list is.
     *
     * <p>What the list's viewport is told, and therefore what its clamp and its grip are derived from.
     * The {@code maxScroll} that used to sit beside it was the same fact minus the band, and the two
     * callers that read it clamped an offset by hand — which is the viewport's job now.
     */
    public static int contentHeight(List<Row> rows) {
        return rows.size() * ROW_HEIGHT;
    }

    /**
     * One row's rectangle, at this scroll offset. Not clipped: a half-past row is the clip's business.
     *
     * <p>This is the browser's hit test as well as its drawing: the press registers a target over
     * {@code body(rowRect(...))}, so the rectangle a row is drawn at and the one it is pressed at are
     * this one answer. There used to be a {@code rowAt(rows, frame, scroll, y)} here answering "which
     * row is at this height" — nothing called it, and a second mechanism for the question above is how
     * the two come apart.
     */
    public static BookGeometry.Rect rowRect(List<Row> rows, Frame frame, int scroll, int index) {
        return BookGeometry.Rect.at(frame.list().x(), frame.list().y() - scroll + index * ROW_HEIGHT,
                frame.list().width(), ROW_HEIGHT);
    }

    /** The three buttons at a row's end, from its right: Edit, Copy, Delete. */
    public record Buttons(BookGeometry.Rect edit, BookGeometry.Rect copy, BookGeometry.Rect delete) {
    }

    public static Buttons buttons(BookGeometry.Rect row) {
        BookGeometry.Rect delete = BookGeometry.Rect.at(row.right() - DELETE_WIDTH, row.y(),
                DELETE_WIDTH, row.height());
        BookGeometry.Rect copy = BookGeometry.Rect.at(delete.x() - GAP - COPY_WIDTH, row.y(),
                COPY_WIDTH, row.height());
        BookGeometry.Rect edit = BookGeometry.Rect.at(copy.x() - GAP - EDIT_WIDTH, row.y(),
                EDIT_WIDTH, row.height());
        return new Buttons(edit, copy, delete);
    }

    /** The part of a row that is the row itself: everything left of the buttons. */
    public static BookGeometry.Rect body(BookGeometry.Rect row) {
        return BookGeometry.Rect.at(row.x(), row.y(), Math.max(0, buttons(row).edit().x() - row.x() - GAP),
                row.height());
    }

    /** Where a table's icon is drawn on its row. */
    public static BookGeometry.Rect icon(BookGeometry.Rect row) {
        return BookGeometry.Rect.at(row.x() + 2, row.y() + (ROW_HEIGHT - ICON) / 2, ICON, ICON);
    }

    /** Where a row's name and detail text go: from the icon to the buttons. */
    public static BookGeometry.Rect text(BookGeometry.Rect row) {
        BookGeometry.Rect icon = icon(row);
        BookGeometry.Rect body = body(row);
        return BookGeometry.Rect.at(icon.right() + GAP, row.y(),
                Math.max(0, body.right() - GAP - (icon.right() + GAP)), row.height());
    }

    /**
     * Where a row's detail goes: the right end of the text band, so the ids of every row line up.
     *
     * <p>Right-aligned inside the text band rather than measured from the card's edge — the fault the
     * first version had, where the detail ran under the buttons.
     */
    public static BookGeometry.Rect detail(BookGeometry.Rect row) {
        BookGeometry.Rect text = text(row);
        int width = Math.min(DETAIL_WIDTH, Math.max(0, text.width() / 2));
        return BookGeometry.Rect.at(Math.max(text.x(), text.right() - width), row.y(), width, row.height());
    }

    /** Where a row's name goes: the text band, less room for the detail and the "current" tag. */
    public static BookGeometry.Rect name(BookGeometry.Rect row) {
        BookGeometry.Rect text = text(row);
        BookGeometry.Rect detail = detail(row);
        int tag = 46;
        return BookGeometry.Rect.at(text.x(), row.y(),
                Math.max(0, detail.x() - GAP - tag - text.x()), row.height());
    }

    /** Where the "current" tag goes on a row, or {@link #NONE} when it is not the current table. */
    public static final BookGeometry.Rect NONE = BookGeometry.Rect.at(0, 0, 0, 0);

    public static BookGeometry.Rect currentTag(BookGeometry.Rect row) {
        BookGeometry.Rect detail = detail(row);
        int width = 42;
        return BookGeometry.Rect.at(detail.x() - GAP - width, row.y(), width, row.height());
    }

    private TableBrowserLayout() {
    }
}
