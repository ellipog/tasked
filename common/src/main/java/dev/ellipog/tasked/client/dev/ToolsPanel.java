package dev.ellipog.tasked.client.dev;

import dev.ellipog.armature.client.Look;
import dev.ellipog.tasked.client.ClientAppearance;
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

    /**
     * What the theme panel is showing: the selected colour, the last thing that happened, and whether
     * the hex field is on the band.
     *
     * @param selected the colour token being edited, or null
     * @param feedback the panel's one-line status, or null for none
     * @param feedbackIsError whether that status is bad news
     * @param hexEditable true when a colour is selected, so the band leaves room for the field instead of
     *     drawing the value as text -- one place showing the number, and it is the one you can type into
     */
    public record State(String selected, String feedback, boolean feedbackIsError, boolean hexEditable) {
    }

    /**
     * Draws everything that is not a widget.
     *
     * <p>The tabs, the switches' buttons, the colour rows, the channel steppers and Revert/Save are
     * widgets and are drawn by the widget pass — this draws the panel they sit on, their labels, and the
     * colour that a widget cannot draw. The tab strip replaced the title row, so the panel's own name is
     * nowhere: the tabs <i>are</i> the top row, and two of them name the two panels the dock holds.
     */
    public static void draw(GuiRenderer r, ToolsLayout.Frame frame, Viewport list, Layout layout,
                            List<ToolsLayout.Action> rows, State state, int mouseX, int mouseY) {
        ArmatureTheme.panel(r, frame.panel().x(), frame.panel().y(), frame.panel().width(),
                frame.panel().height(), ArmatureTheme.panel(), ArmatureTheme.panelEdge());

        if (state.feedback() != null && !state.feedback().isEmpty()) {
            r.text(Measure.truncate(state.feedback(), frame.feedback().width(), textMeasure(r)),
                    frame.feedback().x(), frame.feedback().y(),
                    state.feedbackIsError() ? ArmatureTheme.blocked() : ArmatureTheme.faint());
        }

        drawPreview(r, frame.preview(), state.selected(), mouseX, mouseY);

        Measure measure = textMeasure(r);
        BookGeometry.Rect listRect = frame.list();
        try (GuiRenderer.Scoped clip = r.clip(listRect.x(), listRect.y(), listRect.right(),
                listRect.bottom())) {
            for (ToolsLayout.Action row : rows) {
                Slot slot = layout.slot(row.key());
                if (slot == null) {
                    continue;
                }
                Slot onScreen = ToolsLayout.onScreen(list, slot);
                // A switch on the kind rather than a chain of `isControl()` tests, and exhaustive on purpose:
                // the radius row used to fall through this to the *switch label* branch below, because its
                // kind is STEPPER and the middle test asked `kind == ROW`. The row then drew its label and
                // nothing else -- no arrows, no number -- while the click path, which reads the kind not at
                // all, worked fine: *"i can click them but not see them"*. A switch over the enum means the
                // next kind added cannot quietly become a label.
                switch (row.kind()) {
                    case HEADING -> drawHeading(r, row, slot, onScreen, measure);
                    case ROW -> drawRow(r, row, slot, onScreen, measure, state, mouseX, mouseY);
                    case STEPPER -> drawStepper(r, row, slot, onScreen, measure, mouseX, mouseY);
                    case SWITCH -> drawSwitchLabel(r, row, slot, onScreen, measure);
                    // Loud rather than quiet, because quiet is what happened: the radius row fell through
                    // this dispatch into the label branch and drew as a label with no controls, and nothing
                    // anywhere said so. A new kind now fails the first time it is drawn instead.
                    default -> throw new IllegalStateException("no drawing for row kind " + row.kind());
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
        boolean section = row.key().equals(ToolsLayout.COLOUR_SECTION);
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
        boolean hovered = onScreen.contains(mouseX, mouseY);


        if (token != null) {
            int argb = ClientAppearance.LOOK.main().colour(token);
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
        }
    }

    // ------------------------------------------------------------------
    // The band
    // ------------------------------------------------------------------

    /**
     * One of the radius row's two arrows: a raised box with a minus or a plus in it.
     *
     * <p>Drawn rather than a widget, because the list places one widget per row and this row already has
     * one — see the note at the call site. Brighter under the pointer, because a control nobody can see is a
     * control not pressed, and this pair has already been invisible once. Drawn through
     * {@code ArmatureTheme.panel} so it rounds with the theme like every other surface, which matters more
     * here than anywhere: this is the control that sets that radius.
     */
    private static void arrow(GuiRenderer r, Slot slot, String way, int mouseX, int mouseY) {
        boolean hovered = slot.contains(mouseX, mouseY);
        int face = hovered ? Colour.lerp(ArmatureTheme.raised(), ArmatureTheme.title(), 0.12F)
                : ArmatureTheme.raised();
        ArmatureTheme.panel(r, slot.x(), slot.y(), slot.width(), slot.height(), face,
                ArmatureTheme.panelEdge());
        String glyph = way.equals("down") ? "\u2212" : "+";
        r.text(glyph, slot.x() + (slot.width() - r.textWidth(glyph)) / 2 + GLYPH_NUDGE_X,
                slot.y() + (slot.height() - r.lineHeight()) / 2 + GLYPH_NUDGE_Y,
                hovered ? ArmatureTheme.title() : ArmatureTheme.body());
    }

    /**
     * Where a glyph actually sits inside a small box, as opposed to where centring it puts it.
     *
     * <p>Measured from a screenshot rather than derived, and stated as the report gave it: *"plus and minus
     * not centered inside buttons, move one pixel to right and 2 down"*. Centring a glyph means centring its
     * <i>box</i>, and a font's box carries its descender space — so `+` and `−`, which have neither a
     * descender nor much of an ascent, land high and left of the eye's centre. The two offsets are here
     * rather than inline so the next control that draws its own glyph can start from this answer instead of
     * measuring it again.
     */
    private static final int GLYPH_NUDGE_X = 1;
    private static final int GLYPH_NUDGE_Y = 2;

    /** A switch's label. Its button is a widget, in the strip the row reserved. */
    private static void drawSwitchLabel(GuiRenderer r, ToolsLayout.Action row, Slot slot, Slot onScreen,
                                        Measure measure) {
        r.text(Measure.truncate(row.label(),
                        Math.max(0, slot.width() - ToolsLayout.STRIP_WIDTH - 8), measure),
                onScreen.x() + 2, textY(slot, onScreen, r), ArmatureTheme.body());
    }

    /**
     * The shape's row: its name, two arrows, and the number between them.
     *
     * <h2>Why the whole row is drawn here, label included</h2>
     *
     * <p>Because a widget for the label would sit over the arrows and take their presses — the widget pass
     * runs before the screen's own click handling — so the row has no widget at all and every pixel of it
     * comes from this method and the one test that reads the kind.
     *
     * <p>The arrows come from {@link ToolsLayout#stepper} of the row as placed on screen, which is the same
     * call the press is tested with ({@code ToolsLayout.radiusStepAt}): one derivation, so what is drawn is
     * what is pressed. An override is said with the number's colour rather than a marker — bright when it is
     * the player's, faint when it is still the theme's — so nothing has to explain an asterisk.
     */
    private static void drawStepper(GuiRenderer r, ToolsLayout.Action row, Slot slot, Slot onScreen,
                                    Measure measure, int mouseX, int mouseY) {
        Slot joined = new Slot(row.key(), onScreen.x(), onScreen.y(), slot.width(), slot.height());
        Map<String, Slot> arrows = ToolsLayout.stepper(joined);
        Slot between = ToolsLayout.stepperValue(joined);

        // The label gets the room left of the first arrow, so it can never run under one.
        int room = Math.max(0, arrows.get("down").x() - onScreen.x() - 6);
        r.text(Measure.truncate(row.label(), room, measure), onScreen.x() + 2, textY(slot, onScreen, r),
                ArmatureTheme.body());

        for (String way : List.of("down", "up")) {
            arrow(r, arrows.get(way), way, mouseX, mouseY);
        }
        String number = String.valueOf(ClientAppearance.LOOK.radius());
        r.text(number, between.x() + (between.width() - r.textWidth(number)) / 2,
                textY(slot, onScreen, r) + GLYPH_NUDGE_Y,
                ClientAppearance.LOOK.radiusChosen() ? ArmatureTheme.title() : ArmatureTheme.faint());
    }

    /** The selected colour, its channels with their numbers, and the two actions. */
    private static void drawBand(GuiRenderer r, ToolsLayout.Frame frame, State state, Measure measure) {
        String token = state.selected();
        boolean radius = ToolsLayout.RADIUS.equals(token);
        int argb = token == null || radius ? 0 : ClientAppearance.LOOK.main().colour(token);

        BookGeometry.Rect swatch = frame.swatch();
        int box = swatch.height() - 4;
        int boxY = swatch.y() + 2;
        r.fill(swatch.x(), boxY - 1, swatch.x() + box + 2, boxY + box + 1, ArmatureTheme.panelEdge());
        if (token != null) {
            r.fill(swatch.x() + 1, boxY, swatch.x() + 1 + box, boxY + box, argb);
        }

        // One line, and the value is in the field when there is a field: a name and a hex code *and* a box
        // holding the same hex code is the same fact twice.
        BookGeometry.Rect hexBox = ToolsLayout.hexField(frame.swatch(), state.hexEditable());
        int nameRoom = hexBox == null ? swatch.width() - box - 10 : hexBox.x() - swatch.x() - box - 12;
        String name;
        if (radius) {
            name = "Border radius";
        }
        else {
            name = token == null ? "Press a colour to edit it"
                    : labelOf(token) + (state.hexEditable() ? "" : "  " + String.format("#%08X", argb));
        }
        r.text(Measure.truncate(name, Math.max(0, nameRoom), measure),
                swatch.x() + box + 8, swatch.y() + (swatch.height() - r.lineHeight()) / 2,
                token == null && !radius ? ArmatureTheme.faint() : ArmatureTheme.body());

        // The channels: the letter, the value, and two buttons that are widgets. A radius selection uses
        // the first line and nothing else -- and draws no letter, because its name is in the line above.
        Map<String, Slot> beats = ToolsLayout.beats(frame.channels());
        if (radius) {
            Slot value = beats.get("beat:R");
            String number = String.valueOf(ClientAppearance.LOOK.radius());
            r.text(number, value.x() + (value.width() - r.textWidth(number)) / 2,
                    value.y() + (value.height() - r.lineHeight()) / 2, ArmatureTheme.title());
            return;
        }
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

    /** The radius the theme underneath asks for, for the band's own reference. */
    private static int themeRadius() {
        var theme = dev.ellipog.armature.client.ui.Themes.any(ClientAppearance.LOOK.currentName());
        return theme == null ? 0 : theme.cornerRadius();
    }

    /** A token's name, from the catalogue rather than from its id. */
    private static String labelOf(String tokenId) {
        ThemeToken token = ThemeToken.byId(tokenId);
        return token == null ? tokenId : token.label();
    }

    // ------------------------------------------------------------------
    // The preview
    // ------------------------------------------------------------------

    /** The sample, drawn from the parts the layout computed. */
    private static void drawPreview(GuiRenderer r, BookGeometry.Rect preview, String selected, int mouseX,
                                    int mouseY) {
        ToolsLayout.Preview sample = ToolsLayout.previewParts(preview);
        int w = preview.width();
        int h = preview.height();
        r.fill(sample.canvas().x(), sample.canvas().y(), sample.canvas().right(), sample.canvas().bottom(),
                ArmatureTheme.canvas());
        if (w < 60 || h < 60) {
            return;
        }

        r.fill(sample.line().x(), sample.line().y(), sample.line().right(), sample.line().bottom(),
                ArmatureTheme.lineDone());
        node(r, sample.nodeA(), ArmatureTheme.nodeEdgeAvailable(), ArmatureTheme.hoverRing());
        node(r, sample.nodeB(), ArmatureTheme.nodeEdgeComplete(), 0);

        // The card is square and its contents are round, exactly as the book draws them: the outer panel
        // has no radius and every surface inside it follows the theme's. The first version drew all of
        // them with flat fills, so a theme with a radius of six showed a sample of square corners -- the
        // report was "some corners staying square under bent corners".
        ArmatureTheme.panel(r, sample.card().x(), sample.card().y(), sample.card().width(),
                sample.card().height(), ArmatureTheme.panel(), ArmatureTheme.panelEdge());
        ArmatureTheme.fillSurface(r, sample.raised().x(), sample.raised().y(), sample.raised().width(),
                sample.raised().height(), ArmatureTheme.raised(), ArmatureTheme.CORNERS_TOP);
        r.fill(sample.track().x(), sample.track().y(), sample.track().right(), sample.track().bottom(),
                ArmatureTheme.scrollTrack());
        r.fill(sample.thumb().x(), sample.thumb().y(), sample.thumb().right(), sample.thumb().bottom(),
                ArmatureTheme.scrollThumb());

        int textX = sample.text().x();
        int textY = sample.text().y();
        r.text("Title", textX, textY, ArmatureTheme.title());
        r.text("Body", textX, textY + ToolsLayout.LINE_PITCH, ArmatureTheme.body());
        r.text("faint", textX + r.textWidth("Body") + 5, textY + ToolsLayout.LINE_PITCH,
                ArmatureTheme.faint());

        ArmatureTheme.fillSurface(r, sample.row().x(), sample.row().y(), sample.row().width(),
                sample.row().height(), Colour.translucent(ArmatureTheme.rowHover(), 0.5F),
                ArmatureTheme.CORNERS_ALL);
        r.fill(sample.item().x(), sample.item().y(), sample.item().right(), sample.item().bottom(),
                ArmatureTheme.recessed());
        r.fill(sample.item().x(), sample.item().y(), sample.item().right(), sample.item().y() + 1,
                ArmatureTheme.panelEdge());

        // A rounded surface with a border, drawn as the toolkit draws one: the edge colour fills the whole
        // shape and the face sits one pixel inside it.
        ArmatureTheme.fillSurface(r, sample.button().x(), sample.button().y(), sample.button().width(),
                sample.button().height(), ArmatureTheme.controlEdge(), ArmatureTheme.CORNERS_ALL);
        ArmatureTheme.fillSurface(r, sample.button().x() + 1, sample.button().y() + 1,
                Math.max(0, sample.button().width() - 2), Math.max(0, sample.button().height() - 2),
                ArmatureTheme.raised(), ArmatureTheme.CORNERS_ALL);
        ArmatureTheme.fillSurface(r, sample.tooltip().x(), sample.tooltip().y(), sample.tooltip().width(),
                sample.tooltip().height(), ArmatureTheme.tooltipEdge(), ArmatureTheme.CORNERS_ALL);
        ArmatureTheme.fillSurface(r, sample.tooltip().x() + 1, sample.tooltip().y() + 1,
                Math.max(0, sample.tooltip().width() - 2), Math.max(0, sample.tooltip().height() - 2),
                ArmatureTheme.tooltipFill(), ArmatureTheme.CORNERS_ALL);

        BookGeometry.Rect region = regionOf(selected, sample);
        if (region != null) {
            ring(r, region, ArmatureTheme.selectedRing());
        }

        // And what the pointer would choose, before it is pressed: the same test the click makes, so the
        // sample says which part is under the cursor by pointing back. Drawn under the selection's ring so a
        // part that is both hovered and selected reads as selected.
        ToolsLayout.Hotspot hot = ToolsLayout.hotspotAt(preview, mouseX, mouseY);
        if (hot != null) {
            ring(r, hot.rect(), Colour.alphaOf(ArmatureTheme.title(), 0.35F));
        }
    }

    /** One node: fill, an edge in the state's colour, and a ring when it is the hovered one. */
    private static void node(GuiRenderer r, BookGeometry.Rect rect, int edge, int ringColour) {
        if (ringColour != 0) {
            ArmatureTheme.shapePanel(r, rect.x() - 1, rect.y() - 1, rect.width() + 2,
                    ArmatureTheme.nodeFill(), ringColour, QuestShape.ROUNDED::span);
        }
        ArmatureTheme.shapePanel(r, rect.x(), rect.y(), rect.width(), ArmatureTheme.nodeFill(), edge,
                QuestShape.ROUNDED::span);
    }

    /**
     * Which part of the sample a group's colours paint.
     *
     * <p>Eight groups, eight parts, one entry each -- a colour's group is a fact about this table rather
     * than about where the sample happens to put things. No selection, and a token this build does not
     * know, give no ring: a ring around the wrong thing is worse than none.
     */
    private static BookGeometry.Rect regionOf(String tokenId, ToolsLayout.Preview sample) {
        ThemeToken token = ThemeToken.byId(tokenId);
        if (token == null) {
            return null;
        }
        int pad = 2;
        return switch (token.group()) {
            case SURFACE -> expand(sample.card(), pad);
            case TEXT -> expand(sample.text(), 1);
            case STATE, GRAPH -> BookGeometry.Rect.at(sample.nodeA().x() - pad, sample.nodeA().y() - pad,
                    Math.max(0, sample.line().right() - sample.nodeA().x() + pad),
                    sample.nodeA().height() + pad * 2);
            case ROW -> expand(sample.row(), pad);
            case SCROLL -> BookGeometry.Rect.at(sample.card().right() - ToolsLayout.SAMPLE_INSET - 3,
                    sample.card().y() + 2, 5, Math.max(0, sample.card().height() - 4));
            case OVERLAY -> expand(sample.tooltip(), pad);
            case CONTROL -> expand(sample.button(), pad);
        };
    }

    private static BookGeometry.Rect expand(BookGeometry.Rect rect, int by) {
        return BookGeometry.Rect.at(rect.x() - by, rect.y() - by, rect.width() + by * 2,
                rect.height() + by * 2);
    }

    /** A one-pixel ring that follows a rectangle, drawn just outside it. */
    private static void ring(GuiRenderer r, BookGeometry.Rect rect, int colour) {
        r.fill(rect.x() - 1, rect.y() - 1, rect.right() + 1, rect.y(), colour);
        r.fill(rect.x() - 1, rect.bottom(), rect.right() + 1, rect.bottom() + 1, colour);
        r.fill(rect.x() - 1, rect.y(), rect.x(), rect.bottom(), colour);
        r.fill(rect.right(), rect.y(), rect.right() + 1, rect.bottom(), colour);
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static int textY(Slot slot, Slot onScreen, GuiRenderer r) {
        return onScreen.y() + (slot.height() - r.lineHeight()) / 2;
    }

    private static Measure textMeasure(GuiRenderer r) {
        return Measure.of(r::textWidth, r.lineHeight());
    }
}
