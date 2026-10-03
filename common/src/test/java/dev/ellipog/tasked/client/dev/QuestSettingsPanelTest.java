package dev.ellipog.tasked.client.dev;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.tasked.client.BookGeometry;
import dev.ellipog.tasked.quest.QuestShape;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the settings page draws, from the recorded calls.
 *
 * <h2>What a test can see here, and what it cannot</h2>
 *
 * <p>It can see that every shape the format names is offered as a swatch, that the page names the shape
 * and size it is previewing, that the node in the preview is drawn inside the preview's own pane, that
 * a hovered row's explanation is on the help line, and that a switch says which way it is. It cannot see
 * whether the page reads well — that is what the stills and the game are for.
 *
 * <h2>And it can see where a control landed, which is the thing that shipped wrong</h2>
 *
 * <p>The first version of this page drew every row at its <b>layout</b> coordinates while clipping to
 * the controls column, which is a screen rectangle a column's width to the right — so every control was
 * discarded by the clip and the column drew nothing at all. The preview, the caption and the help line
 * are drawn from the frame's own screen rectangles, so the page looked half-alive rather than broken,
 * and the stills agreed with it because the preview script placed the slots itself. So these tests draw
 * through a viewport placed exactly as the screen places it, and assert that the controls are <b>in the
 * column</b> — the one assertion that makes that class of mistake impossible to ship again.
 *
 * <p>The recording renderer is package-private to this package, which is why this test lives here
 * rather than beside the panel.
 */
@DisplayName("The settings page's drawing")
class QuestSettingsPanelTest {

    private static final BookGeometry.Rect BODY = BookGeometry.Rect.at(0, 0, 496, 284);

    private static JsonObject quest() {
        JsonObject quest = new JsonObject();
        quest.add("id", new JsonPrimitive("smelt_iron"));
        quest.add("size", new JsonPrimitive(96));
        quest.add("iconScale", new JsonPrimitive(0.5));
        quest.add("showTitle", new JsonPrimitive(true));
        quest.add("repeatable", new JsonPrimitive(false));
        return quest;
    }

    /** The viewport the screen hands the panel: the column's own rectangle. */
    private static dev.ellipog.armature.client.ui.kit.Viewport columnOf(QuestSettingsLayout.Frame frame) {
        var column = dev.ellipog.armature.client.ui.kit.Viewport.fixed();
        column.bounds(frame.controls().x(), frame.controls().y(), frame.controls().width(),
                frame.controls().height());
        return column;
    }

    private static RecordingRenderer draw(QuestSettingsPanel.View view) {
        QuestSettingsLayout.Frame frame = QuestSettingsLayout.Frame.of(BODY);
        List<QuestSettingsLayout.Row> rows = QuestSettingsLayout.rows();
        Layout layout = QuestSettingsLayout.build(rows, frame.controls().width(),
                Measure.monospace(6, 9));
        RecordingRenderer r = new RecordingRenderer();
        QuestSettingsPanel.draw(r, frame, layout, rows, quest(), view, columnOf(frame), 0, 0);
        return r;
    }

    private static QuestSettingsPanel.View view(QuestShape shape, String hovered) {
        return new QuestSettingsPanel.View("Smelt Iron", null, shape, shape.geometry(), 0, 96, 0.5,
                true, -1, hovered, "all_completed");
    }

    /** The same, turned: what the preview does with an angle. */
    private static QuestSettingsPanel.View turned(QuestShape shape, int rotation, String hovered) {
        return new QuestSettingsPanel.View("Smelt Iron", null, shape,
                dev.ellipog.armature.client.ui.shape.Shapes.rotated(shape.geometry(), rotation),
                rotation, 96, 0.5, true, -1, hovered, "all_completed");
    }

    @Test
    @DisplayName("every shape the format names is offered, by name")
    void everyShapeIsOffered() {
        RecordingRenderer r = draw(view(QuestShape.ROUNDED, null));
        for (QuestShape shape : QuestShape.values()) {
            String name = shape.name().toLowerCase(java.util.Locale.ROOT);
            assertTrue(r.wrote(name), "the page does not offer the " + name + " shape");
        }
    }

    @Test
    @DisplayName("the caption says what is being previewed and how big it is")
    void theCaptionNamesTheShapeAndSize() {
        RecordingRenderer r = draw(view(QuestShape.HEART, null));
        assertTrue(r.texts().stream().anyMatch(drawn -> drawn.text().contains("heart")
                        && drawn.text().contains("96")),
                "the caption does not say what it is showing: " + r.describe());
    }

    @Test
    @DisplayName("the node is drawn inside the preview pane, not over the controls")
    void thePreviewStaysInItsPane() {
        QuestSettingsLayout.Frame frame = QuestSettingsLayout.Frame.of(BODY);
        RecordingRenderer r = draw(view(QuestShape.CIRCLE, null));
        boolean nodeInPane = r.fills().stream().anyMatch(fill ->
                fill.left() >= frame.preview().x() && fill.right() <= frame.preview().right()
                        && fill.top() >= frame.preview().y() && fill.bottom() <= frame.preview().bottom());
        assertTrue(nodeInPane, "the preview drew nothing inside its own pane: " + r.describe());
    }

    @Test
    @DisplayName("the hovered row's explanation is on the help line")
    void theHelpLineExplainsWhatIsHovered() {
        RecordingRenderer r = draw(view(QuestShape.ROUNDED, "iconScale"));
        assertTrue(r.texts().stream().anyMatch(drawn -> drawn.text().contains("largest square")),
                "the help line did not explain the icon scale: " + r.describe());

        RecordingRenderer quiet = draw(view(QuestShape.ROUNDED, null));
        assertTrue(quiet.texts().stream().anyMatch(drawn -> drawn.text().contains("Shape, size")),
                "with nothing hovered the page should say what it is");
    }

