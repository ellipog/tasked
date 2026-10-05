package dev.ellipog.tasked.client.dev;

import dev.ellipog.tasked.client.BookGeometry;
import dev.ellipog.tasked.quest.loot.RewardTable;
import dev.ellipog.tasked.quest.reward.TableReward;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two table panels' arithmetic, without a window.
 *
 * <p>What is worth pinning is what a wrong answer would look like on screen: a row that cannot be
 * pressed, a drop that lands on the wrong entry, and a percentage that tells an author their drop rate is
 * something it is not.
 */
@DisplayName("the table panels' arithmetic")
class TablePanelLayoutTest {

    private static final BookGeometry.Rect BODY = BookGeometry.Rect.at(10, 20, 300, 200);

    // ------------------------------------------------------------------
    // The browser
    // ------------------------------------------------------------------

    private static List<TableBrowserLayout.Row> tables() {
        return List.of(
                TableBrowserLayout.Row.table("tier_1_ores", "Tier 1 ores", "minecraft:iron_ingot", 14, true),
                TableBrowserLayout.Row.table("dungeon", "Dungeon loot", "minecraft:chest", 7, false));
    }

    @Test
    @DisplayName("the none row is always first, so clearing a reference is always visible")
    void theNoneRowComesFirst() {
        List<TableBrowserLayout.Row> rows = TableBrowserLayout.rows(tables(), "");

        assertEquals(3, rows.size());
        assertEquals(TableBrowserLayout.Kind.NONE, rows.get(0).kind());
        assertEquals("tier_1_ores", rows.get(1).id(), "and the tables keep their order");
    }

    @Test
    @DisplayName("a query filters by name and by id, and never filters the none row out")
    void queriesFilterByNameOrId() {
        assertEquals(2, TableBrowserLayout.rows(tables(), "dungeon").size(),
                "the none row and the one match");
        assertEquals(2, TableBrowserLayout.rows(tables(), "TIER_1").size(), "ids match, case-blind");
        assertEquals(2, TableBrowserLayout.rows(tables(), "ores").size(), "names match too");
        assertEquals(1, TableBrowserLayout.rows(tables(), "nothing like it").size(),
                "no match leaves the none row, which is how a reward is cleared");
    }

    @Test
    @DisplayName("a row is drawn and hit at the same rectangle, and the buttons are inside it")
    void rowsAreDrawnAndHitAlike() {
        List<TableBrowserLayout.Row> rows = TableBrowserLayout.rows(tables(), "");
        TableBrowserLayout.Frame frame = TableBrowserLayout.Frame.of(BODY);

        // The press registers a target over this rectangle, so "the row under the pointer" and "the row
        // drawn there" are one answer -- which is why there is no separate hit-test function here.
        BookGeometry.Rect first = TableBrowserLayout.rowRect(rows, frame, 0, 0);
        BookGeometry.Rect second = TableBrowserLayout.rowRect(rows, frame, 0, 1);
        assertEquals(first.bottom(), second.y(), "the rows tile the list with no gap between them");

        assertTrue(TableBrowserLayout.buttons(second).delete().right() <= second.right(),
                "the buttons stay inside the row");
        assertTrue(TableBrowserLayout.body(second).right() <= TableBrowserLayout.buttons(second).edit().x(),
                "and the row's own press area stops before them");

        assertTrue(first.y() >= frame.list().y(), "the first row starts inside the list");
        assertTrue(second.bottom() <= frame.list().bottom(),
                "and the second one has not run past the list's end");
    }

    @Test
    @DisplayName("a scrolled list still answers for the row under the pointer")
    void scrollingMovesTheHitTestWithTheDrawing() {
        TableBrowserLayout.Frame frame = TableBrowserLayout.Frame.of(BODY);
        // Enough rows to overflow the list (which holds five and a half of them at this size), so the
        // scroll is real rather than clamped away.
        List<TableBrowserLayout.Row> many = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            many.add(TableBrowserLayout.Row.table("t" + i, "Table " + i, "", 1, false));
        }
        List<TableBrowserLayout.Row> rows = TableBrowserLayout.rows(many, "");
        int max = TableBrowserLayout.maxScroll(rows, frame);
        assertTrue(max > 0, "the list must overflow for this to test anything");

