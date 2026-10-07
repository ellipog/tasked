package dev.ellipog.tasked.client;

import dev.ellipog.tasked.net.EditorReplyPayload;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The queue that matches answers to requests, and the one way it used to lose that matching.
 *
 * <h2>Why position matching makes a full queue dangerous</h2>
 *
 * <p>Neither the op payload nor the reply carries a request id, so an answer is matched to a request by
 * <b>position</b>: the oldest marker in this queue is what the next reply is about. That is fine while the
 * queue only ever grows at one end and shrinks at the other, and it is exactly what a full queue breaks.
 *
 * <p>This used to evict the <b>oldest</b> marker when full. That does not lose one request — it shifts
 * every marker after it, so the next answer is attributed to the request <i>before</i> the one it belongs
 * to, and every answer after that is off by one for the rest of the session. A table op's reply gets applied
 * to a quest op, a replica's to a table op, and a refusal clears the wrong field's draft. Nothing reports
 * any of it, because every individual step looks like a normal answer arriving.
 *
 * <p>So a full queue refuses the new marker instead, and the caller does not send — which is what these
 * tests pin. The distinction the class exists to keep is "every marker in the queue belongs to a request
 * that is genuinely in flight", and refusing preserves it where evicting destroys it.
 */
@DisplayName("the client's edit reply queue")
class ClientEditRepliesTest {

    /** The class's own bound, reached through the public surface rather than restated. */
    private static final int MAX = 64;

    @BeforeEach
    void reset() {
        ClientEditReplies.clear();
    }

    @Test
    @DisplayName("markers come back in the order they were recorded")
    void markersAreMatchedInOrder() {
        assertTrue(ClientEditReplies.noteSent(""));
        assertTrue(ClientEditReplies.noteSent("SetField"));
        assertTrue(ClientEditReplies.noteSent(ClientEditReplies.REPLICA_SENTINEL));

        assertEquals("", ClientEditReplies.takeSent(), "the first request's marker comes back first");
        assertEquals("SetField", ClientEditReplies.takeSent(), "then the second");
        assertEquals(ClientEditReplies.REPLICA_SENTINEL, ClientEditReplies.takeSent(), "then the third");
        assertNull(ClientEditReplies.takeSent(), "and then there is nothing waiting");
    }

    @Test
    @DisplayName("a full queue refuses the new marker rather than evicting the oldest")
    void aFullQueueRefuses() {
        // **This is the fault, stated as a test.** The old behaviour evicted the oldest marker here, which
        // is why the assertion is about the *first* marker as much as about the refusal: eviction is not
        // visible from the new marker's side at all, and the damage is at the other end of the queue.
        for (int i = 0; i < MAX; i++) {
            assertTrue(ClientEditReplies.noteSent("op" + i),
                    "marker " + i + " is within the bound and must be recorded");
        }
        assertEquals(MAX, ClientEditReplies.pending(), "the queue is exactly full");

        assertFalse(ClientEditReplies.noteSent("one_too_many"),
                "a full queue refuses, so the caller can decline to send an op it cannot match");
        assertEquals(MAX, ClientEditReplies.pending(), "and refusing does not change what is already held");

        // The point of refusing: the requests already in flight are still matched to their own answers.
        // Under eviction the first marker would be gone and this would return "op1".
        assertEquals("op0", ClientEditReplies.takeSent(),
                "the oldest marker is still the oldest marker -- evicting it shifted every match after it");
        assertEquals("op1", ClientEditReplies.takeSent(), "and the order is otherwise undisturbed");
    }

    @Test
    @DisplayName("draining makes room again")
    void drainingMakesRoom() {
        for (int i = 0; i < MAX; i++) {
            ClientEditReplies.noteSent("op" + i);
        }
        assertFalse(ClientEditReplies.noteSent("refused"), "full");

        // An answer arriving consumes one marker, which is what a working round trip does.
        assertEquals("op0", ClientEditReplies.takeSent());
        assertTrue(ClientEditReplies.noteSent("accepted_now"),
                "one marker consumed is one slot free -- the queue is not permanently shut");
        assertEquals(MAX, ClientEditReplies.pending());
    }

    @Test
    @DisplayName("a null marker is recorded as an empty one, which is a quest op's own")
    void aNullMarkerIsAnEmptyOne() {
        // The reply loop reads the empty string as "a quest op", and a null as "no marker at all" -- so a
        // null that reached the queue as null would be read as an answer to nothing.
        assertTrue(ClientEditReplies.noteSent(null));
        assertEquals("", ClientEditReplies.takeSent(), "a null marker is stored as the empty string");
    }

    @Test
    @DisplayName("clear forgets the markers and the replies together")
    void clearForgetsBoth() {
        ClientEditReplies.noteSent("SetField");
        ClientEditReplies.accept(new EditorReplyPayload("chapter", false, "", "refused"));
        assertEquals(1, ClientEditReplies.pending());

        ClientEditReplies.clear();

        assertEquals(0, ClientEditReplies.pending(), "leaving the world forgets what was in flight");
        assertNull(ClientEditReplies.takeSent());
        assertNull(ClientEditReplies.take(), "and the unread replies go with them");
    }

    @Test
    @DisplayName("replies are drained oldest first and reading consumes them")
    void repliesDrainOldestFirst() {
        ClientEditReplies.accept(new EditorReplyPayload("a", true, "", ""));
        ClientEditReplies.accept(new EditorReplyPayload("b", false, "", "no"));

        List<EditorReplyPayload> drained = ClientEditReplies.drain();

        assertEquals(2, drained.size());
        assertEquals("a", drained.get(0).chapter(), "the first answer is the first one out");
        assertEquals("b", drained.get(1).chapter());
        assertTrue(ClientEditReplies.drain().isEmpty(), "and draining consumes them");
    }
}
