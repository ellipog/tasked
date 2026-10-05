package dev.ellipog.tasked.client.dev;

import dev.ellipog.tasked.client.BookGeometry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Assets panel's arithmetic: the bands, the section column, and a page's lines.
 *
 * <p>What is worth asserting here is what the screen cannot be trusted to remember: that no two bands
 * overlap, that a section row is inside the column, that a press maps to the section it looks like it maps
 * to, and that drawing and hit-testing a page agree. The panel's contents are the screen's business.
 */
@DisplayName("The Assets panel's layout")
class AssetsLayoutTest {

    private static BookGeometry.Rect card() {
        return BookGeometry.Rect.at(100, 50, 400, 260);
    }

    @Test
    @DisplayName("the bands stack without overlapping, and the footer is at the card's foot")
    void theBandsFit() {
        AssetsLayout.Frame frame = AssetsLayout.Frame.of(card());
        BookGeometry.Rect card = card();

        assertTrue(frame.title().y() >= card.y(), "the title is inside the card");
        assertTrue(frame.title().bottom() <= frame.sections().y(),
                "the title ends before the section column starts");
        assertEquals(card.bottom() - AssetsLayout.FOOTER_HEIGHT, frame.footer().y(),
                "the footer is the card's own foot");
        assertTrue(frame.sections().bottom() <= frame.footer().y(),
                "and the content stops before it");
        assertTrue(frame.page().x() >= frame.sections().right(),
                "the page is right of the column, never over it");
        assertTrue(frame.page().right() <= card.right(), "and inside the card");
        assertEquals(frame.sections().height(), frame.page().height(),
                "the column and the page are the same height, so neither looks cut off");
    }

    @Test
    @DisplayName("every section row is inside the column, and a press maps to the row it is on")
    void sectionsAreHitWhereTheyAreDrawn() {
        AssetsLayout.Frame frame = AssetsLayout.Frame.of(card());
        AssetsLayout.Section[] all = AssetsLayout.Section.values();

        for (int i = 0; i < all.length; i++) {
            BookGeometry.Rect row = frame.section(i);
            assertTrue(row.y() >= frame.sections().y() && row.bottom() <= frame.sections().bottom(),
                    all[i] + " must be inside the column");
            assertEquals(all[i], AssetsLayout.sectionAt(frame, row.x() + 2, row.y() + 2),
                    all[i] + " is what a press on its own row is on");
        }

        // And the three things that are not a section row.
        assertNull(AssetsLayout.sectionAt(frame, frame.page().x() + 4, frame.page().y() + 4),
                "a press on the page is not a press on a section");
        assertNull(AssetsLayout.sectionAt(frame, frame.title().x() + 4, frame.title().y() + 4),
                "nor is one on the title");
        BookGeometry.Rect last = frame.section(all.length - 1);
        assertNull(AssetsLayout.sectionAt(frame, last.x() + 2, frame.sections().bottom() + 1),
                "nor is one below the last section");
    }

    @Test
    @DisplayName("a page's lines are laid out by kind, and a press is on the line it is drawn at")
    void linesAreHitWhereTheyAreDrawn() {
        List<AssetsLayout.Kind> lines = List.of(
                AssetsLayout.Kind.HEADING, AssetsLayout.Kind.ROW, AssetsLayout.Kind.ROW,
                AssetsLayout.Kind.HEADING, AssetsLayout.Kind.ROW);

        assertEquals(0, AssetsLayout.lineTop(lines, 0));
        assertEquals(AssetsLayout.HEADING_HEIGHT, AssetsLayout.lineTop(lines, 1), "rows follow the heading");
        assertEquals(AssetsLayout.HEADING_HEIGHT + 2 * AssetsLayout.ROW_HEIGHT,
                AssetsLayout.lineTop(lines, 3), "and the second heading follows both rows");
        assertEquals(2 * AssetsLayout.HEADING_HEIGHT + 3 * AssetsLayout.ROW_HEIGHT,
                AssetsLayout.contentHeight(lines));
        assertEquals(AssetsLayout.contentHeight(lines), AssetsLayout.lineTop(lines, lines.size()),
                "the end of the content is where the last line starts plus its height");

        // Every line answers with itself at its own top, at its bottom-most pixel, and in between.
        for (int i = 0; i < lines.size(); i++) {
            int top = AssetsLayout.lineTop(lines, i);
            int height = AssetsLayout.lineHeight(lines.get(i));
            assertEquals(i, AssetsLayout.lineAt(lines, top), "line " + i + " at its top");
            assertEquals(i, AssetsLayout.lineAt(lines, top + height - 1), "line " + i + " at its last pixel");
            if (height > 2) {
                assertEquals(i, AssetsLayout.lineAt(lines, top + height / 2), "line " + i + " in the middle");
            }
        }
        assertEquals(-1, AssetsLayout.lineAt(lines, -1), "above the content is nothing");
        assertEquals(-1, AssetsLayout.lineAt(lines, AssetsLayout.contentHeight(lines)),
                "and so is past the end");
        assertEquals(-1, AssetsLayout.lineAt(List.of(), 0), "an empty page has no lines at all");
    }

    @Test
    @DisplayName("scrolling is clamped to what does not fit, and nothing more")
    void scrollIsClamped() {
        List<AssetsLayout.Kind> lines = List.of(
                AssetsLayout.Kind.ROW, AssetsLayout.Kind.ROW, AssetsLayout.Kind.ROW);
        int page = AssetsLayout.contentHeight(lines);

        assertEquals(0, AssetsLayout.maxScroll(lines, page), "what fits needs no scroll");
        assertEquals(0, AssetsLayout.maxScroll(lines, page + 100), "and neither does a roomier band");
        assertEquals(AssetsLayout.ROW_HEIGHT, AssetsLayout.maxScroll(lines, page - AssetsLayout.ROW_HEIGHT),
                "one row too many is one row of scroll");
    }

    @Test
    @DisplayName("a squeezed window clamps rather than producing rectangles outside the card")
    void aTinyCardStillFits() {
        // Degradation rather than an error: a window this small is already clipping the book, and a
        // negative width would be a rectangle drawn backwards.
        AssetsLayout.Frame frame = AssetsLayout.Frame.of(BookGeometry.Rect.at(0, 0, 40, 30));

        for (BookGeometry.Rect band : List.of(frame.title(), frame.sections(), frame.page(),
                frame.footer())) {
            assertTrue(band.width() >= 0 && band.height() >= 0, "no band may be inside out: " + band);
        }
        assertSame(AssetsLayout.Section.TABLES, AssetsLayout.Section.values()[0],
                "and the first section is the first one whatever the window");
        assertFalse(frame.page().right() > 40 + 1, "the page stays inside a 40-pixel card");
    }
}
