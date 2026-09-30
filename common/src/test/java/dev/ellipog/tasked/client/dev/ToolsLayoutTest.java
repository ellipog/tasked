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
 * The tools panel's composition: its bands, its sections, and the rows inside them.
 *
 * <h2>What is asked here, and what cannot be</h2>
 *
 * <p>The panel is a floating column over a canvas that can be any size, so the questions that matter are
 * arithmetic: are its bands inside it at every window, do the sections fold, does every colour the theme
 * registry knows appear exactly once, and do the band's eight channel buttons tile the row they are given.
 * All of it is answerable without a client, which is why the composition is a game-free class -- the
 * screen that draws it is the part no test can instantiate.
 */
@DisplayName("The tools panel's layout")
class ToolsLayoutTest {

    private static final Measure MEASURE = Measure.monospace(6, 9);

    private static final List<String> THEMES = List.of("Modern", "Tome", "High contrast");

    private static List<ToolsLayout.Action> open() {
        return ToolsLayout.rows(true, false, THEMES, "Modern", true, true);
    }

    // ------------------------------------------------------------------
    // The rows and the sections
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every colour the registry knows is in the panel, once, under its group's name")
    void everyColourIsListed() {
        List<ToolsLayout.Action> rows = open();

        Set<String> keys = new HashSet<>();
        int colours = 0;
        int groups = 0;
        for (ToolsLayout.Action row : rows) {
            assertTrue(keys.add(row.key()), () -> "a duplicate key: " + row.key());
            if (ToolsLayout.tokenId(row.key()) != null) {
                assertNotNull(ThemeToken.byId(ToolsLayout.tokenId(row.key())),
                        () -> "a colour row for an unknown token: " + row.key());
                colours++;
            }
            if (row.key().startsWith("group:")) {
                groups++;
                assertTrue(row.isHeading(),
                        "a group's name is drawn and does nothing -- a row that selects nothing lies");
            }
        }

        assertEquals(ThemeToken.ALL.size(), colours, "every token, once");
        assertEquals(ThemeToken.Group.values().length, groups, "one label per group");
    }

    @Test
    @DisplayName("both sections fold, and a folded one keeps nothing but its heading")
    void sectionsFold() {
        List<ToolsLayout.Action> openRows = open();
        List<ToolsLayout.Action> folded = ToolsLayout.rows(true, false, THEMES, "Modern", false, false);

        assertEquals(THEMES.size(),
                openRows.stream().filter(row -> ToolsLayout.themeName(row.key()) != null).count());
        assertEquals(0, folded.stream().filter(row -> ToolsLayout.themeName(row.key()) != null).count(),
                "a folded theme section has no themes in it");
        assertEquals(0, folded.stream().filter(row -> ToolsLayout.tokenId(row.key()) != null).count(),
                "and a folded colour section has no colours");

        for (ToolsLayout.Action row : folded) {
            assertTrue(row.key().equals(ToolsLayout.EDIT) || row.key().equals(ToolsLayout.MOTION)
                            || row.isHeading(),
                    () -> "a folded panel kept a row it should not have: " + row.key());
        }
        assertTrue(openRows.size() > folded.size(), "unfolding shows more, which is the whole point");
    }

    @Test
    @DisplayName("the sections are marked open or folded, and the switches say what they will do")
    void theRowsSayWhatTheyAre() {
        List<ToolsLayout.Action> rows = open();

        ToolsLayout.Action themeSection = rows.stream()
                .filter(row -> row.key().equals(ToolsLayout.THEME_SECTION)).findFirst().orElseThrow();
        assertTrue(themeSection.label().startsWith("\u25be"), "an open section points down");
        assertTrue(ToolsLayout.rows(true, false, THEMES, "Modern", false, true).stream()
                        .filter(row -> row.key().equals(ToolsLayout.THEME_SECTION)).findFirst().orElseThrow()
                        .label().startsWith("\u25b8"), "a folded one points sideways");

        ToolsLayout.Action edit = rows.stream()
                .filter(row -> row.key().equals(ToolsLayout.EDIT)).findFirst().orElseThrow();
        assertTrue(edit.hasButton());
        assertEquals("On", edit.buttonLabel(), "the button says what pressing it will do");
        assertEquals("Off", ToolsLayout.rows(false, false, THEMES, "Modern", true, true).stream()
                .filter(row -> row.key().equals(ToolsLayout.EDIT)).findFirst().orElseThrow().buttonLabel());

        ToolsLayout.Action theme = rows.stream()
                .filter(row -> row.key().equals(ToolsLayout.themeKey("Modern"))).findFirst().orElseThrow();
        assertTrue(theme.isControl(), "a theme row is the control");
        assertTrue(theme.label().contains("\u2713"), "and the one in use is marked");
    }

