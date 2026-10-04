package dev.ellipog.tasked.client;

import dev.ellipog.tasked.client.CanvasFocus.Direction;
import dev.ellipog.tasked.client.CanvasFocus.Node;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The canvas's keyboard navigation, without a canvas.
 *
 * <p>The rule is one sentence — the cheapest node ahead, straight beating diagonal — and the cases
 * below are the sentence's edges: the first press, a wall, a diagonal that must lose to a straight
 * line, and a focused node that stops being drawn.
 */
@DisplayName("keyboard focus over the canvas")
class CanvasFocusTest {

    private static final Node CENTRE = new Node("centre", 100, 100, 20);
    private static final Node LEFT = new Node("left", 40, 100, 20);
    private static final Node RIGHT = new Node("right", 160, 100, 20);
    private static final Node UP = new Node("up", 100, 40, 20);
    private static final Node DOWN = new Node("down", 100, 160, 20);

    /** Down-right of RIGHT, so a RIGHT step from CENTRE has a diagonal to refuse. */
    private static final Node DIAGONAL = new Node("diagonal", 200, 160, 20);

    private static final List<Node> ALL = List.of(CENTRE, LEFT, RIGHT, UP, DOWN, DIAGONAL);

    @Test
    @DisplayName("the first press focuses the first node, so it is never a no-op")
    void theFirstStepFocuses() {
        CanvasFocus focus = new CanvasFocus();
        assertEquals("centre", focus.step(ALL, Direction.RIGHT).orElseThrow());
    }

    @Test
    @DisplayName("a step takes the node ahead, and a straight line beats a diagonal")
    void straightBeatsDiagonal() {
        // Each step moves the focus, so each direction starts from the centre again.
        assertEquals("right", stepFrom("centre", Direction.RIGHT),
                "the diagonal is ahead too, and further off-axis: 60 straight beats 100 across 60");
        assertEquals("left", stepFrom("centre", Direction.LEFT));
        assertEquals("up", stepFrom("centre", Direction.UP));
        assertEquals("down", stepFrom("centre", Direction.DOWN));
    }

    private static String stepFrom(String from, Direction direction) {
        CanvasFocus focus = new CanvasFocus();
        focus.focus(from);
        return focus.step(ALL, direction).orElseThrow();
    }

    @Test
    @DisplayName("nothing ahead is a wall, not a wrap")
    void aWallStaysPut() {
        // The diagonal is the furthest right node; nothing is right of it.
        assertEquals("diagonal", stepFrom("diagonal", Direction.RIGHT),
                "wrapping across a canvas reads as a jump");
    }

    @Test
    @DisplayName("a focused node that is no longer drawn hands the focus to the first drawn one")
    void aStaleFocusIsReplaced() {
        CanvasFocus focus = new CanvasFocus();
        focus.focus("gone");

        assertEquals("centre", focus.step(List.of(CENTRE, RIGHT), Direction.DOWN).orElseThrow());
    }

    @Test
    @DisplayName("an empty canvas clears the focus rather than holding an id nobody draws")
    void anEmptyCanvasClears() {
        CanvasFocus focus = new CanvasFocus();
        focus.focus("centre");

        assertTrue(focus.step(List.of(), Direction.RIGHT).isEmpty());
        assertTrue(focus.focused().isEmpty());
    }

    @Test
    @DisplayName("focus and clear are the click's and Escape's halves")
    void focusAndClear() {
        CanvasFocus focus = new CanvasFocus();
        assertTrue(focus.focused().isEmpty());

        focus.focus("right");
        assertEquals("right", focus.focused().orElseThrow());

        focus.clear();
        assertTrue(focus.focused().isEmpty());
    }
}
