package dev.ellipog.tasked.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tasked.quest.reward.RewardAutoClaim;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.Set;

/**
 * A quest's behaviour flags, grouped.
 *
 * <p>Grouped for a mundane reason and a real one. {@code RecordCodecBuilder} takes at most sixteen
 * components, and a flat {@link Quest} with these inline would be over the limit — so they have
 * to go somewhere. They belong together anyway: these are the flags that change how a quest behaves
 * rather than what it contains, and the editor shows them on one panel.
 *
 * <p>In JSON the fields are <b>flat</b> on the quest — {@code "repeatable": true}, not
 * {@code "rules": {"repeatable": true}} — because this is a {@link MapCodec}. An author should not
 * have to nest an object to set a flag.
 *
 * <h2>The two families here, and why the second one arrived</h2>
 *
 * <p>The first six flags are about what a quest <i>is</i>: repeatable, sequential, invisible, named,
 * exclusive. The rest are about what a player may <b>see and when</b> — a family FTB Quests ships and
 * this mod did not, and the reason they are worth having is that a questline with a surprise in it
 * cannot be written without them. A locked quest is normally <i>shown</i> as locked, which is the
 * point of a map; hiding one until its prerequisites are met, or hiding its text until it is done, is
 * how an author writes a reveal. Every one of them is a presentation decision the client can make on
 * its own, because it already knows the dependency states and the task progress — so none of them
 * needed a change to the progression engine.
 */
