package dev.ellipog.tasked.net;

import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.tasked.Constants;
import dev.ellipog.tasked.client.ClientQuestCache;
import dev.ellipog.tasked.client.ClientTicker;
import dev.ellipog.tasked.progress.ProgressService;
import dev.ellipog.tasked.quest.QuestIndex;
import dev.ellipog.tasked.quest.TaskedQuests;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tasked's payloads, declared once for both loaders, and the handlers that act on them.
 *
 * <h2>The declarations live here; the wiring does not</h2>
 *
 * <p>Armature holds each declaration until the loader asks for it, at whatever moment that loader
 * accepts payloads — which is not the same moment on both. So this class names no loader, and both
 * subprojects register the same list.
 *
 * <h2>Each handler runs where the payload arrived</h2>
 *
 * <p>On that side's game thread, which means a handler may touch game state directly. A client
 * handler must not assume it can reach a server, and a server handler must not assume the sender is
 * still connected by the time it runs. Both are checked where they matter rather than here.
 */
public final class TaskedNetworking {

    private static int declared;
    private static boolean alreadyDeclared;

    private TaskedNetworking() {
    }

    /** How many payloads were declared. For a log line that says the wiring happened. */
    public static int declaredCount() {
        return declared;
    }

    /**
     * Declares every payload, once.
     *
     * <p>Called from common init, so it runs on both sides and before any loader has wired anything
     * up. Declaring is cheap and side-effect free; the loader does the work later.
     *
     * <h2>Idempotent on purpose</h2>
     *
     * <p>A second call would append a second copy of every declaration, and the loader would then
     * reject the duplicate — "payload already registered" — which reads as a mod conflict rather than
     * as a mod initialising twice. That is a real possibility rather than a theoretical one: a client
     * with an integrated server runs two entry points, and a test JVM runs them in one process. One
     * boolean is a cheap way to make the failure impossible instead of diagnosable.
     */
    public static void declare() {
        if (alreadyDeclared) {
            return;
        }
        alreadyDeclared = true;

        // --- the tree, server to client ---

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                QuestSyncPayload.TYPE,
                QuestSyncPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                payload -> ClientQuestCache.acceptTree(payload.questCount(), payload.chapterCount(), payload.tree()),
                null));

        // --- progress, server to client ---

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                ProgressSyncPayload.TYPE,
                ProgressSyncPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                // The client's own tick count is read here rather than sent. A cooldown is a
                // countdown, and counting it down from a known point costs one subtraction per frame
                // instead of a packet per second.
                payload -> ClientQuestCache.acceptProgress(payload.teamId(), payload.gameTime(), payload.data(),
                        ClientTicker.ticks()),
                null));
        // --- submitting a task, client to server ---

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                SubmitTaskPayload.TYPE,
                SubmitTaskPayload.CODEC,
                ArmatureNetwork.Direction.TO_SERVER,
                null,
                TaskedNetworking::handleSubmit));

        declared = ArmatureNetwork.registeredCount();
        Constants.LOG.info("Tasked: declared {} payload(s)", declared);
    }

    /**
     * A player pressed a task.
     *
     * <h2>Everything is re-checked here</h2>
     *
     * <p>The client sent two numbers and nothing else, and neither is trusted. Whether the quest
     * exists, whether it is unlocked, whether the task can be submitted by hand, and whether the items
     * are in the inventory are all decided by {@link ProgressService#submit} — the same method
     * {@code /tasked submit} calls. So a client that sends this for a quest it has not unlocked gets a
     * refusal, not progress.
     */
    private static void handleSubmit(SubmitTaskPayload payload, ServerPlayer sender) {
        QuestIndex index = TaskedQuests.index();
        if (index.isEmpty()) {
            return;
        }

        // The index resolves aliases, so a client holding a stale id after a rename still works.
        var entry = index.quest(payload.questId());
        if (entry.isEmpty()) {
            Constants.LOG.warn("tasked: {} asked to submit a task of unknown quest '{}'",
                    sender.getScoreboardName(), payload.questId());
            return;
        }

        ServerPlayer server = sender;
        boolean changed = ProgressService.submit(server.getServer(), server, entry.get(), payload.taskIndex());
        if (changed) {
            // Everyone in the team needs the new state, not just the player who pressed it: progress
            // is shared, so a party member watching the same quest would otherwise not see it move.
            sendToTeam(server, ProgressSyncPayload.REASON_CHANGED);
        }
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    /** Pushes the tree and this player's progress. Called on join. */
    public static void sendEverythingTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        QuestSync.sendEverythingTo(player, server);
    }

    /** Pushes the tree to every connected player, then their progress. Called after a reload. */
    public static void sendTreeToAll(MinecraftServer server) {
        QuestIndex index = TaskedQuests.index();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            QuestSync.sendTreeTo(player, index);
            QuestSync.sendProgress(server, player, ProgressSyncPayload.REASON_RELOAD);
        }
    }

    /**
     * Pushes progress to the sender's whole team, including the sender.
     *
     * <p>The team's progress is shared, so a change caused by one member is news to all of them. The
     * sender is included deliberately: the client made no local change when it asked to submit, so
     * this sync is how it learns the outcome — including when the answer was no.
     */
    public static void sendToTeam(ServerPlayer sender, int reason) {
        // int rather than byte, matching the payload. A byte here and an int there meant three
        // silent narrowing conversions the compiler refused -- which is the good outcome, but the
        // reason field is a code, not a value with a width, so int is the honest type for it.
        MinecraftServer server = sender.getServer();
        if (server == null) {
            return;
        }
        QuestSync.sendProgressToTeam(server, membersOf(server, sender), reason);
    }

    /** Everyone online who shares this player's progress. */
    private static List<ServerPlayer> membersOf(MinecraftServer server, ServerPlayer sender) {
        UUID owner = ProgressService.progressOwner(server, sender);
        List<ServerPlayer> members = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ProgressService.progressOwner(server, player).equals(owner)) {
                members.add(player);
            }
        }
        return members;
    }
}
