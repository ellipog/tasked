package dev.ellipog.tenet.inventory;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * A player's inventory as {@link InventorySpace} sees it.
 *
 * <h2>The one place a slot index is written down, and why these two numbers</h2>
 *
 * <p>Vanilla's {@code PlayerInventory} is three lists laid end to end: {@code items} (36 slots, the
 * main inventory and hotbar), {@code armor} (4) and {@code offhand} (1). Automatic insertion reads
 * them asymmetrically, and that asymmetry is the whole reason this adapter exists rather than the
 * model simply being handed "all 41 slots":
 *
 * <ul>
 *   <li>An empty slot may only be one of the <b>36 main slots</b> — {@code getFreeSlot} walks
 *       {@code items} and nothing else, so an empty chestplate slot is never capacity for a reward.</li>
 *   <li>A <b>merge</b> may also use the offhand: {@code getSlotWithRemainingSpace} checks slot 40
 *       before it walks {@code items}. So slot 40 is passed in as a merge-only slot.</li>
 *   <li>The armor slots are never a target of automatic insertion at all, so they are not passed in.</li>
 * </ul>
 *
 * <p>A stack's key is a single-count copy of itself: {@code ItemStack.equals} compares components and
 * count, so one-copy keys compare exactly the way vanilla's merge test
 * ({@code isSameItemSameComponents}) does, without a wrapper type of our own.
 */
final class InventorySpaces {

    /** The main inventory and hotbar: the only slots an empty stack may be placed into. */
    static final int MAIN_SLOTS = 36;

    /** The offhand, which automatic insertion merges into but never places into. */
    static final int OFFHAND_SLOT = 40;

    /** A key no real stack can equal. Only ever seen on an empty slot, which the merge pass skips. */
    private static final Object EMPTY = new Object();

    private InventorySpaces() {
    }

    /** The space in force: the main slots, then the offhand as merge-only. */
    static InventorySpace of(net.minecraft.server.level.ServerPlayer player) {
        Inventory inventory = player.getInventory();
        List<InventorySpace.Slot> slots = new ArrayList<>(MAIN_SLOTS + 1);
        for (int i = 0; i < MAIN_SLOTS; i++) {
            slots.add(slot(inventory.getItem(i), true));
        }
        slots.add(slot(inventory.getItem(OFFHAND_SLOT), false));
        return InventorySpace.of(slots);
    }

    /** One stack as a need: a one-copy key, its count, and its own max. */
    static InventorySpace.Stack stack(ItemStack stack) {
        return new InventorySpace.Stack(stack.copyWithCount(1), stack.getCount(),
                stack.getMaxStackSize());
    }

    private static InventorySpace.Slot slot(ItemStack stack, boolean insertable) {
        if (stack.isEmpty()) {
            // An empty slot's max is unused: placement takes the incoming stack's own max, because
            // that is what vanilla's addResource does when it fills a free slot.
            return new InventorySpace.Slot(EMPTY, 0, 0, insertable);
        }
        return new InventorySpace.Slot(stack.copyWithCount(1), stack.getCount(),
                stack.getMaxStackSize(), insertable);
    }
}
