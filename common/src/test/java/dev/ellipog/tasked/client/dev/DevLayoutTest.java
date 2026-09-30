package dev.ellipog.tasked.client.dev;

import dev.ellipog.armature.client.ui.ThemeToken;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.tasked.client.BookGeometry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The developer screen's composition: every colour in the list, and the card's own arithmetic.
 *
 * <h2>What is asked here, and what cannot be</h2>
 *
 * <p>The list is long and the card is fixed, so the questions that matter are "is every token in it"
 * and "is every row inside the region the screen will draw it in" -- both of which are arithmetic and
 * neither of which needs a client. The screen itself is a {@code Screen}, so nothing in it can be
 * asserted; that split is why this class exists, and it is the same split
 * {@code PartyPanelLayoutTest} records.
 *
 * <p>The frame's own assertions are the ones worth reading twice, because they are what a rebuild on
 * every press would otherwise move: the bands are placed from the card's bottom, and the list gets what
 * is left. A list that overlapped the footer would draw rows over the Save button, which is a fault no
 * amount of scrolling fixes.
 */
@DisplayName("The developer screen's layout")
class DevLayoutTest {

    private static final Measure MEASURE = Measure.monospace(6, 9);

    /** The three switches, as the screen builds them. Labels are the state, so they are not asserted. */
    private static List<DevLayout.Action> switches() {
        return List.of(
                DevLayout.Action.button(DevLayout.DEV, "Developer mode: off", "Turn on"),
                DevLayout.Action.button(DevLayout.THEME, "Theme: Modern", "Change"),
                DevLayout.Action.button(DevLayout.MOTION, "Motion: on", "Turn off"));
    }

    private static List<DevLayout.Action> everything() {
        java.util.List<DevLayout.Action> all = new java.util.ArrayList<>(switches());
        all.addAll(DevLayout.colourRows());
        return List.copyOf(all);
    }

    // ------------------------------------------------------------------
    // The list
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every theme token is in the list, in the registry's own order, under a heading")
    void everyTokenIsListed() {
        List<DevLayout.Action> rows = DevLayout.colourRows();

        Set<String> keys = new HashSet<>();
        ThemeToken.Group last = null;
        int headings = 0;
        int colours = 0;

        for (DevLayout.Action row : rows) {
            assertTrue(keys.add(row.key()), () -> "a duplicate key: " + row.key());
            if (row.heading()) {
                headings++;
                last = ThemeToken.Group.valueOf(row.key().substring("group:".length()));
                continue;
            }
            ThemeToken token = ThemeToken.byId(DevLayout.tokenId(row.key()));
            assertNotNull(token, () -> "a colour row for an unknown token: " + row.key());
            assertEquals(last, token.group(),
                    () -> token.id() + " is under the wrong heading, or none");
            colours++;
        }

        assertEquals(ThemeToken.ALL.size(), colours, "every token, once");
        assertEquals(ThemeToken.Group.values().length, headings,
                "one heading per group -- no more, because a group with no tokens above it would be a "
                        + "heading over nothing, and no fewer, because a group that lost its heading "
                        + "would read as part of the one before it");
    }

    @Test
    @DisplayName("a colour row is the control, and a switch row carries a button")
    void rowsKnowWhatTheyAre() {
        DevLayout.Action control = DevLayout.Action.row(DevLayout.tokenKey("panel"), "Panel");
        assertTrue(control.isControl());
        assertFalse(control.hasButton(), "the whole row is the control, so there is no strip");

        DevLayout.Action button = DevLayout.Action.button(DevLayout.DEV, "Developer mode: off", "Turn on");
        assertTrue(button.hasButton());
        assertFalse(button.isControl());

        DevLayout.Action heading = DevLayout.Action.heading(DevLayout.groupKey(ThemeToken.Group.TEXT), "Text");
        assertFalse(heading.hasButton(), "a heading is drawn and nothing else");
        assertFalse(heading.isControl());

        // A key that is not a colour row names no token, which is what the screen's select() relies on
        // to refuse a press it did not expect.
        assertNull(DevLayout.tokenId("group:TEXT"));
        assertNull(DevLayout.tokenId(null));
        assertEquals("panel", DevLayout.tokenId(DevLayout.tokenKey("panel")));
    }

