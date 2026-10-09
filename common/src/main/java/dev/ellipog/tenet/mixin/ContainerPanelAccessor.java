package dev.ellipog.tenet.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The live corner and size of whatever container is open.
 *
 * <h2>Why a mixin, when the inventory panel is recomputed instead</h2>
 *
 * <p>{@code InventoryPanel} recomputes the inventory's corner from the numbers the game centres from
 * rather than reading {@code leftPos}/{@code topPos}, because those fields are protected and this
 * project had no mixins to borrow them through. That works for the inventory because its size is a
 * constant: survival is 176x166, creative is 195x136, and the recipe book's push is public knowledge.
 *
 * <p>A chest, a furnace and a modded interface have no constants to recompute from. Their sizes live
 * in the screen instance -- set by each screen's own constructor, and any size a mod author chose --
 * so the only exact reading is the fields themselves. Four getters, no behaviour, client-only: this
 * is the smallest mixin that earns its place.
 *
 * <h2>Why an interface with accessors rather than anything that runs</h2>
 *
 * <p>Because there is nothing to run. An accessor mixin adds no code to the game, redirects no call
 * and wraps no frame: it only widens who may read four fields that already exist. A screen that
 * overrode {@code init} to put its panel somewhere uncentred is still read correctly, because the
 * reading happens after {@code init} ran rather than from the formula {@code init} usually follows.
 */
@Mixin(AbstractContainerScreen.class)
public interface ContainerPanelAccessor {

    /** The panel's left edge in the window, in GUI pixels. */
    @Accessor("leftPos")
    int tenet$leftPos();

    /** The panel's top edge in the window, in GUI pixels. */
    @Accessor("topPos")
    int tenet$topPos();

    /** The panel's width, as the screen's own constructor set it. */
    @Accessor("imageWidth")
    int tenet$imageWidth();

    /** The panel's height, as the screen's own constructor set it. */
    @Accessor("imageHeight")
    int tenet$imageHeight();
}
