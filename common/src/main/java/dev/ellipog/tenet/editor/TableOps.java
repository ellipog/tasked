package dev.ellipog.tenet.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * Writing a {@link TableOp} down and reading one back.
 *
 * <p>The counterpart of {@link EditorOps} for the table family, and deliberately the same shape: a kind,
 * the arguments that kind has, and values written as themselves rather than as strings holding JSON. It
 * is a separate class because the two families are applied by different models — see {@link TableOp} —
 * and because a reader that can answer "not mine" in one place is what lets one payload carry both.
 */
public final class TableOps {

    /** The prefix every table op's kind carries, so {@link #read} can tell one from a chapter's op. */
    public static final String KIND_PREFIX = "table";

    private TableOps() {
    }

    /** One op as the wire holds it. */
    public static JsonObject write(TableOp op) {
        JsonObject json = new JsonObject();
        switch (op) {
            case TableOp.Set set -> {
                json.addProperty("kind", KIND_PREFIX + "Field");
                json.add("target", writeAddress(set.address()));
                json.addProperty("path", set.path());
                json.add("value", set.value() == null ? JsonNull.INSTANCE : set.value());
            }
            case TableOp.SetFields fields -> {
                json.addProperty("kind", KIND_PREFIX + "Fields");
                json.add("target", writeAddress(fields.address()));
                json.add("fields", fields.fields());
            }
            case TableOp.Insert insert -> {
                json.addProperty("kind", KIND_PREFIX + "Insert");
                json.add("target", writeAddress(insert.address()));
                json.addProperty("index", insert.index());
                json.add("entry", insert.entry());
            }
            case TableOp.Remove remove -> {
                json.addProperty("kind", KIND_PREFIX + "Remove");
                json.add("target", writeAddress(remove.address()));
                json.addProperty("index", remove.index());
            }
            case TableOp.Move move -> {
                json.addProperty("kind", KIND_PREFIX + "Move");
                json.add("target", writeAddress(move.address()));
                json.addProperty("from", move.from());
                json.addProperty("to", move.to());
            }
            case TableOp.Undo undo -> {
                json.addProperty("kind", KIND_PREFIX + "Undo");
                json.add("target", writeAddress(undo.address()));
            }
            case TableOp.Redo redo -> {
                json.addProperty("kind", KIND_PREFIX + "Redo");
                json.add("target", writeAddress(redo.address()));
            }
            case TableOp.Create create -> {
                json.addProperty("kind", KIND_PREFIX + "Create");
                json.addProperty("id", create.id());
                json.add("root", create.root());
            }
            case TableOp.Duplicate duplicate -> {
                json.addProperty("kind", KIND_PREFIX + "Duplicate");
                json.addProperty("id", duplicate.id());
                json.addProperty("newId", duplicate.newId());
            }
            case TableOp.Delete delete -> {
                json.addProperty("kind", KIND_PREFIX + "Delete");
                json.addProperty("id", delete.id());
            }
            case TableOp.Restore restore -> {
                json.addProperty("kind", KIND_PREFIX + "Restore");
                json.addProperty("path", restore.path());
            }
            case TableOp.Select select -> {
                json.addProperty("kind", KIND_PREFIX + "Select");
                json.add("owner", writeOwner(select.owner()));
                json.addProperty("owningPath", select.owningPath());
                json.addProperty("table", select.tableId());
            }
        }
        return json;
    }

