package dev.ellipog.tasked.client;

import dev.ellipog.tasked.net.EditorReplyPayload;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The answers the server gave to edits, waiting for whoever is open.
 *
 * <h2>Why a store rather than a screen</h2>
 *
 * <p>The reply arrives on the client's game thread, and what is on that thread is whatever the player has
 * open — which may be nothing at all, because an edit is in flight for as long as a round trip takes and a
 * player may close the book in that time. A payload handler that reached for a screen would have to decide
 * what to do when there is none, and every answer to that question is worse than keeping the message until
 * something wants it.
 *
 * <h2>Why a queue rather than a slot, and what the slot cost</h2>
 *
 * <p>This held only the latest reply, which was fine while one edit at a time was the shape of editing. A
 * burst of quick edits — spamming a stepper — puts several answers between two ticks, and a refusal among
 * them was overwritten before the screen read it: the edit visibly did not stick and nothing said why. So
 * the store keeps them in order, and reading drains them all. Bounded, because a client that never drains
 * (a book closed mid-burst) must not grow without limit; the oldest are dropped if it ever fills.
 *
 * <p><b>Reading consumes</b>, because these are news rather than state: the screen reports each once, and a
 * stale refusal reported again on the next frame is a bug that reads as a repeating error.
 */
public final class ClientEditReplies {

    /** Plenty for any human burst; a bound so a never-drained queue cannot grow without limit. */
    private static final int MAX = 64;

    /**
     * The marker a replica fetch, a table import and a test roll record instead of an op's own kind.
     *
     * <p>They are not ops, and the reply loop has to tell them apart from one: a table op's answer moves
     * the table panel's undo budget and a quest op's does not, and a replica answered through its own
     * payload has already consumed its marker by the time a reply could arrive. The marker is the only
     * thing that carries that distinction, so it belongs here beside the queue that holds it rather than on
     * the screen — and the payload handlers, which have no screen, are the other door that reads it.
     */
    public static final String REPLICA_SENTINEL = "#replica";

    private static final Deque<EditorReplyPayload> replies = new ArrayDeque<>();

    /**
     * What each in-flight request was, oldest first — the marker the reply loop matches answers against.
     *
     * <h2>Why the markers live here rather than on the screen</h2>
     *
     * <p>Because a request is not always answered by an edit reply. A replica fetch and a test roll are
     * answered by their own payloads when they succeed and by an edit reply only when they are refused,
     * and those payload handlers have no screen — the answer arrives on the game thread with whatever is
     * open, which may be nothing. Keeping the markers beside the replies means both doors consume from
     * one queue, so a successful fetch and a refusal move the same needle and the ops after them are not
     * matched to the wrong answer.
     *
     * <p>Reading consumes, like the replies: a marker nobody pops is a marker that mis-aligns everything
     * after it. Bounded like the replies, and cleared with them when the client leaves the world.
     */
    private static final Deque<String> sent = new ArrayDeque<>();

    private ClientEditReplies() {
    }

    /**
     * Records that one request went out, so its answer can be matched to it.
     *
     * <p>Called where the request is sent — an op, a replica fetch, a test roll. The empty string is a
     * quest op's marker and a table op records its own kind, which is what the reply loop reads to decide
     * whether an answer moves the table panel's undo budget.
     *
     * <h2>Why this refuses rather than drops, which is the fault it had</h2>
     *
     * <p>It used to evict the <b>oldest</b> marker once the queue was full, and that is worse than losing
     * one request: the queue is matched by <i>position</i>. Evicting the oldest shifts every marker after
     * it, so the next answer is matched to the request before the one it belongs to, and every answer after
     * that is off by one for the rest of the session. A table op's reply would be applied to a quest op, a
     * replica's to a table op, and a refusal would clear the wrong field's draft — none of which reports
     * anything, because every individual step looks like a normal answer.
     *
     * <p>So a full queue <b>refuses</b> the new marker and says so, and the caller does not send. That
     * leaves the requests already in flight correctly aligned, which is the property worth keeping: losing
     * one edit the author can repeat beats silently mis-attributing every later one. The bound is high
     * enough (see {@link #MAX}) that reaching it means a client that has stopped draining replies, which is
     * a book closed mid-burst rather than a person clicking.
     *
     * @return whether the marker was recorded. <b>False means do not send</b> — an op whose marker was
     *         refused would be answered by a reply that matched the request before it.
     */
    public static synchronized boolean noteSent(String marker) {
        if (sent.size() >= MAX) {
            return false;
        }
        sent.addLast(marker == null ? "" : marker);
        return true;
    }

    /**
     * How many requests are waiting for an answer.
     *
     * <p>For the tests, and for a diagnostic that wants to say the queue is not draining. Not a count of
     * replies: those are drained by the screen each frame and this is the other queue.
     */
    public static synchronized int pending() {
        return sent.size();
    }

    /**
     * The oldest marker still waiting, or null when the answer arrived through its own payload.
     *
     * <p>Null rather than an exception: a payload answered by something that never pushed a marker — a
     * broadcast nobody asked for — is normal, and the reply loop treats an unknown answer as news about a
     * copy rather than as an op's result.
     */
    public static synchronized String takeSent() {
        return sent.pollFirst();
    }

    /** Called by the payload handler. */
    public static synchronized void accept(EditorReplyPayload reply) {
        if (replies.size() >= MAX) {
            replies.removeFirst();
        }
        replies.addLast(reply);
    }

    /**
     * The next reply nobody has read yet, or null. Reading it consumes it.
     *
     * <p>Kept for callers that want one — the wiring test drives this — while the screen drains all.
     */
    public static synchronized EditorReplyPayload take() {
        return replies.pollFirst();
    }

    /** Every reply nobody has read yet, oldest first. Reading them consumes them. */
    public static synchronized List<EditorReplyPayload> drain() {
        List<EditorReplyPayload> out = new ArrayList<>(replies);
        replies.clear();
        return out;
    }

    /** Forgets everything: called when the client leaves the world it was editing. */
    public static synchronized void clear() {
        replies.clear();
        sent.clear();
    }
}
