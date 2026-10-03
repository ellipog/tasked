package dev.ellipog.tasked.quest.loot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tasked.quest.QuestReward;
import dev.ellipog.tasked.quest.reward.RewardTypes;

import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A named roll of rewards: the payload of {@code random}, {@code loot}, {@code all_table} and
 * {@code choice}.
 *
 * <pre>{@code
 * { "emptyWeight": 10, "lootSize": 2,
 *   "entries": [ { "weight": 0, "reward": { "type": "tasked:item", "item": "minecraft:stone" } },
 *                { "weight": 5, "reward": { "type": "tasked:xp", "amount": 30 } } ] }
 * }</pre>
 *
 * <h2>The roll is FTB Quests', weights and all</h2>
 *
 * <p>A weight-zero entry is <b>always</b> granted, once per roll call — it is how a table says "and
 * everyone also gets this". Every positive weight shares the rest of the probability space, and
 * {@code lootSize} is how many times the dice are thrown. {@code emptyWeight} is the chance of
 * nothing on a throw, and only the {@code loot} mode includes it: a {@code random} reward promises
 * something, and a {@code loot} reward is allowed to promise nothing.
 *
 * <p>Kept as pure arithmetic on a {@link RandomSource} handed in, so the distribution is testable
 * with a seeded source rather than by playing.
 */
public record RewardTable(double emptyWeight, int lootSize, List<Entry> entries) {

    /** The fields this contributes, for the validator and the editor. */
    public static final Set<String> FIELDS = Set.of("emptyWeight", "lootSize", "entries");

    /** One entry: a reward, and how much of the table's weight it takes. */
    public record Entry(double weight, QuestReward reward) {

        public static final Set<String> FIELDS = Set.of("weight", "reward");

        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.DOUBLE.optionalFieldOf("weight", 1.0).forGetter(Entry::weight),
                RewardTypes.dispatchCodec().fieldOf("reward").forGetter(Entry::reward)
        ).apply(instance, Entry::new));
    }

    public RewardTable {
        entries = List.copyOf(entries);
    }

    public static final MapCodec<RewardTable> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.DOUBLE.optionalFieldOf("emptyWeight", 0.0).forGetter(RewardTable::emptyWeight),
            Codec.intRange(1, 1000).optionalFieldOf("lootSize", 1).forGetter(RewardTable::lootSize),
            Entry.CODEC.listOf().fieldOf("entries").forGetter(RewardTable::entries)
    ).apply(instance, RewardTable::new));

    public static final Codec<RewardTable> CODEC = MAP_CODEC.codec();

    /**
     * The weighted roll.
     *
     * @param includeEmpty whether the empty band is in play — false for {@code random}, true for
     *                     {@code loot}
     * @return what was granted, the guaranteed entries first
     */
    public List<QuestReward> roll(RandomSource random, boolean includeEmpty) {
        List<QuestReward> granted = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.weight() <= 0) {
                granted.add(entry.reward());
            }
        }

        double empty = includeEmpty ? Math.max(0, emptyWeight) : 0;
        double total = empty;
        for (Entry entry : entries) {
            if (entry.weight() > 0) {
                total += entry.weight();
            }
        }
        if (total <= 0) {
            // Nothing weighted and nothing empty: the guaranteed entries are the whole answer.
            return List.copyOf(granted);
        }

        for (int attempt = 0; attempt < lootSize; attempt++) {
            double pick = random.nextDouble() * total;
            if (pick < empty) {
                // This throw landed in the empty band: nothing for it.
                continue;
            }
            double threshold = empty;
            for (Entry entry : entries) {
                if (entry.weight() <= 0) {
                    continue;
                }
                threshold += entry.weight();
                if (pick < threshold) {
                    granted.add(entry.reward());
                    break;
                }
            }
        }
        return List.copyOf(granted);
    }

    /** Every entry once, weights and the empty roll ignored: the all-table reward's grant. */
    public List<QuestReward> all() {
        List<QuestReward> all = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            all.add(entry.reward());
        }
        return List.copyOf(all);
    }

    /** One entry by its position, for the choice picker. Empty when the index is not one. */
    public Optional<QuestReward> choice(int index) {
        return index >= 0 && index < entries.size()
                ? Optional.of(entries.get(index).reward())
                : Optional.empty();
    }

    public int entryCount() {
        return entries.size();
    }
}
