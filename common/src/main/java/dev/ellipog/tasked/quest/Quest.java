package dev.ellipog.tasked.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.ellipog.tasked.quest.reward.RewardTypes;
import dev.ellipog.tasked.quest.task.TaskTypes;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One quest.
 *
 * <p>In JSON:
 *
 * <pre>{@code
 * {
 *   "id": "punch_a_tree",
 *   "title": "Punch a Tree",
 *   "description": ["Or don't. It's your world."],
 *   "icon": { "item": "minecraft:oak_log", "count": 1 },
 *   "x": 0, "y": 0,
 *   "tasks":  [ { "type": "tasked:item", "item": "minecraft:oak_log", "count": 8 } ],
 *   "rewards": [ { "type": "tasked:item", "item": "minecraft:wooden_axe", "count": 1 } ]
 * }
 * }</pre>
 *
 * <h2>Three things here that FTB Quests does not have</h2>
 *
 * <p><b>{@code aliases}.</b> Progress is stored against a quest's id. Change the id — which every
 * author does, once, after realising {@code stoneage_quest_2} was a bad name — and every player
 * loses that quest. An alias list means the old id still resolves, so a rename costs nothing.
 *
 * <p><b>{@code minRequired}.</b> The four {@link PrerequisiteMode} values cover the shapes anyone
 * actually wants, except one: "any three of these five". A count covers it, and setting it makes the
 * mode irrelevant.
 *
 * <p><b>{@code exclusiveGroup}.</b> FTB Quests has this too, so it is not a novelty — but it is
 * grouped into {@link QuestRules} here rather than being another top-level field competing for
 * attention with the quest's actual content.
 *
 * <p>Note {@code description} is a <i>list</i> of paragraphs. A single string with {@code \n} in it
 * is what FTB Quests takes, and it is the reason its quest descriptions end up as one
 * undifferentiated wall of text.
 *
 * <h2>Where the fields live</h2>
 *
 * <p>The flags are inside {@link QuestRules} because {@code RecordCodecBuilder} caps out at sixteen
 * components and this record has thirteen plus those five. The accessors below delegate, so callers
 * write {@code quest.repeatable()} and never see the grouping.
 */