    @Test
    @DisplayName("the rows are placed in order, do not overlap, and the height counts them all")
    void theListIsPlaced() {
        for (boolean themes : new boolean[] {true, false}) {
            for (boolean colours : new boolean[] {true, false}) {
                List<ToolsLayout.Action> rows = ToolsLayout.rows(true, true, THEMES, "Tome", themes, colours);
                Layout layout = ToolsLayout.build(rows, 288, MEASURE);

                List<Slot> slots = layout.slots();
                assertEquals(rows.size(), slots.size(), "one slot per row");
                int lowest = 0;
                for (int i = 0; i < slots.size(); i++) {
                    final int at = i;
                    Slot slot = slots.get(at);
                    assertTrue(slot.width() >= 0 && slot.height() > 0, () -> "empty row: " + slot);
                    assertTrue(slots.get(at).y() >= (at == 0 ? 0 : slots.get(at - 1).bottom()),
                            () -> "row " + at + " overlaps the one before it");
                    lowest = Math.max(lowest, slot.bottom());
                }
                assertEquals(lowest, layout.height(), "the height is the bottom of the last row placed");
            }
        }
    }

    @Test
    @DisplayName("a switch's button sits in the gap its row reserved, against the column's edge")
    void switchButtonsSitInTheirRows() {
        Layout layout = ToolsLayout.build(open(), 288, MEASURE);

        for (String key : List.of(ToolsLayout.EDIT, ToolsLayout.MOTION)) {
            Slot row = layout.slot(key);
            assertNotNull(row);
            Slot strip = ToolsLayout.strip(row);

            assertTrue(strip.x() >= row.right(),
                    () -> "the button overlaps the row's own label: " + strip);
            assertTrue(strip.right() <= 288 - ToolsLayout.STRIP_INSET,
                    () -> "the button left the column: " + strip);
            assertTrue(strip.y() >= row.y() && strip.bottom() <= row.bottom(),
                    () -> "the button left its row vertically: " + strip);
        }
    }

    // ------------------------------------------------------------------
    // The panel
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the bands are inside the panel and never overlap, at any window")
    void theBandsStack() {
        for (int[] canvas : new int[][] {{600, 260}, {200, 120}, {1200, 700}, {80, 60}}) {
            BookGeometry.Rect area = BookGeometry.Rect.at(10, 40, canvas[0], canvas[1]);
            ToolsLayout.Frame frame = ToolsLayout.frame(area);
            String at = " at a " + canvas[0] + "x" + canvas[1] + " canvas";

            BookGeometry.Rect panel = frame.panel();
            List<BookGeometry.Rect> bands = List.of(frame.title(), frame.feedback(), frame.preview(),
                    frame.list(), frame.swatch(), frame.channels(), frame.actions(),
                    ToolsLayout.revert(frame.actions()), ToolsLayout.save(frame.actions()));

            for (BookGeometry.Rect band : bands) {
                assertTrue(band.x() >= panel.x() && band.right() <= panel.right(),
                        () -> "a band left the panel" + at + ": " + band + " in " + panel);
                assertTrue(band.y() >= panel.y() && band.bottom() <= panel.bottom(),
                        () -> "a band left the panel vertically" + at + ": " + band);
                assertTrue(band.width() >= 0 && band.height() >= 0, () -> "an inverted band" + at);
            }

            assertTrue(frame.feedback().bottom() <= frame.preview().y() + 1, () -> "title band" + at);
            assertTrue(frame.preview().bottom() <= frame.list().y(), () -> "the preview into the list" + at);
            assertTrue(frame.list().bottom() <= frame.swatch().y(), () -> "the list into the band" + at);
            assertTrue(frame.swatch().bottom() <= frame.channels().y(), () -> "the swatch row" + at);
            assertTrue(frame.channels().bottom() <= frame.actions().y(), () -> "the channels" + at);

            BookGeometry.Rect revert = ToolsLayout.revert(frame.actions());
            BookGeometry.Rect save = ToolsLayout.save(frame.actions());
            assertTrue(revert.right() <= save.x(), () -> "Revert and Save overlap" + at);
            assertTrue(save.right() <= frame.actions().right(), () -> "Save left its row" + at);
        }
    }

