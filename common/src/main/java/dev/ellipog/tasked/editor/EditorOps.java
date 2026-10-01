package dev.ellipog.tasked.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

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
                case "delete" -> new EditorOp.Delete(text(json, "quest"));
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

    // ------------------------------------------------------------------
    // Applying
    // ------------------------------------------------------------------

    /** What applying an op did: whether it happened, which quest it made, and what to say about it. */
    public record Applied(boolean ok, String questId, List<String> messages) {

        public static Applied refused(String message) {
            return new Applied(false, null, List.of(message));
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
            case EditorOp.Delete delete -> finish(editor, op, editor.delete(delete.id()), null);
            case EditorOp.Undo ignored -> finish(editor, op, editor.undo(), null);
            case EditorOp.Redo ignored -> finish(editor, op, editor.redo(), null);
        };
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
            return new Applied(false, about, List.of("that edit would change nothing"));
        }
        QuestEditor.SaveResult saved = editor.save();
        if (!saved.ok()) {
            editor.undo();
            return new Applied(false, null, saved.messages());
        }
        return new Applied(true, about, List.of());
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
