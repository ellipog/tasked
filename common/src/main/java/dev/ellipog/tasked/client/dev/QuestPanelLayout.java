package dev.ellipog.tasked.client.dev;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import dev.ellipog.armature.client.ui.inspect.InspectField;
import dev.ellipog.armature.client.ui.inspect.InspectRow;
import dev.ellipog.tasked.quest.EditorField;
import dev.ellipog.tasked.quest.EditorSpecs;
import dev.ellipog.tasked.quest.condition.ConditionTypes;
import dev.ellipog.tasked.quest.reward.RewardTypes;
import dev.ellipog.tasked.quest.task.TaskTypes;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The quest panel's rows, from one quest's own tree.
 *
 * <h2>What reads what</h2>
 *
 * <p>The input is the {@code ClientChapterReplica} copy -- the quest's file as the server holds it, every
 * field in it, including the ones this build has never heard of. The output is a list of
 * {@link InspectRow}s in sections that fold: identity, placement, rules, dependencies, and one section
 * per task and per reward. A section folded is a section absent -- the caller rebuilds with the fold
 * named, and no row of it is built.
 *
 * <p>Row <b>keys are paths</b>. A field row's key is the dotted path its commit goes to
 * ({@code "tasks.0.count"}), because a path is unique within a quest and a second key scheme beside it
 * would be a second thing to keep in step. Rows that are not fields -- the headings, the read-only
 * values, the dependency rows, the raw fallback -- prefix their keys so they cannot collide with a path.
 *
 * <h2>The two voices of per-type</h2>
 *
 * <p>A task or reward whose {@code type} is registered gets its own fields, read from the registry the
 * validator reads -- so a type an addon registers is a panel the day it registers, with no second list
 * anywhere. One this build does not know gets the fallback: a warning heading, then the value itself as
 * it is stored. A fallback that invented rows for fields it did not understand would look like support;
 * showing the value cannot lie, and the tree's own preservation on save is what keeps it safe.
 *
 * <p>The fallback row is this row model's shape of an unknown type; the <b>card</b> is where it is
 * edited, in the multi-line JSON field, and {@code SetField} carries the parsed object whole. This
 * paragraph used to say the opposite -- that the model refused an object on principle -- which stopped
 * being true the day the op grew its {@code JsonElement} value. The stale note made the raw editor look
 * unbuilt while the card had already shipped it, which is worth remembering: a comment that describes a
 * limitation is a claim, and this one outlived its subject.
 */
public final class QuestPanelLayout {

    /** The foldable sections, by their heading key. */
    public static final String IDENTITY = "h:identity";
    public static final String PLACEMENT = "h:placement";
    public static final String RULES = "h:rules";
    public static final String DEPENDENCIES = "h:dependencies";

    /** The row that adds a dependency by typed id, and the prefix of every dependency's own row. */
    public static final String DEPENDENCY_ADD = "dep:add";
    public static final String DEPENDENCY_PICK = "dep:pick";
    public static final String DEPENDENCY_PREFIX = "dep:";

    /** The raw fallback's row prefix. */
    public static final String RAW_PREFIX = "raw:";

    /** The read-only values' prefix. */
    public static final String VALUE_PREFIX = "v:";

    /** The rows that add a task or a reward, and the prefix of a type-picker row. */
    public static final String ADD_TASKS = "add:tasks";
    public static final String ADD_REWARDS = "add:rewards";
    public static final String TYPE_PREFIX = "type:";

    private QuestPanelLayout() {
    }

