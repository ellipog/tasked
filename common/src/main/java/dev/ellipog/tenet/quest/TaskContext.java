package dev.ellipog.tenet.quest;

import net.minecraft.server.level.ServerPlayer;

/**
 * What a task's behaviour is allowed to look at when deciding whether it is satisfied.
 *
 * <p>Deliberately narrow: the player, the loaded quests, and the current game time — and nothing
 * else. No server handle to poke at, no progress store to write to. Progress is recorded by the
 * engine, not by the task, which is what keeps a misbehaving task from corrupting the store and what
 * makes every task a pure function of the world.
 *
 * <h2>Why this lives in {@code quest} rather than in {@code progress}</h2>
 *
 * <p>Because it is part of the task type's contract — it is the parameter of
 * {@link dev.ellipog.tenet.quest.task.TaskBehaviour#current}, which an addon implements. Putting it
 * with the progress machinery would make {@code quest.task} depend on {@code progress} while
 * {@code progress} already depends on {@code quest.task} for the type registry: a package cycle, legal
 * in Java and unpleasant to reason about.
 *
 * <p>Here, the dependency runs one way. {@code progress} knows about {@code quest}; {@code quest}
 * knows about nothing but vanilla.
 *
 * <p>The index is on it because some task types refer to other quests — a structure task that checks
 * whether a chapter is done, or a task conditional on another quest's completion.
 */
public record TaskContext(ServerPlayer player, QuestIndex index, long gameTime) {
}
