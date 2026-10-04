package dev.ellipog.tasked.client.dev;

import dev.ellipog.armature.client.ui.kit.Colour;

/**
 * The colour picker's arithmetic: ARGB to hue/saturation/value and back, and single channels.
 *
 * <h2>Why this is game-free</h2>
 *
 * <p>Because a colour picker is the one control where being wrong is not obvious: a hue that drifts a
 * degree per round trip, a value that loses its alpha when the saturation is dragged, or a channel edit
 * that shifts the wrong byte all still draw a plausible colour. The widget that shows the picker does the
 * painting; the conversions live here, where the tests are.
 *
 * <h2>The conventions, which matter more than the arithmetic</h2>
 *
 * <p>Hue is a fraction of the circle in {@code [0, 1)} rather than degrees, because the picker's own
 * geometry is a fraction (a track's x, a box's y) and converting to degrees and back at both ends is one
 * more place for a factor of 180 to go missing. Saturation and value are fractions too. Alpha travels
 * separately the whole way: nothing here changes it unless it is asked to, and every call that builds a
 * colour takes the alpha to put in it.
 */
public final class ColourMath {

    /** A hue, a saturation and a value, each a fraction; alpha is carried beside them. */
    public record Hsv(float hue, float saturation, float value) {
    }

    /** The red, green, blue or alpha byte of an ARGB int, by index: 0, 1, 2, 3. */
    public static int channel(int argb, int index) {
        return switch (index) {
            case 0 -> Colour.rgb(argb) >> 16 & 0xFF;
            case 1 -> Colour.rgb(argb) >> 8 & 0xFF;
            case 2 -> Colour.rgb(argb) & 0xFF;
            case 3 -> Colour.alpha(argb);
            default -> throw new IllegalArgumentException("channel index must be 0..3: " + index);
        };
    }

    /** The same colour with one channel replaced. The other three are untouched. */
    public static int withChannel(int argb, int index, int value) {
        int clamped = Math.max(0, Math.min(255, value));
        return switch (index) {
            case 0 -> (argb & 0xFF00FFFF) | clamped << 16;
            case 1 -> (argb & 0xFFFF00FF) | clamped << 8;
            case 2 -> (argb & 0xFFFFFF00) | clamped;
            case 3 -> Colour.withAlpha(argb, clamped);
            default -> throw new IllegalArgumentException("channel index must be 0..3: " + index);
        };
    }

    /**
     * The hue, saturation and value of a colour. Alpha is not part of the answer.
     *
     * <p>Gray has no hue, and the convention here is 0 -- the picker's marker parks at red rather than
     * jumping somewhere arbitrary, and the saturation slider is what says "there is no hue in this".
     */
    public static Hsv toHsv(int argb) {
        float r = (Colour.rgb(argb) >> 16 & 0xFF) / 255F;
        float g = (Colour.rgb(argb) >> 8 & 0xFF) / 255F;
        float b = (Colour.rgb(argb) & 0xFF) / 255F;

        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;

        float hue = 0F;
        if (delta > 0F) {
            if (max == r) {
                hue = ((g - b) / delta) % 6F;
            }
            else if (max == g) {
                hue = (b - r) / delta + 2F;
            }
            else {
                hue = (r - g) / delta + 4F;
            }
            hue /= 6F;
            if (hue < 0F) {
                hue += 1F;
            }
        }
        float saturation = max <= 0F ? 0F : delta / max;
        return new Hsv(hue, saturation, max);
    }

    /**
     * The colour a position in the picker's own space means, with the alpha to put in it.
     *
     * <p>The standard sextant walk. Rounded rather than truncated -- 0.5 rounds to the nearer byte at
     * every stage, which is what keeps a drag along the saturation axis from losing a step to integer
     * truncation at the ends.
     */
    public static int fromHsv(float hue, float saturation, float value, int alpha) {
        float h = ((hue % 1F) + 1F) % 1F;
        float s = Math.max(0F, Math.min(1F, saturation));
        float v = Math.max(0F, Math.min(1F, value));

        float sector = h * 6F;
        int index = (int) Math.floor(sector) % 6;
        float f = sector - (float) Math.floor(sector);
        float p = v * (1F - s);
        float q = v * (1F - s * f);
        float t = v * (1F - s * (1F - f));

        float r;
        float g;
        float b;
        switch (index) {
            case 0 -> {
                r = v;
                g = t;
                b = p;
            }
            case 1 -> {
                r = q;
                g = v;
                b = p;
            }
            case 2 -> {
                r = p;
                g = v;
                b = t;
            }
            case 3 -> {
                r = p;
                g = q;
                b = v;
            }
            case 4 -> {
                r = t;
                g = p;
                b = v;
            }
            default -> {
                r = v;
                g = p;
                b = q;
            }
        }
        return Colour.withAlpha(byteOf(r) << 16 | byteOf(g) << 8 | byteOf(b), alpha);
    }

    /** A unit float as a byte, clamped, so a caller's rounding cannot wrap past either end. */
    private static int byteOf(float unit) {
        return Math.max(0, Math.min(255, Math.round(unit * 255F)));
    }

    private ColourMath() {
    }
}
