package dev.ellipog.tasked.client.viewer;

import java.util.Optional;

/**
 * The quest a viewer asked the book to open on, held between the click and the screen's construction.
 *
 * <h2>Why this is a class and not two statics on the screen</h2>
 *
 * <p>Because the request has to survive a call the screen does not make: a recipe viewer asks
 * {@link dev.ellipog.tasked.client.QuestBookScreen#openOn} while its own screen is up, and the book
 * is built afterwards, by Minecraft's screen machinery, from the no-argument constructor the screen
 * opener knows. So the request is parked here, consumed once by {@code init}, and dropped if the
 * tree has moved on since the click — a stale quest id opens nothing rather than an empty card.
 *
 * <p>Written on the client thread (a viewer's click handler), read on the client thread (the screen's
 * init), cleared on the client thread (disconnect). No synchronisation, deliberately: the moment
 * this becomes shared between threads, the review question is what a torn read would open, and the
 * answer is "a card for a quest in the previous session".
 */
public final class QuestBookFocus {

    private static String pending;

    private QuestBookFocus() {
    }

    /** Parks a request for the next time the book is constructed. Empty ids are ignored. */
    public static void request(String questId) {
        if (questId != null && !questId.isEmpty()) {
            pending = questId;
        }
    }

    /** Takes the request, if there is one. The second call in a row always answers empty. */
    public static Optional<String> consume() {
        String id = pending;
        pending = null;
        return Optional.ofNullable(id);
    }

    /** Drops a request. Called with the rest of the view state on disconnect. */
    public static void clear() {
        pending = null;
    }
}
