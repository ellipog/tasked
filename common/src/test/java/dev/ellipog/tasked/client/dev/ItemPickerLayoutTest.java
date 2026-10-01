package dev.ellipog.tasked.client.dev;

import dev.ellipog.tasked.client.BookGeometry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The item picker's arithmetic: which rows exist, how tall the list is, and where a pointer lands.
 *
 * <h2>Why this is a class and not numbers at the call site</h2>
 *
 * <p>The drawing and the press have to agree about where a row is -- a row drawn at one rectangle and
 * hit at another is a row an author cannot press, and the failure reads as a broken mouse rather than
 * as arithmetic. One derivation, asserted here over real rectangles, is what keeps them the same
 * derivation. No client is needed: rows are integers.
 */
@DisplayName("the item picker's layout")
class ItemPickerLayoutTest {

    private static final ItemPicker.Entry OAK =
            new ItemPicker.Entry("minecraft:oak_log", "Oak Log", 8);
    private static final ItemPicker.Entry STICK =
            new ItemPicker.Entry("minecraft:stick", "Stick", 1);

    /** The same item as it comes from the registry: carried counts belong to the inventory's rows. */
    private static final ItemPicker.Entry OAK_REGISTRY =
            new ItemPicker.Entry("minecraft:oak_log", "Oak Log", 0);

    /** A frame with a real search box and a list 100 tall, so the scroll rules have somewhere to go. */
    private static ItemPickerLayout.Frame frame() {
        return new ItemPickerLayout.Frame(
                BookGeometry.Rect.at(10, 20, 200, ItemPickerLayout.SEARCH_HEIGHT),
                BookGeometry.Rect.at(10, 20 + ItemPickerLayout.SEARCH_HEIGHT, 200, 100));
    }

    @Test
    @DisplayName("an empty picker is no rows at all -- the empty state is the drawing's, not a row's")
    void emptyIsEmpty() {
        assertTrue(ItemPickerLayout.compose(List.of(), List.of(), ItemPickerLayout.Current.NONE, null, "")
                .isEmpty());
    }

    private static final ItemPickerLayout.Current CLEARABLE =
            new ItemPickerLayout.Current("minecraft:oak_log", true, true);

    @Test
    @DisplayName("the current value gets a clear row, the inventory its heading, and counts only over one")
    void sections() {
        List<ItemPickerLayout.Row> rows = ItemPickerLayout.compose(
                List.of(OAK, STICK), List.of(), CLEARABLE, null, "");

        assertEquals(ItemPickerLayout.Kind.CLEAR, rows.get(0).kind(), "clear first, above everything");
        assertEquals("Clear", rows.get(0).label(), "and it says what the press does");
        assertEquals("goes back to the default", rows.get(0).secondary());
        assertEquals(ItemPickerLayout.Kind.HEADING, rows.get(1).kind());
        assertEquals("In your inventory", rows.get(1).label());
        assertEquals("x8 Oak Log", rows.get(2).label(),
                "the row is the item's name; a count worth saying rides in front");
        assertEquals("minecraft:oak_log", rows.get(2).secondary(),
                "and the id is shown, because it is what a typed answer matches");
        assertEquals("Stick", rows.get(3).label());
        assertEquals("minecraft:stick", rows.get(3).secondary());
        assertEquals(4, rows.size(), "no query, no All-items section");
    }

    @Test
    @DisplayName("a query puts the results above the inventory, and the inventory keeps its own")
    void aQueryOpensTheList() {
        List<ItemPickerLayout.Row> rows = ItemPickerLayout.compose(
                List.of(STICK), List.of(OAK_REGISTRY), ItemPickerLayout.Current.NONE, null, "oak");

        assertEquals("All items", rows.get(0).label(), "results first -- the list is what was asked for");
        assertEquals("minecraft:oak_log", rows.get(1).id());
        assertEquals("Oak Log", rows.get(1).label(), "a name search's results read as names");
        assertEquals("minecraft:oak_log", rows.get(1).secondary());
        assertEquals("In your inventory", rows.get(2).label(), "and what you carry follows");
        assertEquals("Stick", rows.get(3).label());
        assertEquals(4, rows.size());
    }

    @Test
    @DisplayName("a current value the build does not have gets its own row, kept and marked")
    void theMissingCurrentValueIsShown() {
        List<ItemPickerLayout.Row> rows = ItemPickerLayout.compose(
                List.of(), List.of(),
                new ItemPickerLayout.Current("someothermod:widget", false, false), null, "");

        assertEquals(ItemPickerLayout.Kind.MISSING, rows.get(0).kind());
        assertEquals("someothermod:widget", rows.get(0).id(), "the id travels, so it can be kept");
        assertEquals("missing - the id is kept", rows.get(0).secondary());
        assertEquals(1, rows.size(), "and there is nothing else to show");
    }

