package dev.ellipog.tenet.client.dev;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.inspect.InspectLayout;
import dev.ellipog.armature.client.ui.inspect.InspectRow;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.tenet.client.BookGeometry;

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
 * {@link InspectLayout#strip}; this draws the panel they sit on, every row's <b>label</b>, the values that
 * are not widgets, and the fallback value's text for an unknown type -- drawn here, and edited in the
 * card's own JSON field (see {@code QuestPanelLayout}'s note on the fallback). A {@code FIELD} row's label
 * is truncated to the room left of its strip, which is the one truncation that matters: a label running
 * under the field it names is the oldest fault in this codebase's panels.
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
     *
     * <p>The side-by-side composition: a label with its control in the strip at its right.
     */
    public static void drawRows(GuiRenderer r, BookGeometry.Rect body, Viewport list, Layout layout,
                                List<InspectRow> rows, int mouseX, int mouseY) {
        drawRows(r, body, list, layout, rows, mouseX, mouseY, InspectLayout.Mode.SIDE_BY_SIDE);
    }

    /**
     * The same, composed the way the panel asked for it. See {@link InspectLayout.Mode}.
     *
     * <p>Only the labelled rows differ: a stacked {@code FIELD} draws its label on its own band and
     * leaves the band beneath it to the control, while headings, values and actions are the same shape
     * in both modes. The widget that fills the control band is the screen's; this draws everything
     * around it.
     */
    public static void drawRows(GuiRenderer r, BookGeometry.Rect body, Viewport list, Layout layout,
                                List<InspectRow> rows, int mouseX, int mouseY,
                                InspectLayout.Mode mode) {
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
                    case FIELD, TOGGLE, RAW -> {
                        if (mode == InspectLayout.Mode.STACKED) {
                            drawStacked(r, row, slot, onScreen, list, measure);
                        }
                        else {
                            drawLabelled(r, row, slot, onScreen, list, measure);
                        }
                    }
                    case VALUE -> drawValue(r, row, slot, onScreen, measure);
                    // An ACTION row is its own widget, which the screen builds: the label belongs to that
                    // button and the panel must not draw it twice. Its *value* is the exception, and this
                    // is where the rule was wrong for two rounds: a right-aligned detail -- the id a file
                    // spells, beside a type's name -- is text no widget draws, so a row that carried one
                    // was carrying data nothing read. The button is flat and its label is left-aligned, so
                    // the right end of the strip is free and the press still reaches the widget.
                    case ACTION -> drawActionDetail(r, row, slot, onScreen, list, measure);
                }
            }
        }
    }

    private static void drawHeading(GuiRenderer r, InspectRow row, Slot slot, Slot onScreen,
                                    Measure measure) {
        r.text(Measure.truncate(Labels.of(row.label()), slot.width(), measure), onScreen.x(),
                onScreen.y() + (slot.height() - 8) / 2, ArmatureTheme.title());
    }

    /** One entry of a list: its name, and its two controls drawn in the strip. */
    private static void drawEntry(GuiRenderer r, InspectRow row, Slot slot, Slot onScreen,
                                  Viewport list, Measure measure) {
        List<Slot> halves = InspectLayout.stripHalves(slot);
        Slot strip = InspectLayout.strip(slot);
        Slot stripOnScreen = InspectLayout.onScreen(list, strip);
        int room = Math.max(0, stripOnScreen.x() - onScreen.x() - 6);
        r.text(Measure.truncate(Labels.of(row.label()), room, measure), onScreen.x(),
                onScreen.y() + (slot.height() - 8) / 2, ArmatureTheme.title());

        // The two controls, drawn like every small button in this UI: a rounded face and a glyph.
        // Copy first, remove second -- the destructive one at the row's edge, where a mis-aimed press
        // has to travel past the harmless one to reach it.
        String[] labels = {Labels.of("tenet.dev.copy"), "\u00d7"};
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
        r.text(Measure.truncate(Labels.of(row.label()), slot.width(), measure), onScreen.x(),
                onScreen.y() + (slot.height() - 8) / 2, ArmatureTheme.blocked());
    }

    /**
     * An action row's right-aligned detail: the id a type writes, in the faint ink a secondary fact takes.
     *
     * <p>The label is the button's, so this draws nothing else — and nothing at all for the rows that carry
     * no detail, which is every action row but the type picker's. It is the item picker's arrangement
     * ("Quest Book …… tenet:quest_book") applied to the list that opens instead of it, so the two read as
     * one family and the spelling a file uses is on screen rather than only in a hover.
     */
    private static void drawActionDetail(GuiRenderer r, InspectRow row, Slot slot, Slot onScreen,
                                         Viewport list, Measure measure) {
        if (row.value().isEmpty()) {
            return;
        }
        Slot strip = InspectLayout.onScreen(list, InspectLayout.strip(slot));
        String value = Measure.truncate(row.value(), Math.max(0, strip.width() - 4), measure);
        r.text(value, strip.right() - 2 - measure.width(value),
                onScreen.y() + (slot.height() - 8) / 2, ArmatureTheme.faint());
    }

    /** A row with a control in its strip: the label owns what is left of the strip. */
    private static void drawLabelled(GuiRenderer r, InspectRow row, Slot slot, Slot onScreen,
                                     Viewport list, Measure measure) {
        Slot strip = InspectLayout.strip(slot);
        // In the list's coordinates, so the label's room ends where the strip begins -- and the strip
        // mapped to the screen is where the widget actually is.
        Slot stripOnScreen = InspectLayout.onScreen(list, strip);
        int room = Math.max(0, stripOnScreen.x() - onScreen.x() - 6);
        r.text(Measure.truncate(Labels.of(row.label()), room, measure), onScreen.x(),
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
        r.text(Measure.truncate(Labels.of(row.label()), slot.width() / 2, measure), onScreen.x(),
                onScreen.y() + (slot.height() - 8) / 2, ArmatureTheme.body());
        int valueX = onScreen.x() + slot.width() / 2;
        r.text(Measure.truncate(row.value(), onScreen.right() - valueX, measure), valueX,
                onScreen.y() + (slot.height() - 8) / 2, ArmatureTheme.faint());
    }

    /**
     * A stacked labelled row: the label on its own band, the control's band beneath it.
     *
     * <p>Nothing is drawn in the control band -- that is where the widget is, placed by the same
     * {@code InspectLayout.controlBand} this label's band comes from, so the two cannot disagree about
     * where the line between them is. The label is never truncated against a strip, because in this
     * mode there is no strip: that is the whole of what the mode buys a narrow panel.
     */
    private static void drawStacked(GuiRenderer r, InspectRow row, Slot slot, Slot onScreen,
                                    Viewport list, Measure measure) {
        Slot label = InspectLayout.onScreen(list, InspectLayout.labelBand(slot));
        r.text(Measure.truncate(Labels.of(row.label()), label.width(), measure), label.x(),
                label.y() + (label.height() - 8) / 2, ArmatureTheme.body());

        if (row.kind() == InspectRow.Kind.RAW && !row.value().isEmpty()) {
            // The fallback's value, shown rather than summarised. In this mode it sits in the control
            // band beside whatever editor the screen placed there, on the same terms as `drawLabelled`.
            Slot control = InspectLayout.onScreen(list, InspectLayout.controlBand(slot));
            r.text(Measure.truncate(row.value(), control.width(), measure), control.x(),
                    control.y() + (control.height() - 8) / 2, ArmatureTheme.faint());
        }
    }
}
