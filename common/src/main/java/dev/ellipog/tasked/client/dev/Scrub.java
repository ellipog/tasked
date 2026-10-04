package dev.ellipog.tasked.client.dev;

import java.util.Locale;

/**
 * The arithmetic of a scrubbable field: what a horizontal drag means, and what a typed value is.
 *
 * <h2>Why this is game-free</h2>
 *
 * <p>Because these are exactly the rules worth being wrong about — how far a drag has to travel for one
 * unit, what the modifiers multiply by, where a value clamps, and what "{@code 14 px}" parses to — and a
 * rule that lives in a widget can only be judged by dragging one in a running game. The widget
 * ({@code ScrubField}) keeps the pointer state and the drawing; every number it applies comes from here,
 * where the tests are.
 */
public final class Scrub {

    /**
     * How far the pointer must travel before a press becomes a drag rather than a click.
     *
     * <p>Three pixels, and it is the click's slop rather than the canvas's four: a field is a small
     * target and the hand is less steady over it than over a node, so the smaller value is the one that
     * stops a click from being read as a one-unit edit.
     */
    public static final int SLOP = 3;

    /** How many pixels of travel one unit of change takes, at the base sensitivity. */
    public static final double PIXELS_PER_UNIT = 4.0;

    /** Shift multiplies the base rate: coarse movement across a wide range. */
    public static final double COARSE_MULTIPLIER = 10.0;

    /** Ctrl or Alt multiplies the base rate: fine movement, a tenth of a unit at a time. */
    public static final double FINE_MULTIPLIER = 0.1;

    /**
     * The change a drag of {@code pixels} means.
     *
     * <p>Fine wins when both modifiers are held. They are the two opposite requests — go faster, go
     * slower — and of the two, ignoring the one that says "be careful" is the one a player can undo by
     * letting go of a key; ignoring "be careful" and jumping ten units is the one they cannot.
     */
    public static double delta(double pixels, boolean coarse, boolean fine) {
        double units = pixels / PIXELS_PER_UNIT;
        if (fine) {
            return units * FINE_MULTIPLIER;
        }
        if (coarse) {
            return units * COARSE_MULTIPLIER;
        }
        return units;
    }

    /** Held inside the range, either end inclusive. */
    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Rounds to the nearest multiple of {@code step}.
     *
     * <p>Applied to the value a drag lands on, not to its increments: accumulating quantized deltas is
     * how a slow drag of a tenth of a unit at a time never arrives anywhere. With a step of one this is
     * plain rounding, which is the common case — spacing, sizes and counts are whole numbers, and the
     * fine modifier is what makes a whole number approachable.
     */
    public static double quantize(double value, double step) {
        if (step <= 0) {
            return value;
        }
        return Math.round(value / step) * step;
    }

    /** The clamp and the quantization a field applies to any value before it is shown or committed. */
    public static double settle(double value, double min, double max, double step) {
        return quantize(clamp(value, min, max), step);
    }

    /**
     * How a value is shown at rest: the shortest exact text, then the unit.
     *
     * <p>Integers lose their decimal point entirely ("14 px", not "14.0 px") because that is how the
     * ranges these fields cover are authored everywhere else, and a trailing ".0" reads as a different
     * kind of number than the file holds. Fractional steps keep as many decimals as the step needs, at
     * most two.
     *
     * @param suffix appended as given — "{@code  px}" for a unit, "{@code %}" for a percentage, "" for
     *               a bare number, spacing included by the caller rather than invented here.
     */
    public static String format(double value, double step, String suffix) {
        String number;
        if (step >= 1) {
            number = Long.toString(Math.round(value));
        }
        else if (step >= 0.1) {
            number = String.format(Locale.ROOT, "%.1f", value);
        }
        else {
            number = String.format(Locale.ROOT, "%.2f", value);
        }
        return number + suffix;
    }

    /**
     * The number a typed string means, or null when it means none.
     *
     * <p>Everything that is not part of a number is dropped first, so the field's own formatted text can
     * be retyped after editing it — "{@code 14 px}" is fourteen, and so is "{@code 14px}" or
     * "{@code 14}". What survives is parsed strictly: a lone "-", a lone "." or a second dot
     * ("{@code 1.2.3}") is null rather than a guess, because a field that turns nonsense into a number is
     * a field that silently writes the wrong one.
     */
    public static Double parse(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.' || c == '-') {
                digits.append(c);
            }
        }
        if (digits.isEmpty() || digits.toString().equals("-") || digits.toString().equals(".")
                || digits.toString().equals("-.")) {
            return null;
        }
        try {
            return Double.valueOf(digits.toString());
        }
        catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private Scrub() {
    }
}
