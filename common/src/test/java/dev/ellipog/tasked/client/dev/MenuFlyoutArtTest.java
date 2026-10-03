package dev.ellipog.tasked.client.dev;

import dev.ellipog.tasked.client.BookGeometry;
import dev.ellipog.tasked.quest.DependencyStyle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a flyout actually draws: the previews, the captions, the ring, and the faded rows.
 *
 * <h2>Why a recorded drawing</h2>
 *
 * <p>The same reason the settings panel has one: the faults worth catching here are drawing faults no
 * arithmetic can see — a preview that draws nothing, a caption placed outside its cell, a selected cell
 * with no ring. A screen cannot be instantiated by a test; this class draws into a recorder, and what it
 * was told to draw is what the assertions read.
 */
@DisplayName("MenuFlyoutArt")
class MenuFlyoutArtTest {

    private static MenuFlyout.Cell headCell(String label, boolean selected) {
        return new MenuFlyout.Cell(label, () -> {
        }, selected, new MenuFlyout.Preview.Head(DependencyStyle.ArrowHead.CHEVRON));
    }

    @Test
    @DisplayName("a cell draws its preview's ink and its caption, and a selected one wears a ring")
    void previewsAreDrawnWithTheRealGeometry() {
        MenuFlyout.Placed placed = MenuFlyout.place(0, 0, List.of(MenuFlyout.cells("Head",
                List.of(headCell("Chevron", false), headCell("Tri", true)), true)));
        BookGeometry.Rect row = placed.rows().get(0);
        BookGeometry.Rect first = MenuFlyout.cellRect(row, 0, 2);
        BookGeometry.Rect second = MenuFlyout.cellRect(row, 1, 2);

        RecordingRenderer r = new RecordingRenderer();
        MenuFlyoutArt.draw(r, placed, -1, -1);

        assertTrue(r.wroteWithin("Head", row.x(), row.y(), row.right(), row.y() + 11),
                "the row's own label is drawn in its band");
        assertTrue(r.wroteWithin("Chevron", first.x(), first.y(), first.right(), first.bottom()));
        assertTrue(r.wroteWithin("Tri", second.x(), second.y(), second.right(), second.bottom()));

        // The preview's line is real ink from LineArt, drawn across the middle of the cell.
        BookGeometry.Rect box = BookGeometry.Rect.at(first.x() + 2, first.y() + 2,
                first.width() - 4, first.height() - 11);
        int midY = (box.y() + 1 + box.bottom() - 1) / 2;
        assertTrue(r.covered(first.x() + 5, midY, first.right() - 5, midY + 1),
                "the head preview's line must be drawn");

        assertTrue(r.covered(second.x(), second.y(), second.right(), second.y() + 1),
                "the selected cell wears a ring");
        assertFalse(r.covered(first.x(), first.y(), first.right(), first.y() + 1),
                "an unselected one does not");
    }

    @Test
    @DisplayName("a switched-off row is still drawn: its label and previews are there to read")
    void disabledRowsAreDrawn() {
        MenuFlyout.Placed placed = MenuFlyout.place(0, 0, List.of(
                new MenuFlyout.Row.Cells("Density", List.of(headCell("Low", false)), null, false),
                MenuFlyout.text("\u2026", null)));

        RecordingRenderer r = new RecordingRenderer();
        MenuFlyoutArt.draw(r, placed, -1, -1);

        BookGeometry.Rect row = placed.rows().get(0);
        assertTrue(r.wroteWithin("Density", row.x(), row.y(), row.right(), row.y() + 11),
                "faint, not hidden: the row still says what it is");
        assertTrue(r.wroteWithin("Low", row.x(), row.y(), row.right(), row.bottom()));
        BookGeometry.Rect text = placed.rows().get(1);
        assertTrue(r.wroteWithin("\u2026", text.x(), text.y(), text.right(), text.bottom()),
                "the overfull marker is drawn even though it is not pressable");
    }
}
