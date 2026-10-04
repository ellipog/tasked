package dev.ellipog.tasked.quest.reward;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.armature.api.data.Codecs;
import dev.ellipog.tasked.Constants;
import dev.ellipog.tasked.quest.QuestReward;
import dev.ellipog.tasked.quest.TaskedQuests;
import dev.ellipog.tasked.quest.loot.RewardTable;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * A reward that rolls from a table: {@code random}, {@code loot}, {@code all_table} and
 * {@code choice}.
 *
 * <pre>{@code { "type": "tasked:loot", "table": "dungeon" } }</pre>
 *
 * <p>One record with four modes rather than four records, because they share every field and differ
 * only in what they do with the roll — and FTB Quests' four types differ the same way. The mode is
 * fixed at registration, so a file cannot turn a {@code random} into a {@code choice} by adding a
 * field; the type id <i>is</i> the mode.
 *
 * <h2>Choice does not roll at claim time</h2>
 *
 * <p>{@code random}, {@code loot} and {@code all_table} resolve the moment the reward is granted.
 * {@code choice} cannot: the player picks, so the claim marks nothing, sends the entries to the
 * client, and the answer comes back as its own operation — see
 * {@code ProgressService.claimChoice}. A choice reward therefore stays outstanding until it is
 * answered, which also means a crash between the offer and the pick loses nothing.
 */
public record TableReward(RewardCommon common, Mode mode, Optional<String> table,
                          Optional<RewardTable> inline) implements QuestReward {

    public static final ResourceLocation TYPE_RANDOM =
            ResourceLocation.fromNamespaceAndPath(dev.ellipog.tasked.Tasked.MOD_ID, "random");
    public static final ResourceLocation TYPE_LOOT =
            ResourceLocation.fromNamespaceAndPath(dev.ellipog.tasked.Tasked.MOD_ID, "loot");
    public static final ResourceLocation TYPE_ALL_TABLE =
            ResourceLocation.fromNamespaceAndPath(dev.ellipog.tasked.Tasked.MOD_ID, "all_table");
    public static final ResourceLocation TYPE_CHOICE =
            ResourceLocation.fromNamespaceAndPath(dev.ellipog.tasked.Tasked.MOD_ID, "choice");

    public static final Set<String> FIELDS = Set.of("table", "inline");

    /**
     * A choice cannot be auto-granted: its payout is the player's pick.
     *
     * <p>The other three modes roll or list their entries and pay immediately, so they are as
     * automatic as any item reward. This override is what keeps an auto-claim from marking a choice
     * collected and granting nothing — the offer waits for the claim flow instead.
     */
    @Override
    public boolean autoGrantable() {
        return mode != Mode.CHOICE;
    }

    /** Which of the four table rewards this is. */
    public enum Mode {
        RANDOM,
        LOOT,
        ALL_TABLE,
        CHOICE;

        public static final Codec<Mode> CODEC = Codecs.enumByName(Mode.class);

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The codec a mode's registration uses; the mode is bound here, never read from the file. */
    public static MapCodec<TableReward> mapCodec(Mode mode) {
        return RecordCodecBuilder.mapCodec(instance -> instance.group(
                RewardCommon.MAP_CODEC.forGetter(TableReward::common),
                Codec.STRING.optionalFieldOf("table").forGetter(TableReward::table),
                RewardTable.MAP_CODEC.codec().optionalFieldOf("inline").forGetter(TableReward::inline)
        ).apply(instance, (common, table, inline) -> new TableReward(common, mode, table, inline)));
    }

    @Override
    public ResourceLocation type() {
        return switch (mode) {
            case RANDOM -> TYPE_RANDOM;
            case LOOT -> TYPE_LOOT;
            case ALL_TABLE -> TYPE_ALL_TABLE;
            case CHOICE -> TYPE_CHOICE;
        };
    }

    /**
     * The named table this reward points at, for the loader's cross-file check.
     *
     * <p>Empty for an inline table: there is no reference to dangle.
     */
    @Override
    public Optional<String> tableId() {
        return inline.isPresent() ? Optional.empty() : table;
    }

    /** The table in force: the named one, or the inline one. Empty when neither resolves. */
    public Optional<RewardTable> resolvedTable() {
        if (inline.isPresent()) {
            return inline;
        }
        return table.flatMap(id -> Optional.ofNullable(TaskedQuests.rewardTables().get(id)));
    }

    /** A warning once per failed lookup, and a grant that does nothing: an author's typo, not a crash. */
    private static void reportMissing(TableReward reward) {
        Constants.LOG.warn("tasked: {} reward points at a table that is not loaded ({}) and granted nothing",
                reward.mode().wire(), reward.table().orElse("?"));
    }

    public static final RewardBehaviour<TableReward> BEHAVIOUR = (reward, context) -> {
        Optional<RewardTable> resolved = reward.resolvedTable();
        if (resolved.isEmpty()) {
            reportMissing(reward);
            return;
        }
        RewardTable table = resolved.get();
        switch (reward.mode()) {
            case CHOICE -> {
                // Handled by the claim flow: offered to the client, granted when the pick comes back.
            }
            case ALL_TABLE -> grantAll(table.all(), context, 0);
            case RANDOM -> grantAll(table.roll(context.player().getRandom(), false), context, 0);
            case LOOT -> grantAll(table.roll(context.player().getRandom(), true), context, 0);
        }
    };

    /**
     * Grants a rolled list, dispatching each entry through the registry — which is what lets a table
     * hold any reward, including another table.
     *
     * <p>The depth limit is the cycle guard: a named table whose entry points back at itself is a
     * file an author can write, and unbounded recursion from a data file is a crash, not a message.
     */
    public static void grantAll(List<QuestReward> rewards, RewardContext context, int depth) {
        if (depth > 8) {
            Constants.LOG.warn("tasked: a reward table nested more than 8 deep was cut off -- "
                    + "a table probably references itself");
            return;
        }
        for (QuestReward entry : rewards) {
            if (entry instanceof TableReward nested) {
                Optional<RewardTable> table = nested.resolvedTable();
                if (table.isEmpty()) {
                    reportMissing(nested);
                    continue;
                }
                switch (nested.mode()) {
                    case CHOICE -> Constants.LOG.warn(
                            "tasked: a choice reward inside a table cannot be offered; skipped");
                    case ALL_TABLE -> grantAll(table.get().all(), context, depth + 1);
                    case RANDOM -> grantAll(table.get().roll(context.player().getRandom(), false),
                            context, depth + 1);
                    case LOOT -> grantAll(table.get().roll(context.player().getRandom(), true),
                            context, depth + 1);
                }
            }
            else {
                RewardTypes.behaviourOf(entry).ifPresent(behaviour -> {
                    try {
                        behaviour.grant(entry, context);
                    }
                    catch (RuntimeException e) {
                        // Same rule as the engine's own grant loop: one bad entry must not stop the
                        // rest of the roll.
                        Constants.LOG.error("tasked: granting a {} from a table failed", entry.type(), e);
                    }
                });
            }
        }
    }

    /** The mode as a person reads it. */
    public String description() {
        return mode.wire().replace('_', ' ');
    }

    public static final java.util.function.Function<TableReward, RewardDisplay> DISPLAY = reward -> {
        // The table is the subject, and a table is named by its id -- there is nothing to prettify, so
        // the row spells it. An inline table has no id and says so.
        String what = reward.table().orElse("inline");
        return RewardDisplay.ofTranslatableText("tasked.reward.table", "Roll the " + what + " table",
                what, 1);
    };
}
