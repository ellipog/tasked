package dev.ellipog.tasked;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import dev.ellipog.armature.api.ArmatureApi;
import dev.ellipog.tasked.progress.ProgressService;
import dev.ellipog.tasked.progress.ProgressionEngine;
import dev.ellipog.tasked.progress.QuestState;
import dev.ellipog.tasked.quest.Chapter;
import dev.ellipog.tasked.quest.ChapterGroup;
import dev.ellipog.tasked.quest.PrerequisiteMode;
import dev.ellipog.tasked.quest.Quest;
import dev.ellipog.tasked.quest.QuestIndex;
import dev.ellipog.tasked.quest.QuestLoader;
import dev.ellipog.tasked.quest.QuestReward;
import dev.ellipog.tasked.quest.QuestTask;
import dev.ellipog.tasked.quest.TaskedQuests;
import dev.ellipog.tasked.quest.reward.RewardTypes;
import dev.ellipog.tasked.quest.task.TaskTypes;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

/**
 * {@code /tasked} — the command tree.
 *
 * <h2>Why the questline is playable by command before the screen exists</h2>
 *
 * <p>Stage 3's "done when" is a whole questline completable through commands, with no GUI. That is
 * not a shortcut — it is the order the plan deliberately chose. A quest engine exercised through a
 * UI has two things that could be wrong at once, and when a quest does not complete you cannot tell
 * whether that is the engine, the sync, or the button. By command, there is one candidate.
 *
 * <p>So {@code /tasked progress} prints what is available and how far along each task is,
 * {@code /tasked submit} does what a click will do later, and {@code /tasked complete} forces a
 * quest through for testing a chain. The GUI in Stage 6 calls the same {@link ProgressService}
 * methods these do.
 *
 * <p>Registered from a listener on
 * {@link dev.ellipog.armature.api.event.ArmatureEvents#COMMANDS_REGISTER} rather than once at
 * construction, because that event fires again on every datapack reload and the dispatcher is
 * rebuilt each time. Registering once would leave the commands missing after a {@code /reload}.
 */
public final class TaskedCommand {

    private TaskedCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(Tasked.MOD_ID)
                .then(Commands.literal("version")
                        .executes(TaskedCommand::version))

                .then(Commands.literal("reload")
                        .requires(source -> source.hasPermission(2))
                        .executes(TaskedCommand::reload))

                .then(Commands.literal("quests")
                        .executes(TaskedCommand::quests))

