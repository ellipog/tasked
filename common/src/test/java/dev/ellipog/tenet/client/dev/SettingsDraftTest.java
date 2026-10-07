package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.quest.QuestShape;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 *
 * <h2>And the second, which was not there</h2>
 *
 * <p>A pending value belongs to <b>a quest</b>. There used to be one value for the whole screen and
 * nothing cleared it when the edited quest changed, so the page answered quest B with quest A's number —
 * and because the arrow keys <i>accumulate</i> from the pending value, pressing an arrow on B wrote
 * {@code A's value ± step} into B's file. That is a wrong edit on disk, not a wrong pixel, and nothing
 * reported it: the number is a plausible angle and the operation succeeds.
 */
@DisplayName("The settings draft")
class SettingsDraftTest {

    /** Two quests, so every case about ownership has something to be wrong about. */
    private static final String A = "quest_a";
    private static final String B = "quest_b";

    @Test
    @DisplayName("a pending value is what the page shows, until the server's tree arrives")
    void pendingValuesWinUntilTheRevisionMoves() {
        SettingsDraft draft = new SettingsDraft();
        assertTrue(draft.isEmpty(), "a fresh draft should have nothing pending");

        draft.shape(A, QuestShape.GEAR, 7);
        draft.size(A, 200, 7);
        draft.iconScale(A, 0.5, 7);
        assertFalse(draft.isEmpty(), "three pending values and the draft says it is empty");

        assertEquals(QuestShape.GEAR, draft.shape(A, QuestShape.ROUNDED),
                "the page is asking for a gear and the draft answers with something else");
        assertEquals(200, draft.size(A, 48), "the pending size did not win");
        assertEquals(0.5, draft.iconScale(A, 0.75), "the pending icon scale did not win");

        // The same revision arriving again is not a new answer: a re-send of the same tree must not
        // clear a drag in progress.
        draft.onRevision(7);
        assertEquals(QuestShape.GEAR, draft.shape(A, QuestShape.ROUNDED),
                "a re-sent tree cleared a draft that was still pending");

        // A newer tree is the answer, and the draft goes.
        draft.onRevision(8);
        assertTrue(draft.isEmpty(), "the draft outlived the revision it was recorded at");
        assertEquals(QuestShape.ROUNDED, draft.shape(A, QuestShape.ROUNDED),
                "the server's shape should win once the draft has expired");
        assertEquals(48, draft.size(A, 48), "the server's size should win once the draft has expired");
    }

    @Test
    @DisplayName("one quest's pending value never answers for another")
    void oneQuestsValueNeverAnswersForAnother() {
        // **The regression, and the reason this class is keyed by owner at all.** Nothing cleared the
        // draft when the edited quest changed -- not selecting a node, not the card's back and forward --
        // so the value a quest had asked for was still there when the next quest asked. The arrow keys
        // accumulate from it, so the fault was not a stale preview: it was quest A's number written into
        // quest B's file.
        SettingsDraft draft = new SettingsDraft();

        draft.rotation(A, 45, 7);

        assertEquals(45, draft.rotation(A, 0), "A's own pending value is A's");
        assertEquals(0, draft.rotation(B, 0),
                "and B is answered with the server's value, not with A's -- which is the whole fix");
        assertTrue(draft.isEmpty(B), "B has nothing pending");
        assertFalse(draft.isEmpty(A), "while A still does");

        // What the arrow press does, spelled out: accumulate from *this* quest's value.
        int aNext = Math.floorMod(draft.rotation(A, 0) + 15, 360);
        int bNext = Math.floorMod(draft.rotation(B, 0) + 15, 360);
        assertEquals(60, aNext, "A steps from what A asked for");
        assertEquals(15, bNext,
                "and B steps from the server's value -- before this it stepped from A's 45 and wrote 60 "
                        + "into B's file");
    }

    @Test
    @DisplayName("each quest keeps its own value for the same field")
    void eachQuestKeepsItsOwnValue() {
        SettingsDraft draft = new SettingsDraft();
        draft.size(A, 200, 4);
        draft.size(B, 64, 4);
        draft.shape(A, QuestShape.GEAR, 4);
        draft.shape(B, QuestShape.STAR, 4);
        draft.iconScale(A, 0.5, 4);
        draft.iconScale(B, 1.25, 4);

        assertEquals(200, draft.size(A, 48));
        assertEquals(64, draft.size(B, 48));
        assertEquals(QuestShape.GEAR, draft.shape(A, QuestShape.ROUNDED));
        assertEquals(QuestShape.STAR, draft.shape(B, QuestShape.ROUNDED));
        assertEquals(0.5, draft.iconScale(A, 0.75));
        assertEquals(1.25, draft.iconScale(B, 0.75));
    }

    @Test
    @DisplayName("a value of zero is a value, not an absence")
    void zeroIsAValue() {
        // Rotation 0 is a real angle and must win over the server's. That is why the fields are boxed:
        // `null` means "not asked for" and 0 means "asked for zero", and a primitive could not tell them
        // apart -- which would silently refuse every arrow press that landed on a multiple of 360.
        SettingsDraft draft = new SettingsDraft();
        draft.rotation(A, 0, 3);

        assertFalse(draft.isEmpty(A), "a pending rotation of zero is still pending");
        assertEquals(0, draft.rotation(A, 90), "and it wins over the server's 90");
    }

    @Test
    @DisplayName("a revision forgets every quest's value, because a tree answers for all of them")
    void aRevisionForgetsEveryone() {
        // Everything, not one owner: a tree does not answer for one quest alone, so every pending value is
        // stale at that moment -- agreeing or not. See `EditorSession.onRevision` for the same reading and
        // for the mistake that comes of making it selective.
        SettingsDraft draft = new SettingsDraft();
        draft.size(A, 200, 5);
        draft.size(B, 64, 5);

        draft.onRevision(6);

        assertTrue(draft.isEmpty(), "the tree answered, so nothing is pending");
        assertEquals(48, draft.size(A, 48));
        assertEquals(48, draft.size(B, 48));
    }

    @Test
    @DisplayName("closing the page forgets everything, so the canvas cannot draw a draft")
    void clearingForgetsEverything() {
        SettingsDraft draft = new SettingsDraft();
        draft.size(A, 512, 3);
        draft.size(B, 512, 3);
        draft.clear();
        assertTrue(draft.isEmpty(), "clear() left something pending");
        assertEquals(48, draft.size(A, 48), "a cleared draft should answer with the server's value");
        assertEquals(48, draft.size(B, 48), "for every quest, not only the last one written");
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

    @Test
    @DisplayName("a value with no owner is refused rather than keyed to everything")
    void aValueNeedsAnOwner() {
        // A null owner would put every quest on one entry, which is the fault this class was just fixed
        // for -- so it is refused loudly rather than stored. The same rule `FieldDraft.set` applies to a
        // null chapter.
        SettingsDraft draft = new SettingsDraft();
        assertThrows(IllegalArgumentException.class, () -> draft.size(null, 200, 1));
    }
}
