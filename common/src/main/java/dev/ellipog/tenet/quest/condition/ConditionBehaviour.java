package dev.ellipog.tenet.quest.condition;

/**
 * How a condition type decides.
 *
 * <p>One method, and deliberately: a gate is a predicate, and everything a condition needs beyond the
 * answer lives on its record, where the codec put it. A behaviour is registered with its type, so the
 * registry is the only thing that can disagree with itself — and its widening wrapper is pinned by a
 * test for exactly that reason.
 */
@FunctionalInterface
public interface ConditionBehaviour<T extends QuestCondition> {

    /** Whether this condition is met for the subject. */
    boolean test(T condition, ConditionContext context);
}
