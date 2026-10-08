package dev.ellipog.tenet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import dev.ellipog.armature.api.ArmatureApi;
import dev.ellipog.armature.api.config.ArmatureConfig;
import dev.ellipog.armature.api.config.TeamSettings;
import dev.ellipog.tenet.editor.EditorOp;
import dev.ellipog.tenet.editor.EditorOps;
import dev.ellipog.tenet.progress.ProgressService;
import dev.ellipog.tenet.progress.ProgressionEngine;
import dev.ellipog.tenet.progress.QuestState;
import dev.ellipog.tenet.progress.StageService;
import dev.ellipog.tenet.quest.Chapter;
import dev.ellipog.tenet.quest.ChapterGroup;
import dev.ellipog.tenet.quest.PrerequisiteMode;
import dev.ellipog.tenet.quest.Quest;
import dev.ellipog.tenet.quest.QuestIndex;
import dev.ellipog.tenet.quest.QuestLoader;
import dev.ellipog.tenet.quest.QuestReward;
import dev.ellipog.tenet.quest.QuestSettings;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TenetQuests;
import dev.ellipog.tenet.quest.TreeRefresh;
import dev.ellipog.tenet.quest.condition.ConditionTypes;
import dev.ellipog.tenet.quest.reward.RewardTypes;
import dev.ellipog.tenet.quest.task.TaskTypes;
import dev.ellipog.tenet.net.ProgressSyncPayload;
import dev.ellipog.tenet.net.TenetNetworking;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code /tenet} — the command tree.
 *
 * <h2>Why the questline is playable by command before the screen exists</h2>
 *
 * <p>Stage 3's "done when" is a whole questline completable through commands, with no GUI. That is
 * not a shortcut — it is the order the plan deliberately chose. A quest engine exercised through a
 * UI has two things that could be wrong at once, and when a quest does not complete you cannot tell
 * whether that is the engine, the sync, or the button. By command, there is one candidate.
 *
 * <p>So {@code /tenet progress} prints what is available and how far along each task is,
 * {@code /tenet submit} does what a click will do later, and {@code /tenet complete} forces a
 * quest through for testing a chain. The GUI in Stage 6 calls the same {@link ProgressService}
 * methods these do.
 *
 * <p>Registered from a listener on
 * {@link dev.ellipog.armature.api.event.ArmatureEvents#COMMANDS_REGISTER} rather than once at
 * construction, because that event fires again on every datapack reload and the dispatcher is
 * rebuilt each time. Registering once would leave the commands missing after a {@code /reload}.
 */
public final class TenetCommand {

    private TenetCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(Tenet.MOD_ID)
                .then(Commands.literal("version")
                        .executes(TenetCommand::version))

                .then(Commands.literal("reload")
                        .requires(QuestAuthority.mayEdit())
                        .executes(TenetCommand::reload))

                // What a delete left behind, and the way back to it. An operator's pair, like `reload`:
                // both are about the server's own files, and the copy a delete sets aside is invisible to
                // every screen -- the editor's tree skips tombstones by design, and the history that
                // recorded the delete is the server's memory rather than anything on disk.
                .then(Commands.literal("removed")
                        .requires(QuestAuthority.mayEdit())
                        .executes(TenetCommand::removed))

                .then(Commands.literal("restore")
                        .requires(QuestAuthority.mayEdit())
                        .then(Commands.argument("path", StringArgumentType.string())
                                .executes(TenetCommand::restore)))

                // Where the server's settings live and what is in force. Read-only, and still an
                // operator's read-out rather than a player's: it names server file paths and the pack's
                // own settings, which is the same permission the file-changing commands take.
                .then(Commands.literal("config")
                        .requires(QuestAuthority.mayEdit())
                        .executes(TenetCommand::config))

                .then(Commands.literal("quests")
                        .executes(TenetCommand::quests))

                // The frame rate, as its own operator switch rather than a side effect of edit mode. Bare,
                // it flips; with an argument it says which way, because a script or a habit that expects one
                // state should not have to know what the last one was.
                .then(Commands.literal("vitals")
                        .requires(QuestAuthority.mayEdit())
                        .executes(context -> vitals(context, Optional.empty()))
                        .then(Commands.literal("on")
                                .executes(context -> vitals(context, Optional.of(true))))
                        .then(Commands.literal("off")
                                .executes(context -> vitals(context, Optional.of(false)))))

                // The flat-icon experiment, and it is temporary by design: four ways to draw an icon whose
                // model is one textured quad, so the arm that actually shows the item names the cause of the
                // one that did not. It sets a **system property** rather than a payload because the point is
                // to flip it in a running game and read the screen, and because singleplayer — where this is
                // being diagnosed — runs the server in the client's own JVM. On a dedicated server it would
                // set a property on the server's JVM and do nothing visible, which is why it says so.
                .then(Commands.literal("iconmode")
                        .requires(QuestAuthority.mayEdit())
                        .then(Commands.argument("arm", IntegerArgumentType.integer(0, 3))
                                .executes(TenetCommand::iconMode)))

                // What one editing gesture costs on the server, as a debug log line. Server-side, so unlike
                // `vitals` it needs no player and works from the console -- and it has to work from the
                // console, because the question it answers is about a server's cost rather than a client's.
                .then(Commands.literal("editcost")
                        .requires(QuestAuthority.mayEdit())
                        .executes(context -> editCost(context, Optional.empty()))
                        .then(Commands.literal("on")
                                .executes(context -> editCost(context, Optional.of(true))))
                        .then(Commands.literal("off")
                                .executes(context -> editCost(context, Optional.of(false)))))

                .then(Commands.literal("quest")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(TenetCommand::quest)))

                // --- the engine, by command ---

                .then(Commands.literal("progress")
                        .executes(TenetCommand::progress))

                .then(Commands.literal("submit")
                        .then(Commands.argument("quest", StringArgumentType.word())
                                .executes(ctx -> submit(ctx, 0))
                                .then(Commands.argument("task", IntegerArgumentType.integer(0))
                                        .executes(ctx -> submit(ctx, IntegerArgumentType.getInteger(ctx, "task"))))))

                .then(Commands.literal("complete")
                        .requires(QuestAuthority.mayEdit())
                        .then(Commands.argument("quest", StringArgumentType.word())
                                .executes(TenetCommand::complete)))

                // No permission gate, and that is the point rather than an omission: this is a player
                // collecting what they already earned, not an operator changing anything. The same
                // call the quest book's Claim button makes.
                .then(Commands.literal("claim")
                        .then(Commands.argument("quest", StringArgumentType.word())
                                .executes(TenetCommand::claim)))

                // Whether this team's payouts are held. An operator's switch rather than a player's,
                // which is why it takes the edit permission: the point of blocking is that it is not
                // up to the player being paid. The flag lives in team progress and syncs with it.
                .then(Commands.literal("rewards")
                        .requires(QuestAuthority.mayEdit())
                        .then(Commands.literal("block")
                                .executes(ctx -> setRewardsBlocked(ctx, true)))
                        .then(Commands.literal("unblock")
                                .executes(ctx -> setRewardsBlocked(ctx, false))))

                .then(Commands.literal("reset")
                        .requires(QuestAuthority.mayEdit())
                        .executes(ctx -> reset(ctx, null))
                        .then(Commands.argument("quest", StringArgumentType.word())
                                .executes(ctx -> reset(ctx, StringArgumentType.getString(ctx, "quest")))))

                .then(Commands.literal("types")
                        .executes(TenetCommand::types))

                // Stages: the flags a pack's quests and scripts ask about. Add and remove are an operator's
                // business -- they hand out progression -- while listing is something a player may do for
                // themselves, which is why the gate is on the two subcommands rather than on the subtree.
                .then(Commands.literal("stage")
                        .then(Commands.literal("add")
                                .requires(QuestAuthority.mayEdit())
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("stage", ResourceLocationArgument.id())
                                                .executes(ctx -> stage(ctx, true)))))
                        .then(Commands.literal("remove")
                                .requires(QuestAuthority.mayEdit())
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("stage", ResourceLocationArgument.id())
                                                .executes(ctx -> stage(ctx, false)))))
                        .then(Commands.literal("list")
                                .executes(ctx -> stageList(ctx, null))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .requires(QuestAuthority.mayEdit())
                                        .executes(ctx -> stageList(ctx,
                                                EntityArgument.getPlayer(ctx, "player"))))))

                // The reward tables, in their own file: reading a chest and filling one are aimed
                // actions, and the editor cannot do them because a screen captures the mouse.
                .then(TenetTableCommand.node())

                // The party commands, in their own file. Not a tidiness split: this one is 598 lines
                // about quests, and party membership is a different subject with a different owner --
                // Armature's teams. Two subjects, two files, and the boundary is where a reader would
                // look for it.
                .then(TenetPartyCommand.node())

                // `/tenet theme` and `/tenet motion` were here. They are gone, and where they went
                // is the point rather than the tidy-up.
                //
                // They were client preferences wearing a command's clothes. A command runs on the
                // server: in single player that is the same process as the client, so both appeared to
                // work, and on a dedicated server they changed a field in a process with no window --
                // which is why both had to be wrapped in an `isClient()` guard to avoid being a lie. A
                // control that has to defend against the side it runs on is a control on the wrong side,
                // and the guard was the symptom rather than the cure.
                //
                // They are two rows at the foot of the quest book's sidebar now, which is where a
                // setting that applies to the whole screen belongs and where it can be found without
                // knowing the command existed. The state and the file behind it are in
                // `Armature's Appearance` -- see its class comment for the base-and-override split
                // that lets a chapter dress itself without taking the choice away from the player.
                //
                // Worth recording what the removal gained, beyond the surface: the setting is
                // testable. A command cannot be, so the reading of "did that work" needed a running
                // game and a person to notice; `AppearanceTest` covers the same ground in milliseconds.

                .then(Commands.literal("echo")
                        // Not useful. Kept because it is the cheapest proof that Brigadier arguments
                        // and a return value survive the trip through Armature's command event.
                        .then(Commands.argument("times", IntegerArgumentType.integer(1, 5))
                                .executes(TenetCommand::echo))));
    }

    // ------------------------------------------------------------------
    // Information
    // ------------------------------------------------------------------

    private static int version(CommandContext<CommandSourceStack> context) {
        String version = ArmatureApi.platform().modVersion(Tenet.MOD_ID).orElse("unknown");
        String armature = ArmatureApi.platform().modVersion("armature").orElse("absent");

        context.getSource().sendSuccess(() -> Component.translatable(
                "tenet.command.version", version, armature), false);
        return 1;
    }

    /**
     * Where the server's settings live, and what is in force.
     *
     * <p>The other half of the settings story: a player's own text size lives in the quest book's
     * Settings card and their look in the tools panel, while these are the server's -- a party's cap
     * and a new party's policy, and the tree-wide quest defaults -- and they are files. This prints the
     * running server actually resolved, not the defaults, so an operator can tell "I edited it" from
     * "it took", and names the files to edit rather than describing them.
     */
    private static int config(CommandContext<CommandSourceStack> context) {
        TeamSettings teams = ArmatureConfig.current().teams();
        QuestSettings quests = TenetQuests.settings();
        var source = context.getSource();

        source.sendSuccess(() -> Component.literal("Server settings in force:"), false);
        source.sendSuccess(() -> Component.literal("  parties: maxMembers=" + teams.maxMembers()
                + ", a new party: member invites=" + teams.newPartyMembersCanInvite()
                + ", open join=" + teams.newPartyOpenJoin()), false);
        source.sendSuccess(() -> Component.literal("  quests: defaultAutoClaim="
                + quests.defaultAutoClaim().name().toLowerCase(java.util.Locale.ROOT)
                + ", defaultTeamReward=" + quests.defaultTeamReward()
                + ", suppressAllAutoclaiming=" + quests.suppressAllAutoclaiming()
                + ", detectionDelay=" + quests.detectionDelay() + " ticks"), false);
        source.sendSuccess(() -> Component.literal("Files: " + configPath("armature") + " and "
                + configPath(Tenet.MOD_ID) + "/quests/index.json. Edit, then /tenet reload."), false);
        source.sendSuccess(() -> Component.literal("A player's own text size is theirs: the quest book's"
                + " Settings button. The palette, the corner radius and the Motion switch are in the tools"
                + " panel, behind edit permission."), false);
        return 1;
    }

    /** A mod's config directory, or a phrase saying it could not be resolved. */
    private static String configPath(String modId) {
        try {
            return ArmatureApi.platform().configDir(modId).toString();
        }
        catch (RuntimeException e) {
            return "<config directory unavailable>";
        }
    }

    /**
     * The vitals overlay: the frame rate and the frame's counters, on this operator's client.
     *
     * <h2>Why this is a command rather than a button</h2>
     *
     * <p>Because it is an instrument, and the people who want it are the people diagnosing a frame — an
     * operator with a slow chapter, not every player. A button in the tools panel would put it behind edit
     * mode again (which is what this replaces) and would be reachable by whoever the book lets edit; a
     * command is gated by the one permission the server actually owns, and it can be typed from anywhere,
     * including on a client that has no quest book open.
     *
     * <p>The reply names the state rather than saying "done", because the interesting case is a client that
     * did not get the message — a disconnect between the command and the send — and a reply that only said
     * "ok" would look identical.
     */
    private static int vitals(CommandContext<CommandSourceStack> context, Optional<Boolean> wanted) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal(
                    "The vitals overlay is drawn by a client, so this has to be run by a player."));
            return 0;
        }
        boolean on = TenetNetworking.setVitals(player, wanted.orElse(!TenetNetworking.vitals(player)));
        context.getSource().sendSuccess(() -> Component.literal(
                "Vitals overlay " + (on ? "on" : "off") + " for " + player.getGameProfile().getName()
                        + ". It shows the frame rate and frame time, and the frame's counters."), false);
        return 1;
    }

    /**
     * The edit-cost counter: what one editing gesture costs on the server, once a second to the log.
     *
     * <h2>Why this is not {@code vitals}</h2>
     *
     * <p>Because it is the opposite side of the same question. {@code vitals} is a <b>client</b> instrument —
     * a frame rate and a frame's counters, drawn by a player who has to be there to see them — and it
     * refuses from the console for that reason. This measures the <b>server's</b> half of an edit: the apply,
     * the save, the writes, the fsyncs and the coalesced flush. So it needs no player, and it deliberately
     * works from the console, because the server whose cost is in question is often a dedicated one nobody is
     * sitting in front of.
     *
     * <h2>Why a command rather than always on</h2>
     *
     * <p>Because it is a diagnostic, and a diagnostic that is always on is a permanent cost for a temporary
     * question. Off, every entry point is one static-boolean branch and no clock read at all — see
     * {@code EditPhases} — so the switch is what makes it acceptable to have the call sites on the apply
     * path. Session-only: nothing is written to disk, so a restart forgets, and a preference that outlives a
     * session is a different feature.
     */
    private static int editCost(CommandContext<CommandSourceStack> context, Optional<Boolean> wanted) {
        boolean on = dev.ellipog.tenet.editor.EditPhases.on();
        boolean next = wanted.orElse(!on);
        dev.ellipog.tenet.editor.EditPhases.set(next);
        context.getSource().sendSuccess(() -> Component.literal(
                "Edit cost " + (next ? "on" : "off") + ". " + (next
                        ? "Once a second, at debug level: ops, applyMs, writes and syncs, plus a line per"
                                + " coalesced flush with the reload, encode and deflate."
                        : "The counter is off and holds nothing.")), false);
        return 1;
    }

    /**
     * The flat-icon experiment: which of four ways an icon that is one textured quad is drawn.
     *
     * <p>Four arms, and each answers a different question — the plain blit that drew nothing, the blit that
     * writes a per-vertex colour, the blit bracketed by flushes so it is submitted immediately as
     * {@code renderItem} gets for free, and the pipeline itself as the control. Whichever arm shows the items
     * names the cause; the whole command goes away with the answer.
     *
     * <p>It sets a **system property**, and that is deliberate: the property is read by the drawing each
     * call, so a flip takes effect on the next frame without a reload, and singleplayer — where this is being
     * diagnosed — runs the server in the client's own JVM. On a dedicated server the property is set on the
     * server and nothing visible happens, which the reply says.
     */
    private static int iconMode(CommandContext<CommandSourceStack> context) {
        int arm = IntegerArgumentType.getInteger(context, "arm");
        System.setProperty("armature.iconmode", Integer.toString(arm));
        context.getSource().sendSuccess(() -> Component.literal(
                "Icon drawing arm " + arm + " (" + switch (arm) {
                    case 1 -> "plain blit";
                    case 2 -> "blit with a per-vertex colour";
                    case 3 -> "blit with a flush either side";
                    default -> "the pipeline, which is the correct one";
                } + "). Temporary diagnostic: it only affects a client sharing this JVM, so singleplayer."),
                false);
        return 1;
    }

    /**
     * Every recoverable delete under the quest folder.
     *
     * <h2>Why this is a command rather than a panel</h2>
     *
     * <p>Because nothing else can produce the list. A tombstone is skipped by every walk — that is what
     * makes a delete a delete — so the editor's tree does not know it is there, and the one fact an author
     * needs after the history is gone (their work is still on disk) is a fact no screen shows. It is an
     * operator's read-out for the same reason {@code reload} is: it names the server's own files.
     */
    private static int removed(CommandContext<CommandSourceStack> context) {
        List<dev.ellipog.tenet.quest.Removed> removed = TenetQuests.removed();
        if (removed.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal(
                    "Nothing is set aside: no quest file, chapter, group or table has been removed."), false);
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal(removed.size()
                + (removed.size() == 1 ? " thing is" : " things are")
                + " set aside under the quest folder:"), false);
        for (dev.ellipog.tenet.quest.Removed each : removed) {
            context.getSource().sendSuccess(() -> Component.literal("  " + each.sentence()), false);
        }
        context.getSource().sendSuccess(() -> Component.literal(
                "Put one back with /tenet restore <path>, using the path above."), false);
        return removed.size();
    }

    /**
     * Puts one set-aside thing back.
     *
     * <p>Through the ops every other file change travels through, rather than by reaching for the
     * filesystem here: the path is resolved and refused by the model that owns the root, the write is
     * validated the way every edit is, and the tree is re-synced by the same coalesced refresh an edit
     * arms — so a restored chapter appears for everybody without a reload, and without the reload throwing
     * the undo history away.
     */
    private static int restore(CommandContext<CommandSourceStack> context) {
        String path = StringArgumentType.getString(context, "path");
        EditorOps.Applied applied = TenetQuests.restore(path);
        if (applied.ok()) {
            TreeRefresh.request(EditorOps.reachOf(new EditorOp.RestoreRemoved(path)));
        }
        for (String line : applied.messages()) {
            if (applied.ok()) {
                context.getSource().sendSuccess(() -> Component.literal(line), false);
            }
            else {
                context.getSource().sendFailure(Component.literal(line));
            }
        }
        return applied.ok() ? 1 : 0;
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        // Before the load, and here rather than inside it: this is the command that means "the files may have
        // changed without me", so it is the only place the editor's open chapters are dropped. Doing it inside
        // `TenetQuests.reload` would throw the undo history away after every applied op, because that is what
        // applying an op calls to put its own write on the canvas.
        TenetQuests.editors().forget();
        // And the table editors' draft cache, for the same reason and in the same place: a table edit
        // validates against the file as it was last read, so a hand edit would be silently reverted by
        // the next in-game one. `/tenet reload` is the one gesture that says "the files changed
        // without me", so it is where that cache goes.
        TenetQuests.tables().forget();
        // And Armature's own settings file, which is the other file this command's read-out names:
        // `ArmatureConfig.install` is built for a second call -- its own note names "a reload command"
        // -- and this is the gesture it was written for. Not inside `TenetQuests.reload`, for the
        // reason above: the editor's refresh calls that after every applied op, and a settings file is
        // not the tree.
        ArmatureConfig.install(ArmatureApi.platform().configDir("armature"));
        QuestLoader.Result result = TenetQuests.reload();
        var problems = result.problems();

        context.getSource().sendSuccess(() -> Component.translatable("tenet.command.reload.summary",
                result.filesDecoded(), result.filesFound(), problems.errorCount(), problems.warningCount()), false);
        // filesWithErrors, not filesDecoded: a file can decode and still have a circular dependency
        // in it, and reporting "everything loaded" for that is a lie the author would act on.

        if (result.filesWithErrors() > 0) {
            context.getSource().sendFailure(Component.translatable("tenet.command.reload.failed",
                    result.filesWithErrors()));
        }
        if (!problems.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.translatable("tenet.command.reload.log"), false);
        }

        // Tell everyone what is loaded now -- the tree *and* their progress.
        //
        // A reload can change both: a quest removed, an id renamed, a dependency broken so a quest
        // locks again. Without this, every connected client keeps the tree it was sent at join, so an
        // author's fix appears to do nothing until they reconnect -- which is the same trap as the
        // deploy-does-not-copy-quests one, one layer in.
        //
        // Worth noting what this line's absence was: `TenetNetworking.sendTreeToAll` existed, was
        // documented, and had no caller anywhere in the mod. That is the same shape of gap as the
        // missing progress push -- a method that does exactly the right thing and nothing that calls
        // it -- and it is invisible to a compiler, a test and a reader, because code that is never
        // called looks the same as code that is.
        MinecraftServer server = context.getSource().getServer();
        if (server != null) {
            TenetNetworking.sendTreeToAll(server);
            // And the editors' undo history, which the two `forget` calls above have just thrown away. The
            // tree being right is not enough: the book's undo button is drawn from counters the *client*
            // moves, so without this it went on offering an undo over a history that no longer existed, and
            // a Ctrl+Z sent an op the server had nothing to answer with. See `EditHistoryPayload`.
            TenetNetworking.sendEditHistoryDiscardedToAll(server);
        }
        return result.filesDecoded();
    }

    private static int quests(CommandContext<CommandSourceStack> context) {
        QuestIndex index = TenetQuests.index();

        if (index.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.translatable("tenet.command.quests.empty"), false);
            return 0;
        }

        context.getSource().sendSuccess(() -> Component.translatable("tenet.command.quests.summary",
                index.questCount(), index.chapterCount(), index.groupCount()), false);

        for (QuestIndex.GroupEntry entry : index.groups()) {
            ChapterGroup group = entry.group();
            context.getSource().sendSuccess(() -> Component.literal("  " + group.title().value()), false);
            for (Chapter chapter : group.chapters()) {
                context.getSource().sendSuccess(() -> Component.literal(
                        "    " + chapter.title().value() + "  §8[" + chapter.id() + "]"), false);
                for (Quest quest : chapter.quests()) {
                    context.getSource().sendSuccess(() -> Component.literal(
                            "      " + quest.title().value() + "  §8[" + quest.id() + "]"
                                    + "  §7" + quest.tasks().size() + "t/" + quest.rewards().size() + "r"), false);
                }
            }
        }
        return index.questCount();
    }

    private static int quest(CommandContext<CommandSourceStack> context) {
        String id = StringArgumentType.getString(context, "id");

        return TenetQuests.find(id).map(entry -> {
            Quest quest = entry.quest();

            context.getSource().sendSuccess(() -> Component.translatable("tenet.command.quest.header",
                    quest.title().value(), quest.id()), false);
            context.getSource().sendSuccess(() -> Component.literal("  §7where: §f" + entry.location()), false);
            context.getSource().sendSuccess(() -> Component.literal(
                    "  §7chapter: §f" + entry.chapterId() + " §7group: §f" + entry.groupId()), false);
            context.getSource().sendSuccess(() -> Component.literal(
                    "  §7icon: §f" + quest.icon().describe()
                            + " §7at §f" + quest.layout().x() + "," + quest.layout().y()
                            + " §7shape §f" + quest.layout().shape().name().toLowerCase()), false);

            PrerequisiteMode mode = effectiveMode(entry);
            if (!quest.dependencies().isEmpty()) {
                context.getSource().sendSuccess(() -> Component.literal(
                        "  §7depends on: §f" + quest.dependencies().stream().map(ref -> ref.id()).toList()
                                + " §7(" + mode + ", needs " + quest.requiredCount(mode) + ")"), false);
            }
            else {
                context.getSource().sendSuccess(() -> Component.translatable("tenet.command.text.7no_dependencies"), false);
            }

            // The flags that change behaviour, so a file with them set can be confirmed to have
            // loaded them -- they are invisible otherwise until something behaves oddly.
            StringBuilder flags = new StringBuilder();
            if (quest.repeatable()) {
                flags.append("repeatable");
                if (quest.repeatCooldownTicks() > 0) {
                    flags.append(" (cooldown ").append(quest.repeatCooldownTicks()).append("t)");
                }
            }
            if (quest.sequentialTasks()) {
                flags.append(flags.length() > 0 ? ", " : "").append("sequential");
            }
            if (quest.invisible()) {
                flags.append(flags.length() > 0 ? ", " : "").append("invisible");
            }
            quest.exclusiveGroup().ifPresent(group ->
                    flags.append(flags.length() > 0 ? ", " : "").append("exclusive:").append(group));
            if (flags.length() > 0) {
                context.getSource().sendSuccess(() -> Component.literal("  §7flags: §f" + flags), false);
            }

            for (int i = 0; i < quest.tasks().size(); i++) {
                QuestTask task = quest.tasks().get(i);
                final int index = i;
                String suffix = task.optional() ? " §8(optional)" : "";
                context.getSource().sendSuccess(() -> Component.literal(
                        "  §a+§f task §7" + index + "§f " + describe(task) + suffix), false);
            }
            for (QuestReward reward : quest.rewards()) {
                context.getSource().sendSuccess(() -> Component.literal(
                        "  §6-§f reward §7" + reward.type() + "§f " + describe(reward)), false);
            }
            return 1;
        }).orElseGet(() -> {
            context.getSource().sendFailure(Component.translatable("tenet.command.quest.notfound", id));
            return 0;
        });
    }

    /**
     * What this player can do right now, and how far along.
     *
     * <p>The command that replaces the quest screen until Stage 6, so it has to be genuinely useful:
     * every playable quest, its state, and per task how much is done against how much is needed.
     */
    private static int progress(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        QuestIndex index = TenetQuests.index();

        if (index.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.translatable("tenet.command.quests.empty"), false);
            return 0;
        }

        ProgressionEngine.Resolution resolution =
                ProgressService.resolutionFor(context.getSource().getServer(),
                        ProgressService.progressOwner(context.getSource().getServer(), player));

        context.getSource().sendSuccess(() -> Component.translatable("tenet.command.progress.header",
                resolution.unlockedCount(), index.questCount(), resolution.completedCount()), false);

        // Fetched once, outside the loop. It was inside it, once per quest, which is the same value
        // read eighty times -- and now that `claimable` is asked of it, a per-quest fetch would also
        // be a per-quest chance to ask the wrong question.
        var teamProgress = ProgressService.progressFor(context.getSource().getServer(),
                ProgressService.progressOwner(context.getSource().getServer(), player));

        int shown = 0;
        for (QuestIndex.QuestEntry entry : index.quests()) {
            Quest quest = entry.quest();
            QuestState state = resolution.stateOf(quest);

            // Completed and non-repeatable is not interesting to list; locked is not actionable.
            // A repeatable quest that is done still is, because it can be done again -- and so is one
            // with a payout still waiting, which is the case where leaving it out would hide the only
            // thing the player is meant to do next.
            boolean claimable = ProgressService.canClaimFor(
                    context.getSource().getServer(), teamProgress, quest, player.getUUID());
            boolean worthShowing = state.isPlayable() || claimable
                    || (state == QuestState.COMPLETED && quest.repeatable());
            if (!worthShowing || (quest.invisible() && state != QuestState.COMPLETED)) {
                continue;
            }
            shown++;

            final QuestState shownState = state;
            final boolean showClaimable = claimable;
            long cooldown = resolution.cooldownOf(quest);
            context.getSource().sendSuccess(() -> Component.literal(
                    "  " + stateColour(shownState) + shownState.label() + "§r "
                            + quest.title().value() + "  §8[" + quest.id() + "]"
                            + (showClaimable ? "  §6(rewards waiting - /tenet claim " + quest.id() + ")" : "")
                            + (cooldown > 0 ? "  §7(ready in " + (cooldown / 20) + "s)" : "")), false);

            var stored = teamProgress.progressOf(quest);

            for (int i = 0; i < quest.tasks().size(); i++) {
                QuestTask task = quest.tasks().get(i);
                final int taskIndex = i;
                int recorded = stored.progressOf(taskIndex);
                int required = TaskTypes.behaviourOf(task)
                        .map(behaviour -> behaviour.required(task))
                        .orElse(1);
                boolean done = recorded >= required;

                context.getSource().sendSuccess(() -> Component.literal(
                        "      " + (done ? "§a[ok]" : "§7[  ]") + "§r §7" + taskIndex + "§r "
                                + describe(task) + "  §7" + Math.min(recorded, required) + "/" + required), false);
            }
        }

        if (shown == 0) {
            context.getSource().sendSuccess(() -> Component.translatable("tenet.command.progress.none"), false);
        }
        return shown;
    }

    private static int types(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(
                "§7task types (" + TaskTypes.count() + "):"), false);
        TaskTypes.ids().forEach(id -> context.getSource().sendSuccess(
                () -> Component.literal("  §f" + id + " §7fields: " + TaskTypes.fieldsOf(id)), false));

        context.getSource().sendSuccess(() -> Component.literal(
                "§7reward types (" + RewardTypes.count() + "):"), false);
        RewardTypes.ids().forEach(id -> context.getSource().sendSuccess(
                () -> Component.literal("  §f" + id + " §7fields: " + RewardTypes.fieldsOf(id)), false));

        context.getSource().sendSuccess(() -> Component.literal(
                "§7condition types (" + ConditionTypes.count() + "):"), false);
        ConditionTypes.ids().forEach(id -> context.getSource().sendSuccess(
                () -> Component.literal("  §f" + id + " §7fields: " + ConditionTypes.fieldsOf(id)), false));

        return TaskTypes.count() + RewardTypes.count() + ConditionTypes.count();
    }

    // ------------------------------------------------------------------
    // The engine
    // ------------------------------------------------------------------

    /**
     * Submits a task by hand.
     *
     * <p>Reaches {@link ProgressService#submit}, which is exactly what a button in the quest screen
     * will call in Stage 6. So this is not a test-only path pretending to be one — it is the real
     * thing with a command in front of it.
     */
    private static int submit(CommandContext<CommandSourceStack> context, int taskIndex)
            throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String id = StringArgumentType.getString(context, "quest");

        Optional<QuestIndex.QuestEntry> entry = TenetQuests.find(id);
        if (entry.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("tenet.command.quest.notfound", id));
            return 0;
        }

        boolean changed = ProgressService.submit(context.getSource().getServer(), player, entry.get(), taskIndex);
        if (!changed) {
            context.getSource().sendFailure(Component.translatable("tenet.command.submit.refused", id, taskIndex));
            return 0;
        }
        pushToTeam(context.getSource(), player);
        context.getSource().sendSuccess(() -> Component.translatable("tenet.command.submit.done", id, taskIndex), false);
        return 1;
    }

    /**
     * Forces a quest complete. Its rewards are recorded as waiting, not handed over.
     *
     * <p>Op level 2, and deliberately not routed through task evaluation: its whole purpose is to
     * skip the requirements, which is what makes a long chain testable without gathering forty
     * stacks of cobblestone. It goes through the same {@link ProgressService#complete} the engine
     * uses, so the saved state and the completion guard are both exercised — and since that method no
     * longer grants anything, this command no longer does either. Use {@code /tenet claim} for the
     * rewards, which is what a player does.
     */
    private static int complete(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String id = StringArgumentType.getString(context, "quest");

        Optional<QuestIndex.QuestEntry> entry = TenetQuests.find(id);
        if (entry.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("tenet.command.quest.notfound", id));
            return 0;
        }

        var server = context.getSource().getServer();
        java.util.UUID owner = ProgressService.progressOwner(server, player);
        var progress = ProgressService.progressFor(server, owner);

        // Refuse a quest that is not playable, so this cannot be used to skip a locked chain. An op
        // who wants that can complete the prerequisite first, which is a more honest test anyway.
        QuestState state = ProgressionEngine.resolve(TenetQuests.index(), progress,
                server.overworld().getGameTime()).stateOf(entry.get().quest());
        if (!state.isPlayable()) {
            context.getSource().sendFailure(Component.translatable("tenet.command.complete.locked",
                    id, state.label()));
            return 0;
        }

        // Playable is not the same as completable, now that collecting a payout is a separate act. A
        // repeatable quest that is finished with its rewards still waiting is playable -- its tasks are
        // satisfied and the cooldown has not started -- so the check above lets it through and
        // `complete` would do nothing. Reporting success there would be this command telling an
        // operator that something happened when nothing did.
        if (!ProgressService.canComplete(entry.get().quest(), progress)) {
            context.getSource().sendFailure(Component.translatable("tenet.command.complete.pending", id));
            return 0;
        }

        // And the stage gate, which is per player and therefore invisible to both checks above: the
        // engine's own answer for the team is playable, and `canComplete` is about tasks. Without this
        // the call below still refuses -- the guard is in `complete` itself, where the tick path also
        // reaches it -- but this command would report "done" over a refusal, which is worse than a
        // refusal because an operator would believe it.
        if (!ProgressService.stageGateOpen(server, entry.get().quest(), player.getUUID())) {
            context.getSource().sendFailure(Component.translatable("tenet.command.complete.gated",
                    id, entry.get().quest().requiresStage().map(Object::toString).orElse(""),
                    player.getScoreboardName()));
            return 0;
        }

        ProgressService.complete(server, owner, player, entry.get(), progress);
        pushToTeam(context.getSource(), player);
        context.getSource().sendSuccess(() -> Component.translatable("tenet.command.complete.done", id), false);
        return 1;
    }

    /**
     * Collects a finished quest's rewards.
     *
     * <p>The command half of the Claim button in the quest book. It exists so the whole feature is
     * reachable without a GUI — the plan's Stage 3 exit is a questline playable by command, and a
     * payout that could only be collected by clicking would put a hole in exactly that.
     */
    private static int claim(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String id = StringArgumentType.getString(context, "quest");

        Optional<QuestIndex.QuestEntry> entry = TenetQuests.find(id);
        if (entry.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("tenet.command.quest.notfound", id));
            return 0;
        }

        // One message for every reason a claim can fail -- not finished, no rewards, already collected.
        // Distinguishing them would be three strings a player reads to learn the same thing: there is
        // nothing here for you. `ProgressService.canClaim` is where the distinctions actually live.
        if (!ProgressService.claim(context.getSource().getServer(), player, entry.get())) {
            context.getSource().sendFailure(Component.translatable("tenet.command.claim.nothing", id));
            return 0;
        }

        pushToTeam(context.getSource(), player);
        context.getSource().sendSuccess(() -> Component.translatable("tenet.command.claim.done", id), false);
        return 1;
    }

    /**
     * Holds or releases the speaker's team rewards.
     *
     * <p>Team-scoped because progress is: the flag sits in the same store as the quests it gates, and
     * a player whose rewards are blocked sees it through the same progress push everything else
     * arrives on. Rewards marked {@code ignoreRewardBlocking} keep flowing either way — that is what
     * the field is for — so an operator can hold the bulk of a pack's payouts without breaking the
     * one quest that is supposed to hand something over anyway.
     */
    private static int setRewardsBlocked(CommandContext<CommandSourceStack> context, boolean blocked)
            throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        var server = context.getSource().getServer();
        java.util.UUID owner = ProgressService.progressOwner(server, player);
        dev.ellipog.tenet.progress.ProgressStore store = dev.ellipog.tenet.progress.ProgressStore.of(server);
        store.put(owner, store.progressOf(owner).withRewardsBlocked(blocked));
        pushToTeam(context.getSource(), player);
        context.getSource().sendSuccess(() -> Component.translatable(
                blocked ? "tenet.command.rewards.blocked" : "tenet.command.rewards.unblocked"), true);
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> context, String questId) {
        var server = context.getSource().getServer();
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            // Naming a quest does not help here, and this message used to say it did.
            //
            // The null check fires before `questId` is ever looked at, so "/tenet reset stone_age"
            // from the console hit the same message as "/tenet reset" -- the one escape the message
            // offered was the one case that also failed. A reader would conclude the mod was broken.
            //
            // The real reason is worth saying, because it is not a limitation of the command: progress
            // belongs to a *team*, a team is derived from a player, and the console is in no team. There
            // is no argument that would supply one, so the fix is an honest message rather than a
            // redirect -- and if a console-driven reset is ever wanted, it has to take a player or a
            // team id and is a different command.
            context.getSource().sendFailure(Component.literal(
                    "Run this as a player. Progress belongs to a team, and the console is not in one."));
            return 0;
        }

        java.util.UUID owner = ProgressService.progressOwner(server, player);
        int cleared = ProgressService.reset(server, owner, Optional.ofNullable(questId));

        if (cleared == 0) {
            context.getSource().sendFailure(questId == null
                    ? Component.translatable("tenet.command.reset.nothing")
                    : Component.translatable("tenet.command.quest.notfound", questId));
            return 0;
        }
        pushToTeam(context.getSource(), player);
        context.getSource().sendSuccess(() -> Component.translatable("tenet.command.reset.done", cleared), false);
        return cleared;
    }

    /**
     * Tells everyone who shares this player's progress that it moved.
     *
     * <h2>Why a command has to do this itself</h2>
     *
     * <p>Because the engine's tick cannot do it for a command, and the reason is narrow enough to
     * state exactly: {@code ProgressService.evaluateTeam} skips every quest that is not
     * <i>playable</i>. A quest that is already COMPLETED never reports a change again, however many
     * times it is ticked — so {@code /tenet complete}, {@code reset} and {@code claim} all move
     * something a client is showing and produce no tick that says so. Before this, a client watching
     * an operator finish or clear a quest kept the stale view until something unrelated happened to
     * move.
     *
     * <p>{@code submit} and {@code claim} already reach a client through the payload handlers, which
     * push for themselves — but those handlers are the <i>client-initiated</i> path. The commands are
     * the other one, and the whole point of Stage 3 is that the two are equally real. So this is
     * called from both.
     *
     * <h2>Why the change is reflected rather than avoided</h2>
     *
     * <p>Only after the command has actually changed something: the two refusals above return before
     * this line. That is what keeps "a command was run" from quietly becoming "a message per command".
     */
    private static void pushToTeam(CommandSourceStack source, ServerPlayer player) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            return;
        }
        // The server is passed rather than taken from the player, and it matters here rather than in
        // theory: the harness that plays this questline builds players whose `getServer()` is null,
        // so deriving it from the player would make every push a silent no-op in the one place it is
        // asserted. See TenetNetworking.sendProgressToTeam.
        TenetNetworking.sendProgressToTeam(server, player, ProgressSyncPayload.REASON_CHANGED);
    }

    /**
     * Grants or takes away a stage.
     *
     * <p>Says which happened, and says when nothing happened: "already had it" is a different fact from
     * "granted", and an operator fixing a stuck pack needs to know which one they are looking at. The return
     * value follows the same rule -- 1 when the store changed, 0 when it did not -- so a command block can
     * test it.
     */
    private static int stage(CommandContext<CommandSourceStack> context, boolean grant)
            throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(context, "player");
        // A resource-location argument, not a word: a stage id is `namespace:path`, and Brigadier's
        // word type stops at the colon -- so the one spelling the format documents was untypeable.
        ResourceLocation stage = ResourceLocationArgument.getId(context, "stage");
        MinecraftServer server = context.getSource().getServer();
        boolean changed = grant
                ? StageService.add(server, target.getUUID(), stage)
                : StageService.remove(server, target.getUUID(), stage);
        context.getSource().sendSuccess(() -> Component.translatable(
                grant
                        ? (changed ? "tenet.command.stage.added" : "tenet.command.stage.already")
                        : (changed ? "tenet.command.stage.removed" : "tenet.command.stage.absent"),
                target.getScoreboardName(), stage.toString()), false);
        return changed ? 1 : 0;
    }

    /** Lists a player's stages: their own by default, anybody's for an operator. */
    private static int stageList(CommandContext<CommandSourceStack> context, ServerPlayer named) {
        ServerPlayer target = named != null ? named : context.getSource().getPlayer();
        if (target == null) {
            context.getSource().sendFailure(Component.translatable("tenet.command.stage.needsplayer"));
            return 0;
        }
        Set<ResourceLocation> stages = StageService.list(context.getSource().getServer(), target.getUUID());
        if (stages.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.translatable("tenet.command.stage.none",
                    target.getScoreboardName()), false);
            return 1;
        }
        context.getSource().sendSuccess(() -> Component.translatable("tenet.command.stage.list",
                target.getScoreboardName(), stages.size()), false);
        // One line each, the shape `/tenet types` uses, because a pack can hold twenty and a wrapped line
        // is one nobody can read a name out of.
        for (ResourceLocation stage : stages) {
            context.getSource().sendSuccess(() -> Component.literal("  " + stage), false);
        }
        return 1;
    }

    private static int echo(CommandContext<CommandSourceStack> context) {
        int times = IntegerArgumentType.getInteger(context, "times");
        for (int i = 0; i < times; i++) {
            // Copied to a final local because the lambda below is deferred: sendSuccess takes a
            // Supplier, which may be called after this loop has moved on. Capturing the loop
            // variable is a compile error, and rightly so.
            final int line = i + 1;
            context.getSource().sendSuccess(() -> Component.translatable("tenet.command.echo", line), false);
        }
        return times;
    }

    // ------------------------------------------------------------------

    /** Groups in load order, from the index rather than from a second walk over the files. */
    private static PrerequisiteMode effectiveMode(QuestIndex.QuestEntry entry) {
        // The chapter off the entry, which is the same object that lists this quest -- so the chapter
        // default and the quest it applies to cannot come from two different chapters.
        return entry.quest().prerequisiteMode(entry.chapter().defaultPrerequisiteMode());
    }

    private static String stateColour(QuestState state) {
        return switch (state) {
            case COMPLETED -> "§a";
            case STARTED -> "§e";
            case UNLOCKED -> "§f";
            case LOCKED -> "§8";
        };
    }

    private static String describe(QuestTask task) {
        if (task instanceof dev.ellipog.tenet.quest.task.ItemTask item) {
            return item.item().describe();
        }
        if (task instanceof dev.ellipog.tenet.quest.task.CheckmarkTask checkmark) {
            return checkmark.title().value();
        }
        return "";
    }

    private static String describe(QuestReward reward) {
        if (reward instanceof dev.ellipog.tenet.quest.reward.ItemReward item) {
            return item.item().describe();
        }
        if (reward instanceof dev.ellipog.tenet.quest.reward.XpReward xp) {
            return xp.levels() ? xp.amount() + " levels" : xp.amount() + " xp";
        }
        return "";
    }
}
