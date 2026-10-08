package dev.ellipog.tenet.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The Delete key's two-press rule, and the ids it is a question about.
 *
 * <h2>Why the ids are the part worth testing</h2>
 *
 * <p>"The second press deletes" is arithmetic, and {@link ArmedPressTest} has it. What this class exists
 * for is the other half: the first press names a selection, and the press that confirms has to take
 * <b>those</b> quests. A screen cannot be asked that question — {@code QuestBookScreen} needs a running
 * client — and a boolean cannot answer it, which is exactly how a confirming press comes to delete a
 * selection the author was never asked about. So every case below changes something between the two
 * presses and asserts that the answer is a new question rather than a deletion.
 */
@DisplayName("ArmedDelete")
class ArmedDeleteTest {

    private static final long START = 1_000L;
    private static final String CHAPTER = "first_steps";

    private static List<String> ids(String... ids) {
        return List.of(ids);
    }

    @Test
    @DisplayName("with nothing selected the key is not the editor's, and it asks nothing")
    void nothingSelectedIsNotAQuestion() {
        ArmedDelete delete = new ArmedDelete();

        assertInstanceOf(ArmedDelete.Nothing.class, delete.press(CHAPTER, List.of(), START));
        assertInstanceOf(ArmedDelete.Nothing.class, delete.press(CHAPTER, null, START));
        assertEquals(0, delete.armedCount(), "and nothing is left armed");
    }

    @Test
    @DisplayName("an empty selection drops a question already asked")
    void anEmptySelectionDisarmsTheEarlierQuestion() {
        // The selection can be emptied by a plain click on the canvas, and the armed ids are then gone
        // from the screen: a question about them is a question about nothing.
        ArmedDelete delete = new ArmedDelete();
        assertInstanceOf(ArmedDelete.Armed.class, delete.press(CHAPTER, ids("a"), START));

        assertInstanceOf(ArmedDelete.Nothing.class, delete.press(CHAPTER, List.of(), START + 1));
        assertEquals(0, delete.armedCount());
        assertInstanceOf(ArmedDelete.Armed.class, delete.press(CHAPTER, ids("a"), START + 2),
                "so the next press asks again rather than confirming the id it named before");
    }

    @Test
    @DisplayName("the first press asks, and the second takes exactly what the first named")
    void theSecondPressConfirmsTheArmedIds() {
        ArmedDelete delete = new ArmedDelete();

        assertInstanceOf(ArmedDelete.Armed.class, delete.press(CHAPTER, ids("a", "b"), START));
        assertEquals(2, delete.armedCount(), "the arming press remembers what it named");

        ArmedDelete.Confirmed confirmed = assertInstanceOf(ArmedDelete.Confirmed.class,
                delete.press(CHAPTER, ids("a", "b"), START + 1));
        assertEquals(List.of("a", "b"), confirmed.ids());
        assertEquals(0, delete.armedCount(), "and confirming leaves nothing armed");
    }

    @Test
    @DisplayName("a changed selection is a new question, never a confirmation")
    void aChangedSelectionAsksAgain() {
        // Each of these is a real gesture between two presses of one key: a shift-press adds, a
        // ctrl-press takes away, and clicking another node replaces. None may confirm.
        for (List<String> changed : List.of(ids("a"), ids("a", "b", "c"), ids("b", "c"), ids("c", "b"))) {
            ArmedDelete delete = new ArmedDelete();
            assertInstanceOf(ArmedDelete.Armed.class, delete.press(CHAPTER, ids("a", "b"), START));

            assertInstanceOf(ArmedDelete.Armed.class, delete.press(CHAPTER, changed, START + 1),
                    "a press against " + changed + " must ask again");
            assertEquals(changed.size(), delete.armedCount(),
                    "and it is the new selection that is armed: " + changed);

            ArmedDelete.Confirmed confirmed = assertInstanceOf(ArmedDelete.Confirmed.class,
                    delete.press(CHAPTER, changed, START + 2));
            assertEquals(changed, confirmed.ids(), "so the confirm takes the new set, never the old one");
        }
    }

