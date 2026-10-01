package dev.ellipog.tasked.client;

import dev.ellipog.tasked.net.EditorReplyPayload;

/**
 * The last answer the server gave to an edit, waiting for whoever is open.
 *
 * <h2>Why a store rather than a screen</h2>
 *
 * <p>The reply arrives on the client's game thread, and what is on that thread is whatever the player has
 * open — which may be nothing at all, because an edit is in flight for as long as a round trip takes and a
 * player may close the book in that time. A payload handler that reached for a screen would have to decide
 * what to do when there is none, and every answer to that question is worse than keeping the message until
 * something wants it.
 *
 * <p><b>Reading it consumes it</b>, because it is news rather than state: the screen reports it once and
 * clears it, and a stale refusal reported again on the next frame is a bug that reads as a repeating error.
 */
public final class ClientEditReplies {

    private static volatile EditorReplyPayload latest;

    private ClientEditReplies() {
    }

    /** Called by the payload handler. */
    public static void accept(EditorReplyPayload reply) {
        latest = reply;
    }

    /** The reply nobody has read yet, or null. Reading it clears it. */
    public static EditorReplyPayload take() {
        EditorReplyPayload reply = latest;
        latest = null;
        return reply;
    }
}
