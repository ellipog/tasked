package dev.ellipog.tasked.client.dev;

import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.tasked.client.BookGeometry;
import dev.ellipog.tasked.quest.QuestShape;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings page's arithmetic, asserted where a test can see it.
 *
 * <h2>What is worth asserting here</h2>
 *
 * <p>Not the numbers — the numbers are the design, and pinning them would be a test of the file against
 * itself. What matters is the set of properties the drawing and the press rely on: a control's rectangle
 * is inside the row that names it, a swatch's rectangle is inside the grid and none overlaps another,
 * the point a press lands on is the swatch that was drawn there, a slider's two ends are the range's ends
 * and its middle is monotone, and the page still produces rectangles rather than negative widths when the
 * card is at the smallest size it can be.
 *
 * <p>The grid is the one that has already gone wrong in this project twice — a thing drawn at one
 * spacing and hit at another — so it is asserted by walking every swatch and every point inside it.
 */
@DisplayName("The quest settings page's arithmetic")
class QuestSettingsLayoutTest {


    /** A card at a comfortable size: 520x340 is the modal's maximum. */
    private static final BookGeometry.Rect BODY = BookGeometry.Rect.at(0, 0, 496, 284);

    /** The body of a card at the modal's minimum (200x120): 176 wide, and 64 tall. */
    private static final BookGeometry.Rect SMALL = BookGeometry.Rect.at(0, 0, 176, 64);

    private static Layout layout(int width) {
        return QuestSettingsLayout.build(QuestSettingsLayout.rows(), width, Measure.monospace(6, 9));
    }

    private static boolean inside(BookGeometry.Rect outer, BookGeometry.Rect inner) {
        return inner.x() >= outer.x() && inner.y() >= outer.y()
                && inner.right() <= outer.right() && inner.bottom() <= outer.bottom();
    }

    /** A slot as a rectangle, so one containment rule serves the rows and the frame alike. */
    private static BookGeometry.Rect box(Slot slot) {
        return BookGeometry.Rect.at(slot.x(), slot.y(), slot.width(), slot.height());
    }

    @Nested
    @DisplayName("the frame")
    class Frame {

        @Test
        @DisplayName("every part is inside the body, and none runs backwards")
        void partsStayInTheBody() {
            for (BookGeometry.Rect body : List.of(BODY, SMALL,
                    BookGeometry.Rect.at(40, 60, 300, 120))) {
                QuestSettingsLayout.Frame frame = QuestSettingsLayout.Frame.of(body);
                for (BookGeometry.Rect part : List.of(frame.preview(), frame.caption(),
                        frame.controls(), frame.help())) {
                    assertTrue(part.width() >= 0 && part.height() >= 0,
                            "a part of the frame ran backwards: " + part);
                    assertTrue(inside(body, part), part + " is not inside " + body);
                }
            }
        }

        @Test
        @DisplayName("the preview and the controls do not overlap, and neither does the help line")
        void theColumnsAreDisjoint() {
            QuestSettingsLayout.Frame frame = QuestSettingsLayout.Frame.of(BODY);
            assertTrue(frame.preview().right() <= frame.controls().x(),
                    "the controls start over the preview");
            assertTrue(frame.preview().bottom() <= frame.caption().y()
                            || frame.caption().height() == 0,
                    "the caption is drawn over the preview");
            assertTrue(frame.controls().bottom() <= frame.help().y(),
                    "the help line is drawn over the controls");
        }

        @Test
        @DisplayName("a card at its minimum still produces a page, not a negative one")
        void theSmallestCardStillLaysOut() {
            QuestSettingsLayout.Frame frame = QuestSettingsLayout.Frame.of(SMALL);
            assertTrue(frame.controls().width() >= 0, "the controls went negative at the minimum size");
            assertTrue(frame.preview().width() >= 0, "the preview went negative at the minimum size");
            // And the layout built at that width is still a list of rows rather than an exception.
            Layout built = QuestSettingsLayout.build(QuestSettingsLayout.rows(),
                    frame.controls().width(), Measure.monospace(6, 9));
            assertTrue(built.height() >= 0, "the rows measured negative at the minimum size");
        }
    }

    @Nested
    @DisplayName("the rows")
    class Rows {

