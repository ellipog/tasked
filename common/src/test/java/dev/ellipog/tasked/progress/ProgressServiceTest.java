package dev.ellipog.tasked.progress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine's scheduling rule, in isolation.
 *
 * <h2>Why one arithmetic method deserves its own test class</h2>
 *
 * <p>Because both faults that have lived here were invisible to every test that could be written about
 * the engine's <i>rules</i>. A rule is a pure function of the world and can be checked by calling it.
 * This is a pure function of the <b>clock</b>, and until something is actually ticking it has no output
 * to assert on -- which is why the first of the two shipped, and was found by a player gathering eight
 * logs and watching nothing happen. The second was found by a player reporting that progress worked in
 * one world and not the other.
 *
 * <p>So the decision is a method over two longs and an int rather than an expression inside a loop that
 * needs a running server, and it is tested here.
 *
 * <p>Neither case below is hypothetical. The first was reported as "it doesn't register the logs in my
 * inventory". The second is created by playing two worlds in one session, which is what any player does
 * -- and it produces the sentence that prompted this file: <i>"quest progress worked in one world but
 * not the other, idk why, it seems inconsistent at the very least"</i>. It was not inconsistent.
 */
@DisplayName("ProgressService scheduling")
class ProgressServiceTest {

    /** The default item-task interval: one second, twenty ticks. Small enough to reason about. */
    private static final int INTERVAL = 20;

    @Test
    @DisplayName("a task that has never been evaluated is due, with no sentinel in the arithmetic")
    void neverEvaluatedIsDue() {
        assertTrue(ProgressService.isDue(0L, null, INTERVAL),
                "a game time of zero is a real time and a task with no record must be evaluated");
        assertTrue(ProgressService.isDue(Long.MAX_VALUE, null, INTERVAL));
    }

    @Test
    @DisplayName("a task evaluated within its interval is not due")
    void recentlyEvaluatedIsNotDue() {
        assertFalse(ProgressService.isDue(100L, 90L, INTERVAL));
    }

    @Test
    @DisplayName("a task becomes due the moment its interval has elapsed, and not before")
    void theIntervalIsTheBoundary() {
        assertFalse(ProgressService.isDue(119L, 100L, INTERVAL), "19 ticks into a 20-tick interval");
        assertTrue(ProgressService.isDue(120L, 100L, INTERVAL), "exactly the interval");
        assertTrue(ProgressService.isDue(121L, 100L, INTERVAL));
    }

    @Test
    @DisplayName("a clock that has gone backwards is due, not 'recently evaluated'")
    void aBackwardsClockIsDue() {
        // The second world. Game time is per-world and restarts near zero; the schedule is per-process
        // and keyed by an id that survives the world change. So the new world's clock is *behind* the
        // recorded time, and every task is held back until it catches up.
        assertTrue(ProgressService.isDue(50L, 5_000L, INTERVAL),
                "a smaller clock must evaluate rather than skip. Skipping is every task in the second "
                        + "world being ignored for as long as the first world was played");
        assertTrue(ProgressService.isDue(0L, 1L, INTERVAL), "even one tick backwards");

        // And the arithmetic that made it happen, asserted so this cannot be "fixed" back by somebody
        // who reads `now - lastAt < interval` and sees nothing wrong with it. The difference is
        // negative, and a negative is less than any positive interval -- the old expression was true
        // here, which is why the old code called this task "just evaluated" and moved on.
        assertTrue(50L - 5_000L < INTERVAL,
                "the old test really did call a backwards clock 'not yet due'");
    }

    @Test
    @DisplayName("the overflow case, for the record, since it is the reason null is used at all")
    void theSentinelThatShipped() {
        // `Long.MIN_VALUE` as the "never evaluated" sentinel, evaluated with the documented expression.
        // Subtracting it wraps, and the wrapped value is smaller than any interval -- so a task that had
        // never once been looked at read as evaluated a moment ago, forever, because the caller only
        // records the time once this test passes. Hence `null` and a branch, rather than a sentinel that
        // the subtraction has to survive.
        long now = 100L;
        long wrapped = now - Long.MIN_VALUE;
        assertTrue(wrapped < INTERVAL,
                "now - Long.MIN_VALUE must wrap to a value below the interval for this to be the bug it "
                        + "was: " + wrapped);
    }
}