    @Test
    @DisplayName("a switch says which way it is, from the tree rather than from a cached label")
    void switchesSayTheirState() {
        RecordingRenderer r = draw(view(QuestShape.ROUNDED, null));
        assertTrue(r.wrote("Show title \u00b7 on"), "the showTitle switch is not drawn as on: " + r.describe());
        assertTrue(r.wrote("Repeatable \u00b7 off"), "the repeatable switch is not drawn as off: " + r.describe());
    }

    @Test
    @DisplayName("the preview follows the draft, so a slider moves the node before the server answers")
    void thePreviewReadsTheDraft() {
        QuestSettingsLayout.Frame frame = QuestSettingsLayout.Frame.of(BODY);
        List<QuestSettingsLayout.Row> rows = QuestSettingsLayout.rows();
        Layout layout = QuestSettingsLayout.build(rows, frame.controls().width(),
                Measure.monospace(6, 9));
        RecordingRenderer r = new RecordingRenderer();
        QuestSettingsPanel.View drafted = new QuestSettingsPanel.View("Smelt Iron", null,
                QuestShape.GEAR, dev.ellipog.armature.client.ui.shape.Shapes.rotated(
                        QuestShape.GEAR.geometry(), 0),
                0, 320, 0.5, true, -1, null, "all_completed");
        QuestSettingsPanel.draw(r, frame, layout, rows, quest(), drafted, columnOf(frame), 0, 0);
        assertTrue(r.texts().stream().anyMatch(drawn -> drawn.text().contains("gear")
                        && drawn.text().contains("320")),
                "the caption ignored the draft: " + r.describe());
        // The caption is truncated to its column, so what is asserted is the part that fits -- and the
        // scaling itself, from the node's own extent in the pane.
        assertTrue(r.texts().stream().anyMatch(drawn -> drawn.text().contains("gear")
                        && drawn.text().contains("320")),
                "the scaled caption does not name the shape and size: " + r.describe());
    }

    @Test
    @DisplayName("the size slider's knob is drawn at the value's own place on the track")
    void theKnobSitsWhereTheValueSays() {
        QuestSettingsLayout.Frame frame = QuestSettingsLayout.Frame.of(BODY);
        List<QuestSettingsLayout.Row> rows = QuestSettingsLayout.rows();
        Layout layout = QuestSettingsLayout.build(rows, frame.controls().width(),
                Measure.monospace(6, 9));
        RecordingRenderer r = new RecordingRenderer();
        QuestSettingsPanel.draw(r, frame, layout, rows, quest(),
                view(QuestShape.ROUNDED, null), columnOf(frame), 0, 0);

        // The knob's x, from the layout's own arithmetic through the same viewport the drawing uses --
        // the layout's slot is the column's coordinates, so the expected x has to be placed too.
        var slot = dev.ellipog.armature.client.ui.inspect.InspectLayout.onScreen(columnOf(frame),
                layout.slot("size"));
        var strip = QuestSettingsLayout.strip(slot);
        var track = QuestSettingsLayout.track(strip);
        int knobX = QuestSettingsLayout.knobX(track, 96, QuestSettingsLayout.MIN_SIZE,
                QuestSettingsLayout.MAX_SIZE, true);
        // The knob is the tallest thing on the track's row: a fill that starts on the track and is
        // taller than the track itself, centred on the value's x.
        boolean knobThere = r.fills().stream().anyMatch(fill ->
                fill.bottom() - fill.top() >= QuestSettingsLayout.KNOB_HEIGHT
                        && Math.abs((fill.left() + fill.right()) / 2 - knobX) <= 1);
        assertTrue(knobThere, "no knob was drawn at x=" + knobX + ": " + r.describe());
    }

    @Test
    @DisplayName("every control is drawn inside the column, and none of them over the preview")
    void theControlsLandInTheColumn() {
        // The assertion the first version of this page would have failed. Its rows were drawn at the
        // layout's own coordinates -- x from zero -- while the clip was the column, which starts a
        // column's width to the right, so every control was discarded and the column drew nothing. The
        // preview, the caption and the help line come from the frame's screen rectangles, so the page
        // still looked half-alive; and the preview stills agreed with it, because the preview script
        // placed the slots itself. A test that says "the controls are where the column is" is what
        // closes that gap.
        QuestSettingsLayout.Frame frame = QuestSettingsLayout.Frame.of(BODY);
        RecordingRenderer r = draw(view(QuestShape.ROUNDED, null));

        List<String> labels = List.of("Shape", "Size and icon", "Size", "Icon scale", "Icon",
                "Show title · on", "Placement", "Rules", "Identity extras",
                "rounded", "square", "circle", "diamond", "heart");
        for (String label : labels) {
            assertTrue(r.texts().stream().anyMatch(drawn -> drawn.text().equals(label)
                            && drawn.x() >= frame.controls().x()
                            && drawn.x() < frame.controls().right()),
                    "'" + label + "' was not drawn inside the controls column: " + r.describe());
        }

        // And the other direction: nothing the controls draw may land over the preview pane, which is
        // drawn first and would be painted over rather than hidden.
        for (RecordingRenderer.Drawn drawn : r.texts()) {
            boolean previewText = drawn.x() < frame.controls().x() && drawn.y() < frame.caption().y();
            assertTrue(!previewText || drawn.text().equals("Smelt Iron"),
                    "the controls drew '" + drawn.text() + "' over the preview: " + r.describe());
        }
    }
}
