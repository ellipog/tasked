package dev.ellipog.tasked.client.dev;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The editor's clipboard: the quests the author has copied, as their own trees.
 *
 * <h2>Why it is a session and not a chapter</h2>
 *
 * <p>Because cross-chapter paste is the one thing the clipboard exists for -- the plan's own words are
 * that it is what authors actually want -- and a clipboard that a chapter switch empties is a clipboard
 * that cannot cross anything. It is static for the same reason the caches are: it belongs to the game
 * session, not to one screen's lifetime, and the screen is closed and reopened far more often than an
 * author copies.
 *
 * <h2>What it holds, and what it is honest about</h2>
 *
 * <p>The trees themselves -- the same JSON the replica holds, deep-copied so a later edit of the source
 * does not reach into the clipboard. It knows nothing about ids, positions or chapters: the paste op is
 * the server's to land, under a fresh id, wherever the author is looking. Clearing on leaving a world is
 * the caller's call, like the other client-side caches.
 */
public final class ClientEditorClipboard {

    private static final List<JsonObject> quests = new ArrayList<>();

    private ClientEditorClipboard() {
    }

    /** Puts quests on the clipboard, replacing what was there. Copies, so the source cannot reach in. */
    public static void copy(List<JsonObject> trees) {
        quests.clear();
        for (JsonObject tree : trees) {
            quests.add(tree.deepCopy());
        }
    }

    /** The copied trees, in the order they were copied. */
    public static List<JsonObject> quests() {
        return List.copyOf(quests);
    }

    public static boolean isEmpty() {
        return quests.isEmpty();
    }

    public static int size() {
        return quests.size();
    }

    /** Empties it. For leaving a world, where another server's quests are not this one's to paste. */
    public static void clear() {
        quests.clear();
    }
}
