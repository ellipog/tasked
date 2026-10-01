package dev.ellipog.tasked.client.dev;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.inspect.InspectLayout;
import dev.ellipog.armature.client.ui.inspect.InspectRow;
import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.tasked.client.BookGeometry;

import java.util.List;

/**
 * What the quest panel looks like: the selected quest's own fields, in sections that fold.
 *
 * <h2>Why the drawing is its own class</h2>
 *
 * <p>The same reason the tools panel's is: {@code QuestBookScreen} cannot be instantiated by a test, and
 * everything here takes a {@link GuiRenderer} and plain numbers, so the panel's appearance is one file
 * that can be read at once — and the arithmetic it reads is the toolkit's, which is asserted.
 *
 * <h2>What the widgets do and what this draws</h2>
 *
 * <p>A field's text box, a toggle's button and an action row are widgets, placed by the screen through
 * {@link InspectLayout#strip}; this draws the panel they sit on, every row's <b>label</b>, the read-only
 * values, and the fallback's raw text. A {@code FIELD} row's label is truncated to the room left of its
 * strip, which is the one truncation that matters: a label running under the field it names is the
 * oldest fault in this codebase's panels.
 */
public final class QuestPanel {

    private QuestPanel() {
    }

    /** Draws the panel and everything on it that is not a widget. */
    public static void draw(GuiRenderer r, ToolsLayout.Frame frame, Viewport list, Layout layout,
                            List<InspectRow> rows, int mouseX, int mouseY) {
        ArmatureTheme.panel(r, frame.panel().x(), frame.panel().y(), frame.panel().width(),
                frame.panel().height(), ArmatureTheme.panel(), ArmatureTheme.panelEdge());
        drawRows(r, frame.list(), list, layout, rows, mouseX, mouseY);
    }

    /**
     * The rows, drawn into whatever rectangle holds them -- the dock's list, or the quest card's body.
     *
     * <p>The one row-drawing in the mod: the modal editor and the dock's panel show the same inspector,
     * and a second copy of this switch would be a second place for a kind to fall through. The clip is
     * the caller's rectangle, so a row half past the edge is cut there rather than drawn over a header.
     */
    public static void drawRows(GuiRenderer r, BookGeometry.Rect body, Viewport list, Layout layout,
                                List<InspectRow> rows, int mouseX, int mouseY) {
        Measure measure = Measure.of(r::textWidth, r.lineHeight());
        try (GuiRenderer.Scoped clip = r.clip(body.x(), body.y(), body.right(), body.bottom())) {
            for (InspectRow row : rows) {
                Slot slot = layout.slot(row.key());
                if (slot == null) {
                    continue;
                }
                Slot onScreen = InspectLayout.onScreen(list, slot);
                switch (row.kind()) {
                    case HEADING -> drawHeading(r, row, slot, onScreen, measure);
                    case ENTRY -> drawEntry(r, row, slot, onScreen, list, measure);
                    case WARNING -> drawWarning(r, row, slot, onScreen, measure);
                    case STEPPER -> drawStepperRow(r, row, slot, onScreen, list, measure, mouseX, mouseY);
                    case FIELD, TOGGLE, RAW ->
                            drawLabelled(r, row, slot, onScreen, list, measure);
                    case VALUE -> drawValue(r, row, slot, onScreen, measure);
                    // An ACTION row is wholly its widget, which the screen builds; the panel owes it
                    // nothing. Loud rather than quiet if a kind is ever added without a branch here:
                    // a row that draws as nothing is the fault this panel's sibling had first.
                    case ACTION -> {
                    }
                }
            }
        }
    }

    private static void drawHeading(GuiRenderer r, InspectRow row, Slot slot, Slot onScreen,
                                    Measure measure) {
        r.text(Measure.truncate(row.label(), slot.width(), measure), onScreen.x(),
                onScreen.y() + (slot.height() - 8) / 2, ArmatureTheme.title());
    }

    /**
     * A stepper row: its label, then minus, the value, and plus in the strip.
     *
     * <p>The tools panel's radius row, generalised: the arrows are drawn from the strip the hit test
     * reads -- {@code stepperStepAt} -- so what is drawn is what is pressed, and a number is nudged
     * rather than typed. The value sits between the two arrows, which is where the eye looks for it.
     */
    private static void drawStepperRow(GuiRenderer r, InspectRow row, Slot slot, Slot onScreen,
                                       Viewport list, Measure measure, int mouseX, int mouseY) {
        Slot strip = InspectLayout.strip(slot);
        Slot stripOnScreen = InspectLayout.onScreen(list, strip);
        int room = Math.max(0, stripOnScreen.x() - onScreen.x() - 6);
        r.text(Measure.truncate(row.label(), room, measure), onScreen.x(),
                onScreen.y() + (slot.height() - 8) / 2, ArmatureTheme.body());

        int y = onScreen.y() + (slot.height() - 14) / 2;
        for (String way : List.of("down", "up")) {
            BookGeometry.Rect box = stepperBox(stripOnScreen, way);
            boolean hot = box.contains(mouseX, mouseY);
            ArmatureTheme.panel(r, box.x(), box.y(), box.width(), box.height(),
                    hot ? Colour.lerp(ArmatureTheme.raised(), ArmatureTheme.title(), 0.12F)
                            : ArmatureTheme.raised(), ArmatureTheme.panelEdge());
            String glyph = way.equals("down") ? "−" : "+";
            r.text(glyph, box.x() + (box.width() - r.textWidth(glyph)) / 2 + 1,
                    box.y() + (box.height() - r.lineHeight()) / 2 + 2,
                    hot ? ArmatureTheme.title() : ArmatureTheme.body());
        }
        String value = row.value();
        r.text(Measure.truncate(value, Math.max(0, stripOnScreen.width() - 40), measure),
                stripOnScreen.x() + 18, y + 3, ArmatureTheme.title());
    }

