package dev.ellipog.tenet.net;

import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.armature.api.data.Problems;

import java.util.HashSet;
import java.util.Set;

/**
 * Whether a load's problems are worth telling the players about.
 *
 * <p>A reload re-reports every fault it finds, and a refresh follows every applied op — so
 * without this, one drag re-toasts sixteen pre-existing warnings that nobody caused and
 * nobody can act on from the toast. A report is news only when its set of problems moved:
 * same faults, no message. Joiners are covered elsewhere (the join path sends the last
 * report), so skipping a repeat loses nobody any fact.
 */
public final class ProblemsSync {

    private ProblemsSync() {
    }

    /** True when {@code next} says anything {@code current} did not already say. */
    public static boolean changed(Problems current, Problems next) {
        if (current == null || next == null) {
            return current != next;
        }
        Set<DataProblem> before = new HashSet<>(current.all());
        Set<DataProblem> after = new HashSet<>(next.all());
        return !before.equals(after);
    }
}
