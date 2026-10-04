package dev.ellipog.tasked.client.dev;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bar's quantisation: nothing at zero, a sliver for the first quest, a full strip at 100%.
 *
 * <h2>Why a recorder</h2>
 *
 * <p>Three of this class's four interesting states are numbers, not pictures: zero must emit
 * nothing, a tiny fraction must not round away, and 100% must not first paint a track under itself.
 * The recorder keeps every fill, so each is an assertion.
 */
@DisplayName("ChapterBar")
class ChapterBarTest {

    private static final int FILL = 0xFF86CE8A;
    private static final int TRACK = 0xFF191920;

    @Test
    @DisplayName("nothing done draws nothing at all -- no track, no groove")
    void zeroDrawsNothing() {
        RecordingRenderer r = new RecordingRenderer();
        ChapterBar.draw(r, 10, 20, 100, ChapterBar.HEIGHT, 0F, FILL, TRACK);
        assertEquals(0, r.fills().size(), "an untouched chapter carries no mark");
    }

    @Test
    @DisplayName("half is half the width in fill over a full-width track")
    void halfFillsHalf() {
        RecordingRenderer r = new RecordingRenderer();
        ChapterBar.draw(r, 10, 20, 100, ChapterBar.HEIGHT, 0.5F, FILL, TRACK);

        assertEquals(2, r.fills().size(), "a track and a fill");
        RecordingRenderer.Fill track = r.fills().get(0);
        RecordingRenderer.Fill fill = r.fills().get(1);
        assertEquals(TRACK, track.argb());
        assertEquals(10, track.left());
        assertEquals(110, track.right(), "the track spans the whole bar");
        assertEquals(20, track.top());
        assertEquals(21, track.bottom(), "one pixel tall -- a hairline");
        assertEquals(FILL, fill.argb());
        assertEquals(10, fill.left());
        assertEquals(60, fill.right(), "round(100 * 0.5) with the bar's own origin");
    }

    @Test
    @DisplayName("a fraction too small to round still shows one pixel")
    void oneQuestOfManyStillShows() {
        RecordingRenderer r = new RecordingRenderer();
        ChapterBar.draw(r, 0, 0, 100, ChapterBar.HEIGHT, 1F / 500F, FILL, TRACK);
        assertEquals(1, r.fills().get(1).right(), "one pixel, or the first completion is invisible");
    }

    @Test
    @DisplayName("100% is one fill, the full width, and no track underneath it")
    void fullIsOneFill() {
        RecordingRenderer r = new RecordingRenderer();
        ChapterBar.draw(r, 0, 0, 100, ChapterBar.HEIGHT, 1F, FILL, TRACK);

        assertEquals(1, r.fills().size(), "no track call when it could not show");
        RecordingRenderer.Fill fill = r.fills().get(0);
        assertEquals(FILL, fill.argb());
        assertEquals(0, fill.left());
        assertEquals(100, fill.right());
    }

    @Test
    @DisplayName("a degenerate rectangle draws nothing rather than throwing")
    void degenerateIsSafe() {
        RecordingRenderer r = new RecordingRenderer();
        ChapterBar.draw(r, 0, 0, 0, ChapterBar.HEIGHT, 1F, FILL, TRACK);
        ChapterBar.draw(r, 0, 0, 100, 0, 1F, FILL, TRACK);
        assertEquals(0, r.fills().size());
    }

    @Test
    @DisplayName("every fill stays inside the rectangle it was given")
    void staysInsideItsBox() {
        RecordingRenderer r = new RecordingRenderer();
        ChapterBar.draw(r, 10, 20, 100, ChapterBar.HEIGHT, 0.37F, FILL, TRACK);
        for (RecordingRenderer.Fill fill : r.fills()) {
            assertTrue(fill.left() >= 10 && fill.right() <= 110
                            && fill.top() >= 20 && fill.bottom() <= 21,
                    "outside the bar's box: " + fill);
        }
    }
}
