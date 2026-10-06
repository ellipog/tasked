package dev.ellipog.tasked.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The instrument that counts what one editing gesture costs on the server.
 *
 * <h2>What this asserts, and what it deliberately does not</h2>
 *
 * <p>It asserts that the counter records what it is told, keeps its phases apart, does not invent a phase
 * that did not run, and costs nothing at all while it is off. Those are the properties the number's honesty
 * rests on: a counter that folded the apply into the save, or printed `writes 0` for a second in which
 * nothing was written, would produce a line nobody could read a claim out of — and the claims this line
 * exists to settle are the ones the performance report had to leave as arithmetic.
 *
 * <p>It does <b>not</b> assert that an edit is cheap, or that a burst costs what the report estimated. Those
 * are claims about a real pack on a real disk, and the honest place for them is the run this instrument
 * makes possible — a person with the counter on, editing a chapter, reading the line. A test that asserted a
 * millisecond would be asserting the machine it ran on.
 *
 * <h2>Why the static state is put back</h2>
 *
 * <p>The counter is static, like every instrument here, and the suite runs in one JVM — so a test that left
 * it on would have the next test's writes counted into its own tally, and the failure would look like a
 * wrong count rather than like a leaked switch. Every test therefore puts both back, in a `finally`.
 */
@DisplayName("The edit-cost counter")
class EditPhasesTest {

    private static void off() {
        EditPhases.set(false);
        EditPhases.reset();
    }

    @Test
    @DisplayName("nothing is counted while the counter is off")
    void offCountsNothing() {
        off();
        try {
            EditPhases.applied(5_000_000L);
            EditPhases.wrote();
            EditPhases.flushed(9_000_000L, 3_000_000L, 1_000_000L, 4);

            assertFalse(EditPhases.on(), "the switch is off");
            assertTrue(EditPhases.tally().isEmpty(),
                    "a server with the counter off must not pay for the instrument, which is the property "
                            + "that lets it sit on an apply path at all");
        }
        finally {
            off();
        }
    }

    @Test
    @DisplayName("an op and a write are counted on their own, and a phase that did not run is absent")
    void onCountsPerPhase() {
        off();
        EditPhases.set(true);
        try {
            EditPhases.applied(7_000_000L);
            EditPhases.applied(3_000_000L);
            EditPhases.wrote();
            EditPhases.wrote();

            Map<String, Long> tally = EditPhases.tally();
            assertEquals(2L, tally.get("ops"), "two ops were applied");
            assertEquals(10L, tally.get("applyMs"),
                    "and the millis are summed rather than averaged, so a mean is the reader's division "
                            + "and the count is still there to divide by");
            assertEquals(2L, tally.get("writes"), "two files were written");
            assertEquals(2L, tally.get("syncs"),
                    "and each write is one atomic write, which is one fsync -- see `EditPhases`");

            // A phase that did not run has no entry rather than a zero. That is the difference between a
            // line that says "nothing was written this second" and a line that says "writes 0" for every
            // second of a server nobody is editing, which reads as a measurement.
            assertFalse(tally.containsKey("saveMs"), "the save is inside the apply, not a phase of its own");
        }
        finally {
            off();
        }
    }

    @Test
    @DisplayName("a zero is not recorded, so an idle phase cannot keep a line alive")
    void aZeroIsNotAPhase() {
        off();
        EditPhases.set(true);
        try {
            // An apply that took under a millisecond rounds to zero, and a tally that recorded it would
            // print `applyMs 0` — a phase that ran and cost nothing, which is not a thing a reader can act
            // on and is indistinguishable from one that never ran.
            EditPhases.applied(400_000L);

            Map<String, Long> tally = EditPhases.tally();
            assertEquals(1L, tally.get("ops"), "the op is still counted: it happened");
            assertFalse(tally.containsKey("applyMs"),
                    "but a sub-millisecond apply contributes no millis rather than a zero");
        }
        finally {
            off();
        }
    }

    @Test
    @DisplayName("reset forgets the window, which is what a report does")
    void resetForgetsTheWindow() {
        off();
        EditPhases.set(true);
        try {
            EditPhases.wrote();
            assertEquals(1L, EditPhases.tally().get("writes"));

            EditPhases.reset();
            assertTrue(EditPhases.tally().isEmpty(), "the window is what a report consumes");
        }
        finally {
            off();
        }
    }

    @Test
    @DisplayName("turning the counter off forgets what it held")
    void switchingOffForgets() {
        off();
        EditPhases.set(true);
        try {
            EditPhases.wrote();
            assertEquals(1L, EditPhases.tally().get("writes"));
        }
        finally {
            // `set(false)` is the switch a caller uses, and it must not leave a window behind: a counter
            // switched off and on again reporting the ops from before it was off would be a tally about a
            // window nobody chose.
            EditPhases.set(false);
        }

        assertTrue(EditPhases.tally().isEmpty(), "switching off clears the window");
        assertFalse(EditPhases.on());
    }
}
