package dev.ellipog.tasked.quest.reward;

/**
 * How a reward type hands something over.
 *
 * <p>One method, and the player is passed in rather than returned as a list of items. That is
 * deliberate: a reward that grants items has to cope with a full inventory, and the way it copes — and
 * where the overflow goes — is the reward type's business, not the engine's.
 *
 * <h2>Granting must be idempotent-safe, but is not required to be idempotent</h2>
 *
 * <p>Called from exactly one place — {@code ProgressService.claim}, which a player triggers — and that
 * call marks the rewards collected and <b>saves</b> before invoking this, persisting immediately. So a
 * crash midway through granting leaves a quest marked collected with some rewards given, losing the
 * remaining items rather than duplicating them.
 *
 * <p>That is the deliberate choice. Duplicating a diamond is an exploit; losing one to a crash is an
 * annoyance. FTB Quests takes the same direction, and it is the only defensible one.
 */
public interface RewardBehaviour<T> {

    /**
     * Hands the reward to {@code player}.
     *
     * <p>Runs on the server thread. Implementations must not throw for an ordinary failure — a full
     * inventory is ordinary, and must not abort the remaining rewards on the same quest.
     */
    void grant(T reward, RewardContext context);
}
