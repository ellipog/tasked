package dev.ellipog.tasked.client.dev;

import dev.ellipog.tasked.quest.QuestShape;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings page's pending values, and when they stop being pending.
 *
 * <h2>The one property that matters</h2>
 *
 * <p>A draft is an exception to "the server is the authority", so the exception has to expire: the
 * moment a tree arrives with a newer revision, the file's own answer is the current one and the draft
 * must be forgotten. A draft that outlived its revision would keep drawing a value the server had
 * refused — and it would look like the edit had worked.
 */
@DisplayName("The settings draft")
class SettingsDraftTest {

    @Test
    @DisplayName("a pending value is what the page shows, until the server's tree arrives")
    void pendingValuesWinUntilTheRevisionMoves() {
        SettingsDraft draft = new SettingsDraft();
        assertTrue(draft.isEmpty(), "a fresh draft should have nothing pending");

        draft.shape(QuestShape.GEAR, 7);
        draft.size(200, 7);
        draft.iconScale(0.5, 7);
        assertFalse(draft.isEmpty(), "three pending values and the draft says it is empty");

        assertEquals(QuestShape.GEAR, draft.shape(QuestShape.ROUNDED),
                "the page is asking for a gear and the draft answers with something else");
        assertEquals(200, draft.size(48), "the pending size did not win");
        assertEquals(0.5, draft.iconScale(0.75), "the pending icon scale did not win");

        // The same revision arriving again is not a new answer: a re-send of the same tree must not
        // clear a drag in progress.
        draft.onRevision(7);
        assertEquals(QuestShape.GEAR, draft.shape(QuestShape.ROUNDED),
                "a re-sent tree cleared a draft that was still pending");

        // A newer tree is the answer, and the draft goes.
        draft.onRevision(8);
        assertTrue(draft.isEmpty(), "the draft outlived the revision it was recorded at");
        assertEquals(QuestShape.ROUNDED, draft.shape(QuestShape.ROUNDED),
                "the server's shape should win once the draft has expired");
        assertEquals(48, draft.size(48), "the server's size should win once the draft has expired");
    }

    @Test
    @DisplayName("closing the page forgets everything, so the canvas cannot draw a draft")
    void clearingForgetsEverything() {
        SettingsDraft draft = new SettingsDraft();
        draft.size(512, 3);
        draft.clear();
        assertTrue(draft.isEmpty(), "clear() left something pending");
        assertEquals(48, draft.size(48), "a cleared draft should answer with the server's value");
        // And a cleared draft is not expired by a revision, because it has none to expire.
        draft.onRevision(99);
        assertTrue(draft.isEmpty(), "an empty draft should stay empty");
    }

    @Test
    @DisplayName("a draft with no revision recorded never expires on its own")
    void anUnusedDraftNeverExpires() {
        SettingsDraft draft = new SettingsDraft();
        draft.onRevision(1);
        draft.onRevision(2);
        assertTrue(draft.isEmpty(), "a draft that was never written should have nothing pending");
    }
}
