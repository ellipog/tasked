package dev.ellipog.tasked.client.dev;

import dev.ellipog.armature.client.ui.CanvasBackground;
import dev.ellipog.armature.client.ui.ThemeToken;
import dev.ellipog.armature.client.ui.inspect.InspectLayout;
import dev.ellipog.armature.client.ui.inspect.InspectRow;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Stack;
import dev.ellipog.armature.client.ui.kit.Viewport;
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

    private static List<ToolsLayout.Action> open() {
        return ToolsLayout.rows(true, true, true, true);
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

        assertEquals(ThemeToken.ALL.size(), colours,
                "every token once: the colours list plus the canvas section's Ink");
        assertEquals(ThemeToken.Group.values().length, groups, "one label per group");
        // The ink is an inline chip, not a key of its own: that is what makes the picker, the preview and
        // the commit work on it without a second mechanism.
        assertTrue(rows.stream().anyMatch(row -> "canvasPattern".equals(ToolsLayout.tokenId(row.key()))
                        && row.isChip()),
                "the canvasPattern row is the canvas section's Ink, and still a colour row");
    }

    @Test
    @DisplayName("the colour section folds, and a folded one keeps nothing but its heading")
    void sectionsFold() {
        List<ToolsLayout.Action> openRows = open();
        List<ToolsLayout.Action> folded = ToolsLayout.rows(false, true, true, false);

        assertEquals(List.of("canvasPattern"),
                folded.stream().map(row -> ToolsLayout.tokenId(row.key()))
                        .filter(java.util.Objects::nonNull).toList(),
                "a folded colour section has no colours but the canvas section's Ink");
        for (ToolsLayout.Action row : folded) {
            // The switches, the shape's row, the canvas section's own rows, and headings -- and *no*
            // colour-section row, which is the assertion that matters: folding the section puts its
            // forty rows away and nothing else.
            assertTrue(row.key().equals(ToolsLayout.MOTION)
                            || row.key().equals(ToolsLayout.SNAP)
                            || row.key().equals(ToolsLayout.PROGRESS)
                            || row.key().equals(ToolsLayout.RADIUS)
                            || row.key().equals(ToolsLayout.CANVAS_PATTERN)
                            || row.key().equals(ToolsLayout.CANVAS_SPACING)
                            || row.key().equals(ToolsLayout.CANVAS_SPACE)
                            || row.key().equals(ToolsLayout.CANVAS_OPACITY)
                            || row.key().equals(ToolsLayout.CANVAS_COPY)
                            || row.key().equals(ToolsLayout.tokenKey("canvasPattern"))
                            || row.isHeading(),
                    () -> "a folded panel kept a row it should not have: " + row.key());
        }
        assertTrue(open().stream().anyMatch(row -> row.key().equals(ToolsLayout.RADIUS)),
                "the shape's row is in the panel");
        assertTrue(folded.stream().anyMatch(row -> row.key().equals(ToolsLayout.RADIUS)),
                "and folding the colours does not take it away -- it belongs to another section");
        assertTrue(openRows.size() > folded.size(), "unfolding shows more, which is the whole point");
    }

    @Test
    @DisplayName("the sections are marked open or folded, and the switches say what they will do")
    void theRowsSayWhatTheyAre() {
        List<ToolsLayout.Action> rows = open();

        ToolsLayout.Action section = rows.stream()
                .filter(row -> row.key().equals(ToolsLayout.COLOUR_SECTION)).findFirst().orElseThrow();
        assertTrue(section.label().startsWith("\u25bc"), "an open section points down");
        assertTrue(ToolsLayout.rows(true, true, true, false).stream()
                        .filter(row -> row.key().equals(ToolsLayout.COLOUR_SECTION)).findFirst().orElseThrow()
                        .label().startsWith("\u203a"), "a folded one points sideways");

        // The first switch: animation, a reading preference like the rest. Its label is the state, and
        // it is always in the panel.
        ToolsLayout.Action motion = rows.stream()
                .filter(row -> row.key().equals(ToolsLayout.MOTION)).findFirst().orElseThrow();
        assertTrue(motion.hasButton(), "the motion switch is a switch, not a label");
        assertEquals(ToolsLayout.ON, motion.buttonLabel());
        assertEquals(ToolsLayout.OFF, ToolsLayout.rows(false, true, true, true).stream()
                .filter(row -> row.key().equals(ToolsLayout.MOTION)).findFirst().orElseThrow()
                .buttonLabel());

        // The grid an editor's drag lands on, with Alt as the bypass.
        ToolsLayout.Action snap = rows.stream()
                .filter(row -> row.key().equals(ToolsLayout.SNAP)).findFirst().orElseThrow();
        assertTrue(snap.hasButton(), "the snap switch is a switch, not a label");
        assertEquals(ToolsLayout.ON, snap.buttonLabel());
        assertEquals(ToolsLayout.OFF, ToolsLayout.rows(true, false, true, true).stream()
                .filter(row -> row.key().equals(ToolsLayout.SNAP)).findFirst().orElseThrow().buttonLabel());

        // The last: the sidebar's chapter progress bars. A reading preference rather than an edit,
        // and its label is the state like every switch here.
        ToolsLayout.Action progress = rows.stream()
                .filter(row -> row.key().equals(ToolsLayout.PROGRESS)).findFirst().orElseThrow();
        assertTrue(progress.hasButton(), "the bars' switch is a switch, not a label");
        assertEquals(ToolsLayout.ON, progress.buttonLabel());
        assertEquals(ToolsLayout.OFF, ToolsLayout.rows(true, true, false, true).stream()
                .filter(row -> row.key().equals(ToolsLayout.PROGRESS)).findFirst().orElseThrow()
                .buttonLabel());

        List<String> switchOrder = rows.stream()
                .filter(ToolsLayout.Action::hasButton)
                .map(ToolsLayout.Action::key)
                .toList();
        assertEquals(List.of(ToolsLayout.MOTION, ToolsLayout.SNAP, ToolsLayout.PROGRESS),
                switchOrder, "the three switches sit together, in that order");

        assertTrue(rows.stream().noneMatch(row -> ToolsLayout.paletteId(row.key()) != null),
                "this call passes no palette, so it carries no palette rows -- the list is tested "
                        + "where one is offered");
    }

    @Test
    @DisplayName("the palette list is one row per theme, in order, and it folds")
    void thePaletteIsListed() {
        List<ToolsLayout.Palette> palette = List.of(
                new ToolsLayout.Palette("default", "Default"),
                new ToolsLayout.Palette("high_contrast", "High Contrast"),
                new ToolsLayout.Palette("obsidian", "Obsidian"));

        List<ToolsLayout.Action> rows = ToolsLayout.rows(true, true, true, true, palette, true);
        List<ToolsLayout.Action> paletteRows = rows.stream()
                .filter(row -> ToolsLayout.paletteId(row.key()) != null)
                .toList();
        assertEquals(List.of("default", "high_contrast", "obsidian"),
                paletteRows.stream().map(row -> ToolsLayout.paletteId(row.key())).toList(),
                "every palette, in the catalogue's order");
        assertEquals(List.of("Default", "High Contrast", "Obsidian"),
                paletteRows.stream().map(ToolsLayout.Action::label).toList(),
                "with the label the caller gave it -- the id and the display name differ");
        assertTrue(paletteRows.stream().allMatch(ToolsLayout.Action::isControl),
                "a palette row is itself the control, like a colour row");

        ToolsLayout.Action heading = rows.stream()
                .filter(row -> row.key().equals(ToolsLayout.PALETTE_SECTION)).findFirst().orElseThrow();
        assertTrue(heading.label().startsWith("\u25bc"), "an open section points down");

        List<ToolsLayout.Action> folded = ToolsLayout.rows(true, true, true, true, palette, false);
        assertTrue(folded.stream().noneMatch(row -> ToolsLayout.paletteId(row.key()) != null),
                "a folded palette keeps no rows");
        assertTrue(folded.stream().anyMatch(row -> row.key().equals(ToolsLayout.PALETTE_SECTION)),
                "but keeps its heading, which is the way back");
        assertTrue(ToolsLayout.rows(true, true, true, true, List.of(), true).stream()
                        .noneMatch(row -> row.key().equals(ToolsLayout.PALETTE_SECTION)),
                "a caller with no palettes gets no section, not a heading over nothing");

        // Between the switches and Shape: the mode first, then the first decision about the content.
        List<String> order = rows.stream().map(ToolsLayout.Action::key).toList();
        assertTrue(order.indexOf(ToolsLayout.PALETTE_SECTION) > order.indexOf(ToolsLayout.SNAP));
        assertTrue(order.indexOf(ToolsLayout.PALETTE_SECTION) < order.indexOf(ToolsLayout.SHAPE_SECTION));
    }

    @Test
    @DisplayName("the rows are placed in order, do not overlap, and the height counts them all")
    void theListIsPlaced() {
        // With and without a palette offered: the section's rows are ordinary rows and must place like
        // every other kind.
        for (List<ToolsLayout.Palette> offered
                : List.<List<ToolsLayout.Palette>>of(
                        List.of(new ToolsLayout.Palette("tome", "Tome")), List.of())) {
            for (boolean colours : new boolean[] {true, false}) {
                List<ToolsLayout.Action> rows = ToolsLayout.rows(true, true, true, true, offered, true);
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

        for (String key : List.of(ToolsLayout.MOTION, ToolsLayout.SNAP, ToolsLayout.PROGRESS)) {
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
    @DisplayName("the chapter keeps its identity band, the book its actions row, and both keep the list inside")
    void theChapterFrameGivesTheListTheColumn() {
        BookGeometry.Rect rail = BookGeometry.Rect.at(10, 40, 800, 500);
        ToolsLayout.Frame quest = ToolsLayout.frame(rail, ToolsLayout.Tab.CHAPTER);
        ToolsLayout.Frame book = ToolsLayout.frame(rail, ToolsLayout.Tab.BOOK);

        assertEquals(book.panel(), quest.panel(), "one dock, whatever the tab");
        assertEquals(book.title(), quest.title(), "and one title row in it");

        // The one asymmetry left: the chapter tab names the file its rows edit, and the book tab reserves
        // the actions row that writes the player's own theme. Each tab leaves the other's band empty.
        assertTrue(quest.header().height() > 0, "the chapter's identity band is there");
        assertEquals(0, book.header().height(), "the book tab has no chapter to name");
        assertEquals(0, quest.actions().height(), "the chapter tab reserves no actions");
        assertTrue(book.actions().height() > 0, "the book's Revert and Save have their row");

        // The stack, in order, each band at or below the one before it, and every one inside the panel.
        assertTrue(quest.title().bottom() <= quest.feedback().y(), "the title is above the status");
        assertTrue(quest.feedback().bottom() <= quest.header().y(), "the status is above the identity");
        assertTrue(quest.header().bottom() <= quest.list().y(), "the identity is above the list");
        assertTrue(book.list().bottom() <= book.actions().y(), "and the list stops at the actions row");
        for (BookGeometry.Rect band : List.of(quest.title(), quest.feedback(), quest.header(),
                quest.list(), book.list(), book.actions())) {
            assertTrue(band.x() >= quest.panel().x() && band.right() <= quest.panel().right()
                            && band.y() >= quest.panel().y() && band.bottom() <= quest.panel().bottom(),
                    () -> "a band left the panel: " + band + " in " + quest.panel());
        }

        BookGeometry.Rect header = quest.header();
        assertEquals(ToolsLayout.CHAPTER_HEADER_HEIGHT, header.height(),
                "the header band is the height the layout reserves");
        ToolsLayout.ChapterHeader parts = ToolsLayout.chapterHeader(header);
        for (BookGeometry.Rect part : List.of(parts.icon(), parts.title(), parts.subtitle())) {
            assertTrue(part.x() >= header.x() && part.right() <= header.right()
                            && part.y() >= header.y() && part.bottom() <= header.bottom(),
                    () -> "a header part left its band: " + part + " in " + header);
        }
        assertEquals(parts.icon().width(), parts.icon().height(), "the icon's box is square");

        // And the same on a rail too small for the chrome, where the clamp has to keep every band inside
        // the panel rather than letting one go past the floor -- the header's parts clamped too, not
        // merely its band: a degenerate header is zero-sized, not ink outside the panel.
        ToolsLayout.Frame tiny = ToolsLayout.frame(BookGeometry.Rect.at(0, 0, 120, 60),
                ToolsLayout.Tab.CHAPTER);
        assertTrue(tiny.list().y() >= tiny.panel().y() && tiny.list().bottom() <= tiny.panel().bottom(),
                "a tiny rail still keeps the list inside the panel");
        ToolsLayout.ChapterHeader tinyParts = ToolsLayout.chapterHeader(tiny.header());
        for (BookGeometry.Rect part : List.of(tinyParts.icon(), tinyParts.title(), tinyParts.subtitle())) {
            assertTrue(part.x() >= tiny.header().x() && part.right() <= tiny.header().right()
                            && part.y() >= tiny.header().y() && part.bottom() <= tiny.header().bottom(),
                    () -> "a header part left a clamped band: " + part + " in " + tiny.header());
        }
    }

    @Test
    @DisplayName("the drawer's new boxes sit inside their rows: the title menu, a chip, and a pair's halves")
    void theNewControlsSitInsideTheirRows() {
        ToolsLayout.Frame frame = ToolsLayout.frame(BookGeometry.Rect.at(10, 40, 300, 500),
                ToolsLayout.Tab.CHAPTER);
        BookGeometry.Rect title = frame.title();
        BookGeometry.Rect menu = ToolsLayout.titleMenu(title);
        BookGeometry.Rect label = ToolsLayout.titleLabel(title);
        assertTrue(menu.isInside(title), "the panel menu is inside the title band: " + menu);
        assertTrue(label.isInside(title), "the title's own room is inside the band: " + label);
        assertTrue(label.right() <= menu.x(), "and the two do not meet: " + label + " vs " + menu);

        Slot row = new Slot("row", 20, 60, 260, ToolsLayout.ROW_HEIGHT);
        BookGeometry.Rect chip = ToolsLayout.chip(row);
        assertTrue(chip.x() >= row.x() && chip.right() <= row.right(), "the chip is inside its row");
        assertTrue(chip.width() >= 0 && chip.width() <= 132, "the chip is the capped width: " + chip);

        Slot left = ToolsLayout.pairLeft(row);
        Slot right = ToolsLayout.pairRight(row, "second");
        assertTrue(left.x() >= row.x() && left.right() <= right.x(), "the halves are left, then right");
        assertEquals(row.right(), right.right(), "and the right half reaches the row's edge");
        assertEquals("second", right.key(), "under the second action's key");
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
    @DisplayName("the book's rows fold to their heading, and a field keeps its label's room")
    void theBookRowsFold() {
        List<ToolsLayout.Action> folded = ToolsLayout.bookRows(false);
        assertEquals(1, folded.size(), "folded to its heading: " + folded);
        assertEquals(ToolsLayout.BOOK_SECTION, folded.get(0).key());
        assertTrue(folded.get(0).label().startsWith("\u203a"), "a folded section points sideways");

        List<ToolsLayout.Action> open = ToolsLayout.bookRows(true);
        assertEquals(List.of(ToolsLayout.BOOK_SECTION, ToolsLayout.BOOK_TITLE, ToolsLayout.BOOK_ICON),
                open.stream().map(ToolsLayout.Action::key).toList(), "the heading, then the two fields");

        Slot row = new Slot(ToolsLayout.BOOK_TITLE, 10, 20, 200, 16);
        Slot field = ToolsLayout.valueField(row, ToolsLayout.LABEL_ROOM);
        assertEquals(10 + ToolsLayout.LABEL_ROOM, field.x(), "the label's room is kept on the left");
        assertEquals(200 - ToolsLayout.LABEL_ROOM, field.width(), "and the field takes the rest");
    }

    // ------------------------------------------------------------------
    // Folds, help, and the texture row's controls
    // ------------------------------------------------------------------

    /** An image background at a tile size the tile case can recognize. */
    private static CanvasBackground image(CanvasBackground.Fit fit) {
        return new CanvasBackground(CanvasBackground.Kind.IMAGE, CanvasBackground.Space.SCREEN, 24,
                CanvasBackground.Tuning.DEFAULT,
                new CanvasBackground.Image("minecraft:textures/gui/bg.png", fit, 48));
    }

    /** Every row the three entry points build, with everything unfolded and a palette offered. */
    private static List<ToolsLayout.Action> everyRow() {
        List<ToolsLayout.Palette> palette = List.of(new ToolsLayout.Palette("tome", "Tome"));
        CanvasBackground image = image(CanvasBackground.Fit.COVER);
        List<ToolsLayout.Action> rows = new java.util.ArrayList<>();
        rows.addAll(ToolsLayout.rows(true, true, true, true, palette, true, image, true, null));
        rows.addAll(ToolsLayout.chapterAppearanceRows(palette, true, true, true, image, true, null));
        rows.addAll(ToolsLayout.bookRows(true));
        return List.copyOf(rows);
    }

    @Test
    @DisplayName("folds names the five foldable sections, and nothing else")
    void foldsCoversTheFiveSections() {
        // The predicate both the drawing and the widget pass read. It was a hand-written list of three
        // at the widget site once, and the Canvas and Quest Book headings were drawn promising a fold
        // with nothing behind them -- so this is asserted as an exact set, not a "contains".
        Set<String> foldable = everyRow().stream().map(ToolsLayout.Action::key)
                .filter(ToolsLayout::folds).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(ToolsLayout.APPEARANCE_SECTION, ToolsLayout.BOOK_SECTION,
                        ToolsLayout.CANVAS_SECTION, ToolsLayout.COLOUR_SECTION,
                        ToolsLayout.PALETTE_SECTION),
                foldable, "the five sections, exactly -- an extra key would be a heading with a widget "
                        + "the panel does not mark, and a missing one a marker with no widget behind it");
        assertFalse(ToolsLayout.folds(ToolsLayout.SHAPE_SECTION),
                "Shape is a heading but not a fold: it has nothing to put away");
        assertFalse(ToolsLayout.folds(null), "and no key at all folds");
    }

    @Test
    @DisplayName("every row that is not a colour or a group name offers its help")
    void everyRowHasHelp() {
        // The table is a property of the key, so "every control has help" is a thing a test asserts
        // rather than a sentence a reader hopes. Colour rows and group names are deliberately absent:
        // a swatch shows the colour and the preview rings where it paints, so a sentence would be a
        // third description -- see `ToolsLayout.help`.
        for (ToolsLayout.Action row : everyRow()) {
            if (ToolsLayout.tokenId(row.key()) != null || row.key().startsWith("group:")) {
                continue;
            }
            assertNotNull(ToolsLayout.help(row.key()), () -> "no help for row " + row.key());
        }
    }

    @Test
    @DisplayName("helpAt names the row under a point, and answers nothing outside one")
    void helpAtFindsTheRowUnderThePointer() {
        List<ToolsLayout.Action> rows = everyRow();
        Layout layout = ToolsLayout.build(rows, 288, MEASURE);
        Viewport view = Viewport.fixed().bounds(40, 60, 288, 400);

        Slot radius = ToolsLayout.onScreen(view, layout.slot(ToolsLayout.RADIUS));
        ToolsLayout.Hovered hit = ToolsLayout.helpAt(rows, layout, view, radius.x() + 1, radius.y() + 1);
        assertNotNull(hit, "the radius row is under the pointer");
        assertEquals(ToolsLayout.RADIUS, hit.key(), "and the key travels with the sentence");
        assertEquals(ToolsLayout.help(ToolsLayout.RADIUS), hit.help());

        // A colour row is a real row with no help: the answer is nothing, not the row beside it.
        Slot colour = ToolsLayout.onScreen(view, layout.slot(ToolsLayout.tokenKey("panel")));
        assertNull(ToolsLayout.helpAt(rows, layout, view, colour.x() + 1, colour.y() + 1),
                "a colour row offers no help, and no other row answers for it");
        // And outside the list there is no row at all, in either direction.
        assertNull(ToolsLayout.helpAt(rows, layout, view, view.originX() - 1, radius.y() + 1),
                "left of the list is no row");
        assertNull(ToolsLayout.helpAt(rows, layout, view, radius.x() + 1, view.originY() - 1),
                "and above it neither");
    }


    @Test
    @DisplayName("the texture row is a picture, then a field, then a browse button")
    void theTextureRowHasThreeBoxes() {
        Slot row = new Slot(ToolsLayout.CANVAS_TEXTURE, 10, 20, 288, ToolsLayout.ROW_HEIGHT);
        Slot thumb = ToolsLayout.textureThumb(row);
        Slot field = ToolsLayout.textureField(row);
        Slot browse = ToolsLayout.textureBrowse(row);

        assertEquals(row.key(), field.key(), "the field is the row's own widget, under the row's key");
        assertEquals(ToolsLayout.TEXTURE_THUMB, thumb.width(), "the picture's box is its own size");
        assertEquals(thumb.width(), thumb.height(), "and square");
        assertTrue(thumb.right() <= field.x(), () -> "the picture overlaps the field: " + thumb + field);
        assertTrue(field.right() <= browse.x(), () -> "the field runs under the button: " + field + browse);
        assertEquals(ToolsLayout.TEXTURE_BROWSE, browse.width(), "the button matches a stepper's arrow");
        assertTrue(browse.right() <= row.right() - ToolsLayout.STRIP_INSET,
                "and stays inside the row's own inset");

        // The hit test is the drawing's own derivation, so the button that is seen is the one pressed.
        Viewport view = Viewport.fixed().bounds(40, 60, 288, 120);
        int midY = 60 + row.y() + row.height() / 2;
        assertTrue(ToolsLayout.textureBrowseAt(view, row, 40 + browse.x() + browse.width() / 2.0, midY),
                "the browse button answers where it is drawn");
        assertFalse(ToolsLayout.textureBrowseAt(view, row, 40 + thumb.x() + thumb.width() / 2.0, midY),
                "the picture is not a button");
        assertFalse(ToolsLayout.textureBrowseAt(view, row, 40 + field.x() + field.width() / 2.0, midY),
                "and neither is the field");
        assertFalse(ToolsLayout.textureBrowseAt(view, null, midY, midY),
                "a tab without the row has no button to hit");
    }

    private static boolean inside(BookGeometry.Rect inner, BookGeometry.Rect outer) {
        return inner.x() >= outer.x() && inner.y() >= outer.y()
                && inner.right() <= outer.right() && inner.bottom() <= outer.bottom();
    }

    @Test
    @DisplayName("a colour's channel value comes out of the packed colour it is drawn from")
    void channelValues() {
        int argb = 0x80402010;
        assertEquals(0x80, ToolsPanel.channelValue(argb, "A"));
        assertEquals(0x40, ToolsPanel.channelValue(argb, "R"));
        assertEquals(0x20, ToolsPanel.channelValue(argb, "G"));
        assertEquals(0x10, ToolsPanel.channelValue(argb, "B"));
        assertFalse(ToolsLayout.rows(true, true, true, true).isEmpty(),
                "the switches are always there, whatever else is folded");
    }

    // ------------------------------------------------------------------
    // Where the radius row is pressed
    // ------------------------------------------------------------------

    /**
     * The list's coordinates to the screen's, checked against the viewport's own documented meaning rather
     * than against the method itself.
     *
     * <p>This is the fault that made the arrows invisible: a row's slot is in the <i>list's</i> space, and a
     * caller that used it as a screen rectangle placed the controls outside the panel. So the expectation
     * below is written out by hand — the view's left and top, plus the content position, minus the scroll —
     * and never obtained by calling the thing under test, which would agree with a mapping that put the row
     * anywhere at all.
     */
    @Test
    @DisplayName("a row's slot is put where the viewport says it is, scroll and all")
    void rowsMapThroughTheViewport() {
        Layout layout = ToolsLayout.build(open(), 200, MEASURE);
        Slot row = layout.slot(ToolsLayout.RADIUS);

        Viewport view = Viewport.fixed().bounds(40, 60, 200, 120);
        Slot mapped = ToolsLayout.onScreen(view, row);
        assertEquals(40 + row.x(), mapped.x());
        assertEquals(60 + row.y(), mapped.y());
        assertEquals(row.width(), mapped.width());
        assertEquals(row.height(), mapped.height());

        // And it follows the list: the same row, scrolled up by thirty, is thirty pixels higher.
        view.setOffset(0, -30);
        Slot scrolled = ToolsLayout.onScreen(view, row);
        assertEquals(40 + row.x(), scrolled.x());
        assertEquals(60 + row.y() - 30, scrolled.y());
    }
}
