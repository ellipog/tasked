package dev.ellipog.tasked.quest.loot;

import dev.ellipog.tasked.quest.QuestReward;
import dev.ellipog.tasked.quest.reward.RewardDisplay;
import dev.ellipog.tasked.quest.reward.RewardTypes;
import dev.ellipog.tasked.quest.reward.TableReward;

import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * A table rolled <i>for the author</i>: how often each entry came up, nesting and all.
 *
 * <h2>Why this runs on the server</h2>
 *
 * <p>A table's entries can point at other tables, so a roll is not a property of one file: resolving
 * "how often did the rare bag come up" needs every table the first one can reach. The client holds the
 * one file it is editing and a summary of the rest, which is enough to <i>draw</i> a table and not
 * enough to roll one — so the roll is asked for and answered, and the answer is what the editor draws.
 *
 * <h2>The numbers are the grant's numbers</h2>
 *
 * <p>It rolls through {@link RewardTable#rollIndices}, which is the same arithmetic
 * {@code TableReward} grants by, and it resolves nested tables the same way {@code grantAll} does —
 * one child roll per landing, an {@code all_table} child listing everything, a {@code choice} child
 * skipped (a roll cannot offer anything; the loader reports that entry). A report that rolled by its own
 * rules would be worse than no report.
 *
 * <p>The tallies are positions, not rewards, so an entry that appears twice is two rows — which is what
 * an author tuning weights is looking at. {@code emptyHits} is carried at every level because a child
 * loot table that comes up empty is the difference between "the rare bag never dropped" and "the rare
 * bag dropped and paid nothing", and a report that hid it would be quietly wrong.
 */
public final class TableRoller {

    /**
     * The most rolls one request may ask for. A forged count must not spin the server thread.
     *
     * <p>The command's own argument bound reads this, so the two cannot disagree about how many a
     * person may ask for: a command that offered more than the roller accepts would silently clamp and
     * report fewer rolls than were asked for.
     */
    public static final int MAX_ROLLS = 500;

    private TableRoller() {
    }

    /**
     * One entry's line in the report.
     *
     * @param index   the entry's position in its table, which is what a press on the row edits
     * @param hits    how many times it was granted across the rolls
     * @param display what to draw for it — the same display a reward row uses, so a report and a
     *                quest's reward list never describe the same entry differently
     * @param emptyHits for a nested table, the times its own roll paid nothing; {@code 0} for a leaf
     * @param nested  the child table's own entries, when this entry is a table reward
     */
    public record Tally(int index, int hits, RewardDisplay display, int emptyHits, List<Tally> nested) {

        public Tally {
            nested = List.copyOf(nested);
        }
    }

    /**
     * What the rolls did.
     *
     * <p>Two ways a report can be incomplete, and both are fields rather than silences:
     * {@code truncated} says the walk hit {@link RewardTable#MAX_NESTING}, and {@code notRolled} names
     * every entry the walk refused to roll at all. A report that dropped such entries showed fewer
     * rows than the file has, with no reason — which reads as the entry not being there.
     *
     * @param mode     the reading that was simulated: {@code random}, {@code loot}, {@code all_table}
     *                 or {@code choice}
     * @param rolls    how many grants were simulated
     * @param emptyHits throws that landed in the empty band, at the top level
     * @param entries  one line per entry of the top-level table, in the table's order
     * @param truncated whether nesting was cut off at {@link RewardTable#MAX_NESTING}
     * @param notRolled the entries no roll can pay out, each named with the entry it is — a choice
     *                  inside a table, which offers rather than hands out
     */
    public record Report(TableReward.Mode mode, int rolls, int emptyHits, List<Tally> entries,
                         boolean truncated, List<String> notRolled) {

        public Report {
            entries = List.copyOf(entries);
            notRolled = List.copyOf(notRolled);
        }
    }

    /** One node of the walk, mutable while the dice are thrown and read once at the end. */
    private static final class Node {

        private final RewardTable table;
        private final int[] hits;
        private final Map<Integer, Node> children = new LinkedHashMap<>();
        private int emptyHits;

        private Node(RewardTable table) {
            this.table = table;
            this.hits = new int[table.entryCount()];
        }
    }

    /**
     * Rolls one table, {@code rolls} times, and reports what came up.
     *
     * @param tables where a named reference resolves; the loaded questline on a server, a map in a test
     */
    public static Report roll(RewardTable table, TableReward.Mode mode, int rolls, RandomSource random,
                              Function<String, RewardTable> tables) {
        int count = Math.max(1, Math.min(rolls, MAX_ROLLS));
        Node root = new Node(table);
        boolean[] truncated = {false};
        // A set, because a skipped entry is met once per landing and the same sentence `rolls` times is
        // a wall of one fact. The order is the walk's, so the list reads in the file's own order.
        java.util.Set<String> notRolled = new java.util.LinkedHashSet<>();
        rollInto(root, mode, count, random, tables, 0, truncated, notRolled);
        return new Report(mode, count, root.emptyHits, tallies(root), truncated[0],
                List.copyOf(notRolled));
    }

    /**
     * One table's rolls, into a node.
     *
     * <p>{@code all_table} does not throw dice at all — every entry is granted — so its "hits" are the
     * roll count and its empty band is nothing. That is not an optimisation: a report that showed an
     * all-table's entries at their weight shares would be describing a roll that never happens.
     *
     * <p>{@code choice} throws nothing either, and for the opposite reason: the player picks. It
     * returns before the throwing loop, which leaves every hit at zero — so the entries are listed
     * (the useful thing to show is what the table would offer) while the empty band's arithmetic,
     * which is derived from the throws, is never reached. Without that return a choice report would
     * claim `rolls * lootSize` throws landed in the empty band.
     */
    private static void rollInto(Node node, TableReward.Mode mode, int rolls, RandomSource random,
                                 Function<String, RewardTable> tables, int depth, boolean[] truncated,
                                 java.util.Set<String> notRolled) {
        RewardTable table = node.table;
        if (mode == TableReward.Mode.CHOICE) {
            return;
        }
        boolean all = mode == TableReward.Mode.ALL_TABLE;
        if (all) {
            // No dice: every entry is granted, so its hits are the roll count.
            for (int index = 0; index < table.entryCount(); index++) {
                node.hits[index] += rolls;
            }
        }

        double empty = mode == TableReward.Mode.LOOT ? Math.max(0, table.emptyWeight()) : 0;
        double total = empty;
        for (RewardTable.Entry entry : table.entries()) {
            if (entry.weight() > 0) {
                total += entry.weight();
            }
        }
        int throwCount = total > 0 ? rolls * table.lootSize() : 0;
        int positiveLandings = 0;

        for (int roll = 0; roll < rolls; roll++) {
            List<Integer> landed = all ? allIndices(table)
                    : table.rollIndices(random, mode == TableReward.Mode.LOOT);
            for (int index : landed) {
                if (!all) {
                    node.hits[index]++;
                    if (table.entries().get(index).weight() > 0) {
                        positiveLandings++;
                    }
                }
                descend(node, table, index, random, tables, depth, truncated, notRolled);
            }
        }
        // Every throw lands either on a positive entry or in the empty band, so the band's count is
        // what is left over -- derived rather than counted a second time, because a second count is a
        // second rule. Added, not assigned: a nested table is rolled once per parent landing, so this
        // method runs many times over one node and each run is one more grant's worth of throws.
        node.emptyHits += all ? 0 : throwCount - positiveLandings;
    }

    /** The child roll one landed entry asks for, if it is a table reward at all. */
    private static void descend(Node node, RewardTable table, int index, RandomSource random,
                                Function<String, RewardTable> tables, int depth, boolean[] truncated,
                                java.util.Set<String> notRolled) {
        QuestReward reward = table.entries().get(index).reward();
        if (!(reward instanceof TableReward nested)) {
            return;
        }
        if (TableReward.isChoice(nested)) {
            // A roll cannot offer a choice. The validator refuses the entry where it is written, so
            // reaching here means a file that arrived another way — and the report says which entry it
            // was rather than quietly showing one row fewer than the table has.
            notRolled.add("entry " + (index + 1) + ": a choice of \""
                    + nested.table().orElse("an inline table") + "\" is not rolled - "
                    + "a choice offers its entries rather than handing them out");
            return;
        }
        Optional<RewardTable> child = nested.resolvedTable(tables);
        if (child.isEmpty()) {
            return;
        }
        if (depth >= RewardTable.MAX_NESTING) {
            truncated[0] = true;
            return;
        }
        Node childNode = node.children.computeIfAbsent(index, key -> new Node(child.get()));
        rollInto(childNode, nested.mode(), 1, random, tables, depth + 1, truncated, notRolled);
    }

    private static List<Integer> allIndices(RewardTable table) {
        List<Integer> all = new ArrayList<>(table.entryCount());
        for (int index = 0; index < table.entryCount(); index++) {
            all.add(index);
        }
        return all;
    }

    /** One node, read out. */
    private static List<Tally> tallies(Node node) {
        List<Tally> out = new ArrayList<>(node.table.entryCount());
        for (int index = 0; index < node.table.entryCount(); index++) {
            QuestReward reward = node.table.entries().get(index).reward();
            Node child = node.children.get(index);
            out.add(new Tally(index, node.hits[index], RewardTypes.displayOf(reward),
                    child == null ? 0 : child.emptyHits, child == null ? List.of() : tallies(child)));
        }
        return List.copyOf(out);
    }
}
