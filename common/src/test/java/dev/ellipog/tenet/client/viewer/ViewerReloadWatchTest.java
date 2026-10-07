package dev.ellipog.tenet.client.viewer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a viewer is asked to rebuild itself.
 *
 * <h2>Why the timing rule is asserted rather than measured in the game</h2>
 *
 * <p>Because the rule is arithmetic over ticks and one number: which revision is being watched, and how
 * many ticks it has held. Waiting for it in a client would be a hand test of a `for` loop — and the two
 * failures that matter are both invisible there. A watch that asked <b>per keystroke</b> looks like a
 * viewer that keeps up; a watch that asked for a revision it had already asked for, because a rebuild
 * failed, looks like a viewer that is slow rather than one retrying every tick. So the class takes a
 * tick and answers, both of those are cases below, and the debounce is set to three here instead of
 * twelve.
 *
 * <p>The third failure is the one with no symptom at all: a watch that never asks, so the viewer shows
 * a quest tree from before the author's last edit and nothing says so. The settle cases are written so
 * that a watch which only ever counts up, or only ever resets, fails one of them.
 */
@DisplayName("the viewer reload watch")
class ViewerReloadWatchTest {

    /** A registered revision that is never the one being watched, so nothing is already built. */
    private static final long NOT_BUILT = 1L;

    @Test
    @DisplayName("a revision that keeps moving asks for nothing")
    void aBurstAsksForNothing() {
        ViewerReloadWatch watch = new ViewerReloadWatch(3);

        for (long revision = 10L; revision < 30L; revision++) {
            assertFalse(watch.due(NOT_BUILT, revision),
                    "an edit per tick is a gesture in progress, and the viewer is not asked to keep up "
                            + "with one");
        }
    }

    @Test
    @DisplayName("a revision that holds still asks exactly once, after the settle")
    void aSettledRevisionAsksOnce() {
        ViewerReloadWatch watch = new ViewerReloadWatch(3);

        assertFalse(watch.due(NOT_BUILT, 10L), "the first sighting starts the run rather than asking");
        assertFalse(watch.due(NOT_BUILT, 10L));
        assertTrue(watch.due(NOT_BUILT, 10L), "the third tick of the same revision is the settle");

        assertFalse(watch.due(NOT_BUILT, 10L), "and a revision already asked for is never asked again");
        assertFalse(watch.due(NOT_BUILT, 10L));
    }

    @Test
    @DisplayName("a revision the viewer has already built asks for nothing")
    void aBuiltRevisionAsksNothing() {
        ViewerReloadWatch watch = new ViewerReloadWatch(1);

        assertFalse(watch.due(10L, 10L),
                "the viewer is showing this revision, which is what registration means");
    }

    @Test
    @DisplayName("a rebuild that failed is not retried, and the next change is the retry point")
    void aFailedReloadIsNotRetried() {
        ViewerReloadWatch watch = new ViewerReloadWatch(1);
        assertTrue(watch.due(NOT_BUILT, 10L), "asked for");

        // It failed: `register` never runs, so the viewer still reports the old revision as built.
        for (int tick = 0; tick < 50; tick++) {
            assertFalse(watch.due(NOT_BUILT, 10L),
                    "a watch that retried would retry every tick for as long as the failure lasted, "
                            + "which is worse than the churn it exists to prevent");
        }

        assertTrue(watch.due(NOT_BUILT, 11L),
                "and a change is a new revision by definition, so it is the honest place to try again");
    }

    @Test
    @DisplayName("a new revision starts the clock again rather than inheriting the old run")
    void aNewRevisionRestartsTheClock() {
        ViewerReloadWatch watch = new ViewerReloadWatch(3);
        watch.due(NOT_BUILT, 10L);
        watch.due(NOT_BUILT, 10L);
        assertTrue(watch.due(NOT_BUILT, 10L));

        assertFalse(watch.due(NOT_BUILT, 11L), "a new revision is a new run, however settled the last was");
        assertFalse(watch.due(NOT_BUILT, 11L));
        assertTrue(watch.due(NOT_BUILT, 11L));
    }

    @Test
    @DisplayName("a settle of one is no debounce at all")
    void aSettleOfOneAsksImmediately() {
        ViewerReloadWatch watch = new ViewerReloadWatch(1);

        assertTrue(watch.due(NOT_BUILT, 10L),
                "the degenerate case is a real setting, and it is what having no watch looks like");
        assertTrue(watch.due(NOT_BUILT, 11L), "and it still asks once per revision");
        assertFalse(watch.due(NOT_BUILT, 11L));
    }

    @Test
    @DisplayName("a settle below one is refused rather than treated as a faster debounce")
    void aSettleBelowOneIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ViewerReloadWatch(0),
                "'settle for no ticks' is not a rule anyone means, so it is not silently rounded to one");
    }
}