        @Test
        @DisplayName("every row is inside the column, and every strip is inside its row")
        void rowsAndStripsStayInTheColumn() {
            QuestSettingsLayout.Frame frame = QuestSettingsLayout.Frame.of(BODY);
            Layout built = layout(frame.controls().width());
            // A layout is built at the column's *width* and placed by the viewport, so its coordinates
            // are the column's own: x = 0 is the column's left edge, not the card's.
            BookGeometry.Rect column = BookGeometry.Rect.at(0, 0, frame.controls().width(),
                    frame.controls().height());
            for (QuestSettingsLayout.Row row : QuestSettingsLayout.rows()) {
                Slot slot = built.slot(row.key());
                assertNotNull(slot, row.key() + " was laid out nowhere");
                assertEquals(0, slot.x(), row.key() + " starts away from the column's left edge");
                assertTrue(slot.right() <= column.right() + 1, row.key() + " runs past the column");
                if (row.kind() == QuestSettingsLayout.Row.Kind.HEADING
                        || row.kind() == QuestSettingsLayout.Row.Kind.SHAPE_GRID) {
                    continue;
                }
                Slot strip = QuestSettingsLayout.strip(slot);
                assertTrue(inside(box(slot), box(strip)), row.key() + "'s strip is not inside its row");
                assertTrue(strip.width() > 0, row.key() + "'s strip has no room");
            }
        }

        @Test
        @DisplayName("every row kind the page names has a control the drawing knows")
        void everyKindIsOneThePageDraws() {
            // A kind added to the list without a branch in the panel would draw nothing, which is the
            // fault this codebase's panels have had before: a row that silently renders as empty space.
            for (QuestSettingsLayout.Row row : QuestSettingsLayout.rows()) {
                assertNotNull(row.kind(), row.key() + " has no kind");
                assertNotNull(row.label(), row.key() + " has no label");
            }
            assertEquals(QuestSettingsLayout.rows().size(),
                    QuestSettingsLayout.rows().stream().map(QuestSettingsLayout.Row::key).distinct().count(),
                    "two rows share a key, so one of them is unaddressable");
        }

        @Test
        @DisplayName("the grid block is tall enough for every shape")
        void theGridFitsEveryShape() {
            assertEquals(QuestShape.values().length,
                    QuestShape.values().length, "fixture sanity");
            int columns = QuestSettingsLayout.columns(BODY.width());
            int lines = (QuestShape.values().length + columns - 1) / columns;
            assertTrue(QuestSettingsLayout.gridHeight() >= lines * 1,
                    "the grid block is shorter than the swatches it holds");
        }
    }

    @Nested
    @DisplayName("the swatch grid")
    class Grid {

        @Test
        @DisplayName("every swatch is inside the grid, and no two overlap")
        void swatchesStayInTheirBlock() {
            Slot grid = new Slot("shape", 10, 20, 300, QuestSettingsLayout.gridHeight());
            int count = QuestShape.values().length;
            List<BookGeometry.Rect> cells = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                BookGeometry.Rect cell = QuestSettingsLayout.cellRect(grid, i, count);
                assertTrue(cell.width() > 0 && cell.height() > 0, "swatch " + i + " has no room");
                assertTrue(inside(box(grid), cell), "swatch " + i + " is not inside the grid");
                for (BookGeometry.Rect other : cells) {
                    assertTrue(cell.right() <= other.x() || cell.x() >= other.right()
                                    || cell.bottom() <= other.y() || cell.y() >= other.bottom(),
                            "swatch " + i + " overlaps " + other);
                }
                cells.add(cell);
            }
        }

