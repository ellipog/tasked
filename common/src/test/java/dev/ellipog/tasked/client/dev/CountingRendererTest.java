package dev.ellipog.tasked.client.dev;

import dev.ellipog.tasked.client.render.RecordingRenderer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The submission count: the one number that says what a frame really costs on the CPU side.
 *
 * <h2>Why this arithmetic is worth its own test</h2>
 *
 * <p>Because it is the instrument the batching work is judged by, and it has three ways to be wrong that
 * all look plausible: not counting the first drawing call of a frame (a frame that draws its book in one
 * batch would read zero), counting every call rather than every change of kind (which would say batching
 * changes nothing), and missing the boundaries that are not drawing calls at all — a clip, a flush, the
 * blur — each of which ends a batch below the seam.
 *
 * <p>The delegate is the test renderer, so this asserts the counting and nothing about pixels.
 */
@DisplayName("the submission count")
class CountingRendererTest {

    private static CountingRenderer counting() {
        return new CountingRenderer(RecordingRenderer.create());
    }

    @Test
    @DisplayName("the first thing drawn is a submission, and further fills continue it")
    void theFirstCallIsASubmission() {
        CountingRenderer r = counting();
        assertEquals(0, r.submissions(), "nothing drawn yet");

        r.fill(0, 0, 1, 1, 0xFF000000);
        assertEquals(1, r.submissions(), "the first fill of a frame is one submission");

        r.fill(0, 0, 1, 1, 0xFF000000);
        r.fill(0, 0, 1, 1, 0xFF000000);
        assertEquals(1, r.submissions(),
                "and runs of the same kind are the same submission, which is the whole point of a batch");
    }

    @Test
    @DisplayName("a change of kind is a new submission, because the render types differ")
    void aChangeOfKindIsASubmission() {
        CountingRenderer r = counting();
        r.fill(0, 0, 1, 1, 0xFF000000);
        r.text("a", 0, 0, 0xFFFFFFFF);
        assertEquals(2, r.submissions(), "text is a different render type from a fill");

        r.fill(0, 0, 1, 1, 0xFF000000);
        assertEquals(3, r.submissions(), "and back again is a third");
    }

    @Test
    @DisplayName("measuring text is not drawing it")
    void measuringIsNotDrawing() {
        CountingRenderer r = counting();
        r.textWidth("a label");
        r.styledWidth("a label", true, false, 1F);
        assertEquals(0, r.submissions(), "a width is a font call, not a submission");
    }

    @Test
    @DisplayName("a clip, a flush and a blur all end the submission that was open")
    void boundariesThatAreNotDrawingCalls() {
        CountingRenderer r = counting();
        r.fill(0, 0, 1, 1, 0xFF000000);
        r.flush();
        assertEquals(2, r.submissions(), "the fill, and the flush that ended it");

        r.fill(0, 0, 1, 1, 0xFF000000);
        assertEquals(3, r.submissions(), "and the fill after a flush starts a new submission");

        r.clip(0, 0, 10, 10).close();
        r.fill(0, 0, 1, 1, 0xFF000000);
        assertEquals(5, r.submissions(),
                "the scissor change is a boundary whether the context is managed or not, and the drawing "
                        + "after it is a submission");
    }

    @Test
    @DisplayName("a boundary with nothing open is not a submission of its own")
    void aBoundaryWithNothingPending() {
        CountingRenderer r = counting();
        r.flush();
        r.clip(0, 0, 1, 1).close();
        assertEquals(0, r.submissions(),
                "a flush of an empty batch and a clip before anything is drawn cost nothing to submit — "
                        + "which is what keeps the number from drifting upwards on an idle frame");
    }
}
