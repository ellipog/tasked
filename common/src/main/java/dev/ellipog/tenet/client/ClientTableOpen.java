package dev.ellipog.tenet.client;

/**
 * A table an editor has been asked to open, waiting for the book to be on screen.
 *
 * <h2>Why a hand-off rather than an open</h2>
 *
 * <p>{@code /tenet table edit} runs on the server, so the request arrives as a message — and a message
 * handler cannot assume the book is open, or that the screen it is on can be replaced from where it
 * stands. So the request is parked here, the handler opens the book, and the screen reads this when it
 * is built. One place decides what "open a table" means, and it is the screen.
 */
public final class ClientTableOpen {

    private static volatile String pending;

    private ClientTableOpen() {
    }

    /** Parks a request. The last one wins: two commands in a row are one intent. */
    public static void request(String table) {
        pending = table;
    }

    /** The parked request, or null. Not consumed — a screen that is rebuilt must still see it. */
    public static String peek() {
        return pending;
    }

    /** Takes it, once the editor is actually open. */
    public static String take() {
        String at = pending;
        pending = null;
        return at;
    }

    /** Forgets it: called when the client leaves the world. */
    public static void clear() {
        pending = null;
    }
}
