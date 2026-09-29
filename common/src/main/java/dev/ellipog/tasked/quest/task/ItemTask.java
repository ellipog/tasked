package dev.ellipog.tasked.quest.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.ellipog.tasked.Tasked;
import dev.ellipog.tasked.quest.ItemRef;
import dev.ellipog.tasked.quest.QuestTask;
import dev.ellipog.tasked.quest.TaskCommon;
import dev.ellipog.tasked.quest.TaskContext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.Set;

/**
 * Have enough of an item.
 *
 * <pre>{@code { "type": "tasked:item", "item": "minecraft:oak_log", "count": 8 } }</pre>
 *
 * <h2>{@code consumeItems}, and why it is optional</h2>
 *
 * <p>An item task can either take the items or merely require them. Both are wanted: a tutorial
 * asking you to gather eight logs should probably let you keep them, while a quest that asks for a
 * diamond and gives you a sword in return is a trade, and a trade takes the diamond.
 *
 * <p>It is an {@link Optional} so the chapter's {@code defaultConsumeItems} can fill it in — the
 * inheritance the plan borrowed from FTB Quests. An author of a resource-hungry pack sets it once on
 * the chapter; an author who wants the friendlier behaviour sets nothing.
 */
public record ItemTask(TaskCommon common, ItemRef item, Optional<Boolean> consumeItems) implements QuestTask {

    public static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath(Tasked.MOD_ID, "item");

    /** This type's own fields, for the validator. {@code "type"} and the common fields are added by it. */
    public static final Set<String> FIELDS = Set.of("item", "count", "consumeItems");

    public static final MapCodec<ItemTask> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            // The common settings first, so every task in a file reads in the same order.
            TaskCommon.MAP_CODEC.forGetter(ItemTask::common),
            // A MapCodec, so "item" and "count" sit flat here rather than nested under "item".
            ItemRef.MAP_CODEC.forGetter(ItemTask::item),
            Codec.BOOL.optionalFieldOf("consumeItems").forGetter(ItemTask::consumeItems)
    ).apply(instance, ItemTask::new));

    @Override
    public ResourceLocation type() {
        return TYPE;
    }

    /** Whether this task takes the items, given what the chapter says when the task does not. */
    public boolean consumes(boolean chapterDefault) {
        return consumeItems.orElse(chapterDefault);
    }

    /**
     * Counts matching items in the player's inventory, including the hotbar and the off hand.
     *
     * <p>Strict matching: same item and same components. An enchanted pickaxe is not a plain one, and
     * a shulker box with items inside is not an empty one. Fuzzy matching — ignore the components —
     * is the obvious next field, and it is not here because doing it properly means deciding what
     * "the same" means for every component, and guessing wrong is worse than being strict.
     *
     * <p>Stops as soon as {@code required} is reached, so the cost of the scan is bounded by the
     * requirement rather than by the size of the inventory.
     */
    private static int countIn(Inventory inventory, ItemRef wanted, int required) {
        ItemStack template = wanted.toStack();
        if (template.isEmpty()) {
            return 0;
        }
        int found = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, template)) {
                found += stack.getCount();
                if (found >= required) {
                    return found;
                }
            }
        }
        return found;
    }

    /**
     * The behaviour, in one place.
     *
     * <p>Kept in the task's own file rather than in the registry, so that everything about
     * {@code tasked:item} is readable in one screen.
     */
    public static final TaskBehaviour<ItemTask> BEHAVIOUR = new TaskBehaviour<>() {

        @Override
        public int required(ItemTask task) {
            return task.item().count();
        }

        @Override
        public int current(ItemTask task, TaskContext context) {
            return countIn(context.player().getInventory(), task.item(), task.item().count());
        }

        @Override
        public boolean canSubmitByHand(ItemTask task) {
            // Only worth a button when submitting actually does something. A presence-only task
            // completes by itself the moment the player has the items.
            return task.consumeItems().orElse(false);
        }
    };

    /**
     * The item and its count, with no label — the client draws the item's own name.
     *
     * <p>Not the name resolved here. The server does not know what language the client is in, and a
     * name baked into English on the wire would be wrong for every player not playing in it.
     */
    public static final java.util.function.Function<ItemTask, TaskDisplay> DISPLAY =
            task -> TaskDisplay.ofItem(task.item(), task.item().count());
}
