package dev.ellipog.tasked;

import dev.ellipog.armature.api.ArmatureApi;
import dev.ellipog.armature.api.event.ArmatureEvents;
import dev.ellipog.armature.api.teams.TeamEvents;

import dev.ellipog.tasked.progress.ProgressService;
import dev.ellipog.tasked.quest.TaskedQuests;
import dev.ellipog.tasked.net.TaskedNetworking;

import net.minecraft.resources.ResourceLocation;

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
        ArmatureEvents.SERVER_STARTED.register(server -> TaskedQuests.loadOnServerStart());

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
        ArmatureEvents.PLAYER_TICK.register(player -> {
            var server = player.getServer();
            if (server != null) {
                ProgressService.tick(server);
            }
        });

        // Quiet on purpose: a mob farm would otherwise fill the log. The hook itself is the point --
        // it is what the kill-task type will use.
        ArmatureEvents.ENTITY_DEATH.register((entity, source) -> {
        });

        // Teams, so that shared progress is visible in the log while it is being built. Tasked's
        // party behaviour is Stage 3's T4; this is the hook it will attach to.
        TeamEvents.MEMBER_JOINED.register((server, team, player) ->
                Constants.LOG.info("Tasked: {} joined team '{}'; progress now comes from team {}",
                        player, team.name(), team.id()));
        TeamEvents.MEMBER_LEFT.register((server, team, player, reason) ->
                Constants.LOG.info("Tasked: {} left team '{}' ({})", player, team.name(), reason));

        ArmatureEvents.COMMANDS_REGISTER.register((dispatcher, context, selection) -> {
            TaskedCommand.register(dispatcher);
            Constants.LOG.info("Tasked: /tasked registered");
        });
    }
}
