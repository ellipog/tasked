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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
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
public record ItemTask(TaskCommon common, ItemRef item, Optional<Boolean> consumeItems,
                       ComponentMatch match, boolean onlyFromCrafting) implements QuestTask {

    public static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath(Tasked.MOD_ID, "item");

    /** This type's own fields, for the validator. {@code "type"} and the common fields are added by it. */
    public static final Set<String> FIELDS =
            Set.of("item", "count", "components", "consumeItems", "match", "onlyFromCrafting");

    public static final MapCodec<ItemTask> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            // The common settings first, so every task in a file reads in the same order.
            TaskCommon.MAP_CODEC.forGetter(ItemTask::common),
            // A MapCodec, so "item" and "count" sit flat here rather than nested under "item".
            ItemRef.MAP_CODEC.forGetter(ItemTask::item),
            Codec.BOOL.optionalFieldOf("consumeItems").forGetter(ItemTask::consumeItems),
            // Strict is the default because it is what this type has always matched: a file that says
            // nothing must keep meaning what it meant before the field existed.
            ComponentMatch.CODEC.optionalFieldOf("match", ComponentMatch.STRICT).forGetter(ItemTask::match),
            Codec.BOOL.optionalFieldOf("onlyFromCrafting", false).forGetter(ItemTask::onlyFromCrafting)
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
    private static int countIn(Inventory inventory, ItemStack template, ComponentMatch match, int required) {
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
            ItemStack template = task.item().toStack();
            if (template.isEmpty()) {
                return 0;
            }
            if (task.onlyFromCrafting()) {
                // Counted from the player's own statistics rather than from the inventory, which is
                // the closest this build gets to FTBQ's "only what you crafted": the stat is lifetime
                // and monotonic, so handing the stack away does not un-count it. Documented in the
                // schema as the difference.
                int crafted = context.player().getStats().getValue(Stats.ITEM_CRAFTED.get(template.getItem()));
                return Math.min(task.item().count(), crafted);
            }
            return countIn(context.player().getInventory(), template, task.match(), task.item().count());
        }

        @Override
        public boolean canSubmitByHand(ItemTask task) {
            // Only worth a button when submitting actually does something. A presence-only task
            // completes by itself the moment the player has the items.
            return task.consumeItems().orElse(false);
        }

        @Override
        public boolean takesResources(ItemTask task, boolean chapterDefault) {
            return task.consumes(chapterDefault);
        }

        @Override
        public int take(ItemTask task, ServerPlayer player, int count) {
            ItemStack template = task.item().toStack();
            if (template.isEmpty()) {
                return 0;
            }
            // From the first matching slots, and a slot holding more than is needed is shrunk rather
            // than eaten whole -- taking three of five logs leaves two behind.
            int remaining = count;
            var inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!task.match().matches(template, stack)) {
                    continue;
                }
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
                inventory.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
            }
            inventory.setChanged();
            return count - remaining;
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
