package dev.ellipog.tenet.client.hud;

import dev.ellipog.tenet.client.BookGeometry;
import dev.ellipog.tenet.mixin.ContainerPanelAccessor;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * The live corner and size of whatever container is open.
 *
 * <h2>Why this reads instead of recomputing</h2>
 *
 * <p>{@code InventoryPanel} recomputes the inventory's corner because the inventory's size is a
 * constant and the game's centring is public knowledge. A container's size is not a constant: each
 * screen's constructor sets its own, and a modded interface sets whatever its author chose. So this
 * reads the screen's own fields through {@code ContainerPanelAccessor}, after {@code init} ran --
 * which is also why an uncentred panel still reads correctly. The button hook runs on screen
 * initialisation on both loaders, so the numbers are final by the time anyone asks.
 *
 * <h2>Why null rather than a guess</h2>
 *
 * <p>Because a guess draws the button somewhere nobody asked for. If the mixin did not apply, the
 * cast below throws and the caller keeps the ghost-resolved place -- the button draws where the
 * closed-book inventory puts it, which is the behaviour that shipped before the push. A missing
 * button gap is a worse fault than a present button without one, and a stack trace in a chest is
 * worse than both.
 */
public final class ContainerPanel {

    private ContainerPanel() {
    }

    /**
     * The open container's panel in the window, or null when it cannot be read.
     *
     * <p>Null on a non-container screen, and null when the accessor did not apply: both mean the
     * caller should fall back to the ghost rather than invent a corner.
     */
    public static BookGeometry.Rect rect(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen)) {
            return null;
        }
        try {
            ContainerPanelAccessor access = (ContainerPanelAccessor) screen;
            return BookGeometry.Rect.at(access.tenet$leftPos(), access.tenet$topPos(),
                    access.tenet$imageWidth(), access.tenet$imageHeight());
        }
        catch (RuntimeException e) {
            return null;
        }
    }
}
