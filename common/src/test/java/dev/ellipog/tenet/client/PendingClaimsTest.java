package dev.ellipog.tenet.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The marks a press leaves behind, and the three ways one goes.
 *
 * <h2>What this holds that the screen cannot</h2>
 *
 * <p>The screen that reads this is not instantiated by any test, so every rule worth being wrong about is
 * here instead: that a second press for one key is refused rather than sent, that a mark survives an
 * unrelated revision, that the backstop drops it <b>and reports it</b>, and that the count rule takes a
 * marked reward off what is owed without taking it off what is listed.
 */
@DisplayName("the presses still waiting for an answer")
class PendingClaimsTest {

    @Test
    @DisplayName("a mark is recorded once, and the second press is refused rather than sent")
    void aSecondPressIsRefused() {
        PendingClaims marks = new PendingClaims();
        String key = PendingClaims.rewardKey("punch_a_tree", 0);

        assertTrue(marks.mark(key, 1000L), "the first press is new, so the caller sends");
        assertTrue(marks.isMarked(key), "and it is in flight");
        assertFalse(marks.mark(key, 1010L), "a second press for one row must not become a second packet");
        assertEquals(1, marks.keys().size(), "and must not add a second mark");
    }

    @Test
    @DisplayName("the marks of different subjects are different keys")
    void theKeysNameTheirSubject() {
        // A task key that collided with a quest key would make handing one task in read as collecting the
        // quest's rewards -- and the two are read by different counts.
        assertNotEquals(PendingClaims.taskKey("q", 0), PendingClaims.questKey("q"));
        assertNotEquals(PendingClaims.taskKey("q", 0), PendingClaims.taskKey("q", 1));
        assertNotEquals(PendingClaims.rewardKey("q", 0), PendingClaims.rewardKey("q", 1));
        assertNotEquals(PendingClaims.questKey("q"), PendingClaims.chapterKey("q"));
    }

    @Test
    @DisplayName("a key says what it names, and a key nobody wrote says nothing")
    void aKeyTakesApartAgain() {
        // The reconciler asks this to decide whether a mark's subject is still in the tree, so a key that
        // parses wrongly is a mark that resolves against the wrong quest.
        PendingClaims.Subject task = PendingClaims.subjectOf(PendingClaims.taskKey("punch_a_tree", 2));
        assertEquals(PendingClaims.Kind.TASK, task.kind());
        assertEquals("punch_a_tree", task.owner());
        assertEquals(2, task.index());

        PendingClaims.Subject reward = PendingClaims.subjectOf(PendingClaims.rewardKey("make_a_table", 1));
        assertEquals(PendingClaims.Kind.REWARD, reward.kind());
        assertEquals("make_a_table", reward.owner());
        assertEquals(1, reward.index());

        PendingClaims.Subject quest = PendingClaims.subjectOf(PendingClaims.questKey("make_a_table"));
        assertEquals(PendingClaims.Kind.QUEST, quest.kind());
        assertEquals(-1, quest.index(), "a whole quest names no index");

        PendingClaims.Subject chapter = PendingClaims.subjectOf(PendingClaims.chapterKey("first_steps"));
        assertEquals(PendingClaims.Kind.CHAPTER, chapter.kind());
        assertEquals("first_steps", chapter.owner());

        assertEquals(null, PendingClaims.subjectOf("something else"), "a key this build did not write");
        assertEquals(null, PendingClaims.subjectOf(null));
        assertEquals(null, PendingClaims.subjectOf("reward:q:notanumber"), "a key that is not one");
    }

    @Test
    @DisplayName("an answer that agrees forgets the mark, and the revision moves")
    void anAgreementResolves() {
        PendingClaims marks = new PendingClaims();
        String key = PendingClaims.questKey("make_a_table");
        long before = marks.revision();

        marks.mark(key, 1000L);
        assertNotEquals(before, marks.revision(), "a reader that cached an answer has to be told to ask again");

        marks.resolve(key);
        assertFalse(marks.isMarked(key), "the server said it happened, so there is nothing to believe");
        assertTrue(marks.keys().isEmpty());
    }

    @Test
    @DisplayName("resolving a key nobody marked changes nothing")
    void resolvingAnUnmarkedKeyIsQuiet() {
        PendingClaims marks = new PendingClaims();
        long before = marks.revision();
        marks.resolve(PendingClaims.rewardKey("q", 3));
        assertEquals(before, marks.revision(), "a revision that moves for nothing is a rebuild for nothing");
    }

    @Test
    @DisplayName("a mark is kept until the backstop, and then dropped and reported")
    void theBackstopDropsAndReports() {
        PendingClaims marks = new PendingClaims();
        String key = PendingClaims.taskKey("punch_a_tree", 0);
        marks.mark(key, 10_000L);

        assertTrue(marks.expired(10_000L + PendingClaims.BACKSTOP_MILLIS - 1).isEmpty(),
                "inside the window the press is still believed");
        assertTrue(marks.isMarked(key), "and it is still in flight");

        List<String> gone = marks.expired(10_000L + PendingClaims.BACKSTOP_MILLIS);
        assertEquals(List.of(key), gone, "at the backstop it goes, and the caller is told which");
        assertFalse(marks.isMarked(key), "a mark nobody answered must not be believed forever");
    }

    @Test
    @DisplayName("only the marks past the backstop expire")
    void onlyTheOldOnesExpire() {
        PendingClaims marks = new PendingClaims();
        String old = PendingClaims.rewardKey("q", 0);
        String fresh = PendingClaims.rewardKey("q", 1);
        marks.mark(old, 1_000L);
        marks.mark(fresh, 1_000L + PendingClaims.BACKSTOP_MILLIS);

        assertEquals(List.of(old), marks.expired(1_000L + PendingClaims.BACKSTOP_MILLIS),
                "the one pressed later is still in flight");
        assertTrue(marks.isMarked(fresh));
    }

    @Test
    @DisplayName("a clock that went backwards expires nothing")
    void aBackwardsClockKeepsEverything() {
        // `Util.getMillis` is not promised to be monotonic across a system clock change, and the safe
        // direction is to keep believing a press -- the next answer corrects it either way.
        PendingClaims marks = new PendingClaims();
        String key = PendingClaims.rewardKey("q", 0);
        marks.mark(key, 10_000L);
        assertTrue(marks.expired(9_000L).isEmpty(), "nothing expires on a negative age");
        assertTrue(marks.isMarked(key));
    }

    @Test
    @DisplayName("what is owed counts the press off, and what is listed does not")
    void outstandingTakesTheMarkOffTheCount() {
        // Four rewards: the first is claimable and unmarked, the second is claimable with a press in
        // flight, the third is gated by a condition and the fourth is already collected. One is owed --
        // the gated and collected ones were never owed, and the pressed one is being taken.
        boolean[] claimable = {true, true, false, false};
        boolean[] marked = {false, true, false, false};

        assertEquals(1, PendingClaims.outstanding(4, i -> claimable[i], i -> marked[i]),
                "a press in flight is not something the player can still take");
        assertEquals(2, PendingClaims.outstanding(4, i -> claimable[i], i -> false),
                "and without the press it is, which is the count the badge is taking off");
        assertEquals(0, PendingClaims.outstanding(0, i -> true, i -> false),
                "a quest with no rewards owes nothing");
    }
}
