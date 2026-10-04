package dev.ellipog.tasked.client;

import dev.ellipog.tasked.client.SettingsLayout.Entry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings card's composition, without a screen.
 *
 * <p>The card is <b>the text-size slider and nothing else</b>: the look belongs to the pack and lives
 * in the tools panel, and Motion is a switch in that same panel. So the second test below — nothing
 * else on this card — is the point rather than an omission.
 */
@DisplayName("the settings card's rows")
class SettingsLayoutTest {

    @Test
    @DisplayName("the card is the text-size row alone")
    void theCardIsTheSliderAlone() {
        List<Entry> rows = SettingsLayout.entries(1.25D);

        assertEquals(1, rows.size(), "one row: adding another is a decision, not a convenience");
        assertEquals(SettingsLayout.TEXT_KEY, rows.get(0).key());
        assertEquals("Text size", rows.get(0).label(),
                "the value is the slider's message, which follows the thumb without a rebuild");
    }

    @Test
    @DisplayName("no theme, follow, radius or motion row")
    void noOtherRows() {
        List<Entry> rows = SettingsLayout.entries(1.0D);
        assertTrue(rows.stream().noneMatch(row -> row.key().startsWith("theme:")),
                "the palette list lives in the tools panel, where an author edits it");
        assertTrue(rows.stream().noneMatch(row -> row.key().equals("follow")),
                "nothing to follow back from when there is no choice to drop");
        assertTrue(rows.stream().noneMatch(row -> row.key().equals("radius")),
                "the radius is a field on the theme, and the tools panel's stepper owns it");
        assertTrue(rows.stream().noneMatch(row -> row.key().equals("motion")),
                "motion is a switch in the tools panel, beside the other mode switches");
    }
}
