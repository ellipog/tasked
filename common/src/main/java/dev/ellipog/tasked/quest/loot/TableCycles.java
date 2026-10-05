package dev.ellipog.tasked.quest.loot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The named tables a table can reach, and whether that includes itself.
 *
 * <h2>Why this is checked at load time as well as at grant time</h2>
 *
 * <p>{@code TableReward.grantAll} already cuts a roll off past eight levels deep, so a cycle cannot
 * hang a server. What it cannot do is tell an author what they did: the reward simply pays nothing and
 * the log line is a warning nobody reads. A cycle is a file mistake like a circular {@code dependsOn},
 * and this mod's answer to that one is an error with the whole loop printed — so a table cycle is
 * reported the same way, on the file the loop starts in.
 *
 * <h2>Inline tables are leaves, and that is why the graph is small</h2>
 *
 * <p>Nothing can reference an inline table by id — it has no id — so only named tables are nodes. An
 * inline table's own references are attributed to the file that contains it, which is exactly what
 * {@link RewardTableRefs} does. A cycle therefore always runs through named tables, and the graph is
 * one node per file.
 */
public final class TableCycles {

    private TableCycles() {
    }

    /**
     * The first cycle in the graph, as the chain that closes it ({@code a, b, a}), or empty.
     *
     * <p>The first rather than all of them: one error per file is what an author can act on, and
     * reporting every loop in a broken pack buries the one they are looking at. Fixing one and
     * reloading finds the next.
     *
     * <p>Deterministic: the walk visits the map's own order (the loader's name order) and each node's
     * references in the order met, so the same files always report the same chain.
     */
    public static Optional<List<String>> find(Map<String, RewardTable> tables) {
        Set<String> finished = new LinkedHashSet<>();
        for (String id : tables.keySet()) {
            if (finished.contains(id)) {
                continue;
            }
            Deque<String> path = new ArrayDeque<>();
            Set<String> onPath = new LinkedHashSet<>();
            List<String> cycle = walk(id, tables, path, onPath, finished);
            if (cycle != null) {
                return Optional.of(List.copyOf(cycle));
            }
        }
        return Optional.empty();
    }

    /**
     * One node's depth-first step: null when nothing below it cycles, else the chain.
     *
     * <p>{@code onPath} is the grey set and {@code finished} the black one, in the classic colouring —
     * the path is what the chain is read off, and the finished set is what keeps the walk linear rather
     * than exponential on a graph with shared children.
     */
    private static List<String> walk(String id, Map<String, RewardTable> tables, Deque<String> path,
                                     Set<String> onPath, Set<String> finished) {
        if (finished.contains(id)) {
            return null;
        }
        if (!onPath.add(id)) {
            // Back at a node on the current path: the chain from its first appearance to here, closed
            // by naming it once more so the message reads as a loop rather than a list.
            List<String> chain = new ArrayList<>();
            boolean started = false;
            for (String step : path) {
                if (!started && step.equals(id)) {
                    started = true;
                }
                if (started) {
                    chain.add(step);
                }
            }
            chain.add(id);
            return chain;
        }
        path.addLast(id);
        RewardTable table = tables.get(id);
        if (table != null) {
            for (String next : RewardTableRefs.idsOf(table)) {
                if (!tables.containsKey(next)) {
                    // A dangling reference: the loader's own check reports it, and following it here
                    // would only duplicate the message under a different file.
                    continue;
                }
                List<String> cycle = walk(next, tables, path, onPath, finished);
                if (cycle != null) {
                    return cycle;
                }
            }
        }
        path.removeLast();
        onPath.remove(id);
        finished.add(id);
        return null;
    }
}