        // A scroll is in pixels, as the wheel gives it: one row of scroll moves every row up by a row, so
        // a point that was over row one is over row two -- and the rectangle the press uses moved with it.
        double point = frame.list().y() + TableBrowserLayout.ROW_HEIGHT + 1;
        assertTrue(contains(TableBrowserLayout.rowRect(rows, frame, 0, 1), point));
        assertTrue(contains(TableBrowserLayout.rowRect(rows, frame, TableBrowserLayout.ROW_HEIGHT, 2),
                        point),
                "the hit test follows the drawing, which is the whole reason this is one class");
    }

    /** Whether a row's rectangle covers a pointer's y, the way the registered target does. */
    private static boolean contains(BookGeometry.Rect row, double y) {
        return y >= row.y() && y < row.bottom();
    }

    // ------------------------------------------------------------------
    // The editor
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the frame's bands tile the body without gaps or overlaps")
    void theFrameTilesTheBody() {
        TableEditorLayout.Frame frame = TableEditorLayout.Frame.of(BODY);

        assertEquals(BODY.y(), frame.crumb().y());
        assertEquals(frame.crumb().bottom(), frame.header().y());
        assertEquals(frame.header().bottom(), frame.toolbar().y());
        assertEquals(frame.toolbar().bottom(), frame.list().y());
        // The footer is the card's own -- `BookGeometry.overlayControls` places its buttons -- and the
        // body the panel is handed already stops above it. So the list ends where the body does, and a
        // row can never be drawn under a button.
        assertEquals(BODY.bottom(), frame.list().bottom());
    }

    @Test
    @DisplayName("the card's body stops above the footer band, where the controls are drawn")
    void theBodyLeavesTheFooterItsBand() {
        // The bug this pins: the way out of the table panels is drawn from `overlayControls`, which
        // places Back and the submit slot inside the footer band. A body that reached the card's bottom
        // would put the last row under that button -- drawn beneath it, and beaten in the hit test by
        // it, because the footer's target is registered first. So the body has to stop where the band
        // starts, and the band is `BookGeometry.MODAL_FOOTER_HEIGHT` tall.
        BookGeometry geometry = new BookGeometry(1280, 720);
        BookGeometry.Rect card = geometry.modal();
        BookGeometry.Rect body = TableEditorLayout.body(card);
        BookGeometry.Rect back = geometry.overlayControls(true).get("back");

        assertNotNull(back, "the footer places its controls from the same card");
        assertEquals(card.bottom() - TableEditorLayout.FOOTER_RESERVE, body.bottom(),
                "the body ends exactly where the footer band begins");
        assertTrue(body.bottom() <= back.y(),
                "so nothing in the body is drawn at the height the footer's buttons occupy");
        assertEquals(card.y() + TableEditorLayout.CARD_HEADER + TableEditorLayout.CARD_RULE
                        + TableEditorLayout.CARD_GAP, body.y(),
                "and it starts under the title strip, the rule and the air between them");
    }

    @Test
    @DisplayName("the search box has the band to itself, so nothing can hide under it")
    void theSearchBoxDoesNotOverlapAnything() {
        // The bug this pins: the first version drew two 78-pixel buttons in the same band and sized the
        // search box for 22-pixel ones, so "New" sat under the field -- invisible, and unclickable
        // because the field is a real widget that takes the press. The buttons are the card's footer
        // controls now, and the search box is the whole band.
        TableBrowserLayout.Frame frame = TableBrowserLayout.Frame.of(BODY);

        assertEquals(BODY.x(), frame.search().x());
        assertEquals(BODY.width(), frame.search().width(), "the search box is the full width");
        assertTrue(frame.list().y() >= frame.search().bottom(), "and the list starts below it");
    }

    @Test
    @DisplayName("the row's words fit their buttons, and the text stops before them")
    void rowButtonsAreSizedToTheirWords() {
        TableBrowserLayout.Frame frame = TableBrowserLayout.Frame.of(BODY);
        BookGeometry.Rect row = TableBrowserLayout.rowRect(TableBrowserLayout.rows(tables(), ""), frame,
                0, 1);
        var buttons = TableBrowserLayout.buttons(row);

        // "Edit" and "Copy" are words, not 22-pixel chips: a button narrower than its label is a label
        // that runs out of its own box and across the row -- what the screenshot showed.
        assertTrue(buttons.edit().width() >= TableBrowserLayout.EDIT_WIDTH);
        assertTrue(buttons.copy().width() >= TableBrowserLayout.COPY_WIDTH);
        assertTrue(buttons.delete().width() >= TableBrowserLayout.DELETE_WIDTH);

        BookGeometry.Rect detail = TableBrowserLayout.detail(row);
        assertTrue(detail.right() <= buttons.edit().x(),
                "the detail text is drawn before the buttons, not under them");
        assertTrue(TableBrowserLayout.name(row).right() <= detail.x(),
                "and the name stops before the detail");
        assertTrue(TableBrowserLayout.currentTag(row).right() <= detail.x(),
                "the current tag sits in the same reserved strip");
    }

    @Test
    @DisplayName("the header has room for both of its lines")
    void theHeaderFitsItsLines() {
        // The bug this pins: a third line drawn at y+30 in a 36-pixel header ran into the toolbar under
        // it, which is what put the rolls stepper on top of "Add item".
        TableEditorLayout.Frame frame = TableEditorLayout.Frame.of(BODY);
        var stepper = TableEditorLayout.stepper(frame.header().x(), frame.header().y() + 22);

        assertTrue(stepper.minus().bottom() <= frame.header().bottom(),
                "the stepper's line is inside the header");
        assertEquals(frame.header().bottom(), frame.toolbar().y(),
                "and the toolbar starts where the header ends, with nothing between them");
    }

    @Test
    @DisplayName("a mode reads as a word, not as the type id it is written with")
    void modesReadAsWords() {
        assertEquals("Random", TableEditorLayout.modeLabel(TableReward.Mode.RANDOM));
        assertEquals("Loot", TableEditorLayout.modeLabel(TableReward.Mode.LOOT));
        assertEquals("All once", TableEditorLayout.modeLabel(TableReward.Mode.ALL_TABLE),
                "all_table is the file's spelling, not a control's");
        assertEquals("Choice", TableEditorLayout.modeLabel(TableReward.Mode.CHOICE));
        for (TableReward.Mode mode : TableEditorLayout.previews()) {
            assertFalse(TableEditorLayout.modeHint(mode).isBlank(),
                    mode + " has no sentence for its hover");
        }
    }

    @Test
    @DisplayName("a drop below the last row is still in the list, and an empty table takes one")
    void dropsLandInsideTheList() {
        TableEditorLayout.Frame frame = TableEditorLayout.Frame.of(BODY);
        int top = frame.rowsTop();

        // Empty: anywhere in the list is the first position, which is what makes dropping into a table
        // an author just made possible at all.
        assertEquals(0, TableEditorLayout.dropIndexAt(0, frame, 0, frame.list().x() + 5, top + 5,
                TableEditorLayout.Folds.none()));
        assertEquals(0, TableEditorLayout.dropIndexAt(0, frame, 0, frame.list().x() + 5,
                frame.list().bottom() - 1, TableEditorLayout.Folds.none()));

        // Three entries, and a pointer past the last of them: the end, not nothing. The list is taller
        // than three rows, which is the case that matters -- a table with room to spare under its last
        // entry still takes a drop there.
        assertEquals(3, TableEditorLayout.dropIndexAt(3, frame, 0, frame.list().x() + 5,
                top + TableEditorLayout.ROW_HEIGHT * 3 + 1, TableEditorLayout.Folds.none()));
        assertEquals(1, TableEditorLayout.dropIndexAt(3, frame, 0, frame.list().x() + 5,
                top + TableEditorLayout.ROW_HEIGHT + 1, TableEditorLayout.Folds.none()));

        // The headings are the list's first strip, and a drag held over them is a drag at the top of
        // the list rather than a refusal: they are not a row, but they are above the first one.
        assertEquals(0, TableEditorLayout.dropIndexAt(3, frame, 0, frame.list().x() + 5,
                frame.list().y() + 1, TableEditorLayout.Folds.none()));

        // Outside the list is nothing: the breadcrumb, the toolbar and the footer are not a drop target.
        assertEquals(-1, TableEditorLayout.dropIndexAt(3, frame, 0, frame.list().x() + 5,
                frame.header().y() + 2, TableEditorLayout.Folds.none()));
        assertEquals(-1, TableEditorLayout.dropIndexAt(3, frame, 0, frame.list().x() - 1, top + 5,
                TableEditorLayout.Folds.none()));
        assertEquals(-1, TableEditorLayout.dropIndexAt(3, frame, 0, frame.list().x() + 5,
                BODY.bottom() + 2, TableEditorLayout.Folds.none()),
                "and below the body -- where the card's footer buttons are -- is nothing");
    }

    @Test
    @DisplayName("the headings are the list's first strip, and the rows start under them")
    void theHeadingsSitAboveTheRows() {
        TableEditorLayout.Frame frame = TableEditorLayout.Frame.of(BODY);

        assertEquals(frame.list().y(), frame.headings().y(), "the strip is the top of the list");
        assertEquals(TableEditorLayout.HEADING_HEIGHT, frame.headings().height());
        assertEquals(frame.headings().bottom(), frame.rowsTop(), "and the rows begin under it");
        assertEquals(frame.list().bottom(), frame.rows().bottom(), "the rows keep the rest of the list");
        assertEquals(frame.rowsTop(), TableEditorLayout.rowRect(1, frame, 0, 0,
                        TableEditorLayout.Folds.none()).y(),
                "a row at scroll zero starts at the first row's line, not at the strip");
    }

    @Test
    @DisplayName("a folded row is as tall as its own fields, and pushes the rows under it down")
    void aFoldIsPartOfTheRowAndGrowsWithItsFields() {
        // The bug this pins: the fold's band was one fixed eighteen pixels, which was the whole form when
        // every entry was an item and a count. A row with four lines of controls drew three of them over
        // the row below -- and the row below then lost the presses its own controls should have taken,
        // because the fold's targets were registered first.
        TableEditorLayout.Frame frame = TableEditorLayout.Frame.of(BODY);
        TableEditorLayout.Folds oneLine = TableEditorLayout.Folds.of(Set.of(1), index -> 1);
        TableEditorLayout.Folds threeLines = TableEditorLayout.Folds.of(Set.of(1), index -> 3);

        BookGeometry.Rect first = TableEditorLayout.rowRect(3, frame, 0, 0, oneLine);
        BookGeometry.Rect second = TableEditorLayout.rowRect(3, frame, 0, 1, oneLine);
        BookGeometry.Rect third = TableEditorLayout.rowRect(3, frame, 0, 2, oneLine);

        assertEquals(TableEditorLayout.ROW_HEIGHT, first.height());
        assertEquals(TableEditorLayout.ROW_HEIGHT + TableEditorLayout.FOLD_HEIGHT, second.height(),
                "a one-line fold is the band's old height, so the commonest entry is unchanged");
        assertEquals(first.bottom(), second.y(), "and the row under it starts where it ends");
        assertEquals(second.bottom(), third.y(), "so an open entry cannot be drawn over its neighbour");

        // Three lines is three lines taller, and every row under it moves down by exactly that much.
        BookGeometry.Rect tall = TableEditorLayout.rowRect(3, frame, 0, 1, threeLines);
        assertEquals(TableEditorLayout.ROW_HEIGHT + TableEditorLayout.foldHeight(3), tall.height());
        assertEquals(second.height() + 2 * TableEditorLayout.FOLD_LINE_HEIGHT, tall.height(),
                "two more lines is two more lines, whatever the padding is");
        assertEquals(tall.bottom(), TableEditorLayout.rowRect(3, frame, 0, 2, threeLines).y(),
                "the row below follows the taller fold rather than overlapping it");

        // The hit test follows the drawing: a point in the fold's band belongs to the folded row, not to
        // the row that used to be drawn there -- which is the bug this pins, because the fold's own
        // controls were then the first targets the press scan found.
        assertEquals(1, TableEditorLayout.dropIndexAt(3, frame, 0, frame.list().x() + 5,
                tall.y() + TableEditorLayout.ROW_HEIGHT + 1, threeLines));

        assertTrue(TableEditorLayout.contentHeight(3, threeLines)
                        > TableEditorLayout.contentHeight(3, TableEditorLayout.Folds.none()),
                "the fold counts towards the content's height, so it can be scrolled to");
        assertEquals(0, TableEditorLayout.maxScroll(3, frame, TableEditorLayout.Folds.none()),
                "three closed rows fit the list, so there is nothing to scroll");
    }

    @Test
    @DisplayName("an open fold with no lines is not a state a caller can express")
    void foldsAnswerZeroForAnythingClosed() {
        // The drift this prevents: a set that says entry 2 is open and a line count that says nothing is.
        // The height and the drawing both derive from this one value, so the two cannot disagree.
        TableEditorLayout.Folds folds = TableEditorLayout.Folds.of(Set.of(2), index -> 4);

        assertEquals(4, folds.linesOf(2));
        assertEquals(0, folds.linesOf(0), "a closed entry draws no lines, whatever the function says");
        assertFalse(folds.isOpen(0));
        assertTrue(folds.isOpen(2));
        assertEquals(TableEditorLayout.ROW_HEIGHT, TableEditorLayout.heightOf(0, folds));
        assertEquals(TableEditorLayout.ROW_HEIGHT + TableEditorLayout.foldHeight(4),
                TableEditorLayout.heightOf(2, folds));
    }

    @Test
    @DisplayName("a fold's lines tile its band, inside the row and above the row below")
    void foldLinesStayInsideTheirRow() {
        TableEditorLayout.Folds folds = TableEditorLayout.Folds.of(Set.of(1), index -> 3);
        TableEditorLayout.Frame frame = TableEditorLayout.Frame.of(BODY);
        BookGeometry.Rect row = TableEditorLayout.rowRect(4, frame, 0, 1, folds);
        BookGeometry.Rect next = TableEditorLayout.rowRect(4, frame, 0, 2, folds);

        BookGeometry.Rect previous = null;
        for (int line = 0; line < 3; line++) {
            BookGeometry.Rect band = TableEditorLayout.foldBand(row, line);
            assertEquals(TableEditorLayout.FOLD_LINE_HEIGHT, band.height());
            assertTrue(band.y() >= row.y() + TableEditorLayout.ROW_HEIGHT,
                    "line " + line + " is under the row's own controls");
            assertTrue(band.bottom() <= row.bottom(),
                    "line " + line + " is inside the row, not over the row below");
            assertTrue(band.bottom() <= next.y(), "and above the next row's line");
            if (previous != null) {
                assertTrue(band.y() >= previous.bottom(), "the lines do not overlap each other");
            }
            previous = band;
        }
        // A line past the end of the band is too short to hold a control, which is what the drawing
        // checks before it draws one: a control drawn in a one-pixel band would sit on the row below.
        assertTrue(TableEditorLayout.foldBand(row, 3).height() < TableEditorLayout.FOLD_LINE_HEIGHT,
                "there is no fourth line in a three-line fold");
        assertTrue(TableEditorLayout.foldBand(row, 3).bottom() <= row.bottom(),
                "and even the remainder is inside the row");
    }

    @Test
    @DisplayName("a fold's line is split into equal cells that stay inside it")
    void foldCellsTileTheirLine() {
        BookGeometry.Rect band = TableEditorLayout.foldBand(
                BookGeometry.Rect.at(10, 20, 300, TableEditorLayout.ROW_HEIGHT
                        + TableEditorLayout.foldHeight(1)), 0);

        for (int columns = 1; columns <= 3; columns++) {
            var cells = TableEditorLayout.foldCells(band, columns);
            assertEquals(columns, cells.size());
            assertEquals(band.x(), cells.get(0).x(), "the first cell starts at the line's edge");
            assertEquals(band.right(), cells.get(columns - 1).right(),
                    "the last cell reaches the line's other edge, so nothing is lost to rounding");
            for (int c = 0; c < columns; c++) {
                assertTrue(cells.get(c).width() > 0, "a cell with no width is a control nobody can press");
                if (c > 0) {
                    assertTrue(cells.get(c).x() >= cells.get(c - 1).right(),
                            "cells do not overlap");
                }
            }
        }
        // And a stepper fitted into the narrowest cell a three-column line can produce still has three
        // parts, in order, inside the cell: the `+` was drawn past the cell's right edge when the chips
        // were a fixed width.
        BookGeometry.Rect narrow = BookGeometry.Rect.at(10, 20, 54, TableEditorLayout.FOLD_LINE_HEIGHT);
        var stepper = TableEditorLayout.foldStepper(narrow);
        assertEquals(narrow.x(), stepper.minus().x());
        assertTrue(stepper.minus().right() <= stepper.value().x());
        assertTrue(stepper.value().right() <= stepper.plus().x());
        assertTrue(stepper.plus().right() <= narrow.right(), "the `+` stays inside its cell");
    }

    @Test
    @DisplayName("the way-out button undoes one step, decided in one place")
    void theFooterKnowsWhatItLeaves() {
        // The bug this pins, in both its forms: the label was "Done" whenever no nested table was on the
        // stack, so with a page of types open it said Done and closed the panel (leaving the picker armed,
        // which is how a card came to draw a list of types over itself) -- and the roll report had no case
        // in the decision at all, so its footer closed the panel from a page that was still on screen.
        // Both are one press doing two steps, and the label and the act are two readings of this decision.
        assertEquals(TableEditorLayout.Exit.LEAVE_BROWSER,
                TableEditorLayout.exit(true, TableEditorLayout.TablePage.LIST, false));

        for (TableEditorLayout.TablePage page : new TableEditorLayout.TablePage[] {
                TableEditorLayout.TablePage.PICKER, TableEditorLayout.TablePage.ROLL }) {
            TableEditorLayout.Exit expected = page == TableEditorLayout.TablePage.PICKER
                    ? TableEditorLayout.Exit.LEAVE_PICKER : TableEditorLayout.Exit.LEAVE_ROLL;
            assertEquals(expected, TableEditorLayout.exit(false, page, false), page + " is left, not the panel");
            assertEquals(expected, TableEditorLayout.exit(false, page, true),
                    page + " is innermost: it is left before a table's own back step");
        }

        assertEquals(TableEditorLayout.Exit.TABLE_UP,
                TableEditorLayout.exit(false, TableEditorLayout.TablePage.LIST, true));
        assertEquals(TableEditorLayout.Exit.CLOSE,
                TableEditorLayout.exit(false, TableEditorLayout.TablePage.LIST, false),
                "and only the editor's own root ends the session in it");
    }

    @Test
    @DisplayName("a folded number's label is inside its cell, left of the stepper")
    void foldNumberKeepsItsLabelInTheCell() {
        // The bug this pins: the drawing put the label at `plus.right() + 4`, and the stepper is
        // stretched to fill whatever cell it is given -- so the label began at the cell's own right edge
        // and ran over the neighbour. On an item's fold, "Count" was printed across the "Extra"
        // stepper's `-` chip. The label is a rectangle, so it belongs to this class like the rest.
        for (int width : new int[] {54, 96, 150, 246, 400}) {
            BookGeometry.Rect cell = BookGeometry.Rect.at(10, 20, width,
                    TableEditorLayout.FOLD_LINE_HEIGHT);

            for (String label : new String[] {"Count", "Extra", "As", "Permission level"}) {
                var number = TableEditorLayout.foldNumber(cell, label.length());

                String where = label + " in a " + width + "px cell";
                assertTrue(number.label().x() >= cell.x(), where + ": the label starts inside");
                assertTrue(number.label().right() <= cell.right(), where + ": and ends inside");
                // Never wider than its own words, and never so wide that the stepper it labels has no
                // room: the second bound is what binds in a narrow cell, and it is deliberately the one
                // that wins -- a truncated label is a word an eye can still finish, and a stepper with
                // no chips is a control that cannot be pressed.
                assertTrue(number.label().width()
                                <= label.length() * TableEditorLayout.LABEL_CHAR_WIDTH
                                        + TableEditorLayout.GAP,
                        where + ": the label is not wider than the guess for its words");
                assertTrue(number.minus().x() >= number.label().right(),
                        where + ": the stepper starts after the label");
                assertTrue(number.plus().right() <= cell.right(),
                        where + ": and the `+` stays inside the cell");
                assertTrue(number.minus().right() <= number.value().x(), where);
                assertTrue(number.value().right() <= number.plus().x(), where);
            }

            // A cell too narrow for a label and a stepper gives the label nothing rather than the
            // stepper: a control that cannot be pressed is worse than a word that is not there.
            var cramped = TableEditorLayout.foldNumber(
                    BookGeometry.Rect.at(10, 20, 24, TableEditorLayout.FOLD_LINE_HEIGHT), 12);
            assertEquals(0, cramped.label().width());
            assertTrue(cramped.plus().right() <= 34, "and the stepper keeps what there is");
        }
    }

    @Test
    @DisplayName("the band a page lays out in is inside the card and below the toolbar")
    void thePageBandCannotReachTheHeader() {
        // What the table editor's type picker lays its rows out in now. It used to be the *quest* card's
        // body -- a different rectangle by design -- which drew the picker's heading on the table card's
        // title line and its first rows behind the toolbar. This pins the property the fix leans on:
        // the band is below everything the card keeps above it, and inside the body it was given.
        BookGeometry geometry = new BookGeometry(1280, 720);
        BookGeometry.Rect body = TableEditorLayout.body(geometry.modal());
        TableEditorLayout.Frame frame = TableEditorLayout.Frame.of(body);
        BookGeometry.Rect page = frame.list();

        assertTrue(page.y() >= frame.toolbar().bottom(),
                "a page cannot start in the toolbar's band");
        assertTrue(page.y() >= frame.header().bottom());
        assertTrue(page.bottom() <= body.bottom(), "nor run past the body, where the footer's controls are");
        assertTrue(page.x() >= body.x() && page.right() <= body.right(), "and it is inside the card");
    }

    @Test
    @DisplayName("the odds column is the room a row's name leaves for it")
    void theOddsColumnAndTheNameReserveAgree() {
        // Two literals used to have to agree -- 96 here and 100 in the screen's truncation -- with
        // nothing checking that they did. One constant is what makes a name one pixel too long
        // impossible rather than unlikely.
        assertEquals(TableEditorLayout.ODDS_WIDTH + TableEditorLayout.GAP,
                TableEditorLayout.ODDS_RESERVE);

        BookGeometry.Rect row = BookGeometry.Rect.at(10, 20, 300, TableEditorLayout.ROW_HEIGHT);
        BookGeometry.Rect odds = TableEditorLayout.odds(row);
        BookGeometry.Rect name = TableEditorLayout.row(row).name();

        assertTrue(odds.right() <= name.right(), "the odds are drawn inside the name's band");
        assertTrue(odds.x() >= name.x(), "and start inside it too");
        assertTrue(name.width() - TableEditorLayout.ODDS_RESERVE >= 0,
                "so the room the name keeps for them is never negative");
    }

    @Test
    @DisplayName("a chance says what it is: Always, 100%, Pick, or a qualified percentage")
    void chancesAreWrittenAsTheyBehave() {
        RewardTable.Chance always = new RewardTable.Chance(true, 0);
        RewardTable.Chance oneInFive = new RewardTable.Chance(false, 0.2);

        assertEquals("Always", TableEditorLayout.chance(always, TableReward.Mode.RANDOM, 1),
                "weight zero grants whatever the dice say, and 0.0% would be the opposite");
        assertEquals("100%", TableEditorLayout.chance(oneInFive, TableReward.Mode.ALL_TABLE, 1),
                "an all-table has no dice");
        assertEquals("Pick", TableEditorLayout.chance(oneInFive, TableReward.Mode.CHOICE, 1),
                "a choice offers rather than rolls");
        assertEquals("20.0%", TableEditorLayout.chance(oneInFive, TableReward.Mode.RANDOM, 1));
        assertEquals("20.0% (per roll)", TableEditorLayout.chance(oneInFive, TableReward.Mode.RANDOM, 3),
                "three throws at 20% is not a 20% chance of seeing it");
        assertEquals("48.8% at least once",
                TableEditorLayout.atLeastOnce(oneInFive, TableReward.Mode.RANDOM, 3));
        assertTrue(TableEditorLayout.atLeastOnce(oneInFive, TableReward.Mode.RANDOM, 1).isEmpty(),
                "one throw has nothing to add");
        assertTrue(TableEditorLayout.atLeastOnce(always, TableReward.Mode.RANDOM, 3).isEmpty());
    }

    @Test
    @DisplayName("the preview ring cycles through the four modes and wraps")
    void thePreviewRingWraps() {
        assertEquals(TableReward.Mode.LOOT,
                TableEditorLayout.nextPreview(TableReward.Mode.RANDOM));
        assertEquals(TableReward.Mode.CHOICE,
                TableEditorLayout.nextPreview(TableReward.Mode.ALL_TABLE));
        assertEquals(TableReward.Mode.RANDOM,
                TableEditorLayout.nextPreview(TableReward.Mode.CHOICE), "and back to the start");

        assertTrue(TableEditorLayout.includesEmpty(TableReward.Mode.LOOT));
        assertFalse(TableEditorLayout.includesEmpty(TableReward.Mode.RANDOM),
                "a random reward promises something, so the empty band is out of play");
    }

    @Test
    @DisplayName("a row's controls sit inside the row, in the order they are read")
    void rowControlsAreOrdered() {
        TableEditorLayout.Row row = TableEditorLayout.row(
                BookGeometry.Rect.at(10, 20, 300, TableEditorLayout.ROW_HEIGHT));

        assertTrue(row.icon().x() >= 10);
        assertTrue(row.name().x() >= row.icon().right(), "the name follows the icon");
        assertTrue(row.minus().x() > row.name().x(), "the stepper follows the name");
        assertTrue(row.value().x() > row.minus().right());
        assertTrue(row.plus().x() > row.value().right());
        assertTrue(row.fold().x() > row.plus().right());
        assertTrue(row.remove().x() > row.fold().right());
        assertTrue(row.remove().right() <= 310, "and the cross stays inside the row");
    }
}
