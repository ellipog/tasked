package dev.ellipog.tenet.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arming window, swept at its boundaries rather than waited through.
 *
 * <h2>Why this class is worth writing after the fact</h2>
 *
 * <p>{@link ArmedPress}'s own note has named this test since it was written — "here it is arithmetic on
 * a clock the caller supplies, and {@code ArmedPressTest} sweeps the boundaries" — and the file did not
 * exist. So the one rule in this book that decides whether a destructive control acts on the first press
 * or the second was asserted by nothing: not by a test, and not by any of the structural checks, which
 * read call sites rather than behaviour. A javadoc claiming a test that is not there is the same fault
 * as a field that is parsed and never consumed, and it reads as supported.
 *
 * <p>The clock is a parameter, so every case below is microseconds: nothing here sleeps, and nothing
 * here can be flaky on a loaded machine.
 */
@DisplayName("ArmedPress")
class ArmedPressTest {

    private static final long START = 1_000L;

    @Test
    @DisplayName("the first press asks and the second confirms, and confirming disarms")
    void theFirstPressAsksAndTheSecondConfirms() {
        ArmedPress press = new ArmedPress();

        assertFalse(press.press(START), "the first press asks rather than acting");
        assertTrue(press.armed(START), "and the control is now waiting for a second");
        assertTrue(press.press(START + 1), "the second press confirms");
        assertFalse(press.armed(START + 1), "and the control goes back to saying what it does");
    }

    @Test
    @DisplayName("the window ends exactly at its last millisecond, on both sides")
    void theWindowBoundaryIsExact() {
        // The boundary is the whole point of a window: a press one millisecond late must ask again rather
        // than delete, and the case that decides it has to be asserted on both sides of the line.
        ArmedPress inside = new ArmedPress();
        assertFalse(inside.press(START));
        assertTrue(inside.armed(START + ArmedPress.WINDOW_MILLIS),
                "the last millisecond of the window is still inside it");

        ArmedPress after = new ArmedPress();
        assertFalse(after.press(START));
        assertFalse(after.armed(START + ArmedPress.WINDOW_MILLIS + 1),
                "one past the end is out, so the next press asks again");
        assertFalse(after.press(START + ArmedPress.WINDOW_MILLIS + 1));
        assertTrue(after.armed(START + ArmedPress.WINDOW_MILLIS + 1),
                "and it is that press which is armed, not the lapsed one");
    }

    @Test
    @DisplayName("lapsed is not idle, which is why it is a question of its own")
    void lapsedIsNotTheSameAsIdle() {
        ArmedPress idle = new ArmedPress();
        assertFalse(idle.lapsed(START), "nothing was pressed, so nothing has lapsed");
        assertFalse(idle.armed(START));

        ArmedPress waiting = new ArmedPress();
        waiting.press(START);
        assertFalse(waiting.lapsed(START + 1), "a live window has not lapsed");
        assertTrue(waiting.lapsed(START + ArmedPress.WINDOW_MILLIS + 1),
                "and one past its end has, which is what a label restoring itself reads");
    }

    @Test
    @DisplayName("disarm means no, and a later press is a fresh question")
    void disarmForgetsThePress() {
        ArmedPress press = new ArmedPress();
        press.press(START);
        press.disarm();

        assertFalse(press.armed(START + 1), "disarmed is not armed");
        assertFalse(press.lapsed(START + 1), "and it is idle rather than lapsed: nothing was thrown away");
        assertFalse(press.press(START + 1), "so the next press asks rather than confirming");
        assertTrue(press.armed(START + 1));
    }

    @Test
    @DisplayName("a clock reading zero arms like any other, because the stamp is not the sentinel")
    void aZeroClockArms() {
        // This is the case the old implementation got wrong: zero doubled as "idle", so a first press at
        // the first millisecond of a JVM set the stamp to zero and armed nothing.
        ArmedPress press = new ArmedPress();

        assertFalse(press.press(0L), "the first press asks");
        assertTrue(press.armed(0L), "and it is armed");
        assertTrue(press.press(1L), "so the second confirms");
    }

    @Test
    @DisplayName("disarming an idle press is a no-op rather than an error")
    void disarmingTwiceIsHarmless() {
        // Every gesture path in the book disarms whether or not anything is armed, so this is the call
        // that happens most often.
        ArmedPress press = new ArmedPress();
        press.disarm();
        press.disarm();
        assertFalse(press.armed(START));
    }
}
