package dev.ellipog.tasked.quest.condition;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Who a condition is being asked about, and on what server.
 *
 * <p>A condition is asked once per player — the member whose count is being read on a shared task, or
 * the player a reward is being paid to — so the player here is the subject, not "the team". A party
 * condition is the same predicate for every member; a stage, inventory or score condition is not, and
 * the engine's per-member asking is what makes that the natural reading rather than a special case.
 *
 * @param player the player the condition is asked about
 * @param server the server they are on
 * @param owner  the progress owner this evaluation belongs to — the UUID a party's shared progress is
 *               keyed by. {@code tasked:party_size} resolves it to the team; a solo player is a party
 *               of one.
 */
public record ConditionContext(ServerPlayer player, MinecraftServer server, UUID owner) {
}
