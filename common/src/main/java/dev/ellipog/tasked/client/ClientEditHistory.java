package dev.ellipog.tasked.client;

/**
 * Whether the server has thrown its undo history away since the book last looked.
 *
 * <h2>Why the client needs telling at all</h2>
 *
 * <p>The undo stack is the server's — {@code ServerEditors} holds it, and an undo is an op like any other.
 * But the <b>affordance</b> is the client's: the book decides whether to draw its undo button, and whether a
 * Ctrl+Z is worth sending, from counters it moves itself on each reply. Those counters mirror the server's
 * stack, and nothing else tells the client when the mirror has gone wrong.
 *
 * <p>{@code /tasked reload} drops every open editor on purpose — a history recorded against a model of the
 * files that no longer holds would undo to a state that never existed — and then broadcasts the new tree, so
 * the canvas is right. The counters were not touched, so the book kept drawing a live undo button over a
 * history that had been discarded, and pressing it sent an {@code Undo} the server had nothing to answer
 * with: nothing happened, nothing was said, and the button stayed live.
 *
 * <h2>Why a flag the screen drains rather than a message the handler shows</h2>
 *
 * <p>Because the counters belong to the screen, and this arrives on the game thread with whatever is open —
 * which may be no book at all. A handler that reached for a screen would have to decide what to do when there
 * is none, and the answer that works is the one the reply store already uses: keep the news until something
 * wants it. The book drains it on its next frame, resets its counters and says so once.
 *
 * <p>A flag rather than a queue: this is not news with a count. Two reloads between two frames are one fact —
 * the history is gone — and reporting it twice would be the repeating-error bug the reply store's own note
 * describes.
 */
public final class ClientEditHistory {

    /** Whether a discard has happened that the book has not reacted to yet. */
    private static volatile boolean discarded;

    private ClientEditHistory() {
    }

    /** Called by the payload handler, on the game thread. */
    public static void discard() {
        discarded = true;
    }

    /**
     * Whether a discard is waiting, clearing it.
     *
     * <p>Reading consumes, like the reply store: the book says it once, and a notice reported again on the
     * next frame reads as a repeating error.
     */
    public static boolean takeDiscarded() {
        boolean held = discarded;
        discarded = false;
        return held;
    }

    /** Forgets that anything was discarded: called when the client leaves the world. */
    public static void clear() {
        discarded = false;
    }
}
