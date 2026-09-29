package dev.ellipog.tasked.progress;

import java.util.Map;

/**
 * One team's progress through one quest.
 *
 * <h2>Task progress is keyed by position, and that is a real limitation</h2>
 *
 * <p>{@code taskProgress} maps a task's index to how far along it is. Reordering the tasks in a quest
 * file therefore moves progress to a different task — insert a task at the top and every existing
 * progress value shifts down one.
 *
 * <p>Keyed this way anyway, because the alternative is worse. Giving every task an author-chosen id
 * means every task in every file needs an id, forever, and the files get noisier for a case that
 * almost never comes up. Keying by a hash of the task's content is worse still: editing a count
 * would silently orphan the progress.
 *
 * <p>What actually happens in practice is that reordering a quest's tasks resets partial progress in
 * that one quest, and the next time a player does anything the progress recomputes from their
 * inventory. Nothing is lost that was not derivable. The failure mode is mild enough to be worth the
 * simpler file — but it is a real trade, so it is written down rather than discovered.
 *
 * <p>{@code timesCompleted} and {@code lastCompletedAt} exist for repeatable quests. A non-repeatable
 * quest only ever has {@code timesCompleted} 0 or 1.
 *
 * @param state           how far it has got
 * @param taskProgress    task index to how far along that task is
 * @param rewardsClaimed  whether the completion rewards have been handed over. Separate from
 *                        {@code state} on purpose: a player can be at COMPLETED without having been
 *                        given their items, and that window is where a crash would otherwise
 *                        duplicate every reward
 * @param timesCompleted  how many times a repeatable quest has been finished
 * @param lastCompletedAt game time in ticks of the last completion, for a cooldown
 */
public record QuestProgress(QuestState state,
                            Map<Integer, Integer> taskProgress,
                            boolean rewardsClaimed,
                            int timesCompleted,
                            long lastCompletedAt) {

    /** Nothing done. What a quest with no stored progress looks like. */
    public static final QuestProgress NONE =
            new QuestProgress(QuestState.LOCKED, Map.of(), false, 0, 0L);

    public QuestProgress {
        taskProgress = Map.copyOf(taskProgress);
    }

    /** How far along task {@code index} is. Zero if nothing recorded. */
    public int progressOf(int index) {
        return taskProgress.getOrDefault(index, 0);
    }

    public QuestProgress withState(QuestState state) {
        return new QuestProgress(state, taskProgress, rewardsClaimed, timesCompleted, lastCompletedAt);
    }

    /** Records progress only if it improved. Progress never goes backwards — see {@link #recordTask}. */
    public QuestProgress recordTask(int index, int amount) {
        int current = progressOf(index);
        if (amount <= current) {
            return this;
        }
        Map<Integer, Integer> next = new java.util.LinkedHashMap<>(taskProgress);
        next.put(index, amount);
        return new QuestProgress(state, next, rewardsClaimed, timesCompleted, lastCompletedAt);
    }

    public QuestProgress withRewardsClaimed(boolean claimed) {
        return new QuestProgress(state, taskProgress, claimed, timesCompleted, lastCompletedAt);
    }

    /** Clears task progress, for a repeatable quest starting another round. */
    public QuestProgress resetTasks() {
        return new QuestProgress(state, Map.of(), false, timesCompleted + 1, lastCompletedAt);
    }

    public QuestProgress completedAt(long gameTime) {
        return new QuestProgress(QuestState.COMPLETED, taskProgress, rewardsClaimed, timesCompleted, gameTime);
    }

    /**
     * Whether a repeatable quest's cooldown has elapsed.
     *
     * @param now        game time in ticks
     * @param cooldownTicks how long the cooldown is; zero means there is none
     */
    public boolean cooldownElapsed(long now, int cooldownTicks) {
        if (cooldownTicks <= 0) {
            return true;
        }
        return now - lastCompletedAt >= cooldownTicks;
    }

    /** Ticks still to wait, or zero. For a message that says how long rather than just "no". */
    public long cooldownRemaining(long now, int cooldownTicks) {
        if (cooldownTicks <= 0) {
            return 0L;
        }
        return Math.max(0L, cooldownTicks - (now - lastCompletedAt));
    }

    /** Whether any task has been touched. Used to decide between UNLOCKED and STARTED. */
    public boolean anyTaskProgress() {
        return taskProgress.values().stream().anyMatch(value -> value > 0);
    }
}
