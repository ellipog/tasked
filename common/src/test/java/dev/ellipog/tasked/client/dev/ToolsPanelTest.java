package dev.ellipog.tasked.client.dev;

import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.tasked.client.BookGeometry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The panel's drawing, read back from a renderer that keeps its calls.
 *
 * <h2>The one question this answers</h2>
 *
 * <p>Not "is the arithmetic right" — {@code ToolsLayoutTest} has that — but <i>"did anything get drawn
 * here"</i>. Every fault the tools panel has had was of that kind, and none of them was visible to
 * arithmetic: the radius row's arrows were built as widgets at the list's coordinates and landed outside the
 * panel; then they were drawn by nobody; then the row fell through the dispatch into the switch-label branch
 * and drew as a bare label while the hit test answered presses perfectly. Three reports from play, three
 * screenshots, and one question that would have caught each of them on the way in.
 */
@DisplayName("the tools panel's drawing")
class ToolsPanelTest {

    private static final Measure MEASURE = Measure.monospace(6, 9);

    @Test
    @DisplayName("the radius row draws its label, its two arrows and its number, where they are pressed")
    void theRadiusRowIsDrawn() {
        BookGeometry.Rect canvas = new BookGeometry(854, 480, true).canvas();
        ToolsLayout.Frame frame = ToolsLayout.frame(canvas);
        List<ToolsLayout.Action> rows = ToolsLayout.rows(false, false, true, true);
        Layout layout = ToolsLayout.build(rows, frame.list().width(), MEASURE);
        Viewport list = Viewport.fixed().bounds(frame.list().x(), frame.list().y(),
                frame.list().width(), frame.list().height());
        RecordingRenderer r = new RecordingRenderer();

        ToolsPanel.draw(r, frame, list, layout, rows, new ToolsPanel.State(null, null, false, false), 0, 0);

        Slot row = layout.slot(ToolsLayout.RADIUS);
        // Mapped by hand rather than with `ToolsLayout.onScreen`: the mapping is the earlier test's, and this
        // one is about what lands inside the rectangles it produces. The list is not scrolled here, so a
        // content point is the view's origin plus that point.
        Slot onScreen = new Slot(row.key(), frame.list().x() + row.x(), frame.list().y() + row.y(),
                row.width(), row.height());

        for (Slot arrow : ToolsLayout.stepper(onScreen).values()) {
            assertTrue(r.covered(arrow.x(), arrow.y(), arrow.right(), arrow.bottom()),
                    () -> "nothing drawn where this arrow is: " + arrow + "  (" + r.describe() + ")");
        }
        Slot number = ToolsLayout.stepperValue(onScreen);
        assertTrue(r.wroteWithin("0", number.x(), number.y(), number.right(), number.bottom()),
                () -> "the radius is not drawn between the arrows: " + r.describe());
        assertTrue(r.wroteWithin("Border radius", onScreen.x(), onScreen.y(), onScreen.right(),
                        onScreen.bottom()),
                () -> "the row has no label either: " + r.describe());
    }

    @Test
    @DisplayName("every row of the list is drawn as something, whatever its kind")
    void noRowIsDrawnAsNothing() {
        // The general shape of the fault, rather than the one row it happened to: a kind with no branch, or a
        // branch that draws nothing, leaves a row that is invisible while its widget still takes presses. Each
        // kind is asked for what the *panel* owes it -- a colour row's name is its widget's business, and what
        // the panel draws for it is its code and its swatch.
        BookGeometry.Rect canvas = new BookGeometry(854, 480, true).canvas();
        ToolsLayout.Frame frame = ToolsLayout.frame(canvas);
        List<ToolsLayout.Action> rows = ToolsLayout.rows(false, true, true, true);
        Layout layout = ToolsLayout.build(rows, frame.list().width(), MEASURE);
        Viewport list = Viewport.fixed().bounds(frame.list().x(), frame.list().y(),
                frame.list().width(), frame.list().height());
        RecordingRenderer r = new RecordingRenderer();

        ToolsPanel.draw(r, frame, list, layout, rows, new ToolsPanel.State(null, null, false, false), 0, 0);

        for (ToolsLayout.Action row : rows) {
            Slot slot = layout.slot(row.key());
            Slot onScreen = new Slot(row.key(), frame.list().x() + slot.x(), frame.list().y() + slot.y(),
                    slot.width(), slot.height());
            // What the *panel* owes each kind: its name, for the rows whose name no widget draws; its code and
            // swatch, for the colour rows, whose names are their widgets' business.
            boolean drawn;
            if (row.kind() == ToolsLayout.Action.Kind.ROW) {
                drawn = r.texts().stream().anyMatch(text -> text.text().startsWith("#")
                        && text.x() >= onScreen.x() && text.x() <= onScreen.right()
                        && text.y() >= onScreen.y() - 8 && text.y() <= onScreen.bottom());
            }
            else {
                drawn = r.wroteWithin(row.label(), onScreen.x(), onScreen.y(), onScreen.right(),
                        onScreen.bottom());
            }
            assertTrue(drawn, () -> "a row drew nothing: " + row.key() + " (" + row.kind() + ")");
        }
    }
}
