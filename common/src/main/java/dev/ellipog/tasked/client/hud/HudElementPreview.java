package dev.ellipog.tasked.client.hud;

import dev.ellipog.armature.client.ArmatureButton;
import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.tasked.QuestBook;
import dev.ellipog.tasked.client.BookGeometry;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * One element in the editor: the thing you grab, and the thing that shows what it will look like.
 *
 * <h2>Why it is the real control rather than a picture of one</h2>
 *
 * <p>Because a picture is a second description of an appearance, which is exactly the fault the toolkit's
 * control table exists to prevent: the outside-the-game preview once drew a selected row hover-coloured
 * while the screen drew it flat, and the invented one is the one people looked at. This extends
 * {@link ArmatureButton}, so the preview <i>is</i> the control -- the same fills, the same edge, the same
 * icon box -- and the ring around it is the only thing added. Nothing here knows what a button looks like.
 *
 * <h2>Why the drag needs no help from the screen</h2>
 *
 * <p>Because this is a widget on <b>our own</b> screen. A container screen drops releases and drags -- the
 * reason the real button opens on press and why the last plan needed a clock to notice a release at all --
 * but {@code Screen} focuses the child that consumed a press, and {@code ContainerEventHandler} then hands
 * that child every drag and the release. So moving an element is the ordinary widget path, and the only
 * thing the screen does is decide where a resting element draws.
 */
public final class HudElementPreview extends ArmatureButton {

    private final HudElement element;
    private final HudEditScreen screen;

    /** Whether this gesture has actually moved it, so a click that never moved is not written to the file. */
    private boolean moved;

    HudElementPreview(HudElement element, HudEditScreen screen) {
        super(0, 0, element.width(), element.height(), Component.translatable(element.labelKey()),
                button -> screen.select(element));
        this.element = element;
        this.screen = screen;

        // The one place that knows what an element looks like before anything draws it, and it borrows the
        // real control's own icon rather than describing one. Pinning adds its own arm here.
        if (element == HudElement.INVENTORY_BUTTON) {
            icon(new ItemStack(QuestBook.ITEM));
        }
    }

    /** The element this preview stands for. */
    public HudElement element() {
        return element;
    }

    /**
     * The control, with a ring around it.
     *
     * <p>Drawn here rather than by the screen because a widget cannot draw outside itself and the ring is
     * outside the box by a pixel or two -- and because the ring belongs to the thing it rings: a screen that
     * drew rings for its widget would have to know where every widget ended up, which it already does, twice.
     */
    @Override
    public void draw(GuiRenderer renderer, long nowMillis) {
        // An element that is switched off is still drawn here, and drawn dim: this is the one place a
        // player can see where a hidden thing is, so hiding it here too would make it unreachable.
        ink(HudSettings.on(element) || screen.isHighlighted(element) ? Ink.TITLE : Ink.BLOCKED);

        int ring = screen.isHighlighted(element) ? ArmatureTheme.selectedRing() : ArmatureTheme.panelEdge();
        ArmatureTheme.fillSurface(renderer, getX() - 2, getY() - 2, width + 4, height + 4, ring,
                ArmatureTheme.current().cornerRadius() + 2, ArmatureTheme.CORNERS_ALL);

        super.draw(renderer, nowMillis);
    }

    /**
     * Deliberately not {@code super}: a carried element stays lit while it is carried.
     *
     * <p>{@code ArmatureButton.onDrag} exists to cancel a press that has wandered off the control, so that
     * letting go elsewhere does nothing. A carried HUD element is the opposite case -- the pointer is off the
     * control because the control is following it -- so cancelling would make the thing flicker out from
     * under the cursor.
     */
    @Override
    protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
        moved = true;
        int nextX = getX() + (int) Math.round(dragX);
        int nextY = getY() + (int) Math.round(dragY);
        // Clamped against the window as it is now -- `screen.width`/`height` are read here, per drag event,
        // not remembered from when the widget was built -- and by the same rule the resting position uses, so
        // a drag cannot put the element anywhere a later frame would refuse to draw it.
        setX(HudLayout.placed(nextX, screen.width - width));
        setY(HudLayout.placed(nextY, screen.height - height));
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean acted = super.mouseReleased(mouseX, mouseY, button);
        if (moved) {
            moved = false;
            screen.drop(element);
        }
        return acted;
    }
}
