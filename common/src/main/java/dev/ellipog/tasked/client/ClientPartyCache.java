package dev.ellipog.tasked.client;

import dev.ellipog.armature.client.ui.party.PartyRoster;
import dev.ellipog.tasked.net.PartySnapshot;

import java.util.UUID;

/**
 * The party roster the server last sent, on the client.
 *
 * <h2>Static, and for the same reason the quest cache is</h2>
 *
 * <p>There is one connection per client, so there is one roster. A second player in the same process —
 * which is what the playthrough harness is — would need a second cache, and the honest note is the
 * same as {@code ClientQuestCache}'s: this is a field rather than a map because the thing it describes
 * is per-connection, and there is one connection.
 *
 * <h2>Drawn rather than trusted</h2>
 *
 * <p>{@link #roster(UUID)} builds a {@link PartyRoster} for a viewer, and every rule the panel obeys
 * comes from it — including whether a Remove button is drawn. The panel asserts nothing itself. That
 * matters because the client is where a wrong answer has no consequence until somebody clicks: the
 * server re-checks every action, so a panel that offered a removal it should not have would be a
 * button that fails rather than a security hole. The cost is not correctness, it is a player
 * concluding the mod is broken.
 *
 * <h2>Cleared on disconnect</h2>
 *
 * <p>A roster is about a server. A client that kept it across a disconnect would open its next world
 * drawing the previous server's party — and the failure is convincing, because every row is a real
 * player name.
 */
public final class ClientPartyCache {

    private static volatile PartySnapshot snapshot = PartySnapshot.none();

    private ClientPartyCache() {
    }

    /** The roster the server sent. Never null; {@link PartySnapshot#none()} until one arrives. */
    public static PartySnapshot snapshot() {
        return snapshot;
    }

    /**
     * Takes a roster from the server.
     *
     * <p>Unpacks rather than trusting the payload's own fields, so there is one place a malformed
     * roster is handled — and it is the place with the tests. See {@link PartySnapshot#unpack}.
     */
    public static void accept(String packed) {
        if (packed == null) {
            return;
        }
        // Unpacked rather than trusted, and the unpacking never throws -- see PartySnapshot.unpack. A
        // roster that cannot be read becomes "no party" rather than an exception, because the only
        // thing this payload can do is fill a side panel, and losing a connection over one would be a
        // worse failure than showing the empty state.
        snapshot = PartySnapshot.unpack(packed);
    }

    /** Records that this client is in no party, which is what a disconnect means. */
    public static void clear() {
        snapshot = PartySnapshot.none();
    }

    /** Whether anything has been received describing a party. */
    public static boolean hasParty() {
        return snapshot.isPresent();
    }

    /** How many members the last roster described. Diagnostics. */
    public static int memberCount() {
        return snapshot.members().size();
    }

    /**
     * The roster, as the panel draws it.
     *
     * <p>Takes the viewer because half of what a roster says is about <i>them</i>: which row is theirs,
     * and which rows carry a Remove button. Passing it rather than reading the client's own player
     * inside keeps this callable from a test, which is the same argument every other layout class here
     * makes.
     */
    public static PartyRoster roster(UUID viewer) {
        return PartySnapshot.toRoster(snapshot, viewer);
    }
}
