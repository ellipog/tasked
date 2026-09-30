package dev.ellipog.tasked.client.dev;

import dev.ellipog.armature.client.Appearance;
import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.ThemeToken;
import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.tasked.client.BookGeometry;
import dev.ellipog.tasked.quest.QuestShape;

import java.util.List;
import java.util.Map;

/**
 * What the tools panel looks like: the preview, the list, and the band that edits one colour.
 *
 * <h2>Why the drawing is its own class</h2>
 *
 * <p>Because {@code QuestBookScreen} is a screen: nothing in it can be instantiated by a test, and it is
 * already the largest file in either mod. Everything here takes a {@link GuiRenderer} and plain numbers,
 * so the panel's appearance is one file that can be read at once — and the arithmetic it reads is
 * {@link ToolsLayout}'s, which is asserted.
 *
 * <h2>The preview, and what it is honest about</h2>
 *
 * <p>It draws a small quest: a panel, a node in a shape, three lines of text, a button, a tooltip and a
 * scrollbar mark, all in the theme as it stands. Selecting a colour rings the region its <b>group</b>
 * paints — eight regions for eight groups — because that is the question the list raises: not "what is
 * {@code rowHover}" but "which part of this screen does it change".
 *
 * <p>It is a <b>sample, not the book</b>: the proportions are its own, the text is literal, and the node is
 * always the rounded shape. A preview that claimed to be the real screen would be a second description of
 * it, and this one says what it is.
 */
public final class ToolsPanel {

    /** The preview's own swatch sizes, so the sample reads as a screen rather than as icons. */
    private static final int NODE = 26;

    private ToolsPanel() {
    }

    /** What the panel is showing: the selected colour, the last thing that happened, and the theme. */
    public record State(String selected, String feedback, boolean feedbackIsError, String currentTheme) {
    }

    /**
     * Draws everything that is not a widget.
     *
     * <p>The switches' buttons, the colour rows, the channel steppers and Revert/Save are widgets and are
     * drawn by the widget pass — this draws the panel they sit on, their labels, and the colour that a
     * widget cannot draw.
     */
    public static void draw(GuiRenderer r, ToolsLayout.Frame frame, Viewport list, Layout layout,
                            List<ToolsLayout.Action> rows, State state, int mouseX, int mouseY) {
        ArmatureTheme.panel(r, frame.panel().x(), frame.panel().y(), frame.panel().width(),
                frame.panel().height(), ArmatureTheme.panel(), ArmatureTheme.panelEdge());

        r.text("Tools", frame.title().x(), frame.title().y() + 2, ArmatureTheme.title());
        if (state.feedback() != null && !state.feedback().isEmpty()) {
            r.text(Measure.truncate(state.feedback(), frame.feedback().width(), textMeasure(r)),
                    frame.feedback().x(), frame.feedback().y(),
                    state.feedbackIsError() ? ArmatureTheme.blocked() : ArmatureTheme.faint());
        }

        drawPreview(r, frame.preview(), state.selected());

        Measure measure = textMeasure(r);
        BookGeometry.Rect listRect = frame.list();
        try (GuiRenderer.Scoped clip = r.clip(listRect.x(), listRect.y(), listRect.right(),
                listRect.bottom())) {
            for (ToolsLayout.Action row : rows) {
                Slot slot = layout.slot(row.key());
                if (slot == null) {
                    continue;
                }
                Slot onScreen = onScreen(list, slot);
                if (row.isHeading()) {
                    drawHeading(r, row, slot, onScreen, measure);
                }
                else if (row.isControl()) {
                    drawRow(r, row, slot, onScreen, measure, state, mouseX, mouseY);
                }
                else {
                    // A switch's label. Its button is a widget, in the strip the row reserved.
                    r.text(Measure.truncate(row.label(),
                                    Math.max(0, slot.width() - ToolsLayout.STRIP_WIDTH - 8), measure),
                            onScreen.x() + 2, textY(slot, onScreen, r), ArmatureTheme.body());
                }
            }
        }

        drawBand(r, frame, state, measure);
    }

    // ------------------------------------------------------------------
    // The list
    // ------------------------------------------------------------------

