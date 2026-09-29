package dev.ellipog.tasked.quest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ellipog.armature.api.data.Checks;
import dev.ellipog.armature.api.data.JsonDocument;
import dev.ellipog.armature.api.data.Problems;
import dev.ellipog.tasked.quest.reward.RewardTypes;
import dev.ellipog.tasked.quest.task.TaskTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
/**
 * Checks one quest file against the format, reporting everything wrong with it.
 *
 * <h2>Why this exists when Minecraft has codecs</h2>
 *
 * <p>Codecs turn JSON into objects, and are excellent at it. What they cannot do is say <i>where</i>
 * the problem is, and they ignore fields they do not recognise. Both of those matter enormously for
 * a format people edit by hand:
 *
 * <ul>
 *   <li>{@code "titl": "Punch a Tree"} produces a quest with a blank title, no error, and nothing in
 *       any log. The author compares two files character by character to find it.</li>
 *   <li>A codec failure reports {@code Missing field "title"} and a file name — not the line.</li>
 * </ul>
 *
 * <p>This walks the same file <b>before</b> the codec runs, using the same field sets the records
 * declare, so the two cannot drift: if a record gains a field, this allows it in the same commit.
 * A file with a structural error is not decoded at all, which is why one mistake produces one
 * message rather than a validator complaint followed by a codec complaint about the same thing.
 *
 * <h2>What it checks</h2>
 *
 * <p>Structure and values: every required field present, every field a known one, every enum name
 * valid, every id well-formed, every item that actually exists. What it deliberately does <b>not</b>
 * check is anything needing more than this file — duplicate ids across files, dependencies that point
 * at nothing. That is {@link QuestIndex}, because a file on its own cannot know.
 */
public final class QuestValidator {

    // ------------------------------------------------------------------
    // Field sets. Each mirrors a record's declared fields.
    // ------------------------------------------------------------------

    private static final Set<String> ROOT_FIELDS = Set.of("$schema", "version", "chapterGroups");

    private static final Set<String> GROUP_FIELDS = Set.of("id", "title", "description", "aliases", "chapters");

    private static final Set<String> CHAPTER_FIELDS = Set.of(
            "id", "title", "subtitle", "description", "icon", "aliases", "defaultPrerequisiteMode",
            "progressionMode", "defaultConsumeItems", "quests");

    /**
     * A quest's own fields, plus the layout's and the rules'.
     *
     * <p>{@link QuestRules} holds five of these — {@code repeatable}, {@code repeatCooldownTicks},
     * {@code sequentialTasks}, {@code invisible} and {@code exclusiveGroup} — because
     * {@code RecordCodecBuilder} caps out at sixteen components and the flat quest was over it. They
     * are flat in JSON regardless; the grouping is only visible in Java.
     */
    private static final Set<String> QUEST_FIELDS = union(
            union(Set.of(
                    "id", "title", "subtitle", "description", "icon", "aliases", "dependsOn",
                    "prerequisiteMode", "minRequired", "tasks", "rewards"),
                    QuestLayout.FIELDS),
            QuestRules.FIELDS);

    /** Every type currently registered, for the fallback when a task's own type is unknown. */
    private static Set<String> allTaskFields() {
        Set<String> fields = new LinkedHashSet<>();
        for (ResourceLocation id : TaskTypes.ids()) {
            fields.addAll(TaskTypes.fieldsOf(id));
        }
        return fields;
    }

    private static Set<String> allRewardFields() {
        Set<String> fields = new LinkedHashSet<>();
        for (ResourceLocation id : RewardTypes.ids()) {
            fields.addAll(RewardTypes.fieldsOf(id));
        }
        return fields;
    }

    private QuestValidator() {
    }

    /**
     * Validates one file. Reports into {@code problems}, which may already hold problems from other
     * files — the caller decides whether to stop after one file or keep going.
     */
    public static void validate(JsonDocument document, Problems problems) {
        Checks.rejectUnknown(document, "$", ROOT_FIELDS, problems);

        Checks.optionalInt(document, "$.version", problems).ifPresent(version -> {
            if (version < 1) {
                problems.error(document, "$.version", "version must be at least 1, found " + version);
            } else if (version > QuestFile.CURRENT_VERSION) {
                problems.error(document, "$.version", "this file is version " + version
                        + ", but this build understands at most " + QuestFile.CURRENT_VERSION
                        + ". It was probably written by a newer version of Tasked.");
            }
        });

        if (!document.has("$.chapterGroups")) {
            // Not an error. A file with no chapter groups is a legitimate placeholder — an author
            // who has made the file and not started it.
            problems.warn(document, "$", "no \"chapterGroups\" - the file contributes no quests");
            return;
        }

        var groups = Checks.array(document, "$.chapterGroups", problems);
        if (groups == null) {
            return;
        }
        for (int g = 0; g < groups.size(); g++) {
            validateGroup(document, g, problems);
        }
    }

