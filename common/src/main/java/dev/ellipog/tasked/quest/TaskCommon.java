package dev.ellipog.tasked.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Set;

/**
 * The settings every task has, whatever its type.
 *
 * <p>A separate record so these two fields are declared once. It is a {@link MapCodec} so that each
 * task type embeds it <b>flat</b> — a task says {@code "optional": true} at its own level, not
 * {@code "common": {"optional": true}}. Each type's codec merges this in and adds its own fields,
 * which keeps a type's own codec down to the fields that make it that type.
 *
 * <p>{@code autoSubmitTicks} is borrowed from FTB Quests, which tries a task on a per-type schedule
 * rather than every tick — a short interval for a checkmark, a long one for an observation. One
 * field, and the alternative is a mod that scans every player's inventory twenty times a second for
 * nothing.
 */
public record TaskCommon(boolean optional, int autoSubmitTicks) {

    public static final TaskCommon DEFAULT = new TaskCommon(false, 20);

    /** The field names this contributes, for the validator to allow at task level. */
    public static final Set<String> FIELDS = Set.of("optional", "autoSubmitTicks");

    public static final MapCodec<TaskCommon> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("optional", false).forGetter(TaskCommon::optional),
            Codec.intRange(1, 72000).optionalFieldOf("autoSubmitTicks", 20).forGetter(TaskCommon::autoSubmitTicks)
    ).apply(instance, TaskCommon::new));
}
