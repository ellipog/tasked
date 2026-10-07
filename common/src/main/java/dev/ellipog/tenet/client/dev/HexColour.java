package dev.ellipog.tenet.client.dev;

/**
 * Reading a hex colour somebody typed.
 *
 * <h2>Why this is not in the screen</h2>
 *
 * <p>Because it was, and it was wrong in a way no test could see. The "did that parse" answer was a sign
 * test on the colour that came out — {@code argb < 0} — and every opaque colour has its top bit set, so
 * {@code 0xFF0A0A0D} <i>is</i> negative and <b>every valid six-digit code a player typed was refused</b>.
 * The field answered *"#0A0A0D is not a hex colour"* for the very value it was displaying, and the log
 * from that session is forty lines of it. Whether text is readable is a fact about the text, so it gets a
 * flag and a home where eight cases can pin it.
 *
 * <p>The rule that made the fault possible is worth stating, because it is not about colours: <b>a sentinel
 * must not be a value the code can legitimately produce.</b> {@code -1} was chosen to mean "no colour" by
 * somebody thinking of colours as positive, which they are — until the alpha byte is 0xFF, which it usually
 * is.
 */
public final class HexColour {

    private HexColour() {
    }

    /**
     * The colour {@code text} names, or null when it names none.
     *
     * <p>Reads {@code #RRGGBB}, {@code RRGGBB}, {@code #RGB} — the short form every colour picker accepts,
     * each digit doubled — and {@code #AARRGGBB} when the alpha matters. Surrounding whitespace is ignored,
     * because a code copied out of a file arrives with some.
     *
     * <p>A six- or three-digit code <b>keeps {@code existing}'s alpha</b> rather than becoming opaque; the
     * alpha only ever comes from an eight-digit code or from what the colour already had. Three of the
     * forty-three tokens are translucent on purpose — a screen dim that hides the world is the one that would
     * be noticed — and an alpha silently changed to FF is not recoverable by looking at the colour, which
     * then looks right on a background it should not look right on.
     *
     * @param text     what was typed
     * @param existing the colour's current value, whose alpha a code without one keeps
     */
    public static Integer parse(String text, int existing) {
        String trimmed = text == null ? "" : text.trim();
        String digits = trimmed.startsWith("#") ? trimmed.substring(1) : trimmed;
        if (digits.isEmpty()) {
            return null;
        }
        long parsed;
        try {
            parsed = Long.parseLong(digits, 16);
        }
        catch (NumberFormatException notHex) {
            return null;
        }
        return switch (digits.length()) {
            case 6 -> (existing & 0xFF000000) | (int) parsed;
            case 8 -> (int) parsed;
            case 3 -> {
                int red = (int) ((parsed >> 8) & 0xF);
                int green = (int) ((parsed >> 4) & 0xF);
                int blue = (int) (parsed & 0xF);
                yield (existing & 0xFF000000) | (red * 17) << 16 | (green * 17) << 8 | blue * 17;
            }
            // Any other length is not a colour, and returning null rather than guessing is the whole point
            // of the flag: `#FFFFFFF` is one keystroke away from right and a wrong colour is worse than a
            // message.
            default -> null;
        };
    }
}
