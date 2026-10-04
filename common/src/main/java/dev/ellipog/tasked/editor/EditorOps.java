package dev.ellipog.tasked.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
                default -> null;
            };
        }
        catch (RuntimeException malformed) {
            return null;
        }
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
            // its history on the way in -- so the undo here is the same one a refused save takes, and
            // the model is left exactly as it was found.
            editor.undo();
            return Applied.refused("no file could take that edit: " + unwritable.getMessage());
        }
    }

    private static Applied applyOne(QuestEditor editor, EditorOp op) {
        return switch (op) {
            case EditorOp.SetField set ->
                    finish(editor, op, editor.set(set.id(), set.path(), value(set.value())), null);
            case EditorOp.Move move -> finish(editor, op, editor.move(move.id(), move.x(), move.y()), null);
            case EditorOp.Create create -> {
                String made = editor.create(create.x(), create.y());
                yield finish(editor, op, made != null, made);
            }
            case EditorOp.Duplicate duplicate -> {
                String made = editor.duplicate(duplicate.id());
                yield finish(editor, op, made != null, made);
            }
            case EditorOp.Paste paste -> {
                String made = paste.tree() == null
                        ? null : editor.paste(paste.tree(), paste.x(), paste.y());
                yield finish(editor, op, made != null, made);
            }
            case EditorOp.Insert insert ->
                    finish(editor, op, editor.insert(insert.id(), insert.member(), insert.index(),
                            insert.entry()), null);
            case EditorOp.Remove remove ->
                    finish(editor, op, editor.removeEntry(remove.id(), remove.member(),
                            remove.index()), null);
            case EditorOp.MoveEntry move ->
                    finish(editor, op, editor.moveEntry(move.id(), move.member(), move.from(),
                            move.to()), null);
            case EditorOp.SetChapter set ->
                    finish(editor, op, editor.setChapter(set.path(), value(set.value())), null);
            case EditorOp.SetGroup set ->
                    finish(editor, op, editor.setGroup(set.path(), value(set.value())), null);
            // A root-level settings write: the file itself is what changes, like the structural kinds,
            // so it takes their path -- there is no chapter model to save and no meta to report.
            case EditorOp.SetIndex ignored -> structural(editor, op);
            case EditorOp.Delete delete -> finish(editor, op, editor.delete(delete.id()), null);
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
            case EditorOp.Undo ignored -> history(editor, editor.undo());
            case EditorOp.Redo ignored -> history(editor, editor.redo());
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
    private static Applied history(QuestEditor editor, boolean changed) {
        QuestStructure.Structure.Meta meta = editor.takeLastMeta();
        if (!changed) {
            return new Applied(false, null, List.of("that edit would change nothing"), null, null, List.of());
        }
        if (meta == null) {
            // A field history: the model was put back in memory, and the save is what makes the disk
            // agree with it -- exactly the path `finish` takes for every other op. A structural history
            // needs none: its steps wrote the files themselves, which is what they are for.
            QuestEditor.SaveResult saved = editor.save();
            if (!saved.ok()) {
                editor.undo();
                return new Applied(false, null, saved.messages(), null, null, List.of());
            }
            return new Applied(true, null, List.of(), null, null, List.of());
        }
        return Applied.changed(meta.chapterId(), meta.groupId(), meta.forget());
    }

    /**
     * Saves what the op changed, or undoes the whole of it.
     *
     * <p>One place, because "validate on apply" is one rule: a save that refused wrote nothing, so undoing the
     * model puts memory back where disk already is, and the history is left as it was found.
     */
    private static Applied finish(QuestEditor editor, EditorOp op, boolean changed, String madeId) {
        String about = madeId != null ? madeId : op.quest();
        if (!changed) {
            return new Applied(false, about, List.of("that edit would change nothing"), null, null, List.of());
        }
        QuestEditor.SaveResult saved = editor.save();
        if (!saved.ok()) {
            editor.undo();
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
