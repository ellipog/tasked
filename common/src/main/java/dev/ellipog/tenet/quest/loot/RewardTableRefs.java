package dev.ellipog.tenet.quest.loot;

import dev.ellipog.tenet.quest.QuestReward;
import dev.ellipog.tenet.quest.reward.TableReward;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every table a reward points at, at any depth.
 *
 * <h2>Why one walk and not three</h2>
 *
 * <p>Three checks need the same question answered: the loader asks whether a reference names a table
 * that exists, the cycle check asks which tables a table reaches, and the editor refuses to delete a
 * table something still points at. Each of them has to descend through <b>inline</b> tables too — an
 * entry inside a table can carry its own table, and an entry inside <i>that</i> one can carry another —
 * so a walk written once is a walk that cannot disagree with itself about what "references" means. The
 * loader's own gap is the argument for it: before this, a dangling reference inside an inline table was
 * reported by nothing at all, because the check only looked at a quest's top-level rewards.
 *
 * <h2>Models, not JSON</h2>
 *
 * <p>The walk reads decoded {@link QuestReward}s rather than raw trees, because that is what the
 * loader has by the time it runs these checks and because a decoded tree is finite by construction: a
 * file cannot describe a cycle of inline tables, only a cycle of named references, which is
 * {@link TableCycles}' business.
 */
public final class RewardTableRefs {

    private RewardTableRefs() {
    }

    /**
     * One reference: the id it names, and the path of the <b>reward</b> that names it.
     *
     * <p>The path is what makes a report actionable — {@code $.entries[2].reward.table} is a line an
     * author can open — and it is the reward's path rather than the {@code table} field's so the
     * caller's {@code + ".table"} reads the same for a top-level reward and one three inline tables
     * deep.
     */
    public record Ref(String id, String path) {
    }

    /** The table ids this reward points at, in the order met, without duplicates. */
    public static Set<String> idsOf(QuestReward reward) {
        Set<String> ids = new LinkedHashSet<>();
        for (Ref ref : refsOf(reward, "$")) {
            ids.add(ref.id());
        }
        return ids;
    }

    /** The same, for a list of rewards: a quest's rewards, or a table's entries. */
    public static Set<String> idsOf(List<QuestReward> rewards) {
        Set<String> ids = new LinkedHashSet<>();
        for (Ref ref : refsOf(rewards, "$.rewards")) {
            ids.add(ref.id());
        }
        return ids;
    }

    /** The same, for a table: what its entries point at, through their inline tables. */
    public static Set<String> idsOf(RewardTable table) {
        Set<String> ids = new LinkedHashSet<>();
        for (Ref ref : refsOf(table, "$")) {
            ids.add(ref.id());
        }
        return ids;
    }

    /**
     * Every reference in a list of rewards, with the path of the reward that makes it.
     *
     * @param arrayPath the path of the array itself, e.g. {@code $.rewards} or {@code $.entries}
     */
    public static List<Ref> refsOf(List<QuestReward> rewards, String arrayPath) {
        List<Ref> refs = new ArrayList<>();
        for (int i = 0; i < rewards.size(); i++) {
            collect(rewards.get(i), arrayPath + "[" + i + "]", refs, 0);
        }
        return List.copyOf(refs);
    }

    /**
     * Every reference in a table's entries.
     *
     * <p>A table file's entries are at {@code $.entries[i].reward}, which is the shape the validator
     * and the loader's messages already use.
     */
    public static List<Ref> refsOf(RewardTable table, String rootPath) {
        List<Ref> refs = new ArrayList<>();
        List<RewardTable.Entry> entries = table.entries();
        for (int i = 0; i < entries.size(); i++) {
            collect(entries.get(i).reward(), rootPath + ".entries[" + i + "].reward", refs, 0);
        }
        return List.copyOf(refs);
    }

    /** One reward's own references, with its path. */
    public static List<Ref> refsOf(QuestReward reward, String path) {
        List<Ref> refs = new ArrayList<>();
        collect(reward, path, refs, 0);
        return List.copyOf(refs);
    }

    /**
     * One reward's references, its inline table's entries' references, and so on.
     *
     * <p>A depth cap rather than a cycle check: the tree is finite, but it is built by a decoder whose
     * input an author wrote, and a walk that cannot exceed a stated depth is a walk that cannot be the
     * reason a server thread dies. The fourth of the four walks {@link RewardTable#MAX_NESTING} names --
     * it used to write the number itself, with a comment maintaining the agreement by hand.
     */
    private static void collect(QuestReward reward, String path, List<Ref> refs, int depth) {
        if (depth > RewardTable.MAX_NESTING || !(reward instanceof TableReward table)) {
            return;
        }
        table.tableId().ifPresent(id -> refs.add(new Ref(id, path)));
        if (table.inline().isEmpty()) {
            return;
        }
        List<RewardTable.Entry> entries = table.inline().get().entries();
        for (int i = 0; i < entries.size(); i++) {
            collect(entries.get(i).reward(), path + ".inline.entries[" + i + "].reward", refs, depth + 1);
        }
    }
}
