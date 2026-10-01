package dev.ellipog.tasked.client.dev;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import dev.ellipog.armature.client.ui.inspect.InspectField;
import dev.ellipog.armature.client.ui.inspect.InspectRow;
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

    /**
     * One editable piece of an entry: a task's count, a reward's item.
     *
     * <p>The card edits entries <b>in place</b> -- the row is the editor, not a form -- so a type's
     * fields are described as parts with a kind, and the screen draws and hits them without knowing
     * which type it is looking at. {@link Kind#ITEM} opens the item picker,
     * {@link Kind#FLAG} toggles on the press, and the other two open an inline field.
     */
    public record Part(String path, Kind kind, String label) {

        public enum Kind {
            TEXT,
            INTEGER,
            FLAG,
            ITEM
        }
    }

    /**
     * The editable parts of one task or reward, from its own type.
     *
     * <p>By suffix on the type id rather than by a registry lookup: the parts are about how a type is
     * <i>drawn</i>, and a type this build does not know has no parts -- it gets the raw editor instead.
     * T10's remaining types extend this table the day they register, which is the same deal their
     * panels have.
     */
    public static List<Part> entryParts(String member, JsonObject entry) {
        String type = text(entry, "type", "");
        if ("rewards".equals(member)) {
            if (type.endsWith(":xp")) {
                return List.of(new Part("amount", Part.Kind.INTEGER, "Amount"),
                        new Part("levels", Part.Kind.FLAG, "Levels"));
            }
            return List.of(new Part("item", Part.Kind.ITEM, "Item"),
                    new Part("count", Part.Kind.INTEGER, "Count"));
        }
        if (type.endsWith(":checkmark")) {
            return List.of(new Part("title", Part.Kind.TEXT, "Title"));
        }
        return List.of(new Part("item", Part.Kind.ITEM, "Item"),
                new Part("count", Part.Kind.INTEGER, "Count"),
                new Part("consumeItems", Part.Kind.FLAG, "Consume"));
    }

    /**
     * The settings popover's rows: everything the reader's card does not show.
     *
     * <p>The one place rows are still the shape, and deliberately: a popover of secondary fields is
     * what rows are for. The card itself never uses them -- see the class note on the fourth playtest.
     */
    public static List<InspectRow> settingsRows(JsonObject quest, Set<String> folded) {
        List<InspectRow> rows = new ArrayList<>();
        if (quest == null) {
            return List.copyOf(rows);
        }
        rows.add(InspectRow.heading(PLACEMENT, "Placement"));
        if (!folded.contains(PLACEMENT)) {
            // Numbers and the shape are steppers, not typed fields: a coordinate wants nudging, and a
            // shape wants cycling -- a text box for either is a form, and this popover is not one.
            rows.add(stepper(quest, "x"));
            rows.add(stepper(quest, "y"));
            rows.add(stepper(quest, "shape"));
            rows.add(stepper(quest, "size"));
            rows.add(toggle(quest, "showTitle"));
            rows.add(field(quest, "iconScale"));
        }
        rows.add(InspectRow.heading(RULES, "Rules"));
        if (!folded.contains(RULES)) {
            rows.add(toggle(quest, "repeatable"));
            rows.add(stepper(quest, "repeatCooldownTicks"));
            rows.add(toggle(quest, "sequentialTasks"));
            rows.add(toggle(quest, "invisible"));
            rows.add(field(quest, "exclusiveGroup"));
            rows.add(field(quest, "prerequisiteMode"));
            rows.add(stepper(quest, "minRequired"));
        }
        rows.add(InspectRow.heading(IDENTITY, "Identity extras"));
        if (!folded.contains(IDENTITY)) {
            rows.add(InspectRow.value(VALUE_PREFIX + "id", "Id", text(quest, "id", "")));
            rows.add(InspectRow.field("aliases", "Aliases, comma-separated",
                    String.join(", ", strings(quest, "aliases"))));
        }
        return List.copyOf(rows);
    }

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

    /**
     * The type picker's rows: one per registered type of the member being added to.
     *
     * <p>From the registries, so an addon's type is in the list the day it registers. The label is the
     * type's id as written in a file -- {@code tasked:item} -- because that is the name an author
     * meets in the JSON and in every error message.
     */
    public static List<InspectRow> typeRows(String member) {
        List<InspectRow> rows = new ArrayList<>();
        boolean tasks = !"rewards".equals(member);
        rows.add(InspectRow.heading("h:type",
                tasks ? "Add a task" : "Add a reward"));
        java.util.Set<String> ids = new java.util.TreeSet<>();
        if (tasks) {
            for (ResourceLocation id : TaskTypes.ids()) {
                ids.add(id.toString());
            }
        }
        else {
            for (ResourceLocation id : RewardTypes.ids()) {
                ids.add(id.toString());
            }
        }
        for (String id : ids) {
            rows.add(InspectRow.action(TYPE_PREFIX + id, id));
        }
        return List.copyOf(rows);
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
                for (String fieldName : new TreeSet<>(fieldsOf(type))) {
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
    private static Set<String> fieldsOf(String type) {
        ResourceLocation id = ResourceLocation.tryParse(type);
        if (id == null) {
            return Set.of();
        }
        Set<String> fields = TaskTypes.ids().contains(id) ? TaskTypes.fieldsOf(id) : Set.of();
        if (fields.isEmpty()) {
            fields = RewardTypes.ids().contains(id) ? RewardTypes.fieldsOf(id) : Set.of();
        }
        // `components` is a field of the format but not a row of this panel: it is an object, the
        // picker writes it, and a text field for it would offer to write a *string* where the format
        // holds an object -- a row that corrupts the file it is drawn from. The validator still knows
        // the field (its set is the registry's), so files using it are clean.
        return fields.stream().filter(field -> !"components".equals(field))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
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

    /** A stepper row: the label, and the value its arrows move. */
    private static InspectRow stepper(JsonObject quest, String path) {
        JsonElement found = get(quest, path);
        String value = found != null && found.isJsonPrimitive() ? found.getAsString() : "";
        if (path.equals("shape") && value.isEmpty()) {
            value = "rounded";
        }
        return InspectRow.stepper(path, labelFor(path), value);
    }

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
        if (object.has(member) && object.get(member).isJsonArray()) {
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
