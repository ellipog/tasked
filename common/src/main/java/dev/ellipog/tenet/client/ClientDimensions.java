package dev.ellipog.tenet.client;

import java.util.List;
import java.util.TreeSet;

/**
 * The dimensions the connected server has, as it told us.
 *
 * <p>The one thing about the world the editor needs and the client cannot work out for itself: a
 * dimension is level data on the server, not a registry entry a client is sent, so a modded or datapack
 * dimension is invisible to the client until the server names it. See {@code DimensionSyncPayload}.
 *
 * <p>Written from a payload handler and read from a screen, both on the client thread -- the same
 * contract {@link ClientQuestCache} states -- so there is no synchronisation here, and the volatile field
 * is for the one moment that is genuinely cross-thread: a disconnect clearing it.
 */
public final class ClientDimensions {

    private ClientDimensions() {
    }

    private static volatile List<String> dimensions = List.of();

    /** Takes the server's list from the wire. Sorted, so two arrivals cannot reorder a picker. */
    public static void accept(List<String> ids) {
        TreeSet<String> sorted = new TreeSet<>(ids);
        dimensions = List.copyOf(sorted);
    }

    /** The server's dimensions, or an empty list before it has said -- see {@link #known()}. */
    public static List<String> ids() {
        return dimensions;
    }

    /** Whether the server has told us: the difference between "no dimensions" and "not yet". */
    public static boolean known() {
        return !dimensions.isEmpty();
    }

    /** Forgets the list: another server's worlds are not this one's to offer. */
    public static void clear() {
        dimensions = List.of();
    }
}
