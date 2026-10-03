package dev.ellipog.tasked.client;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.shape.Shape;
import dev.ellipog.tasked.quest.QuestShape;

import net.minecraft.world.item.ItemStack;

/**
 * How a quest node is drawn: one description, shared by the canvas and the settings preview.
 *
 * <h2>Why this is its own class</h2>
 *
 * <p>The node's drawing used to be a private method of {@code QuestBookScreen}, which is a class no test
 * can instantiate and no preview can call. The settings page wants to draw the same node — a preview
 * that drew its own version would be a second description of the thing the author is choosing, and the
 * second description is always the one that drifts: the canvas would gain a wash and the preview would
 * not, or a shape would be added and the preview would keep drawing a square. That is the same fault
 * the tools panel's preview was built to avoid, one level up.
 *
 * <p>So the drawing takes a {@link GuiRenderer} and plain numbers, exactly like {@code QuestPanel} and
 * {@code ToolsPanel}, and both callers hand it the same {@link Look}.
 *
 * <h2>What the caller decides and what this decides</h2>
 *
 * <p>The caller decides <i>state</i>: which edge colour a node's progress gives it, how hovered it is,
 * whether it is selected. This decides <i>art</i>: the ring outside the panel, the panel in the shape,
 * the icon at its fitted box, the block that stands in when there is no icon, and the wash that dims a
 * finished or locked node. Those five steps are the whole of what a node looks like, and they are in
 * this order because each one is drawn over the last.
 */
public final class QuestNodeArt {

    /**
     * The smallest icon box worth drawing an item into.
     *
     * <p>Below this an item is a smudge, and a block in the state colour reads as a node where a smudge
     * reads as a rendering fault. Twelve because an item is sixteen pixels wide at full size and a
     * three-quarter box is twelve.
     */
    public static final int MIN_ITEM_BOX = 12;

    private QuestNodeArt() {
    }

    /**
     * Everything about how one node looks, as plain values.
     *
     * @param size       the node's drawn size in pixels
     * @param shape      the outline's <i>name</i>, which is what says whether a panel is drawn at all
     * @param geometry   the outline's geometry, <b>with any rotation already applied</b>. Resolved by
     *                   the caller rather than here, because a rotation is applied by sampling the shape
     *                   again and a node drawn per frame must not rebuild its table per frame
     * @param icon       the item drawn in the middle; null or empty for no item, which is what the
     *                   stand-in block is for. Null is accepted rather than required to be
     *                   {@code ItemStack.EMPTY} because the game-free tests cannot build a stack at all
     *                   — touching that class bootstraps the item registry, which there is no registry
     *                   to bootstrap — and "no item" is a real state a caller may want to draw.
     * @param iconScale  how much of the largest square that fits the icon fills
     * @param edge       the panel's border colour, from the node's state
     * @param ring       the hover or selection ring's colour, or 0 for none
     * @param wash       the state wash's colour, or 0 for none
     * @param drawsPanel whether the panel is drawn at all — false for {@link QuestShape#NONE}, whose
     *                   node is its icon and whose geometry is still a square for the hit test
     */
    public record Look(int size, QuestShape shape, Shape geometry, ItemStack icon, double iconScale,
                       int edge, int ring, int wash) {
    }