    /** One of a stepper's two arrows, in the strip's own coordinates: what is drawn and hit alike. */
    public static BookGeometry.Rect stepperBox(Slot strip, String way) {
        int width = 16;
        int x = way.equals("down") ? strip.x() : strip.right() - width;
        int y = strip.y() + (strip.height() - 14) / 2;
        return BookGeometry.Rect.at(x, y, width, 14);
    }

    /**
     * Which way a press at a screen point steps a stepper row: -1, +1, or null.
     *
     * <p>The derivation the drawing uses, called with the same strip -- one answer to "where are the
     * arrows", so a press lands on the arrow that is under it.
     */
    public static Integer stepperStepAt(Viewport list, Slot slot, double mouseX, double mouseY) {
        Slot strip = InspectLayout.onScreen(list, InspectLayout.strip(slot));
        if (stepperBox(strip, "down").contains(mouseX, mouseY)) {
            return -1;
        }
        if (stepperBox(strip, "up").contains(mouseX, mouseY)) {
            return 1;
        }
        return null;
    }

    /** One entry of a list: its name, and its two controls drawn in the strip. */
    private static void drawEntry(GuiRenderer r, InspectRow row, Slot slot, Slot onScreen,
                                  Viewport list, Measure measure) {
        List<Slot> halves = InspectLayout.stripHalves(slot);
        Slot strip = InspectLayout.strip(slot);
        Slot stripOnScreen = InspectLayout.onScreen(list, strip);
        int room = Math.max(0, stripOnScreen.x() - onScreen.x() - 6);
        r.text(Measure.truncate(row.label(), room, measure), onScreen.x(),
                onScreen.y() + (slot.height() - 8) / 2, ArmatureTheme.title());

        // The two controls, drawn like every small button in this UI: a rounded face and a glyph.
        // Copy first, remove second -- the destructive one at the row's edge, where a mis-aimed press
        // has to travel past the harmless one to reach it.
        String[] labels = {"Copy", "\u00d7"};
        for (int i = 0; i < halves.size(); i++) {
            Slot at = InspectLayout.onScreen(list, halves.get(i));
            ArmatureTheme.panel(r, at.x(), at.y(), at.width(), at.height(), ArmatureTheme.raised(),
                    ArmatureTheme.panelEdge());
            r.text(labels[i], at.x() + (at.width() - r.textWidth(labels[i])) / 2,
                    at.y() + (at.height() - 8) / 2, ArmatureTheme.body());
        }
    }

    /** The fallback's heading: bad news, in the colour that says so. */
    private static void drawWarning(GuiRenderer r, InspectRow row, Slot slot, Slot onScreen,
                                    Measure measure) {
        r.text(Measure.truncate(row.label(), slot.width(), measure), onScreen.x(),
                onScreen.y() + (slot.height() - 8) / 2, ArmatureTheme.blocked());
    }

    /** A row with a control in its strip: the label owns what is left of the strip. */
    private static void drawLabelled(GuiRenderer r, InspectRow row, Slot slot, Slot onScreen,
                                     Viewport list, Measure measure) {
        Slot strip = InspectLayout.strip(slot);
        // In the list's coordinates, so the label's room ends where the strip begins -- and the strip
        // mapped to the screen is where the widget actually is.
        Slot stripOnScreen = InspectLayout.onScreen(list, strip);
        int room = Math.max(0, stripOnScreen.x() - onScreen.x() - 6);
        r.text(Measure.truncate(row.label(), room, measure), onScreen.x(),
                onScreen.y() + (slot.height() - 8) / 2, ArmatureTheme.body());

        if (row.kind() == InspectRow.Kind.RAW && !row.value().isEmpty()) {
            // The fallback's value, as it is stored -- shown rather than summarised, truncated because
            // a one-line panel cannot hold a whole object and lying about that helps nobody.
            r.text(Measure.truncate(row.value(), strip.width(), measure), stripOnScreen.x(),
                    onScreen.y() + (slot.height() - 8) / 2, ArmatureTheme.faint());
        }
    }

    /** A read-only row: label, then the value after it. */
    private static void drawValue(GuiRenderer r, InspectRow row, Slot slot, Slot onScreen,
                                  Measure measure) {
        r.text(Measure.truncate(row.label(), slot.width() / 2, measure), onScreen.x(),
                onScreen.y() + (slot.height() - 8) / 2, ArmatureTheme.body());
        int valueX = onScreen.x() + slot.width() / 2;
        r.text(Measure.truncate(row.value(), onScreen.right() - valueX, measure), valueX,
                onScreen.y() + (slot.height() - 8) / 2, ArmatureTheme.faint());
    }
}
