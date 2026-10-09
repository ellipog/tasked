package dev.ellipog.tenet.quest.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TaskCommon;
import dev.ellipog.tenet.quest.TaskContext;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;

import java.util.Set;
import java.util.function.Function;

/**
 * Hand over fluid, measured in millibuckets, carried in the buckets the player holds.
 *
 * <pre>{@code { "type": "tenet:fluid", "fluid": "minecraft:water", "amount": 1000 } }</pre>
 *
 * <p>FTB Quests' fluid task is filled at a task screen block; Tenet has no such block yet, so this
 * is the carried-container path for now — a bucket counts as a thousand millibuckets, and a submit
 * empties buckets and hands the empty ones back. The screen path arrives with the blocks in T11, and
 * the file's shape already carries everything it will need.
 */
public record FluidTask(TaskCommon common, ResourceLocation fluid, int amount) implements QuestTask {

    public static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath(Tenet.MOD_ID, "fluid");

    public static final Set<String> FIELDS = Set.of("fluid", "amount");

    /** One bucket is a thousand millibuckets, which is the unit the file and the row use. */
    public static final int BUCKET = 1000;

    public static final MapCodec<FluidTask> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            TaskCommon.mapCodec(20).forGetter(FluidTask::common),
            ResourceLocation.CODEC.fieldOf("fluid").forGetter(FluidTask::fluid),
            Codec.intRange(1, 1_000_000).fieldOf("amount").forGetter(FluidTask::amount)
    ).apply(instance, FluidTask::new));

    @Override
    public ResourceLocation type() {
        return TYPE;
    }

    /** The bucket item that carries this fluid, or air when the fluid has none. */
    public static Item bucketFor(ResourceLocation fluid) {
        Fluid found = BuiltInRegistries.FLUID.get(fluid);
        return found.getBucket();
    }

    public static final TaskBehaviour<FluidTask> BEHAVIOUR = new TaskBehaviour<>() {

        @Override
        public int required(FluidTask task) {
            return task.amount();
        }

        @Override
        public boolean readsInventory() {
            return true;
        }

        @Override
        public int current(FluidTask task, TaskContext context) {
            Item bucket = bucketFor(task.fluid());
            if (bucket == Items.AIR) {
                return 0;
            }
            int carried = 0;
            var inventory = context.player().getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.is(bucket)) {
                    carried += stack.getCount() * BUCKET;
                }
            }
            return Math.min(carried, task.amount());
        }

        @Override
        public boolean canSubmitByHand(FluidTask task, boolean chapterDefault) {
            return true;
        }

        @Override
        public boolean takesResources(FluidTask task, boolean chapterDefault) {
            // Always: the fluid is what is being asked for, so handing it over is the task.
            return true;
        }

        @Override
        public int take(FluidTask task, ServerPlayer player, int count) {
            Item bucket = bucketFor(task.fluid());
            if (bucket == Items.AIR) {
                return 0;
            }
            int remaining = count;
            var inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize() && remaining >= BUCKET; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!stack.is(bucket)) {
                    continue;
                }
                while (remaining >= BUCKET && !stack.isEmpty()) {
                    stack.shrink(1);
                    remaining -= BUCKET;
                    // The empty bucket comes straight back, as it does out of a crafting table. One
                    // that no longer fits is dropped rather than discarded.
                    ItemStack empty = new ItemStack(Items.BUCKET);
                    if (!player.addItem(empty)) {
                        player.drop(empty, false);
                    }
                }
                inventory.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
            }
            inventory.setChanged();
            return count - remaining;
        }
    };

    public static final Function<FluidTask, TaskDisplay> DISPLAY = task -> {
        // The amount and the fluid in one phrase, so the client can prettify the id inside it: the row
        // reads "Hand in 1000 mB of Water" rather than naming a bucket count and an id.
        String what = task.amount() + " mB of " + task.fluid();
        return TaskDisplay.ofTranslatableText("tenet.task.fluid", "Hand in " + what, what,
                task.bucketCount());
    };

    /** The amount as whole buckets, rounded up for a display that cannot show a bucketless fluid. */
    public int bucketCount() {
        return Math.max(1, (amount + BUCKET - 1) / BUCKET);
    }
}
