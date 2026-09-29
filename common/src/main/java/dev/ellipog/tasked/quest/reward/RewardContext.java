package dev.ellipog.tasked.quest.reward;

import net.minecraft.server.level.ServerPlayer;

/**
 * What a reward is allowed to touch when it is granted.
 *
 * <p>The player, and that is all. A reward has no business reading quest state or writing progress —
 * if it needs to, it is not a reward, it is a task. Keeping this to one field is what stops that
 * distinction eroding.
 */
public record RewardContext(ServerPlayer player) {
}
