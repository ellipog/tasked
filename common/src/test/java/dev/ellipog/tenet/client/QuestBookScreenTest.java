package dev.ellipog.tenet.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The effective edit gate: edit mode, the operator permission, and the player's-view preview.
 *
 * <p>A static truth table rather than a screen, because the answer is pure inputs — no player, no
 * widgets, no client — and a screen cannot be instantiated by a test. The one row that matters is
 * the preview's: everything on with the latch lit still answers false, so previewing pauses editing
 * without touching the mode underneath it.
 */
@DisplayName("the effective edit gate")
class QuestBookScreenTest {

    @Test
    @DisplayName("edit mode and the permission edit, and the preview pauses them")
    void previewPausesEditing() {
        assertTrue(QuestBookScreen.editEffective(true, true, false),
                "edit mode on, operator, no preview: editing");
        assertFalse(QuestBookScreen.editEffective(true, true, true),
                "the same with the preview lit: paused, not editing");
    }

    @Test
    @DisplayName("every other row answers false however the preview is set")
    void everythingElseIsFalse() {
        for (boolean preview : new boolean[] {false, true}) {
            assertFalse(QuestBookScreen.editEffective(false, false, preview),
                    "no mode, no permission: a reader either way");
            assertFalse(QuestBookScreen.editEffective(false, true, preview),
                    "no mode: an operator with edit mode off is already seeing the player's book");
            assertFalse(QuestBookScreen.editEffective(true, false, preview),
                    "no permission: edit mode without the level the server checks is a reader");
        }
    }
}
