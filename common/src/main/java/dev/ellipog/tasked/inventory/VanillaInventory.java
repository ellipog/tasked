package dev.ellipog.tasked.inventory;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The vanilla inventory: {@code PlayerInventory.add}, and a model of it that answers before it runs.
 *
 * <p>{@link InventorySpace} is the model and carries the semantics — read from the 1.21.1 artefact,
 * not assumed. This class is only the two adapters: a player's slots into the model, and the real
 * insert whose remainder is handed back.
 *
 * <h2>The remainder</h2>
 *
 * <p>{@code Inventory.add} mutates the stack it is given down to what did not fit, and returns whether
 * it emptied. It never spawns an item entity — the caller decides what to do with the remainder, which
 * is what lets the strict path refuse instead of spill.
 */
public final class VanillaInventory implements InventoryAccess {

    public static final VanillaInventory INSTANCE = new VanillaInventory();

    private VanillaInventory() {
    }

    @Override
    public boolean fitsAll(ServerPlayer player, List<ItemStack> stacks) {
        List<InventorySpace.Stack> needs = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                needs.add(InventorySpaces.stack(stack));
            }
        }
        return InventorySpaces.of(player).fitsAll(needs);
    }

    @Override
    public ItemStack insert(ServerPlayer player, ItemStack stack) {
        ItemStack work = stack.copy();
        player.getInventory().add(work);
        return work;
    }
}
