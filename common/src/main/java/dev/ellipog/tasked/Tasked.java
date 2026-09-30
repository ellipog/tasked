package dev.ellipog.tasked;

import dev.ellipog.armature.api.ArmatureApi;
import dev.ellipog.armature.api.event.ArmatureEvents;
import dev.ellipog.armature.api.teams.TeamEvents;
import dev.ellipog.armature.api.teams.Teams;

import dev.ellipog.tasked.party.PartyStore;
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

    /**
     * Whether {@link #init} has run.
     *
     * <p><b>Not an optimisation.</b> See {@link #init}'s javadoc — without this, a Fabric client
     * registers the quest book twice and is then disconnected from every world it tries to join.
     */
    private static boolean initialised;

    private Tasked() {
    }

    /**
     * Constructs everything Tasked owns. Runs at most once per process, on any loader.
     *
     * <h2>Called twice on a client, which is the whole reason this is guarded</h2>
     *
     * <p>Every loader gives Tasked <b>two</b> entry points, and a client runs both:
     * {@code TaskedFabric.onInitialize} and {@code TaskedFabricClient.onInitializeClient} on Fabric,
     * {@code TaskedNeoForge} and {@code TaskedNeoForgeClient} on NeoForge. Dedicated servers run only
     * the first of each pair, which is why the server log has always shown one line and this was
     * never seen from there. Both client initialisers call {@code init()} on purpose — each is
     * written to work even if the other ran second, so that payload registration cannot depend on a
     * load order the loader does not promise.
     *
     * <p>So the guard is what makes that "defensively, whatever order" claim true rather than
     * hopeful. Two of the three things below were already independently idempotent —
     * {@code TaskedNetworking.declare} has its own flag for exactly this hazard, and
     * {@code ArmatureNetwork.install} keeps the first backend — and the third did not.
     *
     * <h2>What happened without it, since it is a genuinely misleading failure</h2>
     *
     * <p>{@code QuestBook.register()} wrote the same id twice, and <b>vanilla does not refuse
     * that</b>. {@code MappedRegistry.register} checks and then does not act on the result:
     *
     * <pre>{@code
     * if (this.byLocation.containsKey(key.location())) {
     *     Util.pauseInIde(new IllegalStateException("Adding duplicate key '" + key + "'"));
     * }   // <- no `throw`. The return value is discarded.
     * }</pre>
     *
     * <p>and {@code Util.pauseInIde} <i>returns</i> the exception rather than throwing it —
     * {@code if (IS_RUNNING_IN_IDE) { LOGGER.error(...); doPause(...); } return t;}. In a released game
     * that branch is false, so the entire check is a no-op and the registry ends up holding two items
     * under one name with two different raw ids.
     *
     * <p>The symptom then appears somewhere else entirely, twenty minutes later and at the one moment
     * a player is doing something: joining a world. {@code fabric-registry-sync} remaps the client's
     * registries to the server's and refuses —
     * <i>"Map contained two equal IDs 1334 (tasked:quest_book/1335 -> tasked:quest_book/1334)"</i> —
     * which disconnects the client before it finishes connecting. Nothing in that message names
     * Tasked's init, an entry point, or the item; it names a map inside Minecraft's client.
     *
     * <p><b>The tell was in the log the whole time.</b> The line below is inside this guard, so
     * {@code "Tasked loaded on Fabric (production)"} appearing <i>twice</i> in one startup is exactly
     * the thing that pointed here — which is worth keeping in mind the next time two lines that
     * should be one appear in a log. Armature's {@code FabricRegistrar} now refuses a duplicate id
     * outright, so this class of bug fails at construction with the id named rather than at connect.
     */
    public static void init() {
        if (initialised) {
            return;
        }
        initialised = true;

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

        // Every membership change moves progress for somebody, so each one pushes rather than only
        // logging. This was two log lines with a comment saying "this is the hook it will attach to" —
        // which is the shape of a thing started and not finished: `REASON_TEAM_CHANGED` existed,
        // `sendProgress` implemented the full-sync-on-a-team-change branch, and nothing ever sent one.
        //
        // The cases are not the same set of people, which is why they read differently:
        //
        //   - JOINED: the arriving member is in the team the event carries, so walking it covers them
        //     and everybody else in it. Their own solo progress is not merged in — ProgressStore
        //     documents that as deliberate — so what they need is the team's.
        //   - LEFT: the event's team is the one they are no longer in, so walking it would miss exactly
        //     the player whose progress actually moved.
        //   - DISBANDED arrives as one MEMBER_LEFT per member, each carrying the *whole* team — so
        //     walking the team there would send N messages to each of N members for one disband. Each
        //     member's own event names them, so the named player is the whole audience.
        //
        //   - CREATED: **the creator's progress owner changes**, which is the case that looks like it
        //     can be skipped and cannot. Forming a party moves their progress from their own solo id
        //     to the party's, so their client is holding a solo questline being drawn against a
        //     party's empty progress. This listener was first written *declining* to subscribe to it,
        //     on the reasoning that "a new team is empty and nobody's progress belongs to it yet" --
        //     which is true of everybody except the one player who just created it.
        TeamEvents.TEAM_CREATED.register((eventServer, team) -> {
            Constants.LOG.info("Tasked: team '{}' created; {} progress now comes from it",
                    team.name(), team.owner());
            TaskedNetworking.sendTeamChange(eventServer, team, null);
            // And the roster, beside the progress rather than instead of it. The two messages answer
            // different questions -- "what does my questline look like now" and "who is in my party"
            // -- and a client that heard only the first would draw the right questline under a
            // heading that names the wrong members.
            TaskedNetworking.sendPartyToTeam(eventServer, team.id());
        });

        TeamEvents.MEMBER_JOINED.register((eventServer, team, player) -> {
            Constants.LOG.info("Tasked: {} joined team '{}'; progress now comes from team {}",
                    player, team.name(), team.id());
            TaskedNetworking.sendTeamChange(eventServer, team, null);
            TaskedNetworking.sendPartyToTeam(eventServer, team.id());
        });
        TeamEvents.MEMBER_LEFT.register((eventServer, team, player, reason) -> {
            Constants.LOG.info("Tasked: {} left team '{}' ({})", player, team.name(), reason);

            if (reason == TeamEvents.Reason.DISBANDED) {
                TaskedNetworking.sendProgressToPlayer(eventServer, player,
                        ProgressSyncPayload.REASON_TEAM_CHANGED);
                return;
            }
            TaskedNetworking.sendTeamChange(eventServer, team, player);
            // The team as it now is, so the people still in it watch the leaving row go. The leaver is
            // not in it -- which is exactly why they are named separately: they are in no party at all
            // now, so their panel goes to the empty state rather than merely losing a row.
            TaskedNetworking.sendPartyToTeam(eventServer, team.id());
            TaskedNetworking.sendNoPartyTo(eventServer, player);
        });

        // The party's chosen progress mode goes with the party.
        //
        // Here rather than nowhere, and here *only* for a source that fires events -- which is why
        // the command's own disband clears it too. A source that fires nothing never tells anybody a
        // party is gone, so on such a server this listener never runs and the entry is cleared by the
        // command or not at all. A party disbanded through a foreign mod's own screen still leaves a
        // line behind, and that is stated rather than implied: the id is a fresh UUID every time, so
        // the entry is unreadable rather than wrong. See PartyStore.clear.
        TeamEvents.TEAM_DISBANDED.register((eventServer, team) ->
                PartyStore.of(eventServer).clear(team.id()));
    }
}