    @Test
    @DisplayName("a different chapter is a new question, because the sentence named what was on screen")
    void aChapterSwitchAsksAgain() {
        // A selection survives a chapter switch by design, so without this the confirming press would
        // delete quests in a chapter the author has navigated away from -- while the sentence they read
        // named the nodes they could see.
        ArmedDelete delete = new ArmedDelete();
        assertInstanceOf(ArmedDelete.Armed.class, delete.press(CHAPTER, ids("a"), START));

        assertInstanceOf(ArmedDelete.Armed.class, delete.press("another_chapter", ids("a"), START + 1),
                "the same ids in another chapter are another question");
        ArmedDelete.Confirmed confirmed = assertInstanceOf(ArmedDelete.Confirmed.class,
                delete.press("another_chapter", ids("a"), START + 2));
        assertEquals(List.of("a"), confirmed.ids());
    }

    @Test
    @DisplayName("a lapsed question asks again rather than deleting")
    void aLapsedQuestionAsksAgain() {
        ArmedDelete delete = new ArmedDelete();
        assertInstanceOf(ArmedDelete.Armed.class, delete.press(CHAPTER, ids("a"), START));

        assertInstanceOf(ArmedDelete.Armed.class,
                delete.press(CHAPTER, ids("a"), START + ArmedPress.WINDOW_MILLIS + 1));
        assertInstanceOf(ArmedDelete.Confirmed.class,
                delete.press(CHAPTER, ids("a"), START + ArmedPress.WINDOW_MILLIS + 2));
    }

    @Test
    @DisplayName("the boundary is inside the window: the last millisecond confirms")
    void theWindowBoundaryIsInside() {
        ArmedDelete delete = new ArmedDelete();
        assertInstanceOf(ArmedDelete.Armed.class, delete.press(CHAPTER, ids("a"), START));

        assertInstanceOf(ArmedDelete.Confirmed.class,
                delete.press(CHAPTER, ids("a"), START + ArmedPress.WINDOW_MILLIS));
    }

    @Test
    @DisplayName("disarm means no, and the next press is a fresh question")
    void disarmMeansNo() {
        // Called by every other key and every press anywhere: the author moving on is "no", and it must
        // not leave a press that confirms the question they walked away from.
        ArmedDelete delete = new ArmedDelete();
        assertInstanceOf(ArmedDelete.Armed.class, delete.press(CHAPTER, ids("a"), START));

        delete.disarm();
        assertEquals(0, delete.armedCount());
        assertInstanceOf(ArmedDelete.Armed.class, delete.press(CHAPTER, ids("a"), START + 1),
                "the next press asks rather than confirming");
    }

    @Test
    @DisplayName("a null chapter reads as none rather than throwing")
    void aNullChapterIsEveryOtherNullInThisMod() {
        // `effectiveChapter()` is null when the book has no chapters at all, which is a real state: a
        // pack being made from nothing. A key handler is no place to throw.
        ArmedDelete delete = new ArmedDelete();
        assertInstanceOf(ArmedDelete.Armed.class, delete.press(null, ids("a"), START));
        assertInstanceOf(ArmedDelete.Confirmed.class, delete.press(null, ids("a"), START + 1));
    }

    @Test
    @DisplayName("the armed list is a copy, so a caller's own list cannot move under the question")
    void theArmedListIsACopy() {
        // The screen's `selection()` builds a fresh list on every press, but the armed one is held
        // *across* presses: were it the caller's list, a mutation in between would silently change what
        // the confirming press deletes.
        List<String> mine = new ArrayList<>(List.of("a", "b"));
        ArmedDelete delete = new ArmedDelete();
        assertInstanceOf(ArmedDelete.Armed.class, delete.press(CHAPTER, mine, START));
        mine.clear();

        ArmedDelete.Confirmed confirmed = assertInstanceOf(ArmedDelete.Confirmed.class,
                delete.press(CHAPTER, ids("a", "b"), START + 1),
                "the question is about the ids as they were named");
        assertEquals(List.of("a", "b"), confirmed.ids());
        assertEquals(0, delete.armedCount(), "and nothing is armed afterwards");
    }
}