    @Test
    @DisplayName("a typed id that matches nothing is offered as a row, so it can be written on purpose")
    void theTypedMissingIdIsOffered() {
        List<ItemPickerLayout.Row> rows = ItemPickerLayout.compose(
                List.of(), List.of(), ItemPickerLayout.Current.NONE,
                "someothermod:widget", "someothermod:widget");

        assertEquals(ItemPickerLayout.Kind.HEADING, rows.get(0).kind());
        assertEquals("All items", rows.get(0).label());
        assertEquals(ItemPickerLayout.Kind.MISSING, rows.get(1).kind());
        assertEquals("someothermod:widget", rows.get(1).id());
        assertEquals("not installed - use it anyway", rows.get(1).secondary());
    }

    @Test
    @DisplayName("the frame is the body's top strip for the box, and the rest for the list")
    void theFrameSplitsTheBody() {
        BookGeometry.Rect body = BookGeometry.Rect.at(5, 7, 210, 130);
        ItemPickerLayout.Frame frame = ItemPickerLayout.Frame.of(body);

        assertEquals(body.x(), frame.search().x());
        assertEquals(body.y(), frame.search().y());
        assertEquals(ItemPickerLayout.SEARCH_HEIGHT, frame.search().height());
        assertEquals(body.width(), frame.search().width());
        assertEquals(frame.search().bottom(), frame.list().y(), "no gap: the list starts under the box");
        assertEquals(body.bottom(), frame.list().bottom(), "and ends with the body");
    }

    @Test
    @DisplayName("the content is as tall as its rows, and the scroll stops at the last of them")
    void theScroll() {
        ItemPickerLayout.Frame frame = frame();
        List<ItemPickerLayout.Row> rows = ItemPickerLayout.compose(
                List.of(OAK, STICK), List.of(OAK, STICK), CLEARABLE, null, "oak");

        int content = ItemPickerLayout.contentHeight(rows);
        assertTrue(content > frame.list().height(), "the fixture should overflow, or this proves nothing");
        assertEquals(content - frame.list().height(), ItemPickerLayout.maxScroll(rows, frame));

        assertTrue(ItemPickerLayout.maxScroll(List.of(), frame) == 0, "nothing to scroll costs nothing");
    }

    @Test
    @DisplayName("a row is drawn and pressed at one rectangle, and the scroll moves both together")
    void rowsMapThroughTheScroll() {
        ItemPickerLayout.Frame frame = frame();
        List<ItemPickerLayout.Row> rows = ItemPickerLayout.compose(
                List.of(OAK), List.of(), ItemPickerLayout.Current.NONE, null, "");

        // Row 0 is the inventory heading, row 1 the item.
        BookGeometry.Rect at0 = ItemPickerLayout.rowRect(rows, frame, 0, 1);
        assertEquals(frame.list().x(), at0.x());
        assertTrue(at0.y() >= frame.list().y(), "inside the list");
        assertEquals(1, ItemPickerLayout.rowAt(rows, frame, 0, at0.y() + 1));
        assertEquals(-1, ItemPickerLayout.rowAt(rows, frame, 0, at0.bottom() + 40),
                "below the last row is no row");

        BookGeometry.Rect scrolled = ItemPickerLayout.rowRect(rows, frame, 10, 1);
        assertEquals(at0.y() - 10, scrolled.y(), "ten scrolled is ten up");
    }

    @Test
    @DisplayName("the selection steps over headings, and a stale index lands on a real row")
    void theSelectionSteps() {
        List<ItemPickerLayout.Row> rows = ItemPickerLayout.compose(
                List.of(OAK, STICK), List.of(OAK), CLEARABLE, null, "oak");

        int first = ItemPickerLayout.firstPickable(rows);
        assertEquals(0, first, "the clear row is pickable");
        int second = ItemPickerLayout.step(rows, first, 1);
        assertEquals(2, second, "the heading between them is not a stop");
        int back = ItemPickerLayout.step(rows, second, -1);
        assertEquals(first, back, "and back again");
        assertEquals(rows.size() - 1, ItemPickerLayout.step(rows, rows.size() - 1, 1),
                "the end is the end, not a wrap");
        assertEquals(rows.size() - 1, ItemPickerLayout.clamp(rows, 99),
                "a stale index from a shrunken list lands on the nearest real row, not the first");
        assertEquals(first, ItemPickerLayout.clamp(rows, -5), "and one below zero lands on the first");
        assertEquals(-1, ItemPickerLayout.firstPickable(List.of()), "nothing to select");
    }
}
