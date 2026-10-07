package dev.ellipog.tenet.progress;

import dev.ellipog.tenet.api.TenetEvents;
import dev.ellipog.tenet.net.TenetNetworking;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.UUID;

/**
 * Stages, as everything else asks about them: the command, the stage reward, a quest's gate, and -- once
 * the scripting integration is in -- a pack's scripts.
 *
 * <h2>One method per change, not three calls per site</h2>
 *
 * <p>Granting a stage is a write, a sync to that player's client and an event, and doing those three at
 * every call site is how they drift: a stage saved but not synced is a client script that disagrees with
 * the server, and a stage synced but not announced is a listener that never runs. So the three live here
 * together, and a caller asks for the change rather than assembling it.
 *
 * <p>The write is per player, which is what a stage means -- see {@link ProgressStore}'s note. A player who
 * is offline takes the write and skips the rest: a script may legitimately grant a stage to somebody who is
 * not here, and refusing that would be the worse half of the trade.
 */
public final class StageService {

    private StageService() {
    }

    /** Whether this player has the stage. */
    public static boolean has(MinecraftServer server, UUID player, ResourceLocation stage) {
        return ProgressStore.of(server).hasStage(player, stage);
    }

    /** The same, for a player who is here. */
    public static boolean has(MinecraftServer server, ServerPlayer player, ResourceLocation stage) {
        return has(server, player.getUUID(), stage);
    }

    /** Every stage this player has, in the order they were granted. */
    public static Set<ResourceLocation> list(MinecraftServer server, UUID player) {
        return ProgressStore.of(server).stagesOf(player);
    }

    /**
     * Grants a stage.
     *
     * @return whether anything changed -- false when they already had it, which is what makes a stage reward
     *         idempotent and what the event is gated on
     */
    public static boolean add(MinecraftServer server, UUID player, ResourceLocation stage) {
        if (!ProgressStore.of(server).addStage(player, stage)) {
            return false;
        }
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            TenetNetworking.sendStagesTo(online);
            TenetEvents.STAGE_ADDED.invoker().onStageAdded(online, stage);
        }
        return true;
    }

    /** Takes a stage away, and returns whether it was there to take. */
    public static boolean remove(MinecraftServer server, UUID player, ResourceLocation stage) {
        if (!ProgressStore.of(server).removeStage(player, stage)) {
            return false;
        }
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            TenetNetworking.sendStagesTo(online);
            TenetEvents.STAGE_REMOVED.invoker().onStageRemoved(online, stage);
        }
        return true;
    }

    /** Pushes a player's stages to their own client. Called on join, with the tree and the roster. */
    public static void sync(ServerPlayer player) {
        TenetNetworking.sendStagesTo(player);
    }
}
