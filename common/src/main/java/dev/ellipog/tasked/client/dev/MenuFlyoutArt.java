package dev.ellipog.tasked.client.dev;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.tasked.client.BookGeometry;
import dev.ellipog.tasked.quest.DependencyStyle;

import java.util.List;

/**
 * What a style flyout looks like: the previews, the captions and the reset chips.
 *
 * <h2>Why the drawing is its own class</h2>
 *
 * <p>The same reason {@code QuestSettingsPanel}'s is: the screen cannot be instantiated by a test, and
 * everything here takes a {@link GuiRenderer} and plain numbers, so a flyout's appearance is one file
 * that can be read at once — and the arithmetic it reads is {@link MenuFlyout}'s, which is asserted.
 *
 * <h2>The preview is the point</h2>
 *
 * <p>A cell's picture is drawn by {@link LineArt} — the same code the canvas draws lines with, not a
 * copy. That is the whole reason this menu can show "Circuit (45° chamfer)" as a shape instead of a
 * name: the preview cannot disagree with the line it promises, because it is the line.
 */
public final class MenuFlyoutArt {

    private MenuFlyoutArt() {
    }

    /** Everything the flyout draws, from the placement the press also reads. */
    public static void draw(GuiRenderer r, MenuFlyout.Placed placed, int mouseX, int mouseY) {
        for (int i = 0; i < placed.rows().size(); i++) {
            drawRow(r, placed.rows().get(i), placed.content().get(i), mouseX, mouseY);
        }
    }

