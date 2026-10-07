package dev.ellipog.tenet.client.dev;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The picker's conversions, pinned without a client.
 *
 * <p>A colour round trip that is off by one in a channel still looks like the right colour, which is why
 * these are asserted rather than eyeballed: the tolerance below is one byte, and the anchors are exact.
 */
class ColourMathTest {

    @Test
    @DisplayName("the hue anchors land on red, green and blue")
    void hueAnchors() {
        assertEquals(0F, ColourMath.toHsv(0xFFFF0000).hue(), 0.001F);
        assertEquals(1F / 3F, ColourMath.toHsv(0xFF00FF00).hue(), 0.001F);
        assertEquals(2F / 3F, ColourMath.toHsv(0xFF0000FF).hue(), 0.001F);
    }

    @Test
    @DisplayName("a grey has no hue and no saturation, and keeps its value")
    void grey() {
        ColourMath.Hsv hsv = ColourMath.toHsv(0xFF808080);
        assertEquals(0F, hsv.hue(), 0.001F);
        assertEquals(0F, hsv.saturation(), 0.001F);
        assertEquals(128F / 255F, hsv.value(), 0.001F);
    }

    @Test
    @DisplayName("a colour survives the round trip, alpha included")
    void roundTrip() {
        int[] colours = {0xFF000000, 0xFFFFFFFF, 0xFF123456, 0x8012AB34, 0x00FF00FF, 0xFF7F3F1F};
        for (int argb : colours) {
            ColourMath.Hsv hsv = ColourMath.toHsv(argb);
            int back = ColourMath.fromHsv(hsv.hue(), hsv.saturation(), hsv.value(),
                    ColourMath.channel(argb, 3));
            assertEquals(ColourMath.channel(argb, 3), ColourMath.channel(back, 3),
                    "alpha changed for " + Integer.toHexString(argb));
            for (int index = 0; index < 3; index++) {
                assertTrue(Math.abs(ColourMath.channel(argb, index)
                                - ColourMath.channel(back, index)) <= 1,
                        "channel " + index + " moved for " + Integer.toHexString(argb)
                                + ": " + Integer.toHexString(back));
            }
        }
    }

    @Test
    @DisplayName("a channel reads and writes one byte of four, and clamps")
    void channels() {
        int argb = 0xFF123456;
        assertEquals(0x12, ColourMath.channel(argb, 0));
        assertEquals(0x34, ColourMath.channel(argb, 1));
        assertEquals(0x56, ColourMath.channel(argb, 2));
        assertEquals(0xFF, ColourMath.channel(argb, 3));

        assertEquals(0xFFAB3456, ColourMath.withChannel(argb, 0, 0xAB));
        assertEquals(0xFF12CD56, ColourMath.withChannel(argb, 1, 0xCD));
        assertEquals(0xFF1234EF, ColourMath.withChannel(argb, 2, 0xEF));
        assertEquals(0x40123456, ColourMath.withChannel(argb, 3, 0x40));
        // Out of range is clamped rather than wrapping into the neighbouring byte.
        assertEquals(0xFFFF3456, ColourMath.withChannel(argb, 0, 999));
        assertEquals(0xFF123400, ColourMath.withChannel(argb, 2, -5));
    }
}