    /** A section's name, a rule under it, and a marker for a group inside a section. */
    private static void drawHeading(GuiRenderer r, ToolsLayout.Action row, Slot slot, Slot onScreen,
                                    Measure measure) {
        boolean section = row.key().equals(ToolsLayout.THEME_SECTION)
                || row.key().equals(ToolsLayout.COLOUR_SECTION);
        r.text(Measure.truncate(row.label(), slot.width(), measure), onScreen.x(),
                onScreen.y() + (slot.height() - r.lineHeight()) / 2,
                section ? ArmatureTheme.title() : ArmatureTheme.heading());
        if (section) {
            // A rule under a section's name, which is what makes it read as a section rather than as a
            // heading that happens to be in a list.
            r.fill(onScreen.x(), onScreen.bottom() - 1, onScreen.right(), onScreen.bottom(),
                    ArmatureTheme.panelEdge());
        }
    }

    /** A row that is itself the control: a theme, a group's name, or a colour. */
    private static void drawRow(GuiRenderer r, ToolsLayout.Action row, Slot slot, Slot onScreen,
                                Measure measure, State state, int mouseX, int mouseY) {
        String token = ToolsLayout.tokenId(row.key());
        String theme = ToolsLayout.themeName(row.key());
        boolean hovered = onScreen.contains(mouseX, mouseY);

        if (token != null) {
            int argb = Appearance.main().colour(token);
            boolean isSelected = token.equals(state.selected());
            if (isSelected || hovered) {
                r.fill(onScreen.x(), onScreen.y(), onScreen.right(), onScreen.bottom(),
                        Colour.translucent(ArmatureTheme.rowHover(), isSelected ? 0.5F : 0.22F));
            }
            String hex = String.format("#%06X", argb & 0xFFFFFF);
            int hexWidth = r.textWidth(hex);
            int swatch = slot.height() - 4;
            int swatchX = onScreen.right() - 4 - hexWidth - 5 - swatch;
            int swatchY = onScreen.y() + 2;
            r.fill(swatchX - 1, swatchY - 1, swatchX + swatch + 1, swatchY + swatch + 1,
                    ArmatureTheme.panelEdge());
            r.fill(swatchX, swatchY, swatchX + swatch, swatchY + swatch, argb);
            r.text(hex, onScreen.right() - 4 - hexWidth, textY(slot, onScreen, r),
                    isSelected ? ArmatureTheme.title() : ArmatureTheme.faint());
            return;
        }

        boolean current = theme != null && theme.equals(state.currentTheme());
        if (current || hovered) {
            r.fill(onScreen.x(), onScreen.y(), onScreen.right(), onScreen.bottom(),
                    Colour.translucent(ArmatureTheme.rowHover(), current ? 0.4F : 0.2F));
        }
        r.text(Measure.truncate(row.label(), slot.width() - 4, measure), onScreen.x() + 2,
                textY(slot, onScreen, r), current ? ArmatureTheme.title() : ArmatureTheme.body());
    }

    // ------------------------------------------------------------------
    // The band
    // ------------------------------------------------------------------

    /** The selected colour, its channels with their numbers, and the two actions. */
    private static void drawBand(GuiRenderer r, ToolsLayout.Frame frame, State state, Measure measure) {
        String token = state.selected();
        int argb = token == null ? 0 : Appearance.main().colour(token);

        BookGeometry.Rect swatch = frame.swatch();
        int box = swatch.height() - 4;
        int boxY = swatch.y() + 2;
        r.fill(swatch.x(), boxY - 1, swatch.x() + box + 2, boxY + box + 1, ArmatureTheme.panelEdge());
        if (token != null) {
            r.fill(swatch.x() + 1, boxY, swatch.x() + 1 + box, boxY + box, argb);
        }

        String name = token == null ? "Press a colour to edit it"
                : labelOf(token) + "  " + String.format("#%08X", argb);
        r.text(Measure.truncate(name, Math.max(0, swatch.width() - box - 10), measure),
                swatch.x() + box + 8, swatch.y() + (swatch.height() - r.lineHeight()) / 2,
                token == null ? ArmatureTheme.faint() : ArmatureTheme.body());

        // The channels: the letter, the value, and two buttons that are widgets.
        Map<String, Slot> beats = ToolsLayout.beats(frame.channels());
        for (int i = 0; i < ToolsLayout.CHANNELS.size(); i++) {
            String channel = ToolsLayout.CHANNELS.get(i);
            Slot value = beats.get("beat:" + channel);
            Slot down = beats.get("down:" + channel);
            r.text(channel, down.x() - 10, down.y() + (down.height() - r.lineHeight()) / 2,
                    ArmatureTheme.faint());
            String number = token == null ? "--" : String.valueOf(channelValue(argb, channel));
            r.text(number, value.x() + (value.width() - r.textWidth(number)) / 2,
                    value.y() + (value.height() - r.lineHeight()) / 2,
                    token == null ? ArmatureTheme.faint() : ArmatureTheme.title());
        }
    }

