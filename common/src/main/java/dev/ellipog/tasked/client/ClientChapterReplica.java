package dev.ellipog.tasked.client;

import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A chapter's files, on a client, as the server holds them: one JSON tree per quest.
 *
 * <h2>Why a replica rather than the synced tree</h2>
 *
 * <p>The synced tree is what the <i>canvas</i> draws — ids, titles, positions, states — and it is a summary:
 * a property panel needs the fields the summary leaves out, including the ones this build has never heard of,
 * and it needs to say what a field <i>is</i> rather than guess. So an editor's client gets the chapter's own
 * trees, read-only, and edits them by asking: one opinion about the format, on the side that owns the files,
 * with the client holding a copy only to display.
 *
 * <h2>Read-only, and enforced by having no writer</h2>
 *
 * <p>There is no setter here on purpose. A panel that changed a tree in place would be a second author, and
 * the files it thought it had changed are on somebody else's disk — so the shape of the class is the rule:
 * ask for a copy, read it, send an operation if you want it changed.
 *
 * <h2>One request per revision, which is what {@link #claim} is for</h2>
 *
 * <p>The screen asks every frame whether it needs to re-request, and a frame is 16 milliseconds: without a
 * record of what has already been asked for, that is a request flood. {@code claim} answers "yes, ask" once
 * per revision and remembers it, and the answer arriving is what makes the copy current. A replica that
 * arrives while a newer tree is still in flight is briefly behind, and corrects itself when that tree lands
 * and the revision moves again.
 */
public final class ClientChapterReplica {

    /**
     * One chapter's copy: the revision it was taken at, every quest's own tree, and the chapter's own
     * file -- which is where its title, icon and rules live, so a chapter panel has something to edit.
     */
    public record Copy(long revision, Map<String, JsonObject> quests, JsonObject chapterTree) {
    }

    private static final Map<String, Copy> loaded = new ConcurrentHashMap<>();

    /** The revision each chapter has already been asked for, so a frame cannot become a flood. */
    private static final Map<String, Long> asked = new ConcurrentHashMap<>();

    private ClientChapterReplica() {
    }

    /**
     * Whether to request this chapter at this revision, recording the request so the next frame says no.
     *
     * <p>One method rather than a question and a note, because a caller that asks and forgets to note would
     * send a request per frame — which works, and is why nobody would notice until a server log filled up.
     */
    public static boolean claim(String chapter, long revision) {
        if (chapter == null || chapter.isBlank()) {
            return false;
        }
        Copy copy = loaded.get(chapter);
        Long outstanding = asked.get(chapter);
        boolean wants = (copy == null || copy.revision() != revision)
                && (outstanding == null || outstanding != revision);
        if (wants) {
            asked.put(chapter, revision);
        }
        return wants;
    }

    /** Takes the server's answer. Called by the payload handler. */
    public static void accept(String chapter, String questsJson, String chapterJson, long revision) {
        Map<String, JsonObject> quests = new LinkedHashMap<>();
        try {
            JsonObject root = com.google.gson.JsonParser.parseString(questsJson).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> entry : root.entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    quests.put(entry.getKey(), entry.getValue().getAsJsonObject());
                }
            }
        }
        catch (RuntimeException malformed) {
            // Nothing readable: better an empty copy than a half-parsed one, and the panel will say it has
            // no fields rather than showing fields from a chapter that is not this one.
            quests.clear();
        }
        JsonObject chapterTree = new JsonObject();
        try {
            JsonObject parsed = com.google.gson.JsonParser.parseString(chapterJson).getAsJsonObject();
            chapterTree = parsed;
        }
        catch (RuntimeException malformed) {
            // An empty chapter tree is a panel with nothing to show, which is the honest answer for a
            // file that did not arrive readable.
        }
        loaded.put(chapter, new Copy(revision, Map.copyOf(quests), chapterTree));
    }

    /** The chapter's own file from the copy, or an empty object. */
    public static JsonObject chapterTree(String chapter) {
        Copy copy = chapter == null ? null : loaded.get(chapter);
        return copy == null ? new JsonObject() : copy.chapterTree();
    }

    /** One quest's tree from the copy, or null when the chapter or the quest is not in it. */
    public static JsonObject quest(String chapter, String id) {
        Copy copy = chapter == null ? null : loaded.get(chapter);
        return copy == null || id == null ? null : copy.quests().get(id);
    }

    /** The copy of a chapter, or null. */
    public static Copy of(String chapter) {
        return chapter == null ? null : loaded.get(chapter);
    }

    /** Forgets everything: called when the client leaves the world it was reading. */
    public static void clear() {
        loaded.clear();
        asked.clear();
    }
}