    /**
     * The whole panel for one quest.
     *
     * @param quest  the quest's own tree from the replica, or null when nothing is selected -- which is
     *               an empty panel with a line saying so, not an empty panel that looks broken
     * @param folded the heading keys currently folded; a folded section contributes only its heading
     */
    public static List<InspectRow> rows(JsonObject quest, String questId, Set<String> folded) {
        Objects.requireNonNull(folded, "folded");
        List<InspectRow> rows = new ArrayList<>();
        if (quest == null) {
            rows.add(InspectRow.value(VALUE_PREFIX + "none", "Quest",
                    questId == null ? "Select a quest on the canvas" : questId + " is not in this chapter"));
            return List.copyOf(rows);
        }

        rows.add(InspectRow.heading(IDENTITY, "Identity"));
        if (!folded.contains(IDENTITY)) {
            rows.add(InspectRow.value(VALUE_PREFIX + "id", "Id", text(quest, "id", questId)));
            rows.add(field(quest, "title"));
            rows.add(field(quest, "subtitle"));
            rows.add(field(quest, "icon.item"));
            int descriptionLines = lines(quest);
            rows.add(InspectRow.value(VALUE_PREFIX + "description", "Description",
                    descriptionLines + (descriptionLines == 1 ? " line" : " lines") + " -- edited in the file"));
            rows.add(InspectRow.field("aliases", "Aliases, comma-separated",
                    String.join(", ", strings(quest, "aliases"))));
        }

        rows.add(InspectRow.heading(PLACEMENT, "Placement"));
        if (!folded.contains(PLACEMENT)) {
            rows.add(field(quest, "x"));
            rows.add(field(quest, "y"));
            rows.add(field(quest, "shape"));
            rows.add(field(quest, "size"));
            rows.add(toggle(quest, "showTitle"));
            rows.add(field(quest, "iconScale"));
        }

        rows.add(InspectRow.heading(RULES, "Rules"));
        if (!folded.contains(RULES)) {
            rows.add(toggle(quest, "repeatable"));
            rows.add(field(quest, "repeatCooldownTicks"));
            rows.add(toggle(quest, "sequentialTasks"));
            rows.add(toggle(quest, "invisible"));
            rows.add(field(quest, "exclusiveGroup"));
            rows.add(field(quest, "prerequisiteMode"));
            rows.add(field(quest, "minRequired"));
            // The stage gate, beside the other rules about when a quest opens: it is the one of them that is
            // per player rather than per team, which is what its label says.
            rows.add(field(quest, "requiresStage"));
        }

        rows.add(InspectRow.heading(DEPENDENCIES, "Dependencies"));
        if (!folded.contains(DEPENDENCIES)) {
            for (String dependency : strings(quest, "dependsOn")) {
                rows.add(InspectRow.toggle(DEPENDENCY_PREFIX + dependency, dependency));
            }
            rows.add(InspectRow.field(DEPENDENCY_ADD, "Add by id", ""));
            // The second way to say the same thing: the next canvas click names the quest. The third
            // -- dragging an edge between the nodes -- is the canvas's, and needs no row.
            rows.add(InspectRow.action(DEPENDENCY_PICK, "Add from canvas"));
        }

        list(quest, "tasks", "Task", folded, rows);
        rows.add(InspectRow.action(ADD_TASKS, "Add task"));
        list(quest, "rewards", "Reward", folded, rows);
        rows.add(InspectRow.action(ADD_REWARDS, "Add reward"));
        return List.copyOf(rows);
    }

    /** One heading of the picker's list, with the types shown under it. */
    public record TypeGroup(String title, List<TypeChoice> types) {
    }

    /**
     * One type's presentation: what the picker calls it, what it is for, whether a row's sentence about
     * it names a registry id the client should prettify before showing -- and, separately, how the row
     * is explained to a <b>player</b>.
     *
     * <h2>Why two hints and not one</h2>
     *
     * <p>They are read by different people. {@code hint} is the picker's line, written for an author
     * choosing a type ("Hand in a count of any item in a tag"), and it travels with the fields and the
     * id in the picker's own tooltip. {@code playerHint} is what a reader sees when they hover the row
     * in a quest card: no field names, no ids, no jargon -- what the row is asking <i>them</i> to do.
     * A type whose two audiences would read the same words still spells both, because the next edit to
     * one of them must not silently change the other.
     *
     * <p>The flag is a property of the <b>sentence</b>, not of the registry. A dimension's row says
     * "Visit minecraft:overworld", so the id is the one thing on it that wants prettifying; a stage's
     * says "Have the stage my_pack:inducted", where the id <i>is</i> the name and prettifying it would
     * invent one. Only the type knows which of those its sentence is, and the table is where the type
     * is already described.
     */
    public record TypeChoice(String id, String name, String hint, String playerHint, boolean friendlyArg) {

        /** A type whose argument is not a registry id, or whose id is the name. */
        public TypeChoice(String id, String name, String hint, String playerHint) {
            this(id, name, hint, playerHint, false);
        }
    }

    /** The heading every registered type the table does not name is listed under. */
    public static final String MORE = "More";

    /** The key prefix of a group's heading; the index follows, so two groups cannot share a key. */
    public static final String TYPE_GROUP_PREFIX = "h:type:";