    private static void validateGroup(JsonDocument document, int g, Problems problems) {
        String path = groupPath(g);

        if (!isObject(document, path, problems)) {
            return;
        }
        Checks.rejectUnknown(document, path, GROUP_FIELDS, problems);
        Checks.id(document, path + ".id", problems);
        requiredText(document, path + ".title", problems);
        Checks.optionalStringList(document, path + ".aliases", problems).forEach(alias ->
                checkAlias(document, path + ".aliases", alias, problems));

        if (!document.has(path + ".chapters")) {
            problems.warn(document, path, "no \"chapters\" - this chapter group is empty");
            return;
        }
        var chapters = Checks.array(document, path + ".chapters", problems);
        if (chapters == null) {
            return;
        }
        for (int c = 0; c < chapters.size(); c++) {
            validateChapter(document, g, c, problems);
        }
    }

    private static void validateChapter(JsonDocument document, int g, int c, Problems problems) {
        String path = chapterPath(g, c);

        if (!isObject(document, path, problems)) {
            return;
        }
        Checks.rejectUnknown(document, path, CHAPTER_FIELDS, problems);
        Checks.id(document, path + ".id", problems);
        requiredText(document, path + ".title", problems);
        checkIcon(document, path + ".icon", problems);
        Checks.optionalStringList(document, path + ".aliases", problems).forEach(alias ->
                checkAlias(document, path + ".aliases", alias, problems));

        // The codec defaults this, so its absence is not a problem — but a misspelled value is, and
        // that is the case an author actually hits.
        if (document.has(path + ".defaultPrerequisiteMode")) {
            Checks.optionalString(document, path + ".defaultPrerequisiteMode", problems)
                    .ifPresent(name -> checkEnum(document, path + ".defaultPrerequisiteMode", name,
                            PrerequisiteMode.class, problems));
        }
        if (document.has(path + ".progressionMode")) {
            Checks.optionalString(document, path + ".progressionMode", problems)
                    .ifPresent(name -> checkEnum(document, path + ".progressionMode", name,
                            ProgressionMode.class, problems));
        }
        if (document.has(path + ".defaultConsumeItems")) {
            Checks.optionalBool(document, path + ".defaultConsumeItems", problems);
        }

        if (!document.has(path + ".quests")) {
            problems.warn(document, path, "no \"quests\" - this chapter is empty");
            return;
        }
        var quests = Checks.array(document, path + ".quests", problems);
        if (quests == null) {
            return;
        }
        if (quests.isEmpty()) {
            problems.warn(document, path + ".quests", "this chapter's quest list is empty");
        }
        for (int q = 0; q < quests.size(); q++) {
            validateQuest(document, g, c, q, problems);
        }
    }

