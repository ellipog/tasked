package dev.ellipog.tenet.quest.loot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The handles of the tables written <b>inline</b>, and how a copy of one is kept from colliding.
 *
 * <h2>Why a handle rather than a path</h2>
 *
 * <p>An inline table sits inside a reward, and a reward sits at a position in a list. Addressing one by
 * its position ({@code rewards.1.inline}) is addressing it by the thing that changes: delete the reward
 * above it and the path now names a different table, and every later edit lands in the wrong file —
 * silently, because the path still resolves to <i>something</i> that looks like a table. So an inline
 * table carries a {@code uid}, and the editor addresses it by that. A file's root table needs no handle:
 * its file name is its identity and files do not shuffle.
 *
 * <h2>Collisions, and the two rules that handle them</h2>
 *
 * <p>A handle can be duplicated: the quest editor's Copy duplicates a reward's JSON verbatim, and a raw
 * JSON edit can paste a table anywhere. Two rules, because the two situations are opposites:
 *
 * <ul>
 *   <li><b>On a write</b>, only handles that <i>clash</i> are re-minted ({@link #deduplicate}), against
 *       the handles that live outside the subtree being replaced ({@link #handlesOutside}). Re-minting
 *       everything would be the obvious rule and the wrong one: writing a table's own JSON back — which
 *       a raw edit does — carries the live handle, and renaming it would orphan the editor that is
 *       looking at it.</li>
 *   <li><b>On a copy of a whole file</b>, every handle is re-minted ({@link #remintAll}): the copy is a
 *       new file that nothing addresses yet, so there is no live handle to preserve and every reason to
 *       give the copy its own.</li>
 * </ul>
 *
 * <p>And when two inline tables in one owner do share a handle, the resolver refuses rather than picking
 * one — {@link #withUid} returns both paths so the refusal can name them, and the editor offers the
 * repair: {@link #newHandle}, written by the "give this table a new handle" action.
 */
public final class InlineTables {

    private InlineTables() {
    }

    /** One inline table found in an owner: where it is, and the handle it carries (may be empty). */
    public record Found(String path, String uid) {
    }

    /** A fresh handle. A UUID, because two authors pasting the same file must not agree by accident. */
    public static String newHandle() {
        return UUID.randomUUID().toString();
    }

    /** The inline tables of a quest: one under each of its {@code rewards}. */
    public static List<Found> inQuest(JsonObject quest) {
        List<Found> found = new ArrayList<>();
        JsonElement rewards = quest.get("rewards");
        if (rewards != null && rewards.isJsonArray()) {
            JsonArray array = rewards.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i).isJsonObject()) {
                    scanReward(array.get(i).getAsJsonObject(), "rewards." + i, found);
                }
            }
        }
        return List.copyOf(found);
    }

    /** The inline tables of a table file: one under each of its {@code entries}' rewards. */
    public static List<Found> inTable(JsonObject table) {
        List<Found> found = new ArrayList<>();
        JsonElement entries = table.get("entries");
        if (entries != null && entries.isJsonArray()) {
            JsonArray array = entries.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                if (!array.get(i).isJsonObject()) {
                    continue;
                }
                JsonElement reward = array.get(i).getAsJsonObject().get("reward");
                if (reward != null && reward.isJsonObject()) {
                    scanReward(reward.getAsJsonObject(), "entries." + i + ".reward", found);
                }
            }
        }
        return List.copyOf(found);
    }

    /** The ones carrying this handle. Two is a collision the caller must refuse, not resolve. */
    public static List<Found> withUid(List<Found> found, String uid) {
        List<Found> matches = new ArrayList<>();
        if (uid == null || uid.isBlank()) {
            return List.copyOf(matches);
        }
        for (Found one : found) {
            if (uid.equals(one.uid())) {
                matches.add(one);
            }
        }
        return List.copyOf(matches);
    }

    /** The handles in use, for a caller that wants to know what a new one must avoid. */
    public static Set<String> handles(List<Found> found) {
        Set<String> handles = new LinkedHashSet<>();
        for (Found one : found) {
            if (one.uid() != null && !one.uid().isBlank()) {
                handles.add(one.uid());
            }
        }
        return handles;
    }

    /**
     * The handles in use <b>outside</b> the subtree at a path.
     *
     * <p>What a write must be deduplicated against. The subtree being replaced is excluded because its
     * handles are going away with it: a raw edit that writes {@code rewards.2} carries that reward's own
     * table handle, and counting it as a clash would rename the table the author is editing.
     */
    public static Set<String> handlesOutside(List<Found> found, String excludedPath) {
        Set<String> outside = new LinkedHashSet<>();
        for (Found one : found) {
            if (excludedPath != null && !excludedPath.isEmpty()
                    && (one.path().equals(excludedPath) || one.path().startsWith(excludedPath + "."))) {
                continue;
            }
            if (one.uid() != null && !one.uid().isBlank()) {
                outside.add(one.uid());
            }
        }
        return outside;
    }

    /**
     * Re-mints every handle in a value that clashes with one already in use, or with one earlier in the
     * same value. Mutates the value, which is the fresh copy being written.
     *
     * @param taken the handles to avoid; copied, so the caller's set is not consumed
     * @return how many handles were re-minted, for a caller that wants to say so
     */
    public static int deduplicate(JsonElement value, Set<String> taken) {
        Set<String> used = new LinkedHashSet<>(taken);
        return remint(value, used, false);
    }

    /** Re-mints every handle in a copied file, whether it clashes or not. */
    public static int remintAll(JsonElement value) {
        return remint(value, new LinkedHashSet<>(), true);
    }

    /**
     * Re-mints every handle <b>inside</b> one table, leaving the table itself alone.
     *
     * <p>What a copied table file needs. {@link #remintAll} walks from the root, and a table file's root
     * <i>is</i> a table — it holds an {@code entries} array, which is how a table is recognised — so the
     * copy came back with a fresh {@code uid} at the top level. Both the schema and {@code rewards.md}
     * say a table in its own file is addressed by its file name and that the loader ignores a root
     * {@code uid} there: the field belongs to an inline table, and stamping one into a file is a field
     * an author did not write. The entries inside are a different matter — a nested inline table is a
     * second instance of itself in the copy, and needs a handle of its own.
     */
    public static int remintEntries(JsonObject table) {
        JsonElement entries = table.get("entries");
        return entries == null ? 0 : remint(entries, new LinkedHashSet<>(), true);
    }

    /**
     * The walk itself.
     *
     * <p>An inline table is recognised by its shape — an object holding an {@code entries} array, which
     * is what only a table has — rather than by the key it hangs under. That is what lets one walk
     * handle both the values a caller writes: a whole reward carrying an {@code inline} child, and an
     * inline table written directly ({@code rewards.2.inline}), whose root <i>is</i> the table. Both
     * shapes are reached, because the recursion descends into every child object either way.
     *
     * <p>A table with no handle gets one even when nothing clashes: an inline table no editor can
     * address is one nothing can fix, and the moment something writes it is the moment it can be given
     * a handle.
     */
    private static int remint(JsonElement element, Set<String> used, boolean all) {
        int reminted = 0;
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (isTable(object)) {
                JsonElement uid = object.get("uid");
                String current = uid != null && uid.isJsonPrimitive() && uid.getAsJsonPrimitive().isString()
                        ? uid.getAsString() : "";
                if (all || current.isBlank() || !used.add(current)) {
                    String fresh = newHandle();
                    object.addProperty("uid", fresh);
                    used.add(fresh);
                    reminted++;
                }
            }
            // A copy of the entries, because a value is replaced while the map is walked.
            for (Map.Entry<String, JsonElement> child : new ArrayList<>(object.entrySet())) {
                reminted += remint(child.getValue(), used, all);
            }
        }
        else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                reminted += remint(child, used, all);
            }
        }
        return reminted;
    }

    /** Whether an object is an inline table: the one shape that holds an {@code entries} array. */
    private static boolean isTable(JsonObject object) {
        JsonElement entries = object.get("entries");
        return entries != null && entries.isJsonArray();
    }

    /**
     * One reward's inline table, and whatever its own entries hold.
     *
     * <p>The path a caller gets is the path <b>to the inline object</b> — {@code rewards.2.inline} — and
     * it is what a mutation is prefixed with. A nested table's path runs through the outer one
     * ({@code rewards.2.inline.entries.0.reward.inline}), which is exactly what makes an arbitrarily
     * nested table addressable.
     */
    private static void scanReward(JsonObject reward, String rewardPath, List<Found> found) {
        JsonElement inline = reward.get("inline");
        if (inline == null || !inline.isJsonObject()) {
            return;
        }
        JsonObject table = inline.getAsJsonObject();
        String path = rewardPath + ".inline";
        found.add(new Found(path, string(table.get("uid"))));
        JsonElement entries = table.get("entries");
        if (entries == null || !entries.isJsonArray()) {
            return;
        }
        JsonArray array = entries.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isJsonObject()) {
                continue;
            }
            JsonElement nested = array.get(i).getAsJsonObject().get("reward");
            if (nested != null && nested.isJsonObject()) {
                scanReward(nested.getAsJsonObject(), path + ".entries." + i + ".reward", found);
            }
        }
    }

    private static String string(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString() : "";
    }
}
