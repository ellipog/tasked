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

    /**
     * How many rosters have arrived, so a screen can tell whether the one it drew is still current.
     *
     * <h2>Why an arriving roster is not the same event as a changed one</h2>
     *
     * <p>This is {@code ClientQuestCache.treeRevision}'s argument one panel over, and it exists because
     * of the same reported fault read from the other end. {@code PartySyncPayload}'s own note says the
     * roster is sent <b>when the membership changes and on join</b> — and the case it names as the
     * reason it cannot be request-only is that <i>the panel is open while the membership changes</i>:
     * somebody accepts an invite, an officer removes somebody. So a client with the panel open is owed
     * a redraw, and nothing was asking for one.
     *
     * <p>An identity check on the snapshot would not do it, and the distinction is the point: two
     * messages may describe the same party, and the useful question is not "is this roster different"
     * but "has a roster arrived since I drew". The payload is the server saying "this is current now",
     * which is a fact about the message rather than about its contents — and a screen that unpacked
     * both to compare them would be answering a different question with its own answer for the
     * re-send that happened to be identical.
     *
     * <p>{@code volatile} for the same reason the snapshot is: written from a payload handler, read
     * from a screen, and a disconnect may clear it from another thread.
     */
    private static volatile long revision;

    private ClientPartyCache() {
    }

    /** The roster the server sent. Never null; {@link PartySnapshot#none()} until one arrives. */
    public static PartySnapshot snapshot() {
        return snapshot;
    }

    /**
     * Which roster this cache holds. A caller compares it to decide whether what it drew is stale.
     *
     * <p>Only equality is ever asked of it, so nothing depends on the absolute value; it never
     * decreases, for the same reason the tree's does not.
     */
    public static long rosterRevision() {
        return revision;
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
        //
        // The revision moves on the message rather than on the contents, so it moves here -- after the
        // null guard, so a message that never arrived does not count as a roster. A malformed string
        // counts, deliberately: it became the empty state, the panel has to be redrawn to show that,
        // and "the empty state arrived" is exactly as much a reason to redraw as any other roster. See
        // the field's own note.
        snapshot = PartySnapshot.unpack(packed);
        revision++;
    }

    /** Records that this client is in no party, which is what a disconnect means. */
    public static void clear() {
        snapshot = PartySnapshot.none();
        // Moved rather than left alone, because clearing changes what the cache holds as surely as
        // receiving does -- the same argument ClientQuestCache.clear makes for its own revision. A
        // screen that kept its revision would otherwise go on drawing the last server's party.
        revision++;
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