    /** One row: a text row, or a label band with its cells under it. */
    private static void drawRow(GuiRenderer r, BookGeometry.Rect rect, MenuFlyout.Row row,
                                int mouseX, int mouseY) {
        if (row instanceof MenuFlyout.Row.Text text) {
            if (text.action() != null && rect.contains(mouseX, mouseY)) {
                r.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), ArmatureTheme.rowHover());
            }
            int colour = text.action() == null ? ArmatureTheme.faint() : ArmatureTheme.body();
            r.text(fit(r, text.label(), rect.width() - 8), rect.x() + 4,
                    rect.y() + (rect.height() - 8) / 2, colour);
            return;
        }
        MenuFlyout.Row.Cells cells = (MenuFlyout.Row.Cells) row;
        int labelInk = cells.enabled() ? ArmatureTheme.body() : ArmatureTheme.faint();
        r.text(fit(r, cells.label(), rect.width() - MenuFlyout.RESET_WIDTH - 10), rect.x() + MenuFlyout.PAD,
                rect.y() + 1, labelInk);
        BookGeometry.Rect reset = MenuFlyout.resetRect(rect, row);
        if (reset != null) {
            if (reset.contains(mouseX, mouseY)) {
                r.fill(reset.x(), reset.y(), reset.right(), reset.bottom(), ArmatureTheme.rowHover());
            }
            // `\u2190`, not the `\u21ba` this started as: the font has no circled arrow, and a reset
            // chip that draws as the missing-glyph box reads as a rendering fault rather than as a
            // control. `←` is the closest thing the font carries to "back to the chapter default",
            // which is what this chip does -- see BookGeometry.TOOLS_PILL_WIDTH for the measured list.
            r.centredText("\u2190", reset.x() + reset.width() / 2, reset.y() + 1, labelInk);
        }
        for (int i = 0; i < cells.cells().size(); i++) {
            drawCell(r, MenuFlyout.cellRect(rect, i, cells.cells().size()), cells.cells().get(i),
                    cells.enabled(), mouseX, mouseY);
        }
    }

    /** One swatch: its preview, its caption, its hover wash and its selected ring. */
    private static void drawCell(GuiRenderer r, BookGeometry.Rect rect, MenuFlyout.Cell cell,
                                 boolean enabled, int mouseX, int mouseY) {
        if (enabled && rect.contains(mouseX, mouseY)) {
            r.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), ArmatureTheme.rowHover());
        }
        if (cell.selected()) {
            ring(r, rect, ArmatureTheme.selectedRing());
        }
        BookGeometry.Rect preview = BookGeometry.Rect.at(rect.x() + 2, rect.y() + 2,
                Math.max(0, rect.width() - 4), Math.max(0, rect.height() - 11));
        preview(r, preview, cell.preview(), enabled ? ArmatureTheme.line() : ArmatureTheme.faint());
        String label = fit(r, cell.label(), rect.width() - 2);
        int ink = !enabled ? ArmatureTheme.faint()
                : cell.selected() ? ArmatureTheme.body() : ArmatureTheme.faint();
        r.text(label, rect.x() + (rect.width() - r.textWidth(label)) / 2, rect.bottom() - 9, ink);
    }

    /** A one-pixel border inside a cell. */
    private static void ring(GuiRenderer r, BookGeometry.Rect rect, int colour) {
        r.fill(rect.x(), rect.y(), rect.right(), rect.y() + 1, colour);
        r.fill(rect.x(), rect.bottom() - 1, rect.right(), rect.bottom(), colour);
        r.fill(rect.x(), rect.y() + 1, rect.x() + 1, rect.bottom() - 1, colour);
        r.fill(rect.right() - 1, rect.y() + 1, rect.right(), rect.bottom() - 1, colour);
    }

    /**
     * One cell's picture, drawn with the real geometry.
     *
     * <p>Form previews run corner to corner so a Z has a Z to show; every other axis previews along the
     * middle of the cell, because a head or a pattern is read on a line rather than on a slope.
     */
    private static void preview(GuiRenderer r, BookGeometry.Rect box, MenuFlyout.Preview spec,
                                int colour) {
        if (box.width() < 8 || box.height() < 6) {
            return;
        }
        int left = box.x() + 2;
        int right = box.right() - 2;
        int top = box.y() + 1;
        int bottom = box.bottom() - 1;
        int midY = (top + bottom) / 2;
        LineArt.Point acrossFrom = new LineArt.Point(left, bottom);
        LineArt.Point acrossTo = new LineArt.Point(right, top);
        LineArt.Point alongFrom = new LineArt.Point(left, midY);
        LineArt.Point alongTo = new LineArt.Point(right, midY);
        switch (spec) {
            case MenuFlyout.Preview.Form form -> paint(r, LineArt.fills(
                    LineArt.path(form.form(), acrossFrom, acrossTo, 0.35),
                    DependencyStyle.Weight.THIN, DependencyStyle.Dash.SOLID), colour);
            case MenuFlyout.Preview.Head head -> {
                List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.STRAIGHT, alongFrom, alongTo);
                paint(r, LineArt.fills(path, DependencyStyle.Weight.THIN, DependencyStyle.Dash.SOLID),
                        colour);
                paint(r, LineArt.arrows(path, head.head(), DependencyStyle.ArrowPlace.TARGET, 8, 0, 0),
                        colour);
            }
            case MenuFlyout.Preview.Place place -> {
                List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.STRAIGHT, alongFrom, alongTo);
                paint(r, LineArt.fills(path, DependencyStyle.Weight.THIN, DependencyStyle.Dash.SOLID),
                        colour);
                // A third of the cell for a stream, so "a run of heads" reads differently from the two
                // ends of "both" rather than looking like the same picture.
                paint(r, LineArt.arrows(path, DependencyStyle.ArrowHead.CHEVRON, place.place(),
                        Math.max(6, (right - left) / 3), 0, 0), colour);
            }
            case MenuFlyout.Preview.Density density -> {
                List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.STRAIGHT, alongFrom, alongTo);
                paint(r, LineArt.fills(path, DependencyStyle.Weight.THIN, DependencyStyle.Dash.SOLID),
                        colour);
                // Beads rather than chevrons: in a preview's few pixels a chevron enforces its own minimum
                // spacing and the three densities would draw the same; a bead shows the rhythm itself.
                paint(r, LineArt.arrows(path, DependencyStyle.ArrowHead.DOT,
                        DependencyStyle.ArrowPlace.STREAM,
                        Math.max(4, density.density().spacing() / 4), 0, 0), colour);
            }
            case MenuFlyout.Preview.Pattern pattern -> paint(r, LineArt.fills(
                    LineArt.path(DependencyStyle.Form.STRAIGHT, alongFrom, alongTo),
                    DependencyStyle.Weight.THIN, pattern.pattern()), colour);
            case MenuFlyout.Preview.Weight weight -> paint(r, LineArt.fills(
                    LineArt.path(DependencyStyle.Form.STRAIGHT, alongFrom, alongTo),
                    weight.weight(), DependencyStyle.Dash.SOLID), colour);
        }
    }

    private static void paint(GuiRenderer r, List<LineArt.Fill> fills, int colour) {
        for (LineArt.Fill fill : fills) {
            r.fill(fill.x1(), fill.y1(), fill.x2(), fill.y2(), ink(fill.tone(), colour));
        }
    }

    /**
     * The ink one band of a weighted line is drawn with.
     *
     * <p>A conduit's borders are shaded towards black and its core towards white, so the six-pixel trunk
     * reads as a pipe rather than as a fat line; every other weight is the line's own colour. One
     * function, so a preview's pipe and the canvas's pipe cannot disagree about what a conduit looks
     * like — the canvas calls this too.
     */
    public static int ink(LineArt.Tone tone, int colour) {
        return switch (tone) {
            case MAIN -> colour;
            case EDGE -> Colour.shade(colour, -0.45F);
            case CORE -> Colour.shade(colour, 0.35F);
        };
    }

    /** A label cut to a width, the same rule the menu's own labels follow. */
    private static String fit(GuiRenderer r, String label, int room) {
        if (room <= 0 || r.textWidth(label) <= room) {
            return label;
        }
        String cut = label;
        while (cut.length() > 1 && r.textWidth(cut + "\u2026") > room) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "\u2026";
    }
}
