package dev.ellipog.tasked.client;

import java.util.List;

/**
 * The settings card's rows, composed without a client.
 *
 * <h2>What is on this card, and what deliberately is not</h2>
 *
 * <p>The card holds <b>the text-size slider and nothing else</b>. The look is the pack's — a theme
 * list, a radius and a follow-the-pack row sat here once and moved to the tools panel, where an
 * author edits colours — and Motion is a switch in that same panel, beside the other mode switches.
 * What a player is owed on this card is the one thing that is about their own body rather than about
 * the book's appearance: how large the text draws.
 *
 * <h2>Why this is a class</h2>
 *
 * <p>The composition is arithmetic over the values {@code Look} reports, so it lives here where it
 * can be asserted without a screen, and the screen turns the entry into a widget. One row today, and
 * the shape is kept because the row list is what a test can hold and what a second row would join.
 */
public final class SettingsLayout {

    /** The text-size row. */
    public static final String TEXT_KEY = "text";

    /**
     * One row.
     *
     * @param key   the widget key the screen registers its control under
     * @param label what it reads as — the value is the slider's own message, so it can follow the
     *              thumb without a rebuild
     */
    public record Entry(String key, String label) {
    }

    private SettingsLayout() {
    }

    /**
     * The card's rows, in order.
     *
     * @param textScale the player's text size, as a factor; the slider reads it, the row does not
     */
    public static List<Entry> entries(double textScale) {
        return List.of(new Entry(TEXT_KEY, "Text size"));
    }
}