public record QuestRules(boolean repeatable,
                         int repeatCooldownTicks,
                         boolean sequentialTasks,
                         boolean invisible,
                         boolean showTitle,
                         Optional<String> exclusiveGroup,
                         int maxCompletableDependents,
                         boolean hideUntilDependenciesComplete,
                         boolean hideUntilDependenciesVisible,
                         boolean hideDependencyLines,
                         boolean hideTextUntilComplete,
                         boolean hideDetailsUntilStartable,
                         int invisibleUntilTasks,
                         /**
                          * A stage the player must have for the quest to be open to them.
                          *
                          * <p>The one gate here that is <b>per player</b> rather than per team, because a
                          * stage is: a quest gated on {@code my_pack:chapter_one} is open to a player who has
                          * it and locked to one who does not, even in the same party. FTB Quests' stage
                          * integration is the shape being matched -- it is how a pack writes "this chapter
                          * follows that one" when the thing linking them is not a dependency edge.
                          */
                         Optional<ResourceLocation> requiresStage,
                         /**
                          * Whether this quest's rewards are handed over the moment it completes, with the
                          * chapter's default for a quest that says nothing.
                          *
                          * <p>The middle rung of the ladder: a reward's own {@code auto} wins over this,
                          * this wins over the chapter's {@code autoClaim}, and the chapter's wins over the
                          * pack setting in {@code index.json}. Absent rather than {@code DEFAULT} because
                          * "this quest has no opinion" is a real state an author needs — the alternative
                          * would pin the chapter's default the moment a quest is edited.
                          *
                          * <p>The point of it is the fifty dirt-and-wood quests at the start of a pack: a
                          * chapter turns auto-claim on once, and the players are spared fifty clicks.
                          * Rewards that need a decision — a {@code tasked:choice} table — are never
                          * auto-granted whatever this says; they wait for the pick.
                          */
                         Optional<RewardAutoClaim> autoClaim) {

    public static final QuestRules DEFAULT = new QuestRules(false, 0, false, false, false,
            Optional.empty(), 0, false, false, false, false, false, 0, Optional.empty(), Optional.empty());

    /** The field names this contributes, for the validator to allow at quest level. */
    public static final Set<String> FIELDS = Set.of("repeatable", "repeatCooldownTicks", "sequentialTasks",
            "invisible", "showTitle", "exclusiveGroup", "maxCompletableDependents",
            "hideUntilDependenciesComplete", "hideUntilDependenciesVisible", "hideDependencyLines",
            "hideTextUntilComplete", "hideDetailsUntilStartable", "invisibleUntilTasks", "requiresStage",
            "autoClaim");

    /**
     * The auto-claim mode in force for this quest: its own, or the chapter's default.
     *
     * <p>One accessor rather than an {@code orElse} at each call site, so the server's grant path and
     * the client's toast path cannot resolve the ladder differently.
     */
    public RewardAutoClaim autoClaim(RewardAutoClaim fallback) {
        return autoClaim.orElse(fallback);
    }

    /** The bounds of the two counted flags: a cap of dependents, and a number of tasks. */
    public static final int MIN_COUNT = 0;
    public static final int MAX_COUNT = 64;

    public static final MapCodec<QuestRules> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("repeatable", false).forGetter(QuestRules::repeatable),
            // A cooldown only means anything on a repeatable quest, but it is not an error to set
            // one anyway -- an author may set it before deciding, and a validator that complains
            // about a harmless combination is a validator people learn to ignore.
            Codec.intRange(0, 100_000_000).optionalFieldOf("repeatCooldownTicks", 0)
                    .forGetter(QuestRules::repeatCooldownTicks),
            Codec.BOOL.optionalFieldOf("sequentialTasks", false).forGetter(QuestRules::sequentialTasks),
            Codec.BOOL.optionalFieldOf("invisible", false).forGetter(QuestRules::invisible),
            // Off by default, which is the opposite of the obvious choice and is deliberate.
            //
            // A node is an icon; a canvas of fifty names under fifty nodes is a wall of text with
            // pictures in it, and the titles are the part you can already get by hovering. So the
            // default is the quiet one, the hover caption carries the name for every node either way,
            // and an author who wants the names drawn asks for each one they mean. The same reasoning
            // as the icon scale, one step further.
            Codec.BOOL.optionalFieldOf("showTitle", false).forGetter(QuestRules::showTitle),
            // Quests sharing a group are mutually exclusive: completing one locks the others for
            // good. Scoped to the chapter, so "smithing" in two chapters does not collide.
            Codec.STRING.optionalFieldOf("exclusiveGroup").forGetter(QuestRules::exclusiveGroup),
            // At most this many of the quests that depend on this one may be completed; the rest become
            // unavailable. Zero is no cap, which is the default because a cap is a strong statement
            // about a branch and should be written deliberately.
            //
            // This is FTB Quests' "max completable dependents", and it answers the same question as
            // `exclusiveGroup` from the other end: a named group says "these quests exclude each other"
            // across siblings, and a cap says "at most N of the things I unlock". A tree with a shared
            // parent but no natural group name wants the cap; two quests anywhere in the chapter that
            // must not both be taken want the group.
            Codec.intRange(MIN_COUNT, MAX_COUNT).optionalFieldOf("maxCompletableDependents", 0)
                    .forGetter(QuestRules::maxCompletableDependents),
            // The reveal family. Each is a presentation decision, and each is checked by the client
            // against state it already has -- see `QuestVisibility`.
            Codec.BOOL.optionalFieldOf("hideUntilDependenciesComplete", false)
                    .forGetter(QuestRules::hideUntilDependenciesComplete),
            Codec.BOOL.optionalFieldOf("hideUntilDependenciesVisible", false)
                    .forGetter(QuestRules::hideUntilDependenciesVisible),
            Codec.BOOL.optionalFieldOf("hideDependencyLines", false)
                    .forGetter(QuestRules::hideDependencyLines),
            Codec.BOOL.optionalFieldOf("hideTextUntilComplete", false)
                    .forGetter(QuestRules::hideTextUntilComplete),
            Codec.BOOL.optionalFieldOf("hideDetailsUntilStartable", false)
                    .forGetter(QuestRules::hideDetailsUntilStartable),
            // Only meaningful together with `invisible`, and a harmless no-op without it rather than an
            // error: an author may set the count before deciding how hidden the quest should be, and a
            // validator that complains about that is one people learn to ignore.
            Codec.intRange(MIN_COUNT, MAX_COUNT).optionalFieldOf("invisibleUntilTasks", 0)
                    .forGetter(QuestRules::invisibleUntilTasks),
            // The stage gate: an id, like every other name in this format, and nothing checks that the
            // stage exists -- one exists by being granted, so a validator would be guessing about a grant a
            // script may make tomorrow.
            ResourceLocation.CODEC.optionalFieldOf("requiresStage").forGetter(QuestRules::requiresStage),
            // The middle rung of the auto-claim ladder; see the component's javadoc. Absent means the
            // chapter decides.
            RewardAutoClaim.CODEC.optionalFieldOf("autoClaim").forGetter(QuestRules::autoClaim)
    ).apply(instance, QuestRules::new));

    public static final Codec<QuestRules> CODEC = MAP_CODEC.codec();
}
