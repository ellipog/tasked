package dev.ellipog.tasked.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Whether a set of stacks fits in an inventory, simulated without touching it.
 *
 * <h2>The semantics are vanilla's, read from the artefact rather than assumed</h2>
 *
 * <p>This mirrors {@code PlayerInventory.add} exactly, and every rule below was read out of the
 * 1.21.1 sources (see {@code .utils/mc.py}) rather than guessed:
 *
 * <ul>
 *   <li>An <b>empty slot</b> is only ever one of the 36 main slots ({@code items}, 0-35). The armor
 *       slots are never a target, and the offhand is not a target for a new stack either.</li>
 *   <li>A <b>merge</b> may also use the offhand (slot 40), when it already holds a matching stack
 *       with room. That is why a slot carries an {@code insertable} flag instead of the model just
 *       being a list of 36.</li>
 *   <li>Two stacks merge only when {@code ItemStack.isSameItemSameComponents} holds and the
 *       destination is stackable with room left — components included, so a renamed diamond never
 *       merges with a plain one.</li>
 *   <li>The whole stack is placed at once, split across slots at each slot's own max stack size.</li>
 * </ul>
 *
 * <p><b>Game-free by design.</b> A slot is a {@code (key, count, max)} triple, so a test uses string
 * keys where the game uses items, and the one adapter that reads a {@code PlayerInventory} lives in
 * {@link InventorySpaces}.
 */
public final class InventorySpace {

    /** One slot as the model sees it. {@code insertable} false is a merge-only slot — the offhand. */
    public record Slot(Object key, int count, int max, boolean insertable) {
    }

    /** One stack to place. */
    public record Stack(Object key, int count, int max) {
    }

    private final List<Slot> slots;

    private InventorySpace(List<Slot> slots) {
        this.slots = slots;
    }

    /** A space over a copy of these slots; the model mutates its own copy and never the caller's. */
    public static InventorySpace of(List<Slot> slots) {
        Objects.requireNonNull(slots, "slots");
        return new InventorySpace(new ArrayList<>(slots));
    }

    /**
     * Whether every stack fits, in order, each placed before the next is asked about.
     *
     * <p>Order matters and is the point: a table that rolls six stacks must be checked as a batch,
     * because the sixth may only fit in a slot the first five left behind.
     */
    public boolean fitsAll(List<Stack> stacks) {
        Objects.requireNonNull(stacks, "stacks");
        for (Stack stack : stacks) {
            if (!place(stack)) {
                return false;
            }
        }
        return true;
    }

    /** One stack, merged first and then placed — vanilla's two passes over the same slots. */
    private boolean place(Stack stack) {
        int remaining = stack.count();
        if (remaining <= 0) {
            return true;
        }
        // Pass one: any slot holding a matching stack with room, the offhand included.
        for (int i = 0; i < slots.size() && remaining > 0; i++) {
            Slot slot = slots.get(i);
            if (slot.count() > 0 && slot.count() < slot.max() && slot.key().equals(stack.key())) {
                int room = Math.min(slot.max() - slot.count(), remaining);
                slots.set(i, new Slot(slot.key(), slot.count() + room, slot.max(), slot.insertable()));
                remaining -= room;
            }
        }
        // Pass two: empty insertable slots, split at the incoming stack's own max.
        for (int i = 0; i < slots.size() && remaining > 0; i++) {
            Slot slot = slots.get(i);
            if (slot.count() == 0 && slot.insertable()) {
                int placed = Math.min(stack.max(), remaining);
                slots.set(i, new Slot(stack.key(), placed, stack.max(), true));
                remaining -= placed;
            }
        }
        return remaining == 0;
    }
}
