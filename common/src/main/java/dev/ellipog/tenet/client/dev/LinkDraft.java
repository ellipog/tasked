package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.quest.QuestLink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The quest links a client has asked for and the tree has not confirmed yet.
 *
 * <h2>Why this is not {@link FieldDraft}, and why it is not {@link ElementDraft}</h2>
 *
 * <p>The first half is {@code ElementDraft}'s own argument: {@code FieldDraft} amends a member of a
 * tree that exists, and an insert or a removal is a fact about the chapter's array rather than
 * about a member of one of its entries. So the two are used together and neither replaces the
 * other — this holds the array's shape, that holds the values inside it.
 *
 * <p>The second half is why this class exists at all beside that one: a draft holds <i>shapes</i>,
 * and an element and a link are different shapes in different arrays. One generic draft over both
 * would be a list of either, and every call site would sort them back out — which is the two
 * arrays sharing one drawer. The two classes are deliberately the same shape, so a fix to the
 * reconciliation fits both; see {@code ElementDraft} for the argument each half makes.
 */
public final class LinkDraft {

    /**
     * How long an unconfirmed insert or removal is believed.
     *
     * <p>The same four seconds as the element draft's, and the same argument: long enough for a
     * round trip on a bad connection, short enough that a wrong belief cannot outlive the gesture
     * that made it.
     */
    public static final long STALE_MILLIS = ElementDraft.STALE_MILLIS;

    /** One chapter's pending shape: what was asked for, in order, and what was asked away. */
    private static final class Pending {
        final List<Insert> inserts = new ArrayList<>();
        final Map<String, Long> removedAt = new LinkedHashMap<>();
    }

    private record Insert(QuestLink link, int index, long nowMillis) {
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
     * Asks for one link to exist, at a position in the chapter's own array.
     *
     * <p>The id is the client's own guess, and the caller is expected to have made it unique against
     * the links the chapter already has — the server prefers a free id, so the guess is usually the
     * answer and the optimistic link is the real one. When it is not, {@link #STALE_MILLIS} covers it.
     */
    public void insert(String chapter, QuestLink link, int index, long nowMillis) {
        if (chapter == null || link == null || link.id() == null || link.id().isEmpty()) {
            return;
        }
        chapters.computeIfAbsent(chapter, key -> new Pending())
                .inserts.add(new Insert(link, Math.max(0, index), nowMillis));
        version++;
    }

    /** Asks for one link to be gone, by id. */
    public void remove(String chapter, String id, long nowMillis) {
        if (chapter == null || id == null || id.isEmpty()) {
            return;
        }
        chapters.computeIfAbsent(chapter, key -> new Pending()).removedAt.put(id, nowMillis);
        version++;
    }

    /**
     * The chapter's links as this client currently believes them to be.
     *
     * <p>The server's list, minus what has been asked away, plus what has been asked for — spliced in at
     * their positions in the order they were asked for, so two links added in a row keep the order they
     * were added in. The result keeps declaration order: links share the node layer with quests, whose
     * picking the screen already settles, so there is no second order for this cache to define.
     */
    public List<QuestLink> apply(String chapter, List<QuestLink> server, long nowMillis) {
        if (server == null) {
            return List.of();
        }
        Pending pending = chapters.get(chapter);
        if (pending == null) {
            return server;
        }
        List<QuestLink> out = new ArrayList<>(server.size() + pending.inserts.size());
        for (QuestLink link : server) {
            if (!removed(pending, link.id(), nowMillis)) {
                out.add(link);
            }
        }
        for (Insert insert : pending.inserts) {
            if (fresh(insert.nowMillis(), nowMillis)) {
                out.add(Math.min(insert.index(), out.size()), insert.link());
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
    public void reconcile(String chapter, List<QuestLink> server, long nowMillis) {
        Pending pending = chapters.get(chapter);
        if (pending == null) {
            return;
        }
        List<String> present = new ArrayList<>();
        for (QuestLink link : server == null ? List.<QuestLink>of() : server) {
            present.add(link.id());
        }
        pending.inserts.removeIf(insert -> present.contains(insert.link().id())
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
     * <p>Whole rather than per link: this holds <i>shapes</i>, and a shape belongs to the array it was
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

    /** Whether one link has been asked away and not yet confirmed. */
    private static boolean removed(Pending pending, String id, long nowMillis) {
        Long at = pending.removedAt.get(id);
        return at != null && fresh(at, nowMillis);
    }

    private static boolean fresh(long askedAt, long nowMillis) {
        return nowMillis - askedAt <= STALE_MILLIS;
    }
}
