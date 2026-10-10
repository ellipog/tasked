package dev.ellipog.tenet;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import dev.ellipog.tenet.quest.TenetQuests;
import dev.ellipog.tenet.quest.loot.RewardTable;
import dev.ellipog.tenet.quest.loot.TableExport;
import dev.ellipog.tenet.quest.loot.TableImport;
import dev.ellipog.tenet.quest.loot.TableRoller;
import dev.ellipog.tenet.quest.reward.TableReward;
import dev.ellipog.tenet.editor.TableAddress;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code /tenet table ...}: the reward tables, from chat.
 *
 * <h2>Why a command as well as an editor</h2>
 *
 * <p>Because filling a container from a table needs the <b>crosshair</b>, and a screen captures the
 * mouse: it is an aimed action, and while the book is open there is nothing to aim. The same is true of
 * reading a container while you are walking around — though the panel covers that one too, through its
 * own `Import… › From target chest` row, which is why this note no longer claims both directions are
 * commands. So the commands live here for the aimed half, and the editor's buttons cover what works with
 * a panel open: its own item picker, reading the player's inventory, and reading the container you are
 * looking at.
 *
 * <h2>The grammar is literal branches, not a row of optionals</h2>
 *
 * <p>{@code export <id> [here | nested [here | container]]} as nested literals rather than two optional
 * arguments in sequence: Brigadier tries the first optional against {@code here} and fails with
 * "unknown argument" instead of doing what was asked. Two branches that each read as a sentence is the
 * version an author can guess.
 *
 * <p>Every subcommand takes the edit permission, and the container ones require a player: a console has
 * no crosshair, no inventory and no feet to drop things at, and "it worked in single player" is exactly
 * the kind of thing a command must not be.
 */
public final class TenetTableCommand {

    private TenetTableCommand() {
    }

