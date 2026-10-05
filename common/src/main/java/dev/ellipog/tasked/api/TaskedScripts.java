package dev.ellipog.tasked.api;

import dev.ellipog.tasked.net.ProgressSyncPayload;
import dev.ellipog.tasked.net.TaskedNetworking;
import dev.ellipog.tasked.progress.ProgressService;
import dev.ellipog.tasked.progress.ProgressStore;
import dev.ellipog.tasked.progress.ProgressionEngine;
import dev.ellipog.tasked.progress.QuestState;
import dev.ellipog.tasked.progress.StageService;
import dev.ellipog.tasked.progress.TeamProgress;
import dev.ellipog.tasked.quest.Quest;
import dev.ellipog.tasked.quest.QuestIndex;
import dev.ellipog.tasked.quest.TaskedQuests;
import dev.ellipog.tasked.quest.reward.CustomReward;
import dev.ellipog.tasked.quest.task.CustomTask;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What a script — or another mod — can do to a questline, in plain Java.
 *
 * <h2>Why this exists rather than the services being bound directly</h2>
 *
 * <p>Because a script is written against a person's idea of the mod, not against its internals. The
 * services underneath this class take a server, an owner UUID and a progress record; a script has a player
 * and a quest id, and wants to ask "do they have this stage" or "finish this quest for them". So the
 * translation lives here, once, and the scripting integration above it is a thin binding of these methods —
 * which is also what keeps the integration's own file small enough to read in one sitting.
 *
 * <h2>Loader-free and client-free, deliberately</h2>
 *
 * <p>Nothing here names a loader or a client class, so it compiles into {@code :common} like everything else
 * and can be called from a server, an integrated server, or a future scripting platform that is not KubeJS.
 * The KubeJS binding — the part that must name KubeJS's types — lives in {@code :neoforge} and calls this.
 *
 * <h2>No permission checks here</h2>
 *
 * <p>The commands gate stage changes behind an operator's permission because a <i>player</i> may run them.
 * This is pack code: the person who wrote the script is the person who owns the questline, and a permission
 * check would be asking them for their own permission. What is checked is that the named things exist —
 * a quest id that resolves, a stage id that parses — and everything else returns a plain answer rather than
 * throwing into a script.
 */
public final class TaskedScripts {

    private TaskedScripts() {
    }

    // ------------------------------------------------------------------
    // Stages
    // ------------------------------------------------------------------

    /** Whether the player has the stage. False for an id that is not one, rather than an error. */
    public static boolean hasStage(ServerPlayer player, String stage) {
        ResourceLocation id = ResourceLocation.tryParse(stage == null ? "" : stage);
        MinecraftServer server = player == null ? null : player.getServer();
        return id != null && server != null && StageService.has(server, player.getUUID(), id);
    }

    /**
     * Grants the stage.
     *
     * @return whether anything changed — false when they already had it, which is what makes this safe to
     *         call from a listener that may run twice
     */
    public static boolean addStage(ServerPlayer player, String stage) {
        ResourceLocation id = ResourceLocation.tryParse(stage == null ? "" : stage);
        MinecraftServer server = player == null ? null : player.getServer();
        return id != null && server != null && StageService.add(server, player.getUUID(), id);
    }

    /** Takes the stage away, and returns whether it was there to take. */
    public static boolean removeStage(ServerPlayer player, String stage) {
        ResourceLocation id = ResourceLocation.tryParse(stage == null ? "" : stage);
        MinecraftServer server = player == null ? null : player.getServer();
        return id != null && server != null && StageService.remove(server, player.getUUID(), id);
    }

