package dev.ellipog.tenet.quest.reward;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.quest.QuestReward;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;

import java.util.Set;
import java.util.function.Function;

/**
 * Run a command as the player, with FTB Quests' placeholders.
 *
 * <pre>{@code { "type": "tenet:command", "command": "say {p} finished {quest}", "permissionLevel": 2 } }</pre>
 *
 * <p>The escape hatch every pack reaches for, and the one reward whose reach is the whole server — so
 * it runs through the server's own dispatcher with the player as the source, at the permission level
 * the reward names. FTBQ's placeholders are kept whole, because a pack moved from it should not have
 * to learn a second vocabulary for the same sentence.
 */
public record CommandReward(RewardCommon common, String command, int permissionLevel, boolean silent)
        implements QuestReward {

    public static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath(Tenet.MOD_ID, "command");

    public static final Set<String> FIELDS = Set.of("command", "permissionLevel", "silent");

    public static final MapCodec<CommandReward> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            RewardCommon.MAP_CODEC.forGetter(CommandReward::common),
            Codec.STRING.fieldOf("command").forGetter(CommandReward::command),
            // FTBQ's legacy field migrated to 2; two is also its default, and it is enough for the
            // commands packs actually write ("say", "give", "summon") without opening /op.
            Codec.intRange(0, 4).optionalFieldOf("permissionLevel", 2).forGetter(CommandReward::permissionLevel),
            Codec.BOOL.optionalFieldOf("silent", false).forGetter(CommandReward::silent)
    ).apply(instance, CommandReward::new));

    @Override
    public ResourceLocation type() {
        return TYPE;
    }

    public static final RewardBehaviour<CommandReward> BEHAVIOUR = (reward, context) -> {
        CommandSourceStack source = context.player().createCommandSourceStack();
        if (reward.permissionLevel() > 0) {
            source = source.withPermission(reward.permissionLevel());
        }
        if (reward.silent()) {
            source = source.withSuppressedOutput();
        }
        context.server().getCommands().performPrefixedCommand(source, substitute(reward.command(), context));
    };

    /**
     * The command with every placeholder filled in.
     *
     * <p>An unknown brace-word is left as written rather than blanked: a command that says
     * {@code {player}} to an operator reading the log is a one-second fix, and a command that silently
     * loses the word is a mystery.
     */
    static String substitute(String command, RewardContext context) {
        var pos = context.player().blockPosition();
        return command
                .replace("{p}", context.player().getScoreboardName())
                .replace("{x}", String.valueOf(pos.getX()))
                .replace("{y}", String.valueOf(pos.getY()))
                .replace("{z}", String.valueOf(pos.getZ()))
                .replace("{chapter}", context.chapterId())
                .replace("{quest}", context.questId())
                .replace("{team}", String.valueOf(context.owner()))
                .replace("{team_id}", String.valueOf(context.owner()))
                .replace("{long_team_id}", String.valueOf(context.owner()))
                .replace("{member_count}", String.valueOf(context.members().size()))
                .replace("{online_member_count}", String.valueOf(context.members().size()));
    }

    public static final Function<CommandReward, RewardDisplay> DISPLAY = reward ->
            RewardDisplay.ofTranslatableText("tenet.reward.command", "Run " + reward.command(),
                    reward.command(), 1);
}