    /** The {@code table} node, hung off {@code /tenet}. */
    public static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("table")
                .then(Commands.literal("list")
                        .requires(QuestAuthority.mayEdit())
                        .executes(TenetTableCommand::list))
                .then(Commands.literal("roll")
                        .requires(QuestAuthority.mayEdit())
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> roll(ctx, 1, TableReward.Mode.RANDOM))
                                .then(Commands.argument("rolls",
                                                IntegerArgumentType.integer(1, TableRoller.MAX_ROLLS))
                                        .executes(ctx -> roll(ctx,
                                                IntegerArgumentType.getInteger(ctx, "rolls"),
                                                TableReward.Mode.RANDOM))
                                        // The reading, and only reachable *after* the count: a mode
                                        // literal at the id's own level would collide with any table
                                        // named after a mode, and the shipped example is called `loot`
                                        // — so `/tenet table roll loot` has to keep meaning the table
                                        // and not the mode. See this class's note on literal branches.
                                        .then(modeBranch(TableReward.Mode.RANDOM))
                                        .then(modeBranch(TableReward.Mode.LOOT))
                                        .then(modeBranch(TableReward.Mode.ALL_TABLE))
                                        .then(modeBranch(TableReward.Mode.CHOICE)))))
                .then(Commands.literal("import")
                        .requires(QuestAuthority.mayEdit())
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(TenetTableCommand::importFromChest)))
                .then(Commands.literal("export")
                        .requires(QuestAuthority.mayEdit())
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> export(ctx, false, false))
                                .then(Commands.literal("here")
                                        .executes(ctx -> export(ctx, false, true)))
                                .then(Commands.literal("nested")
                                        .executes(ctx -> export(ctx, true, false))
                                        .then(Commands.literal("here")
                                                .executes(ctx -> export(ctx, true, true)))
                                        .then(Commands.literal("container")
                                                .executes(ctx -> export(ctx, true, false))))))
                .then(Commands.literal("edit")
                        .requires(QuestAuthority.mayEdit())
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(TenetTableCommand::edit)));
    }

    /**
     * One reading of {@code roll <id> [rolls] <mode>}.
     *
     * <p>A literal per mode rather than one word argument, for the reason this class's own note gives:
     * an argument that could be either a count or a reading is a branch Brigadier resolves by failing
     * with "unknown argument". The reading is spelled exactly as the file and the editor's chip spell
     * it — {@code Mode.wire()} — so the command and the panel cannot name the same reading two ways.
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> modeBranch(
            TableReward.Mode mode) {
        return Commands.literal(mode.wire())
                .executes(ctx -> roll(ctx, IntegerArgumentType.getInteger(ctx, "rolls"), mode));
    }

    /** The tables the loader has, with what each one is. */
    private static int list(CommandContext<CommandSourceStack> ctx) {
        Map<String, RewardTable> tables = TenetQuests.rewardTables();
        if (tables.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "no reward tables are loaded - they live in "
                            + dev.ellipog.tenet.quest.QuestFiles.REWARD_TABLES_DIRECTORY + "/"), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(tables.size() + " reward table(s):")
                .withStyle(ChatFormatting.WHITE), false);
        for (Map.Entry<String, RewardTable> entry : new java.util.TreeMap<>(tables).entrySet()) {
            RewardTable table = entry.getValue();
            String line = "  " + entry.getKey() + " - " + table.displayTitle(entry.getKey())
                    + " (" + table.entryCount() + " entries, " + table.lootSize() + " roll"
                    + (table.lootSize() == 1 ? "" : "s")
                    + (table.emptyWeight() > 0 ? ", empty " + trim(table.emptyWeight()) : "") + ")";
            ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        }
        return tables.size();
    }

    /**
     * Rolls a table without granting anything, and prints what came up.
     *
     * <p>The same arithmetic a grant uses ({@code TableRoller} rolls through the table's own
     * {@code rollIndices}), so a report here and a report in the editor are the same numbers — and the
     * count is clamped, so a mistyped number cannot spin the server.
     *
     * <p><b>The reading is an argument now.</b> It was hardcoded to {@code random}, so
     * {@code /tenet table roll loot} rolled <i>without</i> the empty band that the same file's
     * {@code tenet:loot} reward includes: the command's report contradicted the grant it exists to
     * explain. The default is still {@code random}, which is what the argument used to mean.
     */
    private static int roll(CommandContext<CommandSourceStack> ctx, int rolls, TableReward.Mode mode) {
        String id = StringArgumentType.getString(ctx, "id");
        RewardTable table = TenetQuests.rewardTables().get(id);
        if (table == null) {
            ctx.getSource().sendFailure(Component.literal("no reward table named \"" + id + "\""));
            return 0;
        }
        TableRoller.Report report = TableRoller.roll(table, mode, rolls,
                ctx.getSource().getLevel().getRandom(),
                other -> TenetQuests.rewardTables().get(other));
        ctx.getSource().sendSuccess(() -> Component.literal(
                report.rolls() + " roll(s) of " + id + " as " + report.mode().wire()
                        + (report.truncated() ? " (nesting cut off)" : ""))
                .withStyle(ChatFormatting.WHITE), false);
        for (TableRoller.Tally tally : report.entries()) {
            printTally(ctx, tally, "  ", mode);
        }
        if (report.emptyHits() > 0) {
            ctx.getSource().sendSuccess(() -> Component.literal("  nothing: " + report.emptyHits() + "x")
                    .withStyle(ChatFormatting.DARK_GRAY), false);
        }
        for (String note : report.notRolled()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  " + note)
                    .withStyle(ChatFormatting.RED), false);
        }
        return report.rolls();
    }

    /**
     * One tally, and its children under it.
     *
     * <p>The name comes from the tally's own display — the same fields a reward row draws — so a report
     * in chat and a report in the editor name an entry the same way, and a nested table's line is
     * followed by its entries rather than replaced by them.
     *
     * <p>A ceiling mode prints no count. {@code all_table} grants every entry once whatever the number,
     * and {@code choice} throws nothing at all — "3x" beside a guarantee is a number about nothing, and
     * the same rule the editor's pane follows.
     */
    private static void printTally(CommandContext<CommandSourceStack> ctx, TableRoller.Tally tally,
                                   String indent, TableReward.Mode mode) {
        var display = tally.display();
        String name = display.label() == null || display.label().isBlank()
                ? display.labelFallback() : display.label();
        boolean diced = mode == TableReward.Mode.RANDOM || mode == TableReward.Mode.LOOT;
        String line = indent + "#" + (tally.index() + 1) + " " + (name == null || name.isBlank()
                ? "(entry)" : name)
                + (diced ? ": " + tally.hits() + "x" : "")
                + (tally.emptyHits() > 0 ? " (paid nothing " + tally.emptyHits() + "x)" : "");
        ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        for (TableRoller.Tally child : tally.nested()) {
            printTally(ctx, child, indent + "  ", mode);
        }
    }

    /** The chest the player is looking at, into the table. */
    private static int importFromChest(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String id = StringArgumentType.getString(ctx, "id");
        if (!TenetQuests.tables().exists(id)) {
            ctx.getSource().sendFailure(Component.literal("no reward table named \"" + id + "\""));
            return 0;
        }
        Optional<Container> container = TableImport.containerLookingAt(player);
        if (container.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(TableImport.lookingAtBlock(player)
                    ? "Targeted block is not a container"
                    : "Look at a container to import"));
            return 0;
        }
        TableImport.Imported imported = TableImport.fromContainer(container.get());
        dev.ellipog.tenet.editor.EditorOps.Applied applied = TenetQuests.tables()
                .importInto(TableAddress.of(id), imported.entries(), ctx.getSource().getServer()
                        .registryAccess().createSerializationContext(
                                com.mojang.serialization.JsonOps.INSTANCE));
        if (!applied.ok()) {
            for (String message : applied.messages()) {
                ctx.getSource().sendFailure(Component.literal(message));
            }
            return 0;
        }
        // The same refresh the in-game editor asks for: without it the clients keep the tree they were
        // sent at join, so a command import would look like it did nothing until someone reconnected.
        dev.ellipog.tenet.quest.TreeRefresh.requestTables();
        String line = TableImport.describe(imported, id);
        ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.WHITE), true);
        return imported.entries().size();
    }

    /**
     * The table's items, into a chest or the player's own hands.
     *
     * <p>Never destructive: empty slots only, then the player's inventory, then the ground — and the
     * counts are reported, so an author who exported more than the chest could hold knows where the rest
     * went rather than finding it later. {@code here} skips the chest entirely, which is the way out of
     * a room where every angle within reach hits a wall.
     */
    private static int export(CommandContext<CommandSourceStack> ctx, boolean nested, boolean here)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String id = StringArgumentType.getString(ctx, "id");
        RewardTable table = TenetQuests.rewardTables().get(id);
        if (table == null) {
            ctx.getSource().sendFailure(Component.literal("no reward table named \"" + id + "\""));
            return 0;
        }
        TableExport.Export export = TableExport.stacks(table, nested,
                other -> TenetQuests.rewardTables().get(other));
        if (export.stacks().isEmpty()) {
            // The skipped count is named here too: an author whose table is all experience and
            // commands gets "nothing can go in a chest, 4 entries are not items" rather than a
            // refusal that leaves them wondering which entry was the problem.
            ctx.getSource().sendFailure(Component.literal(
                    "nothing in that table can go in a chest: "
                            + (export.skipped() > 0
                                    ? export.skipped() + " entr" + (export.skipped() == 1 ? "y is" : "ies are")
                                            + " not items"
                                    : "it has no entries")));
            return 0;
        }
        Container container = null;
        if (!here) {
            Optional<Container> looked = TableImport.containerLookingAt(player);
            if (looked.isEmpty()) {
                ctx.getSource().sendFailure(Component.literal(TableImport.lookingAtBlock(player)
                        ? "Targeted block is not a container - use \"here\" to put them in your inventory"
                        : "Look at a container to export into - use \"here\" for your inventory"));
                return 0;
            }
            container = looked.get();
        }
        TableExport.Result result = TableExport.give(export, container, player);
        String line = "exported " + result.given() + " stack(s) from " + id
                + (result.toContainer() > 0 ? ", " + result.toContainer() + " into the container" : "")
                + (result.toInventory() > 0 ? ", " + result.toInventory() + " into your inventory" : "")
                + (result.dropped() > 0 ? ", " + result.dropped() + " dropped here" : "")
                + (result.skipped() > 0 ? "; skipped " + result.skipped() + " non-item entr"
                        + (result.skipped() == 1 ? "y" : "ies") : "")
                // Named, because these entries are in the chest at their *minimum*: an author reading
                // the chest as the table's own contents would take one iron for a roll that pays up to
                // sixty-four.
                + (result.randomised() > 0 ? "; " + result.randomised() + " entr"
                        + (result.randomised() == 1 ? "y rolls" : "ies roll")
                        + " a bonus on top, so the chest holds the minimum" : "")
                + (nested ? " (a flattened view - do not import this chest back into the same table)" : "");
        ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.WHITE), true);
        return result.given();
    }

    /** Opens the table's editor on the player's client, which is the one thing a command cannot do itself. */
    private static int edit(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String id = StringArgumentType.getString(ctx, "id");
        if (!TenetQuests.tables().exists(id)) {
            // Refused before anything is sent: a modal that can only show a refusal is worse than a
            // sentence in chat, and a typo is the commonest way to reach one.
            ctx.getSource().sendFailure(Component.literal("Unknown reward table: " + id));
            return 0;
        }
        dev.ellipog.armature.api.net.ArmatureNetwork.sendToPlayer(player,
                new dev.ellipog.tenet.net.TableOpenPayload(id));
        ctx.getSource().sendSuccess(() -> Component.literal("opening the editor for " + id), false);
        return 1;
    }

    /** A number without a trailing {@code .0}, for a chat line. */
    private static String trim(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
