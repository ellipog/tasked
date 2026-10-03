package dev.ellipog.tasked.client.dev;

import dev.ellipog.tasked.client.BookGeometry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sidebar drop arithmetic, without a screen.
 *
 * <h2>The rows are drawn the way the sidebar draws them</h2>
 *
 * <p>Eighteen pixels tall on a twenty-pixel pitch, a heading followed by its chapters and then the next
 * heading — because the questions that matter are about the things that only exist in that shape: is
 * the pointer in the middle of a heading or on its edge, is the gap between two chapters of one group or
 * between a group's last chapter and the next heading, and where does the line go. A test built on tidy
 * coordinates would answer a different set of questions.
 */
@DisplayName("SidebarDrag")
class SidebarDragTest {

    private static final int PITCH = 20;

    private static SidebarDrag.Row row(String key, boolean group, String container, int index) {
        return new SidebarDrag.Row(key, group, container,
                BookGeometry.Rect.at(0, index * PITCH, 140, BookGeometry.SIDEBAR_ROW_HEIGHT));
    }

    /**
     * Two groups and a chapter at the root, which is the smallest tree with every case in it.
     *
     * <pre>
     *   0  group:alpha      ▼ Alpha
     *   1  chapter:one          One
     *   2  chapter:two          Two
     *   3  group:beta       ▼ Beta
     *   4  chapter:root         Root (belongs to no group)
     * </pre>
     */
    private static final List<SidebarDrag.Row> ROWS = List.of(
            row("group:alpha", true, "", 0),
            row("chapter:one", false, "group:alpha", 1),
            row("chapter:two", false, "group:alpha", 2),
            row("group:beta", true, "", 3),
            // A root chapter: its container is the root, not the heading drawn above it. That is the
            // fact the layout knows and this list now carries.
            row("chapter:root", false, "", 4));

    /** A y in the top band of a row — the edge, where a drop lands beside it rather than inside it. */
    private static double edgeOf(int index) {
        return index * PITCH + 1;
    }

    private static double middleOf(int index) {
        return index * PITCH + BookGeometry.SIDEBAR_ROW_HEIGHT / 2.0;
    }

    @Test
    @DisplayName("a gap just under a heading is the first slot inside that group")
    void theGapUnderAHeadingIsItsFirstSlot() {
        // Dropping a root chapter right below Alpha's heading: the heading's own row is above the gap,
        // and "above a heading" reads as the group rather than as the root.
        SidebarDrag.Drop drop = SidebarDrag.target(ROWS, edgeOf(1) - 0.5, "chapter:root", false);

        SidebarDrag.Drop.Insert insert = assertInstanceOf(SidebarDrag.Drop.Insert.class, drop);
        assertEquals("group:alpha", insert.containerKey());
        assertEquals(0, insert.index());
    }

    @Test
    @DisplayName("a gap between two chapters stays in their group")
    void aGapBetweenChaptersStaysInTheirGroup() {
        SidebarDrag.Drop drop = SidebarDrag.target(ROWS, edgeOf(3) - 0.5, "chapter:root", false);

        SidebarDrag.Drop.Insert insert = assertInstanceOf(SidebarDrag.Drop.Insert.class, drop);
        assertEquals("group:alpha", insert.containerKey());
        assertEquals(2, insert.index(), "after both of Alpha's chapters");
    }

    @Test
    @DisplayName("the middle of a heading means into it, not beside it")
    void theMiddleOfAHeadingMeansInto() {
        SidebarDrag.Drop drop = SidebarDrag.target(ROWS, middleOf(3), "chapter:one", false);

        SidebarDrag.Drop.Into into = assertInstanceOf(SidebarDrag.Drop.Into.class, drop);
        assertEquals("group:beta", into.groupKey());
    }

    @Test
    @DisplayName("the edge of a heading is still a position beside it")
    void theEdgeOfAHeadingIsAPosition() {
        // The top band of Beta's row: not "into Beta" but the root slot before it. Both readings of a
        // heading are reachable, which is the point of splitting the row rather than choosing one.
        SidebarDrag.Drop drop = SidebarDrag.target(ROWS, edgeOf(3), "chapter:root", false);

        SidebarDrag.Drop.Insert insert = assertInstanceOf(SidebarDrag.Drop.Insert.class, drop);
        assertEquals("group:alpha", insert.containerKey(), "still inside Alpha, above Beta's heading");
        assertEquals(2, insert.index());
    }