    @Test
    @DisplayName("the panel floats inside the canvas it is docked to, and leaves it most of its width")
    void thePanelIsInsideTheCanvas() {
        BookGeometry.Rect canvas = BookGeometry.Rect.at(150, 30, 800, 500);
        ToolsLayout.Frame frame = ToolsLayout.frame(canvas);

        assertTrue(frame.panel().right() <= canvas.right() && frame.panel().x() >= canvas.x(),
                () -> "the panel is not inside the canvas: " + frame.panel() + " in " + canvas);
        assertTrue(frame.panel().width() <= ToolsLayout.WIDTH, "never wider than it was designed");
        assertTrue(frame.panel().width() >= ToolsLayout.MIN_WIDTH, "and never uselessly narrow");
        assertTrue(canvas.width() - frame.panel().width() >= 100,
                "the panel leaves room to see the graph beside it, which is why it is a panel");

        // A canvas too small for the panel: it clamps rather than disappearing.
        ToolsLayout.Frame tiny = ToolsLayout.frame(BookGeometry.Rect.at(0, 0, 120, 60));
        assertTrue(tiny.panel().width() >= 0 && tiny.panel().height() >= 0, "a tiny canvas is not inverted");
        assertTrue(tiny.panel().x() >= 0, "and the panel is not placed off the left edge");
    }

    @Test
    @DisplayName("the eight channel buttons tile their lines, with a value between each pair")
    void theChannelsTileTheirBand() {
        BookGeometry.Rect band = BookGeometry.Rect.at(20, 100, 288, ToolsLayout.CHANNEL_ROW * 2 + 1);
        var beats = ToolsLayout.beats(band);

        for (String channel : ToolsLayout.CHANNELS) {
            Slot down = beats.get("down:" + channel);
            Slot value = beats.get("beat:" + channel);
            Slot up = beats.get("up:" + channel);
            assertNotNull(down, () -> "no down button for " + channel);
            assertNotNull(up, () -> "no value slot for " + channel);
            assertTrue(down.right() <= value.x(), () -> "the down button overlaps the value: " + channel);
            assertTrue(value.right() <= up.x(), () -> "the value overlaps the up button: " + channel);
            assertTrue(up.right() <= band.right(), () -> "the row left its band: " + channel);
            assertTrue(value.width() > 0, () -> channel + " has nowhere to draw its number");
        }
        assertEquals(12, beats.size(), "four channels, three slots each");
        assertTrue(beats.get("down:R").y() < beats.get("down:B").y(),
                "the first two channels are on the first line and the last two on the second");
        assertNull(ToolsLayout.tokenId("theme:Modern"), "a theme key is not a colour key");
        assertEquals("panel", ToolsLayout.tokenId(ToolsLayout.tokenKey("panel")));
    }

    @Test
    @DisplayName("a colour's channel value comes out of the packed colour it is drawn from")
    void channelValues() {
        int argb = 0x80402010;
        assertEquals(0x80, ToolsPanel.channelValue(argb, "A"));
        assertEquals(0x40, ToolsPanel.channelValue(argb, "R"));
        assertEquals(0x20, ToolsPanel.channelValue(argb, "G"));
        assertEquals(0x10, ToolsPanel.channelValue(argb, "B"));
        assertFalse(ToolsLayout.rows(true, true, List.of(), "", true, true).isEmpty(),
                "a client with no themes still gets its switches");
    }
}
