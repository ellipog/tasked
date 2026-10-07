package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.client.BookGeometry;

import java.util.List;

/**
 * The sidebar tree drag's arithmetic: what a pointer over the tree means as a drop.
 *
 * <h2>One list, two kinds of row, and three answers</h2>
 *
 * <p>The sidebar is a flat list of visible rows — a heading, its chapters, the next heading — and a
 * drag over it can mean one of three things: <b>insert</b> at a position among a container's children,
 * <b>into</b> a group (the pointer is held over its heading), or <b>nowhere</b> (nothing to land on).
 * That is the whole vocabulary, and it is deliberately arithmetic rather than pixels: this class is
 * game-free and tested, and the screen's job is only to draw what it returns and send the op.
 *
 * <h2>Why "into" is a band in the middle of a heading</h2>
 *
 * <p>A heading is a row like any other, and both readings of the pointer over it are wanted: the top
 * and bottom edges are where a row is dropped <i>beside</i> the heading — above or below the group —
 * and the middle is where a chapter is dropped <i>inside</i> it. Splitting the row rather than making
 * the whole of it one answer is what keeps both gestures reachable without a modifier: the edges
 * behave exactly as they do over a chapter row, and the middle is unambiguous because nothing else is
 * there.
 *
 * <p>A group dragged over a heading is never "into": groups do not nest, and the answer is the root
 * position the pointer's y implies. That is checked here rather than at the call site so no caller can
 * forget it.
 */
public final class SidebarDrag {

    /**
     * One visible row, as the drop arithmetic needs it.
     *
     * @param key       the row's own key ({@code group:x} or {@code chapter:x})
     * @param group     whether it is a heading
     * @param container the heading it hangs under, or empty for a heading or a root chapter. <b>Carried
     *                  rather than guessed</b>: the rows are a flat list, and a root chapter is drawn
     *                  after every group, so walking backwards to the nearest heading calls the last
     *                  group its parent — which made a root chapter dropped on that group compute as
     *                  "already its last child" and send nothing. The layout knows the parent; this is
     *                  how it gets here.
     * @param rect      where it is drawn, in screen coordinates
     */
    public record Row(String key, boolean group, String container, BookGeometry.Rect rect) {

        /** Whether the pointer is over this row at all. */
        public boolean contains(double y) {
            return y >= rect.y() && y < rect.bottom();
        }

        /** Whether the pointer is in the band of this row that means "into", not "beside". */
        public boolean intoBand(double y) {
            int quarter = Math.max(1, rect.height() / 4);
            return y >= rect.y() + quarter && y < rect.bottom() - quarter;
        }
    }

    /** What a pointer stands for. Exactly one of the three, and the caller switches on it. */
    public sealed interface Drop {

        /**
         * Insert at a position among a container's children.
         *
         * @param containerKey the group's key, or empty for the root
         * @param index        the position among that container's children, with the dragged row
         *                     already out of the count — the same convention {@link RowDrag#finalIndex}
         *                     gives, so a downward drag does not land one short
         */
        record Insert(String containerKey, int index) implements Drop {
        }

        /** Into a group, appended to its list. The heading is highlighted rather than lined. */
        record Into(String groupKey) implements Drop {
        }

        /** Nowhere to land: an empty list, or a pointer that is over nothing. */
        record Nowhere() implements Drop {
        }
    }

    /**
     * What a pointer at {@code y} means for the row being dragged.
     *
     * @param rows          every visible row, in drawing order
     * @param y             the pointer
     * @param draggedKey    the key of the row being dragged
     * @param draggedIsGroup whether the dragged row is a heading
     */
    public static Drop target(List<Row> rows, double y, String draggedKey, boolean draggedIsGroup) {
        if (rows.isEmpty()) {
            return new Drop.Nowhere();
        }

        // A heading's middle, for a chapter: into it. A group itself never nests.
        if (!draggedIsGroup) {
            for (Row row : rows) {
                if (row.group() && !row.key().equals(draggedKey) && row.contains(y) && row.intoBand(y)) {
                    return new Drop.Into(row.key());
                }
            }
        }

        int gap = gapAt(rows, y);
        if (gap < 0) {
            return new Drop.Nowhere();
        }

        // The gap belongs to the container of the row above it. Directly under a heading that is the
        // group itself -- index zero of its children -- and above everything it is the root.
        int above = gap - 1;
        String container = "";
        if (above >= 0) {
            Row row = rows.get(above);
            if (!row.key().equals(draggedKey)) {
                container = row.group() && !draggedIsGroup ? row.key() : row.container();
            }
            else {
                // The pointer sits at the dragged row's own seam; read the row above it instead.
                container = above == 0 ? "" : rows.get(above - 1).container();
            }
        }

        int index = 0;
        for (int i = 0; i < gap; i++) {
            Row row = rows.get(i);
            // The dragged row is out of the count — the manifest removes it before inserting — and only
            // rows of the dragged kind are counted, because a group and a loosened chapter are placed
            // among their own kind. Skipping groups outright here was the first version, and it made
            // every group drop land at index zero.
            if (row.key().equals(draggedKey) || row.group() != draggedIsGroup) {
                continue;
            }
            if (row.container().equals(container)) {
                index++;
            }
        }
        return new Drop.Insert(container, index);
    }

