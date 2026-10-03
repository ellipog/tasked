package dev.ellipog.tasked.quest.loot;

import com.mojang.serialization.JsonOps;

import dev.ellipog.tasked.quest.ItemRef;
import dev.ellipog.tasked.quest.MinecraftTestBootstrap;
import dev.ellipog.tasked.quest.QuestReward;
import dev.ellipog.tasked.quest.reward.ItemReward;
import dev.ellipog.tasked.quest.reward.RewardCommon;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reward table's roll, with a seeded source rather than a playtest.
 *
 * <p>The distribution properties are arithmetic — guaranteed entries, the number of throws, the
 * empty band — and a seeded {@link RandomSource} makes each one reproducible. What a playtest can
 * check is that the numbers feel right; what these check is that the rules are the ones written.
 */
@DisplayName("the reward table's roll")
class RewardTableTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    private static ItemReward item(String id) {
        return new ItemReward(RewardCommon.DEFAULT,
                new ItemRef(ResourceLocation.withDefaultNamespace(id), 1), 0, false);
    }

    private static RewardTable table(double emptyWeight, int lootSize, RewardTable.Entry... entries) {
        return new RewardTable(emptyWeight, lootSize, List.of(entries));
    }

    private static RewardTable.Entry entry(double weight, QuestReward reward) {
        return new RewardTable.Entry(weight, reward);
    }

    private static long countOf(List<QuestReward> rewards, ItemReward wanted) {
        return rewards.stream().filter(reward -> reward == wanted).count();
    }

    @Test
    @DisplayName("a weight-zero entry is always granted, exactly once, whatever the dice say")
    void weightZeroIsAlwaysGranted() {
        ItemReward guaranteed = item("stone");
        ItemReward weighted = item("diamond");
        RewardTable table = table(0, 1, entry(0, guaranteed), entry(1, weighted));

        for (int seed = 0; seed < 20; seed++) {
            List<QuestReward> granted = table.roll(RandomSource.create(seed), false);
            assertEquals(1, countOf(granted, guaranteed), "seed " + seed);
        }
    }

    @Test
    @DisplayName("lootSize is how many times the dice are thrown")
    void lootSizeRollsTheStatedNumberOfTimes() {
        ItemReward one = item("stone");
        ItemReward two = item("diamond");
        RewardTable table = table(0, 3, entry(1, one), entry(1, two));

        List<QuestReward> granted = table.roll(RandomSource.create(7), false);

        assertEquals(3, granted.size(), "one throw per loot size, and no empty band to swallow any");
    }

    @Test
    @DisplayName("the empty band can yield nothing, and only when the mode includes it")
    void theEmptyBandOnlyBitesWhenIncluded() {
        RewardTable table = table(1.0, 20, entry(1, item("stone")));

        List<QuestReward> withEmpty = table.roll(RandomSource.create(3), true);
        assertTrue(withEmpty.size() < 20,
                "twenty throws with a fifty-fifty empty chance granting all twenty is not a roll");
        assertFalse(withEmpty.isEmpty(), "and the weighted entry is not impossible either");

        List<QuestReward> withoutEmpty = table.roll(RandomSource.create(3), false);
        assertEquals(20, withoutEmpty.size(),
                "the empty weight is out of play when the mode excludes it");
    }

    @Test
    @DisplayName("the all-table grant takes every entry once, weights ignored")
    void allIgnoresWeights() {
        RewardTable table = table(5.0, 4, entry(0, item("stone")), entry(9, item("diamond")));

        assertEquals(2, table.all().size(), "every entry once, the empty weight and the weights set aside");
    }

    @Test
    @DisplayName("choice finds an entry by position and refuses one that is not there")
    void choiceIndexesEntries() {
        ItemReward first = item("stone");
        RewardTable table = table(0, 1, entry(1, first), entry(1, item("diamond")));

        assertTrue(table.choice(0).isPresent());
        assertTrue(table.choice(0).get() == first, "position zero is the first entry");
        assertTrue(table.choice(1).isPresent());
        assertFalse(table.choice(-1).isPresent());
        assertFalse(table.choice(2).isPresent(), "there is no third entry");
    }

    @Test
    @DisplayName("a table round-trips through its codec")
    void codecRoundTrip() {
        RewardTable table = table(0.5, 2, entry(0, item("stone")), entry(3, item("diamond")));

        var encoded = RewardTable.CODEC.encodeStart(JsonOps.INSTANCE, table).getOrThrow();
        RewardTable decoded = RewardTable.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

        assertEquals(0.5, decoded.emptyWeight());
        assertEquals(2, decoded.lootSize());
        assertEquals(2, decoded.entryCount());
        assertEquals(0.0, decoded.entries().get(0).weight());
        assertEquals(3.0, decoded.entries().get(1).weight());
        assertEquals("tasked:item", decoded.entries().get(1).reward().type().toString());
    }
}