public record Quest(
        String id,
        QuestText title,
        Optional<QuestText> subtitle,
        List<QuestText> description,
        ItemRef icon,
        QuestLayout layout,
        List<String> aliases,
        List<QuestRef> dependencies,
        /**
         * Per-line overrides, keyed by the dependency they style.
         *
         * <p>Beside {@code dependsOn} rather than inside it, and that is a format decision rather than a
         * storage one: a dependency is a name, and has been since the first file — turning the entries
         * into objects to carry a style would break every reader of every pack for a field most edges
         * never set. A map keyed by the dependency id leaves the list alone and disappears when unused.
         */
        Map<String, DependencyStyle> dependencyLines,
        Optional<PrerequisiteMode> prerequisiteMode,
        int minRequired,
        List<QuestTask> tasks,
        List<QuestReward> rewards,
        QuestRules rules
) {

    /** A quest with nothing in it. The starting point for the editor's "new quest" button. */
    public static Quest blank(String id, QuestText title) {
        return new Quest(id, title, Optional.empty(), List.of(), ItemRef.DEFAULT_ICON, QuestLayout.DEFAULT,
                List.of(), List.of(), Map.of(), Optional.empty(), 0, List.of(), List.of(),
                QuestRules.DEFAULT);
    }

    // ------------------------------------------------------------------
    // Delegated flags, so the grouping stays an implementation detail
    // ------------------------------------------------------------------

    public boolean repeatable() {
        return rules.repeatable();
    }

    public int repeatCooldownTicks() {
        return rules.repeatCooldownTicks();
    }

    public boolean sequentialTasks() {
        return rules.sequentialTasks();
    }

    public boolean invisible() {
        return rules.invisible();
    }

    /**
     * Whether the book draws this quest's name under its node.
     *
     * <p>False by default. A node is an icon; a canvas of fifty names is a wall of text, and the name
     * is available on hover for every quest whether this is set or not. An author asks for the ones
     * worth naming.
     */
    public boolean showTitle() {
        return rules.showTitle();
    }

    public Optional<String> exclusiveGroup() {
        return rules.exclusiveGroup();
    }

    /**
     * The stage a player needs for this quest to be open to them, if any.
     *
     * <p>Per player rather than per team, because a stage is -- see {@code QuestRules#requiresStage}. The
     * gate is checked where a player's own view is built (they see the quest locked) and where a quest could
     * be finished or collected, so a quest with a shut gate cannot complete or pay out for a player who
     * never had the stage. A pack that wants a whole party gated grants the stage to every member, which is
     * how it does that for anything else too.
     */
    public Optional<ResourceLocation> requiresStage() {
        return rules.requiresStage();
    }

    // ------------------------------------------------------------------

    /**
     * The prerequisite mode in force for this quest.
     *
     * @param chapterDefault what the chapter says, used when the quest does not say
     */
    public PrerequisiteMode prerequisiteMode(PrerequisiteMode chapterDefault) {
        return prerequisiteMode.orElse(chapterDefault);
    }

    /**
     * Whether {@code idOrAlias} refers to this quest.
     *
     * <p>Checked against the id and every alias, which is what makes renaming safe.
     */
    public boolean matches(String idOrAlias) {
        return id.equals(idOrAlias) || aliases.contains(idOrAlias);
    }

    /** How many dependencies must be satisfied, given the effective mode. {@code minRequired} wins when set. */
    public int requiredCount(PrerequisiteMode effectiveMode) {
        return PrerequisiteMode.requiredCount(effectiveMode, minRequired, dependencies.size());
    }

    /**
     * How many tasks must be done for the quest to complete.
     *
     * <p>Every task that is not marked optional — with the one exception the plan names: if every
     * task is optional, then any one of them completes the quest. A quest where nothing is required
     * would otherwise complete itself the moment it unlocked.
     */
    public int requiredTaskCount() {
        long mandatory = tasks.stream().filter(task -> !task.optional()).count();
        if (mandatory > 0) {
            return (int) mandatory;
        }
        // All optional: one will do, unless there are none at all, in which case there is nothing
        // to do and the quest is completable by declaration.
        return tasks.isEmpty() ? 0 : 1;
    }

    /** Whether {@code index} is a task a player has to do. */
    public boolean isTaskMandatory(int index) {
        if (index < 0 || index >= tasks.size()) {
            return false;
        }
        long mandatory = tasks.stream().filter(task -> !task.optional()).count();
        if (mandatory > 0) {
            return !tasks.get(index).optional();
        }
        // Every task is optional, so exactly one of them counts -- the first, so the choice is
        // stable rather than depending on which the player happened to do first.
        return !tasks.isEmpty() && index == 0;
    }

    /** Whether {@code index} is unlocked, given the tasks before it. Always true when not sequential. */
    public boolean isTaskUnlocked(int index, java.util.function.IntPredicate earlierTaskDone) {
        if (!sequentialTasks()) {
            return true;
        }
        for (int earlier = 0; earlier < index; earlier++) {
            if (!earlierTaskDone.test(earlier)) {
                return false;
            }
        }
        return true;
    }

    public static final Codec<Quest> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(Quest::id),
            QuestText.CODEC.fieldOf("title").forGetter(Quest::title),
            QuestText.CODEC.optionalFieldOf("subtitle").forGetter(Quest::subtitle),
            QuestText.CODEC.listOf().optionalFieldOf("description", List.of()).forGetter(Quest::description),
            ItemRef.CODEC.optionalFieldOf("icon", ItemRef.DEFAULT_ICON).forGetter(Quest::icon),
            // A MapCodec, so x/y/shape/size are flat on the quest in JSON.
            QuestLayout.MAP_CODEC.forGetter(Quest::layout),
            Codec.STRING.listOf().optionalFieldOf("aliases", List.of()).forGetter(Quest::aliases),
            QuestRef.CODEC.listOf().optionalFieldOf("dependsOn", List.of()).forGetter(Quest::dependencies),
            // Keyed by dependency id. Absent means every line follows the chapter's style, which is what
            // every file written before this field existed says.
            Codec.unboundedMap(Codec.STRING, DependencyStyle.CODEC)
                    .optionalFieldOf("dependencyLines", Map.of()).forGetter(Quest::dependencyLines),
            PrerequisiteMode.CODEC.optionalFieldOf("prerequisiteMode").forGetter(Quest::prerequisiteMode),
            Codec.intRange(0, 64).optionalFieldOf("minRequired", 0).forGetter(Quest::minRequired),
            // Accessor methods rather than constants: caching these in a static field is what caused a
            // class-initialisation cycle that compiled cleanly and failed only at runtime. See the note
            // in QuestTask, which explains it in full.
            TaskTypes.dispatchCodec().listOf().optionalFieldOf("tasks", List.of()).forGetter(Quest::tasks),
            RewardTypes.dispatchCodec().listOf().optionalFieldOf("rewards", List.of()).forGetter(Quest::rewards),
            // Also a MapCodec: the flags stay flat on the quest.
            QuestRules.MAP_CODEC.forGetter(Quest::rules)
    ).apply(instance, Quest::new));
}
