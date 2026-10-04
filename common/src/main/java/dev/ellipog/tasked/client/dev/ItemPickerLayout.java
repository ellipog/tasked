package dev.ellipog.tasked.client.dev;

import dev.ellipog.tasked.client.BookGeometry;

import java.util.ArrayList;
import java.util.List;

/**
 * The item picker's arithmetic: which rows exist, how tall the list is, and where a pointer lands.
 *
 * <h2>One derivation for the drawing and the press</h2>
 *
 * <p>A row drawn at one rectangle and hit at another is a row an author cannot press, and the failure
 * reads as a broken mouse rather than as arithmetic. So the rows are composed here, their heights
 * decided here, and {@code rowRect} is the one answer both the drawing and the hit test read.
 *
 * <p>The list's scroll is the caller's: every method here takes the offset it is drawing at and
 * clamps it only where clamping is the answer ({@link #maxScroll}). A class that also remembered the
 * scroll would be a second place the screen's state lives.
 */
public final class ItemPickerLayout {

    /** The search box's height, which is a control's, not a row's. */
    public static final int SEARCH_HEIGHT = 18;

    /** A section's heading: shorter than a row, because it is a word and not a thing to press. */
    public static final int HEADING_HEIGHT = 14;

    /** One pickable row. */
    public static final int ROW_HEIGHT = 18;

    /** What a row is for. Headings name a section; the others can be pressed. */
    public enum Kind {
        HEADING,
        CLEAR,
        /** An id with no item behind it: the field's own value, or one typed on purpose. Kept, marked. */
        MISSING,
        ITEM,
        /**
         * A file in the pack, picked by {@code TexturePicker}.
         *
         * <p>The same list, one more thing a row can be: the texture picker composes its rows through
         * this class so the two lists share the frame, the heights, the scroll and the hit test -- and
         * the drawing is where the two differ, because a picture is not an item. {@link #pickable}
         * accepts it with every kind that is not a heading.
         */
        TEXTURE
    }

    /**
     * One row of the picker.
     *
     * <p>{@code id} is empty for a heading; {@code secondary} is the count for a carried stack, or the
     * note that says why a missing row is missing.
     */
    public record Row(Kind kind, String id, String label, String secondary) {
    }

    /**
     * The field as it stands: its id (empty when none), whether that id resolves to an item this
     * build knows, and whether the caller has decided it may be cleared.
     *
     * <p>Passed in rather than derived, because "does an item exist" is the game's question and this
     * class is arithmetic; {@link #NONE} is a field with no value at all.
     */
    public record Current(String id, boolean known, boolean clearable) {

        public static final Current NONE = new Current("", true, false);
    }

    /** Where the search box is and where the list is, from the card's body rectangle. */
    public record Frame(BookGeometry.Rect search, BookGeometry.Rect list) {

        /**
         * The body split in two: the box across its top, the list under it.
         *
         * <p>No gap between them, because the box is the first thing in the list rather than a header
         * over it -- a strip of card between a search box and the results it searches is a strip that
         * does nothing.
         */
        public static Frame of(BookGeometry.Rect body) {
            return new Frame(
                    BookGeometry.Rect.at(body.x(), body.y(), body.width(), SEARCH_HEIGHT),
                    BookGeometry.Rect.at(body.x(), body.y() + SEARCH_HEIGHT, body.width(),
                            Math.max(0, body.height() - SEARCH_HEIGHT)));
        }
    }

    /**
     * The rows a picker shows: the clear row when there is a value to clear, the inventory, and the
     * query's matches.
     *
     * <p>The inventory is shown whatever the query says -- it is the list for an empty box, and while
     * a query is typed it stays where it was rather than jumping under the search results, because a
     * list that moves while you type is a list you lose your place in. An empty picker is no rows at
     * all: the empty state's sentence is the drawing's, and a row pretending to be one would be a row
     * the keyboard can land on.
     */
    public static List<Row> compose(List<ItemPicker.Entry> inventory, List<ItemPicker.Entry> matches,
                                    Current current, String typedCandidate, String query) {
        return compose(inventory, matches, current, typedCandidate, query, null);
    }

