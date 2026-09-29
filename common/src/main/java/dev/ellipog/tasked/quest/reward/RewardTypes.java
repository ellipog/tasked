package dev.ellipog.tasked.quest.reward;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import dev.ellipog.armature.api.data.TypeDispatch;
import dev.ellipog.armature.api.data.TypeSpec;
import dev.ellipog.armature.api.registry.SimpleRegistry;
import dev.ellipog.tasked.Tasked;
import dev.ellipog.tasked.quest.ItemRef;
import dev.ellipog.tasked.quest.QuestReward;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.Set;

/**
 * Every reward type Tasked knows about.
 *
 * <p>Mirrors {@link dev.ellipog.tasked.quest.task.TaskTypes}, including why
 * {@link #dispatchCodec()} is a method rather than a constant and why the registry holds an entry
 * rather than a bare spec. Both are explained there.
 */
public final class RewardTypes {

    /** A registered type: what reads it, how it is granted, and what represents it. */
    private record Entry(TypeSpec<QuestReward> spec, RewardBehaviour<QuestReward> behaviour, ItemRef icon,
                         java.util.function.Function<QuestReward, RewardDisplay> display) {
    }

    private static final SimpleRegistry<Entry> REGISTRY = SimpleRegistry.create("quest reward types");

    /** {@code tasked:item} — some items. */
    public static final QuestRewardType<ItemReward> ITEM = register(
            "item", ItemReward.MAP_CODEC, ItemReward.FIELDS, ItemReward.BEHAVIOUR,
            new ItemRef(ResourceLocation.withDefaultNamespace("diamond"), 1), ItemReward.DISPLAY);

    /** {@code tasked:xp} — experience points or levels. */
    public static final QuestRewardType<XpReward> XP = register(
            "xp", XpReward.MAP_CODEC, XpReward.FIELDS, XpReward.BEHAVIOUR,
            new ItemRef(ResourceLocation.withDefaultNamespace("experience_bottle"), 1), XpReward.DISPLAY);

    private RewardTypes() {
    }

    private static final class Dispatch {
        static final Codec<QuestReward> CODEC = TypeDispatch.codec(
                "quest reward", "type", QuestReward::type,
                () -> REGISTRY.values().stream().map(Entry::spec).toList());
    }

    public static Codec<QuestReward> dispatchCodec() {
        return Dispatch.CODEC;
    }

    public static <T extends QuestReward> QuestRewardType<T> register(String path,
                                                                      MapCodec<T> codec,
                                                                      Set<String> fields,
                                                                      RewardBehaviour<T> behaviour,
                                                                      ItemRef icon,
                                                                      java.util.function.Function<T, RewardDisplay> display) {
        return register(ResourceLocation.fromNamespaceAndPath(Tasked.MOD_ID, path), codec, fields, behaviour,
                icon, display);
    }

    public static <T extends QuestReward> QuestRewardType<T> register(ResourceLocation id,
                                                                      MapCodec<T> codec,
                                                                      Set<String> fields,
                                                                      RewardBehaviour<T> behaviour,
                                                                      ItemRef icon,
                                                                      java.util.function.Function<T, RewardDisplay> display) {
        QuestRewardType<T> typed = new SimpleQuestRewardType<>(id, codec, fields, behaviour, icon, display);
        REGISTRY.register(id, new Entry(widenSpec(typed), widenBehaviour(behaviour), icon, widenDisplay(display)));
        return typed;
    }

    /**
     * What a reward gives, as a client should draw it.
     *
     * <p>{@link RewardDisplay#NONE} for an unregistered type — reachable only from a listing against a
     * quest that failed to decode, which the validator has already reported.
     */
    public static RewardDisplay displayOf(QuestReward reward) {
        return REGISTRY.get(reward.type())
                .map(entry -> entry.display().apply(reward))
                .orElse(RewardDisplay.NONE);
    }

    /** The icon for a reward's type, for a listing. Paper for an unregistered type. */
    public static ItemRef iconOf(ResourceLocation id) {
        return REGISTRY.get(id).map(Entry::icon).orElse(ItemRef.DEFAULT_ICON);
    }

    public static Set<ResourceLocation> ids() {
        return REGISTRY.ids();
    }

    public static int count() {
        return REGISTRY.size();
    }

    public static Set<String> fieldsOf(ResourceLocation id) {
        return REGISTRY.get(id).map(entry -> entry.spec().fields()).orElse(Set.of());
    }

    /** How to grant a reward. Empty for an unregistered type, which cannot have decoded. */
    public static Optional<RewardBehaviour<QuestReward>> behaviourOf(QuestReward reward) {
        return REGISTRY.get(reward.type()).map(Entry::behaviour);
    }

    @SuppressWarnings("unchecked")
    private static <T extends QuestReward> TypeSpec<QuestReward> widenSpec(QuestRewardType<T> type) {
        return new TypeSpec<>() {
            @Override
            public ResourceLocation id() {
                return type.id();
            }

            @Override
            public Set<String> fields() {
                return type.fields();
            }

            @Override
            public MapCodec<QuestReward> codec() {
                return type.codec().xmap(reward -> (QuestReward) reward, reward -> (T) reward);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T extends QuestReward> RewardBehaviour<QuestReward> widenBehaviour(RewardBehaviour<T> behaviour) {
        return (reward, context) -> behaviour.grant((T) reward, context);
    }

    @SuppressWarnings("unchecked")
    private static <T extends QuestReward> java.util.function.Function<QuestReward, RewardDisplay> widenDisplay(
            java.util.function.Function<T, RewardDisplay> display) {
        // Sound for the same reason the spec's and the behaviour's casts are: the display travels with
        // the id and the codec, so the only value ever handed to this downcast came from that codec.
        return reward -> display.apply((T) reward);
    }

    private record SimpleQuestRewardType<T extends QuestReward>(
            ResourceLocation id, MapCodec<T> codec, Set<String> fields,
            RewardBehaviour<T> behaviour, ItemRef icon, java.util.function.Function<T, RewardDisplay> display
    ) implements QuestRewardType<T> {

        @Override
        public RewardDisplay display(T reward) {
            return display.apply(reward);
        }
    }
}
