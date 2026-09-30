package dev.ellipog.tasked;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import dev.ellipog.armature.api.teams.Team;
import dev.ellipog.armature.api.teams.TeamManager;
import dev.ellipog.armature.api.teams.TeamRole;
import dev.ellipog.armature.api.teams.Teams;

import dev.ellipog.tasked.net.ProgressSyncPayload;
import dev.ellipog.tasked.net.TaskedNetworking;
import dev.ellipog.tasked.party.PartyMode;
import dev.ellipog.tasked.party.PartyStore;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /tasked party} — the party commands, on Armature's teams.
 *
 * <h2>This wraps; it does not implement</h2>
 *
 * <p>Every mutation below is one call to {@link TeamManager}: create, invite, accept, leave, kick,
 * disband. There is no membership logic in this file and there must not be — the whole reason teams
 * live in Armature is that membership is the general primitive, and a second implementation of
 * "who is in the party" here is how the party panel and the party commands come to disagree.
 *
 * <p>What this file adds is the three things a command has and an API does not: a permission
 * question, a player-facing message, and <b>telling the clients</b>.
 *
 * <h2>The source may not be ours, and that is the ordinary case rather than an edge case</h2>
 *
 * <p>A server running FTB Teams or Open Parties and Claims already has parties, and
 * {@link Teams#of} resolves to whichever of them it is. So:
 *
 * <ul>
 *   <li><b>A read-only source refuses loudly.</b> {@code TeamManager}'s mutators throw by default so
 *       that a source which cannot write says so instead of silently doing nothing — but a command
 *       that let that exception escape would present a library's exception as a crash. So every
 *       mutating subcommand asks {@link TeamManager#managesMembership()} first and, when the answer
 *       is no, says which source owns the parties on this server and where to go instead. That is
 *       strictly more useful than the exception, because it names the mod.</li>
 *   <li><b>A silent source needs the push, because nothing else will make it.</b> See
 *       {@link #pushMembershipChange}.</li>
 * </ul>
 *
 * <p><b>The mode is not gated on any of that</b>, and the distinction is worth stating because it
 * looks like an oversight. A {@link PartyMode} is not membership — it is a rule about how
 * <i>quest</i> counts combine, it lives in Tasked's own store, and it is readable and writable on a
 * server whose parties are entirely somebody else's. So {@code /tasked party mode} works everywhere,
 * including on the one server where {@code /tasked party create} has to refuse.
 *
 * <h2>Why promises here are not gated on operator permission</h2>
 *
 * <p>Because none of this is an operator's business. Creating a party, inviting somebody and leaving
 * are things a player does for themselves, and their authority over other players is checked by
 * Armature — {@code kick} refuses an actor who does not outrank the target, and {@code disband}
 * refuses anybody but the owner. Putting an op gate on top would mean a player cannot leave a party
 * without asking an admin, which is the sort of rule that gets a mod uninstalled.
 */
public final class TaskedPartyCommand {

    private TaskedPartyCommand() {
    }

    /** The {@code party} subtree, for {@link TaskedCommand} to hang off {@code /tasked}. */
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("party")
                // No argument: what am I, and how do we count?
                .executes(TaskedPartyCommand::info)

                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(TaskedPartyCommand::create)))

                .then(Commands.literal("invite")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(TaskedPartyCommand::invite)))

                .then(Commands.literal("accept")
                        .executes(TaskedPartyCommand::accept))

                .then(Commands.literal("leave")
                        .executes(TaskedPartyCommand::leave))

                .then(Commands.literal("kick")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(TaskedPartyCommand::kick)))

                .then(Commands.literal("disband")
                        .executes(TaskedPartyCommand::disband))

                // `mode` with no argument reads, which is the shape every other toggle in this mod
                // has and the reason it is not two commands.
                .then(Commands.literal("mode")
                        .executes(TaskedPartyCommand::mode)
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .executes(TaskedPartyCommand::setMode)));
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /**
     * {@code /tasked party} — who is in this party, and how it counts.
     *
     * <p>Prints the source as well as the party, and that is the line that answers the question a
     * server operator actually has: whether the parties their players see are Tasked's or another
     * mod's. It is the same answer {@code Tasked}'s own boot log gives, at the moment somebody is
     * looking for it.
     */
    private static int info(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        MinecraftServer server = context.getSource().getServer();
        TeamManager teams = Teams.of(server);
        Team team = teams.teamOf(player.getUUID());

        // The source first, because it is the line that answers the question a server operator
        // actually has — whether the parties their players see are Tasked's or another mod's — and
        // because it is the only place that fact is stated outside a boot log.
        context.getSource().sendSuccess(() -> Component.translatable("tasked.command.party.source",
                teams.name()), false);

        if (!team.persistent()) {
            context.getSource().sendSuccess(() -> Component.translatable("tasked.command.party.solo"), false);
        }
        else {
            String name = team.name().isEmpty() ? "(unnamed)" : team.name();
            context.getSource().sendSuccess(() -> Component.translatable("tasked.command.party.header",
                    name, team.size()), false);

            // Sorted by authority then id, so the owner is first and the listing reads the same way
            // twice. `members()` is an immutable map whose iteration order is deliberately
            // unspecified, so without this the lines would shuffle between calls for no reason a
            // reader could see — and a member scanning for their own name would find it in a
            // different place each time.
            team.members().entrySet().stream()
                    .sorted(java.util.Map.Entry
                            .<UUID, TeamRole>comparingByValue(
                                    java.util.Comparator.comparingInt(TeamRole::authority).reversed())
                            .thenComparing(entry -> entry.getKey().toString()))
                    .forEach(entry -> {
                        String who = nameOf(server, entry.getKey());
                        String role = entry.getValue().name().toLowerCase(java.util.Locale.ROOT);
                        boolean self = entry.getKey().equals(player.getUUID());
                        context.getSource().sendSuccess(() -> Component.literal(
                                "  §7" + role + "§r " + who + (self ? " §8(you)" : "")), false);
                    });
        }

        // And how it counts, on the same screen as who is in it — because the two questions are the
        // same question from a player's point of view: what does being in this party mean.
        UUID owner = ownerOf(server, player);
        PartyMode mode = PartyStore.of(server).modeOf(owner);
        context.getSource().sendSuccess(() -> Component.translatable("tasked.command.party.mode",
                mode.id(), mode.description()), false);

        return 1;
    }

    private static int mode(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        MinecraftServer server = context.getSource().getServer();

        UUID owner = TaskedPartyCommand.ownerOf(server, player);
        PartyMode current = PartyStore.of(server).modeOf(owner);

        context.getSource().sendSuccess(() -> Component.translatable("tasked.command.party.mode",
                current.id(), current.description()), false);
        context.getSource().sendSuccess(() -> Component.translatable("tasked.command.party.mode.options",
                PartyMode.ids()), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    private static int create(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        MinecraftServer server = context.getSource().getServer();
        TeamManager teams = teamsOrRefuse(context);
        if (teams == null) {
            return 0;
        }

        String name = StringArgumentType.getString(context, "name");

        if (teams.realTeamOf(player.getUUID()).isPresent()) {
            context.getSource().sendFailure(Component.translatable("tasked.command.party.already_in"));
            return 0;
        }

        Team[] created = new Team[1];
        try {
            created[0] = teams.create(name, player.getUUID());
        }
        catch (RuntimeException e) {
            // A source's own refusal, with the source's own words. Armature's stored manager throws
            // IllegalStateException for an owner already in a team -- checked above, so reaching here
            // means a foreign source declined for a reason only it can explain.
            context.getSource().sendFailure(Component.literal(e.getMessage() == null
                    ? e.toString() : e.getMessage()));
            return 0;
        }

        // The creator is the only member, and their progress owner has just moved from their own solo
        // id to the party's -- so their client is now showing their solo questline against a party's
        // empty progress. That is a change worth telling them about, and it is the case the
        // TEAM_CREATED listener exists for.
        pushMembershipChange(context.getSource(), teams, Set.of(), created[0].memberIds(), player.getUUID());

        context.getSource().sendSuccess(() -> Component.translatable("tasked.command.party.created",
                created[0].name()), false);
        return 1;
    }

    private static int invite(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(context, "player");
        TeamManager teams = teamsOrRefuse(context);
        if (teams == null) {
            return 0;
        }

        Optional<Team> mine = teams.realTeamOf(player.getUUID());
        if (mine.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("tasked.command.party.not_in"));
            return 0;
        }
        Team team = mine.get();

        // Authority is checked before the call rather than after, so the message can name the reason.
        // `invite` itself refuses an already-invited player, which is a different refusal and comes
        // back as a false below.
        if (team.roleOrMember(player.getUUID()) == TeamRole.MEMBER) {
            context.getSource().sendFailure(Component.translatable("tasked.command.party.needs_rank",
                    "officer"));
            return 0;
        }

        if (!teams.invite(team.id(), target.getUUID())) {
            context.getSource().sendFailure(Component.translatable("tasked.command.party.invite_failed",
                    target.getScoreboardName()));
            return 0;
        }

        // Nothing is pushed, and this is deliberate rather than an omission: an invitation is not
        // membership, so nobody's progress owner has moved. Pushing here would be a full sync per
        // invite, which is traffic for a change that has not happened.
        context.getSource().sendSuccess(() -> Component.translatable("tasked.command.party.invited",
                target.getScoreboardName(), team.name()), false);
        // The invitee is told even though their progress did not move, because they now have
        // something to accept and no other line will say so.
        target.displayClientMessage(Component.translatable("tasked.party.invited_you", team.name()), false);
        return 1;
    }

    private static int accept(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        TeamManager teams = teamsOrRefuse(context);
        if (teams == null) {
            return 0;
        }

        // Both sides captured before the change, because after it the old team may be gone -- when a
        // player accepts an invite while in a party of one, the old team stops existing, and asking
        // for its members afterwards would find nothing and leave its... no: it had one member, who
        // is the player being told anyway. Captured before regardless, because the general case is a
        // player leaving a party of four to join another, and those three are owed a sync.
        Set<UUID> before = memberIdsOf(teams, player.getUUID());

        Optional<Team> joined = teams.acceptInvite(player.getUUID());
        if (joined.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("tasked.command.party.no_invite"));
            return 0;
        }

        pushMembershipChange(context.getSource(), teams, before, joined.get().memberIds(), player.getUUID());

        context.getSource().sendSuccess(() -> Component.translatable("tasked.command.party.joined",
                joined.get().name()), false);
        return 1;
    }

    private static int leave(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        TeamManager teams = teamsOrRefuse(context);
        if (teams == null) {
            return 0;
        }

        Set<UUID> before = memberIdsOf(teams, player.getUUID());
        if (before.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("tasked.command.party.not_in"));
            return 0;
        }

        teams.leave(player.getUUID());

        pushMembershipChange(context.getSource(), teams, before, memberIdsOf(teams, player.getUUID()),
                player.getUUID());

        context.getSource().sendSuccess(() -> Component.translatable("tasked.command.party.left"), false);
        return 1;
    }

    private static int kick(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(context, "player");
        TeamManager teams = teamsOrRefuse(context);
        if (teams == null) {
            return 0;
        }

        if (target.getUUID().equals(player.getUUID())) {
            context.getSource().sendFailure(Component.translatable("tasked.command.party.kick_self"));
            return 0;
        }

        Set<UUID> before = memberIdsOf(teams, target.getUUID());
        if (before.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("tasked.command.party.target_not_in",
                    target.getScoreboardName()));
            return 0;
        }

        // The authority check is Armature's, deliberately. `kick` returns false for an actor who does
        // not outrank the target -- and re-deriving that here would be a second copy of the rule,
        // which is how a command and an API come to disagree about who may remove whom.
        if (!teams.kick(player.getUUID(), target.getUUID())) {
            context.getSource().sendFailure(Component.translatable("tasked.command.party.no_authority",
                    target.getScoreboardName()));
            return 0;
        }

        pushMembershipChange(context.getSource(), teams, before, memberIdsOf(teams, target.getUUID()),
                target.getUUID());

        context.getSource().sendSuccess(() -> Component.translatable("tasked.command.party.kicked",
                target.getScoreboardName()), false);
        target.displayClientMessage(Component.translatable("tasked.party.you_were_removed"), false);
        return 1;
    }

    private static int disband(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        MinecraftServer server = context.getSource().getServer();
        TeamManager teams = teamsOrRefuse(context);
        if (teams == null) {
            return 0;
        }

        Optional<Team> mine = teams.realTeamOf(player.getUUID());
        if (mine.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("tasked.command.party.not_in"));
            return 0;
        }
        Team team = mine.get();

        if (!team.owner().equals(player.getUUID())) {
            context.getSource().sendFailure(Component.translatable("tasked.command.party.not_owner"));
            return 0;
        }

        Set<UUID> before = new LinkedHashSet<>(team.memberIds());
        if (!teams.disband(player.getUUID(), team.id())) {
            context.getSource().sendFailure(Component.translatable("tasked.command.party.disband_failed"));
            return 0;
        }

        // The mode goes with the party, and this is the one place Armature's own event cannot do it:
        // a source that fires no events never tells the store a party is gone, so on such a server the
        // entry is cleared here or not at all. A party disbanded through a foreign mod's own command
        // still leaves a line behind -- unreadable, since a team id is fresh every time, and the note
        // on PartyStore.clear says so rather than implying otherwise.
        PartyStore.of(server).clear(team.id());

        pushMembershipChange(context.getSource(), teams, before, Set.of(), player.getUUID());

        context.getSource().sendSuccess(() -> Component.translatable("tasked.command.party.disbanded",
                team.name()), false);
        return 1;
    }

    private static int setMode(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        MinecraftServer server = context.getSource().getServer();
        String wanted = StringArgumentType.getString(context, "mode");

        Optional<PartyMode> mode = PartyMode.byId(wanted);
        if (mode.isEmpty()) {
            // One message for a bad name, saying what the names are. `PartyMode.ids()` rather than a
            // list written here, which is the same reason the mode is spelled one way for the file and
            // the command: a second spelling drifts the first time a mode is renamed.
            context.getSource().sendFailure(Component.translatable("tasked.command.party.mode_unknown",
                    wanted, PartyMode.ids()));
            return 0;
        }

        UUID owner = TaskedPartyCommand.ownerOf(server, player);
        PartyStore store = PartyStore.of(server);

        if (store.modeOf(owner) == mode.get()) {
            context.getSource().sendSuccess(() -> Component.translatable("tasked.command.party.mode_same",
                    mode.get().id()), false);
            return 0;
        }

        store.put(owner, mode.get());

        // Pushed, because the counting rule has just changed and the whole party's view may have. A
        // delta rather than a full sync is correct and is what REASON_CHANGED means: progress is
        // monotonic, so a mode change can only raise what a party has, never lower it -- switching
        // from pooled back to one-member does not un-complete anything, because the engine keeps the
        // best count it has seen.
        TaskedNetworking.sendProgressToTeam(server, player, ProgressSyncPayload.REASON_CHANGED);

        // And the roster, because the mode travels on it and the party panel now draws it as a line of
        // text. Without this a party watching the panel would keep reading the old rule until a member
        // joined or left -- which is the shape of "the button did nothing" that the row answers.
        TaskedNetworking.sendPartyToTeam(server, owner);

        context.getSource().sendSuccess(() -> Component.translatable("tasked.command.party.mode_set",
                mode.get().id(), mode.get().description()), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // The three things a command adds
    // ------------------------------------------------------------------

    /**
     * The team manager, or a refusal that names the source.
     *
     * <p>Returning null rather than throwing is the whole point of it existing. A source that cannot
     * write throws {@link UnsupportedOperationException} from every mutator — by design, so that it
     * says so rather than silently doing nothing — and letting that escape would present a library's
     * exception to a player as a crash. The message is strictly more useful than the exception,
     * because it names which mod owns the parties on this server.
     */
    private static TeamManager teamsOrRefuse(CommandContext<CommandSourceStack> context) {
        TeamManager teams = Teams.of(context.getSource().getServer());

        if (!teams.managesMembership()) {
            context.getSource().sendFailure(Component.translatable("tasked.command.party.not_ours",
                    teams.name()));
            return null;
        }
        return teams;
    }

    /**
     * Tells the clients whose progress moved, when nothing else will.
     *
     * <h2>Why this is conditional, and why the condition is the honest one</h2>
     *
     * <p>A source that fires events ({@link TeamManager#firesEvents()} is true) announces its own
     * membership changes, and Tasked's listener on those events does the pushing. A source that does
     * not is one where this is the only chance anybody gets — a party changed and Armature was not
     * told. When both fired, every member would receive two full syncs for one change, and the
     * message count is exactly what the playthrough asserts on, so the duplication would be a failing
     * test rather than a shrug.
     *
     * <p>The condition is read off the interface rather than guessed from the source's name, so it
     * cannot go stale: FTB Teams bridges its party events and answers true, Open Parties and Claims
     * offers no party event to bridge and answers false, and this does the right thing for each
     * without naming either.
     *
     * <h2>The audience, and why it is a union</h2>
     *
     * <p>"The people who were in it" and "the people who are in it" are different sets, and a
     * membership change moves progress for both: the members who remain are now sharing with one more
     * or one fewer player, and the player who moved has had their progress owner change under them.
     * A caller that pushed to only the team as it now is would leave the leaver stale — which is the
     * same fault {@code sendTeamChange}'s {@code alsoThis} parameter exists for, at the other end.
     *
     * <p>So a caller passes the membership <b>before</b> and <b>after</b>, and the player, and this
     * works out the rest. Both are needed at the call sites anyway to describe what the command did,
     * and computing them there is what keeps this from having to ask "what changed" after the fact —
     * a question whose answer is already gone by then.
     */
    private static void pushMembershipChange(CommandSourceStack source,
                                             TeamManager teams,
                                             Set<UUID> before,
                                             Set<UUID> after,
                                             UUID subject) {
        if (teams.firesEvents()) {
            // The source's own event carries this, and Tasked's listener acts on it. Doing it here as
            // well would be two syncs for one change.
            return;
        }
        Set<UUID> affected = new LinkedHashSet<>(before);
        affected.addAll(after);
        affected.add(subject);
        pushTo(source, affected);
    }

    /** One full sync per player who needs one, by id, skipping anybody offline. */
    private static void pushTo(CommandSourceStack source, Set<UUID> players) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            return;
        }
        for (UUID player : players) {
            // The full-sync reason rather than REASON_CHANGED: a player whose progress owner has moved
            // is holding the previous team's progress entirely, and a delta against it would merge one
            // party's questline onto another's.
            TaskedNetworking.sendProgressToPlayer(server, player, ProgressSyncPayload.REASON_TEAM_CHANGED);
        }
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    /** The ids of {@code player}'s real team, or empty when they are solo. */
    private static Set<UUID> memberIdsOf(TeamManager teams, UUID player) {
        return teams.realTeamOf(player)
                .map(team -> (Set<UUID>) new LinkedHashSet<>(team.memberIds()))
                .orElseGet(Set::of);
    }

    private static UUID ownerOf(MinecraftServer server, ServerPlayer player) {
        return Teams.teamOf(server, player.getUUID()).id();
    }

    /** A player's name, or their id when they are offline. */
    private static String nameOf(MinecraftServer server, UUID player) {
        ServerPlayer found = server.getPlayerList().getPlayer(player);
        return found != null ? found.getScoreboardName() : player.toString();
    }
}