    private static void validateQuest(JsonDocument document, int g, int c, int q, Problems problems) {
        String path = questPath(g, c, q);

        if (!isObject(document, path, problems)) {
            return;
        }
        Checks.rejectUnknown(document, path, QUEST_FIELDS, problems);

        Checks.id(document, path + ".id", problems);
        requiredText(document, path + ".title", problems);
        checkText(document, path + ".subtitle", problems);
        checkTextList(document, path + ".description", problems);
        checkIcon(document, path + ".icon", problems);

        Checks.optionalStringList(document, path + ".aliases", problems).forEach(alias ->
                checkAlias(document, path + ".aliases", alias, problems));

        if (document.has(path + ".prerequisiteMode")) {
            Checks.optionalString(document, path + ".prerequisiteMode", problems)
                    .ifPresent(name -> checkEnum(document, path + ".prerequisiteMode", name,
                            PrerequisiteMode.class, problems));
        }

        if (document.has(path + ".repeatable")) {
            Checks.optionalBool(document, path + ".repeatable", problems);
        }
        if (document.has(path + ".invisible")) {
            Checks.optionalBool(document, path + ".invisible", problems);
        }
        if (document.has(path + ".sequentialTasks")) {
            Checks.optionalBool(document, path + ".sequentialTasks", problems);
        }
        if (document.has(path + ".repeatCooldownTicks")) {
            Checks.optionalInt(document, path + ".repeatCooldownTicks", problems).ifPresent(ticks -> {
                if (ticks < 0) {
                    problems.error(document, path + ".repeatCooldownTicks",
                            "a cooldown may not be negative, found " + ticks + " - use 0 for none");
                }
            });
            // A cooldown on a quest that can only be done once is not an error, but it does nothing,
            // and silently doing nothing is what makes a file hard to reason about.
            if (!boolAt(document, path + ".repeatable")) {
                problems.warn(document, path + ".repeatCooldownTicks",
                        "a cooldown only means something on a repeatable quest; this quest is not repeatable");
            }
        }
        if (document.has(path + ".exclusiveGroup")) {
            Checks.optionalString(document, path + ".exclusiveGroup", problems).ifPresent(group -> {
                if (group.isBlank()) {
                    problems.error(document, path + ".exclusiveGroup",
                            "an exclusive group name may not be empty - remove the field instead");
                }
            });
        }
        if (document.has(path + ".shape")) {
            Checks.optionalString(document, path + ".shape", problems)
                    .ifPresent(name -> checkEnum(document, path + ".shape", name, QuestShape.class, problems));
        }
        if (document.has(path + ".size")) {
            Checks.optionalInt(document, path + ".size", problems).ifPresent(size -> {
                if (size < 16 || size > 512) {
                    problems.error(document, path + ".size",
                            "size must be between 16 and 512, found " + size
                                    + (size > 512 ? " - did you mean " + (size / 10) + "?" : ""));
                }
            });
        }

        if (document.has(path + ".minRequired")) {
            Checks.optionalInt(document, path + ".minRequired", problems).ifPresent(count -> {
                if (count < 0) {
                    problems.error(document, path + ".minRequired",
                            "minRequired may not be negative, found " + count);
                }
            });
        }

        checkDependencies(document, path + ".dependsOn", problems);
        checkTasks(document, path + ".tasks", problems);
        checkRewards(document, path + ".rewards", problems);
    }

    // ------------------------------------------------------------------
    // Tasks and rewards
    // ------------------------------------------------------------------

