package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.quest.QuestShape;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the settings page has asked for and the server has not answered yet, <b>per quest</b>.
 *
 * <h2>Why a draft exists at all</h2>
 *
 * <p>Every other control in the editor commits and then draws what the server sent, which is the whole
 * of "the server is the authority". The settings page cannot: its preview has to follow a slider while
 * the slider is being dragged, and a preview that waited for a round trip would lag a pointer by a tick
 * and, on a busy server, by much more — an author dragging the size control would see the node move in
 * steps behind their hand, which reads as a broken control rather than as latency.
 *
 * <p>So a value is remembered here the moment it is asked for, the preview reads it, and it is forgotten
 * the moment the server's tree arrives with a newer revision — after which the file's own answer is the
 * current one and nothing is pending. That is exactly {@code EditorSession}'s contract for a dragged
 * node, and deliberately so: two kinds of pending edit with two different lifetimes would be two things
 * to keep in step, and the one thing that must not differ is when the server's answer wins.
 *
 * <h2>Why the owner is part of the key, and what its absence cost</h2>
 *
 * <p>There used to be one {@code shape}, one {@code size}, one {@code iconScale} and one {@code rotation}
 * for the whole screen — and <b>nothing cleared them when the edited quest changed.</b> The page's own
 * close did, and a picker opening did, and an undo did; selecting a different node did not, and neither
 * did the card's own back-and-forward navigation. So:
 *
 * <pre>
 *   select quest A, open its settings, nudge rotation   -> draft.rotation = 45
 *   select quest B                                       -> the draft is still 45
 *   nudge B's rotation                                   -> current = 45, next = 45 + 15
 * </pre>
 *
 * <p>and <b>quest B's file is written with a value derived from quest A's</b>. That is a wrong edit on
 * disk rather than a wrong pixel, and it is silent: the number is a plausible angle, the operation
 * succeeds, and nothing anywhere reports that the two quests were added together.
 *
 * <p>The fault is not that a value was remembered — that is the point of the class. It is that the value
 * was remembered <i>about nothing</i>, so it answered for whichever quest asked next. Keying by owner is
 * what makes "the pending value for this quest" a question with an answer.
 *
 * <p>The read is therefore by owner too, which is why the getters take one. A caller cannot ask for "the
 * pending size" in the abstract, because there is no such thing: there is the pending size <i>of a
 * quest</i>. That is the same shape {@link FieldDraft} uses, and for the same reason.
 *
 * <h2>What is not here</h2>
 *
 * <p>The commit itself. The screen sends the operation; this only remembers what was asked for. A class
 * that also wrote would be a second place the client could change a file, which is the mistake the
 * editor's whole design is arranged around.
 */
public final class SettingsDraft {

    /**
     * One quest's pending values.
     *
     * <p>Boxed rather than primitives because "not asked for" and "asked for the value zero" are different
     * answers: a size of 0 is not a size, but a rotation of 0 is a real angle and must win over the
     * server's.
     */
    private static final class Pending {

        private QuestShape shape;
        private Integer size;
        private Double iconScale;
        private Integer rotation;
    }

    private final Map<String, Pending> pending = new LinkedHashMap<>();

    /** The revision the pending values were recorded at, or -1 when there are none. */
    private long atRevision = -1;

    /** Remembers a shape for this quest, until a newer tree arrives. */
    public void shape(String owner, QuestShape value, long revision) {
        pendingFor(owner).shape = value;
        atRevision = revision;
    }

    /** Remembers a size for this quest, until a newer tree arrives. */
    public void size(String owner, int value, long revision) {
        pendingFor(owner).size = value;
        atRevision = revision;
    }

    /** Remembers an icon scale for this quest, until a newer tree arrives. */
    public void iconScale(String owner, double value, long revision) {
        pendingFor(owner).iconScale = value;
        atRevision = revision;
    }

    /** Remembers a rotation for this quest, until a newer tree arrives. */
    public void rotation(String owner, int value, long revision) {
        pendingFor(owner).rotation = value;
        atRevision = revision;
    }

    /** This quest's pending shape, or the server's. */
    public QuestShape shape(String owner, QuestShape sent) {
        Pending held = pending.get(owner);
        return held == null || held.shape == null ? sent : held.shape;
    }

    /** This quest's pending size, or the server's. */
    public int size(String owner, int sent) {
        Pending held = pending.get(owner);
        return held == null || held.size == null ? sent : held.size;
    }

    /** This quest's pending icon scale, or the server's. */
    public double iconScale(String owner, double sent) {
        Pending held = pending.get(owner);
        return held == null || held.iconScale == null ? sent : held.iconScale;
    }

    /** This quest's pending rotation, or the server's. */
    public int rotation(String owner, int sent) {
        Pending held = pending.get(owner);
        return held == null || held.rotation == null ? sent : held.rotation;
    }

    private Pending pendingFor(String owner) {
        // A null owner would key every quest to one entry, which is the fault this class was just fixed
        // for -- so it is refused rather than stored, the same way `FieldDraft.set` refuses a null chapter.
        if (owner == null) {
            throw new IllegalArgumentException("a pending value needs an owner: " + owner);
        }
        return pending.computeIfAbsent(owner, key -> new Pending());
    }

    /** Whether anything is pending, for any quest. */
    public boolean isEmpty() {
        return pending.isEmpty();
    }

    /** Whether anything is pending for this quest. */
    public boolean isEmpty(String owner) {
        Pending held = pending.get(owner);
        return held == null
                || (held.shape == null && held.size == null && held.iconScale == null
                        && held.rotation == null);
    }

    /**
     * Forgets every pending value once the server has sent a tree that is newer than they are.
     *
     * <p>Called with the cache's revision, every frame, because that is where the fact lives: the tree
     * arriving is what makes the files' own answer the current one, and nothing else can see it. A
     * revision that has not moved leaves them alone, which is what keeps the preview following a drag.
     *
     * <p>Everything, not one owner: a revision that moves means the server has answered, and a tree does
     * not answer for one quest alone — so every pending value is stale at that moment, agreeing or not.
     * See {@code EditorSession.onRevision} for the same reading and the mistake that comes of making it
     * selective.
     */
    public void onRevision(long revision) {
        if (atRevision >= 0 && revision != atRevision) {
            clear();
        }
    }

    /** Forgets everything pending — when the page closes, and when the server's answer arrives. */
    public void clear() {
        pending.clear();
        atRevision = -1;
    }
}
