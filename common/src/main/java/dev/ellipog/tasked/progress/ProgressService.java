package dev.ellipog.tasked.progress;

import dev.ellipog.tasked.Constants;
import dev.ellipog.tasked.quest.Chapter;
import dev.ellipog.tasked.quest.Quest;
import dev.ellipog.tasked.quest.QuestIndex;
import dev.ellipog.tasked.quest.QuestReward;
import dev.ellipog.tasked.quest.QuestTask;
import dev.ellipog.tasked.quest.TaskContext;
import dev.ellipog.tasked.quest.TaskedQuests;
import dev.ellipog.tasked.quest.reward.RewardContext;
import dev.ellipog.tasked.quest.reward.RewardTypes;
import dev.ellipog.tasked.quest.task.TaskTypes;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything that changes quest progress.
 *
 * <h2>The one place that writes</h2>
 *
 * <p>Task behaviours can only <i>observe</i>; rewards can only <i>give</i>. Recording, unlocking and
 * completing all happen here, so there is one implementation of "what it means to finish a quest"
 * and one place to look when progress goes wrong. A task that could write its own progress would
 * make that impossible to reason about.
 *
 * <h2>Who progress belongs to</h2>
 *
 * <p>Never a player — always a <b>team</b>. A solo player is in a team of one, whose id is their own
 * UUID (see {@code Team.solo}). So there is no branch anywhere here for "playing alone", and the same
 * code path serves both cases. Two players in a party share one {@link TeamProgress}, which is what
 * makes the plan's "two players on a dedicated server share progress correctly" fall out rather than
 * needing to be built.
 *
 * <h2>Evaluation cost</h2>
 *
 * <p>Resolving every quest's state is a walk over a few hundred nodes with a memoised map — cheap, and
 * it happens once per team per tick that anything needs it. Task evaluation is the expensive half,
 * because an item task walks an inventory: so each task is only evaluated when its own
 * {@code autoSubmitTicks} has elapsed, tracked per team in memory. Nothing about evaluation timing is
 * persisted, because losing it costs one extra evaluation after a restart and nothing else.
 */
public final class ProgressService {

    /**
     * When each task was last evaluated, keyed by team and then "questId#taskIndex".
     *
     * <p>In memory, and that is a choice rather than an oversight: it is a scheduling detail, not a
     * fact about the world. Persisting it would add a write per task per interval for a value whose
     * worst case on loss is one duplicate evaluation.
     */
    private static final Map<UUID, Map<String, Long>> LAST_EVALUATED = new LinkedHashMap<>();

    /**
     * The server and tick the last full evaluation ran on.
     *
     * <p>Needed because the hook is a <i>player</i> tick: with four players online it fires four times
     * per tick, evaluating every team four times over. The work is idempotent, so that would be
     * wasteful rather than wrong — but four inventory scans a second per quest, per player, is exactly
     * the kind of thing that gets a mod blamed for lag it did not cause.
     */
    private static MinecraftServer lastEvaluatedServer;
    private static long lastEvaluatedTick = Long.MIN_VALUE;

    private ProgressService() {
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /** The team a player's progress belongs to. */
    public static UUID progressOwner(MinecraftServer server, ServerPlayer player) {
        return dev.ellipog.armature.api.teams.Teams.teamOf(server, player.getUUID()).id();
    }

    /** Every quest's state, for whoever's progress this is. */
    public static ProgressionEngine.Resolution resolutionFor(MinecraftServer server, UUID owner) {
        QuestIndex index = TaskedQuests.index();
        TeamProgress progress = ProgressStore.of(server).progressOf(owner);
        return ProgressionEngine.resolve(index, progress, server.overworld().getGameTime());
    }

    public static TeamProgress progressFor(MinecraftServer server, UUID owner) {
        return ProgressStore.of(server).progressOf(owner);
    }

    // ------------------------------------------------------------------
    // Evaluation
    // ------------------------------------------------------------------

    /**
     * Evaluates every online team's tasks and completes any quest that is now finished.
     *
     * <p>Called once per player tick. Almost every call does nothing, because each task has its own
     * interval and most will not be due — an item task defaulting to every twenty ticks means one
     * inventory scan per second per relevant quest, which is nothing. Raising a task's
     * {@code autoSubmitTicks} is the knob for an expensive one.
     */
    public static void tick(MinecraftServer server) {
        QuestIndex index = TaskedQuests.index();
        if (index.isEmpty()) {
            return;
        }

        long now = server.overworld().getGameTime();

        // The hook is a player tick, so this fires once per player. Everything below is per team, and
        // most players are alone or in one party -- so without this guard a party of four would have
        // its shared progress evaluated four times every tick.
        if (server == lastEvaluatedServer && now == lastEvaluatedTick) {
            return;
        }
        lastEvaluatedServer = server;
        lastEvaluatedTick = now;

        // One pass per team, not per player: two members of a party share progress, so evaluating it
        // twice per tick would do the same work again and could race on the same stored value.
        Map<UUID, ServerPlayer> representativeOf = new LinkedHashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            representativeOf.putIfAbsent(progressOwner(server, player), player);
        }

        for (Map.Entry<UUID, ServerPlayer> entry : representativeOf.entrySet()) {
            evaluateTeam(server, index, entry.getKey(), entry.getValue(), now);
        }
    }

