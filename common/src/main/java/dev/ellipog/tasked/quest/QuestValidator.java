package dev.ellipog.tasked.quest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
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

    /**
     * The field sets, <b>taken from the records that declare the fields</b>.
     *
     * <p>These used to be copies written out here, under a comment claiming they "mirror a record's
     * declared fields" — which is a description of an intention rather than a mechanism. Adding
     * {@code collapsedByDefault} to {@code ChapterGroup} made the cost concrete: it is a two-file edit
     * where forgetting the second half produces a field the <b>codec reads and the validator calls
     * unknown</b>, so the file loads with checking disabled and is refused with it on. The author's only
     * clue would be which build they had.
     *
     * <p>So the records own their sets, and a version-1 inline group and a version-2 {@code group.json}
     * allow the same fields by construction rather than by two lists agreeing. Version 1 and version 2
     * describe <i>the same objects</i>; only the file layout differs, so the fields a group may carry
     * cannot depend on which format it was written in.
     */
    private static final Set<String> GROUP_FIELDS = ChapterGroup.FIELDS;

    private static final Set<String> CHAPTER_FIELDS = Chapter.FIELDS;

    /**
     * What a version-2 per-kind file may hold at its root: the kind's own fields, plus {@code $schema}.
     *
     * <p>And deliberately <b>no {@code version}</b>. A per-kind file cannot be anything but version 2 —
     * the folder layout settles it, the same way a flat {@code .json} at the root is version 1 by
     * position rather than by the number inside it — so a version field would be a second answer to a
     * question that is already answered, and a second answer is a thing that can disagree.
     */
    private static final Set<String> GROUP_DOCUMENT_FIELDS = withSchema(GROUP_FIELDS);

    private static final Set<String> CHAPTER_DOCUMENT_FIELDS = withSchema(CHAPTER_FIELDS);

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
     * Validates a version-2 document that is one whole chapter group.
     *
     * <p>The version-2 counterpart of {@link #validate}, and the difference is entirely in where things
     * are: a version-1 file holds {@code chapterGroups[]} and the group is at {@code $.chapterGroups[0]},
     * while a {@code group.json} <i>is</i> the group and its fields are at {@code $}. The checks are the
     * same checks at a different path, which is why they are shared rather than written twice.
     */
    public static void validateGroupDocument(JsonDocument document, Problems problems) {
        validateGroupAt(document, "$", problems, false, GROUP_DOCUMENT_FIELDS);
    }

    /** Validates a version-2 document that is one whole chapter. */
    public static void validateChapterDocument(JsonDocument document, Problems problems) {
        validateChapterAt(document, "$", problems, false, CHAPTER_DOCUMENT_FIELDS);
    }

    /**
     * Validates a version-2 document that is one whole quest.
     *
     * <p>No "inline children" distinction to make: a quest has no children, so its field check is the
     * same one version 1 uses, at a different root.
     */
    public static void validateQuestDocument(JsonDocument document, Problems problems) {
        validateQuestAt(document, "$", problems, withSchema(QUEST_FIELDS));
    }

    /**
     * Validates one version-1 file. Reports into {@code problems}, which may already hold problems from
     * other files — the caller decides whether to stop after one file or keep going.
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

    /** A version-1 group, where the whole tree is one document. */
    private static void validateGroup(JsonDocument document, int g, Problems problems) {
        validateGroupAt(document, groupPath(g), problems, true, GROUP_FIELDS);
    }

    /**
     * A group's own fields, at whatever path it sits at, plus its child list in whichever shape applies.
     *
     * <h2>The allowed set is a parameter, and that is the fix for a real duplicate</h2>
     *
     * <p>The unknown-field check is the one thing the two layouts genuinely disagree about: a version-1
     * group sits at {@code $.chapterGroups[0]} inside a file that also holds {@code version}, while a
     * {@code group.json} is the group and its whole document is the object — so it may carry
     * {@code $schema} and may not carry {@code version}. Everything else about a group is the same
     * either way, which is why the body is shared.
     *
     * <p>It was not, at first. The check ran in the wrapper <i>and</i> in this method, at the same path
     * with two different sets — so {@code $schema} was allowed by the outer call and refused by the
     * inner one, and a perfectly good {@code group.json} reported "unknown field $schema" with a field
     * list that plainly contained it. Two descriptions of one field set, one level apart, disagreeing:
     * the same fault this file's own class comment is about, committed inside the fix for it.
     *
     * @param inlineChildren true for version 1, where {@code chapters} holds chapter <i>objects</i>;
     *                       false for version 2, where it holds the <b>names</b> of chapter folders
     * @param allowedFields  what this level may hold. The caller knows whether it is at a file root.
     */
    private static void validateGroupAt(JsonDocument document, String path, Problems problems,
                                        boolean inlineChildren, Set<String> allowedFields) {
        if (!isObject(document, path, problems)) {
            return;
        }
        Checks.rejectUnknown(document, path, allowedFields, problems);
        Checks.id(document, path + ".id", problems);
        requiredText(document, path + ".title", problems);
        // Checked, and it was not until the codec rejected one. A field the validator does not look at
        // is a field whose mistakes arrive as a codec message at line 1 column 1 — which is exactly what
        // happened to the first group description written as a list of lines.
        //
        // `checkTextOrList`, not `checkTextList`, because this is the one description whose codec
        // takes either shape -- see the method. Using the list-only check here was the validator
        // being *stricter than the format*: a bare string decoded fine and was then failed by the
        // validator, so the same file loaded with the check disabled and refused with it on. The
        // author's only clue would have been which build they had.
        checkTextOrList(document, path + ".description", problems);
        Checks.optionalStringList(document, path + ".aliases", problems).forEach(alias ->
                checkAlias(document, path + ".aliases", alias, problems));
        if (document.has(path + ".collapsedByDefault")) {
            Checks.optionalBool(document, path + ".collapsedByDefault", problems);
        }

        if (!document.has(path + ".chapters")) {
            problems.warn(document, path, "no \"chapters\" - this chapter group is empty");
            return;
        }
        if (inlineChildren) {
            var chapters = Checks.array(document, path + ".chapters", problems);
            if (chapters == null) {
                return;
            }
            for (int c = 0; c < chapters.size(); c++) {
                validateChapterAt(document, path + ".chapters[" + c + "]", problems, true, CHAPTER_FIELDS);
            }
        }
        else {
            checkNameList(document, path + ".chapters", "chapter", problems);
        }
    }

    /**
     * A manifest's list of child names: an array of single path segments, in order.
     *
     * <h2>Shape here, resolution at load, and one message either way</h2>
     *
     * <p>This checks that the list is an array of strings and says so when it is not. It deliberately
     * does <b>not</b> check that a name is a bare segment, or that the thing it names exists, or that the
     * folder it resolves against holds nothing unlisted. All three are {@link QuestFiles}' business — it
     * is the step that holds the folder the names resolve against — and it reports each of them with a
     * message that names the declared string and the path it failed to resolve to.
     *
     * <p>Doing any of it here as well would mean one missing chapter producing two messages about the
     * same absence, which is the fault {@link #validate} exists to avoid: the whole reason a file with a
     * structural error is not decoded is so that one mistake produces one message.
     *
     * <p>The <i>message</i> is worth as much as the check, and it names the format change rather than
     * describing a type error. {@code "chapters": [{"id": "one"}]} is a real thing to write — an element
     * copied straight out of a version-1 file — and "expected a string, found an object" would leave the
     * author looking at a field that looks right to them.
     */
    private static void checkNameList(JsonDocument document, String path, String what, Problems problems) {
        var names = Checks.array(document, path, problems);
        if (names == null) {
            return;
        }
        if (names.isEmpty()) {
            problems.warn(document, path, "this " + what + " list is empty");
            return;
        }
        for (int i = 0; i < names.size(); i++) {
            JsonElement child = names.get(i);
            if (child.isJsonPrimitive() && child.getAsJsonPrimitive().isString()) {
                continue;
            }
            problems.error(document, path + "[" + i + "]", "expected the name of a " + what
                    + " as a string, found " + Checks.kindOf(child)
                    + " - a manifest lists the names of the folders and files sitting beside it, in the"
                    + " order they should appear. This is the version-2 layout: what was nested inline"
                    + " is now a folder of its own, named here.");
        }
    }

    /** A version-1 chapter, where the whole tree is one document. */
    private static void validateChapter(JsonDocument document, int g, int c, Problems problems) {
        validateChapterAt(document, chapterPath(g, c), problems, true, CHAPTER_FIELDS);
    }

    /**
     * A chapter's own fields, at whatever path it sits at, plus its quest list in whichever shape.
     *
     * @param inlineChildren true for version 1, where {@code quests} holds quest <i>objects</i>; false
     *                       for version 2, where it holds the <b>file names</b> of quests. The order of
     *                       that list is the progression of a {@code LINEAR} chapter, so it is the one
     *                       list in the format whose order is load-bearing rather than presentational.
     * @param allowedFields  what this level may hold. See {@link #validateGroupAt} for why this is a
     *                       parameter rather than a constant read here.
     */
    private static void validateChapterAt(JsonDocument document, String path, Problems problems,
                                          boolean inlineChildren, Set<String> allowedFields) {
        if (!isObject(document, path, problems)) {
            return;
        }
        Checks.rejectUnknown(document, path, allowedFields, problems);
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

        // A theme name is checked for being a non-empty string and nothing more, and that stopping
        // point is the point of it: the theme catalogue is a <b>client</b> concept, and this validator
        // runs on the server too. A dedicated server has no appearance and no themes, so teaching it
        // the list would be a server knowing something only a client can act on — the same shape of
        // mistake as a command that reaches for a client class. The name is checked where it is used,
        // by the client that could not honour it, and the message there names the chapter.
        //
        // An empty string is worth rejecting because the codec would accept it and `Optional.of("")`
        // is not "no opinion" — it is a chapter asking for a theme called nothing, which can only be
        // reported as unrecognised.
        if (document.has(path + ".theme")) {
            Checks.optionalString(document, path + ".theme", problems).ifPresent(name -> {
                if (name.isBlank()) {
                    problems.error(document, path + ".theme",
                            "a theme name may not be empty - remove the field to use the player's own theme");
                }
            });
        }

        if (!document.has(path + ".quests")) {
            problems.warn(document, path, "no \"quests\" - this chapter is empty");
            return;
        }
        if (inlineChildren) {
            var quests = Checks.array(document, path + ".quests", problems);
            if (quests == null) {
                return;
            }
            if (quests.isEmpty()) {
                problems.warn(document, path + ".quests", "this chapter's quest list is empty");
            }
            for (int q = 0; q < quests.size(); q++) {
                validateQuestAt(document, path + ".quests[" + q + "]", problems, QUEST_FIELDS);
            }
        }
        else {
            checkNameList(document, path + ".quests", "quest", problems);
        }
    }

    /** A version-1 quest, where the whole tree is one document. */
    private static void validateQuest(JsonDocument document, int g, int c, int q, Problems problems) {
        validateQuestAt(document, questPath(g, c, q), problems, QUEST_FIELDS);
    }

    /**
     * A quest's own fields, at whatever path it sits at.
     *
     * <p>Its path is the only thing either layout changes — a quest has no children either way, so
     * unlike a group or a chapter there is no child-list shape to branch on. The allowed set is still a
     * parameter for the same reason as the other two: at a version-2 file root the document may carry
     * {@code $schema}, and one level down it may not.
     */
    private static void validateQuestAt(JsonDocument document, String path, Problems problems,
                                       Set<String> allowedFields) {
        if (!isObject(document, path, problems)) {
            return;
        }
        Checks.rejectUnknown(document, path, allowedFields, problems);

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
        if (document.has(path + ".showTitle")) {
            Checks.optionalBool(document, path + ".showTitle", problems);
        }
        if (document.has(path + ".iconScale")) {
            checkIconScale(document, path + ".iconScale", problems);
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

    /**
     * Checks the icon scale, which is a fraction and therefore not a field {@link Checks} can read.
     *
     * <p>Read from the JSON directly rather than through a helper, because {@code Checks} has no
     * {@code optionalDouble} and inventing one in Armature for a single consumer would be library work
     * for a caller's convenience. Everything else about this is the same shape as the numeric checks
     * above: read the value, then say what is wrong with it in the terms the author wrote.
     *
     * <p>Bounds come from {@link QuestShape}, which is where the geometry they describe lives — and
     * therefore what the codec uses too. Two numbers in two places is the mistake this whole file is
     * arranged to avoid, and the honest reason a message can say "the largest that fits" and be right.
     */
    private static void checkIconScale(JsonDocument document, String path, Problems problems) {
        JsonElement element = document.get(path).orElse(null);
        if (element == null) {
            return;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            problems.error(document, path, "expected a number between "
                    + QuestShape.MIN_ICON_SCALE + " and " + QuestShape.MAX_ICON_SCALE
                    + ", found " + Checks.kindOf(element));
            return;
        }

        double scale = element.getAsDouble();
        if (scale < QuestShape.MIN_ICON_SCALE || scale > QuestShape.MAX_ICON_SCALE) {
            problems.error(document, path, "iconScale must be between "
                    + QuestShape.MIN_ICON_SCALE + " and " + QuestShape.MAX_ICON_SCALE + ", found " + scale
                    + (scale > QuestShape.MAX_ICON_SCALE
                            ? " - 1.0 is the largest icon that fits in the node"
                            : " - below a quarter the item is a smudge; omit the field for the default ("
                              + QuestLayout.DEFAULT_ICON_SCALE + ")"));
        }
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

        // And the type's own codec gets the last word. The checks above are about names and value
        // shapes; whether the fields that are there *make a value* is the codec's question, and it is
        // the difference between a save that refuses and a file the loader drops at the next read.
        // This gap had a live defect behind it -- deleting an item task's "item" saved cleanly and
        // took the quest out of the tree -- and the loader's own message asked for it to be closed.
        type.ifPresent(id -> TaskTypes.codecOf(id)
                .ifPresent(codec -> decodeEntry(document, path, id, codec, problems)));
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

        // The reward half of the codec check in checkTask, for the same defect and the same reason.
        type.ifPresent(id -> RewardTypes.codecOf(id)
                .ifPresent(codec -> decodeEntry(document, path, id, codec, problems)));
    }

    /**
     * Asks the entry's own codec whether its fields make a value.
     *
     * <p>This is the backstop the field-name checks cannot be: a codec's required fields are not
     * derivable from a field list, and their absence is invisible to every rule above -- an absent
     * field is not an unknown one. The failure is reported at the entry's own path with the codec's
     * message under it, which names the key that is missing.
     *
     * <p>Called only for a type the validator already knows, because the loader's dispatch cannot
     * decode an unknown type at all and that case has its own message above; this asks the narrower
     * question on purpose, so an addon's type is not double-reported.
     */
    private static void decodeEntry(JsonDocument document, String path, ResourceLocation id,
                                    MapCodec<?> codec, Problems problems) {
        document.get(path).ifPresent(entry -> codec.codec().parse(JsonOps.INSTANCE, entry)
                .error().ifPresent(error -> problems.error(document, path,
                        "these fields do not form a " + id + ":\n    "
                                + error.message().replace("\n", "\n    "))));
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

        // And the reference's own codec -- the icon's half of the entry check in checkTask: a
        // component patch the codec cannot read would otherwise reach the loader, which skips the
        // whole quest over it. The codec's own message names what it was unhappy about.
        document.get(path).ifPresent(object -> ItemRef.CODEC.parse(JsonOps.INSTANCE, object)
                .error().ifPresent(error -> problems.error(document, path,
                        "this is not a usable item reference:\n    "
                                + error.message().replace("\n", "\n    "))));
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
     * <p>An item whose mod is not installed is a <b>warning</b>, and that is a reversal worth its
     * sentence: it was an error, "a quest requiring it can never be completed" -- and an error skips
     * the file, so a pack whose mod was removed lost the entire quest, node and all, with the id still
     * sitting in the file. A missing mod is often temporary (an update, a server-side absence), so the
     * id is kept, the quest loads, and the row says it is missing. The message still names the mod,
     * because that is the actual fix.
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
            problems.warn(document, itemPath, "there is no item " + id + hint
                    + ". The id is kept and the quest still loads; its row will say the item is missing");
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
        checkText(document, path, problems, true);
    }

    /**
     * The text check, with the blank warning optional.
     *
     * @param warnIfBlank whether an empty string is worth reporting. True for a title or a subtitle —
     *     an empty one is a quest with no name. False inside a list of paragraphs, where a blank entry
     *     is how an author writes a line break: each entry is drawn as its own paragraph, so there is
     *     no other way to say "skip a line". That distinction was found the hard way, by the shipped
     *     questlines warning on every deliberate blank line in them.
     */
    private static void checkText(JsonDocument document, String path, Problems problems, boolean warnIfBlank) {
        JsonElement element = document.get(path).orElse(null);
        if (element == null) {
            return;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            if (warnIfBlank && element.getAsString().isBlank()) {
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

    /**
     * A list of paragraphs, where a blank entry is legal and a list that is *all* blank is not.
     *
     * <p>An empty entry means a blank line, which is the only way an author can write one: each entry
     * is drawn as its own paragraph. So the per-paragraph blank warning is off here, and the question
     * worth asking moves to the whole list — a description where nothing at all would be shown.
     *
     * <p>Found by the shipped questlines reporting four warnings each on their deliberate line breaks,
     * which is the worst possible shape for a check to take: it was not wrong about the fact, it was
     * wrong about whether the fact was a problem, and a warning like that teaches an author to skim
     * output.
     */
    private static void checkTextList(JsonDocument document, String path, Problems problems) {
        var array = Checks.optionalArray(document, path, problems);
        if (array.isEmpty()) {
            return;
        }

        boolean anyText = false;
        for (int i = 0; i < array.get().size(); i++) {
            JsonElement element = array.get().get(i);
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                    || !element.getAsString().isBlank()) {
                anyText = true;
            }
            checkText(document, path + "[" + i + "]", problems, false);
        }

        if (!array.get().isEmpty() && !anyText) {
            problems.warn(document, path, "every paragraph here is empty, so nothing will be shown");
        }
    }

    /**
     * A description the format accepts in either shape: a list of paragraphs, or one bare string.
     *
     * <p>Only {@link ChapterGroup} has this union, and the union is the codec's own doing — its javadoc
     * argues why. The validator has to accept both <b>because the codec does</b>. A validator stricter
     * than the format is the worst of both worlds: the file decodes, so the format says it is fine, and
     * is then refused, so the tool says it is not — and the author has no way to tell which is
     * authoritative. This is the same fault as a validator that is too *lax*, just pointing the other
     * way: in both cases the two descriptions of the format have drifted and the message names the
     * wrong thing.
     *
     * <p>Found by a test written to assert both spellings work. The test believed the codec and failed
     * against the validator, which is the right way round for that to be discovered — the codec is the
     * thing that decides whether a file loads.
     */
    private static void checkTextOrList(JsonDocument document, String path, Problems problems) {
        JsonElement element = document.get(path).orElse(null);
        if (element == null) {
            return;
        }
        if (element.isJsonArray()) {
            checkTextList(document, path, problems);
            return;
        }
        // A bare string is one paragraph, so a blank one means nothing is shown at all — the same
        // question `checkTextList` asks of a list that is entirely blank, asked of the one-paragraph
        // case. Which is why this uses the warning-on-blank form rather than the list's tolerant one.
        checkText(document, path, problems);
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

    /**
     * A field set plus {@code $schema}, for the root of a version-2 per-kind document.
     *
     * <p>Every kind allows it and none reads it: it is an editor's pointer at the schema in
     * {@code _schema/}, and the folder holding those is skipped by name so the walk never trips over it.
     */
    private static Set<String> withSchema(Set<String> fields) {
        Set<String> out = new HashSet<>(fields);
        out.add("$schema");
        return out;
    }

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
