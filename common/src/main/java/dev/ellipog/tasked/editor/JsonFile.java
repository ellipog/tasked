package dev.ellipog.tasked.editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import dev.ellipog.armature.api.data.JsonWrite;

import java.io.IOException;
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

    /**
     * Writes the tree to its file.
     *
     * <p>Through {@link JsonWrite#atomically}, so the file holds either the tree as it was read or the
     * tree as it is now, and never a truncated half of either. The reason is the window a plain
     * {@code Files.writeString} opens: it truncates the file the moment it opens it, so a crash, an
     * out-of-memory kill or a power cut between that and the last byte leaves an author's chapter as an
     * empty file with nothing on disk to recover it from. That is the one failure this project cannot
     * ask an author to work around, because the thing they would work around it with is gone.
     *
     * <p>{@link #markSaved()} is called only once the write has actually happened. That is what keeps a
     * failed save dirty — and therefore retryable — rather than recording a state memory and disk do
     * not agree on, which is the same fault {@link #replaceWith} documents from the other side.
     */
    public void write() throws IOException {
        JsonWrite.atomically(file, json());
        markSaved();
    }

    /** Records the tree as what is on disk. For a caller that wrote it another way. */
    public void markSaved() {
        saved = root.deepCopy();
    }

    /**
     * Replaces the whole tree with this text, as of an undo.
     *
     * <h2>Why this does not mark the tree saved</h2>
     *
     * <p>It used to, with the reason written out: *"what a snapshot restores is a state that was written, so
     * the document that comes back is clean rather than dirty"*. The reason is wrong, and the fault it hid
     * was invisible from both ends at once. The text a snapshot holds came out of this object's own memory —
     * it was never the disk's, necessarily — so after undoing a field edit the disk holds the *edit* while
     * memory holds the value before it, and marking the tree saved says those agree. `save()` then skips the
     * file, because there is nothing dirty to write: <b>the disk keeps the edit and the screen shows it
     * gone</b>. Nothing logs, nothing throws, and reloading the chapter brings the edit back.
     *
     * <p>So the marking belongs to the callers that know memory and disk agree — the ones that have just
     * written the text, or taken it from the file — and they say so with {@link #markSaved()}.
     *
     * @throws com.google.gson.JsonSyntaxException if the text is not a JSON object
     */
    public void replaceWith(String text) {
        JsonElement parsed = JsonParser.parseString(text);
        if (!parsed.isJsonObject()) {
            throw new com.google.gson.JsonSyntaxException(file + " is not a JSON object");
        }
        root = parsed.getAsJsonObject();
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /** Whether the path names anything at all. */
    public boolean has(String path) {
        return get(path) != null;
    }    /** A string at a dotted path, or the fallback. An object where a string belongs is not a string. */
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

    /**
     * The element at a dotted path, or null.
     *
     * <p>A step that is a whole number names an <b>index</b> when what it follows is an array, and a
     * member name otherwise — which is what lets one path shape reach both {@code icon.item} and
     * {@code tasks.0.count}, the field the panel edits most. An index outside the array, or a step that
     * asks anything of a primitive, is simply not there.
     */
    public JsonElement get(String path) {
        JsonElement at = root;
        for (String step : path.split("\\.")) {
            if (at == null) {
                return null;
            }
            if (at.isJsonObject()) {
                at = at.getAsJsonObject().get(step);
            }
            else if (at.isJsonArray()) {
                JsonArray array = at.getAsJsonArray();
                Integer index = asIndex(step);
                at = index == null || index < 0 || index >= array.size() ? null : array.get(index);
            }
            else {
                return null;
            }
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
        // A whole number is written whole: "count": 8, not "count": 8.0. The value is the same number,
        // but the file is the author's, and 8.0 in a diff where 8 was is noise they did not make — the
        // same argument that rounds a dragged position to whole content units.
        JsonElement number = isWhole(value) ? new JsonPrimitive((long) value) : new JsonPrimitive(value);
        parent(path).add(leaf(path), number);
    }

    private static boolean isWhole(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value)
                && value == Math.floor(value) && Math.abs(value) <= 9.007199254740992E15;
    }

    public void setFlag(String path, boolean value) {
        parent(path).addProperty(leaf(path), value);
    }

    /**
     * Replaces whatever is at a path with this JSON, whatever shape it is.
     *
     * <p>The raw-editing write: a field whose value is an object, or a whole array element
     * ({@code "tasks.1"}), which the typed setters cannot carry. Where the leaf lands is decided by
     * what it lives in -- a member of an object, or an element of an array -- so one method writes
     * both without the caller knowing which it has.
     *
     * @throws UnwritablePath when the path names something no value could be written to
     */
    public void setJson(String path, JsonElement value) {
        JsonElement container = descend(path, true);
        if (container == null) {
            throw new UnwritablePath(path);
        }
        String leaf = leaf(path);
        if (container.isJsonArray()) {
            JsonArray array = container.getAsJsonArray();
            Integer index = asIndex(leaf);
            if (index == null || index < 0 || index >= array.size()) {
                throw new UnwritablePath(path);
            }
            array.set(index, value);
            return;
        }
        if (!container.isJsonObject()) {
            throw new UnwritablePath(path);
        }
        container.getAsJsonObject().add(leaf, value);
    }

    /**
     * Removes whatever is at the path. A path that is not there is not a problem.
     *
     * <p>Array-element aware like {@link #setJson}: {@code "tasks.1"} removes the task, not a member
     * named "1" from somewhere it never lived.
     */
    public void remove(String path) {
        JsonElement container = descend(path, false);
        if (container == null) {
            return;
        }
        String leaf = leaf(path);
        if (container.isJsonArray()) {
            JsonArray array = container.getAsJsonArray();
            Integer index = asIndex(leaf);
            if (index != null && index >= 0 && index < array.size()) {
                array.remove((int) index);
            }
            return;
        }
        if (container.isJsonObject()) {
            container.getAsJsonObject().remove(leaf);
        }
    }

    // ------------------------------------------------------------------
    // Array entries
    // ------------------------------------------------------------------

    /**
     * Inserts one object into the array at a path, creating the array if it is absent.
     *
     * <p>The editor's Add: a task, a reward, or a table's entry enters the list at a position. The
     * index is clamped to the array's own length, so "append" is {@code size()} and a stale index lands
     * at the end rather than throwing -- the position of an add is a preference, not an invariant.
     *
     * <h2>Dotted paths, and the one thing that may be created</h2>
     *
     * <p>{@code "tasks"} is the common case; {@code "rewards.2.inline.entries"} is a table's entries
     * inside a reward's own inline table, and {@code "entries"} is a table file's own list. A dotted
     * path's <b>intermediates must already be there</b> -- creating them would let a mistyped path
     * invent a {@code "rewards": {}} in a file that never had one, and a table grown that way has no
     * handle and nothing that can address it. The <b>terminal array</b> is the exception: a table the
     * author has just started has no {@code entries} yet, and refusing to create it would make the
     * first entry impossible to add.
     *
     * @throws UnwritablePath when the path cannot be walked, or when what is at its end is not an array
     */
    public void insert(String path, int index, JsonObject entry) {
        JsonArray array = arrayAt(path, true);
        insertAt(array, Math.max(0, Math.min(index, array.size())), entry);
    }

    /**
     * Inserts at an index, which {@code JsonArray} cannot do itself in this Gson version.
     *
     * <p>Append, shift the tail right, drop the element in -- the array is small (a chapter's tasks),
     * so the copy is nothing next to not having the operation.
     */
    private static void insertAt(JsonArray array, int index, JsonElement element) {
        array.add(element);
        for (int i = array.size() - 1; i > index; i--) {
            array.set(i, array.get(i - 1));
        }
        array.set(index, element);
    }

    /**
     * The array a list operation works on.
     *
     * <p>{@code create} decides what happens when the <b>terminal</b> member is absent: an insert makes
     * an empty array there (see {@link #insert}), while a remove or a move simply finds nothing. Either
     * way the walk to it never creates anything, and anything at the end that is not an array is a
     * refusal rather than something to write through.
     *
     * @return the array, or null when {@code create} is false and there is none
     * @throws UnwritablePath when the path cannot be walked, or ends at something that is not an array
     */
    private JsonArray arrayAt(String path, boolean create) {
        JsonElement container = descend(path, false);
        if (container == null) {
            // A step above the terminal is not there. For a remove or a move that is simply "nothing
            // to do"; for an insert it is a refusal, because the only thing an insert may create is
            // the terminal array itself.
            if (create) {
                throw new UnwritablePath(path);
            }
            return null;
        }
        String leaf = leaf(path);
        if (!container.isJsonObject()) {
            // The last step names an index in an array, or a field of a primitive: neither is a list
            // this can add to, and both are paths an author can write by mistake.
            throw new UnwritablePath(path);
        }
        JsonElement found = container.getAsJsonObject().get(leaf);
        if (found == null) {
            if (!create) {
                return null;
            }
            JsonArray created = new JsonArray();
            container.getAsJsonObject().add(leaf, created);
            return created;
        }
        if (!found.isJsonArray()) {
            throw new UnwritablePath(path);
        }
        return found.getAsJsonArray();
    }

    /** Removes the element at an index. False when there is nothing there to remove. */
    public boolean removeIndex(String path, int index) {
        JsonArray array;
        try {
            array = arrayAt(path, false);
        }
        catch (UnwritablePath notAList) {
            return false;
        }
        if (array == null || index < 0 || index >= array.size()) {
            return false;
        }
        array.remove(index);
        return true;
    }

    /**
     * Moves one element of the array at a path to another position in it.
     *
     * <p>Remove-then-insert, and the insert happens at {@code to} in the shortened list -- so the
     * element ends up at the index asked for whether it moved up or down. False when either end is
     * not a position in the array, and false for a path that is not there: a reorder of a list nothing
     * has is not an edit.
     */
    public boolean moveIndex(String path, int from, int to) {
        JsonArray array;
        try {
            array = arrayAt(path, false);
        }
        catch (UnwritablePath notAList) {
            return false;
        }
        if (array == null || from < 0 || from >= array.size() || to < 0 || to >= array.size()
                || from == to) {
            return false;
        }
        JsonElement moved = array.remove(from);
        insertAt(array, to, moved);
        return true;
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

    /**
     * The object a dotted path's last step lives in, creating the chain if it is absent.
     *
     * <p>An array step descends into an <b>existing</b> element and nothing else: an index past the end,
     * or into a member that is not an object, is a path nothing can be written to and is refused rather
     * than improvised around. Creating inside an array is a different edit from writing a field —
     * it grows the array, which is {@link #insert}'s business and not a field write's side effect.
     *
     * @throws UnwritablePath when the path names something no value could be written to — including a
     *     member that is already there as a non-object with more path left, which the previous version
     *     clobbered with a fresh object and would have destroyed an array with
     */
    private JsonObject parent(String path) {
        JsonElement at = descend(path, true);
        if (at == null || !at.isJsonObject()) {
            // An array here means the path names a whole list element with no member under it --
            // "tasks.0" is not a field any typed setter can write, and setJson is the one that can.
            throw new UnwritablePath(path);
        }
        return at.getAsJsonObject();
    }

    /**
     * Walks to the container a path's last step lives in, creating missing members when {@code create}
     * says to. The container is an object for an ordinary field, or an array when the last step is one
     * of its indices -- which is what lets {@link #setJson} and {@link #remove} write whole elements.
     *
     * <p>Creation is only ever "an object where nothing was". Anything already there keeps its type, so
     * a write can reshape a value it is about to replace at the leaf but can never silently replace the
     * <i>container</i> it has to walk through.
     */
    private JsonElement descend(String path, boolean create) {
        String[] steps = path.split("\\.");
        JsonElement at = root;
        for (int i = 0; i < steps.length - 1; i++) {
            String step = steps[i];
            if (at.isJsonArray()) {
                JsonArray array = at.getAsJsonArray();
                Integer index = asIndex(step);
                if (index == null || index < 0 || index >= array.size()
                        || !array.get(index).isJsonObject()) {
                    throw new UnwritablePath(path);
                }
                at = array.get(index);
                continue;
            }
            if (!at.isJsonObject()) {
                throw new UnwritablePath(path);
            }
            JsonObject object = at.getAsJsonObject();
            JsonElement next = object.get(step);
            if (next == null) {
                if (!create) {
                    return null;
                }
                JsonObject created = new JsonObject();
                object.add(step, created);
                at = created;
            }
            else if (next.isJsonObject() || next.isJsonArray()) {
                // An array mid-path is fine: the next step is its index. It is the *container* a write
                // lands in that decides whether the write is an element or a member, and the caller
                // is the one that knows which it asked for.
                at = next;
            }
            else {
                throw new UnwritablePath(path);
            }
        }
        return at;
    }

    /** A step that is a whole number, when it has to name an array index — else null. */
    private static Integer asIndex(String step) {
        if (step.isEmpty()) {
            return null;
        }
        for (int i = 0; i < step.length(); i++) {
            if (!Character.isDigit(step.charAt(i))) {
                return null;
            }
        }
        try {
            return Integer.parseInt(step);
        }
        catch (NumberFormatException absurdlyLong) {
            return null;
        }
    }

    /** Why a path refused a write: it names something no value could be written to. */
    public static final class UnwritablePath extends RuntimeException {

        UnwritablePath(String path) {
            super(path);
        }
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
