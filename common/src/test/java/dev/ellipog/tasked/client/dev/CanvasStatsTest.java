package dev.ellipog.tasked.client.dev;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the canvas currently holds, and the one number that says it is being rebuilt.
 *
 * <h2>Why these are asserted rather than looked at</h2>
 *
 * <p>They are read by an overlay that only draws while an operator has asked for it, so nothing else in the
 * suite would notice them being wrong — and a wrong reading here is worse than a missing one, because the whole
 * purpose of the baseline table is comparing readings taken at different sizes. A `nodes` figure that did not
 * move with the canvas would make two rows look comparable when they were not.
 *
 * <p>{@code rebuilds} is the one that is not a description: it is cumulative, and a per-second delta of it is
 * the only way to see that a drag is re-stamping the canvas every frame. The frame counters look much the same
 * whether the canvas was built once or sixty times.
 */
@DisplayName("the canvas statistics")
class CanvasStatsTest {

    @BeforeEach
    void reset() {
        CanvasStats.clear();
    }

    @Test
    @DisplayName("nothing is claimed before anything has been published")
    void nothingBeforeTheFirstPublish() {
        // Zero rather than absent, and that is the honest reading: a screen with no canvas has no nodes, and
        // the overlay must not print a number it made up.
        assertEquals(0, CanvasStats.nodes());
        assertEquals(0, CanvasStats.edges());
        assertEquals(0, CanvasStats.named());
        assertEquals(0, CanvasStats.visible());
        assertEquals(0, CanvasStats.rebuilds());
    }

    @Test
    @DisplayName("a publish is readable back, whole")
    void aPublishIsReadableBack() {
        CanvasStats.published(960, 1810, 420, 733);

        assertEquals(960, CanvasStats.nodes(), "the chapter's quest count");
        assertEquals(1810, CanvasStats.edges(), "the lines it built");
        assertEquals(420, CanvasStats.named(), "the label pass's own input");
        assertEquals(733, CanvasStats.visible(), "and what survived the cull");
    }

    @Test
    @DisplayName("each publish replaces the last, so a still canvas reports what it is drawing now")
    void eachPublishReplacesTheLast() {
        CanvasStats.published(70, 120, 30, 70);
        CanvasStats.published(960, 1810, 420, 733);

        assertEquals(960, CanvasStats.nodes(),
                "these describe the canvas's contents, so the newest rebuild is the answer");
        assertEquals(1810, CanvasStats.edges());
    }

    @Test
    @DisplayName("rebuilds counts every publish, because that is what says a drag is re-stamping")
    void rebuildsCountsEveryPublish() {
        // Cumulative and not a per-frame figure: the reading that matters is its delta over a second, and a
        // drag of sixty frames must show sixty here or the instrument cannot see the thing B19 is about.
        assertEquals(0, CanvasStats.rebuilds());
        for (int i = 0; i < 60; i++) {
            CanvasStats.published(70, 120, 30, 70);
        }

        assertEquals(60, CanvasStats.rebuilds(), "sixty rebuilds is what sixty drag frames look like");
    }

    @Test
    @DisplayName("clear forgets everything, including the rebuild count")
    void clearForgetsEverything() {
        // Leaving the world: the next one has its own canvas, and a rebuild count carried over would read as
        // a canvas that had been churning when nothing had happened yet.
        CanvasStats.published(70, 120, 30, 70);
        CanvasStats.clear();

        assertEquals(0, CanvasStats.nodes());
        assertEquals(0, CanvasStats.rebuilds(), "the count belongs to the world it was taken in");
    }
}
