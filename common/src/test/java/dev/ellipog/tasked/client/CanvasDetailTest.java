package dev.ellipog.tasked.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the canvas draws at each zoom.
 *
 * <h2>The assertion that matters most is about a zoom nobody plays at</h2>
 *
 * <p>The preview harness draws the book at 1.0×, and every golden still is compared byte for byte against
 * what it drew before. So the tier in force at the zooms this project has <i>pictures</i> of is the one
 * that may not move: if a threshold ever creeps up to where the previews are taken, the acceptance gate
 * stops being a comparison and becomes a re-baselining exercise — and re-baselining is how a visual
 * regression gets waved through. That case is first for that reason.
 *
 * <p>The rest is the shape of the ladder: each tier gives up strictly more than the one above it, and a
 * tier that kept an expensive thing it claims to drop would show up here rather than in a frame time.
 */
@DisplayName("the canvas's detail tiers")
class CanvasDetailTest {

    @Test
    @DisplayName("the zoom the pictures are taken at is the full one")
    void thePreviewZoomIsFull() {
        assertEquals(CanvasDetail.FULL, CanvasDetail.of(1.0F),
                "the preview harness draws at 1.0x, and its stills are compared byte for byte");
        assertEquals(CanvasDetail.FULL, CanvasDetail.of(0.75F), "and so is every zoom above the threshold");
    }

    @Test
    @DisplayName("each threshold is where it says, and the two are not the same number")
    void theThresholdsAreWhereTheySay() {
        assertEquals(CanvasDetail.CARTOON, CanvasDetail.of(CanvasDetail.CARTOON_BELOW - 0.01F));
        assertEquals(CanvasDetail.FULL, CanvasDetail.of(CanvasDetail.CARTOON_BELOW),
                "the threshold itself is still full detail: the bound is 'below this', not 'at this'");
        assertEquals(CanvasDetail.BLOCKS, CanvasDetail.of(CanvasDetail.BLOCKS_BELOW - 0.01F));
        assertEquals(CanvasDetail.CARTOON, CanvasDetail.of(CanvasDetail.BLOCKS_BELOW));

        assertTrue(CanvasDetail.BLOCKS_BELOW < CanvasDetail.CARTOON_BELOW,
                "one threshold inside the other, or the middle tier could never be reached");
        assertTrue(CanvasDetail.CARTOON_BELOW < 0.6F,
                "and both strictly below the zoom a preview is taken at");
    }

    @Test
    @DisplayName("the canvas's own minimum zoom still lands on a tier rather than on nothing")
    void theOutermostZoomIsBlocks() {
        // The book's own MIN_ZOOM. The tier is a property of the scale, not of a viewport, so this is
        // the one number that has to be checked against the ladder rather than the other way round.
        assertEquals(CanvasDetail.BLOCKS, CanvasDetail.of(0.2F));
    }

    @Test
    @DisplayName("each tier gives up exactly what it claims, and a lower one never gives up less")
    void theLadderIsMonotonic() {
        assertTrue(CanvasDetail.FULL.rings() && CanvasDetail.FULL.labels()
                && CanvasDetail.FULL.badges(), "full detail draws everything the tier decides");

        assertFalse(CanvasDetail.CARTOON.rings(),
                "a one-pixel ring round a small node is a thicker border rather than a cue");
        assertTrue(CanvasDetail.CARTOON.labels(), "a title is still worth reading at half scale");
        assertTrue(CanvasDetail.CARTOON.badges());

        assertFalse(CanvasDetail.BLOCKS.rings());
        assertFalse(CanvasDetail.BLOCKS.labels(), "and at the outermost tier, no text at all");
        assertFalse(CanvasDetail.BLOCKS.badges());

        for (CanvasDetail lower : CanvasDetail.values()) {
            for (CanvasDetail higher : CanvasDetail.values()) {
                if (lower.ordinal() > higher.ordinal()) {
                    continue;
                }
                // Anything the lower tier draws, the higher one draws too.
                assertTrue(!higher.rings() || lower.rings());
                assertTrue(!higher.labels() || lower.labels());
                assertTrue(!higher.badges() || lower.badges());
            }
        }
    }

    @Test
    @DisplayName("the thresholds in force are the client's, and the pure rule is what a test can hold")
    void theThresholdsComeFromTheClient() {
        // The rule and the numbers are separated on purpose: `of(scale)` reads the client's `canvas.json`,
        // and the three-argument form is the rule with nothing else in it, which is what the cases above
        // assert against. That is what keeps this test independent of a file on the machine running it.
        //
        // Whether an *icon* is drawn is deliberately not here at all: it is a property of the box a node's
        // item would fill, measured per node by `QuestNodeArt` against `CanvasSettings.iconMinBox()`. That is
        // pinned behaviourally in `NodeArtOrderTest`, where the drawing can be watched — the tier cannot
        // refuse an icon any more, and a test here could only assert that by reflection, which would be a
        // test of the source text rather than of the drawing.
        assertEquals(CanvasDetail.FULL, CanvasDetail.of(1.0F, 0.5F, 0.3F));
        assertEquals(CanvasDetail.CARTOON, CanvasDetail.of(0.4F, 0.5F, 0.3F));
        assertEquals(CanvasDetail.BLOCKS, CanvasDetail.of(0.2F, 0.5F, 0.3F));
        assertEquals(CanvasDetail.CARTOON, CanvasDetail.of(0.4F, 0.6F, 0.3F),
                "the thresholds are arguments, so a client that raises one moves the tier: at 0.4 the rings "
                        + "stop at 0.6 rather than at the default 0.5");
    }
}