    /** Every stage this player has, as strings a script can compare or print. */
    public static List<String> stages(ServerPlayer player) {
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null) {
            return List.of();
        }
        return StageService.list(server, player.getUUID()).stream()
                .map(ResourceLocation::toString)
                .sorted()
                .toList();
    }

    // ------------------------------------------------------------------
    // Quests
    // ------------------------------------------------------------------

    /**
     * The quest's state as this player's progress has it: {@code LOCKED}, {@code UNLOCKED}, {@code STARTED}
     * or {@code COMPLETED}, or {@code UNKNOWN} for an id that resolves to no quest.
     *
     * <p>The team's state, not the player's own view of it — a gate is a per-player overlay applied when the
     * client is told, and a script asking about progress is asking about the progress that is stored.
     */
    public static String state(ServerPlayer player, String questId) {
        MinecraftServer server = player == null ? null : player.getServer();
        QuestIndex index = TaskedQuests.index();
        if (server == null || index.isEmpty()) {
            return "UNKNOWN";
        }
        Optional<QuestIndex.QuestEntry> entry = index.quest(questId == null ? "" : questId);
        if (entry.isEmpty()) {
            return "UNKNOWN";
        }
        UUID owner = ProgressService.progressOwner(server, player);
        TeamProgress progress = ProgressStore.of(server).progressOf(owner);
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, progress,
                server.overworld().getGameTime());
        return resolution.stateOf(entry.get().quest()).name();
    }

    /**
     * Finishes the quest for this player's progress, exactly as {@code /tasked complete} does — the same
     * service call, and now the same guard in front of it, so a script cannot complete something the
     * command would refuse.
     *
     * <p>Refuses, returning false, for an id that resolves to no quest, for a quest that is not playable
     * (its dependencies unmet, which is the command's own first check), and for one the engine will not
     * complete (its gate shut for this player, or a completion already paid out). The team is told after
     * an attempt that reached the service, because a refusal the client does not hear about is a card
     * that goes on showing the wrong thing; a locked quest is refused before anything is attempted, and
     * the card already draws it locked.
     */
    public static boolean complete(ServerPlayer player, String questId) {
        MinecraftServer server = player == null ? null : player.getServer();
        QuestIndex index = TaskedQuests.index();
        if (server == null || index.isEmpty()) {
            return false;
        }
        Optional<QuestIndex.QuestEntry> found = index.quest(questId == null ? "" : questId);
        if (found.isEmpty()) {
            return false;
        }
        QuestIndex.QuestEntry entry = found.get();
        UUID owner = ProgressService.progressOwner(server, player);
        TeamProgress before = ProgressStore.of(server).progressOf(owner);
        // The command's own guard, and the reason the parity claim was false without it: the service
        // refuses a shut stage gate and a settled completion, but a quest whose dependencies are unmet
        // is LOCKED and would sail straight through it.
        QuestState state = ProgressionEngine.resolve(index, before, server.overworld().getGameTime())
                .stateOf(entry.quest());
        if (!state.isPlayable()) {
            return false;
        }
        TeamProgress after = ProgressService.complete(server, owner, player, entry, before);
        boolean completed = after.progressOf(entry.quest()).state() == QuestState.COMPLETED;
        TaskedNetworking.sendProgressToTeam(server, player, ProgressSyncPayload.REASON_CHANGED);
        return completed;
    }

    // ------------------------------------------------------------------
    // Custom types
    // ------------------------------------------------------------------

    /**
     * Registers what a {@code tasked:custom} task measures.
     *
     * <p>The same registry the javadoc on that type points at, so a script's handler and a mod's are the same
     * kind of thing — registered once at load, looked up by the id a quest file names.
     */
    public static void registerTask(String id, CustomTask.CustomTasks.Handler handler) {
        CustomTask.CustomTasks.register(id, handler);
    }

    /** Registers what a {@code tasked:custom} reward does when it is granted. */
    public static void registerReward(String id, CustomReward.CustomRewards.Handler handler) {
        CustomReward.CustomRewards.register(id, handler);
    }

    /** The ids a custom task can name in this build. For diagnostics and for scripts that ask. */
    public static List<String> customTaskIds() {
        return CustomTask.CustomTasks.ids().stream().sorted().toList();
    }

    /** The ids a custom reward can name in this build. */
    public static List<String> customRewardIds() {
        return CustomReward.CustomRewards.ids().stream().sorted().toList();
    }

    /** The quest a string names, or null — for a script deciding whether to bother. */
    public static Quest resolve(ServerPlayer player, String questId) {
        return TaskedQuests.index().quest(questId == null ? "" : questId).map(QuestIndex.QuestEntry::quest)
                .orElse(null);
    }
}
