package dev.ellipog.tenet.quest.task;

import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * How many of something a player is carrying — one definition, shared by the tasks and the conditions
 * that ask the same question.
 *
 * <p>The item half started as a private method on {@code ItemTask}. The moment a condition had to
 * answer "how many do they have", a copy would have been a second answer, and the two would have
 * drifted the first time matching changed — which is the failure this class exists to make impossible.
 * The scan stops as soon as {@code required} is reached, so its cost is bounded by the requirement
 * rather than by the size of the inventory.
 */
public final class ItemCounting {

    private ItemCounting() {
    }

    /** Items matching the filter, counted up to {@code required}. */
    public static int countIn(Inventory inventory, ItemStack template, ComponentMatch match, int required) {
        if (template.isEmpty()) {
            return 0;
        }
        int found = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (match.matches(template, stack)) {
                found += stack.getCount();
                if (found >= required) {
                    return found;
                }
            }
        }
        return found;
    }

    /** Items in the tag, counted up to {@code required}. */
    public static int countIn(Inventory inventory, TagKey<Item> tag, int required) {
        int found = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(tag)) {
                found += stack.getCount();
                if (found >= required) {
                    return found;
                }
            }
        }
        return found;
    }
}