    /**
     * The picker's grouping, order, names and hints for the built-in types.
     *
     * <p>Curated, because none of those four things can be derived: a registry knows ids, not what to
     * call them or which belong together. The table is presentation only -- {@link #typeRows} lists what
     * the registries hold, so a type it does not name still appears, under {@link #MORE}, named by its
     * own id.
     */
    private static final List<TypeGroup> TASK_GROUPS = List.of(
            new TypeGroup("Hand in", List.of(
                    new TypeChoice("tasked:item", "Item", "Hand in a count of one item.",
                            "Have this many in your inventory."),
                    new TypeChoice("tasked:item_tag", "Item tag", "Hand in a count of any item in a tag.",
                            "Have this many items from this tag.", true),
                    new TypeChoice("tasked:xp", "Experience", "Hand in experience points or levels.",
                            "Have this much experience."),
                    new TypeChoice("tasked:fluid", "Fluid", "Hand in fluid, carried in buckets.",
                            "Have this much fluid, carried in buckets.", true))),
            new TypeGroup("Go", List.of(
                    new TypeChoice("tasked:dimension", "Dimension", "Be in a dimension.",
                            "Travel to this dimension.", true),
                    new TypeChoice("tasked:biome", "Biome", "Be in a biome, or any biome of a tag.",
                            "Be in this biome.", true),
                    new TypeChoice("tasked:structure", "Structure", "Be inside a structure.",
                            "Find this structure.", true),
                    new TypeChoice("tasked:location", "Location", "Stand inside a box of coordinates.",
                            "Stand inside this area."))),
            new TypeGroup("Progress", List.of(
                    new TypeChoice("tasked:advancement", "Advancement",
                            "Earn an advancement, or one of its criteria.",
                            "Earn this advancement.", true),
                    new TypeChoice("tasked:stat", "Statistic", "Reach a vanilla statistic value.",
                            "Raise this statistic to the amount shown.", true),
                    new TypeChoice("tasked:stage", "Stage", "The player has a stage.",
                            "Story progress - another quest or a command sets this."),
                    new TypeChoice("tasked:kill", "Kill", "Kill entities, by type or tag.",
                            "Defeat this many.", true),
                    new TypeChoice("tasked:observation", "Observe",
                            "Look at a block or entity for long enough.",
                            "Look at it and keep looking for the time shown.", true))),
            new TypeGroup("Manual", List.of(
                    new TypeChoice("tasked:checkmark", "Checkmark", "The player says they did it.",
                            "You decide when this one is done."))),
            // Last, and its own group: this one is not a kind of thing to ask for but a way of asking for
            // anything -- the handler a mod or a script registered decides what it means.
            new TypeGroup("Other", List.of(
                    new TypeChoice("tasked:custom", "Custom",
                            "Progress measured by a mod or a script, by an id.",
                            "Tracked by the pack's own code."))));

    private static final List<TypeGroup> REWARD_GROUPS = List.of(
            new TypeGroup("Items", List.of(
                    new TypeChoice("tasked:item", "Item", "Give one item.",
                            "You get this item when you claim the quest."),
                    new TypeChoice("tasked:random", "Random roll", "One guaranteed roll from a loot table.",
                            "Rolls a loot table when you claim it."),
                    new TypeChoice("tasked:loot", "Loot roll", "A roll that can come up empty.",
                            "Rolls a loot table when you claim it - it can come up empty."),
                    new TypeChoice("tasked:all_table", "Whole table",
                            "Every table entry once, weights ignored.",
                            "Gives every entry of a loot table, once."))),
            new TypeGroup("Choice", List.of(
                    new TypeChoice("tasked:choice", "Choice", "The player picks one entry of a table.",
                            "You pick one entry when you claim it."))),
            new TypeGroup("Server", List.of(
                    new TypeChoice("tasked:command", "Command", "Run a command as the player.",
                            "Runs a command when you claim it."),
                    new TypeChoice("tasked:advancement", "Advancement", "Award an advancement.",
                            "Awards an advancement when you claim it.", true),
                    new TypeChoice("tasked:custom", "Custom", "Somebody else's code, by id.",
                            "Granted by the pack's own code."))),
            new TypeGroup("Progress", List.of(
                    new TypeChoice("tasked:xp", "Experience", "Give experience points or levels.",
                            "Gives experience when you claim it."),
                    new TypeChoice("tasked:stage", "Stage", "Set a stage, or take one away.",
                            "Sets story progress when you claim it."))));

    private static final List<TypeGroup> CONDITION_GROUPS = List.of(
            new TypeGroup("Items", List.of(
                    new TypeChoice("tasked:item", "Item", "Have a count of one item.",
                            "Have this many in your inventory."),
                    new TypeChoice("tasked:item_tag", "Item tag", "Have a count of any item in a tag.",
                            "Have this many items from this tag."))),
            new TypeGroup("Progress", List.of(
                    new TypeChoice("tasked:advancement", "Advancement", "Have earned an advancement.",
                            "Earn this advancement."),
                    new TypeChoice("tasked:score", "Scoreboard", "Have a score on an objective.",
                            "Reach this score on the scoreboard."),
                    new TypeChoice("tasked:stage", "Stage", "Have a stage.",
                            "Story progress - another quest or a command sets this."))),
            new TypeGroup("Party", List.of(
                    new TypeChoice("tasked:party_size", "Party size", "Have this many members online.",
                            "Be in a party of this many."))));

    /**
     * The type picker's rows: the page's own heading, then one group heading per table group with the
     * registered types of the member being added to under it.
     *
     * <p>From the registries, so an addon's type is in the list the day it registers. The table supplies
     * the grouping, the order, the name and the hint; a registered id it does not name is still listed,
     * under {@link #MORE} and named by its id -- the spelling an author meets in the JSON, in the entry
     * row's hover label and in every error message.
     */
    public static List<InspectRow> typeRows(String member) {
        Set<String> registered = new TreeSet<>();
        for (ResourceLocation id : ("rewards".equals(member) ? RewardTypes.ids() : TaskTypes.ids())) {
            registered.add(id.toString());
        }
        return typeRows(member, registered);
    }