    /** Draws one node with its corner at {@code x, y}. */
    public static void draw(GuiRenderer r, int x, int y, Look look) {
        int size = look.size();
        ArmatureTheme.Spans spans = look.geometry()::spans;

        // The hover and selection ring, drawn FIRST and one pixel larger, so the node's own panel
        // covers all but its outer edge. What shows is a one-pixel ring that follows the shape.
        //
        // It used to be `ArmatureTheme.outline(...)`, a rectangle drawn around a circle. On a round or
        // hexagonal node that is a box drawn round a disc -- which reads as two unrelated things
        // stacked. Following the shape is also what FTB Quests does, and for the same reason: the ring
        // is the node saying "this one", so it has to be the node's shape saying it.
        if (look.ring() != 0) {
            ArmatureTheme.shapePanel(r, x - 1, y - 1, size + 2, ArmatureTheme.nodeFill(), look.ring(),
                    spans);
        }

        // The panel, in the node's own shape. A shape is a row-to-span lookup and nothing else, so the
        // fill, the border, the ring and the hit test all come from one place -- which is why a click
        // lands on exactly the pixels that were drawn and not on a bounding box around them.
        //
        // `none` is the one shape that draws no panel: its node is its icon, and the square its
        // geometry describes is for the hit test and the icon's fit. See `QuestShape.drawsPanel`.
        if (look.shape().drawsPanel()) {
            ArmatureTheme.shapePanel(r, x, y, size, ArmatureTheme.nodeFill(), look.edge(), spans);
        }

        // The icon's corner and its size, from ONE inset -- `iconBox`, not two numbers here.
        //
        // The version that shipped took the size from `shape.iconInset(size)` and the position from the
        // constant `NODE_INSET`, so a 36-pixel item was drawn 3 pixels in from the corner instead of 6:
        // off centre in both axes, with its corner through the rounded outline. Same mistake as the
        // colliding buttons and the label and its room -- one value, two places -- and the fix is the
        // same: compute the pair together, somewhere a caller cannot take one and invent the other.
        int[] iconBox = look.geometry().iconBox(x, y, size, look.iconScale());
        boolean drewItem = look.icon() != null && !look.icon().isEmpty()
                && iconBox[2] >= MIN_ITEM_BOX
                && r.icon(look.icon(), iconBox[0], iconBox[1], iconBox[2]);

        if (!drewItem) {
            // No icon, or one the client cannot resolve, or a node too small to hold one. A block in the
            // state colour still reads as a node in a graph, where an empty one reads as a bug -- and it
            // follows the shape, so a small circle is a small circle rather than a square inside it.
            int inset = Math.max(1, size / 4);
            ArmatureTheme.fillShape(r, x + inset, y + inset, size - inset * 2,
                    (look.edge() & 0x00FFFFFF) | 0xB0000000, spans);
        }

        // The state, as a wash over the node. It used to be a chip with a cross in the node's
        // bottom-right corner, and at node scale that chip was a black square pasted over the artwork.
        // Dimming what is already there says "not yet" without hiding what the quest is, which is the
        // only reason the icon is here.
        //
        // The wash FOLLOWS THE SHAPE, and that is the whole point of drawing it here rather than with a
        // `fill` rectangle over the icon's box. A rectangle over a circular node is a black square on a
        // round thing -- which reads as a rendering glitch rather than as a style. Inset by one so the
        // state-coloured border stays crisp; the item is inside this and is dimmed by it, which is
        // intended.
        if (look.wash() != 0) {
            // Drawn after the item, which is safe: every fill in GuiGraphics ends by flushing the buffer
            // (fill -> flushIfUnmanaged -> flush -> bufferSource.endBatch), so the item is submitted
            // first and the wash lands on top of it. Verified in Stage 4 rather than assumed -- an
            // overlay that draws *under* the thing it overlays is invisible, which is a bug that looks
            // like the overlay was never called.
            //
            // The spans are looked up at `size - 2`, because that is the size this call passes -- a
            // shape is a function of (row, size), not a fixed table, so the same method reference gives
            // the smaller outline for free. That is the design paying for itself.
            ArmatureTheme.fillShape(r, x + 1, y + 1, size - 2, look.wash(), spans);
        }
    }

    /**
     * A node's name, in a box under it — the hover caption and the settings preview's label.
     *
     * <p>Clamped into {@code [left, right]} horizontally and flipped above the node when there is no
     * room below, because the caller that draws a caption is always drawing it inside something. The
     * canvas passes its own rectangle; the settings preview passes the preview pane.
     *
     * @param left   the left edge of the room the caption may use, inclusive
     * @param right  its right edge, exclusive
     * @param bottom its bottom edge, exclusive
     */
    public static void caption(GuiRenderer r, int x, int y, int size, String title, int left,
                               int right, int bottom) {
        int boxWidth = r.textWidth(title) + 10;
        int boxX = Math.max(left + 2, Math.min(x + size / 2 - boxWidth / 2, right - boxWidth - 2));
        int boxY = y + size + 4;
        if (boxY + 14 > bottom) {
            boxY = y - 18;
        }

        ArmatureTheme.panel(r, boxX, boxY, boxWidth, 14, ArmatureTheme.panel(),
                ArmatureTheme.controlEdgeBright());
        r.text(title, boxX + 5, boxY + 3, ArmatureTheme.title());
    }
}
