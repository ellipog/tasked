package dev.ellipog.tasked.quest.condition;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.armature.api.teams.Teams;
import dev.ellipog.tasked.Tasked;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.function.Function;

/**
 * Be in a party of at least this many, right now.
 *
 * <pre>{@code { "type": "tasked:party_size", "min": 2 } }</pre>
 *
 * <p><b>Online members, not the roster.</b> The engine only ever counts the members who are present —
 * a shared task is evaluated against the players actually here — and a togetherness gate satisfied by
 * somebody who is offline would be a gate nobody could see through. A solo player is a party of one,
 * so {@code min: 2} is the "bring a friend" gate.
 *
 * <p>The count is of {@code teamOf}, which never returns nothing: a player with no team is a party of
 * themselves, which is the same answer the engine's own evaluation uses.
 */
public record PartySizeCondition(int min) implements QuestCondition {

    public static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath(Tasked.MOD_ID, "party_size");

    public static final Set<String> FIELDS = Set.of("min");

    public static final MapCodec<PartySizeCondition> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.intRange(1, 1000).fieldOf("min").forGetter(PartySizeCondition::min)
    ).apply(instance, PartySizeCondition::new));

    @Override
    public ResourceLocation type() {
        return TYPE;
    }

    public static final ConditionBehaviour<PartySizeCondition> BEHAVIOUR = (condition, context) -> {
        var team = Teams.teamOf(context.server(), context.player().getUUID());
        int present = 0;
        for (ServerPlayer online : context.server().getPlayerList().getPlayers()) {
            if (team.isMember(online.getUUID())) {
                present++;
            }
        }
        return present >= condition.min();
    };

    public static final Function<PartySizeCondition, ConditionDisplay> DISPLAY = condition -> {
        String subject = String.valueOf(condition.min());
        return ConditionDisplay.ofTranslatableText("tasked.condition.party_size",
                "Be in a party of " + subject + " or more", subject);
    };
}
