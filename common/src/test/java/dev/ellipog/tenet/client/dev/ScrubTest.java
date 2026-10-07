package dev.ellipog.tenet.client.dev;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The scrubbable field's arithmetic, pinned without a client.
 *
 * <p>Every one of these rules was a choice -- how far a drag must travel, what the modifiers multiply
 * by, where a value settles, and what a typed string means -- and each is the kind of choice that is
 * invisible until it is wrong in a running game. Here they are asserted directly.
 */
class ScrubTest {

    @Test
    @DisplayName("a base drag moves one unit per four pixels, in the drag's own direction")
    void baseRate() {
        assertEquals(0.0, Scrub.delta(0, false, false));
        assertEquals(1.0, Scrub.delta(4, false, false));
        assertEquals(-2.5, Scrub.delta(-10, false, false));
    }

    @Test
    @DisplayName("Shift is coarse, Ctrl/Alt is fine, and fine wins when both are held")
    void modifiers() {
        assertEquals(10.0, Scrub.delta(4, true, false));
        assertEquals(0.1, Scrub.delta(4, false, true));
        // Both at once is the two opposite requests; the careful one is the one that is honoured.
        assertEquals(0.1, Scrub.delta(4, true, true));
    }

    @Test
    @DisplayName("a value clamps to its ends and quantizes to its step")
    void settle() {
        assertEquals(6.0, Scrub.settle(0, 6, 256, 1));
        assertEquals(256.0, Scrub.settle(999, 6, 256, 1));
        assertEquals(14.0, Scrub.settle(13.6, 0, 100, 1));
        assertEquals(1.5, Scrub.settle(1.44, 0, 10, 0.5));
        // A step of zero is "no quantization", not "round to zero".
        assertEquals(3.2, Scrub.settle(3.2, 0, 10, 0));
    }

    @Test
    @DisplayName("integers print without a decimal point, and the unit is the caller's")
    void formatting() {
        assertEquals("14 px", Scrub.format(14, 1, " px"));
        assertEquals("20%", Scrub.format(20, 1, "%"));
        assertEquals("0", Scrub.format(0.2, 1, ""));
        assertEquals("1.5", Scrub.format(1.5, 0.5, ""));
        assertEquals("0.25", Scrub.format(0.25, 0.01, ""));
    }

    @Test
    @DisplayName("a formatted value parses back, and nonsense is null rather than a guess")
    void parsing() {
        assertEquals(14.0, Scrub.parse("14 px"), 0.0);
        assertEquals(14.0, Scrub.parse("14px"), 0.0);
        assertEquals(20.0, Scrub.parse("20%"), 0.0);
        assertEquals(-3.5, Scrub.parse("-3.5"), 0.0);
        assertNull(Scrub.parse(""));
        assertNull(Scrub.parse("   "));
        assertNull(Scrub.parse("-"));
        assertNull(Scrub.parse("."));
        assertNull(Scrub.parse("1.2.3"));
        assertNull(Scrub.parse(null));
    }
}
