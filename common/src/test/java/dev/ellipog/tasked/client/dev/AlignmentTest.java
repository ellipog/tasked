package dev.ellipog.tasked.client.dev;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Straightening a node: the midpoint of its two nearest links.
 *
 * <p>The property that matters is "nearest", not "first two": a dependency list is authored order, and
 * the two links that bracket a node on the canvas are whichever two are closest to it. A helper that
 * walked the list would straighten a chain between two quests on the far side of the graph, which looks
 * like the node jumping for no reason.
 */
@DisplayName("Alignment")
class AlignmentTest {

    private static LineArt.Point at(int x, int y) {
        return new LineArt.Point(x, y);
    }

    @Test
    @DisplayName("the two nearest are chosen, whatever order they arrive in")
    void theTwoNearest() {
        LineArt.Point node = at(100, 100);

        // Deliberately listed far-first: a first-two implementation fails here.
        var middle = Alignment.midpointOfTwoNearest(node,
                List.of(at(500, 100), at(120, 100), at(80, 100)));

        assertTrue(middle.isPresent());
        assertEquals(at(100, 100), middle.get(), "between the 80 and the 120, either side of the node");
    }

    @Test
    @DisplayName("a chain's middle node lands exactly between its neighbours")
    void aChainStraightens() {
        var middle = Alignment.midpointOfTwoNearest(at(50, 90), List.of(at(10, 90), at(90, 90)));

        assertEquals(at(50, 90), middle.orElseThrow(), "already straight, so it does not move");
    }

    @Test
    @DisplayName("fewer than two links is nothing to do, not a guess")
    void fewerThanTwoIsEmpty() {
        assertTrue(Alignment.midpointOfTwoNearest(at(0, 0), List.of()).isEmpty());
        assertTrue(Alignment.midpointOfTwoNearest(at(0, 0), List.of(at(10, 10))).isEmpty());
    }

    @Test
    @DisplayName("a candidate in the same place as the node is not a link to centre on")
    void aCoincidentPointIsIgnored() {
        // Two quests stacked at one spot are a warned-about authoring mistake; centring on the stack the
        // node is part of would be a no-op dressed as a gesture.
        assertTrue(Alignment.midpointOfTwoNearest(at(10, 10),
                List.of(at(10, 10), at(10, 10))).isEmpty());
    }
}