    /**
     * Evaluates one team's tasks, and completes what is finished.
     *
     * <h2>Why {@code working} exists rather than reusing {@code progress}</h2>
     *
     * <p>{@link #complete} writes to the store, and the local variable does not see that write. So the
     * loop carries its own copy forward. Without that, completing one quest and then recording progress
     * on the next would write back a {@link TeamProgress} holding the <i>pre-completion</i> value for
     * the first quest — silently undoing it. The completion would appear to work, then vanish the next
     * time the player did anything.
     */
    private static void evaluateTeam(MinecraftServer server,
                                     QuestIndex index,
                                     UUID owner,
                                     ServerPlayer anyMember,
                                     long now) {
        ProgressStore store = ProgressStore.of(server);
        TeamProgress working = store.progressOf(owner);
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, working, now);

        Map<String, Long> lastEvaluated = LAST_EVALUATED.computeIfAbsent(owner, key -> new LinkedHashMap<>());

        for (QuestIndex.QuestEntry entry : index.quests()) {
            Quest quest = entry.quest();
            if (!resolution.stateOf(quest).isPlayable()) {
                continue;
            }

            QuestProgress questProgress = working.progressOf(quest);
            boolean chapterConsumes = chapterOf(index, entry)
                    .map(Chapter::defaultConsumeItems)
                    .orElse(false);

            for (int taskIndex = 0; taskIndex < quest.tasks().size(); taskIndex++) {
                QuestTask task = quest.tasks().get(taskIndex);

                if (ProgressionEngine.isTaskSatisfied(quest, taskIndex, questProgress)) {
                    continue;
                }

                // A final local, because the lambda below cannot capture `questProgress`: it is
                // reassigned further down this loop, so it is not effectively final. Java refuses
                // that outright, and it is right to -- a deferred lambda reading a variable that
                // moves is a bug waiting to be written.
                //
                // A snapshot is also the correct reading rather than a workaround. isTaskUnlocked
                // runs the predicate synchronously, so the state it should judge against is exactly
                // the progress as it stands at this moment.
                final QuestProgress beforeThisTask = questProgress;
                if (!quest.isTaskUnlocked(taskIndex,
                        earlier -> ProgressionEngine.isTaskSatisfied(quest, earlier, beforeThisTask))) {
                    continue;
                }

                String key = quest.id() + "#" + taskIndex;
                long dueAt = lastEvaluated.getOrDefault(key, Long.MIN_VALUE);
                int interval = task.common().autoSubmitTicks();
                if (now - dueAt < interval) {
                    continue;
                }
                lastEvaluated.put(key, now);

                Optional<dev.ellipog.tasked.quest.task.TaskBehaviour<QuestTask>> behaviour =
                        TaskTypes.behaviourOf(task);
                if (behaviour.isEmpty()) {
                    continue;
                }

                TaskContext context = new TaskContext(anyMember, index, now);
                int required = behaviour.get().required(task);
                int current = behaviour.get().current(task, context);

                // Progress only ever goes up. A consuming task zeroes its own count the moment the
                // items are taken, so without this the task would un-complete itself.
                int recorded = questProgress.progressOf(taskIndex);
                int best = Math.max(recorded, current);
                if (best <= recorded) {
                    continue;
                }
                questProgress = questProgress.recordTask(taskIndex, best);

                if (best >= required && consumes(task, chapterConsumes)) {
                    consume(anyMember, task, required);
                }
            }

            // Completion is checked after the task pass, so a quest whose last task was satisfied
            // above completes on the same evaluation rather than one interval later.
            if (ProgressionEngine.tasksSatisfied(quest, questProgress)) {
                working = complete(server, owner, anyMember, entry, working.put(quest, questProgress));
            }
            else if (!questProgress.equals(working.progressOf(quest))) {
                working = working.put(quest, questProgress);
            }
        }