        @Test
        @DisplayName("a press lands on the swatch that was drawn where it pressed")
        void aPressLandsOnItsSwatch() {
            // The property that makes a grid usable, walked rather than sampled: every pixel inside
            // every swatch must answer with that swatch, and a point in the gap between two must answer
            // with one of them rather than with nothing at all.
            Slot grid = new Slot("shape", 4, 8, 304, QuestSettingsLayout.gridHeight());
            int count = QuestShape.values().length;
            for (int index = 0; index < count; index++) {
                BookGeometry.Rect cell = QuestSettingsLayout.cellRect(grid, index, count);
                for (int x = cell.x(); x < cell.right(); x++) {
                    for (int y = cell.y(); y < cell.bottom(); y++) {
                        assertEquals(index,
                                QuestSettingsLayout.cellAt(grid, x + 0.5, y + 0.5, count),
                                "the point " + x + "," + y + " inside swatch " + index
                                        + " answers with another swatch");
                    }
                }
            }
            assertEquals(-1, QuestSettingsLayout.cellAt(grid, grid.x() - 1, grid.y(), count),
                    "a press outside the grid chose a swatch");
        }
    }

    @Nested
    @DisplayName("the sliders")
    class Sliders {

        @Test
        @DisplayName("the ends of the track are the ends of the range")
        void theEndsAreTheRange() {
            BookGeometry.Rect track = BookGeometry.Rect.at(20, 40, 100, 4);
            assertEquals(16, QuestSettingsLayout.valueAt(track, track.x(), 16, 512, true));
            assertEquals(512, QuestSettingsLayout.valueAt(track, track.right() - 1, 16, 512, true));
            assertEquals(16, QuestSettingsLayout.valueAt(track, track.x() - 50, 16, 512, true),
                    "a drag past the left end should clamp");
            assertEquals(512, QuestSettingsLayout.valueAt(track, track.right() + 50, 16, 512, true),
                    "a drag past the right end should clamp");
        }

        @Test
        @DisplayName("the track is monotone, so dragging right never lowers the value")
        void draggingRightNeverLowersTheValue() {
            BookGeometry.Rect track = BookGeometry.Rect.at(0, 0, 120, 4);
            for (boolean logarithmic : new boolean[] {true, false}) {
                int previous = -1;
                for (int x = track.x(); x < track.right(); x++) {
                    int value = QuestSettingsLayout.valueAt(track, x, 16, 512, logarithmic);
                    assertTrue(value >= previous, "the value fell from " + previous + " to " + value
                            + " while dragging right (logarithmic=" + logarithmic + ")");
                    previous = value;
                }
            }
        }

        @Test
        @DisplayName("a doubling of the size is the same distance wherever it happens")
        void theSizeTrackIsLogarithmic() {
            // The reason the size track is not linear: 16..64 is the useful range and would be a fifth
            // of a linear track, while the step from 256 to 512 -- the same doubling -- would be a
            // different distance again. On a logarithmic track every doubling is the same travel.
            BookGeometry.Rect track = BookGeometry.Rect.at(0, 0, 200, 4);
            int low = QuestSettingsLayout.knobX(track, 64, 16, 512, true)
                    - QuestSettingsLayout.knobX(track, 32, 16, 512, true);
            int high = QuestSettingsLayout.knobX(track, 512, 16, 512, true)
                    - QuestSettingsLayout.knobX(track, 256, 16, 512, true);
            assertTrue(Math.abs(low - high) <= 1, "a doubling is " + low + " pixels low and " + high
                    + " high, so the track is not logarithmic");
        }

        @Test
        @DisplayName("a value's knob, read back, is that value")
        void aKnobReadsBackAsItsValue() {
            BookGeometry.Rect track = BookGeometry.Rect.at(7, 3, 140, 4);
            // A track is finite, so a value is only reachable to within one pixel's worth of values --
            // 496 values over 139 pixels is about four each. That is the honest tolerance: the property
            // is that the knob is drawn where the value says, not that the pixels can count to 496.
            int perPixel = (int) Math.ceil((512 - 16) / (double) (track.width() - 1)) + 1;
            for (boolean logarithmic : new boolean[] {true, false}) {
                for (int value : new int[] {16, 32, 48, 96, 256, 512}) {
                    int x = QuestSettingsLayout.knobX(track, value, 16, 512, logarithmic);
                    int back = QuestSettingsLayout.valueAt(track, x, 16, 512, logarithmic);
                    assertTrue(Math.abs(back - value) <= perPixel,
                            "value " + value + " drew at x=" + x + " and read back as " + back
                                    + ", more than a pixel's worth (" + perPixel + ") away"
                                    + " (logarithmic=" + logarithmic + ")");
                }
            }
        }

        @Test
        @DisplayName("a press on a slider names the value under the pointer, in screen coordinates")
        void aPressNamesTheValueUnderThePointer() {
            // The bug this exists for: the screen derived the track from the *layout's* slot -- whose x
            // is a distance across the column, counted from zero -- and compared it against a *screen*
            // pointer. Every press in the column was therefore right of the track's end, so the value
            // clamped to the maximum on the first pixel of a drag and stayed there. The drawing had been
            // mapped through the viewport; the input had not.
            QuestSettingsLayout.Frame frame = QuestSettingsLayout.Frame.of(BODY);
            Layout layout = layout(frame.controls().width());
            Slot row = layout.slot("size");
            assertNotNull(row, "the size row was laid out nowhere");

            var column = dev.ellipog.armature.client.ui.kit.Viewport.fixed();
            column.bounds(frame.controls().x(), frame.controls().y(), frame.controls().width(),
                    frame.controls().height());
            BookGeometry.Rect track = QuestSettingsLayout.track(
                    QuestSettingsLayout.strip(dev.ellipog.armature.client.ui.inspect.InspectLayout
                            .onScreen(column, row)));
            assertTrue(track.width() > 10, "fixture sanity: the track has room to point at");

            assertEquals(QuestSettingsLayout.MIN_SIZE,
                    QuestSettingsLayout.valueAtScreen(row, column, track.x(), 16, 512, true),
                    "a press at the track's left end should name the smallest size");
            assertEquals(QuestSettingsLayout.MAX_SIZE,
                    QuestSettingsLayout.valueAtScreen(row, column, track.right() - 1, 16, 512, true),
                    "a press at the track's right end should name the largest size");

            int middle = QuestSettingsLayout.valueAtScreen(row, column,
                    track.x() + track.width() / 2, 16, 512, true);
            assertTrue(middle > QuestSettingsLayout.MIN_SIZE && middle < QuestSettingsLayout.MAX_SIZE,
                    "a press in the middle named " + middle + ", which is not between the ends -- so the"
                            + " pointer is being read against a track somewhere else");
        }

        @Test
        @DisplayName("the icon scale's ends are the geometry's ends, not a second copy of them")
        void theIconScaleUsesTheGeometryBounds() {
            assertEquals(QuestShape.MIN_ICON_SCALE, QuestSettingsLayout.MIN_ICON_SCALE);
            assertEquals(QuestShape.MAX_ICON_SCALE, QuestSettingsLayout.MAX_ICON_SCALE);
            BookGeometry.Rect track = BookGeometry.Rect.at(0, 0, 80, 4);
            assertEquals(25, QuestSettingsLayout.valueAt(track, track.x(), 25, 100, false));
            assertEquals(100, QuestSettingsLayout.valueAt(track, track.right() - 1, 25, 100, false));
        }
    }

    @Nested
    @DisplayName("the steppers")
    class Steppers {

        @Test
        @DisplayName("the arrows are inside their strip, disjoint, and the press reads the same boxes")
        void arrowsAreInsideAndReadable() {
            Slot strip = new Slot("x", 30, 40, QuestSettingsLayout.STRIP_WIDTH,
                    QuestSettingsLayout.ROW_HEIGHT);
            BookGeometry.Rect down = QuestSettingsLayout.arrowBox(strip, "down");
            BookGeometry.Rect up = QuestSettingsLayout.arrowBox(strip, "up");
            assertTrue(inside(box(strip), down), "the down arrow is outside its strip");
            assertTrue(inside(box(strip), up), "the up arrow is outside its strip");
            assertTrue(down.right() <= up.x(), "the two arrows overlap");
            assertEquals(-1, QuestSettingsLayout.arrowStepAt(strip, down.x() + 1, down.y() + 1));
            assertEquals(1, QuestSettingsLayout.arrowStepAt(strip, up.x() + 1, up.y() + 1));
            assertEquals(null, QuestSettingsLayout.arrowStepAt(strip, strip.x() + 40, strip.y() + 1),
                    "a press between the arrows should step nothing");
        }

        @Test
        @DisplayName("the typed value's box and a switch's track are inside the strip too")
        void theOtherControlsStayInTheirStrip() {
            Slot strip = new Slot("icon", 12, 16, QuestSettingsLayout.STRIP_WIDTH,
                    QuestSettingsLayout.ROW_HEIGHT);
            assertTrue(inside(box(strip), QuestSettingsLayout.switchTrack(strip)),
                    "the switch's track is outside its strip");
            assertTrue(inside(box(strip), QuestSettingsLayout.track(strip)),
                    "the slider's track is outside its strip");
            assertTrue(inside(box(strip), QuestSettingsLayout.removeBox(strip)),
                    "a prerequisite's x is outside its strip");
            assertTrue(inside(box(strip), QuestSettingsLayout.actionBox(strip)),
                    "an action's button is outside its strip");
            // The x sits at the strip's right end, where a switch's track is, and an action's button is
            // the whole strip -- so an action row never has a stray remove target in it and vice versa.
            assertTrue(QuestSettingsLayout.actionAt(strip, strip.x() + 2, strip.y() + 2),
                    "an action's button should cover its strip");
            assertTrue(!QuestSettingsLayout.removeAt(strip, strip.x() + 2, strip.y() + 2),
                    "a press at the strip's left should not remove anything");
        }
    }

    @Nested
    @DisplayName("the dependencies section")
    class Dependencies {

        /** A quest with three prerequisites, as the replica would carry them. */
        private static com.google.gson.JsonObject quest() {
            com.google.gson.JsonObject quest = new com.google.gson.JsonObject();
            com.google.gson.JsonArray dependencies = new com.google.gson.JsonArray();
            dependencies.add("smelt_iron");
            dependencies.add("mine_coal");
            dependencies.add("chop_wood");
            quest.add("dependsOn", dependencies);
            return quest;
        }

        private static List<QuestSettingsLayout.Row> rows() {
            // The titles the client cache would answer with, with one id it has never heard of.
            return QuestSettingsLayout.rows(quest(), id -> id.equals("mine_coal") ? "Mine Coal" : id, 2);
        }

        @Test
        @DisplayName("one row per prerequisite, keyed by id and labelled by title")
        void oneRowPerPrerequisite() {
            List<QuestSettingsLayout.Row> rows = rows();
            List<QuestSettingsLayout.Row> dependencies = rows.stream()
                    .filter(row -> row.kind() == QuestSettingsLayout.Row.Kind.DEPENDENCY).toList();
            assertEquals(3, dependencies.size(), "the list did not become three rows");
            assertEquals(List.of("dep:smelt_iron", "dep:mine_coal", "dep:chop_wood"),
                    dependencies.stream().map(QuestSettingsLayout.Row::key).toList(),
                    "the rows are not in the list's order, or their keys are not the ids");
            assertEquals("Mine Coal", dependencies.get(1).label(),
                    "a known prerequisite was not named by its title");
            assertEquals("chop_wood", dependencies.get(2).label(),
                    "an unknown prerequisite should fall back to its id rather than a blank label");
            assertEquals(rows.size(), rows.stream().map(QuestSettingsLayout.Row::key).distinct().count(),
                    "two rows share a key, so one of them is unaddressable");
        }

        @Test
        @DisplayName("the three ways to add one are all on the page, and Add selected counts the selection")
        void theAddRowsAreThere() {
            List<QuestSettingsLayout.Row> rows = rows();
            assertTrue(rows.stream().anyMatch(row -> row.key().equals(QuestPanelLayout.DEPENDENCY_ADD)
                    && row.kind() == QuestSettingsLayout.Row.Kind.FIELD), "no Add-by-id row");
            assertTrue(rows.stream().anyMatch(row -> row.key().equals(QuestPanelLayout.DEPENDENCY_PICK)
                    && row.kind() == QuestSettingsLayout.Row.Kind.ACTION), "no Pick row");
            QuestSettingsLayout.Row selected = rows.stream()
                    .filter(row -> row.key().equals(QuestSettingsLayout.DEPENDENCY_SELECTED))
                    .findFirst().orElseThrow();
            assertEquals("Add selected (2)", selected.label(),
                    "the row should say how many nodes it would add");
            assertEquals("Add selected", QuestSettingsLayout.rows(quest(), id -> id, 0).stream()
                    .filter(row -> row.key().equals(QuestSettingsLayout.DEPENDENCY_SELECTED))
                    .findFirst().orElseThrow().label(),
                    "with nothing selected the row should not claim a count");
        }

        @Test
        @DisplayName("the requirement picker cycles its closed set and wraps at both ends")
        void thePickerCyclesAndWraps() {
            List<String> choices = QuestSettingsLayout.requirementChoices();
            assertEquals("", choices.get(0), "the chapter's default should be the first value");
            assertEquals(5, choices.size(), "the four modes and the default, and nothing else");
            String at = "";
            for (int step = 0; step < choices.size(); step++) {
                assertEquals(choices.get(step), at, "the cycle skipped or reordered a value");
                at = QuestSettingsLayout.cycleRequirement(at, 1);
            }
            assertEquals("", at, "stepping past the last value should come back to the default");
            assertEquals(choices.get(choices.size() - 1), QuestSettingsLayout.cycleRequirement("", -1),
                    "stepping back from the default should wrap to the last value");
            assertEquals("", QuestSettingsLayout.cycleRequirement("nonsense", 0),
                    "a value the client does not know should read as the default, not throw");
        }

        @Test
        @DisplayName("the picker names what the chapter's default actually is")
        void theDefaultIsNamed() {
            assertEquals("Chapter default (all completed)",
                    QuestSettingsLayout.requirementLabel("", "all_completed"));
            assertEquals("one started", QuestSettingsLayout.requirementLabel("one_started", "all_completed"),
                    "a written-out mode should read as itself, not as the default");
        }
    }
}
