package dev.ellipog.tasked.quest.reward;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * What a reward is allowed to touch when it is granted.
 *
 * <p>The player, and the facts about where the payout came from that a reward may legitimately need
 * to name: the server (to run a command), the team whose claim it answers, the quest and chapter ids
 * (for placeholders), and the online members (for a count). Nothing here reads or writes quest
 * <i>progress</i> — if a reward needs to, it is not a reward, it is a task. Keeping progress out is
 * what stops that distinction eroding.
 *
 * <p>The empty strings and the singleton member list of {@link #of} are the shape a reward sees when
 * it is granted outside a quest context (a command, a script), not a valid quest identity.
 */
public record RewardContext(ServerPlayer player, MinecraftServer server, UUID owner, String questId,
                            String chapterId, List<ServerPlayer> members) {

    public RewardContext {
        members = List.copyOf(members);
    }

    /** The player alone: enough for a reward that gives what it says and needs nothing else. */
    public static RewardContext of(ServerPlayer player) {
        return new RewardContext(player, player.getServer(), player.getUUID(), "", "", List.of(player));
    }
}