    /** One channel's value out of a packed colour. */
    public static int channelValue(int argb, String channel) {
        int shift = switch (channel) {
            case "R" -> 16;
            case "G" -> 8;
            case "B" -> 0;
            default -> 24;
        };
        return (argb >>> shift) & 0xFF;
    }

    /** A token's name, from the catalogue rather than from its id. */
    private static String labelOf(String tokenId) {
        ThemeToken token = ThemeToken.byId(tokenId);
        return token == null ? tokenId : token.label();
    }

    // ------------------------------------------------------------------
    // The preview
    // ------------------------------------------------------------------

    /**
     * A small quest, drawn in the theme as it stands, with the selected group's region ringed.
     *
     * <p>The eight regions are the eight groups, and each is one rectangle the sample uses for that
     * group's colours: the background stack, the text lines, the node, the graph line, the row wash, the
     * scrollbar mark, the tooltip, and the button. A group with nothing selected is not ringed, and a
     * token whose group is unknown is not either — a ring around the wrong thing would be worse than none.
     */
    private static void drawPreview(GuiRenderer r, BookGeometry.Rect preview, String selected) {
        BookGeometry.Rect rect = BookGeometry.Rect.at(preview.x(), preview.y(), preview.width(),
                preview.height());
        r.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), ArmatureTheme.canvas());

        int x = rect.x() + 6;
        int y = rect.y() + 6;
        int w = rect.width() - 12;

        // The background stack: a panel with a raised strip and a recessed strip inside it.
        BookGeometry.Rect panel = BookGeometry.Rect.at(x, y, w, rect.height() - 12);
        ArmatureTheme.panel(r, panel.x(), panel.y(), panel.width(), panel.height(),
                ArmatureTheme.panel(), ArmatureTheme.panelEdge());
        BookGeometry.Rect raised = BookGeometry.Rect.at(panel.x() + 6, panel.y() + 6,
                Math.max(0, panel.width() - 12), 14);
        r.fill(raised.x(), raised.y(), raised.right(), raised.bottom(), ArmatureTheme.raised());
        BookGeometry.Rect recessed = BookGeometry.Rect.at(raised.x(), raised.bottom() + 4,
                Math.max(0, raised.width() - 60), 12);
        r.fill(recessed.x(), recessed.y(), recessed.right(), recessed.bottom(),
                ArmatureTheme.recessed());

        // The graph line, under the node, so the GRAPH group has a region of its own.
        BookGeometry.Rect line = BookGeometry.Rect.at(recessed.right() + 8, recessed.y() + 5,
                Math.max(0, panel.right() - recessed.right() - 14), 2);
        r.fill(line.x(), line.y(), line.right(), line.bottom(), ArmatureTheme.line());

        // The node, in the state the STATE group's colours describe: available, with its ring.
        int nodeX = panel.right() - NODE - 8;
        int nodeY = raised.bottom() + 6;
        r.fill(nodeX - 1, nodeY - 1, nodeX + NODE + 1, nodeY + NODE + 1, ArmatureTheme.nodeFill());
        ArmatureTheme.shapePanel(r, nodeX, nodeY, NODE, ArmatureTheme.nodeFill(),
                ArmatureTheme.nodeEdgeAvailable(), QuestShape.ROUNDED::span);

        // The text lines: a title, body and faint line inside the panel.
        int textX = panel.x() + 6;
        int textY = recessed.bottom() + 6;
        r.text("Title", textX, textY, ArmatureTheme.title());
        r.text("Body text", textX, textY + 11, ArmatureTheme.body());
        r.text("faint", textX + r.textWidth("Body text") + 6, textY + 11, ArmatureTheme.faint());

        // The row wash, under the lines.
        BookGeometry.Rect row = BookGeometry.Rect.at(textX - 2, textY + 22, Math.max(0, w / 2), 10);
        r.fill(row.x(), row.y(), row.right(), row.bottom(),
                Colour.translucent(ArmatureTheme.rowHover(), 0.5F));

        // The button: raised, with the control edge the Controls group owns.
        BookGeometry.Rect button = BookGeometry.Rect.at(row.right() + 8, row.y() - 2, 52, 14);
        ArmatureTheme.panel(r, button.x(), button.y(), button.width(), button.height(),
                ArmatureTheme.raised(), ArmatureTheme.controlEdge());

        // The tooltip, floating over the panel's bottom-right corner.
        BookGeometry.Rect tooltip = BookGeometry.Rect.at(panel.right() - 74, panel.bottom() - 22, 68, 16);
        ArmatureTheme.panel(r, tooltip.x(), tooltip.y(), tooltip.width(), tooltip.height(),
                ArmatureTheme.tooltipFill(), ArmatureTheme.tooltipEdge());

        // The scrollbar mark, against the panel's right edge.
        BookGeometry.Rect track = BookGeometry.Rect.at(panel.right() - 5, panel.y() + 4, 3,
                Math.max(0, panel.height() - 8));
        r.fill(track.x(), track.y(), track.right(), track.bottom(), ArmatureTheme.scrollTrack());
        r.fill(track.x(), track.y(), track.right(), track.y() + track.height() / 2,
                ArmatureTheme.scrollThumb());

        // And the ring, around the region the selected token's group paints.
        BookGeometry.Rect region = regionOf(selected, panel, raised, row, line, nodeX, nodeY, button,
                tooltip);
        if (region != null) {
            ring(r, region);
        }
    }

    /** Which part of the sample a group's colours paint. Null for no selection and for an unknown one. */
    private static BookGeometry.Rect regionOf(String tokenId, BookGeometry.Rect panel,
                                              BookGeometry.Rect raised, BookGeometry.Rect row,
                                              BookGeometry.Rect line, int nodeX, int nodeY,
                                              BookGeometry.Rect button, BookGeometry.Rect tooltip) {
        ThemeToken token = ThemeToken.byId(tokenId);
        if (token == null) {
            return null;
        }
        return switch (token.group()) {
            case SURFACE -> panel;
            case TEXT -> BookGeometry.Rect.at(raised.x(), raised.bottom() + 4, raised.width() - 60, 34);
            case STATE -> BookGeometry.Rect.at(nodeX, nodeY, NODE, NODE);
            case GRAPH -> BookGeometry.Rect.at(line.x() - 2, line.y() - 14,
                    Math.max(0, line.width() + 4), 30);
            case ROW -> row;
            case SCROLL -> BookGeometry.Rect.at(panel.right() - 7, panel.y() + 2, 7,
                    Math.max(0, panel.height() - 4));
            case OVERLAY -> tooltip;
            case CONTROL -> button;
        };
    }

    /** A one-pixel ring that follows a rectangle, drawn just outside it. */
    private static void ring(GuiRenderer r, BookGeometry.Rect rect) {
        int colour = ArmatureTheme.selectedRing();
        r.fill(rect.x() - 1, rect.y() - 1, rect.right() + 1, rect.y(), colour);
        r.fill(rect.x() - 1, rect.bottom(), rect.right() + 1, rect.bottom() + 1, colour);
        r.fill(rect.x() - 1, rect.y(), rect.x(), rect.bottom(), colour);
        r.fill(rect.right(), rect.y(), rect.right() + 1, rect.bottom(), colour);
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static Slot onScreen(Viewport view, Slot slot) {
        return new Slot(slot.key(), view.screenX(slot.x()), view.screenY(slot.y()),
                slot.width(), slot.height());
    }

    private static int textY(Slot slot, Slot onScreen, GuiRenderer r) {
        return onScreen.y() + (slot.height() - r.lineHeight()) / 2;
    }

    private static Measure textMeasure(GuiRenderer r) {
        return Measure.of(r::textWidth, r.lineHeight());
    }
}
