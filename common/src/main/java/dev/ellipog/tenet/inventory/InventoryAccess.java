package dev.ellipog.tenet.inventory;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * How a stack is actually put into a player's inventory, and what is left over.
 *
 * <h2>Why this is a seam and not a call to {@code addItem}</h2>
 *
 * <p>Because the strict claim path asks "does this fit?" <b>before</b> it hands anything over, and a
 * question and an answer are only trustworthy when they come from the same place. So the two methods
 * here are the loader's own transfer: {@code fitsAll} simulates it, {@code insert} performs it and
 * returns the remainder, and a loader that knows about inventories beyond the vanilla one can answer
 * both with the platform's own machinery.
 *
 * <p>The default is {@link VanillaInventory}, which mirrors {@code PlayerInventory.add} exactly —
 * see {@link InventorySpace} for the semantics and where they were read from. Fabric installs that
 * same implementation explicitly; NeoForge installs one that goes through its item-handler API.
 */
public interface InventoryAccess {

    /**
     * Whether all of these stacks fit right now, without changing anything.
     *
     * <p>Sequential: each stack is placed in the simulation before the next is asked about, so a batch
     * is answered the way it will be inserted.
     */
    boolean fitsAll(ServerPlayer player, List<ItemStack> stacks);

    /**
     * Inserts as much as the inventory will take.
     *
     * @return the part that did not fit — empty when all of it did. Callers drop this; nothing here
     *     spawns an entity, matching vanilla's own {@code add}, which leaves the remainder in the stack
     *     for the caller to deal with.
     */
    ItemStack insert(ServerPlayer player, ItemStack stack);
}
