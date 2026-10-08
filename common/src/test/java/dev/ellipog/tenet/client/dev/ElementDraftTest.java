package dev.ellipog.tenet.client.dev;

import com.google.gson.JsonParser;

import dev.ellipog.tenet.quest.CanvasElement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape draft: what a client believes about a chapter's element array before the tree agrees.
 *
 * <h2>Why this is worth its own tests</h2>
 *
 * <p>Because it is the half of "no rubberband" that a screenshot cannot show. A pending insert that never
 * expires is an element that exists twice — once as the author's and once as the server's, under the id the
 * server chose — and a pending removal that never expires is a decoration that is gone for the author and
 * present for everyone else. Both are invisible in the drawing and obvious here.
 */
@DisplayName("the element shape draft")
class ElementDraftTest {

    private static CanvasElement element(String json) {
        return CanvasElement.fromJson(JsonParser.parseString(json))
                .orElseThrow(() -> new AssertionError("the fixture is not an element: " + json));
    }

    private static CanvasElement box(String id) {
        return element("{ \"type\": \"rect\", \"id\": \"" + id + "\", \"x\": 0, \"y\": 0,"
                + " \"width\": 8, \"height\": 8 }");
    }

    @Test
    @DisplayName("a draft that knows nothing about a chapter hands the server's own list back untouched")
    void anEmptyDraftChangesNothing() {
        ElementDraft draft = new ElementDraft();
        List<CanvasElement> server = List.of(box("one"), box("two"));
        // The same list, not a copy: an untouched chapter must not pay for the feature at all.
        assertSame(server, draft.apply("first_steps", server, 0L));
        assertTrue(draft.isEmpty(), "and a draft that has been asked for nothing says so");
    }

    @Test
    @DisplayName("an insert appears at once, at the position it was asked for")
    void anInsertAppears() {
        ElementDraft draft = new ElementDraft();
        List<CanvasElement> server = List.of(box("one"), box("three"));
        draft.insert("first_steps", box("two"), 1, 100L);

        assertEquals(List.of("one", "two", "three"),
                draft.apply("first_steps", server, 100L).stream().map(CanvasElement::id).toList(),
                "the new element sits where it was asked for, between the two the server sent");

        // And an append, which is the ordinary case: the end of what the client can see.
        draft.insert("first_steps", box("four"), 3, 100L);
        assertEquals(List.of("one", "two", "three", "four"),
                draft.apply("first_steps", server, 100L).stream().map(CanvasElement::id).toList());
    }

    @Test
    @DisplayName("a removal takes the element away at once, and only that one")
    void aRemovalHides() {
        ElementDraft draft = new ElementDraft();
        List<CanvasElement> server = List.of(box("one"), box("two"));
        draft.remove("first_steps", "one", 100L);

        assertEquals(List.of("two"),
                draft.apply("first_steps", server, 100L).stream().map(CanvasElement::id).toList());
    }

    @Test
    @DisplayName("a draft for another chapter is not this chapter's")
    void chaptersDoNotShare() {
        // The failure this prevents is the one a shared field would produce: an element added in one chapter
        // appearing in the next one the author opens, because the draft was never about a chapter at all.
        ElementDraft draft = new ElementDraft();
        draft.insert("first_steps", box("ghost"), 0, 100L);
        List<CanvasElement> other = List.of(box("one"));

        assertSame(other, draft.apply("the_deep", other, 100L), "another chapter is untouched");
        assertEquals(List.of("ghost", "one"),
                draft.apply("first_steps", other, 100L).stream().map(CanvasElement::id).toList(),
                "and the one it is about shows the element that was added to it");
    }

    @Test
    @DisplayName("the server's own list ends an insert and a removal, exactly")
    void theServerSettlesIt() {
        ElementDraft draft = new ElementDraft();
        draft.insert("first_steps", box("box"), 0, 100L);
        draft.remove("first_steps", "old", 100L);

        // The server now holds the insert and no longer holds the removal: both beliefs are spent, and the
        // draft has nothing left to say -- which is what keeps it from growing for the life of a session.
        draft.reconcile("first_steps", List.of(box("box")), 100L);
        List<CanvasElement> server = List.of(box("box"));
        assertSame(server, draft.apply("first_steps", server, 100L),
                "nothing pending, so the server's list is handed straight back");
    }

    @Test
    @DisplayName("a pending insert the server renamed expires, and so does a removal it never took")
    void theBackstopEndsWhatAgreementCannot() {
        // The one case exact agreement cannot settle: the client asked for `box`, the chapter already had one,
        // and the server named it `box_2`. Nothing in the tree will ever confirm the client's guess, so only a
        // clock can end it -- and without that the author would see two boxes for the life of the session.
        ElementDraft draft = new ElementDraft();
        draft.insert("first_steps", box("box"), 0, 100L);
        draft.remove("first_steps", "kept", 100L);
        List<CanvasElement> server = List.of(box("box_2"), box("kept"));

        assertEquals(List.of("box", "box_2"),
                draft.apply("first_steps", server, 100L).stream().map(CanvasElement::id).toList(),
                "inside the window both beliefs hold: the new box, and `kept` gone from the list");

        long after = 100L + ElementDraft.STALE_MILLIS + 1;
        assertEquals(server, draft.apply("first_steps", server, after),
                "and past it the server's own list is the truth, including the name it chose");
        draft.reconcile("first_steps", server, after);
        assertSame(server, draft.apply("first_steps", server, after),
                "and the reconciliation drops them, so the chapter pays nothing again");
    }

    @Test
    @DisplayName("a chapter switch or a refused tree forgets a whole chapter")
    void clearingForgets() {
        ElementDraft draft = new ElementDraft();
        draft.insert("first_steps", box("ghost"), 0, 100L);
        draft.remove("first_steps", "one", 100L);
        List<CanvasElement> server = List.of(box("one"));

        draft.clear("first_steps");
        assertSame(server, draft.apply("first_steps", server, 100L));
        assertTrue(draft.isEmpty(), "and it holds nothing at all now");
    }

    @Test
    @DisplayName("the version moves whenever the shape does, which is what a stamp is keyed on")
    void theVersionMoves() {
        // The drawing cache is keyed on this: a version that did not move on an insert would leave the canvas
        // showing the old array until something else happened to move it.
        ElementDraft draft = new ElementDraft();
        long start = draft.version();
        draft.insert("first_steps", box("one"), 0, 100L);
        assertTrue(draft.version() > start, "an insert moves it");
        long afterInsert = draft.version();
        draft.reconcile("first_steps", List.of(box("one")), 100L);
        assertTrue(draft.version() > afterInsert, "and so does settling one");
    }
}