    /**
     * The same, over an id set the caller supplies.
     *
     * <p>How the fallback group is tested: an id no table names is what an addon's type looks like, and
     * the alternative -- registering one into a registry the whole test JVM shares -- would leave every
     * other test running against a build with an addon installed.
     */
    static List<InspectRow> typeRows(String member, Set<String> registered) {
        boolean tasks = !"rewards".equals(member);
        List<TypeGroup> groups = tasks ? TASK_GROUPS : REWARD_GROUPS;
        return rowsFor(tasks ? "Add a task" : "Add a reward", groups, registered);
    }

    /**
     * The condition picker's rows: the same machinery as the task and reward pickers, over the third
     * registry.
     *
     * <p>Its own headings rather than a shared "Add a task" one, because a condition is not a task and
     * the picker is the only place an author learns the six types' names.
     */
    public static List<InspectRow> conditionTypeRows() {
        Set<String> registered = new TreeSet<>();
        for (ResourceLocation id : ConditionTypes.ids()) {
            registered.add(id.toString());
        }
        return rowsFor("Add a condition", CONDITION_GROUPS, registered);
    }

    /**
     * The rows one picker shows: the page's own heading, then each table group with the registered types
     * it names, then everything the table does not name under {@link #MORE}.
     *
     * <p>Shared by all three pickers so a type's row, a group's heading and the fallback group cannot
     * mean different things in different lists — and so an addon's type is in each list the day it
     * registers, named by its id where the table has nothing to call it.
     */
    private static List<InspectRow> rowsFor(String heading, List<TypeGroup> groups, Set<String> registered) {
        List<InspectRow> rows = new ArrayList<>();
        rows.add(InspectRow.heading("h:type", heading));
        Set<String> named = new TreeSet<>();
        int group = 0;
        for (TypeGroup each : groups) {
            List<TypeChoice> shown = each.types().stream()
                    .filter(choice -> registered.contains(choice.id())).toList();
            if (shown.isEmpty()) {
                continue;
            }
            rows.add(InspectRow.heading(TYPE_GROUP_PREFIX + group++, each.title()));
            for (TypeChoice choice : shown) {
                rows.add(InspectRow.action(TYPE_PREFIX + choice.id(), choice.name()));
                named.add(choice.id());
            }
        }
        Set<String> unnamed = new TreeSet<>(registered);
        unnamed.removeAll(named);
        if (!unnamed.isEmpty()) {
            rows.add(InspectRow.heading(TYPE_GROUP_PREFIX + group, MORE));
            for (String id : unnamed) {
                rows.add(InspectRow.action(TYPE_PREFIX + id, id));
            }
        }
        return List.copyOf(rows);
    }

    /**
     * A condition type's name from the picker's table, or its prettified path for one the table does not
     * name — an addon's, which the badge names by the spelling a file uses.
     */
    public static String conditionName(String typeId) {
        for (TypeGroup group : CONDITION_GROUPS) {
            for (TypeChoice choice : group.types()) {
                if (choice.id().equals(typeId)) {
                    return choice.name();
                }
            }
        }
        ResourceLocation id = ResourceLocation.tryParse(typeId);
        return id == null ? typeId : EditorSpecs.label(id.getPath());
    }

    /**
     * The declared form for a condition's type, or empty for one this build does not know — the raw-JSON
     * fallback's trigger, the same as an entry's own unknown type.
     */
    public static List<EditorField> conditionEditorFor(JsonObject condition) {
        String type = text(condition, "type", "");
        ResourceLocation id = ResourceLocation.tryParse(type);
        return id == null ? List.of() : ConditionTypes.editorOf(id);
    }

    /**
     * The author's description of a condition type, for the picker's button hover: what the table says it
     * is for, the fields it will ask for, and the id a file spells.
     */
    public static List<String> conditionTypeTooltip(String typeId) {
        List<String> lines = new ArrayList<>();
        for (TypeGroup group : CONDITION_GROUPS) {
            for (TypeChoice choice : group.types()) {
                if (choice.id().equals(typeId)) {
                    lines.add(choice.hint());
                }
            }
        }
        ResourceLocation id = ResourceLocation.tryParse(typeId);
        if (id != null && ConditionTypes.ids().contains(id)) {
            lines.add("Fields: " + new TreeSet<>(ConditionTypes.fieldsOf(id)));
        }
        lines.add(typeId);
        return List.copyOf(lines);
    }

    /**
     * Whether a type's sentence names a registry id the client should prettify before it is shown.
     *
     * <p>False for a type the table does not name: an addon's sentence is its own, and rewriting an id
     * inside it would be a guess about a shape this build has never seen.
     */
    public static boolean prettifiesIds(String member, String typeId) {
        TypeChoice choice = choiceFor(member, typeId);
        return choice != null && choice.friendlyArg();
    }

