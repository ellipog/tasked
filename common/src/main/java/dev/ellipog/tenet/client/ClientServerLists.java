package dev.ellipog.tenet.client;

import java.util.List;
import java.util.TreeSet;

/**
 * The lists the connected server has, as it told us.
 *
 * <p>The facts about the world the editor needs and the client cannot work out for itself. A dimension
 * is level data on the server rather than a registry entry a client is shipped, and a structure is a
 * datapack registry the server does not send — so both a modded or datapack dimension and every
 * structure, vanilla ones included, are invisible to the client until the server names them. See
 * {@link dev.ellipog.tenet.net.ServerListsPayload} for the rule and for why an unreadable registry
 * answers empty rather than throwing.
 *
 * <p>Written from a payload handler and read from a screen, both on the client thread — the same
 * contract {@link ClientQuestCache} states — so there is no synchronisation here, and the volatile field
 * is for the one moment that is genuinely cross-thread: a disconnect clearing it.
 */
public final class ClientServerLists {

    private ClientServerLists() {
    }

    private static volatile List<String> dimensions = List.of();
    private static volatile List<String> structures = List.of();

    /** Takes the server's lists from the wire. Sorted, so two arrivals cannot reorder a picker. */
    public static void accept(List<String> ids, List<String> structureIds) {
        dimensions = List.copyOf(new TreeSet<>(ids));
        structures = List.copyOf(new TreeSet<>(structureIds));
    }

    /** The server's dimensions, or an empty list before it has said -- see {@link #known()}. */
    public static List<String> dimensions() {
        return dimensions;
    }

    /**
     * The server's structures and structure tags, or an empty list before it has said.
     *
     * <p>There is deliberately no fallback for this one, the way the dimensions fall back to the three
     * every player knows: a structure's id is the pack's to choose, so a list of vanilla ids would be a
     * guess dressed as an answer. An empty list until the payload arrives is the honest shape, and the
     * picker's box still takes a typed id in the meantime.
     */
    public static List<String> structures() {
        return structures;
    }

    /** Whether the server has told us: the difference between "no dimensions" and "not yet". */
    public static boolean known() {
        return !dimensions.isEmpty();
    }

    /** Forgets the lists: another server's worlds and structures are not this one's to offer. */
    public static void clear() {
        dimensions = List.of();
        structures = List.of();
    }
}
