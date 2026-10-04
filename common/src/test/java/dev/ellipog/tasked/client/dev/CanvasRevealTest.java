package dev.ellipog.tasked.client.dev;

import dev.ellipog.armature.client.ui.kit.Viewport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The locate-on-canvas arithmetic: where the camera must sit, how it gets there, and the flash.
 *
 * <h2>Why this is asserted rather than looked at</h2>
 *
 * <p>The offset formula is the same relation {@code Viewport} uses to draw, so it can be pinned
 * against a real viewport instead of a comment: after applying the target, the content point must be on
 * the view's centre line, at every scale and with negative coordinates. A version that was a pixel off
 * would look almost right — which is exactly the kind of wrong a test is for.
 */
@DisplayName("CanvasReveal")
class CanvasRevealTest {

    private static final int VIEW_LEFT = 40;
    private static final int VIEW_TOP = 24;
    private static final int VIEW_WIDTH = 400;
    private static final int VIEW_HEIGHT = 300;

    private static Viewport view() {
        return Viewport.of(0.35F, 2.2F).bounds(VIEW_LEFT, VIEW_TOP, VIEW_WIDTH, VIEW_HEIGHT);
    }

    @Test
    @DisplayName("the target offset puts the content point on the view's centre line, at any scale")
    void theTargetOffsetCentresThePoint() {
        for (float scale : new float[] { 0.35F, 1F, 2.2F }) {
            Viewport view = view();
            view.setScale(scale);
            float contentX = 1234.5F;
            float contentY = -678.25F;

            view.setOffset(CanvasReveal.offsetX(view.viewWidth(), contentX, scale),
                    CanvasReveal.offsetY(view.viewHeight(), contentY, scale));

            assertEquals(VIEW_LEFT + VIEW_WIDTH / 2.0, view.screenX(contentX), 1.0,
                    "the content point lands on the view's centre line at scale " + scale);
            assertEquals(VIEW_TOP + VIEW_HEIGHT / 2.0, view.screenY(contentY), 1.0,
                    "and on the horizontal one, at scale " + scale);
        }
    }

    @Test
    @DisplayName("the glide is exact at both ends, monotone in between, and clamped outside them")
    void theGlideIsExactAtTheEnds() {
        assertEquals(120, CanvasReveal.glide(120, -300, 0, 260), "the start is where the view is");
        assertEquals(-300, CanvasReveal.glide(120, -300, 260, 260), "and the end is exact");
        assertEquals(-300, CanvasReveal.glide(120, -300, 5000, 260), "past the end is the end");
        assertEquals(120, CanvasReveal.glide(120, -300, -5, 260), "before the start is the start");

        int previous = 120;
        boolean moved = false;
        for (long at = 0; at <= 260; at += 20) {
            int value = CanvasReveal.glide(120, -300, at, 260);
            assertTrue(value <= previous, "a descending glide never turns back: " + value + " at " + at);
            previous = value;
            moved |= value != 120;
        }
        assertTrue(moved, "the glide has to actually move");
    }

    @Test
    @DisplayName("a zero duration lands rather than dividing by it")
    void aZeroDurationLands() {
        assertEquals(7, CanvasReveal.glide(0, 7, 0, 0));
    }

    @Test
    @DisplayName("the flash is nothing before it starts and after it ends, and two pulses in between")
    void theFlashStartsAndEndsAtNothing() {
        assertEquals(0F, CanvasReveal.flash(0, CanvasReveal.FLASH_MILLIS));
        assertEquals(0F, CanvasReveal.flash(-1, CanvasReveal.FLASH_MILLIS));
        assertEquals(0F, CanvasReveal.flash(CanvasReveal.FLASH_MILLIS, CanvasReveal.FLASH_MILLIS));
        assertEquals(0F, CanvasReveal.flash(5000, CanvasReveal.FLASH_MILLIS));

        float early = CanvasReveal.flash(90, 900);
        float late = CanvasReveal.flash(810, 900);
        assertTrue(early > 0F && early <= 1F, "the first pulse is visible: " + early);
        assertTrue(late > 0F && late <= 1F, "and so is the last: " + late);
        assertTrue(early > late, "the envelope decays: " + early + " vs " + late);

        // Two peaks with a valley between them: what makes it read as a flash rather than as one slow
        // brighten that happens to end.
        float peakOne = CanvasReveal.flash(225, 900);
        float valley = CanvasReveal.flash(450, 900);
        float peakTwo = CanvasReveal.flash(675, 900);
        assertTrue(peakOne > valley && peakTwo > valley,
                "two pulses, not one: " + peakOne + ", " + valley + ", " + peakTwo);
    }
}
