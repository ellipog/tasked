package dev.ellipog.tasked.client.dev;

import dev.ellipog.tasked.progress.QuestState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

/**
 * The visibility family: whether a quest is drawn, and which parts of its card are readable.
 *
 * <h2>Why this is its own class, with its own interface</h2>
 *
 * <p>Every rule here is a boolean function of things the client already knows — the quest's flags, its
 * dependencies, the team's progress — and none of it needs a screen. The screen cannot be instantiated
 * by a test; this can. So the rules live here and are asserted directly, and the canvas and the card only
 * ask the two questions this class answers. The alternative is what the codebase has already paid for
 * once: behaviour that exists only inside a drawing method, so the only way to test it is to look at a
 * screenshot and hope the flag that was set is the flag being read.
 *
 * <h2>The two families, kept apart</h2>
 *
 * <p>{@code invisible} and {@code invisibleUntilTasks} are about the quest <b>existing</b> yet: it is
 * drawn when it is completed, or — with a task count — once that many of its tasks have progress. That
 * second half is the easter-egg case: a quest nobody can see until they have stumbled onto part of it.
 *
 * <p>The {@code hideUntil...} flags are about the quest being <b>revealed by its prerequisites</b>:
 * hidden until the prerequisite rule is met, or until at least one prerequisite is itself visible. The
 * second is recursive, which is what makes a chain of hidden quests reveal itself one link at a time.
 *
 * <p>A completed quest is always visible and always readable. That is not a special case bolted on: it
 * is what makes the flags safe to author, because nothing can be permanently lost behind one — the quest
 * that completes is the quest that shows itself, whatever the flags say.
 */
public final class QuestVisibility {

    /**
     * What the rules need to know about one quest, without the cache.
     *
     * <p>An interface rather than the cache's own {@code Entry}, because the recursion has to ask about
     * quests in <b>other chapters</b> — a cross-chapter prerequisite counts, which is the whole reason a
     * dependency is resolved globally — and it has to answer for an id the cache has never heard of. A
     * lookup that answers with the flags of one quest can serve both.
     */
    public interface Lookup {

        boolean invisible(String id);

        int invisibleUntilTasks(String id);

        boolean hideUntilDependenciesComplete(String id);

        boolean hideUntilDependenciesVisible(String id);

        List<String> dependencies(String id);
    }

    private QuestVisibility() {
    }

    /**
     * Whether a quest is drawn at all, on the canvas and anywhere else that lists quests.
     *
     * @param states      the team's state for a quest id — {@link QuestState#LOCKED} for one it does not
     *     know, which is the reading that hides least
     * @param ruleMet     whether a quest's prerequisite <b>rule</b> is satisfied. A function rather than a
     *     recomputation here, because "met" is {@code DependencyProgress}'s question and it already
     *     knows about {@code minRequired}, the started-based modes and per-edge satisfaction — asking it
     *     twice, in two places, is how the canvas and the card would come to disagree
     * @param tasksWithProgress how many of a quest's tasks have any progress. The same number the card
     *     prints beside each task, so a quest that appears when three tasks are touched says so with the
     *     count the author set
     */
    public static boolean visible(String id, Lookup lookup, Function<String, QuestState> states,
                                  Function<String, Boolean> ruleMet,
                                  Function<String, Integer> tasksWithProgress) {
        return visible(id, lookup, states, ruleMet, tasksWithProgress, new ArrayDeque<>());
    }

    private static boolean visible(String id, Lookup lookup, Function<String, QuestState> states,
                                   Function<String, Boolean> ruleMet,
                                   Function<String, Integer> tasksWithProgress,
                                   Deque<String> visiting) {
        if (states.apply(id) == QuestState.COMPLETED) {
            // Done, therefore shown. Every flag below is about *not yet*; a quest that is over has
            // nothing left to hide.
            return true;
        }

        if (lookup.invisible(id)) {
            int until = lookup.invisibleUntilTasks(id);
            return until > 0 && tasksWithProgress.apply(id) >= until;
        }

        if (visiting.contains(id)) {
            // A cycle. The loader has already reported it, and this walk is not the place to report it
            // again -- but it has to terminate. An already-visiting quest is treated as *visible*, which
            // breaks the loop in the direction that shows quests: a cycle is an author's mistake, and a
            // whole chapter silently vanishing is a much worse way to find out about it than seeing the
            // two quests that point at each other.
            return true;
        }

        visiting.push(id);
        try {
            if (lookup.hideUntilDependenciesComplete(id) && !ruleMet.apply(id)) {
                return false;
            }
            if (lookup.hideUntilDependenciesVisible(id)) {
                // Any one dependency being visible is enough, and "visible" is this same question about
                // that dependency -- which is the recursion: a chain of hidden quests reveals itself
                // from its first visible link outwards, one prerequisite at a time.
                //
                // A quest with no dependencies at all is visible, and that is a correction rather than a
                // special case: the loop below never runs, so the answer used to be "hidden forever
                // unless completed" -- while the sibling flag `hideUntilDependenciesComplete` reads an
                // empty rule as *satisfied* (requiredCount of zero dependencies is zero). Two flags
                // about the same prerequisites disagreeing about the empty case is the kind of thing an
                // author discovers by staring at a canvas, so an empty rule means met here too.
                boolean any = lookup.dependencies(id).isEmpty();
                for (String dependency : lookup.dependencies(id)) {
                    if (visible(dependency, lookup, states, ruleMet, tasksWithProgress, visiting)) {
                        any = true;
                        break;
                    }
                }
                if (!any) {
                    return false;
                }
            }
            return true;
        }
        finally {
            visiting.pop();
        }
    }

    /**
     * Whether this quest's incoming dependency lines are drawn.
     *
     * <p>The one flag in the family that is not about the quest: the quest is visible and the lines into
     * it are not. FTB Quests has it for the same reason — a hub that twenty quests feed into draws twenty
     * lines across the chapter, and the author may want the hub without the clutter.
     */
    public static boolean drawsDependencyLines(boolean hideDependencyLines) {
        return !hideDependencyLines;
    }

    /**
     * Whether the card's description is readable.
     *
     * <p>Hidden until completed, so the card is a name and an icon and nothing else — for a quest whose
     * description would give away what it is asking for.
     */
    public static boolean showsText(boolean hideTextUntilComplete, QuestState state) {
        return !hideTextUntilComplete || state == QuestState.COMPLETED;
    }

    /**
     * Whether the card's task details are readable.
     *
     * <p>Hidden until the quest can be started, which is the stricter of the two: unlike the
     * description, the task list is what an author may want withheld until the quest is actually
     * reachable — a locked quest's tasks describe content the player cannot touch yet.
     */
    public static boolean showsDetails(boolean hideDetailsUntilStartable, QuestState state) {
        return !hideDetailsUntilStartable || state.isAtLeast(QuestState.UNLOCKED);
    }
}