    /**
     * The same, with the caller's own words over the results.
     *
     * <p>{@code heading} is what separates the two callers, and not only in wording. The item picker passes
     * null: its results mean something once a query has been typed, and before that what is offered is what
     * the player carries. A search field passes its own heading and shows its list whether or not anything
     * has been typed -- ten rows of what the registry holds is what tells an author what the field can even
     * hold, and an empty box and an empty registry must not look alike.
     */
    public static List<Row> compose(List<ItemPicker.Entry> inventory, List<ItemPicker.Entry> matches,
                                    Current current, String typedCandidate, String query, String heading) {
        List<Row> rows = new ArrayList<>();
        if (current.clearable() && !current.id().isEmpty()) {
            // The row the caller has already decided is legal -- it is only ever offered for the
            // quest's icon, where "clear" means the optional field goes and the default applies.
            rows.add(new Row(Kind.CLEAR, "", Labels.of("tasked.dev.picker.clear"),
                    Labels.of("tasked.dev.picker.clear_detail")));
        }
        if (!current.id().isEmpty() && !current.known()) {
            // The field's own value when the build cannot resolve it: what a mod that went away looks
            // like. Shown so it is never silent, and pickable so pressing it keeps it.
            rows.add(new Row(Kind.MISSING, current.id(), current.id(),
                    Labels.of("tasked.dev.picker.missing")));
        }
        boolean results = !matches.isEmpty() || typedCandidate != null;
        boolean show = heading != null ? results
                : results && query != null && !query.isBlank();
        // The results above the inventory, because a query is about the whole registry and the list
        // someone is reading is the answer -- what they carry is the fallback, not the headline.
        if (show) {
            rows.add(heading(heading == null ? Labels.of("tasked.dev.picker.all_items") : heading));
            if (typedCandidate != null) {
                rows.add(new Row(Kind.MISSING, typedCandidate, typedCandidate,
                        Labels.of("tasked.dev.picker.not_installed")));
            }
            for (ItemPicker.Entry entry : matches) {
                rows.add(item(entry));
            }
        }
        if (!inventory.isEmpty()) {
            rows.add(heading(Labels.of("tasked.dev.picker.inventory")));
            for (ItemPicker.Entry entry : inventory) {
                rows.add(item(entry));
            }
        }
        return List.copyOf(rows);
    }

    /** How tall one row is. */
    public static int heightOf(Row row) {
        return row.kind() == Kind.HEADING ? HEADING_HEIGHT : ROW_HEIGHT;
    }

    /** How tall the whole list is, headings and all. */
    public static int contentHeight(List<Row> rows) {
        int total = 0;
        for (Row row : rows) {
            total += heightOf(row);
        }
        return total;
    }

    /** The furthest the list can scroll before its last row is at the bottom of the frame. */
    public static int maxScroll(List<Row> rows, Frame frame) {
        return Math.max(0, contentHeight(rows) - frame.list().height());
    }

    /**
     * One row's rectangle on screen, at this scroll offset.
     *
     * <p>Not clipped to the list: a row half past the edge is the clip's business, and the drawing
     * already holds one. This answers where the row <i>is</i>.
     */
    public static BookGeometry.Rect rowRect(List<Row> rows, Frame frame, int scroll, int index) {
        int y = frame.list().y() - scroll;
        for (int i = 0; i < index; i++) {
            y += heightOf(rows.get(i));
        }
        return BookGeometry.Rect.at(frame.list().x(), y, frame.list().width(), heightOf(rows.get(index)));
    }

    /** The row a pointer at this y is over, or -1 -- headings included, because they are drawn. */
    public static int rowAt(List<Row> rows, Frame frame, int scroll, double y) {
        if (y < frame.list().y() || y >= frame.list().bottom()) {
            return -1;
        }
        for (int i = 0; i < rows.size(); i++) {
            BookGeometry.Rect rect = rowRect(rows, frame, scroll, i);
            if (y >= rect.y() && y < rect.bottom()) {
                return i;
            }
        }
        return -1;
    }

    /** Whether a row can be selected or pressed. A heading is neither. */
    public static boolean pickable(Row row) {
        return row.kind() != Kind.HEADING;
    }

    /** The first pickable row, or -1 when there is none. */
    public static int firstPickable(List<Row> rows) {
        for (int i = 0; i < rows.size(); i++) {
            if (pickable(rows.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * A valid selection near {@code index}: the nearest pickable row at or before it, else the first
     * after it, else -1.
     *
     * <p>Asked after the list has changed size -- a query keystroke rebuilds the rows, and an index
     * that named the seventh match cannot go on naming it when there are two. Landing on the nearest
     * row rather than on the first is what stops the selection jumping to the top while typing.
     */
    public static int clamp(List<Row> rows, int index) {
        if (rows.isEmpty()) {
            return -1;
        }
        int at = Math.max(0, Math.min(rows.size() - 1, index));
        for (int i = at; i >= 0; i--) {
            if (pickable(rows.get(i))) {
                return i;
            }
        }
        for (int i = at + 1; i < rows.size(); i++) {
            if (pickable(rows.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** The next pickable row in the given direction, or the selection itself at the ends. */
    public static int step(List<Row> rows, int from, int delta) {
        int at = clamp(rows, from);
        if (at < 0 || delta == 0) {
            return at;
        }
        for (int i = at + delta; i >= 0 && i < rows.size(); i += delta) {
            if (pickable(rows.get(i))) {
                return i;
            }
        }
        return at;
    }

    private static Row heading(String label) {
        return new Row(Kind.HEADING, "", label, "");
    }

    private static Row item(ItemPicker.Entry entry) {
        // The name is the row and the id is the small print, which is the other way round from the
        // first version and the fix for a real report: a name search matched but every row showed an
        // id, so the match looked like it had not happened. A count rides in front of the name,
        // because it is the one thing about a carried stack that is not already in the id.
        String name = entry.label() == null || entry.label().isBlank() ? entry.id() : entry.label();
        String counted = entry.count() > 1 ? "x" + entry.count() + " " + name : name;
        return new Row(Kind.ITEM, entry.id(), counted, entry.id());
    }

    private ItemPickerLayout() {
    }
}
