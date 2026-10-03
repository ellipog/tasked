package dev.ellipog.tasked.quest.condition;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tasked.Tasked;
import dev.ellipog.tasked.quest.ItemRef;
import dev.ellipog.tasked.quest.task.ComponentMatch;
import dev.ellipog.tasked.quest.task.ItemCounting;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;
import java.util.function.Function;

/**
 * Have enough of an item.
 *
 * <pre>{@code { "type": "tasked:item", "item": "minecraft:iron_ingot", "count": 8 } }</pre>
 *
 * <p>The condition half of {@code tasked:item}, and the same matching: {@code match} defaults to
 * strict for the reason it does there — a file that says nothing means the whole stack — and the count
 * is read through the same helper the task uses, so a condition can never disagree with the task it
 * guards about how many the player has.
 */
public record ItemCondition(ItemRef item, ComponentMatch match) implements QuestCondition {

    public static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath(Tasked.MOD_ID, "item");

    public static final Set<String> FIELDS = Set.of("item", "count", "components", "match");

    public static final MapCodec<ItemCondition> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ItemRef.MAP_CODEC.forGetter(ItemCondition::item),
            ComponentMatch.CODEC.optionalFieldOf("match", ComponentMatch.STRICT).forGetter(ItemCondition::match)
    ).apply(instance, ItemCondition::new));

    @Override
    public ResourceLocation type() {
        return TYPE;
    }

    public static final ConditionBehaviour<ItemCondition> BEHAVIOUR = (condition, context) -> {
        int have = ItemCounting.countIn(context.player().getInventory(), condition.item().toStack(),
                condition.match(), condition.item().count());
        return have >= condition.item().count();
    };

    public static final Function<ItemCondition, ConditionDisplay> DISPLAY =
            condition -> ConditionDisplay.ofItem(condition.item());
}