    /**
     * The op this JSON is, or null for anything that is not a table op this build knows.
     *
     * <p>Null for a chapter's op too, which is the point: the payload handler asks this first and falls
     * back to {@link EditorOps#read}. A malformed table op is null as well, so a newer client's kind or
     * a missing argument is a refusal and a sentence rather than a crash on the server thread.
     */
    public static TableOp read(JsonObject json) {
        if (json == null || !json.has("kind") || !json.get("kind").isJsonPrimitive()) {
            return null;
        }
        String kind = json.get("kind").getAsString();
        if (!kind.startsWith(KIND_PREFIX)) {
            return null;
        }
        try {
            return switch (kind) {
                case KIND_PREFIX + "Field" -> new TableOp.Set(readAddress(json.getAsJsonObject("target")),
                        text(json, "path"), value(json));
                case KIND_PREFIX + "Fields" -> new TableOp.SetFields(
                        readAddress(json.getAsJsonObject("target")),
                        json.has("fields") && json.get("fields").isJsonObject()
                                ? json.getAsJsonObject("fields") : null);
                case KIND_PREFIX + "Insert" -> new TableOp.Insert(
                        readAddress(json.getAsJsonObject("target")), (int) number(json, "index"),
                        json.has("entry") && json.get("entry").isJsonObject()
                                ? json.getAsJsonObject("entry") : null);
                case KIND_PREFIX + "Remove" -> new TableOp.Remove(
                        readAddress(json.getAsJsonObject("target")), (int) number(json, "index"));
                case KIND_PREFIX + "Move" -> new TableOp.Move(
                        readAddress(json.getAsJsonObject("target")),
                        (int) number(json, "from"), (int) number(json, "to"));
                case KIND_PREFIX + "Undo" -> new TableOp.Undo(readAddress(json.getAsJsonObject("target")));
                case KIND_PREFIX + "Redo" -> new TableOp.Redo(readAddress(json.getAsJsonObject("target")));
                case KIND_PREFIX + "Create" -> new TableOp.Create(text(json, "id"),
                        json.has("root") && json.get("root").isJsonObject()
                                ? json.getAsJsonObject("root") : null);
                case KIND_PREFIX + "Duplicate" -> new TableOp.Duplicate(text(json, "id"), text(json, "newId"));
                case KIND_PREFIX + "Delete" -> new TableOp.Delete(text(json, "id"));
                case KIND_PREFIX + "Restore" -> new TableOp.Restore(text(json, "path"));
                case KIND_PREFIX + "Select" -> new TableOp.Select(
                        readOwner(json.getAsJsonObject("owner")), text(json, "owningPath"),
                        nullableText(json, "table"));
                default -> null;
            };
        }
        catch (RuntimeException malformed) {
            return null;
        }
    }

    /** An address as JSON: a named file, or the quest whose reward holds the reference. */
    public static JsonObject writeAddress(TableAddress address) {
        return writeOwner(address.owner());
    }

    public static JsonObject writeOwner(TableAddress.Owner owner) {
        JsonObject json = new JsonObject();
        switch (owner) {
            case TableAddress.Owner.Named named -> json.addProperty("table", named.id());
            case TableAddress.Owner.InQuest quest -> {
                json.addProperty("chapter", quest.chapter());
                json.addProperty("quest", quest.quest());
            }
        }
        return json;
    }

    /** An address back, or null when the object names neither a file nor a quest. */
    public static TableAddress readAddress(JsonObject json) {
        TableAddress.Owner owner = readOwner(json);
        if (owner == null) {
            return null;
        }
        return new TableAddress(owner);
    }

    public static TableAddress.Owner readOwner(JsonObject json) {
        if (json == null) {
            return null;
        }
        if (json.has("table") && json.get("table").isJsonPrimitive()) {
            return new TableAddress.Owner.Named(json.get("table").getAsString());
        }
        if (json.has("chapter") && json.has("quest")
                && json.get("chapter").isJsonPrimitive() && json.get("quest").isJsonPrimitive()) {
            return new TableAddress.Owner.InQuest(json.get("chapter").getAsString(),
                    json.get("quest").getAsString());
        }
        return null;
    }

    /** A field's value, with JSON null read as the absence it means: "remove this field". */
    private static JsonElement value(JsonObject json) {
        JsonElement value = json.has("value") ? json.get("value") : null;
        return value == null || value.isJsonNull() ? null : value;
    }

    private static String text(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalStateException("missing " + key);
        }
        return value.getAsString();
    }

    private static String nullableText(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : null;
    }

    private static double number(JsonObject json, String key) {
        return json.get(key).getAsDouble();
    }
}
