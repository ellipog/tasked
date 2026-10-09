package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.quest.QuestLink;
import dev.ellipog.tenet.quest.QuestRef;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape draft: what a client believes about a chapter's link array before the tree agrees.
 *
 * <p>Mirrors {@link ElementDraftTest} case for case, because the two drafts are the same shape over
 * different arrays: a pending insert that never expires is a link that exists twice, and a pending
 * removal that never expires is a marker gone for the author and present for everyone else.
 */
@DisplayName("the link shape draft")
class LinkDraftTest {

    private static QuestLink link(String id, String quest) {
        return new QuestLink(id, new QuestRef(quest), 0, 0,
                dev.ellipog.tenet.quest.QuestShape.ROUNDED,
                dev.ellipog.tenet.quest.QuestLayout.DEFAULT_SIZE);
    }

    @Test
    @DisplayName("a draft that knows nothing about a chapter hands the server's own list back untouched")
    void anEmptyDraftChangesNothing() {
        LinkDraft draft = new LinkDraft();
        List<QuestLink> server = List.of(link("one", "a"), link("two", "b"));
        // The same list, not a copy: an untouched chapter must not pay for the feature at all.
        assertSame(server, draft.apply("first_steps", server, 0L));
        assertTrue(draft.isEmpty(), "and a draft that has been asked for nothing says so");
    }

    @Test
    @DisplayName("an insert appears at once, at the position it was asked for")
    void anInsertAppears() {
        LinkDraft draft = new LinkDraft();
        List<QuestLink> server = List.of(link("one", "a"), link("three", "c"));
        draft.insert("first_steps", link("two", "b"), 1, 100L);

        assertEquals(List.of("one", "two", "three"),
                draft.apply("first_steps", server, 100L).stream().map(QuestLink::id).toList(),
                "the new link sits where it was asked for, between the two the server sent");

        draft.insert("first_steps", link("four", "d"), 3, 100L);
        assertEquals(List.of("one", "two", "three", "four"),
                draft.apply("first_steps", server, 100L).stream().map(QuestLink::id).toList());
    }

    @Test
    @DisplayName("a removal takes the link away at once, and only that one")
    void aRemovalHides() {
        LinkDraft draft = new LinkDraft();
        List<QuestLink> server = List.of(link("one", "a"), link("two", "b"));
        draft.remove("first_steps", "one", 100L);

        assertEquals(List.of("two"),
                draft.apply("first_steps", server, 100L).stream().map(QuestLink::id).toList());
    }

    @Test
    @DisplayName("a draft for another chapter is not this chapter's")
    void chaptersDoNotShare() {
        LinkDraft draft = new LinkDraft();
        draft.insert("first_steps", link("ghost", "a"), 0, 100L);
        List<QuestLink> other = List.of(link("one", "a"));

        assertSame(other, draft.apply("the_deep", other, 100L), "another chapter is untouched");
        assertEquals(List.of("ghost", "one"),
                draft.apply("first_steps", other, 100L).stream().map(QuestLink::id).toList(),
                "and the one it is about shows the link that was added to it");
    }

    @Test
    @DisplayName("the server's own list ends an insert and a removal, exactly")
    void theServerSettlesIt() {
        LinkDraft draft = new LinkDraft();
        draft.insert("first_steps", link("box", "a"), 0, 100L);
        draft.remove("first_steps", "old", 100L);

        draft.reconcile("first_steps", List.of(link("box", "a")), 100L);
        List<QuestLink> server = List.of(link("box", "a"));
        assertSame(server, draft.apply("first_steps", server, 100L),
                "nothing pending, so the server's list is handed straight back");
    }

    @Test
    @DisplayName("a pending insert the server renamed expires, and so does a removal it never took")
    void theBackstopEndsWhatAgreementCannot() {
        LinkDraft draft = new LinkDraft();
        draft.insert("first_steps", link("box", "a"), 0, 100L);
        draft.remove("first_steps", "kept", 100L);
        List<QuestLink> server = List.of(link("box_2", "a"), link("kept", "b"));

        assertEquals(List.of("box", "box_2"),
                draft.apply("first_steps", server, 100L).stream().map(QuestLink::id).toList(),
                "inside the window both beliefs hold");

        long after = 100L + LinkDraft.STALE_MILLIS + 1;
        assertEquals(server, draft.apply("first_steps", server, after),
                "and past it the server's own list is the truth, including the name it chose");
        draft.reconcile("first_steps", server, after);
        assertSame(server, draft.apply("first_steps", server, after),
                "and the reconciliation drops them, so the chapter pays nothing again");
    }

    @Test
    @DisplayName("a chapter switch or a refused tree forgets a whole chapter")
    void clearingForgets() {
        LinkDraft draft = new LinkDraft();
        draft.insert("first_steps", link("ghost", "a"), 0, 100L);
        draft.remove("first_steps", "one", 100L);
        List<QuestLink> server = List.of(link("one", "a"));

        draft.clear("first_steps");
        assertSame(server, draft.apply("first_steps", server, 100L));
        assertTrue(draft.isEmpty(), "and it holds nothing at all now");
    }

    @Test
    @DisplayName("the version moves whenever the shape does, which is what a stamp is keyed on")
    void theVersionMoves() {
        LinkDraft draft = new LinkDraft();
        long start = draft.version();
        draft.insert("first_steps", link("one", "a"), 0, 100L);
        assertTrue(draft.version() > start, "an insert moves it");
        long afterInsert = draft.version();
        draft.reconcile("first_steps", List.of(link("one", "a")), 100L);
        assertTrue(draft.version() > afterInsert, "and so does settling one");
    }
}
