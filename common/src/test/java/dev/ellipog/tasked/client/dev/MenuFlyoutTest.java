package dev.ellipog.tasked.client.dev;

import dev.ellipog.tasked.client.BookGeometry;
import dev.ellipog.tasked.quest.DependencyStyle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The flyout's arithmetic: how tall a row is, where a cell is, and what a press on it means.
 *
 * <h2>Why this is worth testing rather than looking at</h2>
 *
 * <p>The failure mode of getting the grid arithmetic wrong is not an ugly panel but a cell you cannot
 * press on the pixels it is drawn with — the picture looks fine and the click lands on its neighbour.
 * So the grid is asserted the way the swatch grid is: the drawing and the hit test read one derivation,
 * and that derivation is a division rather than a running cursor.
 */
@DisplayName("MenuFlyout")
class MenuFlyoutTest {

    private static MenuFlyout.Cell cell(String label) {
        return new MenuFlyout.Cell(label, () -> {
        }, false, new MenuFlyout.Preview.Head(DependencyStyle.ArrowHead.CHEVRON));
    }

    private static List<MenuFlyout.Cell> cells(int count) {
        List<MenuFlyout.Cell> cells = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            cells.add(cell("c" + i));
        }
        return cells;
    }

    @Test
    @DisplayName("cells per line: four for a short row, three for a long one, never zero")
    void columnsWrapAtFour() {
        assertEquals(1, MenuFlyout.columns(1));
        assertEquals(2, MenuFlyout.columns(2));
        assertEquals(3, MenuFlyout.columns(3));
        assertEquals(4, MenuFlyout.columns(4));
        assertEquals(3, MenuFlyout.columns(5), "a five-cell row wraps as 3+2");
        assertEquals(3, MenuFlyout.columns(6), "and six as two lines of three");
    }

    @Test
    @DisplayName("a row's height is its label band plus its lines of cells")
    void rowHeights() {
        assertEquals(MenuFlyout.ROW_HEIGHT, MenuFlyout.rowHeight(MenuFlyout.text("Join handles", () -> {
        })));
        assertEquals(MenuFlyout.LABEL_HEIGHT + MenuFlyout.CELL_HEIGHT,
                MenuFlyout.rowHeight(MenuFlyout.cells("Head", cells(4), true)),
                "four cells are one line");
        assertEquals(MenuFlyout.LABEL_HEIGHT + 2 * MenuFlyout.CELL_HEIGHT,
                MenuFlyout.rowHeight(MenuFlyout.cells("Form", cells(6), true)),
                "six cells are two");
    }

    @Test
    @DisplayName("the flyout is as wide as its widest need: previews need more room than text")
    void theWidthFollowsTheContent() {
        assertEquals(150, MenuFlyout.width(List.of(MenuFlyout.text("No group", () -> {
        }))));
        assertEquals(MenuFlyout.FLYOUT_WIDTH,
                MenuFlyout.width(List.of(MenuFlyout.text("Join handles", () -> {
                }), MenuFlyout.cells("Head", cells(5), true))));
    }

    @Test
    @DisplayName("six cells wrap as two lines of three, and the fourth starts the second line at the left")
    void theGridWrapsByRow() {
        MenuFlyout.Placed placed =
                MenuFlyout.place(0, 0, List.of(MenuFlyout.cells("Form", cells(6), true)));
        BookGeometry.Rect row = placed.rows().get(0);

        assertEquals(MenuFlyout.LABEL_HEIGHT + 2 * MenuFlyout.CELL_HEIGHT, row.height());
        BookGeometry.Rect third = MenuFlyout.cellRect(row, 2, 6);
        BookGeometry.Rect fourth = MenuFlyout.cellRect(row, 3, 6);
        assertEquals(row.y() + MenuFlyout.LABEL_HEIGHT, third.y());
        assertEquals(row.y() + MenuFlyout.LABEL_HEIGHT + MenuFlyout.CELL_HEIGHT, fourth.y(),
                "the fourth cell wraps to the second line");
        assertEquals(MenuFlyout.cellRect(row, 0, 6).x(), fourth.x(),
                "and starts at the left again");
        for (int i = 0; i < 6; i++) {
            BookGeometry.Rect cell = MenuFlyout.cellRect(row, i, 6);
            assertTrue(cell.isInside(row), "cell " + i + " must sit inside its row: " + cell);
        }
    }

    @Test
    @DisplayName("a press on a cell tunes, on a text row chooses, on a reset chip clears, outside is null")
    void hitsRouteToTheirBehaviour() {
        AtomicInteger pressed = new AtomicInteger();
        AtomicInteger reset = new AtomicInteger();
        List<MenuFlyout.Cell> headCells = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            headCells.add(new MenuFlyout.Cell("c" + i, pressed::incrementAndGet, i == 0,
                    new MenuFlyout.Preview.Head(DependencyStyle.ArrowHead.CHEVRON)));
        }
        List<MenuFlyout.Row> rows = List.of(
                new MenuFlyout.Row.Cells("Head", headCells, reset::incrementAndGet, true),
                MenuFlyout.text("Join handles", pressed::incrementAndGet));
        MenuFlyout.Placed placed = MenuFlyout.place(10, 20, rows);

        BookGeometry.Rect firstCell = MenuFlyout.cellRect(placed.rows().get(0), 0, 4);
        MenuFlyout.Hit cellHit = MenuFlyout.hit(placed, firstCell.x() + 1, firstCell.y() + 1);
        assertInstanceOf(MenuFlyout.Hit.Tune.class, cellHit);
        ((MenuFlyout.Hit.Tune) cellHit).run().run();
        assertEquals(1, pressed.get(), "a cell press is the cell's own action");

        BookGeometry.Rect chip = MenuFlyout.resetRect(placed.rows().get(0), rows.get(0));
        assertNotNull(chip, "a row with a reset offers its chip");
        MenuFlyout.Hit chipHit = MenuFlyout.hit(placed, chip.x() + 1, chip.y() + 1);
        assertInstanceOf(MenuFlyout.Hit.Tune.class, chipHit);
        ((MenuFlyout.Hit.Tune) chipHit).run().run();
        assertEquals(1, reset.get(), "the chip clears the axis");
        assertEquals(1, pressed.get(), "and is not the cell's action");

        BookGeometry.Rect textRow = placed.rows().get(1);
        assertInstanceOf(MenuFlyout.Hit.Choose.class,
                MenuFlyout.hit(placed, textRow.x() + 1, textRow.y() + 1),
                "a text row closes with its action, the way text rows always have");

        assertNull(MenuFlyout.hit(placed, placed.panel().x() - 1, placed.panel().y()),
                "off the flyout is null, which is what lets the screen close the menu");
    }

    @Test
    @DisplayName("a disabled row absorbs its cells, and a reset-less row has no chip to hit")
    void disabledRowsAbsorb() {
        AtomicInteger pressed = new AtomicInteger();
        List<MenuFlyout.Row> rows = List.of(
                new MenuFlyout.Row.Cells("Density", cells(3), null, false));
        MenuFlyout.Placed placed = MenuFlyout.place(0, 0, rows);

        BookGeometry.Rect cell = MenuFlyout.cellRect(placed.rows().get(0), 0, 3);
        assertInstanceOf(MenuFlyout.Hit.None.class, MenuFlyout.hit(placed, cell.x() + 1, cell.y() + 1),
                "a switched-off row is drawn, not pressed");
        assertEquals(0, pressed.get());
        assertNull(MenuFlyout.resetRect(placed.rows().get(0), rows.get(0)),
                "no reset is a chip that does not exist, not a chip that does nothing");
    }

    @Test
    @DisplayName("an unpressable text row is still a hit: the overfull '…' absorbs rather than closes")
    void unpressableTextAbsorbs() {
        MenuFlyout.Placed placed = MenuFlyout.place(0, 0, List.of(MenuFlyout.text("\u2026", null)));
        BookGeometry.Rect row = placed.rows().get(0);

        assertInstanceOf(MenuFlyout.Hit.None.class, MenuFlyout.hit(placed, row.x() + 1, row.y() + 1));
    }
}
