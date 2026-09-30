package dev.ellipog.tasked;

import dev.ellipog.armature.api.ArmatureApi;
import dev.ellipog.armature.api.event.ArmatureEvents;
import dev.ellipog.armature.api.teams.TeamEvents;
import dev.ellipog.armature.api.teams.Teams;

import dev.ellipog.tasked.progress.ProgressService;
import dev.ellipog.tasked.quest.TaskedQuests;
import dev.ellipog.tasked.net.ProgressSyncPayload;
import dev.ellipog.tasked.net.TaskedNetworking;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.Set;
import java.util.UUID;

/**
 * Common entry point, shared by the Fabric and NeoForge builds.
 *
 * <p>Compiled against vanilla and Armature's API only — nothing under this package may name a loader,
 * and nothing may name a client-only class. Both would build fine here and fail at runtime on a
 * dedicated server, which is the single most common mistake in a mod laid out this way.
 */
public final class Tasked {

    /** Every id this mod uses, in one place. */
    public static final String MOD_ID = "tasked";

    /** The quest book screen, opened by id so that common code never names a client class. */
    public static final ResourceLocation QUEST_BOOK_SCREEN = ResourceLocation.fromNamespaceAndPath(MOD_ID, "quest_book");

    /** Whether {@link #listenToTeams} has run. See its comment for why a flag is needed. */
    private static boolean teamListenersInstalled;

    private Tasked() {
    }

    public static void init() {
        Constants.LOG.info("Tasked loaded on {} ({})",
                ArmatureApi.platform().name(), ArmatureApi.platform().environmentName());

        // Before anything else: the payload declarations have to exist before the loader installs
        // its networking, and every load path calls init() first precisely so they do.
        TaskedNetworking.declare();

        QuestBook.register();
        listen();

        Constants.LOG.info("Armature {}", ArmatureApi.platform().modVersion("armature").orElse("absent"));
    }

    /**
     * Wires Tasked's own listeners onto Armature's events.
     *
     * <p>Everything here is on the server side, which is deliberate: Tasked is server-authoritative
     * from the first commit, and a single-player world is a server. Progress that only exists on the
     * client is progress that two players disagree about.
     */
    private static void listen() {
        // Quest files are loaded from STARTED, not STARTING. Verified on both loaders: on Fabric,
        // STARTING fires before the game has logged starting the server at all -- no level exists
        // yet. STARTED is well-defined on both, and consistent code is worth more than an earlier
        // hook. See ArmatureEvents for the full note.
        //
        // listenToTeams is here rather than beside the other registrations below, and that is a
        // change rather than an accident -- see its comment for what it buys and what it costs.
        ArmatureEvents.SERVER_STARTED.register(server -> {
            TaskedQuests.loadOnServerStart();
            listenToTeams(server);
        });

        ArmatureEvents.SERVER_STOPPING.register(server ->
                Constants.LOG.info("Tasked: server stopping; {} loaded, nothing to save yet",
                        TaskedQuests.summary()));

        ArmatureEvents.PLAYER_JOIN.register(player -> {
            Constants.LOG.info("Tasked: {} joined", player.getScoreboardName());
            // The whole point of Stage 4: without this the quest book opens on an empty cache and
            // says so, which is indistinguishable from having no quests loaded at all.
            TaskedNetworking.sendEverythingTo(player);
        });

        ArmatureEvents.PLAYER_LEAVE.register(player ->
                Constants.LOG.info("Tasked: {} left", player.getScoreboardName()));

        // The engine. Runs once per player tick, and dedupes internally -- see
        // ProgressService.tick, which has to, because this hook fires per player and the work it
        // does is per team.
        //
        // The set it returns is the teams whose progress actually moved, and telling those players is
        // the half that was missing. Without it, the only thing that ever pushed progress to a client
        // was the handler for pressing Submit -- so gathering the items completed the quest, granted
        // the reward and printed the completion message while the quest book went on showing 0 of 8
        // until the player reconnected. See TaskedNetworking.sendProgressToOwners.
        //
        // Empty on almost every tick, which is what makes "tell them when it moves" cost nothing
        // rather than costing a packet per player per tick.
        ArmatureEvents.PLAYER_TICK.register(player -> {
            var server = player.getServer();
            if (server == null) {
                return;
            }
            Set<UUID> changed = ProgressService.tick(server);
            if (!changed.isEmpty()) {
                TaskedNetworking.sendProgressToOwners(server, changed, ProgressSyncPayload.REASON_CHANGED);
            }
        });

        // Quiet on purpose: a mob farm would otherwise fill the log. The hook itself is the point --
        // it is what the kill-task type will use.
        ArmatureEvents.ENTITY_DEATH.register((entity, source) -> {
        });

        ArmatureEvents.COMMANDS_REGISTER.register((dispatcher, context, selection) -> {
            TaskedCommand.register(dispatcher);
            Constants.LOG.info("Tasked: /tasked registered");
        });
    }

    /**
     * Subscribes to Armature's team events, once per process, and says which source the teams come
     * from.
     *
     * <h2>Why this is on {@code SERVER_STARTED} rather than beside the other listeners</h2>
     *
     * <p>It was beside them, and that was wrong in a way worth recording. A team event only ever
     * fires from a server, and {@link ArmatureEvents#SERVER_STARTED} is <b>fired by loader code in
     * each loader's subproject</b> — so registering here makes the subscription part of "a server
     * exists" rather than part of "this class was initialised". The two coincide in production and do
     * not in a test, and that difference is the useful part: a test JVM never fires a lifecycle event,
     * so nothing in Tasked's tests can come to depend on a team event having been subscribed, because
     * it never is there. That is a constraint on the tests rather than on Tasked, and it is the reason
     * the guard below is worth having.
     *
     * <p>It also means the resolution happens at a moment when a server definitely exists, which is
     * what the source probe wants — see {@code TeamProviders} for why the resolution is lazy rather
     * than waiting for this.
     *
     * <p>Guarded by a flag because {@code SERVER_STARTED} fires once per server and a process can host
     * more than one — a single-player world, a disconnect, another world. Without the flag the second
     * server would log every membership change twice, which reads as a duplicated event rather than as
     * a duplicated listener.
     *
     * <h2>What the log line on the first tick of a server is for</h2>
     *
     * <p>{@code Teams.of(server).name()} is {@code "stored"} on a server running nothing else, and
     * the id of a parties mod on a server running one. Printed at startup rather than on the first
     * team command, because the useful moment to find out which source a server resolved to is before
     * anybody asks — and because "both parties mods are installed and this one won" is a decision
     * that is otherwise invisible. See {@code TeamProviders} for why that decision is a heuristic.
     */
    private static void listenToTeams(MinecraftServer server) {
        if (teamListenersInstalled) {
            return;
        }
        teamListenersInstalled = true;

        Constants.LOG.info("Tasked: teams come from '{}' on this server", Teams.of(server).name());

        // So that shared progress is visible in the log while it is being built. Tasked's party
        // behaviour is Stage 3's T4; this is the hook it will attach to.
        TeamEvents.MEMBER_JOINED.register((eventServer, team, player) ->
                Constants.LOG.info("Tasked: {} joined team '{}'; progress now comes from team {}",
                        player, team.name(), team.id()));
        TeamEvents.MEMBER_LEFT.register((eventServer, team, player, reason) ->
                Constants.LOG.info("Tasked: {} left team '{}' ({})", player, team.name(), reason));
    }
}
