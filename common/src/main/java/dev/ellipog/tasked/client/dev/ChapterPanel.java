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

}
