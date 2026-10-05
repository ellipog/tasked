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
 * <p>There is no setter here on purpose. A panel that changed a tree in place would be a second author, and the
 * files it thought it had changed are on somebody else's disk — so the shape of the class is the rule: ask for
 * a copy, read it, send an operation if you want it changed.
 *
 * <h2>Asking again, which is the half that had to change</h2>
 *
 * <p>The screen asks every frame whether it needs to re-request, and a frame is 16 milliseconds: without a
 * record of what has been asked, that is a request flood. This used to answer "yes, ask" once per
 * (chapter, revision) and then <b>never again</b> — so a request that was refused, or answered with an empty
 * chapter file, left the panel saying "the copy has not arrived yet" for good, because nothing but a tree
 * revision could re-arm it. A player saw exactly that: an editor that never picked anything up.
 *
 * <p>So the rule is now: <b>ask again while there is no usable copy, but no faster than
 * {@link #RETRY_MILLIS}</b>. A usable copy is one taken at the current revision whose chapter tree is not
 * empty — an empty one is the server saying it has nothing to show, not an answer. A refusal is remembered
 * and shown in the panel, so the placeholder tells the truth instead of claiming a copy is still in flight.
 */
public final class ClientChapterReplica {

    /** How long a chapter waits before asking again, in milliseconds. */
    public static final long RETRY_MILLIS = 2000L;

    /**
     * One chapter's copy: the revision it was taken at, every quest's own tree, and the chapter's own
     * file -- which is where its title, icon and rules live, so a chapter panel has something to edit.
     */
    public record Copy(long revision, Map<String, JsonObject> quests, JsonObject chapterTree) {
    }

    private static final Map<String, Copy> loaded = new ConcurrentHashMap<>();

    /** When each chapter was last asked for, so a frame cannot become a flood and a failure can retry. */
    private static final Map<String, Long> attempted = new ConcurrentHashMap<>();

    /**
     * The revision each chapter was last asked for.
     *
     * <p>The gate is per revision, not per clock: a <b>newer</b> revision is a new question and may be
     * asked at once, while the retry window stays as the backstop for the <i>same</i> one — a refusal,
     * or a chapter whose file has not arrived. Throttling by time alone meant a revision arriving inside
     * the window could not be fetched at all, so a chapter's copy could sit stale while the tree moved
     * under it.
     */
    private static final Map<String, Long> attemptedRevision = new ConcurrentHashMap<>();

    /** The last refusal the server sent about each chapter, for the panel to say instead of guessing. */
    private static final Map<String, String> refusals = new ConcurrentHashMap<>();

    private ClientChapterReplica() {
    }

    /**
     * Whether to request this chapter, recording the attempt so the next frame says no.
     *
     * <p>One method rather than a question and a note, because a caller that asks and forgets to note would
     * send a request per frame — which works, and is why nobody would notice until a server log filled up.
     *
     * @param nowMillis the caller's clock, so a test can drive the retry window without waiting
     */
    public static boolean claim(String chapter, long revision, long nowMillis) {
        if (chapter == null || chapter.isBlank()) {
            return false;
        }
        Copy copy = loaded.get(chapter);
        if (copy != null && copy.revision() == revision && usable(copy)) {
            return false;
        }
        // Asked for this revision already: the retry window is the backstop. A different revision is a
        // different question and goes at once -- see `attemptedRevision`.
        Long askedFor = attemptedRevision.get(chapter);
        if (askedFor != null && askedFor == revision) {
            Long last = attempted.get(chapter);
            if (last != null && nowMillis - last < RETRY_MILLIS) {
                return false;
            }
        }
        attempted.put(chapter, nowMillis);
        attemptedRevision.put(chapter, revision);
        return true;
    }

    /**
     * Whether a copy is one the panel can show: current, and with a chapter file in it.
     *
     * <p>The empty test is the one that matters. The server sends {@code "{}"} when it has no chapter file
     * to send, and treating that as an answer is how a panel ends up permanently claiming a copy is still
     * on its way while the server has already said it has none.
     */
    private static boolean usable(Copy copy) {
        return !copy.chapterTree().isEmpty();
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
            chapterTree = com.google.gson.JsonParser.parseString(chapterJson).getAsJsonObject();
        }
        catch (RuntimeException malformed) {
            // An empty chapter tree is a panel with nothing to show, which is the honest answer for a
            // file that did not arrive readable -- and `usable` keeps `claim` asking rather than settling.
        }
        loaded.put(chapter, new Copy(revision, Map.copyOf(quests), chapterTree));
        refusals.remove(chapter);
    }

    /**
     * Remembers that the server refused this chapter — a permission, or no such chapter — so the panel can
     * say so.
     *
     * <p>Recorded rather than only toasted, because the refusal is the answer to the question the panel is
     * visibly failing to answer: without it the placeholder says "has not arrived yet" forever, when the
     * truth is "the server said no".
     */
    public static void refuse(String chapter, String message) {
        if (chapter != null && !chapter.isBlank() && message != null && !message.isBlank()) {
            refusals.put(chapter, message);
        }
    }

    /** The last refusal about this chapter, or null. Shown by the panel's placeholder. */
    public static String refusal(String chapter) {
        return chapter == null ? null : refusals.get(chapter);
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
        attempted.clear();
        attemptedRevision.clear();
        refusals.clear();
    }
}
