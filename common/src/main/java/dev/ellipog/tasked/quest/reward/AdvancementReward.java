package dev.ellipog.tasked.quest.reward;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tasked.Constants;
import dev.ellipog.tasked.Tasked;
import dev.ellipog.tasked.quest.QuestReward;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Award an advancement, or one criterion of one.
 *
 * <pre>{@code { "type": "tasked:advancement", "advancement": "minecraft:story/mine_stone" } }</pre>
 *
 * <p>An empty criterion means every criterion, which is FTBQ's reading: the reward grants the whole
 * advancement rather than the first criterion of it.
 */
public record AdvancementReward(RewardCommon common, ResourceLocation advancement, Optional<String> criterion)
        implements QuestReward {

    public static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath(Tasked.MOD_ID, "advancement");

    public static final Set<String> FIELDS = Set.of("advancement", "criterion");

    public static final MapCodec<AdvancementReward> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            RewardCommon.MAP_CODEC.forGetter(AdvancementReward::common),
            ResourceLocation.CODEC.fieldOf("advancement").forGetter(AdvancementReward::advancement),
            Codec.STRING.optionalFieldOf("criterion").forGetter(AdvancementReward::criterion)
    ).apply(instance, AdvancementReward::new));

    @Override
    public ResourceLocation type() {
        return TYPE;
    }

    public static final RewardBehaviour<AdvancementReward> BEHAVIOUR = (reward, context) -> {
        var holder = context.server().getAdvancements().get(reward.advancement());
        if (holder == null) {
            Constants.LOG.warn("tasked: advancement reward names {} which this build does not have; skipped",
                    reward.advancement());
            return;
        }
        if (reward.criterion().isPresent()) {
            context.player().getAdvancements().award(holder, reward.criterion().get());
            return;
        }
        for (String name : holder.value().criteria().keySet()) {
            context.player().getAdvancements().award(holder, name);
        }
    };

    public static final Function<AdvancementReward, RewardDisplay> DISPLAY = reward -> {
        String what = reward.criterion().map(name -> name + " of " + reward.advancement())
                .orElse(reward.advancement().toString());
        return RewardDisplay.ofTranslatableText("tasked.reward.advancement", "Award " + what, what, 1);
    };
}
