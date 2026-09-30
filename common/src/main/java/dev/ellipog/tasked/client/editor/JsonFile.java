package dev.ellipog.tasked.client.editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One JSON file on disk, as a tree the editor can change.
 *
 * <h2>Why a tree rather than a record</h2>
 *
 * <p>Because the editor's contract is that a hand-edited file survives a visit to it. The loader decodes a
 * file into records and <b>throws away what it did not understand</b> — which is right for a loader and
 * fatal for an editor: a pack author's comment-ish extra field, a key from a newer build, or a field the
 * editor has no panel for would be gone the first time somebody dragged a node. So the editor holds the
 * file's own tree, changes the fields it knows, and writes the tree back. Nothing else moves, including
 * the order of the keys, which is why this is a {@link JsonObject} (insertion-ordered) and not a map.
 *
 * <h2>What it does not promise</h2>
 *
 * <p>Whitespace. A save re-serialises the whole file, so a hand-written file that inlined its small
 * objects comes back expanded. The <i>data</i> round trip is lossless and it is asserted; the
 * formatting is the editor's, and a file the editor has never saved is never touched at all.
 *
 * <h2>Paths, and why they are dotted strings</h2>
 *
 * <p>{@code "icon.item"} names {@code root.icon.item}, creating {@code icon} on the way if it is not
 * there. A typed accessor per field would be forty methods with forty names for the same three lines,
 * and the field names themselves have to exist somewhere regardless — the validator is what decides
 * whether they are spelled right, and it runs on every save.
 */
public final class JsonFile {

    /**
     * Two-space indent, and no HTML escaping.
     *
     * <p>{@code disableHtmlEscaping} is not cosmetic here: Gson escapes {@code <}, {@code >}, {@code &},
     * {@code =} and {@code '} by default, and a quest title of "Bread & Butter" would be written as
     * {@code Bread \u0026 Butter} — legal JSON, unreadable in the file an author is meant to edit, and a
     * diff on every save once the author fixes it by hand.
     */
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final Path file;
    private JsonObject root;

    /**
     * The tree as it was last read or written, for {@link #dirty()}.
     *
     * <p>A deep copy of the tree rather than the file's text, and the difference is a real fault the
     * first version had: comparing text made every file <i>read</i> report itself changed, because the
     * editor's serialisation is not the author's — a hand-written file with four-space indentation came
     * back re-indented, so a chapter nobody had touched had "unsaved changes". The comparison has to be
     * about the data, because the data is what the editor promises not to change.
     */
    private JsonObject saved;

    private JsonFile(Path file, JsonObject root, JsonObject saved) {
        this.file = Objects.requireNonNull(file, "file");
        this.root = root;
        this.saved = saved;
    }

    /**
     * Reads one file.
     *
     * @throws com.google.gson.JsonSyntaxException if the text is not a JSON object
     */
    public static JsonFile parse(Path file, String text) {
        JsonElement parsed = JsonParser.parseString(text);
        if (!parsed.isJsonObject()) {
            throw new com.google.gson.JsonSyntaxException(
                    file + " is not a JSON object, so it is not a quest file this editor can open");
        }
        JsonObject root = parsed.getAsJsonObject();
        return new JsonFile(file, root, root.deepCopy());
    }

    /**
     * A new tree, as if it had been read from a file that contained it.
     *
     * <p>There is no saved copy, so a file that does not exist yet is dirty until it is written — which
     * is the answer that gets it written.
     */
    public static JsonFile of(Path file, JsonObject root) {
        return new JsonFile(file, root, null);
    }

    public Path file() {
        return file;
    }

    /** The tree itself, for a caller that needs to walk it. Edits go through the setters below. */
    public JsonObject root() {
        return root;
    }

    /** The file's text, as the editor would write it. */
    public String json() {
        return GSON.toJson(root) + "\n";
    }

    /** Whether the tree has changed since it was read or written: the data, not the whitespace. */
    public boolean dirty() {
        return saved == null || !root.equals(saved);
    }