        store.put(owner, working);
    }

    // ------------------------------------------------------------------
    // Completing
    // ------------------------------------------------------------------

    /**
     * Marks a quest complete and grants its rewards.
     *
     * <h2>The order here is the whole point</h2>
     *
     * <p>Rewards are marked claimed and <b>saved</b> before any of them is handed over. So a crash
     * midway through granting leaves a quest marked claimed with some items given — losing the rest
     * rather than duplicating them. Duplicating a diamond is an exploit; losing one to a crash is an
     * annoyance. That is the direction the plan chose, and it is the only defensible one.
     *
     * <p>Repeatable quests keep their {@code timesCompleted} and clear their task progress, so the
     * next round starts clean while dependents stay satisfied — a chain following a repeatable quest
     * does not lock again every time the player redos it.
     *
     * @return the progress with this completion recorded. <b>Callers must use it</b>: the store has
     *         been written, but the value passed in does not know that.
     */
    public static TeamProgress complete(MinecraftServer server,
                                        UUID owner,
                                        ServerPlayer player,
                                        QuestIndex.QuestEntry entry,
                                        TeamProgress progress) {
        Quest quest = entry.quest();
        QuestProgress current = progress.progressOf(quest);

        if (current.state() == QuestState.COMPLETED && current.rewardsClaimed()) {
            // Already done and already paid. Without this guard a repeatable quest whose cooldown has
            // not elapsed would be re-granted its rewards on every single evaluation.
            return progress;
        }

        long now = server.overworld().getGameTime();

        QuestProgress claimed = current.completedAt(now).withRewardsClaimed(false);
        if (quest.repeatable()) {
            claimed = claimed.resetTasks();
        }

        // Step one: record it, rewards marked unclaimed, and save. A crash after this point loses
        // the rewards rather than duplicating them.
        TeamProgress saved = progress.put(quest, claimed);
        ProgressStore.of(server).put(owner, saved);

        // Step two: grant, then mark claimed and save again.
        grantRewards(player, quest);

        TeamProgress finished = saved.put(quest, claimed.withRewardsClaimed(true));
        ProgressStore.of(server).put(owner, finished);

        Constants.LOG.info("tasked: {} completed '{}' for team {}",
                player.getScoreboardName(), quest.id(), owner);
        player.displayClientMessage(
                Component.translatable("tasked.quest.completed", quest.title().component()), false);

        return finished;
    }

    private static void grantRewards(ServerPlayer player, Quest quest) {
        if (quest.rewards().isEmpty()) {
            return;
        }
        RewardContext context = new RewardContext(player);
        for (QuestReward reward : quest.rewards()) {
            Optional<dev.ellipog.tasked.quest.reward.RewardBehaviour<QuestReward>> behaviour =
                    RewardTypes.behaviourOf(reward);
            if (behaviour.isEmpty()) {
                Constants.LOG.warn("tasked: no behaviour registered for reward type {}; skipped", reward.type());
                continue;
            }
            try {
                behaviour.get().grant(reward, context);
            }
            catch (RuntimeException e) {
                // One bad reward must not abort the rest, and must not leave the quest unclaimed --
                // which would re-grant everything on the next evaluation.
                Constants.LOG.error("tasked: granting a {} reward failed; the rest were still given",
                        reward.type(), e);
            }
        }
    }

    // ------------------------------------------------------------------
    // Submitting by hand
    // ------------------------------------------------------------------

    /**
     * A player submits one task by hand — a checkmark, or an item task that consumes.
     *
     * <p>Refused when the task cannot be satisfied right now, so a submit button that is showing
     * cannot be used to skip the requirement. For an item task the check is "they actually have the
     * items"; for a checkmark there is nothing to check, which is the point of a checkmark.
     *
     * @return whether anything changed
     */
    public static boolean submit(MinecraftServer server, ServerPlayer player, QuestIndex.QuestEntry entry, int taskIndex) {
        Quest quest = entry.quest();
        if (taskIndex < 0 || taskIndex >= quest.tasks().size()) {
            return false;
        }

        UUID owner = progressOwner(server, player);
        ProgressStore store = ProgressStore.of(server);
        TeamProgress progress = store.progressOf(owner);
        long now = server.overworld().getGameTime();

        // Keep it honest: submitting has to be allowed for the quest.
        QuestState state = ProgressionEngine.resolve(TaskedQuests.index(), progress, now).stateOf(quest);
        if (!state.isPlayable()) {
            return false;
        }

        QuestTask task = quest.tasks().get(taskIndex);
        QuestProgress questProgress = progress.progressOf(quest);

        // A final snapshot for the lambda below, because `questProgress` is reassigned further down
        // and Java will not let a lambda capture a local that moves. That refusal is correct: a
        // deferred lambda reading a variable that changes underneath it is a bug waiting to be
        // written. And a snapshot is the right reading anyway -- isTaskUnlocked runs its predicate
        // synchronously, so it should judge against the progress as it stands at this moment, before
        // this submit changes anything.
        final QuestProgress beforeSubmit = questProgress;

        if (!quest.isTaskUnlocked(taskIndex,
                earlier -> ProgressionEngine.isTaskSatisfied(quest, earlier, beforeSubmit))) {
            return false;
        }

        Optional<dev.ellipog.tasked.quest.task.TaskBehaviour<QuestTask>> behaviour = TaskTypes.behaviourOf(task);
        if (behaviour.isEmpty() || !behaviour.get().canSubmitByHand(task)) {
            return false;
        }

        boolean chapterConsumes = chapterOf(TaskedQuests.index(), entry)
                .map(Chapter::defaultConsumeItems)
                .orElse(false);

        if (consumes(task, chapterConsumes)) {
            int required = behaviour.get().required(task);
            if (!hasEnough(player, task, required)) {
                player.displayClientMessage(Component.translatable("tasked.quest.not_enough"), true);
                return false;
            }
            consume(player, task, required);
        }

        questProgress = questProgress.recordTask(taskIndex, behaviour.get().required(task));
        progress = progress.put(quest, questProgress);
        store.put(owner, progress);

        // Submitting can finish the quest, and for a checkmark that is the only way it ever will.
        if (ProgressionEngine.tasksSatisfied(quest, questProgress)) {
            complete(server, owner, player, entry, progress);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Resetting
    // ------------------------------------------------------------------

    /**
     * Clears progress for one quest, or everything.
     *
     * <p>Removes the entry rather than setting it to a locked state, so the quest recomputes from the
     * dependency graph — which is what "reset" has to mean for a quest whose dependencies are also
     * reset.
     *
     * @return how many quests were cleared
     */
    public static int reset(MinecraftServer server, UUID owner, Optional<String> questIdOrAlias) {
        ProgressStore store = ProgressStore.of(server);
        TeamProgress progress = store.progressOf(owner);

        if (questIdOrAlias.isEmpty()) {
            int count = progress.size();
            store.clear(owner);
            LAST_EVALUATED.remove(owner);
            return count;
        }

        Optional<QuestIndex.QuestEntry> entry = TaskedQuests.find(questIdOrAlias.get());
        if (entry.isEmpty()) {
            return 0;
        }
        store.put(owner, progress.remove(entry.get().quest()));
        LAST_EVALUATED.remove(owner);
        return 1;
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static boolean consumes(QuestTask task, boolean chapterDefault) {
        if (task instanceof dev.ellipog.tasked.quest.task.ItemTask item) {
            return item.consumes(chapterDefault);
        }
        return false;
    }

    private static boolean hasEnough(ServerPlayer player, QuestTask task, int required) {
        if (!(task instanceof dev.ellipog.tasked.quest.task.ItemTask item)) {
            return true;
        }
        return countMatching(player, item) >= required;
    }

    private static int countMatching(ServerPlayer player, dev.ellipog.tasked.quest.task.ItemTask task) {
        ItemStack template = task.item().toStack();
        if (template.isEmpty()) {
            return 0;
        }
        int found = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, template)) {
                found += stack.getCount();
            }
        }
        return found;
    }

    /**
     * Takes {@code count} matching items from the player's inventory.
     *
     * <p>Removes from the first matching slots and, where a slot held more than was needed, shrinks
     * that stack rather than removing it — so taking three of five logs leaves two behind rather than
     * eating the whole stack.
     */
    private static void consume(ServerPlayer player, QuestTask task, int count) {
        if (!(task instanceof dev.ellipog.tasked.quest.task.ItemTask item)) {
            return;
        }
        ItemStack template = item.item().toStack();
        if (template.isEmpty()) {
            return;
        }

        int remaining = count;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, template)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
            inventory.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
        }
        inventory.setChanged();
    }

    private static Optional<Chapter> chapterOf(QuestIndex index, QuestIndex.QuestEntry entry) {
        return index.chapter(entry.chapterId()).map(QuestIndex.ChapterEntry::chapter);
    }

    /** Everything a player can act on right now, for a listing. Invisible quests are left out. */
    public static List<QuestIndex.QuestEntry> availableTo(MinecraftServer server, ServerPlayer player) {
        ProgressionEngine.Resolution resolution = resolutionFor(server, progressOwner(server, player));
        List<QuestIndex.QuestEntry> out = new ArrayList<>();
        for (QuestIndex.QuestEntry entry : TaskedQuests.index().quests()) {
            if (resolution.stateOf(entry.quest()).isPlayable() && !entry.quest().invisible()) {
                out.add(entry);
            }
        }
        return out;
    }
}
