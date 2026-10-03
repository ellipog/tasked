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

    private static final Deque<EditorReplyPayload> replies = new ArrayDeque<>();

    private ClientEditReplies() {
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
    }
}
