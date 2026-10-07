package dev.ellipog.tenet.client;

import dev.ellipog.tenet.client.LabelOverlap.Box;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The label collision test: the same answer as the straightforward scan, for far less work.
 *
 * <h2>Why the test is a sweep against the rule it replaces</h2>
 *
 * <p>Because this is arithmetic with three ways to be subtly wrong — a window bound off by one, a box
 * that starts above the band and is tall enough to reach into it, an owner that is skipped where it
 * should be tested — and every one of them shows up as a label that is drawn over a node, or a label
 * that vanishes for no reason. Neither is visible in a diff and both are annoying rather than obvious on
 * screen.
 *
 * <p>So the oracle is written out here as the loop this replaces, and the two are compared over
 * thousands of generated canvases: overlapping boxes, tall boxes among short ones, labels above, below,
 * inside and beside them, and every node in turn as the owner. A case that disagrees is a case worth
 * looking at, which is the only useful kind of assertion for a rule like this.
 */
@DisplayName("the label collision test")
class LabelOverlapTest {

    /** The straightforward scan, kept here as the thing the new rule has to agree with. */
    private static boolean byHand(List<Box> boxes, int owner, int textX, int textY, int width) {
        for (int i = 0; i < boxes.size(); i++) {
            if (i == owner) {
                continue;
            }
            Box box = boxes.get(i);
            if (textX < box.x() + box.size() && textX + width > box.x()
                    && textY < box.y() + box.size() && textY + 9 > box.y()) {
                return true;
            }
        }
        return false;
    }

    private static void sameAsByHand(List<Box> boxes, int owner, int textX, int textY, int width) {
        assertEquals(byHand(boxes, owner, textX, textY, width),
                new LabelOverlap(boxes).over(owner, textX, textY, width),
                "boxes " + boxes + ", owner " + owner + ", label at " + textX + "," + textY
                        + " width " + width);
    }

    @Test
    @DisplayName("a label over a neighbour is a collision, and one in the gap is not")
    void theObviousCases() {
        // Two twenty-pixel nodes on one row, eighty pixels apart.
        List<Box> boxes = List.of(new Box(0, 0, 20), new Box(80, 0, 20));

        assertTrue(new LabelOverlap(boxes).over(0, 80, 15, 18),
                "drawn across the second node's row, over its own pixels");
        assertFalse(new LabelOverlap(boxes).over(0, 80, 22, 18),
                "the nodes end at row 19, so a label starting at row 22 is clear of both");
        assertFalse(new LabelOverlap(boxes).over(0, 30, 15, 18),
                "20 pixels wide at x=30 ends at 50, and the second node starts at 80");
    }

    @Test
    @DisplayName("a label is never tested against its own node")
    void theOwnerIsSkipped() {
        List<Box> boxes = List.of(new Box(100, 100, 20));

        assertFalse(new LabelOverlap(boxes).over(0, 100, 100, 20),
                "the owner's own box overlaps the label by construction, and is skipped");
    }

    @Test
    @DisplayName("a box taller than the label's band is found even though it starts above it")
    void aTallBoxIsFoundAboveTheBand() {
        // The case the window's lower bound exists for: this box's top edge is 50 pixels above the
        // label, so a window built from the label's own rows would step straight over it.
        List<Box> boxes = List.of(new Box(0, 0, 200), new Box(500, 500, 10));

        assertTrue(new LabelOverlap(boxes).over(1, 10, 150, 20),
                "the tall box reaches down into the label's row");
    }

    @Test
    @DisplayName("a box that ends exactly at the label's top edge does not collide")
    void theEdgesAreHalfOpen() {
        List<Box> boxes = List.of(new Box(0, 0, 40));

        assertFalse(new LabelOverlap(boxes).over(1, 0, 40, 20),
                "the box covers rows 0..39 and the label starts at row 40");
        assertTrue(new LabelOverlap(boxes).over(1, 0, 39, 20), "and one row up is an overlap");
    }

    @Test
    @DisplayName("every case in a swept canvas agrees with the scan it replaces")
    void agreesWithTheScan() {
        Random random = new Random(20240611L);
        for (int round = 0; round < 200; round++) {
            int count = 1 + random.nextInt(12);
            List<Box> boxes = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                // Sizes from tiny to taller than the whole band, positions clustered so that collisions
                // are common rather than a rarity the sweep would never reach.
                boxes.add(new Box(random.nextInt(120) - 20, random.nextInt(120) - 20,
                        1 + random.nextInt(60)));
            }
            for (int owner = 0; owner < count; owner++) {
                for (int step = 0; step < 8; step++) {
                    sameAsByHand(boxes, owner, random.nextInt(140) - 20, random.nextInt(140) - 20,
                            1 + random.nextInt(60));
                }
            }
        }
    }

    @Test
    @DisplayName("no boxes at all is not a collision, and neither is a label in an empty row")
    void nothingToCollideWith() {
        assertFalse(new LabelOverlap(List.of()).over(0, 5, 5, 20));
        assertFalse(new LabelOverlap(List.of(new Box(0, 0, 10))).over(0, 0, 500, 20),
                "a label far below every box");
    }

    @Test
    @DisplayName("a thousand boxes, at negative coordinates, still answer as the scan does")
    void aBigCanvasSortsCorrectly() {
        // The sort this class uses packs a row and an index into one long so it can sort primitives, and
        // the packing subtracts the lowest row — which is why negative coordinates are the case worth
        // pinning: a chapter panned up or left has boxes above and left of the origin, and a packing that
        // assumed non-negative rows would order them wrongly and start dropping labels that do collide.
        // A thousand is also the size that made the previous insertion sort O(n^2) and worth replacing.
        List<Box> boxes = new ArrayList<>(1000);
        Random random = new Random(20261006L);
        for (int i = 0; i < 1000; i++) {
            boxes.add(new Box(random.nextInt(900) - 450, random.nextInt(900) - 450, 8 + random.nextInt(40)));
        }
        LabelOverlap overlap = new LabelOverlap(boxes);

        for (int owner = 0; owner < 1000; owner += 97) {
            for (int step = 0; step < 6; step++) {
                int textX = random.nextInt(900) - 450;
                int textY = random.nextInt(900) - 450;
                int width = 1 + random.nextInt(120);
                assertEquals(byHand(boxes, owner, textX, textY, width),
                        overlap.over(owner, textX, textY, width),
                        "owner " + owner + ", label at " + textX + "," + textY + " width " + width);
            }
        }
    }
}
