package dev.ellipog.tasked.quest.reward;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.ellipog.tasked.Tasked;
import dev.ellipog.tasked.quest.QuestReward;
import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/**
 * Give the player experience — either a number of points, or a number of whole levels.
 *
 * <pre>{@code { "type": "tasked:xp", "amount": 3, "levels": true } }</pre>
 *
 * <p>{@code levels} exists because they are genuinely different rewards: thirty points is a small
 * nudge, thirty levels is most of a playthrough. FTB Quests has both as separate reward types, which
 * is two entries in its type list for one idea.
 */
public record XpReward(int amount, boolean levels) implements QuestReward {

    public static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath(Tasked.MOD_ID, "xp");

    public static final Set<String> FIELDS = Set.of("amount", "levels");

    public static final MapCodec<XpReward> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.intRange(1, 100_000).fieldOf("amount").forGetter(XpReward::amount),
            Codec.BOOL.optionalFieldOf("levels", false).forGetter(XpReward::levels)
    ).apply(instance, XpReward::new));

    @Override
    public ResourceLocation type() {
        return TYPE;
    }

    public static final RewardBehaviour<XpReward> BEHAVIOUR = (reward, context) -> {
        if (reward.levels()) {
            // giveExperienceLevels adjusts the level and *removes* the accumulated points towards the
            // next one, which is what a player means by "three levels" -- and is why it is not the
            // same as giving three levels' worth of points.
            context.player().giveExperienceLevels(reward.amount());
        }
        else {
            context.player().giveExperiencePoints(reward.amount());
        }
    };

    /**
     * Experience has no item to look up, so the label goes as a key with an English fallback.
     *
     * <p>Two keys rather than one because "five experience points" and "five levels" are different
     * rewards with different wording, and a single key with a plural would need the client to decide
     * which noun to use — which is the server's question to answer, since the server is the one that
     * knows whether {@code levels} was set.
     */
    public static final java.util.function.Function<XpReward, RewardDisplay> DISPLAY = reward ->
            reward.levels()
                    ? RewardDisplay.ofTranslatableText("tasked.reward.xp.levels",
                            reward.amount() + (reward.amount() == 1 ? " level" : " levels"), reward.amount())
                    : RewardDisplay.ofTranslatableText("tasked.reward.xp.points",
                            reward.amount() + " XP", reward.amount());
}
