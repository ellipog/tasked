package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.quest.CanvasElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The canvas elements a client has asked for and the tree has not confirmed yet.
 *
 * <h2>Why this is not {@link FieldDraft}</h2>
 *
 * <p>Because the two answer different halves of one question. {@code FieldDraft} amends <b>a member of a
 * tree that exists</b> — it can overlay {@code width} onto an element's own JSON, and it is what keeps a
 * slider's value where the author put it while the round trip happens. It cannot express an element that is
 * <i>not there yet</i> (an insert) or one that is <i>about to stop being there</i> (a delete), because both
 * are facts about the chapter's array rather than about a member of one of its entries.
 *
 * <p>So the two are used together and neither replaces the other: this holds the array's shape, that holds
 * the values inside it. See {@code QuestBookScreen#elementsNow}, the one place they are combined — and the
 * one place the drawing, the hit tests and the panel all read from, which is what makes an edit appear in
 * the frame it is made rather than after the round trip.
 *
 * <h2>The reconciliation, and why it needs no revision number</h2>
 *
 * <p>An insert is spent when the server's list <b>holds its id</b>, and a removal is spent when the list
 * <b>no longer does</b>. Both are exact agreement rather than a guess: the array is the thing being changed,
 * so its own contents say whether the change arrived.
 *
 * <p>The one case exactness cannot settle is a race on an id: the client asks for {@code box}, and by the
 * time the op lands the chapter has one, so the server names it {@code box_2} — and the pending {@code box}
 * is confirmed by nothing, ever. That is what {@link #STALE_MILLIS} is for, and it is the same backstop, for
 * the same reason, as {@code FieldDraft}'s.
 */
public final class ElementDraft {

    /**
     * How long an unconfirmed insert or removal is believed.
     *
     * <p>The same four seconds as {@code FieldDraft}'s, and the same argument: long enough for a round trip
     * on a bad connection, short enough that a wrong belief cannot outlive the gesture that made it.
     */
    public static final long STALE_MILLIS = 4000L;

    /** One chapter's pending shape: what was asked for, in order, and what was asked away. */
    private static final class Pending {
        final List<Insert> inserts = new ArrayList<>();
        final Map<String, Long> removedAt = new LinkedHashMap<>();
    }

    private record Insert(CanvasElement element, int index, long nowMillis) {
    }

    private final Map<String, Pending> chapters = new LinkedHashMap<>();
    private long version;

    /** A number that changes whenever anything here does, so a drawing cache can be keyed on it. */
    public long version() {
        return version;
    }

    /** Whether anything at all is pending. */
    public boolean isEmpty() {
        return chapters.isEmpty();
    }

    /**
     * Asks for one element to exist, at a position in the chapter's own array.
     *
     * <p>The id is the client's own guess, and the caller is expected to have made it unique against the
     * elements the chapter already has — the server prefers a free id, so the guess is usually the answer and
     * the optimistic element is the real one. When it is not, {@link #STALE_MILLIS} covers it.
     */
    public void insert(String chapter, CanvasElement element, int index, long nowMillis) {
        if (chapter == null || element == null || element.id() == null || element.id().isEmpty()) {
            return;
        }
        chapters.computeIfAbsent(chapter, key -> new Pending())
                .inserts.add(new Insert(element, Math.max(0, index), nowMillis));
        version++;
    }

    /** Asks for one element to be gone, by id. */
    public void remove(String chapter, String id, long nowMillis) {
        if (chapter == null || id == null || id.isEmpty()) {
            return;
        }
        chapters.computeIfAbsent(chapter, key -> new Pending()).removedAt.put(id, nowMillis);
        version++;
    }

    /**
     * The chapter's elements as this client currently believes them to be.
     *
     * <p>The server's list, minus what has been asked away, plus what has been asked for — spliced in at
     * their positions in the order they were asked for, so two elements added in a row keep the order they
     * were added in. The result is <b>not</b> sorted here: the caller runs the model's own
     * {@code CanvasElement.inDrawOrder} over the whole list, which is what keeps one definition of the draw
     * order for the tree and for the preview.
     */
    public List<CanvasElement> apply(String chapter, List<CanvasElement> server, long nowMillis) {
        if (server == null) {
            return List.of();
        }
        Pending pending = chapters.get(chapter);
        if (pending == null) {
            return server;
        }
        List<CanvasElement> out = new ArrayList<>(server.size() + pending.inserts.size());
        for (CanvasElement element : server) {
            if (!removed(pending, element.id(), nowMillis)) {
                out.add(element);
            }
        }
        for (Insert insert : pending.inserts) {
            if (fresh(insert.nowMillis(), nowMillis)) {
                out.add(Math.min(insert.index(), out.size()), insert.element());
            }
        }
        return List.copyOf(out);
    }

    /**
     * Forgets what the server's own list has settled, and what has gone stale. Called on every revision.
     *
     * <p>An insert the server now holds is done, a removal the server no longer holds is done, and anything
     * older than the backstop is dropped whatever the server says — the only thing that can end the belief
     * of an insert the server renamed.
     */
    public void reconcile(String chapter, List<CanvasElement> server, long nowMillis) {
        Pending pending = chapters.get(chapter);
        if (pending == null) {
            return;
        }
        List<String> present = new ArrayList<>();
        for (CanvasElement element : server == null ? List.<CanvasElement>of() : server) {
            present.add(element.id());
        }
        pending.inserts.removeIf(insert -> present.contains(insert.element().id())
                || !fresh(insert.nowMillis(), nowMillis));
        pending.removedAt.entrySet().removeIf(entry -> !present.contains(entry.getKey())
                || !fresh(entry.getValue(), nowMillis));
        if (pending.inserts.isEmpty() && pending.removedAt.isEmpty()) {
            chapters.remove(chapter);
        }
        version++;
    }

    /**
     * Forgets one chapter entirely, which is what a chapter switch, a disconnect or a refused tree does.
     *
     * <p>Whole rather than per element: this holds <i>shapes</i>, and a shape belongs to the array it was
     * asked for — so nothing here could honestly survive the chapter being left.
     */
    public void clear(String chapter) {
        if (chapters.remove(chapter) != null) {
            version++;
        }
    }

    /** Forgets everything. */
    public void clear() {
        if (!chapters.isEmpty()) {
            chapters.clear();
            version++;
        }
    }

    /** Whether one element has been asked away and not yet confirmed. */
    private static boolean removed(Pending pending, String id, long nowMillis) {
        Long at = pending.removedAt.get(id);
        return at != null && fresh(at, nowMillis);
    }

    private static boolean fresh(long askedAt, long nowMillis) {
        return nowMillis - askedAt <= STALE_MILLIS;
    }
}