    private static void checkTasks(JsonDocument document, String path, Problems problems) {
        if (!document.has(path)) {
            return;
        }
        var array = Checks.array(document, path, problems);
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            checkTask(document, path + "[" + i + "]", problems);
        }
    }

    private static void checkTask(JsonDocument document, String path, Problems problems) {
        if (!isObject(document, path, problems)) {
            return;
        }
        Optional<ResourceLocation> type = readType(document, path, TaskTypes.ids(), "quest task", problems);
        if (type.isEmpty()) {
            // Already reported. Fall back to the union of every task type's fields, so that one bad
            // type does not then produce a complaint about every field on the task.
            Checks.rejectUnknown(document, path,
                    union(union(allTaskFields(), TaskCommon.FIELDS), Set.of("type")), problems);
            return;
        }

        // Every field any task type declares, plus this one's own and the common ones. Deliberately
        // a union rather than just this type's fields: a task may legitimately be changed from one
        // type to another, and flagging the leftover fields of the old type on every file would be
        // noise. What this catches is a field no type anywhere understands, which is the typo case.
        Checks.rejectUnknown(document, path,
                union(union(allTaskFields(), TaskCommon.FIELDS), Set.of("type")), problems);

        if (document.has(path + ".optional")) {
            Checks.optionalBool(document, path + ".optional", problems);
        }
        if (document.has(path + ".autoSubmitTicks")) {
            Checks.optionalInt(document, path + ".autoSubmitTicks", problems).ifPresent(ticks -> {
                if (ticks < 1 || ticks > 72000) {
                    problems.error(document, path + ".autoSubmitTicks",
                            "must be between 1 and 72000 ticks, found " + ticks);
                }
            });
        }

        // Every built-in task type that names an item does so under "item", so validating that field
        // pair generically covers item tasks and anything an addon adds that follows the convention.
        // Note this checks values only: whether the task's own fields are all known was decided above,
        // by the task type's field set.
        if (document.has(path + ".item")) {
            checkItem(document, path, problems);
        }
    }

    private static void checkRewards(JsonDocument document, String path, Problems problems) {
        if (!document.has(path)) {
            return;
        }
        var array = Checks.array(document, path, problems);
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            checkReward(document, path + "[" + i + "]", problems);
        }
    }

    private static void checkReward(JsonDocument document, String path, Problems problems) {
        if (!isObject(document, path, problems)) {
            return;
        }
        Optional<ResourceLocation> type = readType(document, path, RewardTypes.ids(), "quest reward", problems);
        if (type.isEmpty()) {
            Checks.rejectUnknown(document, path, union(allRewardFields(), Set.of("type")), problems);
            return;
        }
        // The union of every reward type's fields, for the reason given in checkTask.
        Checks.rejectUnknown(document, path, union(allRewardFields(), Set.of("type")), problems);

        if (document.has(path + ".item")) {
            checkItem(document, path, problems);
        }
    }

    // ------------------------------------------------------------------
    // Shared pieces
    // ------------------------------------------------------------------

    /**
     * Reads and resolves a {@code "type"} field.
     *
     * <p>Reporting the unknown-type case here rather than leaving it to the codec is what produces a
     * usable message. The codec's own dispatch does list the valid types — but by then the file has a
     * structural error and is not decoded at all, so this is the only message the author ever sees,
     * which is exactly why it should be the good one.
     *
     * <p>The caller passes which registry it expects, so a task cannot claim a reward's type and be
     * told that every one of its fields is unknown.
     */
    private static Optional<ResourceLocation> readType(JsonDocument document, String path,
                                                       Set<ResourceLocation> known, String kind,
                                                       Problems problems) {
        String typePath = path + ".type";
        Optional<String> raw = Checks.optionalString(document, typePath, problems);
        if (raw.isEmpty()) {
            problems.error(document, typePath, "missing required field \"type\"");
            return Optional.empty();
        }

        ResourceLocation id = ResourceLocation.tryParse(raw.get());
        if (id == null) {
            problems.error(document, typePath, "\"" + raw.get()
                    + "\" is not a valid id; expected something like \"tasked:item\"");
            return Optional.empty();
        }

        if (!known.contains(id)) {
            problems.error(document, typePath, "unknown " + kind + " type \"" + raw.get() + "\"\n"
                    + "    known types: " + String.join(", ",
                            known.stream().map(ResourceLocation::toString).sorted().toList()));
            return Optional.empty();
        }
        return Optional.of(id);
    }

    /**
     * Checks an icon object, which is exactly an item reference and nothing else.
     *
     * <p>Separate from {@link #checkItem} because this one owns the object and can therefore reject
     * unknown fields on it, while a task's {@code "item"} fields live on the task itself — where
     * rejecting against the item's field set would flag {@code "type"} and {@code "optional"} as
     * unknown. That mistake would have made every task in every file report two spurious errors.
     */
    private static void checkIcon(JsonDocument document, String path, Problems problems) {
        if (!document.has(path)) {
            return;
        }
        if (!isObject(document, path, problems)) {
            return;
        }
        Checks.rejectUnknown(document, path, ItemRef.FIELDS, problems);
        checkItem(document, path, problems);
    }

    /**
     * Checks the values of an {@code "item"} / {@code "count"} pair that sits flat on a larger object.
     *
     * <p>Deliberately does <b>not</b> reject unknown fields: whether the enclosing object has the right
     * fields is a question only its own codec's field set can answer, and its caller has already asked
     * it.
     *
     * <p>Existence is checked here, by reading the JSON, rather than from the decoded records. That
     * keeps every content check in one phase, and means an unknown item is reported at its own line and
     * column instead of as part of a batch of cross-file complaints afterwards.
     *
     * <p>An item whose mod is not installed is an error rather than a warning: a quest requiring it can
     * never be completed, and a pack that silently ships one has a broken questline. The message says
     * which mod is probably missing, because that is the actual fix.
     */
    private static void checkItem(JsonDocument document, String path, Problems problems) {
        String itemPath = path + ".item";
        Optional<String> raw = Checks.string(document, itemPath, problems);
        if (raw.isEmpty()) {
            return;
        }

        ResourceLocation id = ResourceLocation.tryParse(raw.get());
        if (id == null) {
            problems.error(document, itemPath, "\"" + raw.get()
                    + "\" is not a valid item id; expected something like \"minecraft:oak_log\"");
            return;
        }

        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            String hint = id.getNamespace().equals("minecraft")
                    ? " - check the spelling; minecraft: has no such item"
                    : " - the mod \"" + id.getNamespace()
                      + "\" is probably not installed, or is installed on one side only";
            problems.error(document, itemPath, "there is no item " + id + hint);
            // Note the messages here are deliberately ASCII. These lines end up in server logs, which
            // get read through terminals and editors with every encoding going; an em dash renders as
            // mojibake in at least one of them, and the message is worth more than the typography.
        }

        Checks.optionalInt(document, path + ".count", problems).ifPresent(count -> {
            if (count < 1) {
                problems.error(document, path + ".count", "count must be at least 1, found " + count
                        + " - to require nothing, remove the task instead");
            }
        });
    }

    private static void checkDependencies(JsonDocument document, String path, Problems problems) {
        var ids = Checks.optionalStringList(document, path, problems);
        for (int i = 0; i < ids.size(); i++) {
            String elementPath = path + "[" + i + "]";
            String candidate = ids.get(i);
            if (candidate.isEmpty()) {
                problems.error(document, elementPath, "a dependency id may not be empty");
                continue;
            }
            boolean wellFormed = candidate.length() <= 64;
            for (int j = 0; wellFormed && j < candidate.length(); j++) {
                char ch = candidate.charAt(j);
                wellFormed = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_';
            }
            if (!wellFormed) {
                problems.error(document, elementPath, "'" + candidate
                        + "' is not a valid quest id; only lowercase letters, digits and underscores are allowed");
            }
        }
    }

    /** A text value, in either of its two forms. */
    /**
     * A required text field. Separate from {@link #checkText} only so the message can name it.
     *
     * <p>Was "missing required field" with no name, which is technically true and practically
     * useless at a line and column pointing into an object with eight fields.
     */
    private static void requiredText(JsonDocument document, String path, Problems problems) {
        if (!document.has(path)) {
            problems.error(document, path, "missing required field " + Checks.nameOf(path));
            return;
        }
        checkText(document, path, problems);
    }

    private static void checkText(JsonDocument document, String path, Problems problems) {
        JsonElement element = document.get(path).orElse(null);
        if (element == null) {
            return;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            if (element.getAsString().isBlank()) {
                problems.warn(document, path, "this text is empty, so nothing will be shown");
            }
            return;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            Checks.rejectUnknown(document, path, Set.of("translate", "fallback"), problems);
            if (!object.has("translate")) {
                problems.error(document, path,
                        "a translatable text needs a \"translate\" key, e.g. "
                                + "{ \"translate\": \"quest.tasked.example\", \"fallback\": \"Example\" }");
            } else {
                Checks.string(document, path + ".translate", problems);
            }
            if (object.has("fallback")) {
                Checks.string(document, path + ".fallback", problems);
            } else {
                problems.warn(document, path + ".translate",
                        "no \"fallback\" - if a translation is ever missing this will show the raw key");
            }
            return;
        }
        problems.error(document, path, "expected text: either a string, or { \"translate\": ..., "
                + "\"fallback\": ... } - but found " + Checks.kindOf(element));
    }

    private static void checkTextList(JsonDocument document, String path, Problems problems) {
        var array = Checks.optionalArray(document, path, problems);
        if (array.isEmpty()) {
            return;
        }
        for (int i = 0; i < array.get().size(); i++) {
            checkText(document, path + "[" + i + "]", problems);
        }
    }

    /**
     * An alias has the same rules as an id, because that is what it is: another name for the same
     * thing, looked up the same way.
     */
    private static void checkAlias(JsonDocument document, String path, String alias, Problems problems) {
        if (alias.isEmpty() || alias.length() > 64) {
            problems.error(document, path, "an alias must be between 1 and 64 characters");
            return;
        }
        for (int i = 0; i < alias.length(); i++) {
            char c = alias.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')) {
                problems.error(document, path, "alias '" + alias + "' is not valid; only lowercase letters, "
                        + "digits and underscores are allowed");
                return;
            }
        }
    }

    private static <E extends Enum<E>> void checkEnum(JsonDocument document, String path, String value,
                                                      Class<E> type, Problems problems) {
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(value)) {
                return;
            }
        }
        Set<String> valid = new LinkedHashSet<>();
        for (E constant : type.getEnumConstants()) {
            valid.add(constant.name().toLowerCase(java.util.Locale.ROOT));
        }
        problems.error(document, path, "'" + value + "' is not one of: " + String.join(", ", valid));
    }

    /** A boolean field's value, defaulting to false. For cross-field checks that need one. */
    private static boolean boolAt(JsonDocument document, String path) {
        return document.get(path)
                .filter(element -> element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean())
                .map(element -> element.getAsBoolean())
                .orElse(false);
    }

    private static boolean isObject(JsonDocument document, String path, Problems problems) {
        JsonElement element = document.get(path).orElse(null);
        if (element == null) {
            return false;
        }
        if (!element.isJsonObject()) {
            problems.error(document, path, "expected an object, found " + Checks.kindOf(element));
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Paths
    // ------------------------------------------------------------------

    // Shared with QuestIndex so that both agree on where a quest is. If one of these changes, the
    // other's error messages move with it rather than silently pointing somewhere else.

    public static String groupPath(int g) {
        return "$.chapterGroups[" + g + "]";
    }

    public static String chapterPath(int g, int c) {
        return groupPath(g) + ".chapters[" + c + "]";
    }

    public static String questPath(int g, int c, int q) {
        return chapterPath(g, c) + ".quests[" + q + "]";
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.addAll(b);
        return out;
    }
}