    @Test
    @DisplayName("the rows are placed in order, they do not overlap, and the height counts them all")
    void theListIsPlaced() {
        List<DevLayout.Action> rows = everything();
        Layout layout = DevLayout.build(rows, DevLayout.WIDTH, MEASURE);

        List<Slot> slots = layout.slots();
        assertEquals(rows.size(), slots.size(), "one slot per row");

        int lowest = 0;
        for (int i = 0; i < slots.size(); i++) {
            // Copied to a final local, because the message lambdas are deferred and `i` moves on -- the
            // same rule the roster's tests record: a lambda naming whichever row the loop had reached by
            // the time it ran would report the wrong pair, and only on failure.
            final int at = i;
            Slot slot = slots.get(i);
            assertNotNull(slot);
            assertTrue(slot.width() >= 0 && slot.height() > 0, () -> "empty row: " + slot);
            if (i > 0) {
                assertTrue(slot.y() >= slots.get(at - 1).bottom(),
                        () -> "row " + at + " overlaps the one before it: " + slots.get(at - 1)
                                + " then " + slot);
            }
            lowest = Math.max(lowest, slot.bottom());
        }
        assertEquals(lowest, layout.height(), "the height is the bottom of the last thing placed");
    }

    @Test
    @DisplayName("a switch row's button sits in the gap its row reserved, against the column's edge")
    void buttonsSitInTheirRows() {
        Layout layout = DevLayout.build(switches(), DevLayout.WIDTH, MEASURE);

        for (DevLayout.Action row : switches()) {
            Slot slot = layout.slot(row.key());
            assertNotNull(slot);
            Slot strip = DevLayout.strip(slot);
            assertNotNull(strip);

            // The row's slot is its *content*: a STRETCH row is narrowed by its insets, so the gap the
            // inset reserved is to the right of the slot and the button belongs there. Asserted on both
            // sides, because the first version placed it inside the slot -- which compiles, looks like a
            // button, and leaves sixty-six pixels of empty card between it and the edge.
            assertTrue(strip.x() >= slot.right(),
                    () -> "the button overlaps the row's own label: row " + slot + ", button " + strip);
            assertTrue(strip.right() <= DevLayout.WIDTH - DevLayout.STRIP_INSET,
                    () -> "the button left the column: " + strip);
            assertTrue(strip.right() >= DevLayout.WIDTH - DevLayout.STRIP_INSET - DevLayout.STRIP_WIDTH,
                    () -> "the button is not against the column's right edge: " + strip);
            assertTrue(strip.y() >= slot.y() && strip.bottom() <= slot.bottom(),
                    () -> "the button left its row vertically: row " + slot + ", button " + strip);
        }
    }

    @Test
    @DisplayName("a zero-width column places empty rows rather than a negative rectangle")
    void zeroWidthIsSafe() {
        for (int width : new int[] {0, -1, 1, 8}) {
            Layout layout = DevLayout.build(everything(), width, MEASURE);
            for (Slot slot : layout.slots()) {
                assertTrue(slot.width() >= 0, () -> "a negative width at column " + width + ": " + slot);
                Slot strip = DevLayout.strip(slot);
                assertTrue(strip.width() >= 0 && strip.x() >= slot.x(),
                        () -> "a strip outside its row at column " + width + ": " + strip);
            }
        }
    }