    /** Writes the tree to its file. */
    public void write() throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, json(), StandardCharsets.UTF_8);
        markSaved();
    }

    /** Records the tree as what is on disk. For a caller that wrote it another way. */
    public void markSaved() {
        saved = root.deepCopy();
    }

    /**
     * Replaces the whole tree with the file's text, as of a save or an undo.
     *
     * <p>The saved copy goes with it: what a snapshot restores is a state that was written, so the
     * document that comes back is clean rather than dirty. Without that, an undo would leave the editor
     * thinking it had unsaved changes it had already saved.
     *
     * @throws com.google.gson.JsonSyntaxException if the text is not a JSON object
     */
    public void replaceWith(String text) {
        JsonElement parsed = JsonParser.parseString(text);
        if (!parsed.isJsonObject()) {
            throw new com.google.gson.JsonSyntaxException(file + " is not a JSON object");
        }
        root = parsed.getAsJsonObject();
        saved = root.deepCopy();
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /** Whether the path names anything at all. */
    public boolean has(String path) {
        return get(path) != null;
    }

    /** A string at a dotted path, or the fallback. An object where a string belongs is not a string. */
    public String text(String path, String fallback) {
        JsonElement found = get(path);
        return found != null && found.isJsonPrimitive() && found.getAsJsonPrimitive().isString()
                ? found.getAsString() : fallback;
    }

    /** A number at a dotted path, or the fallback. */
    public double number(String path, double fallback) {
        JsonElement found = get(path);
        return found != null && found.isJsonPrimitive() && found.getAsJsonPrimitive().isNumber()
                ? found.getAsDouble() : fallback;
    }

    /** A boolean at a dotted path, or the fallback. */
    public boolean flag(String path, boolean fallback) {
        JsonElement found = get(path);
        return found != null && found.isJsonPrimitive() && found.getAsJsonPrimitive().isBoolean()
                ? found.getAsBoolean() : fallback;
    }

    /** The strings of an array at a dotted path. Anything else, including a missing array, is empty. */
    public List<String> strings(String path) {
        JsonElement found = get(path);
        if (found == null || !found.isJsonArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonElement element : found.getAsJsonArray()) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                out.add(element.getAsString());
            }
        }
        return List.copyOf(out);
    }

    /** The element at a dotted path, or null. */
    public JsonElement get(String path) {
        JsonElement at = root;
        for (String step : path.split("\\.")) {
            if (at == null || !at.isJsonObject()) {
                return null;
            }
            at = at.getAsJsonObject().get(step);
        }
        return at;
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    public void setText(String path, String value) {
        parent(path).add(leaf(path), new com.google.gson.JsonPrimitive(value));
    }

    public void setNumber(String path, double value) {
        parent(path).addProperty(leaf(path), value);
    }

    public void setFlag(String path, boolean value) {
        parent(path).addProperty(leaf(path), value);
    }

    /** Removes whatever is at the path. A path that is not there is not a problem. */
    public void remove(String path) {
        JsonObject owner = parentIfPresent(path);
        if (owner != null) {
            owner.remove(leaf(path));
        }
    }

    /** Replaces the array at a path with these strings. */
    public void setStrings(String path, List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        parent(path).add(leaf(path), array);
    }

    /** Appends to the array at a path, creating it if it is absent. */
    public void addString(String path, String value) {
        JsonElement found = get(path);
        JsonArray array;
        if (found != null && found.isJsonArray()) {
            array = found.getAsJsonArray();
        }
        else {
            array = new JsonArray();
            parent(path).add(leaf(path), array);
        }
        array.add(value);
    }

    /**
     * Removes the first element equal to {@code value} from the array at a path.
     *
     * @return whether anything was removed, which is what tells a caller the file changed
     */
    public boolean removeString(String path, String value) {
        JsonElement found = get(path);
        if (found == null || !found.isJsonArray()) {
            return false;
        }
        JsonArray array = found.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (element.isJsonPrimitive() && element.getAsString().equals(value)) {
                array.remove(i);
                return true;
            }
        }
        return false;
    }

    /** The object a dotted path's last step lives in, creating the chain if it is absent. */
    private JsonObject parent(String path) {
        JsonObject at = root;
        String[] steps = path.split("\\.");
        for (int i = 0; i < steps.length - 1; i++) {
            JsonElement next = at.get(steps[i]);
            if (next == null || !next.isJsonObject()) {
                JsonObject created = new JsonObject();
                at.add(steps[i], created);
                at = created;
            }
            else {
                at = next.getAsJsonObject();
            }
        }
        return at;
    }

    private JsonObject parentIfPresent(String path) {
        JsonObject at = root;
        String[] steps = path.split("\\.");
        for (int i = 0; i < steps.length - 1; i++) {
            JsonElement next = at.get(steps[i]);
            if (next == null || !next.isJsonObject()) {
                return null;
            }
            at = next.getAsJsonObject();
        }
        return at;
    }

    private static String leaf(String path) {
        int split = path.lastIndexOf('.');
        return split < 0 ? path : path.substring(split + 1);
    }

    @Override
    public String toString() {
        return "JsonFile(" + file + (dirty() ? ", unsaved" : "") + ")";
    }
}
