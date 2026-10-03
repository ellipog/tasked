package dev.ellipog.tasked.client.dev;

import dev.ellipog.armature.client.ArmatureTheme;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an inline editor is allowed to look like: the ink rule, and the box that must not be there.
 *
 * <h2>The report this is the answer to</h2>
 *
 * <p>From play: *"make the description box and all text fields in general look exactly the same when I
 * edit and when I click out of it"*. The editor used to draw the value it replaced in a different ink
 * ({@code title()} while reading is {@code body()}), over a recessed fill with a border, at a different
 * line pitch -- so clicking a field moved, recoloured and re-spaced the text, and clicking out moved it
 * back. Every part of that is a choice, and these are the choices: the editor is drawn in the ink the
 * reader draws the same thing in, and the widget paints no box at all. What is left when it is focused
 * is the caret and the selection, which is what an editor needs and a reader does not have.
 *
 * <h2>Why the rule is asserted and not just written at the call site</h2>
 *
 * <p>Because it is exactly the kind of colour that gets "improved" later -- a white border to show which
 * field is focused is the obvious thing to add, and it is the thing that was just removed. The reader's
 * own inks are named beside these assertions so a change to one is a change to both.
 */
@DisplayName("what the inline editor draws with")
class InlineEditTest {

    @Test
    @DisplayName("a field is drawn in the ink the reader draws the same value in")
    void theInkIsTheReadersInk() {
        // The reader: `drawOverlay` draws the title in `title()` and the chapter/subtitle line in
        // `faint()`; every other editable value -- prose and the row values -- is `body()`.
        assertEquals(ArmatureTheme.title(), InlineEdit.ink("title"));
        assertEquals(ArmatureTheme.faint(), InlineEdit.ink("subtitle"));
        assertEquals(ArmatureTheme.body(), InlineEdit.ink("description"));
        assertEquals(ArmatureTheme.body(), InlineEdit.ink("tasks.0.count"));
        assertEquals(ArmatureTheme.body(), InlineEdit.ink("rewards.1.amount"));
        assertEquals(ArmatureTheme.body(), InlineEdit.ink("dep:add"));
        assertEquals(ArmatureTheme.body(), InlineEdit.ink("anything this build has not heard of"));
    }

    @Test
    @DisplayName("the box is nothing at all: a fully transparent fill, so the surface behind shows")
    void thereIsNoBox() {
        assertEquals(0, InlineEdit.NO_BOX, "not a black box with no alpha -- nothing at all");
        assertEquals(0, InlineEdit.NO_BOX >>> 24, "no alpha either");
    }

    @Test
    @DisplayName("a piece is drawn by its field or by the card, never by both")
    void theDrawingChangesHandsRatherThanDoubling() {
        // The rule every drawing site calls: the edit path draws a value only when no field is standing
        // in for it. Both drawings at once is the overprinted description from play -- the field is
        // transparent, so nothing hides the second copy.
        assertTrue(InlineEdit.replaces("description", "description"));
        assertTrue(InlineEdit.replaces("tasks.0.count", "tasks.0.count"));
        assertTrue(InlineEdit.replaces(InlineEdit.DEPENDENCY_ADD, InlineEdit.DEPENDENCY_ADD));

        assertFalse(InlineEdit.replaces("description", "title"), "a different piece");
        assertFalse(InlineEdit.replaces("description", null), "nothing is being edited");
        assertFalse(InlineEdit.replaces(null, "description"), "a piece with no path is nobody's");
    }

    @Test
    @DisplayName("the header's pieces are the header's, and everything else is the body's")
    void theRegionFollowsTheReader() {
        // The reader draws the title and the subtitle line in the card's fixed header and everything
        // else in the scrolling body, and the editor keeps a field alive only inside the region its
        // target lives in. Asking the body about a title is what made those two unopenable: their boxes
        // sit above the body's top edge, so the field was stood down on the frame it opened.
        assertTrue(InlineEdit.inHeader("title"), "the title is drawn in the header");
        assertTrue(InlineEdit.inHeader("subtitle"), "so is the chapter/subtitle line");

        assertFalse(InlineEdit.inHeader("description"), "prose scrolls with the body");
        assertFalse(InlineEdit.inHeader("tasks.0.count"), "so do the row parts");
        assertFalse(InlineEdit.inHeader("dep:add"));
        assertFalse(InlineEdit.inHeader(null), "and a piece with no path is nobody's region");
    }
}