    // ------------------------------------------------------------------
    // The card
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the bands do not overlap: title, list, editing line, channels, footer")
    void theBandsStack() {
        for (int[] size : new int[][] {{854, 480}, {427, 240}, {1200, 700}, {320, 200}}) {
            DevLayout.Frame frame = DevLayout.frame(size[0], size[1]);
            String at = " at " + size[0] + "x" + size[1];

            BookGeometry.Rect card = frame.card();
            List<BookGeometry.Rect> bands = List.of(frame.title(), frame.body(), frame.editing(),
                    frame.channels(), frame.footer().get(DevLayout.RESET),
                    frame.footer().get(DevLayout.SAVE), frame.footer().get(DevLayout.CLOSE));

            for (BookGeometry.Rect band : bands) {
                assertTrue(band.x() >= card.x() && band.right() <= card.right(),
                        () -> "a band left the card" + at + ": " + band + " in " + card);
                assertTrue(band.y() >= card.y() && band.bottom() <= card.bottom(),
                        () -> "a band left the card vertically" + at + ": " + band + " in " + card);
                assertTrue(band.width() >= 0 && band.height() >= 0,
                        () -> "an inverted band" + at + ": " + band);
            }

            assertTrue(frame.title().bottom() <= frame.body().y(),
                    () -> "the list starts under the title" + at);
            assertTrue(frame.body().bottom() <= frame.editing().y(),
                    () -> "the list runs into the editing line" + at);
            assertTrue(frame.editing().bottom() <= frame.channels().y(),
                    () -> "the editing line runs into the channel row" + at);
            assertTrue(frame.channels().bottom() <= frame.footer().get(DevLayout.RESET).y(),
                    () -> "the channel row runs into the footer" + at);

            BookGeometry.Rect reset = frame.footer().get(DevLayout.RESET);
            BookGeometry.Rect save = frame.footer().get(DevLayout.SAVE);
            BookGeometry.Rect close = frame.footer().get(DevLayout.CLOSE);
            assertTrue(reset.right() <= save.x(), () -> "Reset and Save overlap" + at);
            assertTrue(save.right() <= close.x(), () -> "Save and Close overlap" + at);
        }
    }

    @Test
    @DisplayName("the eight channel buttons tile the band they are given")
    void channelsTileTheBand() {
        BookGeometry.Rect band = BookGeometry.Rect.at(40, 100, DevLayout.WIDTH - 24, 20);

        int right = band.x();
        for (int i = 0; i < DevLayout.CHANNELS.size(); i++) {
            final int at = i;
            BookGeometry.Rect button = DevLayout.channel(i, band);
            assertTrue(button.x() >= right, () -> "button " + at + " overlaps the one before it: " + button);
            assertTrue(button.right() <= band.right(), () -> "button " + at + " left the band: " + button);
            assertTrue(button.height() == band.height() && button.y() == band.y(),
                    () -> "button " + at + " left the band vertically: " + button);
            right = button.right();
        }

        assertEquals(DevLayout.CHANNELS.size(), 8, "four channels, two directions each");
        assertEquals(8, DevLayout.CHANNELS.stream().map(DevLayout.Channel::key).distinct().count(),
                "every channel button has its own key, or two of them would be the same control");
        assertEquals(DevLayout.CHANNELS.size(), DevLayout.CHANNELS.stream()
                        .map(channel -> channel.channel() + channel.step()).distinct().count(),
                "and every key names a different (channel, direction) pair");
    }

    @Test
    @DisplayName("the card is the largest modal the window allows, and never bigger")
    void theCardFillsTheModal() {
        for (int[] size : new int[][] {{854, 480}, {427, 240}}) {
            BookGeometry geometry = new BookGeometry(size[0], size[1]);
            DevLayout.Frame frame = DevLayout.frame(size[0], size[1]);
            String at = " at " + size[0] + "x" + size[1];

            assertEquals(geometry.modal().height(), frame.card().height(),
                    () -> "the card is not the modal's full height" + at);
            assertEquals(Math.min(DevLayout.WIDTH, geometry.modal().width()), frame.card().width(),
                    () -> "the card is wider than asked for, or wider than the window allows" + at);
        }
    }
}