    /**
     * A registry id as a row reads it: the path, prettified, with a tag's leading {@code #} kept.
     *
     * <p>A string that is not a valid id at all is returned unchanged -- but a bare word is not such a
     * string: since 1.21 it parses as {@code minecraft:<word>}, so this is the rewrite to apply once a
     * token is known to be an id, not the test for whether it is one. That test is
     * {@link #prettiedArgument}'s, and it asks for a namespace.
     */
    public static String friendlyId(String id) {
        boolean tag = id.startsWith("#");
        ResourceLocation location = ResourceLocation.tryParse(tag ? id.substring(1) : id);
        if (location == null) {
            return id;
        }
        return (tag ? "#" : "") + EditorSpecs.label(location.getPath());
    }

    /**
     * A type's argument, with the registry ids in it prettified when the type's table entry says its
     * argument names one.
     *
     * <h2>Why token by token, and why a token must be a full id</h2>
     *
     * <p>Because some sentences put more than an id in the argument. A fluid task's is
     * "1000 mB of minecraft:water" and an observation's is "minecraft:beacon for 2.0s", and the words
     * around the id are not registry ids -- running the whole argument through the prettifier would
     * mangle them.
     *
     * <p>And a bare word is not evidence of an id: since 1.21 a namespace-less string parses as
     * {@code minecraft:<word>}, so prettifying every parseable token would turn the "of" in that fluid
     * sentence into "Of" and a kill task's "anything" into "Anything". Only a token that spells a
     * namespace is an id here -- which is what the sentences write, since they all stringify a
     * {@code ResourceLocation} -- and everything else passes through exactly as the server wrote it.
     */
    public static String prettiedArgument(String member, String typeId, String arg) {
        return prettiedArgument(member, typeId, arg, null);
    }

