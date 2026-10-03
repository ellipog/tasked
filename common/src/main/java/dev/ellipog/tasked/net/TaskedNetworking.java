package dev.ellipog.tasked.net;

import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.armature.api.teams.Team;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ellipog.tasked.Constants;
import dev.ellipog.tasked.QuestAuthority;
import dev.ellipog.tasked.client.ClientChapterReplica;
import dev.ellipog.tasked.client.ClientEditReplies;
import dev.ellipog.tasked.client.ClientPartyCache;
import dev.ellipog.tasked.client.ClientQuestCache;
import dev.ellipog.tasked.editor.EditorOp;
import dev.ellipog.tasked.editor.EditorOps;
import dev.ellipog.tasked.client.ClientTicker;
import dev.ellipog.tasked.progress.ProgressService;
import dev.ellipog.tasked.quest.QuestIndex;
import dev.ellipog.tasked.quest.TaskedQuests;
import dev.ellipog.tasked.quest.TreeRefresh;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /**
     * Every roster message sent, by the player it went to.
     *
     * <h2>Why a tally lives in production code</h2>
     *
     * <p>{@code QuestSync} keeps the same kind of count for the same reason, and its note applies here
     * word for word: the playthrough drives a real server and has no client, so the client's cache --
     * the thing a panel actually draws from -- cannot be read from a test. What <i>is</i> observable is
     * the message leaving this class, and that is the half a wrong listener gets wrong.
     *
     * <p>It is worth counting because a fault of exactly that shape was found here: the disband path
     * sent every member their progress and no roster, which was invisible while the panel closed on
     * every press and is a panel left reading a party that no longer exists now that it stays open.
     * This is what lets a test say "both members were told" rather than "the code that should have told
     * them was written".
     *
     * <p>A plain {@code HashMap}, written from the server thread and read through
     * {@code callOnServerThread} -- {@code QuestSync}'s arrangement, and its reasoning.
     */
    private static final Map<UUID, Integer> ROSTERS_SENT = new HashMap<>();

    private TaskedNetworking() {
    }

    /** How many roster messages one player has been sent. Diagnostics, as {@code QuestSync}'s are. */
    public static int rostersSentTo(UUID player) {
        return ROSTERS_SENT.getOrDefault(player, 0);
    }

    /** Forgets the roster counts, so a caller can measure a window rather than a session. */
    public static void forgetRosters() {
        ROSTERS_SENT.clear();
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

        // --- and everything outstanding, for the rewards panel's one press ---

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                ClaimAllPayload.TYPE,
                ClaimAllPayload.CODEC,
                ArmatureNetwork.Direction.TO_SERVER,
                null,
                (payload, sender) -> handleClaimAll(sender)));

        // --- a choice reward's entries, and the player's answer ---

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                ChoiceRewardPayload.TYPE,
                ChoiceRewardPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                TaskedNetworking::handleChoiceOffer,
                null));

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                ClaimChoicePayload.TYPE,
                ClaimChoicePayload.CODEC,
                ArmatureNetwork.Direction.TO_SERVER,
                null,
                TaskedNetworking::handleClaimChoice));

        // --- the world's dimensions, server to client ---

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                DimensionSyncPayload.TYPE,
                DimensionSyncPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                TaskedNetworking::handleDimensions,
                null));

        // --- one player's stages, to that player ---

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                StageSyncPayload.TYPE,
                StageSyncPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                TaskedNetworking::handleStages,
                null));

        // --- one edit, client to server, and the server's answer ---
        //
        // The op path. A client asks; the server checks permission, applies the operation to its own model
        // of that chapter and writes it, and answers with what happened -- and then the tree is re-sent by
        // the same broadcast `/tasked reload` uses, so the canvas has one source of truth rather than two.

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                EditorOpPayload.TYPE,
                EditorOpPayload.CODEC,
                ArmatureNetwork.Direction.TO_SERVER,
                null,
                TaskedNetworking::handleEditorOp));

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                EditorReplyPayload.TYPE,
                EditorReplyPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                TaskedNetworking::handleEditorReply,
                null));

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                ReplicaRequestPayload.TYPE,
                ReplicaRequestPayload.CODEC,
                ArmatureNetwork.Direction.TO_SERVER,
                null,
                TaskedNetworking::handleReplicaRequest));

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                ChapterReplicaPayload.TYPE,
                ChapterReplicaPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                TaskedNetworking::handleChapterReplica,
                null));

        // --- a party's roster, server to client ---
        //
        // Declared on both sides, and useless on a server, which is what every TO_CLIENT payload is: a
        // dedicated server never receives its own roster message. It costs one line in a table that both
        // loaders register from, and declaring it only on the client would mean the two loaders had
        // different payload lists -- which is the shape of bug that presents as "the panel works on
        // Fabric" and nothing says why.
        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                PartySyncPayload.TYPE,
                PartySyncPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                TaskedNetworking::handlePartySync,
                null));

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

    /**
     * The player pressing Claim all in the rewards panel: the whole book's outstanding rewards in one
     * press.
     *
     * <p>{@link ProgressService#claimAll} finds the quests itself and asks the same question the single
     * claim does, so the two controls cannot disagree about what may be taken. The payload carries
     * nothing -- that is its point -- so it is named and dropped at the registration rather than handed
     * to a method with a parameter it would never read.
     */
    private static void handleClaimAll(ServerPlayer sender) {
        MinecraftServer server = sender.getServer();
        if (server == null || TaskedQuests.index().isEmpty()) {
            return;
        }
        ProgressService.claimAll(server, sender);
        // Sent whether or not anything paid, the same correction the single claim's handler documents:
        // a panel showing rewards the server has already given out is put right by the progress the
        // client already knows how to read.
        sendToTeam(sender, ProgressSyncPayload.REASON_CHANGED);
    }

    /**
     * The player answered a choice reward.
     *
     * <p>The payload names positions, not rewards, and {@link ProgressService#claimChoice} resolves
     * all three of them against the server's own files — so the worst a modified client can do is
     * pick entry 2 instead of entry 1 of a table it was legitimately offered.
     */
    private static void handleClaimChoice(ClaimChoicePayload payload, ServerPlayer sender) {
        MinecraftServer server = sender.getServer();
        if (server == null || TaskedQuests.index().isEmpty()) {
            return;
        }
        var entry = TaskedQuests.index().quest(payload.questId());
        if (entry.isEmpty()) {
            Constants.LOG.warn("tasked: {} answered a choice on unknown quest '{}'",
                    sender.getScoreboardName(), payload.questId());
            return;
        }
        if (ProgressService.claimChoice(server, sender, entry.get(), payload.rewardIndex(),
                payload.entryIndex())) {
            sendToTeam(sender, ProgressSyncPayload.REASON_CHANGED);
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
        // And their party's roster, which is the third thing a client has no way to know and needs
        // before it can draw a panel. Sent through the player object rather than looked up by id --
        // see `sendOwnRosterTo` for the whole of that fault.
        sendOwnRosterTo(player);
        // And the dimensions this server has. The one list the editor searches that the client cannot
        // build for itself: a dimension is level data rather than a registry entry, so a modded or
        // datapack one is invisible until the server names it. See DimensionSyncPayload.
        ArmatureNetwork.sendToPlayer(player, new DimensionSyncPayload(dimensionIds(server)));
        // And their stages, which are theirs alone rather than the team's -- see ProgressStore.
        sendStagesTo(player);
    }

    /** Every dimension this server has, by id: vanilla, modded and datapack alike. */
    private static List<String> dimensionIds(MinecraftServer server) {
        return server.levelKeys().stream()
                .map(key -> key.location().toString())
                .sorted()
                .toList();
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
     * Reloads the loaded tree and broadcasts it: the coalesced half of an edit's server work.
     *
     * <p>Called by the tick flush rather than per operation — see {@link TreeRefresh} — so a burst of
     * edits pays for one reload and one broadcast. The op itself is still applied synchronously by
     * {@link #handleEditorOp}, and its reply is still immediate.
     */
    public static void refreshTree(MinecraftServer server) {
        TaskedQuests.reload();
        sendTreeToAll(server);
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

    /**
     * A party's roster, from the server.
     *
     * <h2>Unpacked here rather than trusted as fields</h2>
     *
     * <p>Because the roster arrives as one string — see {@link PartySyncPayload} — and there is exactly
     * one place that knows how to read it. {@code PartySnapshot.unpack} never throws and skips what it
     * cannot read, so a malformed message draws "no party" rather than disconnecting the client: a
     * payload that was only ever going to fill a side panel is not worth a lost connection.
     */
    /**
     * A choice offer arrived: hold it for the picker.
     *
     * <p>Held rather than acted on, because the picker is a screen and this runs wherever the network
     * thread reached; the screen reads {@code ClientChoiceOffers} when it opens.
     */
    private static void handleChoiceOffer(ChoiceRewardPayload payload) {
        dev.ellipog.tasked.client.ClientChoiceOffers.accept(payload.questId(), payload.rewardIndex(),
                payload.entries());
    }

    /**
     * The server's dimensions arrived: hold them for the editor's search.
     *
     * <p>Held rather than acted on, for the same reason the choice offer is: this runs wherever the network
     * thread reached, and the screen reads the list when it draws a dimension field.
     */
    private static void handleDimensions(DimensionSyncPayload payload) {
        dev.ellipog.tasked.client.ClientDimensions.accept(payload.dimensions());
    }

    /**
     * A player's stages arrived: hold them for a script or a screen to read.
     *
     * <p>Held rather than acted on, like every other client payload here: this runs wherever the network
     * thread reached, and the reader asks when it needs to know.
     */
    private static void handleStages(StageSyncPayload payload) {
        dev.ellipog.tasked.client.ClientStages.accept(payload.stages());
    }

    /** Pushes one player's stages to their own client. Called on join, and after every change. */
    public static void sendStagesTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        List<String> ids = dev.ellipog.tasked.progress.StageService.list(server, player.getUUID()).stream()
                .map(net.minecraft.resources.ResourceLocation::toString)
                .sorted()
                .toList();
        ArmatureNetwork.sendToPlayer(player, new StageSyncPayload(ids));
    }

    private static void handlePartySync(PartySyncPayload payload) {
        ClientPartyCache.accept(payload.packed());
        // Info rather than debug, paired with the sender's line: "the server sent it" and "the client
        // took it" are two claims, and a panel that shows the wrong party is almost always one of them
        // being false while the other looks fine.
        Constants.LOG.info("tasked: roster received ({} member(s))",
                ClientPartyCache.memberCount());
    }

    /**
     * A chapter's files, asked for by an editor's client.
     *
     * <p>The same permission the ops need, because a chapter's trees are the author's working material and not
     * a player's business — and because a client that may not edit has no panel that would show them. A refusal
     * is an {@link EditorReplyPayload} rather than silence: the author gets a sentence in the same place an op's
     * refusal appears.
     */
    private static void handleReplicaRequest(ReplicaRequestPayload payload, ServerPlayer sender) {
        if (!QuestAuthority.mayEdit(sender)) {
            reply(sender, payload.chapter(), false, "",
                    "You may not read the quest files (permission level " + QuestAuthority.EDIT_LEVEL + ")");
            return;
        }
        JsonObject quests = TaskedQuests.editors().replica(payload.chapter());
        if (quests == null) {
            reply(sender, payload.chapter(), false, "",
                    "no chapter called \"" + payload.chapter() + "\"");
            return;
        }
        com.google.gson.JsonObject chapterTree = TaskedQuests.editors().chapterTree(payload.chapter());
        ArmatureNetwork.sendToPlayer(sender, new ChapterReplicaPayload(payload.chapter(), quests.toString(),
                chapterTree == null ? "{}" : chapterTree.toString()));
    }

    /**
     * A chapter's files, arriving. Kept where the panel will read them.
     *
     * <p>Stamped with the tree revision of the moment it arrived, which is the version {@link ClientChapterReplica}
     * compares against: a copy and a tree that disagree are a copy to ask for again.
     */
    private static void handleChapterReplica(ChapterReplicaPayload payload) {
        ClientChapterReplica.accept(payload.chapter(), payload.quests(), payload.chapterTree(),
                ClientQuestCache.treeRevision());
        Constants.LOG.info("tasked: chapter \"{}\" replica received ({} bytes)",
                payload.chapter(), payload.quests().length());
    }

    /**
     * One edit, applied. The whole of the server's side of the editor.
     *
     * <h2>The four things that happen, in order, and why in that order</h2>
     *
     * <p><b>Permission first</b>, through the same {@link QuestAuthority} the commands read, so a client that
     * asked anyway is refused before anything is opened or read. Then the op is <b>applied and validated</b>
     * by the model — see {@code EditorOps.apply}: a save that refused wrote nothing and undid itself, so a
     * refusal here has changed no file. Then, if it landed, the <b>index is reloaded</b> from the files the op
     * just wrote, which is what the tree broadcast sends to everyone: the author's own client included, which
     * is how the canvas stops drawing its remembered value. Finally the <b>reply</b>, which the client needs
     * for two things it cannot work out for itself: the reason for a refusal, and the id of a quest that was
     * created or duplicated.
     *
     * <p>The reply goes to the sender alone, unlike the tree: a refusal is between the author and their own
     * edit, and broadcasting one author's validation error to the whole server would be telling other players
     * about a file they are not editing.
     */
    private static void handleEditorOp(EditorOpPayload payload, ServerPlayer sender) {
        if (!QuestAuthority.mayEdit(sender)) {
            // Refused, not ignored, and with the level named: an author whose permissions are short needs to
            // know that is the reason rather than watching an edit do nothing.
            reply(sender, payload.chapter(), false, "",
                    "You may not edit the questline (permission level " + QuestAuthority.EDIT_LEVEL + ")");
            return;
        }

        EditorOp op = EditorOps.read(parse(payload.op()));
        if (op == null) {
            reply(sender, payload.chapter(), false, "", "that is not an edit this version knows");
            return;
        }

        EditorOps.Applied applied = TaskedQuests.editors().apply(payload.chapter(), op);
        if (applied.ok()) {
            // The reload and the all-player tree broadcast are coalesced to one per server tick -- see
            // TreeRefresh -- so a burst of edits pays for them once. The reply below stays per op, so
            // the author hears about every operation immediately.
            TreeRefresh.request();
        }
        reply(sender, payload.chapter(), applied.ok(),
                applied.questId() == null ? "" : applied.questId(),
                String.join("\n", applied.messages()));
    }

    /** An op's JSON, or null: a payload from a newer client is a refusal rather than a parse crash. */
    private static JsonObject parse(String json) {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        }
        catch (RuntimeException malformed) {
            return null;
        }
    }

    private static void reply(ServerPlayer sender, String chapter, boolean ok, String questId,
                              String messages) {
        ArmatureNetwork.sendToPlayer(sender, new EditorReplyPayload(chapter, ok, questId, messages));
    }

    /**
     * The server's answer, put where the open screen will find it.
     *
     * <p>Through a cache rather than into a screen, for the reason every other client payload here does: this
     * runs on the client's game thread with whatever is open, which may be nothing — a player who closes the
     * book while an op is in flight still gets the answer, and it is dropped rather than sent to a screen that
     * is gone.
     */
    private static void handleEditorReply(EditorReplyPayload payload) {
        ClientEditReplies.accept(payload);
        Constants.LOG.info("tasked: edit reply for \"{}\" -- {}",
                payload.chapter(), payload.ok() ? "applied" : "refused");
    }

    /**
     * Asks the server to apply one edit.
     *
     * <p>The client's whole side of the editor: it never writes a file, and it does not decide whether the
     * edit is allowed or possible. What it gets back is a {@link EditorReplyPayload} into
     * {@link ClientEditReplies}, and the changed tree as a reload broadcast — the canvas keeps drawing its
     * remembered value until that arrives, which is the one exception the design allows and it expires.
     */
    /**
     * Asks for a chapter's files, when a panel is about to need them.
     *
     * <p>One request per revision, decided by {@link ClientChapterReplica#claim}, which the caller uses as the
     * gate: this method does not ask twice on its own, because a screen asking every frame is a screen the
     * server sees as a flood.
     */
    public static void requestReplica(String chapter) {
        ArmatureNetwork.sendToServer(new ReplicaRequestPayload(chapter));
    }

    public static void sendEditorOp(String chapter, EditorOp op) {
        ArmatureNetwork.sendToServer(new EditorOpPayload(chapter, EditorOps.write(op).toString()));
    }

    /**
     * Pushes a party's roster to everyone who can see it.
     *
     * <h2>Who is told, and why it is not only the members</h2>
     *
     * <p>The members, because their own panel is describing themselves. And <b>nobody else</b> — a
     * roster is not public information, and a server that broadcast every party's membership would be
     * telling players who is grouped with whom without being asked to.
     *
     * <h2>Called from the same places as the progress push</h2>
     *
     * <p>Which is what makes it nearly free. A membership change already sends every member a progress
     * sync — that is what {@code sendTeamChange} is for — so the roster rides along on a message that
     * was going out anyway, and an idle tick sends nothing at all.
     */
    public static void sendPartyToTeam(MinecraftServer server, UUID teamId) {
        PartySnapshot snapshot = PartySnapshot.of(server, teamId);
        if (!snapshot.isPresent()) {
            // The team is gone, or nobody is in it. An empty roster tells nobody, and that is not a
            // gap: whoever the roster *would* name is named by the caller instead -- see sendNoPartyTo
            // and its note on why a disband has to send something rather than nothing.
            return;
        }
        // Two lists travel per *recipient* rather than with the party, and that is what `withPlayers`
        // is for: two members of one party receive different snapshots, because each has their own
        // invitations. The online list is the same for both, and is filled here anyway so there is one
        // call that produces a complete snapshot rather than two that have to agree.
        //
        // This is also what makes the panel usable without typing: an invitation the client cannot see
        // is an invitation it cannot offer an Accept button for.
        for (PartySnapshot.Member member : snapshot.members()) {
            sendRosterTo(server, member.id(), snapshot
                    .withPlayers(server, member.id(), id -> invitesFor(server, id))
                    .withMode(dev.ellipog.tasked.party.PartyStore.of(server).modeOf(teamId).id()));
        }
    }

    /**
     * The invitations a player is holding, as the snapshot's own record.
     *
     * <h2>Why the adapter is here rather than in the snapshot</h2>
     *
     * <p>Because the snapshot is a plain value -- it holds ids, names and an enum, and nothing about
     * teams -- and `TeamInvite` would drag Armature's teams into a message format. So the conversion
     * from a {@code Team} to the two fields the client needs happens at the one place that already
     * deals with both, and the format stays a format.
     */
    private static java.util.List<PartySnapshot.Invite> invitesFor(MinecraftServer server, UUID player) {
        java.util.List<PartySnapshot.Invite> out = new java.util.ArrayList<>();
        for (Team team : dev.ellipog.armature.api.teams.Teams.of(server).invitesFor(player)) {
            out.add(new PartySnapshot.Invite(team.id(), team.name()));
        }
        return out;
    }

    /** One player's roster, if they are still connected. */
    public static void sendRosterTo(MinecraftServer server, UUID playerId, PartySnapshot snapshot) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            // Debug rather than info, and it is the one silent exit left in this path: a member who has
            // disconnected between a change and the push. The *expected* null -- the joining player,
            // who is not in the list yet -- no longer comes through here at all. See `sendOwnRosterTo`.
            Constants.LOG.debug("tasked: no player {} to send a roster to", playerId);
            return;
        }
        sendRoster(player, snapshot);
    }

    /**
     * A roster, to one player, through the player object rather than through a lookup.
     *
     * <h2>Why a message's recipient must not be found by id</h2>
     *
     * <p>Because at {@code PLAYER_JOIN} the lookup has not been populated yet: the event fires while the
     * connection is still being accepted, and {@code getPlayerList().getPlayer(uuid)} answers null for
     * the player who is joining. Every other push in this class can afford that -- a party change
     * happens long after everybody is listed -- and the join push cannot, because its whole audience is
     * the one player who is not in the list yet. Which is exactly what a party did: the tree and the
     * progress arrived on a login, because they are sent through the {@code ServerPlayer} the event
     * hands over, and the roster -- the only message that looked its own recipient up -- was dropped
     * without a word, on every login, with every command still insisting the party existed.
     */
    private static void sendRoster(ServerPlayer player, PartySnapshot snapshot) {
        ROSTERS_SENT.merge(player.getUUID(), 1, Integer::sum);
        // Logged at info rather than debug, and one line per message: a party's messages are a handful
        // per session, and "was the client told" is the question every fault in this area has turned
        // on. Both halves of it are logged -- see `handlePartySync` for the receiving side.
        Constants.LOG.info("Tasked: roster -> {} ({} member(s))",
                player.getScoreboardName(), snapshot.members().size());
        send(player, new PartySyncPayload(snapshot.teamId(), snapshot.pack()));
    }

    /**
     * Every party's roster, to its members: for a change that is not about any one party.
     *
     * <h2>Why a player joining the server moves a panel they are not in</h2>
     *
     * <p>Because a party's panel lists the players who are <b>not</b> in it — an Invite row each — and
     * marks which members are online. Both of those are facts about the server rather than about the
     * party, so somebody logging in or out changes every open panel on it, and no team event fires to
     * say so: TEAM_CREATED, MEMBER_JOINED and MEMBER_LEFT are all about membership.
     *
     * <p>The cost is one message per member per party, on an event that happens when somebody joins or
     * leaves the server. That is worth naming rather than leaving to be discovered: on a large server it
     * is a push per party per arrival, and the alternative is a panel whose Invite list is as stale as
     * the server is busy.
     */
    public static void sendRostersToAllParties(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (Team team : dev.ellipog.armature.api.teams.Teams.of(server).teams()) {
            // Skips itself for a team that is gone or has nobody in it, which is what makes this safe
            // to call for every team the source knows about.
            sendPartyToTeam(server, team.id());
        }
    }

    /**
     * The roster of the player who is logging in, sent to them.
     *
     * <h2>Why this exists beside {@code sendEverythingTo}'s other half</h2>
     *
     * <p>Because the login is the one moment a player is not in the player list, so it is the one push
     * that has to carry its own recipient. See {@link #sendRoster} for the fault that made that
     * concrete: a party that no login could see, while every command and every event knew about it.
     *
     * <p>The caller is the join hook, so the player is by definition not in a team of one: this asks
     * the team source, and a player with no party is <b>told</b> they have none rather than left in
     * silence. That matters across a relog in a single client -- one client can open two worlds, and a
     * cache that is never told otherwise describes the previous world's party.
     */
    public static void sendOwnRosterTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        Optional<Team> mine = dev.ellipog.armature.api.teams.Teams.of(server).realTeamOf(player.getUUID());
        if (mine.isEmpty()) {
            sendRoster(player, PartySnapshot.none());
            return;
        }
        UUID teamId = mine.get().id();
        sendRoster(player, PartySnapshot.of(server, teamId)
                .withArriving(server, player, id -> invitesFor(server, id))
                .withMode(dev.ellipog.tasked.party.PartyStore.of(server).modeOf(teamId).id()));
    }

    /**
     * Tells one player they are in no party.
     *
     * <h2>Why an empty roster has to be sent rather than inferred</h2>
     *
     * <p>Because a client's cache is <b>stale by default</b>: it holds whatever it was last told, and if
     * a party is disbanded while a panel is open, nothing else would ever tell that client. The panel
     * would go on drawing a party that no longer exists — with real player names in it, which is what
     * makes it convincing. So a disband sends this to each former member rather than sending nothing.
     */
    public static void sendNoPartyTo(MinecraftServer server, UUID playerId) {
        sendRosterTo(server, playerId, PartySnapshot.none());
    }

    /**
     * Sends one payload, tolerating a player who has gone.
     *
     * <p>The check is not defensive noise. A sync is produced by several paths that run on a tick — the
     * engine reporting a change, a team membership change, a command — and a player can disconnect
     * between the moment a change is noticed and the moment it is sent. Sending to a disconnected player
     * is a no-op on Fabric and throws on NeoForge, which is the worst combination: it works on one loader
     * and breaks the other, from the same source file.
     */
    private static void send(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection == null || player.hasDisconnected()) {
            return;
        }
        ArmatureNetwork.sendToPlayer(player, payload);
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
