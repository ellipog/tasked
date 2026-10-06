package dev.ellipog.tasked.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the editor's fields have asked for and the server has not answered yet.
 *
 * <h2>Why this exists: a stepper that waited for the round trip could not be spammed</h2>
 *
 * <p>The card draws its field values from the chapter replica, and the replica is refreshed only when
 * the tree revision moves and no faster than {@code ClientChapterReplica.RETRY_MILLIS}. A nudge that
 * only sent an op therefore computed every press from the same stale copy — five fast presses sent the
 * same {@code +1} five times and the number never moved, which is what "the editor freezes when I edit
 * quickly" was. A value is remembered here the moment it is asked for, the controls and rows read it,
 * and the next press accumulates from it.
 *
 * <h2>Owners, and the two ways a reader is wired</h2>
 *
 * <p>A pending value belongs to a chapter and to an <b>owner</b> inside it: a quest id for a quest
 * field, or {@link #CHAPTER_OWNER} for the chapter's own file. Two ways to read it, because the two
 * surfaces are built differently: a quest field is read per field, so {@link #value} is consulted
 * where the value is read; the Chapter tab builds all of its rows from the whole tree at once, so
 * {@link #overlaid} hands it a copy with the pending values applied and every row sees them.
 *
 * <p>A third shape arrived with the reward-table panels: a panel whose values live in a file that is
 * <b>not</b> the chapter's copy — a table's own file, or an inline table inside a quest — and whose
 * paths are relative to that table. It reads through {@link #overlaid} like the Chapter tab does, and
 * converges through {@link #reconcileOwner} against the copy it is actually drawing, because
 * {@link #reconcile} compares <i>every</i> draft in a chapter against the chapter's values and would
 * judge a table's drafts by the wrong file.
 *
 * <h2>Why the expiry rule is not {@code SettingsDraft}'s</h2>
 *
 * <p>The settings page's preview draft expires the moment a newer tree arrives, because its preview
 * reads the tree, which arrives in milliseconds. These fields read the replica, which lags by up to its
 * retry window — so expiry on a revision move would drop a burst's last value while the copy still
 * held the first one, and the number would jump backwards. The rule here is therefore
 * <b>convergence</b>: a draft is dropped when the chapter's copy arrives holding the drafted value.
 * A newer copy that disagrees is ignored inside {@link #STALE_MILLIS}, because a burst's own copies
 * can land mid-burst and disagree with the newest ask for a moment; past that it wins, so a refusal
 * whose reply was lost, or another author's edit, cannot be masked past the next tree revision that
 * moves.
 *
 * <h2>What is not here</h2>
 *
 * <p>The commit itself. The screen sends the operation; this only remembers what was asked for — the
 * same split {@code SettingsDraft} documents, and for the same reason. A group's fields are not
 * drafted either, and cannot be: a group's file is not in the replica, so there is nothing here to
 * converge against.
 */
public final class FieldDraft {

    /** The owner of a chapter's own fields: its file, not a quest. */
    public static final String CHAPTER_OWNER = "#chapter";

    /**
     * The book's own settings: the pack's {@code index.json} settings block, edited from the Book
     * section.
     *
     * <p>A second owner rather than a second draft class, because a pending value is a pending value —
     * the only difference is which file the server writes. The chapter a book draft is scoped to is the
     * open one, the only scope this draft has; the value converges like any other and expires if no
     * copy confirms it.
     */
    public static final String BOOK_OWNER = "#book";

    /**
     * How long a draft may outlive its last write while the copy disagrees, before the copy wins.
     *
     * <p>Longer than any burst of presses (each write refreshes the clock) and longer than the
     * replica's retry window, so a copy that merely lags cannot drop a live draft.
     */
    public static final long STALE_MILLIS = 4000L;

    private record Pending(String chapter, String owner, String path, JsonElement value,
                           long revision, long atMillis) {
    }

    private final Map<String, Pending> pending = new LinkedHashMap<>();

    /** Bumped on every write, so the overlay cache below knows its copy is stale. */
    private long version;

    /** The last overlay per chapter+owner, keyed by the version and the tree it was made from. */
    private record Cached(long version, JsonObject source, JsonObject result) {
    }

    private final Map<String, Cached> overlays = new HashMap<>();

    /**
     * One pending value's key: the chapter, the owner and the path.
     *
     * <h2>Why the chapter is in it, and what its absence cost</h2>
     *
     * <p>It used to be {@code owner\0path} alone, and the two fixed owners — {@link #CHAPTER_OWNER} and
     * {@link #BOOK_OWNER} — are the <b>same string in every chapter</b>. So chapter A's pending
     * {@code #chapter} field and chapter B's were one entry: the second write overwrote the first, and the
     * chapter guard in {@link #value} then reported the overwritten draft as simply <i>absent</i>. A
     * pending edit that vanishes reads exactly like an edit that was never made, so nothing logged and
     * nothing looked wrong — the value reverted to the file's and the author's press appeared to do
     * nothing.
     *
     * <p>The chapter has to be here rather than only in the guard because the guard is a <i>filter over a
     * shared slot</i>, and a filter cannot tell "not mine" from "not there". One key per chapter is what
     * makes those two different answers.
     */
    private static String key(String chapter, String owner, String path) {
        return chapter + "\u0000" + owner + "\u0000" + path;
    }

    /**
     * Remembers the value an edit asked for, until the server's copy holds the same thing.
     *
     * <p>A null chapter or owner is refused rather than stored: nothing can converge against a chapter
     * that is not there, and a pending value with no chapter would answer for every one.
     */
    public void set(String chapter, String owner, String path, JsonElement value,
                    long revision, long nowMillis) {
        if (chapter == null || owner == null) {
            return;
        }
        pending.put(key(chapter, owner, path),
                new Pending(chapter, owner, path, value, revision, nowMillis));
        version++;
    }

    /** The pending value for this field, or null. Only a draft for this chapter and owner answers. */
    public JsonElement value(String chapter, String owner, String path) {
        Pending entry = pending.get(key(chapter, owner, path));
        return entry != null && Objects.equals(entry.chapter(), chapter) ? entry.value() : null;
    }

    /** The pending text for a field, or the server's. */
    public String text(String chapter, String owner, String path, String server) {
        JsonElement drafted = value(chapter, owner, path);
        return drafted != null && drafted.isJsonPrimitive() ? drafted.getAsString() : server;
    }

    /** The pending number for a field, or the server's. */
    public int number(String chapter, String owner, String path, int server) {
        JsonElement drafted = value(chapter, owner, path);
        return drafted != null && drafted.isJsonPrimitive() && drafted.getAsJsonPrimitive().isNumber()
                ? drafted.getAsInt() : server;
    }

    /** The same, for a fractional field. */
    public double decimal(String chapter, String owner, String path, double server) {
        JsonElement drafted = value(chapter, owner, path);
        return drafted != null && drafted.isJsonPrimitive() && drafted.getAsJsonPrimitive().isNumber()
                ? drafted.getAsDouble() : server;
    }

    /** The pending flag for a field, or the server's. */
    public boolean flag(String chapter, String owner, String path, boolean server) {
        JsonElement drafted = value(chapter, owner, path);
        return drafted != null && drafted.isJsonPrimitive() && drafted.getAsJsonPrimitive().isBoolean()
                ? drafted.getAsBoolean() : server;
    }

    /**
     * The pending list for a field, or the server's.
     *
     * <p>How the canvas reads a pending {@code dependsOn}: the tree carries the dependency list as a
     * plain value, so a list edit is a draft like any other, and the edges are drawn from this.
     */
    public List<String> strings(String chapter, String owner, String path, List<String> server) {
        JsonElement drafted = value(chapter, owner, path);
        if (drafted == null || !drafted.isJsonArray()) {
            return server;
        }
        List<String> out = new ArrayList<>();
        for (JsonElement element : drafted.getAsJsonArray()) {
            if (element.isJsonPrimitive()) {
                out.add(element.getAsString());
            }
        }
        return List.copyOf(out);
    }

    /**
     * The tree with this owner's pending values applied, for a caller that builds its view once from
     * the whole tree: the Chapter tab's rows and the quest card both read the whole tree, so an overlay
     * is the one place every read can see the pending value, at the same paths the ops write.
     *
     * <p><b>Cached</b>, because the card asks for it many times a frame: the copy is remade only when
     * the draft has been written since, or when the tree itself has been replaced by a newer replica.
     * Nothing pending returns the tree itself, so the common case copies nothing at all.
     */
    public JsonObject overlaid(String chapter, String owner, JsonObject tree) {
        if (tree == null || pending.isEmpty()) {
            return tree;
        }
        String key = chapter + "\u0000" + owner;
        Cached cached = overlays.get(key);
        if (cached != null && cached.version() == version && cached.source() == tree) {
            return cached.result();
        }
        JsonObject copy = null;
        for (Pending entry : pending.values()) {
            if (!entry.chapter().equals(chapter) || !entry.owner().equals(owner)) {
                continue;
            }
            if (copy == null) {
                copy = tree.deepCopy();
            }
            setPath(copy, entry.path(), entry.value());
        }
        JsonObject result = copy == null ? tree : copy;
        overlays.put(key, new Cached(version, tree, result));
        return result;
    }

    /**
     * Writes one dotted path into the copy: intermediate containers are created as needed, and a null
     * value removes the leaf — which is how a cleared field travels, and the overlay has to read the
     * same way the file will.
     *
     * <h2>Arrays are the reason this is not a plain walk</h2>
     *
     * <p>A task's path is {@code tasks.0.count}, and a walk that treated {@code tasks} as an object
     * member <b>replaced the whole list with an object</b> — so one nudge made every task in the card
     * vanish until the replica arrived and the draft expired. Every list in the format is an array
     * ({@code tasks}, {@code rewards}, {@code conditions}, {@code dependsOn}), so a numeric step is
     * read as an index and the container it needs is the kind its parent already is.
     */
    private static void setPath(JsonObject root, String path, JsonElement value) {
        String[] steps = path.split("\\.");
        for (String step : steps) {
            if (step.isEmpty()) {
                // A doubled or trailing dot names nothing; writing it would add a stray empty member.
                return;
            }
        }
        JsonElement node = root;
        for (int i = 0; i < steps.length - 1; i++) {
            node = descend(node, steps[i], isIndex(steps[i + 1]));
        }
        write(node, steps[steps.length - 1], value);
    }

    /**
     * The container for a step, created (and kept in its parent) when absent: an object member, or an
     * array element when the step is an index. A step that cannot be resolved — a name inside an array,
     * a container inside a primitive — answers with a throwaway object, so the write lands nowhere
     * rather than corrupting a value the author has.
     */
    private static JsonElement descend(JsonElement node, String step, boolean nextIsIndex) {
        if (node instanceof JsonObject object) {
            JsonElement found = object.get(step);
            if (found != null && !found.isJsonNull()) {
                return found;
            }
            JsonElement created = nextIsIndex ? new JsonArray() : new JsonObject();
            object.add(step, created);
            return created;
        }
        if (node instanceof JsonArray array) {
            int index = indexOf(step);
            if (index < 0) {
                return new JsonObject();
            }
            while (array.size() <= index) {
                array.add(JsonNull.INSTANCE);
            }
            JsonElement found = array.get(index);
            if (found != null && !found.isJsonNull()) {
                return found;
            }
            JsonElement created = nextIsIndex ? new JsonArray() : new JsonObject();
            array.set(index, created);
            return created;
        }
        return new JsonObject();
    }

    /** The leaf: an object member, or an array slot when the parent is an array. */
    private static void write(JsonElement node, String step, JsonElement value) {
        if (node instanceof JsonObject object) {
            if (value == null) {
                object.remove(step);
            }
            else {
                object.add(step, value);
            }
            return;
        }
        if (node instanceof JsonArray array) {
            int index = indexOf(step);
            if (index < 0) {
                return;
            }
            while (array.size() <= index) {
                array.add(JsonNull.INSTANCE);
            }
            // Null keeps the slot: an array's shape is the list, and a slot the server cleared is an
            // absent value in place rather than a shorter list.
            array.set(index, value == null ? JsonNull.INSTANCE : value);
        }
    }

    private static boolean isIndex(String step) {
        return indexOf(step) >= 0;
    }

    /** The step as an array index, or -1 when it is not one. */
    private static int indexOf(String step) {
        if (step.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < step.length(); i++) {
            if (!Character.isDigit(step.charAt(i))) {
                return -1;
            }
        }
        try {
            return Integer.parseInt(step);
        }
        catch (NumberFormatException overflow) {
            return -1;
        }
    }

    /**
     * Forgets every draft about a chapter — a refusal, or leaving it — and the overlays made for it.
     */
    public void forgetChapter(String chapter) {
        pending.values().removeIf(entry -> Objects.equals(entry.chapter(), chapter));
        overlays.keySet().removeIf(key -> key.startsWith(chapter + "\u0000"));
        version++;
    }

    /**
     * Forgets one owner's drafts under a list — for a structural edit that shifts the indices they
     * name.
     *
     * <p>A draft at {@code tasks.2.count} names a position, not a task. Inserting, removing or
     * reordering tasks moves that position onto a different task, and the draft would then draw — and
     * a later nudge would <b>commit</b> — a value the author never chose onto the wrong row. The
     * list's shape changed, so every pending value indexed inside it is no longer about anything.
     *
     * <p>This covers the local gesture that makes the shift. Another author's shift is not visible
     * here at all, so {@link #reconcile} carries an out-of-range guard for the part of it a copy can
     * show — a list that came back shorter than the draft's index. A same-length reorder by another
     * author is not detectable from a copy, and the {@code STALE_MILLIS} backstop is what ends it.
     */
    public void forgetList(String chapter, String owner, String member) {
        String prefix = member + ".";
        boolean removed = pending.values().removeIf(entry ->
                Objects.equals(entry.chapter(), chapter) && Objects.equals(entry.owner(), owner)
                        && entry.path().startsWith(prefix));
        if (removed) {
            version++;
        }
    }

    /** Forgets everything — the card closing, or the client leaving the world. */
    public void clear() {
        pending.clear();
        overlays.clear();
        version++;
    }

    /** Whether anything is pending. */
    public boolean isEmpty() {
        return pending.isEmpty();
    }

    /**
     * How many times this draft has been written to, as one number.
     *
     * <p>Exposed because a caller that builds something expensive from a drafted value needs to know when
     * to build it again, and the draft is the only thing that knows: the card's layout is built from the
     * quest's fields with the draft laid over them, so a stepper press has to rebuild it — and the tree
     * revision does not move for a write still in flight. The counter only ever goes up, so a caller may
     * snapshot it and compare.
     */
    public long version() {
        return version;
    }

    /**
     * The same, for one owner, against the copy that owner's values actually come from.
     *
     * <p>For a panel whose file is not the chapter's: a reward table's own file, or an inline table
     * inside a quest. {@link #reconcile} walks every draft in the chapter and asks the chapter's copy
     * about each — which is right for the card and wrong here, because a table's values are not in that
     * copy at all. Same rules, one owner.
     */
    public void reconcileOwner(String chapter, String owner, Long copyRevision, ServerValues values,
                               long nowMillis) {
        if (chapter == null || owner == null || copyRevision == null) {
            return;
        }
        boolean removed = pending.values().removeIf(entry ->
                Objects.equals(entry.chapter(), chapter) && Objects.equals(entry.owner(), owner)
                        && stale(entry, copyRevision, values, nowMillis));
        if (removed) {
            version++;
        }
    }

    /**
     * Forgets one owner's drafts, and the overlays made for it.
     *
     * <p>What an undo needs: the server has just put the file back, and a draft still holding the value
     * the author undid would keep drawing it — a Ctrl+Z that appears to do nothing. Also what a refusal
     * needs, for the same reason from the other side: the edit did not stick, so nothing should pretend
     * it did.
     */
    public void forgetOwner(String chapter, String owner) {
        if (owner == null) {
            return;
        }
        boolean removed = pending.values().removeIf(entry ->
                Objects.equals(entry.chapter(), chapter) && Objects.equals(entry.owner(), owner));
        overlays.keySet().removeIf(key -> Objects.equals(key, chapter + "\u0000" + owner));
        if (removed) {
            version++;
        }
    }

    /**
     * Reconciles the drafts against one chapter's replica copy.
     *
     * @param chapter      the chapter the copy is for
     * @param copyRevision the revision the copy was taken at, or null when there is no copy
     * @param values       the copy's value for an owner's field, or null when it holds none
     * @param nowMillis    the caller's clock, so a test can drive the backstop without waiting
     */
    public void reconcile(String chapter, Long copyRevision, ServerValues values, long nowMillis) {
        if (chapter == null || copyRevision == null) {
            return;
        }
        boolean removed = pending.values().removeIf(entry ->
                Objects.equals(entry.chapter(), chapter) && stale(entry, copyRevision, values, nowMillis));
        if (removed) {
            // Only a removal changes what an overlay should hold: this runs every frame, and bumping
            // the version unconditionally would throw the cache away on every one of them.
            version++;
        }
    }

    /**
     * Whether a draft is finished: the copy has caught up with it, or has disagreed for too long.
     *
     * <p>One predicate for both reconcilers, because the rules are one rule — the difference between
     * them is which drafts they are asked about, not what counts as done.
     */
    private static boolean stale(Pending entry, long copyRevision, ServerValues values, long nowMillis) {
        if (copyRevision <= entry.revision()) {
            return false;
        }
        // A copy newer than the write that no longer has the list position this draft names: the
        // shape moved under it, most likely by another author (a local shift forgets first). The
        // draft is about nothing now, and letting it live would draw -- and commit -- its value on
        // whichever entry the position landed on.
        if (outOfRange(entry.path(), entry.owner(), values)) {
            return true;
        }
        JsonElement server = values.value(entry.owner(), entry.path());
        // A cleared field is a draft whose value is null, and it converges when the copy holds
        // nothing there: `server.equals(null)` would never be true, so the cleared case needs its
        // own comparison or it could only ever expire on the clock.
        boolean same = entry.value() == null
                ? server == null : server != null && server.equals(entry.value());
        if (same) {
            return true;
        }
        // A copy newer than the ask that disagrees: a refusal whose reply was missed, or another
        // author's edit. Bounded by time, because a burst's own copies can land mid-burst, and
        // because a refusal does not move the tree -- so this is the only guard for one whose
        // reply never arrived.
        return nowMillis - entry.atMillis() > STALE_MILLIS;
    }

    /**
     * Whether the path names a list position the copy no longer has.
     *
     * <p>Walks the path and asks the copy for each container a step indexes into: {@code tasks.0.count}
     * is out of range when the copy's {@code tasks} is missing, is not an array, or holds nothing at
     * zero. Only the length is checked — a same-length reorder leaves every position present, and
     * nothing in the copy says the entry at one moved, so that case is the backstop's.
     */
    private static boolean outOfRange(String path, String owner, ServerValues values) {
        String[] steps = path.split("\\.");
        StringBuilder prefix = new StringBuilder();
        for (String step : steps) {
            int index = indexOf(step);
            if (index >= 0) {
                JsonElement list = prefix.length() == 0 ? null : values.value(owner, prefix.toString());
                if (!(list instanceof JsonArray array) || array.size() <= index) {
                    return true;
                }
            }
            if (prefix.length() > 0) {
                prefix.append('.');
            }
            prefix.append(step);
        }
        return false;
    }

    /** The server's value for an owner's field, as the replica copy holds it. */
    public interface ServerValues {

        JsonElement value(String owner, String path);
    }
}
