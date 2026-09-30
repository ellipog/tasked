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

    /**
     * A row that is itself the control: a theme, a group's name, or a colour.
     *
     * <p>The row's <i>name</i> is not drawn here. Every one of these rows is a widget -- flat, so it
     * paints nothing but its own label -- and the first version drew the name here as well, so every row
     * carried two copies of it a few pixels apart. The screenshot read as a font fault and was a
     * duplicated string.
     */
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
        // The name is the widget's, and the mark for the theme in use is already in that name.
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
     * <h2>What the sample is, and why it changed</h2>
     *
     * <p>The first version was abstract bars: a panel, two strips, three words and some boxes, placed at
     * fixed insets that did not fit the rectangle they were in. It read as a rendering fault -- which it
     * was -- and it answered nothing: nobody could tell which bar was a reward row.
     *
     * <p>This is the two things a theme actually paints, arranged the way the book arranges them: a
     * <b>node</b> with its connecting line on the canvas, and a <b>quest popover</b> below -- a raised
     * title strip, a title, a body line, a faint word, a reward row with an item's square, and a Claim
     * button, with a scrollbar down the card's right edge and a tooltip hanging off its corner. Eight
     * parts for eight groups, and selecting a colour rings the part its group paints.
     *
     * <p>Every position is a fraction of the rectangle it is drawn in, so the sample survives a narrow or
     * short panel. And it is a <b>sample</b>: the shape is always the rounded one, the text is literal and
     * the item is a square, because a preview that claimed to be the screen would be a second description
     * of the screen.
     */
    private static void drawPreview(GuiRenderer r, BookGeometry.Rect preview, String selected) {
        int x = preview.x();
        int y = preview.y();
        int w = preview.width();
        int h = preview.height();
        r.fill(x, y, x + w, y + h, ArmatureTheme.canvas());
        if (w < 60 || h < 60) {
            // No room for a sample: the canvas alone, rather than a smear of overlapping parts.
            return;
        }

        // The nodes, on the canvas, joined by a line: the STATE and GRAPH groups.
        int nodeSize = Math.max(12, Math.min(NODE, h / 4));
        int nodeY = y + h / 5 - nodeSize / 2;
        int firstX = x + w / 8;
        int secondX = x + w / 2;
        BookGeometry.Rect line = BookGeometry.Rect.at(firstX + nodeSize, nodeY + nodeSize / 2 - 1,
                Math.max(6, secondX - firstX - nodeSize), 2);
        r.fill(line.x(), line.y(), line.right(), line.bottom(), ArmatureTheme.lineDone());
        node(r, firstX, nodeY, nodeSize, ArmatureTheme.nodeEdgeAvailable(), ArmatureTheme.hoverRing());
        node(r, secondX, nodeY, nodeSize, ArmatureTheme.nodeEdgeComplete(), 0);

        // The popover, which is what the quest overlay is.
        BookGeometry.Rect card = BookGeometry.Rect.at(x + w / 10, y + h / 2, w - w / 5, h / 2 - 8);
        ArmatureTheme.panel(r, card.x(), card.y(), card.width(), card.height(),
                ArmatureTheme.panel(), ArmatureTheme.panelEdge());

        // Its title strip: the raised surface the header draws.
        BookGeometry.Rect raised = BookGeometry.Rect.at(card.x() + 1, card.y() + 1, card.width() - 2, 11);
        r.fill(raised.x(), raised.y(), raised.right(), raised.bottom(), ArmatureTheme.raised());

        // And its scrollbar, down the right edge.
        BookGeometry.Rect track = BookGeometry.Rect.at(card.right() - 5, card.y() + 4, 3,
                Math.max(0, card.height() - 8));
        r.fill(track.x(), track.y(), track.right(), track.bottom(), ArmatureTheme.scrollTrack());
        r.fill(track.x(), track.y(), track.right(), track.y() + Math.max(3, track.height() / 3),
                ArmatureTheme.scrollThumb());

        // The text: a title, a body line, a faint word.
        int textX = card.x() + 4;
        int textY = raised.bottom() + 3;
        r.text("Title", textX, textY, ArmatureTheme.title());
        r.text("Body", textX, textY + 10, ArmatureTheme.body());
        r.text("faint", textX + r.textWidth("Body") + 5, textY + 10, ArmatureTheme.faint());

        // The reward row: a washed row with an item's square at its left.
        BookGeometry.Rect row = BookGeometry.Rect.at(textX - 2, textY + 20,
                Math.max(0, card.width() - 54), 12);
        r.fill(row.x(), row.y(), row.right(), row.bottom(),
                Colour.translucent(ArmatureTheme.rowHover(), 0.5F));
        int item = Math.max(6, Math.min(10, row.height() - 2));
        r.fill(row.x() + 1, row.y() + 1, row.x() + 1 + item, row.y() + 1 + item,
                ArmatureTheme.recessed());
        r.fill(row.x() + 1, row.y(), row.x() + 1 + item + 1, row.y() + 1, ArmatureTheme.panelEdge());

        // The button beside it, and a tooltip hanging off the card's top corner.
        BookGeometry.Rect button = BookGeometry.Rect.at(card.right() - 44, row.y() - 1, 40, 14);
        ArmatureTheme.panel(r, button.x(), button.y(), button.width(), button.height(),
                ArmatureTheme.raised(), ArmatureTheme.controlEdge());

        BookGeometry.Rect tooltip = BookGeometry.Rect.at(card.right() - 58, card.y() - 13, 56, 12);
        ArmatureTheme.panel(r, tooltip.x(), tooltip.y(), tooltip.width(), tooltip.height(),
                ArmatureTheme.tooltipFill(), ArmatureTheme.tooltipEdge());

        BookGeometry.Rect region = regionOf(selected, card, raised, row, line, firstX, nodeY, nodeSize,
                button, tooltip);
        if (region != null) {
            ring(r, region);
        }
    }

    /** One node: its fill, its edge in a state's colour, and its ring when it is the hovered one. */
    private static void node(GuiRenderer r, int x, int y, int size, int edge, int ringColour) {
        if (ringColour != 0) {
            ArmatureTheme.shapePanel(r, x - 1, y - 1, size + 2, ArmatureTheme.nodeFill(), ringColour,
                    QuestShape.ROUNDED::span);
        }
        ArmatureTheme.shapePanel(r, x, y, size, ArmatureTheme.nodeFill(), edge, QuestShape.ROUNDED::span);
    }

    /**
     * Which part of the sample a group's colours paint.
     *
     * <p>Eight groups, eight parts, one entry each -- so a colour's group is a fact about this table
     * rather than about how the sample happens to be drawn. No selection, and a token this build does not
     * know, give no ring: a ring around the wrong thing is worse than none.
     */
    private static BookGeometry.Rect regionOf(String tokenId, BookGeometry.Rect card,
                                              BookGeometry.Rect raised, BookGeometry.Rect row,
                                              BookGeometry.Rect line, int nodeX, int nodeY, int nodeSize,
                                              BookGeometry.Rect button, BookGeometry.Rect tooltip) {
        ThemeToken token = ThemeToken.byId(tokenId);
        if (token == null) {
            return null;
        }
        int pad = 2;
        return switch (token.group()) {
            case SURFACE -> expand(card, pad);
            case TEXT -> BookGeometry.Rect.at(raised.x(), raised.bottom() + 1,
                    Math.max(0, raised.width() - 4), 24);
            case STATE, GRAPH -> BookGeometry.Rect.at(nodeX - pad, nodeY - pad,
                    Math.max(0, line.right() - nodeX + pad), nodeSize + pad * 2);
            case ROW -> expand(row, pad);
            case SCROLL -> BookGeometry.Rect.at(card.right() - 7, card.y() + 2, 6,
                    Math.max(0, card.height() - 4));
            case OVERLAY -> expand(tooltip, pad);
            case CONTROL -> expand(button, pad);
        };
    }

    private static BookGeometry.Rect expand(BookGeometry.Rect rect, int by) {
        return BookGeometry.Rect.at(rect.x() - by, rect.y() - by, rect.width() + by * 2,
                rect.height() + by * 2);
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
