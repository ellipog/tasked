package dev.ellipog.tasked.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A named table's file, on a client, as the server holds it.
 *
 * <h2>The counterpart of {@link ClientChapterReplica}, and why it is a second one</h2>
 *
 * <p>A chapter's replica answers "what does this chapter's folder hold"; this answers "what does this one
 * file hold", and the two are asked by different panels at different times. What they share is the
 * <b>shape</b> of the answer, and the retry rule that took a bug report to get right: ask again while
 * there is no usable copy, but no faster than {@link #RETRY_MILLIS}, or a screen that asks every frame
 * becomes a request flood.
 *
 * <p>Read-only, and enforced by having no writer: a panel that changed this copy would be a second
 * author, and the file it thought it had changed is on somebody else's disk. An edit is an operation,
 * and this is only what is drawn while waiting for one.
 *
 * <p>The copy is <b>text</b> rather than a decoded table, for the reason the chapter replica gives: the
 * client's copy is a copy rather than a translation, so a table written by a newer build is still
 * something an author can look at.
 */
public final class ClientTableReplica {

    /** How long a table waits before asking again, in milliseconds. The chapter panel's own number. */
    public static final long RETRY_MILLIS = 2000L;

    /**
     * One table's copy: the revision it was taken at, the file's own text, and who points at it.
     *
     * <p>{@code usedBy} is the server's own referrer walk — the same list its delete guard refuses with —
     * sent with the copy because it is a property of the file rather than of the text: it changes when
     * another quest is edited, which is not when this table changes.
     */
    public record Copy(long revision, String json, JsonObject root, java.util.List<String> usedBy) {
    }

    private static final Map<String, Copy> loaded = new ConcurrentHashMap<>();
    private static final Map<String, Long> attempted = new ConcurrentHashMap<>();

    /**
     * The revision each table was last asked for.
     *
     * <p>The gate is per revision, not per clock: a <b>newer</b> revision is a new question and may be
     * asked at once, while the retry window stays as the backstop for the <i>same</i> one — a refusal,
     * or a file that has not appeared yet. Throttling by time alone meant a revision arriving inside the
     * window could not be fetched at all: with edits closer together than the window the panel's copy
     * went stale and stayed stale, which is what "the selected table lags behind" was.
     */
    private static final Map<String, Long> attemptedRevision = new ConcurrentHashMap<>();
    private static final Map<String, String> refusals = new ConcurrentHashMap<>();

    /**
     * How many times any copy has changed, ever.
     *
     * <h2>Why a counter rather than the revision</h2>
     *
     * <p>Because the revision cannot answer the question a reader is asking. A panel caches the table it
     * decoded and re-decodes when <i>the copy changed</i>; the revision it holds is the tree's, and the
     * tree's is <b>zero</b> in a fresh world. So a panel keyed on the revision asked "dice@0" before the
     * file arrived and "dice@0" after it did, could not tell the two apart, and kept drawing "Waiting for
     * this table's file..." over a copy that was sitting right there in this map. Two copies inside one
     * revision — a refusal and then a re-fetch — had the same fault.
     *
     * <p>This counter moves on every path that changes what a reader would see: a copy taken, a refusal
     * recorded, everything forgotten. A monotonic number never reused cannot be equal on both sides of a
     * change, which is the whole property a cache key needs and the revision never had.
     */
    private static final java.util.concurrent.atomic.AtomicLong generation =
            new java.util.concurrent.atomic.AtomicLong();

    /** How many times a copy has changed. See {@link #generation} for why this exists. */
    public static long generation() {
        return generation.get();
    }

    private ClientTableReplica() {
    }

    /**
     * Whether to request this table, recording the attempt so the next frame says no.
     *
     * <p>One method rather than a question and a note, for the reason the chapter replica records: a
     * caller that asks and forgets to note would send a request per frame, which works — and is why
     * nobody would notice until a server log filled up.
     */
    public static boolean claim(String table, long revision, long nowMillis) {
        if (table == null || table.isBlank()) {
            return false;
        }
        Copy copy = loaded.get(table);
        if (copy != null && copy.revision() == revision && usable(copy)) {
            return false;
        }
        // Asked for this revision already: the retry window is the backstop. A different revision is a
        // different question and goes at once -- see `attemptedRevision`.
        Long askedFor = attemptedRevision.get(table);
        if (askedFor != null && askedFor == revision) {
            Long last = attempted.get(table);
            if (last != null && nowMillis - last < RETRY_MILLIS) {
                return false;
            }
        }
        attempted.put(table, nowMillis);
        attemptedRevision.put(table, revision);
        return true;
    }

    /**
     * Whether a copy is one a panel can show: current, and with something in it.
     *
     * <p>An empty answer is the server saying it has no such file — a table that was deleted, or one
     * whose id the client made up — and settling on it would leave the panel claiming a copy is on its
     * way for the rest of the session. So the question is asked again, and the refusal is what is shown.
     */
    private static boolean usable(Copy copy) {
        return !copy.json().isBlank() && !copy.root().isEmpty();
    }

    /** Takes the server's answer. Called by the payload handler. */
    public static void accept(String table, String json, long revision, java.util.List<String> usedBy) {
        JsonObject root = new JsonObject();
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        }
        catch (RuntimeException malformed) {
            // An unreadable copy is a panel with nothing to show, which is the honest answer for a file
            // that did not arrive readable -- and `usable` keeps `claim` asking rather than settling.
        }
        loaded.put(table, new Copy(revision, json, root,
                usedBy == null ? java.util.List.of() : java.util.List.copyOf(usedBy)));
        refusals.remove(table);
        // A copy a reader has not seen yet, whatever revision it claims.
        generation.incrementAndGet();
    }

    /**
     * Who points at this table, or an empty list.
     *
     * <p>Empty for a table with no copy as well as for one nobody uses, which is the truth of what is
     * known: the editor draws its line only once it has a copy, and until then it says it is waiting.
     */
    public static java.util.List<String> usedBy(String table) {
        Copy copy = table == null ? null : loaded.get(table);
        return copy == null ? java.util.List.of() : copy.usedBy();
    }

    /** Remembers a refusal — a permission, or no such table — so a panel can say so. */
    public static void refuse(String table, String message) {
        if (table != null && !table.isBlank() && message != null && !message.isBlank()) {
            refusals.put(table, message);
            // A refusal is a change a reader has to see: the panel draws a sentence instead of a table.
            generation.incrementAndGet();
        }
    }

    /** The last refusal about this table, or null. */
    public static String refusal(String table) {
        return table == null ? null : refusals.get(table);
    }

    /** The copy of a table, or null when there is none. */
    public static Copy of(String table) {
        return table == null ? null : loaded.get(table);
    }

    /** Forgets everything: called when the client leaves the world it was reading. */
    public static void clear() {
        loaded.clear();
        attempted.clear();
        attemptedRevision.clear();
        refusals.clear();
        generation.incrementAndGet();
    }
}
