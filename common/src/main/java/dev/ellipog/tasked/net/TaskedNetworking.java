package dev.ellipog.tasked.net;

import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.armature.api.teams.Team;
import dev.ellipog.tasked.Constants;
import dev.ellipog.tasked.client.ClientQuestCache;
import dev.ellipog.tasked.client.ClientTicker;
import dev.ellipog.tasked.progress.ProgressService;
import dev.ellipog.tasked.quest.QuestIndex;
import dev.ellipog.tasked.quest.TaskedQuests;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collection;
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

    /**
     * The half-received messages, one reassembler per payload type.
     *
     * <p>Two rather than one because a tree and a progress message are separate transfers with
     * separate ids — an id from one is meaningless in the other, and sharing a reassembler would mean
     * a tree chunk completing a progress message.
     *
     * <p>Static because there is one connection per client. A second connection would need a second
     * reassembler, and there is no second connection: the client's whole cache is static for the same
     * reason.
     */
    private static final SyncWire.Reassembler TREE = new SyncWire.Reassembler();
    private static final SyncWire.Reassembler PROGRESS = new SyncWire.Reassembler();

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
                payload -> acceptTree(payload),
                null));

        // --- progress, server to client ---

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                ProgressSyncPayload.TYPE,
                ProgressSyncPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                // The client's own tick count is read here rather than sent. A cooldown is a
                // countdown, and counting it down from a known point costs one subtraction per frame
                // instead of a packet per second.
                payload -> acceptProgress(payload),
                null));
        // --- submitting a task, client to server ---

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                SubmitTaskPayload.TYPE,
                SubmitTaskPayload.CODEC,
                ArmatureNetwork.Direction.TO_SERVER,
                null,
                TaskedNetworking::handleSubmit));

        // --- collecting a finished quest's rewards, client to server ---

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                ClaimRewardPayload.TYPE,
                ClaimRewardPayload.CODEC,
                ArmatureNetwork.Direction.TO_SERVER,
                null,
                TaskedNetworking::handleClaim));

        declared = ArmatureNetwork.registeredCount();
        Constants.LOG.info("Tasked: declared {} payload(s)", declared);
    }

    // ------------------------------------------------------------------
    // The client's two handlers
    // ------------------------------------------------------------------

    /**
     * One chunk of the tree: reassemble, decompress, hand to the cache.
     *
     * <h2>Silence is the right answer to most of these</h2>
     *
     * <p>A chunk that did not complete a message returns null and nothing else happens — that is the
     * normal case for all but the last chunk, and logging it would be a line per chunk on every join.
     * A chunk that cannot be <i>placed</i> is different: {@link SyncWire.MalformedSync} means the
     * sender and this client disagree about the format, which is worth a line and is still not worth
     * a disconnect. A client that refused to connect because a pack it cannot parse appeared would be
     * a worse failure than one that shows nothing and says so.
     */
    private static void acceptTree(QuestSyncPayload payload) {
        byte[] json = completed(TREE, payload.chunk(), payload.data(), "tree");
        if (json == null) {
            return;
        }
        ClientQuestCache.acceptTree(payload.questCount(), payload.chapterCount(),
                payload.hasTheme() ? payload.packTheme() : null, json);
    }

    /** One chunk of progress: reassemble, decompress, and tell the cache whether it is a delta. */
    private static void acceptProgress(ProgressSyncPayload payload) {
        byte[] json = completed(PROGRESS, payload.chunk(), payload.data(), "progress");
        if (json == null) {
            return;
        }
        ClientQuestCache.acceptProgress(payload.teamId(), payload.gameTime(), json,
                ClientTicker.ticks(), payload.chunk().full());
    }

    /**
     * A chunk's bytes once the message is whole, or null while it is not.
     *
     * <p>The one place the two handlers' error handling lives, so a malformed tree and a malformed
     * progress message are treated identically. They want the same treatment — log, ignore, stay
     * connected — and writing it twice is two places for one of them to start throwing instead.
     *
     * @param what what to call the message in a log line, so it names which one failed
     */
    private static byte[] completed(SyncWire.Reassembler reassembler, SyncChunk chunk, byte[] data,
                                    String what) {
        byte[] packed;
        try {
            packed = reassembler.accept(chunk, data);
        }
        catch (SyncWire.MalformedSync e) {
            Constants.LOG.warn("tasked: ignored an unplaceable {} chunk from the server: {}",
                    what, e.getMessage());
            return null;
        }
        if (packed == null) {
            return null;
        }

        try {
            return SyncWire.unpack(packed);
        }
        catch (SyncWire.MalformedSync e) {
            Constants.LOG.warn("tasked: ignored an unreadable {} message from the server: {}",
                    what, e.getMessage());
            return null;
        }
    }

    /**
     * Forgets every half-received message. Called on disconnect, beside the cache's own clear.
     *
     * <p>Without it a player who disconnects mid-tree leaves chunks in memory, and the transfer id of
     * the next connection's first message is a fresh one — so the leftovers are never completed and
     * never freed. The cap in {@link SyncWire.Reassembler} bounds that, and this is what stops it
     * being reached at all.
     */
    public static void forgetTransfers() {
        TREE.forgetAll();
        PROGRESS.forgetAll();
    }

    /** How many transfers are half-received. Diagnostics for a test. */
    public static int pendingTransfers() {
        return TREE.pendingTransfers() + PROGRESS.pendingTransfers();
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

    /**
     * A player pressed Claim.
     *
     * <h2>The server decides whether anything is owed</h2>
     *
     * <p>All this handler does is ask. {@link ProgressService#claim} checks that the quest is finished,
     * that it has rewards, and that they have not already been collected — so a client that sends this
     * for a quest it has not finished, or sends it twice, gets a refusal and no items.
     *
     * <p>The sync is sent whether or not the claim succeeded, and that is deliberate: a client showing
     * a Claim button the server disagrees with needs to be corrected, and the correction is the
     * progress it already knows how to read. Sending nothing on failure would leave the wrong button
     * on screen until something else happened to push progress.
     */
    private static void handleClaim(ClaimRewardPayload payload, ServerPlayer sender) {
        MinecraftServer server = sender.getServer();
        if (server == null || TaskedQuests.index().isEmpty()) {
            return;
        }

        // The index resolves aliases, so a client holding a stale id after a rename still works.
        var entry = TaskedQuests.index().quest(payload.questId());
        if (entry.isEmpty()) {
            Constants.LOG.warn("tasked: {} asked to claim the rewards of unknown quest '{}'",
                    sender.getScoreboardName(), payload.questId());
            return;
        }

        ProgressService.claim(server, sender, entry.get());
        sendToTeam(sender, ProgressSyncPayload.REASON_CHANGED);
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
     * Pushes progress to every online member of the teams named in {@code owners}.
     *
     * <h2>Why this is the method that was missing</h2>
     *
     * <p>The automatic half of the engine had no way to reach a client. The only code anywhere that
     * pushed progress to a player was the handler for <i>pressing Submit</i>, so submitting a task
     * updated the screen and <b>nothing else did</b>. Gathering eight oak logs completed the quest on the server,
     * granted its reward and printed the completion message, while the book went on showing
     * {@code 0 / 8} for the rest of the session.
     *
     * <p>That is what a player reports as "it doesn't register the logs in my inventory", and it is the
     * most misleading possible form for this bug to take: the counting was correct the whole time. The
     * natural experiments all fail to help — throwing the items on the ground and picking them up
     * again, reconnecting, gathering more — because none of them is a code path that pushes progress.
     * The one gesture that would have worked was pressing Submit on a task that has no button.
     *
     * <p>Keyed by <b>owner</b> rather than by player, because progress belongs to a team: one changed
     * owner can mean two or more players who each need to hear about it.
     */
    public static void sendProgressToOwners(MinecraftServer server, Collection<UUID> owners, int reason) {
        if (owners.isEmpty()) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (owners.contains(ProgressService.progressOwner(server, player))) {
                QuestSync.sendProgress(server, player, reason);
            }
        }
    }

    /**
     * Pushes progress to one player, by id, if they are still connected.
     *
     * <h2>Why this exists rather than a second team lookup at the call site</h2>
     *
     * <p>Two callers need it and neither has a {@link ServerPlayer} to hand: a team event carries a
     * player's <b>UUID</b> and nothing else, and the player it names is routinely offline — the whole
     * point of the event is that the membership changed, which is not the same as the player being
     * present. So the offline case is the common one rather than an edge case, and it is handled here
     * once instead of at every caller.
     *
     * <p>Doing nothing for an absent player is correct rather than merely safe: a snapshot describes
     * what <i>this connection</i> was last sent, and a player who is not connected has been sent
     * nothing. {@code sendProgress} would push a row into that map for somebody who will never read
     * it, and {@code sendEverythingTo} drops the row on their next join anyway. So this is not just
     * avoiding a null — it is declining to record knowledge nobody has.
     *
     * <p>An absent player is also not a reason for the <i>sender</i> to hear about it: the events that
     * call this fire for changes a listener should react to, not for changes it should report.
     */
    public static void sendProgressToPlayer(MinecraftServer server, UUID playerId, int reason) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            return;
        }
        QuestSync.sendProgress(server, player, reason);
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
        sendProgressToTeam(server, sender, reason);
    }

    /**
     * The same, for a caller that already holds the server.
     *
     * <h2>Why the server is a parameter here</h2>
     *
     * <p>Because taking it from the player is a second question with a second answer. A
     * {@link ServerPlayer} carries its server in production and does not necessarily carry one in a
     * test — the playthrough harness builds players that answer {@code null} — so a command that
     * derived the server from its own source would silently push nothing there, and the push would
     * look tested when it was not. A command source always knows its server, so it passes it.
     *
     * <p>That is not a test convenience dressed up as design: it is the honest direction of the
     * dependency. The sender is what decides <i>whose</i> progress moved; the server is what the
     * message is sent through, and the caller that has one should not have to ask a player for it.
     */
    public static void sendProgressToTeam(MinecraftServer server, ServerPlayer sender, int reason) {
        QuestSync.sendProgressToTeam(server, membersOf(server, sender), reason);
    }

    /**
     * Pushes progress to everyone who has to hear that a team changed.
     *
     * <h2>Why the team's members <i>and</i> one named player</h2>
     *
     * <p>A membership change moves progress for two sets of people, and they are not nested. The team
     * keeps what it recorded, so every member still in it has a view that may have changed. And the
     * player who left is keyed by their own id again — Armature's {@code MEMBER_LEFT} fires with the
     * team as it now is, which does not include them, so a caller that only walked the team would
     * leave exactly the person whose progress actually moved as the one who was never told.
     *
     * <p>That is why this takes the pair rather than a single team: the recipient set genuinely is
     * "the team, plus this player", and a caller that had to work that out itself would be the second
     * place that knows it.
     *
     * <h2>Why the reason travels</h2>
     *
     * <p>{@code REASON_TEAM_CHANGED} rather than {@code REASON_CHANGED}, and it is not decoration. It
     * is what makes {@code QuestSync} send a <b>full</b> sync rather than a delta — a client that has
     * changed teams is holding the previous team's progress, and a delta would merge one party's
     * questline onto another's. See {@code sendProgress} for that branch.
     *
     * @param team     the team as the event reports it — after a join, and before a disband
     * @param alsoThis a player who is no longer in the team and still needs to hear, or null
     */
    public static void sendTeamChange(MinecraftServer server, Team team, UUID alsoThis) {
        for (UUID member : team.memberIds()) {
            sendProgressToPlayer(server, member, ProgressSyncPayload.REASON_TEAM_CHANGED);
        }
        if (alsoThis != null) {
            sendProgressToPlayer(server, alsoThis, ProgressSyncPayload.REASON_TEAM_CHANGED);
        }
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
