package dev.ellipog.tasked.client.dev;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.tasked.client.BookGeometry;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * The Chapter tab's own drawing: the header band above its rows.
 *
 * <h2>Why the header is not a row</h2>
 *
 * <p>The rows are a scrolling list of the chapter's fields; the header is <i>which chapter</i> those
 * fields are about, so it stays put while they scroll -- and it is the one place the icon's item id
 * can be seen as the item it names, beside the name it belongs to. Drawing it here rather than in the
 * screen keeps the same split every panel in this package has: the screen builds widgets, a class that
 * takes a {@link GuiRenderer} draws, and the rectangles come from {@link ToolsLayout}.
 */
public final class ChapterPanel {

    private ChapterPanel() {
    }

    /**
     * One cycling row's control: an arrow at each end and the value between them.
     *
     * <p>The whole control is drawn here and hit-tested by the screen from the same rectangles
     * ({@link ChapterPanelLayout#choiceArrows}), so no widget ever covers the arrows -- the widget pass
     * takes presses before the screen's own handling, which is the same reason the tools panel's radius
     * stepper has no widget either. The glyphs are the font-safe minus and plus: the triangles this UI
     * first used drew as missing-glyph boxes.
     *
     * @param value the text to show -- already a label, with the unset state named
     * @param unset whether that state is the unset one, which is drawn faint like every inherited value
     */
    public static void drawChoice(GuiRenderer r, Slot band, String value, boolean unset,
                                  int mouseX, int mouseY) {
        Map<String, Slot> arrows = ChapterPanelLayout.choiceArrows(band);
        for (String way : List.of("down", "up")) {
            Slot box = arrows.get(way);
            boolean hot = box.contains(mouseX, mouseY);
            ArmatureTheme.panel(r, box.x(), box.y(), box.width(), box.height(),
                    hot ? Colour.lerp(ArmatureTheme.raised(), ArmatureTheme.title(), 0.12F)
                            : ArmatureTheme.raised(),
                    ArmatureTheme.panelEdge());
            String glyph = way.equals("down") ? "\u2212" : "+";
            r.text(glyph, box.x() + (box.width() - r.textWidth(glyph)) / 2 + 1,
                    box.y() + (box.height() - 8) / 2, hot ? ArmatureTheme.title() : ArmatureTheme.body());
        }
        Slot between = ChapterPanelLayout.choiceValueSlot(band);
        String shown = Measure.truncate(value, between.width(),
                Measure.of(r::textWidth, r.lineHeight()));
        r.text(shown, between.x() + (between.width() - r.textWidth(shown)) / 2,
                band.y() + (band.height() - 8) / 2,
                unset ? ArmatureTheme.faint() : ArmatureTheme.title());
    }

    /**
     * The chapter's identity: its icon, its title and its subtitle, on the band the frame reserved.
     *
     * @param icon   the resolved icon, or empty when the chapter's item is unknown or absent
     * @param iconId the id as the file spells it, for the placeholder below
     */
    public static void drawHeader(GuiRenderer r, BookGeometry.Rect band, ToolsLayout.ChapterHeader parts,
                                  ChapterPanelLayout.Header header, ItemStack icon, String iconId) {
        ArmatureTheme.panel(r, band.x(), band.y(), band.width(), band.height(),
                ArmatureTheme.recessed(), ArmatureTheme.panelEdge());

        if (icon != null && !icon.isEmpty()) {
            r.icon(icon, parts.icon().x(), parts.icon().y(), parts.icon().width());
        }
        else if (!iconId.isEmpty()) {
            // The id names something this client does not have -- a missing mod, or a typo. Drawn as a
            // mark rather than as nothing: an empty box reads as "no icon set", which is a different
            // fact from "set to something absent", and only one of the two is worth chasing.
            r.fill(parts.icon().x(), parts.icon().y(), parts.icon().right(), parts.icon().bottom(),
                    ArmatureTheme.blocked());
            r.centredText("?", parts.icon().x() + parts.icon().width() / 2,
                    parts.icon().y() + (parts.icon().height() - 8) / 2, ArmatureTheme.title());
        }

        Measure measure = Measure.of(r::textWidth, r.lineHeight());
        r.text(Measure.truncate(header.title(), parts.title().width(), measure), parts.title().x(),
                parts.title().y() + (parts.title().height() - 8) / 2, ArmatureTheme.title());
        if (!header.subtitle().isEmpty() && parts.subtitle().height() > 0) {
            r.text(Measure.truncate(header.subtitle(), parts.subtitle().width(), measure),
                    parts.subtitle().x(), parts.subtitle().y() + (parts.subtitle().height() - 8) / 2,
                    ArmatureTheme.faint());
        }
    }
}