    /**
     * The same, with a chance to name a full id better than its path can.
     *
     * <p>{@code betterName} is asked for each id token, with the token's id as its argument, and its
     * answer is used when it has one. An advancement is the caller that needs this -- the client holds
     * the advancement's own title, which is what the player knows it by -- and every other type passes
     * null.
     */
    public static String prettiedArgument(String member, String typeId, String arg,
                                          java.util.function.Function<String, String> betterName) {
        if (!prettifiesIds(member, typeId)) {
            return arg;
        }
        StringBuilder out = new StringBuilder();
        for (String token : arg.split(" ")) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            String bare = token.startsWith("#") ? token.substring(1) : token;
            if (bare.indexOf(':') < 0) {
                out.append(token);
                continue;
            }
            String better = betterName == null ? null : betterName.apply(bare);
            out.append(better != null ? better : friendlyId(token));
        }
        return out.toString();
    }

    /**
     * The hover description of a type in the picker: what it is for, the fields it takes, and the id a
     * file spells.
     *
     * <p>Plain strings, because that is what the picker's buttons draw with. This is the <b>author's</b>
     * description and it says so: field names and the id are what an author edits, and the picker is a
     * tool only edit mode reaches. What a player reads on a row's hover is {@link #playerTooltip}, which
     * shares none of these lines.
     *
     * <p>A type the table does not name has no hint, so its id -- which is the name in that case -- is
     * the last line rather than the first.
     */
    public static List<String> typeTooltip(String member, String typeId) {
        TypeChoice choice = choiceFor(member, typeId);
        List<String> lines = new ArrayList<>();
        if (choice != null) {
            lines.add(choice.hint());
        }
        ResourceLocation id = ResourceLocation.tryParse(typeId);
        if (id != null) {
            Set<String> fields = "rewards".equals(member) ? RewardTypes.fieldsOf(id) : TaskTypes.fieldsOf(id);
            if (!fields.isEmpty()) {
                lines.add("Fields: " + String.join(", ", new TreeSet<>(fields)));
            }
        }
        lines.add(typeId);
        return List.copyOf(lines);
    }

    /** The player line for a type the table does not name: true of every one of them, and nothing else. */
    private static final String UNKNOWN_TASK = "Tracked by the pack's own code.";
    private static final String UNKNOWN_REWARD = "Granted by the pack's own code.";

    /** The type's player-facing line, or null for one the table does not name. */
    public static String playerHint(String member, String typeId) {
        TypeChoice choice = choiceFor(member, typeId);
        return choice == null ? null : choice.playerHint();
    }

    /**
     * What a player reads when they hover a task or reward row in a quest card.
     *
     * <h2>Written for the reader, not the author</h2>
     *
     * <p>No field names, no ids, no type names: the row beside the pointer already says what the row
     * asks, and this says what that <i>means</i> and how it is completed. An author who wants the fields
     * and the id has the picker's tooltip, which is where that belongs -- see {@link #typeTooltip}.
     *
     * <h2>The one row-specific fact</h2>
     *
     * <p>{@code byHand} is the row's own flag, the one that draws the "hand in" tag and the Submit
     * button. It decides the second line, and what that line may claim depends on the type: the item
     * kinds, experience and fluid take what they are handed; a checkmark takes nothing; and an addon's
     * handler is its own business, so it gets the neutral wording. A carried item that is <b>not</b>
     * handed in says so instead -- that nothing is taken is the surprising half of the two behaviours,
     * and the half a player has no other way to learn.
     */
    public static List<String> playerTooltip(String member, String typeId, boolean byHand) {
        boolean rewards = "rewards".equals(member);
        String line = playerHint(member, typeId);
        List<String> lines = new ArrayList<>();
        lines.add(line == null ? (rewards ? UNKNOWN_REWARD : UNKNOWN_TASK) : line);
        if (!byHand) {
            if (!rewards && carriesItems(typeId)) {
                lines.add("Nothing is taken - the quest only checks that you have them.");
            }
            return List.copyOf(lines);
        }
        if ("tasked:checkmark".equals(typeId)) {
            lines.add("Press the Submit button when you have done it.");
        }
        else if (!rewards && takesResources(typeId)) {
            lines.add("Hand it in with the Submit button - what you hand over is taken.");
        }
        else {
            lines.add("Hand it in with the Submit button.");
        }
        return List.copyOf(lines);
    }

    /** The task kinds a row can carry in an inventory: their sentences are about having, not doing. */
    private static boolean carriesItems(String typeId) {
        return "tasked:item".equals(typeId) || "tasked:item_tag".equals(typeId);
    }

    /**
     * The task kinds that take what they are handed.
     *
     * <p>The item kinds only reach a Submit button when set to consume -- a presence-only one completes
     * by itself -- so when they are here, they take. Experience and fluid always take; a checkmark never
     * does, which is why it is not in this set.
     */
    private static boolean takesResources(String typeId) {
        return "tasked:item".equals(typeId) || "tasked:item_tag".equals(typeId)
                || "tasked:xp".equals(typeId) || "tasked:fluid".equals(typeId);
    }

    /**
     * The name the picker and an entry's badge show for a type.
     *
     * <p>The table's own word, or the id for a type it does not name -- an addon's, which is the spelling
     * its author meets in the file and in every error message, and therefore the honest fallback.
     */
    public static String typeName(String member, String typeId) {
        TypeChoice choice = choiceFor(member, typeId);
        return choice == null ? typeId : choice.name();
    }

    private static TypeChoice choiceFor(String member, String typeId) {
        for (TypeGroup group : "rewards".equals(member) ? REWARD_GROUPS : TASK_GROUPS) {
            for (TypeChoice choice : group.types()) {
                if (choice.id().equals(typeId)) {
                    return choice;
                }
            }
        }
        return null;
    }

    /**
     * The fields of one entry, from its own type's registration: the type's form, with the settings every
     * task or reward has already appended to it.
     *
     * <p>Empty for a type this build does not know, and for an entry whose {@code type} is missing or
     * malformed. There is no form to draw for those, and the card draws the raw value instead -- the same
     * refusal the reader makes, and the one that keeps an unknown type editable rather than silently
     * blank.
     */
    public static List<dev.ellipog.tasked.quest.EditorField> editorFor(String member, JsonObject entry) {
        String type = text(entry, "type", "");
        ResourceLocation id = ResourceLocation.tryParse(type);
        if (id == null) {
            return List.of();
        }
        return "rewards".equals(member) ? RewardTypes.editorOf(id) : TaskTypes.editorOf(id);
    }

    /** Whether a type id is one this build can add to the given member. */
    public static boolean canAdd(String member, String typeId) {
        ResourceLocation id = ResourceLocation.tryParse(typeId);
        if (id == null) {
            return false;
        }
        return "rewards".equals(member) ? RewardTypes.ids().contains(id) : TaskTypes.ids().contains(id);
    }

    /** The tree a fresh entry of this type starts as, or null for a type this build cannot add. */
    public static JsonObject defaultEntry(String member, String typeId) {
        ResourceLocation id = ResourceLocation.tryParse(typeId);
        if (id == null) {
            return null;
        }
        return ("rewards".equals(member) ? RewardTypes.defaultTree(id) : TaskTypes.defaultTree(id))
                .orElse(null);
    }

    /**
     * One task's or one reward's section: its type's fields, or the fallback.
     *
     * <p>A registered type's rows are one per field name in the registry's own set, sorted so the panel
     * is stable across rebuilds -- a set's order is not, and a panel whose rows shuffle between frames
     * is a panel an author cannot aim at.
     */
    private static void list(JsonObject quest, String member, String what, Set<String> folded,
                             List<InspectRow> rows) {
        if (!quest.has(member) || !quest.get(member).isJsonArray()) {
            return;
        }
        var array = quest.getAsJsonArray(member);
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String headingKey = "h:" + member + "." + i;
            String type = text(entry, "type", "not stated");
            boolean known = knownType(type);

            if (known) {
                // An entry row rather than a plain heading: its controls -- duplicate, remove -- go on
                // the line that names what they act on.
                rows.add(InspectRow.entry(headingKey, what + " " + (i + 1) + " \u00b7 " + type));
            }
            else {
                rows.add(InspectRow.warning(headingKey,
                        what + " " + (i + 1) + " \u00b7 " + type + " -- not known to this build"));
            }
            if (folded.contains(headingKey)) {
                continue;
            }
            if (known) {
                for (String fieldName : new TreeSet<>(fieldsOf(type, entry))) {
                    // The value is read from the quest's own root, with the full path: the commit goes
                    // back through the same path, and a read that started at the task object would be
                    // answering a path that names nothing from where it stands.
                    rows.add(field(quest, member + "." + i + "." + fieldName));
                }
            }
            else {
                rows.add(InspectRow.raw(RAW_PREFIX + member + "." + i,
                        "as stored", entry.toString()));
            }
        }
    }

    /** Whether a type id is one this build has a panel for. The fallback is not a panel. */
    public static boolean knownType(String type) {
        ResourceLocation id = ResourceLocation.tryParse(type);
        if (id == null) {
            return false;
        }
        return TaskTypes.ids().contains(id) || RewardTypes.ids().contains(id);
    }

    /** A registered type's own field names, sorted. Empty for an unregistered one. */
    private static Set<String> fieldsOf(String type, JsonObject entry) {
        ResourceLocation id = ResourceLocation.tryParse(type);
        if (id == null) {
            return Set.of();
        }
        Set<String> fields = TaskTypes.ids().contains(id) ? TaskTypes.fieldsOf(id) : Set.of();
        if (fields.isEmpty()) {
            fields = RewardTypes.ids().contains(id) ? RewardTypes.fieldsOf(id) : Set.of();
        }
        // Structured fields are not rows of this panel: a text field for an object or an array would
        // offer to write a *string* where the format holds a structure -- a row that corrupts the file
        // it is drawn from. `components` is the named case; a field whose stored value is an array or
        // an object -- a location's position -- is the general one. The editor's card still edits
        // those, as indexed numbers or through the picker. An absent structured field stays a row
        // (as text), which is how it gets created in the first place.
        return fields.stream().filter(field -> {
            // Structured fields are not rows of this panel, named or otherwise: `components` because the
            // item control owns it, and `conditions` because the card draws the list as its own section
            // -- a text row here would offer to write a string where the format holds a list, which is
            // the corruption this filter exists to prevent.
            if ("components".equals(field) || "conditions".equals(field)) {
                return false;
            }
            JsonElement value = entry.get(field);
            return value == null || (!value.isJsonArray() && !value.isJsonObject());
        }).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    // ------------------------------------------------------------------
    // The fields, and what type each one edits as
    // ------------------------------------------------------------------

    /**
     * The typed field for editing whatever is at a path <b>right now</b>.
     *
     * <p>Asked again at commit time rather than remembered from when the rows were built, because the
     * tree may have changed underneath the panel -- a revision boundary is the only thing that rebuilds
     * it, and a value read from a stale tree is a value the server does not have.
     *
     * <p>The kind comes from three answers, in order: what the format <b>declares</b> the field to be,
     * which holds even when the value is absent and is what lets a panel add an {@code iconScale} that
     * was never there; else what is <b>there</b> -- a number that is whole edits whole and one that is
     * not as a decimal, a boolean as a flag; else text, which is the honest answer for a field whose
     * value is a string or has never been written.
     */
    public static InspectField<?> fieldFor(JsonObject quest, String path) {
        String label = labelFor(path);
        InspectField<?> declared = declared(path, label);
        if (declared != null) {
            return declared;
        }
        JsonElement current = get(quest, path);
        if (current != null && current.isJsonPrimitive()) {
            JsonPrimitive primitive = current.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return InspectField.flag(label);
            }
            if (primitive.isNumber()) {
                double value = primitive.getAsDouble();
                return value == Math.floor(value)
                        ? InspectField.integer(label, Integer.MIN_VALUE, Integer.MAX_VALUE)
                        : InspectField.decimal(label, -1.0E9, 1.0E9);
            }
        }
        return InspectField.text(label);
    }

    /**
     * The kind the format declares for the quest-level fields, and for the per-type fields whose names
     * carry it. Bounds here are generous -- the loader's own validator is what refuses an impossible
     * value, on the server, with its own message.
     */
    private static InspectField<?> declared(String path, String label) {
        if (path.equals("x") || path.equals("y") || path.equals("size")
                || path.equals("repeatCooldownTicks") || path.equals("minRequired")
                || path.endsWith(".count") || path.endsWith(".amount")
                || path.endsWith(".autoSubmitTicks")) {
            return InspectField.integer(label, -1000000, 1000000);
        }
        if (path.equals("iconScale")) {
            return InspectField.decimal(label, 0.25, 8.0);
        }
        if (path.equals("showTitle") || path.equals("repeatable") || path.equals("sequentialTasks")
                || path.equals("invisible") || path.endsWith(".consumeItems")
                || path.endsWith(".optional")) {
            return InspectField.flag(label);
        }
        return null;
    }

    /**
     * A row's label, from its path: the field's own name, with the container that names it, said like a
     * field.
     *
     * <p>{@code "icon.item"} says "Icon Item", because "Item" alone in a panel of rows is ambiguous. The
     * parts that do not name a field are dropped: an index is where the field lives -- which the
     * section's heading already says -- and the tasks/rewards member is there, for the same reason. So
     * {@code "tasks.0.count"} says "Count", and {@code "title"} says "Title".
     */
    public static String labelFor(String path) {
        List<String> parts = new ArrayList<>();
        for (String step : path.split("\\.")) {
            if (!step.matches("\\d+") && !step.equals("tasks") && !step.equals("rewards")) {
                parts.add(step);
            }
        }
        if (parts.isEmpty()) {
            return path;
        }
        StringBuilder out = new StringBuilder();
        for (String word : parts.get(parts.size() - 1).split("(?=[A-Z])|_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        // The leaf is the field's own name; the container before it is said whole, so that "Icon Item"
        // and not "Icon" -- a label of the container alone would name the section twice.
        if (parts.size() >= 2) {
            out.insert(0, capitalised(parts.get(parts.size() - 2)) + " ");
        }
        return out.toString();
    }

    private static String capitalised(String word) {
        String[] parts = word.split("(?=[A-Z])|_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)))
                    .append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // The row factories, which read the tree to say what is there
    // ------------------------------------------------------------------

    private static InspectRow field(JsonObject quest, String path) {
        return InspectRow.field(path, labelFor(path), displayValue(quest, path));
    }

    private static InspectRow field(JsonObject quest, String path, String label) {
        return InspectRow.field(path, label, displayValue(quest, path));
    }

    private static InspectRow toggle(JsonObject quest, String path) {
        boolean on = quest.has(path) && quest.get(path).isJsonPrimitive()
                && quest.get(path).getAsJsonPrimitive().isBoolean()
                && quest.get(path).getAsBoolean();
        return InspectRow.toggle(path, labelFor(path) + (on ? " \u00b7 on" : " \u00b7 off"));
    }

    /** A field's value as the panel shows it, with absent said rather than implied. */
    private static String displayValue(JsonObject quest, String path) {
        JsonElement found = get(quest, path);
        if (found == null) {
            return "";
        }
        if (found.isJsonPrimitive()) {
            return found.getAsString();
        }
        return found.toString();
    }

    private static String text(JsonObject object, String member, String fallback) {
        return object.has(member) && object.get(member).isJsonPrimitive()
                ? object.get(member).getAsString() : fallback;
    }

    /** The strings of an array at a top-level member of the quest's tree. Absent is empty. */
    public static List<String> strings(JsonObject object, String member) {
        List<String> out = new ArrayList<>();
        // Null-tolerant, like `get`: the settings page builds its rows from the replica, and a replica
        // that has not arrived yet is a real state rather than a caller's mistake.
        if (object != null && object.has(member) && object.get(member).isJsonArray()) {
            for (JsonElement element : object.getAsJsonArray(member)) {
                if (element.isJsonPrimitive()) {
                    out.add(element.getAsString());
                }
            }
        }
        return List.copyOf(out);
    }

    private static int lines(JsonObject quest) {
        JsonElement description = quest.get("description");
        if (description == null || !description.isJsonArray()) {
            return description == null ? 0 : 1;
        }
        return description.getAsJsonArray().size();
    }

    // ------------------------------------------------------------------
    // Reading a tree, the one way the panel does it
    // ------------------------------------------------------------------

    /**
     * An element at a dotted path of the quest's tree, or null.
     *
     * <p>The panel's own read, not the model's: the replica is a plain {@link JsonObject}, and importing
     * {@code JsonFile} to walk it would be opening a file that is not there. The array steps are the
     * same shape the model writes, so a path this read answers is a path a commit can go back through.
     */
    public static JsonElement get(JsonObject root, String path) {
        JsonElement at = root;
        for (String step : path.split("\\.")) {
            if (at == null) {
                return null;
            }
            if (at.isJsonObject()) {
                at = at.getAsJsonObject().get(step);
            }
            else if (at.isJsonArray()) {
                int index;
                try {
                    index = Integer.parseInt(step);
                }
                catch (NumberFormatException notAnIndex) {
                    return null;
                }
                var array = at.getAsJsonArray();
                at = index < 0 || index >= array.size() ? null : array.get(index);
            }
            else {
                return null;
            }
        }
        return at;
    }
}
