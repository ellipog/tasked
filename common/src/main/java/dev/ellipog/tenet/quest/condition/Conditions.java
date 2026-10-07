package dev.ellipog.tenet.quest.condition;

import java.util.ArrayList;
import java.util.List;

/**
 * The one place a list of conditions is evaluated.
 *
 * <p>AND: every condition must hold, and an empty list holds trivially — which is what makes
 * "conditions nobody wrote" free rather than a check every call site must remember to skip.
 *
 * <p>An unregistered type evaluates as <b>not met</b>. That direction is the safe one for a gate: a
 * condition whose codec this build cannot find would otherwise be a lock that silently opens. It is
 * also unreachable in practice — the dispatch refuses to decode an unknown type, and the validator
 * refuses the file at its own line.
 */
public final class Conditions {

    private Conditions() {
    }

    /** Whether every condition in the list is met for the subject. */
    public static boolean passes(List<QuestCondition> conditions, ConditionContext context) {
        for (QuestCondition condition : conditions) {
            if (!met(condition, context)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The indices of the conditions that are not met, ascending.
     *
     * <p>What the wire mask is built from: a locked row's hover names exactly what is missing, rather
     * than listing conditions that are satisfied alongside the one that is not.
     */
    public static List<Integer> unmet(List<QuestCondition> conditions, ConditionContext context) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < conditions.size(); i++) {
            if (!met(conditions.get(i), context)) {
                out.add(i);
            }
        }
        return List.copyOf(out);
    }

    private static boolean met(QuestCondition condition, ConditionContext context) {
        return ConditionTypes.behaviourOf(condition)
                .map(behaviour -> behaviour.test(condition, context))
                .orElse(false);
    }
}
