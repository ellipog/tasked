package dev.ellipog.tenet.client.hud;

import com.google.gson.JsonParser;
import dev.ellipog.tenet.client.BookGeometry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The element table itself: its names, its ids and the language entries behind them.
 *
 * <h2>Why the labels are checked here and not by the sweep</h2>
 *
 * <p>{@code LangSweepTest} holds the translation keys and the code in step in both directions, and it cannot
 * see these: {@link HudElement#labelKey()} is a <b>prefix plus an id</b>, so the key the editor looks up at
 * runtime is not a literal any scan can read whole. Sweeping {@code tenet.hud.} would therefore report every
 * label as an orphan — the reverse direction of that test — and the forward direction, "a key the code names
 * exists", is blind to a concatenation. So a misspelt entry would draw {@code tenet.hud.pinned_quests} on the
 * editor's row and nothing anywhere would fail.
 *
 * <p>That is the gap this file fills, with the language file read rather than a copy of its keys written down:
 * a list of expected keys here would be a third description of the table and would drift with it.
 */
@DisplayName("the HUD element table")
class HudElementTest {

    /** The test working directory is the common module, so the resources are one level in. */
    private static final Path LANG =
            Path.of("src", "main", "resources", "assets", "tenet", "lang", "en_us.json");

    @Test
    @DisplayName("every element's derived label exists in en_us")
    void everyLabelExists() throws IOException {
        Set<String> defined = new TreeSet<>(
                JsonParser.parseString(Files.readString(LANG)).getAsJsonObject().keySet());

        Set<String> missing = new TreeSet<>();
        for (HudElement element : HudElement.values()) {
            if (!defined.contains(element.labelKey())) {
                missing.add(element.labelKey());
            }
        }
        assertTrue(missing.isEmpty(), () -> "these rows would read as their own key in the editor: " + missing);
    }

    @Test
    @DisplayName("no two elements share an id, because the file is keyed by it")
    void idsAreUnique() {
        Set<String> seen = new HashSet<>();
        for (HudElement element : HudElement.values()) {
            assertTrue(seen.add(element.id()), element + " shares its id with another element");
            assertEquals("tenet.hud." + element.id(), element.labelKey());
        }
    }

    @Test
    @DisplayName("either spelling names an element, and a key this build has not got names nothing")
    void namedReadsBothSpellings() {
        assertEquals(HudElement.PINNED_QUESTS, HudElement.named("pinned_quests"),
                "the id the file holds");
        assertEquals(HudElement.PINNED_QUESTS, HudElement.named("PINNED_QUESTS"),
                "and the constant's own name, which is what somebody reading the enum would guess");
        assertEquals(HudElement.NOTIFICATIONS, HudElement.named("notifications"));
        assertNull(HudElement.named("nothing_like_this"), "a key written by a newer build costs only itself");
        assertNull(HudElement.named(null));
    }

    @Test
    @DisplayName("the tone is the how, so the editor asks one question about a drawn thing")
    void theKindsSplitControlsFromDrawnElements() {
        assertEquals(HudElement.Kind.CONTROL, HudElement.INVENTORY_BUTTON.kind(),
                "the book's button is a widget on somebody else's screen and draws itself");
        for (HudElement element : new HudElement[] {
                HudElement.PINNED_QUESTS, HudElement.NOTIFICATIONS}) {
            assertEquals(HudElement.Kind.HUD, element.kind(), element + " is drawn by the HUD");
        }
    }

    @Test
    @DisplayName("the drawn elements ship switched on, and the pinned one is harmless until something is pinned")
    void theDefaultsAreOn() {
        assertTrue(HudElement.PINNED_QUESTS.defaultOn(),
                "on and drawing nothing is the state a player who has never pinned a quest is in");
        assertTrue(HudElement.NOTIFICATIONS.defaultOn(),
                "the HUD is where this round asked for the notices to go; the switch beside it is the"
                        + " way back to a vanilla toast");
        assertTrue(HudElement.INVENTORY_BUTTON.defaultOn());
    }

    @Test
    @DisplayName("the dim defaults ship see-through pins and unchanged notices")
    void theDimDefaultsArePinned() {
        assertEquals(0.5, HudElement.PINNED_QUESTS.defaultDim(),
                "half: the request is a background you can see through");
        assertEquals(1.0, HudElement.NOTIFICATIONS.defaultDim(),
                "full: their opaque panel is the look they already have, and changing it unasked would be "
                        + "a second visual change hiding inside the slider");
        assertEquals(1.0, HudElement.INVENTORY_BUTTON.defaultDim(),
                "answered but never read: a control draws itself");
    }

    @Test
    @DisplayName("a pinned stack's starting box is smaller than the tallest it can draw, and it ships middle-left")
    void theStartingBoxesAreSensible() {
        HudElement pinned = HudElement.PINNED_QUESTS;
        HudElement notices = HudElement.NOTIFICATIONS;

        assertTrue(pinned.width() >= PinnedPanelLayout.MIN_WIDTH
                        && pinned.width() <= PinnedPanelLayout.MAX_WIDTH,
                "the editor's starting box is inside the column's own bounds: " + pinned.width());
        assertEquals(HudElement.Anchor.TOP_LEFT, notices.anchor(),
                "everything that has always been top-left still is");
        assertEquals(HudElement.Anchor.MIDDLE_LEFT, pinned.anchor(),
                "the stack is the one element whose height moves under it");
        assertEquals(4, pinned.defaultX(), "against the left edge");
        assertEquals(0, pinned.defaultY(), "and a centred offset of nothing: no constant names a middle");
        assertFalse(HudLayout.boxAt(pinned, 320, 240, pinned.defaultX(), pinned.defaultY(),
                        pinned.width(), pinned.height())
                        .intersects(HudLayout.boxAt(notices, 320, 240, notices.defaultX(),
                                notices.defaultY(), notices.width(), notices.height())),
                "and the same at a window small enough to clamp both");
    }

    @Test
    @DisplayName("the language file is read from where this test thinks it is")
    void theLanguageFileIsThere() {
        // A path that stopped resolving would make the first test in this file vacuous rather than failing:
        // an empty set of defined keys and a set of missing ones are both "no orphan found".
        assertTrue(Files.isRegularFile(LANG), "no language file at " + LANG.toAbsolutePath());
    }
}