                .then(Commands.literal("quest")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(TaskedCommand::quest)))

                // --- the engine, by command ---

                .then(Commands.literal("progress")
                        .executes(TaskedCommand::progress))

                .then(Commands.literal("submit")
                        .then(Commands.argument("quest", StringArgumentType.word())
                                .executes(ctx -> submit(ctx, 0))
                                .then(Commands.argument("task", IntegerArgumentType.integer(0))
                                        .executes(ctx -> submit(ctx, IntegerArgumentType.getInteger(ctx, "task"))))))

                .then(Commands.literal("complete")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("quest", StringArgumentType.word())
                                .executes(TaskedCommand::complete)))

                .then(Commands.literal("reset")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> reset(ctx, null))
                        .then(Commands.argument("quest", StringArgumentType.word())
                                .executes(ctx -> reset(ctx, StringArgumentType.getString(ctx, "quest")))))

                .then(Commands.literal("types")
                        .executes(TaskedCommand::types))

                .then(Commands.literal("echo")
                        // Not useful. Kept because it is the cheapest proof that Brigadier arguments
                        // and a return value survive the trip through Armature's command event.
                        .then(Commands.argument("times", IntegerArgumentType.integer(1, 5))
                                .executes(TaskedCommand::echo))));
    }

    // ------------------------------------------------------------------
    // Information
    // ------------------------------------------------------------------

    private static int version(CommandContext<CommandSourceStack> context) {
        String version = ArmatureApi.platform().modVersion(Tasked.MOD_ID).orElse("unknown");
        String armature = ArmatureApi.platform().modVersion("armature").orElse("absent");

        context.getSource().sendSuccess(() -> Component.translatable(
                "tasked.command.version", version, armature), false);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        QuestLoader.Result result = TaskedQuests.reload();
        var problems = result.problems();

        context.getSource().sendSuccess(() -> Component.translatable("tasked.command.reload.summary",
                result.filesDecoded(), result.filesFound(), problems.errorCount(), problems.warningCount()), false);
        // filesWithErrors, not filesDecoded: a file can decode and still have a circular dependency
        // in it, and reporting "everything loaded" for that is a lie the author would act on.

        if (result.filesWithErrors() > 0) {
            context.getSource().sendFailure(Component.translatable("tasked.command.reload.failed",
                    result.filesWithErrors()));
        }
        if (!problems.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.translatable("tasked.command.reload.log"), false);
        }
        return result.filesDecoded();
    }

    private static int quests(CommandContext<CommandSourceStack> context) {
        QuestIndex index = TaskedQuests.index();

        if (index.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.translatable("tasked.command.quests.empty"), false);
            return 0;
        }

        context.getSource().sendSuccess(() -> Component.translatable("tasked.command.quests.summary",
                index.questCount(), index.chapterCount(), index.files().size()), false);

        for (ChapterGroup group : allGroups(index)) {
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

        return TaskedQuests.find(id).map(entry -> {
            Quest quest = entry.quest();

            context.getSource().sendSuccess(() -> Component.translatable("tasked.command.quest.header",
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
                context.getSource().sendSuccess(() -> Component.literal("  §7no dependencies"), false);
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
            context.getSource().sendFailure(Component.translatable("tasked.command.quest.notfound", id));
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
        QuestIndex index = TaskedQuests.index();

        if (index.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.translatable("tasked.command.quests.empty"), false);
            return 0;
        }

        ProgressionEngine.Resolution resolution =
                ProgressService.resolutionFor(context.getSource().getServer(),
                        ProgressService.progressOwner(context.getSource().getServer(), player));

        context.getSource().sendSuccess(() -> Component.translatable("tasked.command.progress.header",
                resolution.unlockedCount(), index.questCount(), resolution.completedCount()), false);

        int shown = 0;
        for (QuestIndex.QuestEntry entry : index.quests()) {
            Quest quest = entry.quest();
            QuestState state = resolution.stateOf(quest);

            // Completed and non-repeatable is not interesting to list; locked is not actionable.
            // A repeatable quest that is done still is, because it can be done again.
            boolean worthShowing = state.isPlayable()
                    || (state == QuestState.COMPLETED && quest.repeatable());
            if (!worthShowing || (quest.invisible() && state != QuestState.COMPLETED)) {
                continue;
            }
            shown++;

            final QuestState shownState = state;
            long cooldown = resolution.cooldownOf(quest);
            context.getSource().sendSuccess(() -> Component.literal(
                    "  " + stateColour(shownState) + shownState.label() + "§r "
                            + quest.title().value() + "  §8[" + quest.id() + "]"
                            + (cooldown > 0 ? "  §7(ready in " + (cooldown / 20) + "s)" : "")), false);

            var stored = ProgressService.progressFor(context.getSource().getServer(),
                    ProgressService.progressOwner(context.getSource().getServer(), player)).progressOf(quest);

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
            context.getSource().sendSuccess(() -> Component.translatable("tasked.command.progress.none"), false);
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

        return TaskTypes.count() + RewardTypes.count();
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

        Optional<QuestIndex.QuestEntry> entry = TaskedQuests.find(id);
        if (entry.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("tasked.command.quest.notfound", id));
            return 0;
        }

        boolean changed = ProgressService.submit(context.getSource().getServer(), player, entry.get(), taskIndex);
        if (!changed) {
            context.getSource().sendFailure(Component.translatable("tasked.command.submit.refused", id, taskIndex));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.translatable("tasked.command.submit.done", id, taskIndex), false);
        return 1;
    }

    /**
     * Forces a quest complete, granting its rewards.
     *
     * <p>Op level 2, and deliberately not routed through task evaluation: its whole purpose is to
     * skip the requirements, which is what makes a long chain testable without gathering forty
     * stacks of cobblestone. It goes through the same {@link ProgressService#complete} the engine
     * uses, so the rewards, the saved state and the "already claimed" guard are all exercised.
     */
    private static int complete(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String id = StringArgumentType.getString(context, "quest");

        Optional<QuestIndex.QuestEntry> entry = TaskedQuests.find(id);
        if (entry.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("tasked.command.quest.notfound", id));
            return 0;
        }

        var server = context.getSource().getServer();
        java.util.UUID owner = ProgressService.progressOwner(server, player);
        var progress = ProgressService.progressFor(server, owner);

        // Refuse a quest that is not playable, so this cannot be used to skip a locked chain. An op
        // who wants that can complete the prerequisite first, which is a more honest test anyway.
        QuestState state = ProgressionEngine.resolve(TaskedQuests.index(), progress,
                server.overworld().getGameTime()).stateOf(entry.get().quest());
        if (!state.isPlayable()) {
            context.getSource().sendFailure(Component.translatable("tasked.command.complete.locked",
                    id, state.label()));
            return 0;
        }

        ProgressService.complete(server, owner, player, entry.get(), progress);
        context.getSource().sendSuccess(() -> Component.translatable("tasked.command.complete.done", id), false);
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> context, String questId) {
        var server = context.getSource().getServer();
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("Run this as a player, or name a quest."));
            return 0;
        }

        java.util.UUID owner = ProgressService.progressOwner(server, player);
        int cleared = ProgressService.reset(server, owner, Optional.ofNullable(questId));

        if (cleared == 0) {
            context.getSource().sendFailure(questId == null
                    ? Component.translatable("tasked.command.reset.nothing")
                    : Component.translatable("tasked.command.quest.notfound", questId));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.translatable("tasked.command.reset.done", cleared), false);
        return cleared;
    }

    private static int echo(CommandContext<CommandSourceStack> context) {
        int times = IntegerArgumentType.getInteger(context, "times");
        for (int i = 0; i < times; i++) {
            // Copied to a final local because the lambda below is deferred: sendSuccess takes a
            // Supplier, which may be called after this loop has moved on. Capturing the loop
            // variable is a compile error, and rightly so.
            final int line = i + 1;
            context.getSource().sendSuccess(() -> Component.translatable("tasked.command.echo", line), false);
        }
        return times;
    }

    // ------------------------------------------------------------------

    /** Groups in load order, from the files rather than the lookup table — which holds one entry per alias. */
    private static List<ChapterGroup> allGroups(QuestIndex index) {
        return index.files().stream()
                .flatMap(file -> file.file().chapterGroups().stream())
                .toList();
    }

    private static PrerequisiteMode effectiveMode(QuestIndex.QuestEntry entry) {
        return TaskedQuests.index().chapter(entry.chapterId())
                .map(chapter -> entry.quest().prerequisiteMode(chapter.chapter().defaultPrerequisiteMode()))
                .orElse(PrerequisiteMode.ALL_COMPLETED);
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
        if (task instanceof dev.ellipog.tasked.quest.task.ItemTask item) {
            return item.item().describe();
        }
        if (task instanceof dev.ellipog.tasked.quest.task.CheckmarkTask checkmark) {
            return checkmark.title().value();
        }
        return "";
    }

    private static String describe(QuestReward reward) {
        if (reward instanceof dev.ellipog.tasked.quest.reward.ItemReward item) {
            return item.item().describe();
        }
        if (reward instanceof dev.ellipog.tasked.quest.reward.XpReward xp) {
            return xp.levels() ? xp.amount() + " levels" : xp.amount() + " xp";
        }
        return "";
    }
}
