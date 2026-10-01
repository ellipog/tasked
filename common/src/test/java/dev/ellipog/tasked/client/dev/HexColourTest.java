package dev.ellipog.tasked.client.dev;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading a hex code: the forms, the alpha rule, and the one that shipped broken.
 *
 * <h2>The fault these exist for</h2>
 *
 * <p>The reading used to be inside the screen, with {@code argb < 0} as its "unreadable" sentinel — and
 * every opaque colour is <i>negative</i> as an int, because its top bit is set. So {@code #0A0A0D}, the
 * value the field was displaying at the time, was answered with *"is not a hex colour"*, and the log from
 * that session is forty lines of the player typing valid codes and being told they were not. No test could
 * catch it, because the arithmetic lived in a screen and a screen cannot be instantiated by a test. The
 * first case below is that report, written down.
 */
@DisplayName("hex codes")
class HexColourTest {

    /** An opaque colour, as the theme holds one. */
    private static final int OPAQUE = 0xFF24242E;

    @Test
    @DisplayName("the code the field was showing is readable, and it is negative")
    void theCodeTheFieldWasShowing() {
        int parsed = HexColour.parse("#0A0A0D", OPAQUE);

        assertEquals(0xFF0A0A0D, parsed);
        assertTrue(parsed < 0, "and it is negative, which is exactly what the sign test got wrong");
    }

    @Test
    @DisplayName("with or without the hash, upper or lower case")
    void theFormsPeopleType() {
        assertEquals(0xFF0A0A0D, HexColour.parse("0A0A0D", OPAQUE));
        assertEquals(0xFFAABBCC, HexColour.parse("aabbcc", OPAQUE));
        assertEquals(0xFFAABBCC, HexColour.parse("#AaBbCc", OPAQUE));
        assertEquals(0xFF0A0A0D, HexColour.parse("  #0A0A0D  ", OPAQUE), "a code pasted out of a file");
    }

    @Test
    @DisplayName("the three-digit short form doubles each digit")
    void theShortForm() {
        assertEquals(0xFFAABBCC, HexColour.parse("#abc", OPAQUE));
        assertEquals(0xFF000000, HexColour.parse("#000", OPAQUE));
        assertEquals(0xFFFFFFFF, HexColour.parse("#fff", OPAQUE));
    }

    @Test
    @DisplayName("only an eight-digit code carries an alpha, and then it is the one typed")
    void alphaComesFromEightDigits() {
        assertEquals(0x801A2B3C, HexColour.parse("#801A2B3C", OPAQUE));
        assertEquals(0x001A2B3C, HexColour.parse("#001A2B3C", OPAQUE), "even a fully transparent one");
    }

    @Test
    @DisplayName("a code without an alpha keeps the colour's own, which is the whole reason the rule exists")
    void sixDigitsKeepTheExistingAlpha() {
        // Three of the forty-one tokens are translucent on purpose -- the screen dim above all -- and an
        // alpha silently set to FF is not something the author can see and undo by looking at the colour.
        int translucent = 0xB80A0A0D;

        assertEquals(0xB81A2B3C, HexColour.parse("#1A2B3C", translucent));
        assertEquals(0xFF1A2B3C, HexColour.parse("#1A2B3C", OPAQUE));
        assertEquals(0xB8AABBCC, HexColour.parse("#abc", translucent), "the short form has no alpha slot "
                + "either, so it keeps the one that is there");
    }

    @Test
    @DisplayName("anything that is not a colour is null, rather than a guess")
    void notColours() {
        assertNull(HexColour.parse("#FFFFFFF", OPAQUE), "one keystroke away from right, and wrong");
        assertNull(HexColour.parse("#0A0A0", OPAQUE));
        assertNull(HexColour.parse("#GGGGGG", OPAQUE));
        assertNull(HexColour.parse("hello", OPAQUE));
        assertNull(HexColour.parse("", OPAQUE));
        assertNull(HexColour.parse("#", OPAQUE));
        assertNull(HexColour.parse(null, OPAQUE), "and a null is not an exception in a click handler");
    }

    @Test
    @DisplayName("every colour the shipped theme holds round-trips through its own code")
    void theThemesOwnCodesRoundTrip() {
        // The values the panel displays are `#%06X` of these, so the parse has to accept exactly what the
        // display produces -- including the translucent one, whose alpha the display does not show at all.
        for (int argb : new int[] {0xFF0A0A0E, 0xFF24242E, 0xFFFFFFFF, 0xB80A0A0D, 0xFF86CE8A}) {
            String code = String.format("#%06X", argb & 0xFFFFFF);
            Integer parsed = HexColour.parse(code, argb);

            assertNotNull(parsed, () -> "the field's own display was refused: " + code);
            assertEquals(argb & 0xFFFFFF, parsed & 0xFFFFFF, () -> "rgb changed for " + code);
            assertEquals(argb & 0xFF000000, parsed & 0xFF000000, () -> "alpha changed for " + code);
        }
    }
}
