package dev.ellipog.tenet.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import dev.ellipog.tenet.quest.TreeRefresh;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Writing an {@link EditorOp} down, reading one back, and applying it.
 *
 * <h2>The three parts, and why they are one class</h2>
 *
 * <p>{@link #write} is what the client sends, {@link #read} is what the server makes of it, and {@link #apply}
 * is what the model does about it. They belong together because they are one decision — what an edit *is*, on
 * the wire and in the files — and because the failure that would hurt is the three of them disagreeing: an op
 * the client can write and the server cannot read is an edit that silently does nothing, and an op the reader
 * accepts and the applier refuses is a message that contradicts itself.
 *
 * <h2>Validation happens on apply, and a refusal costs nothing</h2>
 *
 * <p>{@code apply} mutates the model, saves through {@link QuestEditor#save()} — which validates every file in
 * the chapter with the loader's own validator and writes <b>nothing at all</b> if any of them would not load —
 * and undoes itself when that save refuses. That is the whole of "validate on apply": an op that would leave
 * an unloadable chapter is reported with the validator's own messages and costs one undo, so there is no state
 * in which the files on disk and the model in memory disagree.
 */
public final class EditorOps {

    private EditorOps() {
    }

    // ------------------------------------------------------------------
    // The wire
    // ------------------------------------------------------------------

    /** An op as the wire holds it: a kind, and the arguments that kind has. */
    public static JsonObject write(EditorOp op) {
        Objects.requireNonNull(op, "op");
        JsonObject json = new JsonObject();
        switch (op) {
            case EditorOp.SetField set -> {
                json.addProperty("kind", "field");
                json.addProperty("quest", set.id());
                json.addProperty("path", set.path());
                // Written inline rather than as a quoted fragment: the value *is* JSON, and a string holding
                // JSON inside a string holding JSON is two escapes waiting to disagree.
                json.add("value", set.value() == null ? JsonNull.INSTANCE : set.value());
            }
            case EditorOp.Move move -> {
                json.addProperty("kind", "move");
                json.addProperty("quest", move.id());
                json.addProperty("x", move.x());
                json.addProperty("y", move.y());
            }
            case EditorOp.Create create -> {
                json.addProperty("kind", "create");
                json.addProperty("x", create.x());
                json.addProperty("y", create.y());
            }
            case EditorOp.Duplicate duplicate -> {
                json.addProperty("kind", "duplicate");
                json.addProperty("quest", duplicate.id());
            }
            case EditorOp.Paste paste -> {
                json.addProperty("kind", "paste");
                // The tree itself, inline: it is the file the server will write, and a string holding
                // JSON inside JSON is two escapes waiting to disagree -- see SetField's value.
                json.add("quest", paste.tree());
                json.addProperty("x", paste.x());
                json.addProperty("y", paste.y());
            }
            case EditorOp.Insert insert -> {
                json.addProperty("kind", "insert");
                json.addProperty("quest", insert.id());
                json.addProperty("member", insert.member());
                json.addProperty("index", insert.index());
                json.add("entry", insert.entry());
            }
            case EditorOp.Remove remove -> {
                json.addProperty("kind", "remove");
                json.addProperty("quest", remove.id());
                json.addProperty("member", remove.member());
                json.addProperty("index", remove.index());
            }
            case EditorOp.SetChapter set -> {
                json.addProperty("kind", "chapter");
                json.addProperty("path", set.path());
                json.add("value", set.value() == null ? JsonNull.INSTANCE : set.value());
            }
            case EditorOp.SetGroup set -> {
                json.addProperty("kind", "group");
                json.addProperty("path", set.path());
                json.add("value", set.value() == null ? JsonNull.INSTANCE : set.value());
            }
            case EditorOp.SetIndex set -> {
                json.addProperty("kind", "index");
                json.addProperty("key", set.key());
                json.add("value", set.value() == null ? JsonNull.INSTANCE : set.value());
            }
            case EditorOp.MoveEntry move -> {
                json.addProperty("kind", "moveEntry");
                json.addProperty("quest", move.id());
                json.addProperty("member", move.member());
                json.addProperty("from", move.from());
                json.addProperty("to", move.to());
            }
            case EditorOp.Delete delete -> {
                json.addProperty("kind", "delete");
                json.addProperty("quest", delete.id());
            }
            case EditorOp.Undo ignored -> json.addProperty("kind", "undo");
            case EditorOp.Redo ignored -> json.addProperty("kind", "redo");
            case EditorOp.Batch batch -> {
                json.addProperty("kind", "batch");
                // The elements written by this same method, so a new op kind cannot be readable at the
                // top level and unreadable inside a batch: there is one writer and one reader for both.
                com.google.gson.JsonArray ops = new com.google.gson.JsonArray();
                for (EditorOp each : batch.ops()) {
                    ops.add(write(each));
                }
                json.add("ops", ops);
            }
            case EditorOp.MoveChapter move -> {
                json.addProperty("kind", "moveChapter");
                json.addProperty("chapter", move.chapter());
                json.addProperty("groupId", move.groupId());
                json.addProperty("index", move.index());
            }
            case EditorOp.MoveGroup move -> {
                json.addProperty("kind", "moveGroup");
                json.addProperty("group", move.group());
                json.addProperty("index", move.index());
            }
            case EditorOp.CreateChapter create -> {
                json.addProperty("kind", "createChapter");
                json.addProperty("groupId", create.groupId());
                json.addProperty("index", create.index());
                json.addProperty("chapter", create.id());
                json.addProperty("title", create.title());
            }
            case EditorOp.CreateGroup create -> {
                json.addProperty("kind", "createGroup");
                json.addProperty("group", create.id());
                json.addProperty("title", create.title());
            }
            case EditorOp.RenameChapter rename -> {
                json.addProperty("kind", "renameChapter");
                json.addProperty("chapter", rename.id());
                json.addProperty("newId", rename.newId());
                json.addProperty("title", rename.title());
            }
            case EditorOp.RenameGroup rename -> {
                json.addProperty("kind", "renameGroup");
                json.addProperty("group", rename.id());
                json.addProperty("newId", rename.newId());
                json.addProperty("title", rename.title());
            }
            case EditorOp.DuplicateChapter duplicate -> {
                json.addProperty("kind", "duplicateChapter");
                json.addProperty("chapter", duplicate.id());
                json.addProperty("newId", duplicate.newId());
                json.addProperty("title", duplicate.newTitle());
            }
            case EditorOp.DuplicateGroup duplicate -> {
                json.addProperty("kind", "duplicateGroup");
                json.addProperty("group", duplicate.id());
                json.addProperty("newId", duplicate.newId());
                json.addProperty("title", duplicate.newTitle());
            }
            case EditorOp.DeleteChapter delete -> {
                json.addProperty("kind", "deleteChapter");
                json.addProperty("chapter", delete.id());
            }
            case EditorOp.DeleteGroup delete -> {
                json.addProperty("kind", "deleteGroup");
                json.addProperty("group", delete.group());
            }
        }
        return json;
    }

    /**
     * An op read back, or null for anything this build cannot act on.
     *
     * <p>Null rather than an exception, because this runs on a server thread with a player's message in its
     * hand: a kind from a newer client, a missing argument or a number that is not one is a refusal and a
     * sentence, not a crash in the tick loop. The caller says so with {@link Applied}.
     */
    public static EditorOp read(JsonObject json) {
        if (json == null || !json.has("kind") || !json.get("kind").isJsonPrimitive()) {
            return null;
        }
        try {
            return switch (json.get("kind").getAsString()) {
                case "field" -> new EditorOp.SetField(text(json, "quest"), text(json, "path"),
                        json.has("value") ? json.get("value") : JsonNull.INSTANCE);
                case "move" -> new EditorOp.Move(text(json, "quest"), number(json, "x"), number(json, "y"));
                case "create" -> new EditorOp.Create(number(json, "x"), number(json, "y"));
                case "duplicate" -> new EditorOp.Duplicate(text(json, "quest"));
                case "paste" -> new EditorOp.Paste(
                        json.has("quest") && json.get("quest").isJsonObject()
                                ? json.get("quest").getAsJsonObject() : null,
                        number(json, "x"), number(json, "y"));
                case "insert" -> new EditorOp.Insert(text(json, "quest"), text(json, "member"),
                        (int) number(json, "index"),
                        json.has("entry") && json.get("entry").isJsonObject()
                                ? json.get("entry").getAsJsonObject() : null);
                case "remove" -> new EditorOp.Remove(text(json, "quest"), text(json, "member"),
                        (int) number(json, "index"));
                case "moveEntry" -> new EditorOp.MoveEntry(text(json, "quest"), text(json, "member"),
                        (int) number(json, "from"), (int) number(json, "to"));
                case "chapter" -> new EditorOp.SetChapter(text(json, "path"),
                        json.has("value") ? json.get("value") : JsonNull.INSTANCE);
                case "group" -> new EditorOp.SetGroup(text(json, "path"),
                        json.has("value") ? json.get("value") : JsonNull.INSTANCE);
                case "index" -> new EditorOp.SetIndex(text(json, "key"),
                        json.has("value") ? json.get("value") : JsonNull.INSTANCE);
                case "delete" -> new EditorOp.Delete(text(json, "quest"));
                case "moveChapter" -> new EditorOp.MoveChapter(text(json, "chapter"),
                        nullableText(json, "groupId"), (int) number(json, "index"));
                case "moveGroup" -> new EditorOp.MoveGroup(text(json, "group"),
                        (int) number(json, "index"));
                case "createChapter" -> new EditorOp.CreateChapter(nullableText(json, "groupId"),
                        (int) number(json, "index"), text(json, "chapter"), nullableText(json, "title"));
                case "createGroup" -> new EditorOp.CreateGroup(text(json, "group"),
                        nullableText(json, "title"));
                case "renameChapter" -> new EditorOp.RenameChapter(text(json, "chapter"),
                        text(json, "newId"), nullableText(json, "title"));
                case "renameGroup" -> new EditorOp.RenameGroup(text(json, "group"),
                        text(json, "newId"), nullableText(json, "title"));
                case "duplicateChapter" -> new EditorOp.DuplicateChapter(text(json, "chapter"),
                        text(json, "newId"), nullableText(json, "title"));
                case "duplicateGroup" -> new EditorOp.DuplicateGroup(text(json, "group"),
                        text(json, "newId"), nullableText(json, "title"));
                case "deleteChapter" -> new EditorOp.DeleteChapter(text(json, "chapter"));
                case "deleteGroup" -> new EditorOp.DeleteGroup(text(json, "group"));
                case "undo" -> new EditorOp.Undo();
                case "redo" -> new EditorOp.Redo();
                case "batch" -> readBatch(json);
                default -> null;
            };
        }
        catch (RuntimeException malformed) {
            return null;
        }
    }

    /**
     * A batch read back: every element through this same reader, and the ones this build cannot act on
     * dropped.
     *
     * <h2>Why a dropped element is not a refusal</h2>
     *
     * <p>Because a batch is one gesture made of many edits, and an element written by a newer client
     * says nothing about the rest: refusing the whole gesture over one unknown kind would make an older
     * server read "duplicate these seventy" as an error nobody can act on. A batch that is left empty by
     * that dropping is refused on apply, where there is still somebody to tell — a sentence rather than
     * nothing happening.
     *
     * <p>The outer object's own failures are the caller's: a batch whose {@code ops} is missing or is not
     * an array reads as an empty batch and is refused there, which is the same answer by a shorter path.
     */
    private static EditorOp.Batch readBatch(JsonObject json) {
        List<EditorOp> ops = new ArrayList<>();
        JsonElement held = json.get("ops");
        if (held != null && held.isJsonArray()) {
            for (JsonElement element : held.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                EditorOp op = read(element.getAsJsonObject());
                if (op != null) {
                    ops.add(op);
                }
            }
        }
        return new EditorOp.Batch(ops);
    }

    private static String text(JsonObject json, String key) {
        return json.get(key).getAsString();
    }

    private static double number(JsonObject json, String key) {
        return json.get(key).getAsDouble();
    }

    /** A string argument that may legitimately be absent, as null: an ungrouped chapter's group id. */
    private static String nullableText(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : null;
    }

    // ------------------------------------------------------------------
    // Applying
    // ------------------------------------------------------------------

    /**
     * What applying an op did: whether it happened, which quest it made, and what to say about it.
     *
     * @param chapterId the chapter the client should select afterwards, for the structural edits that
     *                  make or rename one; null when the selection should be left where it is
     * @param groupId   the group that chapter is in, or null
     * @param forget    chapters whose cached editors must be dropped, because their folder moved
     */
    public record Applied(boolean ok, String questId, List<String> messages, String chapterId,
                          String groupId, List<String> forget) {

        public Applied {
            forget = forget == null ? List.of() : List.copyOf(forget);
        }

        public static Applied refused(String message) {
            return new Applied(false, null, List.of(message), null, null, List.of());
        }

        /** A structural edit that happened. */
        static Applied changed(String chapterId, String groupId, List<String> forget) {
            return new Applied(true, null, List.of(), chapterId, groupId, forget);
        }
    }

    /** Applies one op to a chapter, validating on the way: see this class's note. */
    public static Applied apply(QuestEditor editor, EditorOp op) {
        Objects.requireNonNull(editor, "editor");
        if (op == null) {
            return Applied.refused("that is not an edit this version knows");
        }
        try {
            return applyOne(editor, op);
        }
        catch (JsonFile.UnwritablePath unwritable) {
            // A path nothing can be written to refused before it changed anything, but the op pushed
            // its history on the way in -- so this is the same abandonment a refused save takes, and
            // the model is left exactly as it was found, redo trail included.
            editor.abandon();
            return Applied.refused("no file could take that edit: " + unwritable.getMessage());
        }
    }

    /**
     * Several chapter edits as one: one history step, one save, all or nothing.
     *
     * <h2>The order, and why it is this one</h2>
     *
     * <p>Every element is checked before the group is opened, so a batch that could never be applied
     * costs nothing at all. Then the group takes the single snapshot, the mutations run, and the save
     * happens once — after all of them — which is what makes one Ctrl+Z enough and what stops a batch
     * paying for seventy validations of a chapter it is rewriting anyway.
     *
     * <p><b>Atomic.</b> An element that would change nothing, or a save the validator refuses, abandons
     * the whole batch: the files and the memory go back to the snapshot, and no history is left behind.
     * Applying sixty-eight of seventy would leave the author to find the two that are missing, and the
     * Ctrl+Z they would then press reverts all sixty-eight regardless.
     */
    private static Applied applyBatch(QuestEditor editor, EditorOp.Batch batch) {
        List<EditorOp> ops = batch.ops();
        if (ops.isEmpty()) {
            return Applied.refused("that batch holds no edits this version can apply");
        }
        for (EditorOp op : ops) {
            if (!joinable(op)) {
                return Applied.refused("a batch is one gesture's chapter edits, and " + describe(op)
                        + " is not one of them");
            }
        }

        // One slot for the first element that refused, and one for the count that landed: a Runnable
        // cannot return either, and a batch that carries on past a refusal is the thing to avoid.
        Applied[] refusal = new Applied[1];
        int[] landed = new int[1];
        editor.group(() -> {
            for (EditorOp op : ops) {
                Applied applied = applyOne(editor, op, false);
                if (!applied.ok()) {
                    refusal[0] = applied;
                    return;
                }
                landed[0]++;
            }
        });

        if (refusal[0] != null) {
            editor.abandon();
            List<String> messages = refusal[0].messages().isEmpty()
                    ? List.of("one edit in that batch could not be applied, so none of them were")
                    : refusal[0].messages();
            return new Applied(false, null, messages, null, null, List.of());
        }

        QuestEditor.SaveResult saved = editor.save();
        if (!saved.ok()) {
            editor.abandon();
            return new Applied(false, null, saved.messages(), null, null, List.of());
        }
        return new Applied(true, "", List.of(summary(ops)), null, null, List.of());
    }

    /**
     * Whether an op may be one element of a {@link EditorOp.Batch}.
     *
     * <p>Chapter edits only. A structural edit's undo is the tree's own record rather than a snapshot of
     * one chapter's files, so it cannot join a snapshot; {@code SetIndex} writes the root's settings with
     * no history at all; and an undo, a redo or a nested batch has no meaning inside one. The list is
     * written as a switch over the op so a new kind has to be classified by hand rather than defaulting
     * into "joinable" — the fault that would be invisible until somebody's bulk delete half-worked.
     */
    private static boolean joinable(EditorOp op) {
        return switch (op) {
            case EditorOp.SetField ignored -> true;
            case EditorOp.Move ignored -> true;
            case EditorOp.Create ignored -> true;
            case EditorOp.Duplicate ignored -> true;
            case EditorOp.Paste ignored -> true;
            case EditorOp.Insert ignored -> true;
            case EditorOp.Remove ignored -> true;
            case EditorOp.MoveEntry ignored -> true;
            case EditorOp.SetChapter ignored -> true;
            case EditorOp.SetGroup ignored -> true;
            case EditorOp.Delete ignored -> true;
            case EditorOp.Batch ignored -> false;
            case EditorOp.Undo ignored -> false;
            case EditorOp.Redo ignored -> false;
            case EditorOp.SetIndex ignored -> false;
            case EditorOp.MoveChapter ignored -> false;
            case EditorOp.MoveGroup ignored -> false;
            case EditorOp.CreateChapter ignored -> false;
            case EditorOp.CreateGroup ignored -> false;
            case EditorOp.RenameChapter ignored -> false;
            case EditorOp.RenameGroup ignored -> false;
            case EditorOp.DuplicateChapter ignored -> false;
            case EditorOp.DuplicateGroup ignored -> false;
            case EditorOp.DeleteChapter ignored -> false;
            case EditorOp.DeleteGroup ignored -> false;
        };
    }

    /** What a refused batch calls the element it refused: the kind, in the author's words. */
    private static String describe(EditorOp op) {
        return switch (op) {
            case EditorOp.Batch ignored -> "a batch inside a batch";
            case EditorOp.Undo ignored -> "an undo";
            case EditorOp.Redo ignored -> "a redo";
            case EditorOp.SetIndex ignored -> "a pack setting";
            // One arm per kind rather than a list: a case may not bind the same name twice, and a
            // pattern that names nothing is not in this language yet.
            case EditorOp.MoveChapter ignored -> "moving a chapter";
            case EditorOp.MoveGroup ignored -> "moving a group";
            case EditorOp.CreateChapter ignored -> "making a chapter";
            case EditorOp.CreateGroup ignored -> "making a group";
            case EditorOp.RenameChapter ignored -> "renaming a chapter";
            case EditorOp.RenameGroup ignored -> "renaming a group";
            case EditorOp.DuplicateChapter ignored -> "copying a chapter";
            case EditorOp.DuplicateGroup ignored -> "copying a group";
            case EditorOp.DeleteChapter ignored -> "deleting a chapter";
            case EditorOp.DeleteGroup ignored -> "deleting a group";
            default -> "one of those edits";
        };
    }

    // ------------------------------------------------------------------
    // What an edit owes the tree
    // ------------------------------------------------------------------

    /**
     * The fields an edit can change without moving anything a player has: where a node is, what it looks
     * like, what it is called.
     *
     * <h2>Why this list is here rather than shared with the panel's own</h2>
     *
     * <p>Because the two are different questions that happen to share some names.
     * {@code QuestPanelLayout.FIELD_LABELS} is a <b>client</b> class — a screen's caption table, in the
     * package the server must not load — and this is a server-side decision about a reload. So they are
     * two lists, and the thing worth writing down is which way a disagreement between them falls:
     * <b>an unlisted field is {@link TreeRefresh.Touch#CONTENT}</b>, which is correct and merely costs a
     * progress delta on an edit nobody makes often. A field wrongly <i>listed</i> here would be the
     * dangerous direction, so the list holds only names whose whole effect is what a screen draws.
     *
     * <p>A path through {@code tasks} or {@code rewards} is never display-only, even when it lands on a
     * label: those arrays are what progress is stored <b>against</b>, so a rule that classified one of
     * them by its last step would be one edit away from a wrong answer. That is why the check is on the
     * whole path and not on its tail.
     */
    private static final Set<String> DISPLAY_ONLY = Set.of(
            // Where a node sits and how it is drawn, which is a drag and therefore a burst.
            "x", "y", "size", "shape", "rotation", "iconScale",
            // And what it is labelled with.
            "title", "subtitle", "description", "showTitle", "icon",
            // Flags about drawing and about what a screen decides to show, and the per-line styles a
            // dependency is drawn with. The two visibility flags belong here for the same reason the
            // theme does: what they change is derived on the client from the tree it has just been sent
            // -- see QuestVisibility -- so no player's stored progress is involved.
            //
            // `hideUntilDependenciesComplete` is here for that reason too, and its absence until now was
            // the odd one out rather than a decision: the family is 'what a screen withholds', every
            // member of it derives on the client, and a member left off this list costs a progress delta
            // for an edit no player's stored progress can have moved. A chapter's
            // `hideUntilDependenciesComplete` is the same fact one level up, so it is here as well.
            // Note what does NOT belong here: `dependsOn`, `completesWhen`, `prerequisiteMode` and
            // `minRequired` all move a resolved state, which is CONTENT by `reachOfPath`'s default.
            "invisible", "hideUntilDependenciesVisible", "hideUntilDependenciesComplete",
            "hideTextUntilComplete", "hideDetailsUntilStartable", "invisibleUntilTasks", "hideDependencyLines",
            "dependencyLines",
            // A chapter's and a group's own appearance, and the book's name and icon.
            "theme", "collapsedByDefault", "bookTitle", "bookIcon");

    /**
     * What one edit owes: the flag the next flush should be armed with.
     *
     * <h2>The rule, in one sentence</h2>
     *
     * <p>A full progress sync is owed where an <b>existing quest's task or reward positions</b> can
     * move, because a player's stored counts are read by position; everything else that touches the
     * quests is a delta, which is keyed by id and recomputes every state before sending what differs.
     *
     * <p>That second half is worth stating because it is where this is <i>cheaper</i> than the obvious
     * reading: creating, duplicating, pasting and deleting a quest all move <b>ids</b> rather than
     * positions, and a delta handles a new id by seeding it and a deleted one by naming it in
     * {@code removed}. The same goes for every chapter and group edit — renaming, moving, copying and
     * deleting a chapter change which chapter a quest is in, not where its rows sit. So the heavy
     * constant is owed by four kinds and not by the dozen a cautious list would have given it to.
     *
     * <p>Written as a switch over the op, like {@link #joinable}, so a new kind has to be classified by
     * hand rather than defaulting into whichever answer happens to sit in a {@code default} arm.
     */
    public static TreeRefresh.Touch reachOf(EditorOp op) {
        return switch (op) {
            case EditorOp.Move ignored -> TreeRefresh.Touch.COSMETIC;
            case EditorOp.SetField field -> reachOfPath(field.path());
            case EditorOp.SetChapter chapter -> reachOfPath(chapter.path());
            case EditorOp.SetGroup group -> reachOfPath(group.path());
            case EditorOp.SetIndex index -> reachOfPath(index.key());

            // Ids move, or a quest arrives or leaves: a delta is keyed by id and names its removals.
            case EditorOp.Create ignored -> TreeRefresh.Touch.CONTENT;
            case EditorOp.Duplicate ignored -> TreeRefresh.Touch.CONTENT;
            case EditorOp.Paste ignored -> TreeRefresh.Touch.CONTENT;
            case EditorOp.Delete ignored -> TreeRefresh.Touch.CONTENT;
            case EditorOp.MoveChapter ignored -> TreeRefresh.Touch.CONTENT;
            case EditorOp.MoveGroup ignored -> TreeRefresh.Touch.CONTENT;
            case EditorOp.CreateChapter ignored -> TreeRefresh.Touch.CONTENT;
            case EditorOp.CreateGroup ignored -> TreeRefresh.Touch.CONTENT;
            case EditorOp.RenameChapter ignored -> TreeRefresh.Touch.CONTENT;
            case EditorOp.RenameGroup ignored -> TreeRefresh.Touch.CONTENT;
            case EditorOp.DuplicateChapter ignored -> TreeRefresh.Touch.CONTENT;
            case EditorOp.DuplicateGroup ignored -> TreeRefresh.Touch.CONTENT;
            case EditorOp.DeleteChapter ignored -> TreeRefresh.Touch.CONTENT;
            case EditorOp.DeleteGroup ignored -> TreeRefresh.Touch.CONTENT;

            // A row's position moves, or a snapshot rolls one back to somewhere unknown.
            case EditorOp.Insert ignored -> TreeRefresh.Touch.FULL;
            case EditorOp.Remove ignored -> TreeRefresh.Touch.FULL;
            case EditorOp.MoveEntry ignored -> TreeRefresh.Touch.FULL;
            case EditorOp.Undo ignored -> TreeRefresh.Touch.FULL;
            case EditorOp.Redo ignored -> TreeRefresh.Touch.FULL;

            // The gesture's own answer: whichever of its elements owes the most.
            case EditorOp.Batch batch -> strongestOf(batch.ops());
        };
    }

    /**
     * What one field's edit owes, from its path.
     *
     * <p>Two outcomes rather than three, deliberately: every path that is not display-only lands on
     * {@link TreeRefresh.Touch#CONTENT}, because a delta is the honest answer for a field whose meaning
     * this class cannot bound — the server recomputes every quest's resolved state and sends what
     * differs, so an unrecognised field is covered by construction rather than by being guessed at.
     */
    private static TreeRefresh.Touch reachOfPath(String path) {
        if (path == null || path.isEmpty()) {
            return TreeRefresh.Touch.CONTENT;
        }
        for (String step : path.split("\\.")) {
            if (step.equals("tasks") || step.equals("rewards")) {
                return TreeRefresh.Touch.CONTENT;
            }
        }
        return DISPLAY_ONLY.contains(path) ? TreeRefresh.Touch.COSMETIC : TreeRefresh.Touch.CONTENT;
    }

    /** The heaviest touch one batch owes. Empty for a batch of nothing, which owes nothing either. */
    private static TreeRefresh.Touch strongestOf(List<EditorOp> ops) {
        TreeRefresh.Touch owed = TreeRefresh.Touch.NONE;
        for (EditorOp op : ops) {
            owed = owed.strongest(reachOf(op));
        }
        return owed;
    }

    /**
     * One line for what a batch did, counted by kind.
     *
     * <p>A count rather than a list: the author made one gesture and wants to hear what it did, and the
     * ids of seventy new quests are not a sentence. Mixed kinds answer with the honest general form, and
     * the number is always there so a batch that did less than expected is visible at a glance.
     */
    private static String summary(List<EditorOp> ops) {
        int duplicates = 0;
        int deletes = 0;
        int creates = 0;
        for (EditorOp op : ops) {
            switch (op) {
                case EditorOp.Duplicate ignored -> duplicates++;
                case EditorOp.Delete ignored -> deletes++;
                case EditorOp.Create ignored -> creates++;
                default -> {
                }
            }
        }
        String noun = ops.size() == 1 ? " quest" : " quests";
        if (duplicates == ops.size()) {
            return "Duplicated " + ops.size() + noun;
        }
        if (deletes == ops.size()) {
            return "Deleted " + ops.size() + noun;
        }
        if (creates == ops.size()) {
            return "Added " + ops.size() + noun;
        }
        return "Applied " + ops.size() + " edits as one step";
    }

    /**
     * Several ops as the one op that carries them, which is the rule every bulk gesture follows.
     *
     * <p>One element stays itself rather than becoming a one-element batch, and that is not a
     * micro-optimisation: a plain create or duplicate answers with the id it made, which is what selects
     * the new node on the author's canvas and what a failed duplicate names. A batch carries no id, so
     * wrapping a single edit would lose the answer that edit already gives.
     */
    public static EditorOp batch(List<EditorOp> ops) {
        Objects.requireNonNull(ops, "ops");
        return ops.size() == 1 ? ops.get(0) : new EditorOp.Batch(ops);
    }

    private static Applied applyOne(QuestEditor editor, EditorOp op) {
        return applyOne(editor, op, true);
    }

    /**
     * One op, with the save optional.
     *
     * <p>{@code save} is false for exactly one caller: {@link #applyBatch}, which mutates several ops
     * and saves once at the end. Everything else — the refusal shapes, what a refused save costs — is the
     * same either way, so this is a flag on one switch rather than a second switch that would drift from
     * it. Every case that is not a chapter mutation is unreachable with {@code save} false, because
     * {@link #joinable} refuses those before a batch is opened.
     */
    private static Applied applyOne(QuestEditor editor, EditorOp op, boolean save) {
        return switch (op) {
            case EditorOp.SetField set ->
                    finish(editor, op, editor.set(set.id(), set.path(), value(set.value())), null, save);
            case EditorOp.Move move ->
                    finish(editor, op, editor.move(move.id(), move.x(), move.y()), null, save);
            case EditorOp.Create create -> {
                String made = editor.create(create.x(), create.y());
                yield finish(editor, op, made != null, made, save);
            }
            case EditorOp.Duplicate duplicate -> {
                String made = editor.duplicate(duplicate.id());
                yield finish(editor, op, made != null, made, save);
            }
            case EditorOp.Paste paste -> {
                String made = paste.tree() == null
                        ? null : editor.paste(paste.tree(), paste.x(), paste.y());
                yield finish(editor, op, made != null, made, save);
            }
            case EditorOp.Insert insert ->
                    finish(editor, op, editor.insert(insert.id(), insert.member(), insert.index(),
                            insert.entry()), null, save);
            case EditorOp.Remove remove ->
                    finish(editor, op, editor.removeEntry(remove.id(), remove.member(),
                            remove.index()), null, save);
            case EditorOp.MoveEntry move ->
                    finish(editor, op, editor.moveEntry(move.id(), move.member(), move.from(),
                            move.to()), null, save);
            case EditorOp.SetChapter set ->
                    finish(editor, op, editor.setChapter(set.path(), value(set.value())), null, save);
            case EditorOp.SetGroup set ->
                    finish(editor, op, editor.setGroup(set.path(), value(set.value())), null, save);
            // A root-level settings write: the file itself is what changes, like the structural kinds,
            // so it takes their path -- there is no chapter model to save and no meta to report.
            case EditorOp.SetIndex ignored -> structural(editor, op);
            case EditorOp.Delete delete -> finish(editor, op, editor.delete(delete.id()), null, save);
            // A batch is handled as a whole, and never as an element of itself: `joinable` refused that
            // before the group was opened.
            case EditorOp.Batch batch -> applyBatch(editor, batch);
            // The structural kinds, in one line each: what they do depends only on the tree's root, not
            // on the chapter this op arrived at -- see `structureAt` and `applyWithoutSession`.
            case EditorOp.MoveChapter ignored -> structural(editor, op);
            case EditorOp.MoveGroup ignored -> structural(editor, op);
            case EditorOp.CreateChapter ignored -> structural(editor, op);
            case EditorOp.CreateGroup ignored -> structural(editor, op);
            case EditorOp.RenameChapter ignored -> structural(editor, op);
            case EditorOp.RenameGroup ignored -> structural(editor, op);
            case EditorOp.DuplicateChapter ignored -> structural(editor, op);
            case EditorOp.DuplicateGroup ignored -> structural(editor, op);
            case EditorOp.DeleteChapter ignored -> structural(editor, op);
            case EditorOp.DeleteGroup ignored -> structural(editor, op);
            case EditorOp.Undo ignored -> history(editor, editor.undo(), "nothing to undo in this chapter");
            case EditorOp.Redo ignored -> history(editor, editor.redo(), "nothing to redo in this chapter");
        };
    }

    /**
     * The structural edit an op asks for, as the tree's own model can perform it — or null for the ops
     * that belong to a chapter.
     *
     * <p>Split out so the same edits can be applied with and without a session: {@link #applyOne}
     * records the structure on the acting chapter's history, and {@link #applyWithoutSession} has no
     * chapter to record on — which is the state a questline with no chapters is in.
     */
    private static QuestStructure.Outcome structureAt(Path root, EditorOp op) {
        return switch (op) {
            case EditorOp.MoveChapter move -> QuestStructure.moveChapter(root, move.chapter(),
                    move.groupId(), move.index());
            case EditorOp.MoveGroup move -> QuestStructure.moveGroup(root, move.group(), move.index());
            case EditorOp.CreateChapter create -> QuestStructure.createChapter(root, create.groupId(),
                    create.index(), create.id(), create.title());
            case EditorOp.CreateGroup create -> QuestStructure.createGroup(root, create.id(),
                    create.title());
            case EditorOp.RenameChapter rename -> QuestStructure.renameChapter(root, rename.id(),
                    rename.newId(), rename.title());
            case EditorOp.RenameGroup rename -> QuestStructure.renameGroup(root, rename.id(),
                    rename.newId(), rename.title());
            case EditorOp.DuplicateChapter duplicate -> QuestStructure.duplicateChapter(root,
                    duplicate.id(), duplicate.newId(), duplicate.newTitle());
            case EditorOp.DuplicateGroup duplicate -> QuestStructure.duplicateGroup(root,
                    duplicate.id(), duplicate.newId(), duplicate.newTitle());
            case EditorOp.DeleteChapter delete -> QuestStructure.deleteChapter(root, delete.id());
            case EditorOp.DeleteGroup delete -> QuestStructure.deleteGroup(root, delete.group());
            default -> null;
        };
    }

    /**
     * Applies a structural edit with no session editor behind it.
     *
     * <p>The one case this exists for: a questline with no chapters. An editor is opened on a chapter,
     * so an empty tree has none to open, and therefore no history for the edit to join — but making the
     * first chapter is exactly the thing somebody wants to do there. The edit is applied and reported;
     * what it cannot do is be undone, because the history it would live in is the thing that does not
     * exist yet.
     */
    public static Applied applyWithoutSession(Path root, EditorOp op) {
        if (op == null) {
            return Applied.refused("that is not an edit this version knows");
        }
        if (op instanceof EditorOp.SetIndex set) {
            // A root-level settings write needs no session at all: there is no chapter model behind it,
            // which is exactly the case this path exists for -- a pack whose book has no chapters yet
            // can still be named.
            return QuestStructure.setIndexSetting(root, set.key(), value(set.value()))
                    ? Applied.changed(null, null, List.of())
                    : Applied.refused("the book's settings could not be written");
        }
        QuestStructure.Outcome outcome = structureAt(root, op);
        if (outcome == null) {
            return Applied.refused("this edit belongs to a chapter, and there is none yet");
        }
        if (!outcome.ok()) {
            return Applied.refused(outcome.refusal());
        }
        QuestStructure.Structure.Meta meta = outcome.structure().forwardMeta();
        return Applied.changed(meta.chapterId(), meta.groupId(), meta.forget());
    }

    /**
     * Runs one structural edit and records it on the acting chapter's history.
     *
     * <p>The record goes on the editor the op was sent to, not on the chapter that changed — most of
     * these change chapters the sender may not have open at all. That is what keeps one Ctrl+Z, in one
     * session, enough to reverse a drag.
     */
    private static Applied structural(QuestEditor editor, EditorOp op) {
        if (op instanceof EditorOp.SetIndex set) {
            // The book's own settings live in index.json, which no editor model holds: the write goes
            // straight to the file, and there is no structure to record. Undo does not cover it -- the
            // history is chapter- and group-shaped -- which is why the Book section says so.
            return QuestStructure.setIndexSetting(editor.treeRoot(), set.key(), value(set.value()))
                    ? Applied.changed(null, null, List.of())
                    : Applied.refused("the book's settings could not be written");
        }
        QuestStructure.Outcome outcome = structureAt(editor.treeRoot(), op);
        if (!outcome.ok()) {
            return Applied.refused(outcome.refusal());
        }
        editor.record(outcome.structure());
        QuestStructure.Structure.Meta meta = outcome.structure().forwardMeta();
        return Applied.changed(meta.chapterId(), meta.groupId(), meta.forget());
    }

    /**
     * What an undo or a redo did, told the same way a structural edit is told.
     *
     * <p>The meta of a reversed structure is its <b>reverse</b> meta — the id the thing has now, not the
     * id the edit gave it — which is what keeps the editor cache right when Ctrl+Z moves a chapter back.
     * A field edit has no meta, and reports none.
     */
    private static Applied history(QuestEditor editor, boolean changed, String nothing) {
        QuestStructure.Structure.Meta meta = editor.takeLastMeta();
        if (!changed) {
            // The sentence is the caller's, because the two halves of the key have two reasons: an undo
            // with nothing behind it and a redo with nothing ahead of it are different news, and "that
            // edit would change nothing" -- which is what this said for both -- told the author neither.
            return new Applied(false, null, List.of(nothing), null, null, List.of());
        }
        if (meta == null) {
            // A field history: the model was put back in memory, and the save is what makes the disk
            // agree with it -- exactly the path `finish` takes for every other op. A structural history
            // needs none: its steps wrote the files themselves, which is what they are for.
            QuestEditor.SaveResult saved = editor.save();
            if (!saved.ok()) {
                // Abandoned rather than undone: an undo that refuses must leave the history as it was
                // found, and `undo` would leave the refused state on the redo trail. See `abandon`.
                editor.abandon();
                return new Applied(false, null, saved.messages(), null, null, List.of());
            }
            return new Applied(true, null, List.of(), null, null, List.of());
        }
        return Applied.changed(meta.chapterId(), meta.groupId(), meta.forget());
    }

    /**
     * Saves what the op changed, or undoes the whole of it.
     *
     * <p>One place, because "validate on apply" is one rule: a save that refused wrote nothing, so
     * abandoning the model puts memory back where disk already is, and the history is left as it was
     * found — the redo trail included, which is what {@code abandon} exists for. With {@code save} false
     * the caller is {@code applyBatch}, which saves once for the whole gesture; nothing else passes it.
     */
    private static Applied finish(QuestEditor editor, EditorOp op, boolean changed, String madeId,
                                  boolean save) {
        String about = madeId != null ? madeId : op.quest();
        if (!changed) {
            return new Applied(false, about, List.of("that edit would change nothing"), null, null, List.of());
        }
        if (!save) {
            // A batch element: the chapter is left dirty for the one save at the end, and the validation
            // it needs happens there, once, over the files the whole gesture produced.
            return new Applied(true, about, List.of(), null, null, List.of());
        }
        QuestEditor.SaveResult saved = editor.save();
        if (!saved.ok()) {
            editor.abandon();
            return new Applied(false, null, saved.messages(), null, null, List.of());
        }
        return new Applied(true, about, List.of(), null, null, List.of());
    }

    /**
     * A wire value as the model takes it: text, a number, a flag, a list of strings, a whole JSON
     * value, or null to remove.
     *
     * <p>An object -- or an array that is not a list of strings -- crosses as itself rather than being
     * taken apart: that is the raw-editing path, where a field the panel has no shape for is still the
     * field's value. An array of strings stays a list of strings, because that is the shape the format
     * uses for ids and the model has a setter for it.
     */
    private static Object value(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonObject()) {
            return element;
        }
        if (element.isJsonArray()) {
            List<String> values = new ArrayList<>();
            for (JsonElement each : element.getAsJsonArray()) {
                if (!each.isJsonPrimitive() || !each.getAsJsonPrimitive().isString()) {
                    // Not a list of ids: keep the JSON whole rather than stringify its members.
                    return element;
                }
                values.add(each.getAsString());
            }
            return values;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            return primitive.getAsDouble();
        }
        return primitive.getAsString();
    }
}
