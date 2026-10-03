package dev.ellipog.tasked.quest.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tasked.Tasked;
import dev.ellipog.tasked.quest.QuestTask;
import dev.ellipog.tasked.quest.TaskCommon;
import dev.ellipog.tasked.quest.TaskContext;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Have enough of any item in a tag.
 *
 * <pre>{@code { "type": "tasked:item_tag", "tag": "minecraft:logs", "count": 16 } }</pre>
 *
 * <p>A sibling of {@code tasked:item} rather than a field on it. FTB Quests has no tag field of its
 * own — its tags arrive through filter adapters that store a filter stack in the item field — so
 * there is no file shape to match, and a separate type keeps the most-used task's {@code item}
 * required. The same consumption rules apply: presence checks by default, take the items when
 * {@code consumeItems} says so.
 */
public record ItemTagTask(TaskCommon common, ResourceLocation tag, int count,
                          Optional<Boolean> consumeItems) implements QuestTask {

    public static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath(Tasked.MOD_ID, "item_tag");

    public static final Set<String> FIELDS = Set.of("tag", "count", "consumeItems");

    public static final MapCodec<ItemTagTask> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            TaskCommon.mapCodec(20).forGetter(ItemTagTask::common),
            ResourceLocation.CODEC.fieldOf("tag").forGetter(ItemTagTask::tag),
            Codec.intRange(1, 6400).optionalFieldOf("count", 1).forGetter(ItemTagTask::count),
            Codec.BOOL.optionalFieldOf("consumeItems").forGetter(ItemTagTask::consumeItems)
    ).apply(instance, ItemTagTask::new));

    @Override
    public ResourceLocation type() {
        return TYPE;
    }

    /** Whether this task takes the items, given what the chapter says when the task does not. */
    public boolean consumes(boolean chapterDefault) {
        return consumeItems.orElse(chapterDefault);
    }

    private static boolean matches(ItemStack stack, TagKey<Item> tag) {
        return !stack.isEmpty() && stack.is(tag);
    }

    public static final TaskBehaviour<ItemTagTask> BEHAVIOUR = new TaskBehaviour<>() {

        @Override
        public int required(ItemTagTask task) {
            return task.count();
        }

        @Override
        public int current(ItemTagTask task, TaskContext context) {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, task.tag());
            int found = 0;
            var inventory = context.player().getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (matches(stack, tag)) {
                    found += stack.getCount();
                    if (found >= task.count()) {
                        return found;
                    }
                }
            }
            return found;
        }

        @Override
        public boolean canSubmitByHand(ItemTagTask task) {
            return task.consumeItems().orElse(false);
        }

        @Override
        public boolean takesResources(ItemTagTask task, boolean chapterDefault) {
            return task.consumes(chapterDefault);
        }

        @Override
        public int take(ItemTagTask task, ServerPlayer player, int count) {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, task.tag());
            int remaining = count;
            var inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!matches(stack, tag)) {
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

    public static final Function<ItemTagTask, TaskDisplay> DISPLAY = task ->
            // The tag is the subject; the count rides the row's progress chip.
            TaskDisplay.ofTranslatableText("tasked.task.item_tag", "Hand in #" + task.tag(),
                    "#" + task.tag(), task.count());
}
