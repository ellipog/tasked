package dev.ellipog.tasked.client;

import dev.ellipog.tasked.net.EditorReplyPayload;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The queue that matches answers to requests, and the two ways it used to lose that matching.
 *
 * <h2>Why position matching alone is not enough</h2>
 *
 * <p>An answer is matched to a request by <b>position</b> when it carries no id: the oldest marker in this
 * queue is what the next reply is about. That works while the queue only grows at one end and shrinks at the
 * other, and it breaks in two ways that a request id fixes.
 *
 * <p><b>A full queue.</b> This used to evict the <b>oldest</b> marker when full, which does not lose one
 * request — it shifts every marker after it, so the next answer is attributed to the request <i>before</i>
 * the one it belongs to and every answer after that is off by one for the rest of the session. A table op's
 * reply gets applied to a quest op, a replica's to a table op, and a refusal clears the wrong field's draft.
 * Nothing reports any of it, because every individual step looks like a normal answer arriving. The queue
 * refuses now instead, and the caller does not send.
 *
 * <p><b>A reply that names its request.</b> With an id the match does not depend on how many requests are in
 * flight or on what order they were sent in, so an entry the queue had to drop costs one unmatched answer
 * rather than a shift — which is what {@link ClientEditReplies#takeById} is for.
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
        assertNotNull(ClientEditReplies.noteSent(""));
        assertNotNull(ClientEditReplies.noteSent("SetField"));
        assertNotNull(ClientEditReplies.noteSent(ClientEditReplies.REPLICA_SENTINEL));

        assertEquals("", ClientEditReplies.takeSent(), "the first request's marker comes back first");
        assertEquals("SetField", ClientEditReplies.takeSent(), "then the second");
        assertEquals(ClientEditReplies.REPLICA_SENTINEL, ClientEditReplies.takeSent(), "then the third");
        assertNull(ClientEditReplies.takeSent(), "and then there is nothing waiting");
    }

    @Test
    @DisplayName("every request gets its own id, and zero is never one of them")
    void everyRequestGetsItsOwnId() {
        // Zero is the wire's "no id", so a minted id must never be zero -- otherwise a request would be
        // indistinguishable from a broadcast and would be matched by position instead of by name.
        ClientEditReplies.Request first = ClientEditReplies.noteSent("");
        ClientEditReplies.Request second = ClientEditReplies.noteSent("");
        assertNotNull(first);
        assertNotNull(second);

        assertTrue(first.id() != 0, "an id of zero would read as 'no id' on the wire");
        assertTrue(second.id() != 0);
        assertTrue(first.id() != second.id(),
                "two requests in flight at once need different ids, or the second answer matches the first");
    }

    @Test
    @DisplayName("an answer is matched to its own request by id, whatever the order")
    void anAnswerIsMatchedById() {
        // **The property the id exists for.** The middle request is answered first -- which is what a
        // per-chapter apply that takes longer than its neighbour's does -- and it is still matched to
        // itself rather than to whatever happened to be oldest.
        ClientEditReplies.Request first = ClientEditReplies.noteSent("");
        ClientEditReplies.Request middle = ClientEditReplies.noteSent("SetField");
        ClientEditReplies.Request last = ClientEditReplies.noteSent("");
        assertNotNull(first);
        assertNotNull(middle);
        assertNotNull(last);

        ClientEditReplies.Request matched = ClientEditReplies.takeById(middle.id());
        assertNotNull(matched, "the middle request's answer finds the middle request");
        assertEquals("SetField", matched.marker(), "and carries that request's own marker");

        // The other two are untouched, so their answers still match them by position.
        assertEquals("", ClientEditReplies.takeSent(), "the first is still waiting");
        assertEquals("", ClientEditReplies.takeSent(), "and so is the last");
        assertEquals(0, ClientEditReplies.pending());
    }

    @Test
    @DisplayName("an id nobody recorded matches nothing, which is a normal answer")
    void anUnknownIdMatchesNothing() {
        // A broadcast nobody asked for, or an entry the queue dropped. Null rather than an exception: the
        // reply loop treats an unmatched answer as news about a copy, which is what it did before ids.
        ClientEditReplies.noteSent("");
        assertNull(ClientEditReplies.takeById(9999L), "an id that was never recorded matches nothing");
        assertEquals(1, ClientEditReplies.pending(), "and looking does not consume anything else");
    }

    @Test
    @DisplayName("a full queue refuses the new request rather than evicting the oldest")
    void aFullQueueRefuses() {
        // **This is the fault, stated as a test.** The old behaviour evicted the oldest marker here, which
        // is why the assertion is about the *first* marker as much as about the refusal: eviction is not
        // visible from the new request's side at all, and the damage is at the other end of the queue.
        for (int i = 0; i < MAX; i++) {
            assertNotNull(ClientEditReplies.noteSent("op" + i),
                    "request " + i + " is within the bound and must be recorded");
        }
        assertEquals(MAX, ClientEditReplies.pending(), "the queue is exactly full");

        assertNull(ClientEditReplies.noteSent("one_too_many"),
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
        assertNull(ClientEditReplies.noteSent("refused"), "full");

        // An answer arriving consumes one marker, which is what a working round trip does.
        assertEquals("op0", ClientEditReplies.takeSent());
        assertNotNull(ClientEditReplies.noteSent("accepted_now"),
                "one marker consumed is one slot free -- the queue is not permanently shut");
        assertEquals(MAX, ClientEditReplies.pending());
    }

    @Test
    @DisplayName("a null marker is recorded as an empty one, which is a quest op's own")
    void aNullMarkerIsAnEmptyOne() {
        // The reply loop reads the empty string as "a quest op", and a null as "no marker at all" -- so a
        // null that reached the queue as null would be read as an answer to nothing.
        assertNotNull(ClientEditReplies.noteSent(null));
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

    @Test
    @DisplayName("a reply carrying no id is the wire's zero, and that is what the loop falls back on")
    void noIdIsZero() {
        // The fallback path depends on this being exactly zero rather than "any unset value", because the
        // reply loop's branch is an equality test against it.
        assertEquals(0L, EditorReplyPayload.NO_REQUEST);
        assertEquals(EditorReplyPayload.NO_REQUEST,
                new EditorReplyPayload("c", true, "", "").requestId(),
                "the four-argument constructor is the no-id one, which is what a broadcast uses");
        assertFalse(new EditorReplyPayload("c", true, "", "").requestId() != 0L);
    }
}
