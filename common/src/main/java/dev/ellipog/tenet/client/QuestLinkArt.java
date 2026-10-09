package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.armature.client.ui.shape.Shape;
import dev.ellipog.tenet.progress.QuestState;
import dev.ellipog.tenet.quest.QuestLink;

import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * How a quest link is drawn: a node like any other, except that it is not a quest.
 *
 * <h2>Why this is its own class, like {@code QuestNodeArt}</h2>
 *
 * <p>For the reason that class's own note gives: the canvas is a screen no test can instantiate,
 * so drawing that lives in it is drawing nothing can assert. Everything here takes plain values —
 * a renderer, a viewport, an icon the caller resolved, a state the caller read — which means a
 * recording renderer can be handed the same calls a client would get, and "a link wears its
 * target's wash", "a hovered link wears the ring" and "a circle's corners do not answer" become
 * assertions rather than screenshots.
 *
 * <h2>What the caller decides and what this decides</h2>
 *
 * <p>The caller decides <i>which</i>: which links exist, which of them a reader may see, where the
 * target has got to, and what its icon is. This decides <i>how</i>: the box, the ring, the wash —
 * through {@link QuestNodeArt}, which is what makes a link the same picture as the node it
 * mirrors rather than a second description of one. A link that drew its own node would be exactly
 * the drift {@code QuestNodeArt} exists to stop: the canvas would gain a wash and the mirror
 * would not.
 *
 * <p>Nothing here reads or changes a player's progress: the state arrives as a value, and the
 * only thing this does with it is choose inks — the whole of the relation between a marker and
 * the questline, and the reason a link is never counted for anything.
 */
public final class QuestLinkArt {

    private QuestLinkArt() {
    }

    /**
     * Everything about this frame that this class needs and does not hold.
     *
     * @param renderer what draws
     * @param view     the canvas's viewport, which turns a content coordinate into a screen one
     */
    public record Frame(GuiRenderer renderer, Viewport view) {
    }

    /**
     * One link's box on screen, in the pixels a press arrives in.
     *
     * <p>The element art's own box, because a box and a hit test over the same numbers must
     * describe the same pixels — and a link's box is a node's box, measured exactly the way a
     * quest node's is.
     */
    public record Slot(QuestLink link, CanvasElementArt.Box box, Shape geometry) {
    }

    /**
     * A link's drawn box: its corner on screen, and its size at this zoom.
     *
     * <p>The floor is the node's own: twelve pixels is the smallest a node can be drawn and still
     * be a target, reached by zooming out rather than by a file. A file's 16..512 range is the
     * number on screen times the zoom, with nothing clamped away — the same reading a quest node
     * gets, so a link that says nothing draws exactly as its target does.
     */
    public static CanvasElementArt.Box boxOf(QuestLink link, Viewport view) {
        int size = Math.max(12, Math.round(link.size() * view.scale()));
        int x = view.screenX(link.x());
        int y = view.screenY(link.y());
        return new CanvasElementArt.Box(x, y, x + size, y + size);
    }

    /**
     * Draws one link with its corner at its box, wearing its target's state.
     *
     * @param icon  the target's icon; null or empty for none, which is what the stand-in block is
     *              for. Null is accepted because the game-free tests cannot build a stack at all.
     * @param texture the target's texture path when its icon is a texture, and empty otherwise
     * @param state how far the target has got: the edge and the wash, and nothing else
     * @param ring  the hover or selection ring's colour, or 0 for none. Links are never flashed — a
     *              press opens the target rather than choosing the marker — so the caller passes a hover
     *              ring, a selection ring, or nothing.
     */
    public static void draw(Frame frame, Slot slot, ItemStack icon, String texture, QuestState state,
                            int ring) {
        QuestNodeArt.draw(frame.renderer(), slot.box().left(), slot.box().top(),
                new QuestNodeArt.Look(slot.box().width(), slot.link().shape(), slot.geometry(), icon,
                        texture,
                        dev.ellipog.tenet.quest.QuestLayout.DEFAULT_ICON_SCALE,
                        QuestNodeArt.edgeFor(state), ring, QuestNodeArt.washFor(state)));
    }

    /**
     * The link under the pointer, or null.
     *
     * <p>Reverse order, so the link drawn last — and therefore on top — is the one picked. The
     * bounding box first as a filter, then the shape's own spans: a circle's corners are outside
     * it, and a bounding-box hit test would let a click land on a link's transparent corner. That
     * is the node's own rule, because a link is a node where the pointer is concerned.
     */
    public static Slot at(List<Slot> slots, double mouseX, double mouseY) {
        for (int i = slots.size() - 1; i >= 0; i--) {
            Slot slot = slots.get(i);
            CanvasElementArt.Box box = slot.box();
            if (mouseX < box.left() || mouseX >= box.right() || mouseY < box.top()
                    || mouseY >= box.bottom()) {
                continue;
            }
            if (slot.geometry().contains(mouseX, mouseY, box.left(), box.top(), box.width())) {
                return slot;
            }
        }
        return null;
    }
}
