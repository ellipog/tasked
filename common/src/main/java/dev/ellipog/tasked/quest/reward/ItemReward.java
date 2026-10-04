package dev.ellipog.tasked.quest.reward;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.ellipog.tasked.Tasked;
import dev.ellipog.tasked.quest.ItemRef;
import dev.ellipog.tasked.quest.QuestReward;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Set;

/**
 * Give the player some items.
 *
 * <pre>{@code { "type": "tasked:item", "item": "minecraft:diamond", "count": 4 } }</pre>
 *
 * <p>Note that this shares a type id with {@link dev.ellipog.tasked.quest.task.ItemTask} —
 * {@code tasked:item} means "items" on both sides, and the two registries are separate. As long as a
 * task and a reward are never confused for one another in the same place, which they cannot be, they
 * read the same way to an author.
 */
public record ItemReward(RewardCommon common, ItemRef item, int randomBonus, boolean onlyOne)
        implements QuestReward {

    public static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath(Tasked.MOD_ID, "item");

    /** The type's own fields; {@link RewardCommon#FIELDS} is unioned in at registration. */
    public static final Set<String> FIELDS =
            Set.of("item", "count", "components", "randomBonus", "onlyOne");

    public static final MapCodec<ItemReward> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            RewardCommon.MAP_CODEC.forGetter(ItemReward::common),
            ItemRef.MAP_CODEC.forGetter(ItemReward::item),
            Codec.intRange(0, 6400).optionalFieldOf("randomBonus", 0).forGetter(ItemReward::randomBonus),
            Codec.BOOL.optionalFieldOf("onlyOne", false).forGetter(ItemReward::onlyOne)
    ).apply(instance, ItemReward::new));

    @Override
    public ResourceLocation type() {
        return TYPE;
    }

    public static final RewardBehaviour<ItemReward> BEHAVIOUR = (reward, context) -> {
        ItemStack template = reward.item().toStack();
        if (template.isEmpty()) {
            // The item does not exist. The validator reports this at load time, so reaching here
            // means a reload changed the registries underneath a loaded questline -- worth saying,
            // and not worth throwing over.
            dev.ellipog.tasked.Constants.LOG.warn("tasked: reward {} resolved to nothing and was skipped",
                    reward.item().describe());
            return;
        }

        // `onlyOne`: FTBQ's "don't give me a second one". Checked by item type, not components -- the
        // question is whether the player already has this thing, not this exact stack.
        if (reward.onlyOne() && carries(context.player(), template)) {
            return;
        }

        int bonus = reward.randomBonus() > 0
                ? context.player().getRandom().nextInt(reward.randomBonus() + 1)
                : 0;
        int remaining = reward.item().count() + bonus;

        // Split into legal stacks before handing them over: an ItemRef may ask for thousands, and one
        // over-full stack is a value vanilla items do not have.
        while (remaining > 0) {
            int size = Math.min(remaining, template.getMaxStackSize());
            ItemStack give = template.copyWithCount(size);
            // add() puts what it can into the inventory and returns false if some of it did not fit.
            // What does not fit is dropped at the player's feet rather than discarded -- a quest
            // reward that silently evaporates because the player was full is the kind of bug that
            // gets a mod uninstalled. The drop is reported rather than announced: one press can
            // overflow on many stacks, and the claim operation says the one sentence. See RewardFeedback.
            if (!context.player().addItem(give)) {
                context.player().drop(give, false);
                context.feedback().dropped(give);
            }
            remaining -= size;
        }
    };

    /** Whether the player already carries this item at all. */
    private static boolean carries(net.minecraft.server.level.ServerPlayer player, ItemStack template) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == template.getItem()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The item and its count, with no label — the client draws the item's own name.
     *
     * <p>A lambda rather than {@code RewardDisplay::ofItem}, because the method reference does not
     * fit: {@code ofItem} takes an {@link ItemRef} and what arrives is an {@code ItemReward}. The
     * compiler says "invalid method reference", which is accurate and does not say why — the two
     * types read as interchangeable at a glance because one wraps the other.
     */
    public static final java.util.function.Function<ItemReward, RewardDisplay> DISPLAY =
            reward -> RewardDisplay.ofItem(reward.item());
}