    @Test
    @DisplayName("a group is never dropped into a group")
    void aGroupNeverDropsIntoAGroup() {
        // Groups do not nest, and a heading drawn as a drop target for another heading would be a
        // promise the format cannot keep.
        SidebarDrag.Drop drop = SidebarDrag.target(ROWS, middleOf(3), "group:alpha", true);

        SidebarDrag.Drop.Insert insert = assertInstanceOf(SidebarDrag.Drop.Insert.class, drop);
        assertEquals("", insert.containerKey());
        assertEquals(1, insert.index(), "the root slot after Alpha");
    }

    @Test
    @DisplayName("the line is drawn at the slot the drop names")
    void theLineIsDrawnAtTheSlot() {
        assertEquals(ROWS.get(2).rect().y(),
                SidebarDrag.indicatorY(ROWS, new SidebarDrag.Drop.Insert("group:alpha", 1)),
                "the top edge of the second child");

        assertEquals(ROWS.get(2).rect().bottom(),
                SidebarDrag.indicatorY(ROWS, new SidebarDrag.Drop.Insert("group:alpha", 2)),
                "and past the last child, the bottom of the last row in the group");
    }

    @Test
    @DisplayName("a drop that changes nothing says so")
    void aDropThatChangesNothing() {
        assertTrue(SidebarDrag.isNoOp(ROWS, "chapter:one",
                new SidebarDrag.Drop.Insert("group:alpha", 0)),
                "one is already Alpha's first chapter");
        assertFalse(SidebarDrag.isNoOp(ROWS, "chapter:one",
                new SidebarDrag.Drop.Insert("group:alpha", 1)),
                "but sliding it after two is a real reorder");
        assertFalse(SidebarDrag.isNoOp(ROWS, "chapter:one",
                new SidebarDrag.Drop.Into("group:beta")),
                "and into another group is always a change");
        assertTrue(SidebarDrag.isNoOp(ROWS, "group:alpha",
                new SidebarDrag.Drop.Insert("", 0)),
                "a group dropped back at the top is where it already is");
    }

    @Test
    @DisplayName("an empty list has nowhere to drop")
    void anEmptyListIsNoDrop() {
        assertInstanceOf(SidebarDrag.Drop.Nowhere.class,
                SidebarDrag.target(List.of(), 10, "chapter:one", false));
    }

    /**
     * The reported layout: one group, then an ungrouped chapter — which is where the sidebar puts every
     * root chapter, because the groups are all added before them.
     */
    private static final List<SidebarDrag.Row> GROUP_THEN_ROOT = List.of(
            row("group:last", true, "", 0),
            row("chapter:root", false, "", 1));

    @Test
    @DisplayName("a root chapter dropped on the last group is not a no-op")
    void aRootChapterDroppedOnTheLastGroupIsNotANoOp() {
        // The bug this pins: a root chapter drawn after the last heading used to have that heading
        // guessed as its container, so dropping it INTO that group computed as "already the last child"
        // and the client sent nothing -- the drag animated and nothing happened, with no error to see.
        SidebarDrag.Drop drop = SidebarDrag.target(GROUP_THEN_ROOT, middleOf(0), "chapter:root", false);

        SidebarDrag.Drop.Into into = assertInstanceOf(SidebarDrag.Drop.Into.class, drop);
        assertEquals("group:last", into.groupKey());
        assertFalse(SidebarDrag.isNoOp(GROUP_THEN_ROOT, "chapter:root", into),
                "a root chapter is not a child of the heading drawn above it");
    }

    @Test
    @DisplayName("a root chapter after a group is still at the root")
    void aRootChapterAfterAGroupIsStillAtTheRoot() {
        // The gap under the root chapter — the end of the list — is a root slot, not the last group's.
        SidebarDrag.Drop drop = SidebarDrag.target(GROUP_THEN_ROOT,
                GROUP_THEN_ROOT.get(1).rect().bottom() - 0.5, "group:last", true);

        SidebarDrag.Drop.Insert insert = assertInstanceOf(SidebarDrag.Drop.Insert.class, drop);
        assertEquals("", insert.containerKey());
        assertEquals(0, insert.index(), "after the root chapter it is already at the end: a no-op, but at the root");
    }
}
