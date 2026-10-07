package dev.ellipog.tenet.quest.reward;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tenet.quest.condition.ConditionTypes;
import dev.ellipog.tenet.quest.condition.QuestCondition;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The settings every reward has, whatever its type.
 *
 * <p>The reward half of {@link dev.ellipog.tenet.quest.TaskCommon}, and the same shape: a
 * {@link MapCodec} embedded <b>flat</b>, so a reward says {@code "auto": "no_toast"} at its own level
 * rather than under a nested object. Each type's codec merges this in and adds the fields that make
 * it that type.
 *
 * <h2>The four original fields, and where their defaults live</h2>
 *
 * <p>{@code team}, {@code auto} and the tree's file both follow FTB Quests' resolution: an explicit
 * value wins, and silence defers to the quest tree's own settings — which is why the tristate is an
 * {@code Optional<Boolean>} and not a boolean. The two flags are plain booleans whose absence is
 * simply false, because that is what they mean.
 *
 * <p>{@link #FIELDS} is unioned into every registered type by
 * {@link RewardTypes#register(net.minecraft.resources.ResourceLocation, MapCodec, Set,
 * RewardBehaviour, dev.ellipog.tenet.quest.ItemRef, java.util.function.Function,
 * java.util.function.Supplier)}, so a type that embeds this codec cannot forget to declare them —
 * and an addon's type that does not embed it is a mistake the validator's codec check reports at
 * the file's own line.
 *
 * <h2>{@code conditions}</h2>
 *
 * <p>The gate on being paid: every condition must hold for the player a payout would go to, checked
 * on every path that pays — the claim, claim-all, the choice answer, and both automatic ones. A
 * reward whose conditions are unmet is not lost: it stays unclaimed, and the same paths pick it up
 * the moment they hold. The short constructor keeps every existing default and test from carrying a
 * list they never fill.
 */
public record RewardCommon(Optional<Boolean> team, RewardAutoClaim auto, boolean excludeFromClaimAll,
                           boolean ignoreRewardBlocking, List<QuestCondition> conditions) {

    /** The four-field shape: no conditions. */
    public RewardCommon(Optional<Boolean> team, RewardAutoClaim auto, boolean excludeFromClaimAll,
                        boolean ignoreRewardBlocking) {
        this(team, auto, excludeFromClaimAll, ignoreRewardBlocking, List.of());
    }

    /** What a reward that says nothing is: every axis deferred. */
    public static final RewardCommon DEFAULT =
            new RewardCommon(Optional.empty(), RewardAutoClaim.DEFAULT, false, false);

    /** The fields this contributes, for the validator and the editor's row list. */
    public static final Set<String> FIELDS =
            Set.of("team", "auto", "excludeFromClaimAll", "ignoreRewardBlocking", "conditions");

    public static final MapCodec<RewardCommon> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("team").forGetter(RewardCommon::team),
            RewardAutoClaim.CODEC.optionalFieldOf("auto", RewardAutoClaim.DEFAULT).forGetter(RewardCommon::auto),
            Codec.BOOL.optionalFieldOf("excludeFromClaimAll", false).forGetter(RewardCommon::excludeFromClaimAll),
            Codec.BOOL.optionalFieldOf("ignoreRewardBlocking", false).forGetter(RewardCommon::ignoreRewardBlocking),
            // Through the dispatch codec, built lazily there -- see QuestCondition for the
            // class-initialisation cycle that makes this the pattern rather than a constant.
            ConditionTypes.dispatchCodec().listOf().optionalFieldOf("conditions", List.of())
                    .forGetter(RewardCommon::conditions)
    ).apply(instance, RewardCommon::new));

    /** Whether this reward goes to the whole team, given the quest tree's own default. */
    public boolean teamReward(boolean fileDefault) {
        return team.orElse(fileDefault);
    }

    /** The auto-claim mode in force, given the quest tree's own default. */
    public RewardAutoClaim autoClaim(RewardAutoClaim fileDefault) {
        return auto.resolved(fileDefault);
    }
}