    /**
     * Whether this drop would change anything.
     *
     * <p>A drop that would put the row back where it already is sends nothing — the same rule the flat
     * row drag already follows, and the reason it matters here is bigger: every structural op rewrites
     * manifests and broadcasts the whole tree, so a no-op drop would cost a round trip and a rebuild to
     * change one array's order to its current order.
     */
    public static boolean isNoOp(List<Row> rows, String draggedKey, Drop drop) {
        int at = indexOf(rows, draggedKey);
        String currentContainer = at < 0 ? "" : rows.get(at).container();
        int currentIndex = indexIn(rows, draggedKey, currentContainer);
        return switch (drop) {
            case Drop.Nowhere ignored -> true;
            case Drop.Insert insert -> insert.containerKey().equals(currentContainer)
                    && insert.index() == currentIndex;
            case Drop.Into into -> {
                // Into a group takes the end of its list, so it is a no-op only when the row is already
                // the last child of that group.
                int children = childCount(rows, into.groupKey());
                yield into.groupKey().equals(currentContainer) && currentIndex == children - 1;
            }
        };
    }

    /**
     * Where the insertion line is drawn for an {@code Insert}, or -1 for the other answers.
     *
     * <p>The top edge of the child the row would land above; under the last child of the container when
     * it would land at the end. Found by walking the same rows the drop was computed from, so the line
     * and the drop cannot disagree about which gap is meant.
     */
    public static int indicatorY(List<Row> rows, Drop drop) {
        if (!(drop instanceof Drop.Insert insert)) {
            return -1;
        }
        int childIndex = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.group()) {
                continue;
            }
            if (!row.container().equals(insert.containerKey())) {
                continue;
            }
            if (childIndex == insert.index()) {
                return row.rect().y();
            }
            childIndex++;
        }
        // Past the last child: the bottom of the last row that belongs to the container, or of the
        // heading itself when the container is an empty group.
        int last = -1;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.group()) {
                if (insert.containerKey().equals(row.key())) {
                    last = Math.max(last, i);
                }
            }
            else if (row.container().equals(insert.containerKey())) {
                last = Math.max(last, i);
            }
        }
        return last < 0 ? -1 : rows.get(last).rect().bottom();
    }

    /**
     * The heading a row hangs under, or empty at the root — the row's own field, asked by index.
     *
     * <p>Kept because it reads better at a call site than {@code rows.get(i).container()}, and because
     * there is one answer: the walk it used to do is gone, so this cannot disagree with the field.
     */
    public static String containerOf(List<Row> rows, int index) {
        return index < 0 || index >= rows.size() ? "" : rows.get(index).container();
    }

    /** How many children a heading has among the visible rows. Zero for a collapsed or empty group. */
    public static int childCount(List<Row> rows, String groupKey) {
        int count = 0;
        for (Row row : rows) {
            if (!row.group() && row.container().equals(groupKey)) {
                count++;
            }
        }
        return count;
    }

    /** A row's position among the children of its container, counting only rows of its own kind. */
    private static int indexIn(List<Row> rows, String key, String container) {
        int at = indexOf(rows, key);
        if (at < 0) {
            return 0;
        }
        boolean kindIsGroup = rows.get(at).group();
        int index = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.key().equals(key)) {
                return index;
            }
            if (row.group() != kindIsGroup) {
                continue;
            }
            if (row.container().equals(container)) {
                index++;
            }
        }
        return index;
    }

    private static int indexOf(List<Row> rows, String key) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).key().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private static int gapAt(List<Row> rows, double y) {
        return RowDrag.gapAt(rects(rows), y);
    }

    private static List<BookGeometry.Rect> rects(List<Row> rows) {
        return rows.stream().map(Row::rect).toList();
    }

    private SidebarDrag() {
    }
}
