package dev.ellipog.tasked.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Optional;
import java.util.Set;

/**
 * A quest's behaviour flags, grouped.
 *
 * <p>Grouped for a mundane reason and a real one. {@code RecordCodecBuilder} takes at most sixteen
 * components, and a flat {@link Quest} with these five inline would be over the limit — so they have
 * to go somewhere. They belong together anyway: these are the flags that change how a quest behaves
 * rather than what it contains, and the editor shows them on one panel.
 *
 * <p>In JSON the fields are <b>flat</b> on the quest — {@code "repeatable": true}, not
 * {@code "rules": {"repeatable": true}} — because this is a {@link MapCodec}. An author should not
 * have to nest an object to set a flag.
 */
public record QuestRules(boolean repeatable,
                         int repeatCooldownTicks,
                         boolean sequentialTasks,
                         boolean invisible,
                         Optional<String> exclusiveGroup) {

    public static final QuestRules DEFAULT =
            new QuestRules(false, 0, false, false, Optional.empty());

    /** The field names this contributes, for the validator to allow at quest level. */
    public static final Set<String> FIELDS =
            Set.of("repeatable", "repeatCooldownTicks", "sequentialTasks", "invisible", "exclusiveGroup");

    public static final MapCodec<QuestRules> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("repeatable", false).forGetter(QuestRules::repeatable),
            // A cooldown only means anything on a repeatable quest, but it is not an error to set
            // one anyway -- an author may set it before deciding, and a validator that complains
            // about a harmless combination is a validator people learn to ignore.
            Codec.intRange(0, 100_000_000).optionalFieldOf("repeatCooldownTicks", 0)
                    .forGetter(QuestRules::repeatCooldownTicks),
            Codec.BOOL.optionalFieldOf("sequentialTasks", false).forGetter(QuestRules::sequentialTasks),
            Codec.BOOL.optionalFieldOf("invisible", false).forGetter(QuestRules::invisible),
            // Quests sharing a group are mutually exclusive: completing one locks the others for
            // good. Scoped to the chapter, so "smithing" in two chapters does not collide.
            Codec.STRING.optionalFieldOf("exclusiveGroup").forGetter(QuestRules::exclusiveGroup)
    ).apply(instance, QuestRules::new));

    public static final Codec<QuestRules> CODEC = MAP_CODEC.codec();
}
