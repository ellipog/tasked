package dev.ellipog.tenet.client.dev;

import dev.ellipog.armature.client.ui.kit.Easing;

/**
 * The arithmetic behind "locate on canvas": where the camera has to sit to put a node in the middle,
 * how it gets there, and how bright the outline flash is at a moment.
 *
 * <h2>Why this is a class and not the screen's own sums</h2>
 *
 * <p>The same reason {@link MenuPlacement} is one: the screen cannot be instantiated by a test, and
 * everything worth being wrong about here is arithmetic — a target offset a pixel off is a node that
 * arrives slightly off-centre, and a flash envelope with the wrong ends is either a node that never
 * lights up or one that never stops. The offset formula is pinned against {@code Viewport}'s own
 * transform in the test, so "the camera arrives at the node" is an assertion rather than a look.
 */
public final class CanvasReveal {

    /** How long the camera's glide takes. Long enough to read as movement, short enough not to wait. */
    public static final long GLIDE_MILLIS = 260;

    /** How long the outline flash lasts. */
    public static final long FLASH_MILLIS = 900;

    private CanvasReveal() {
    }

    /**
     * The view offset that puts a content-space point at the middle of the view, at this scale.
     *
     * <p>The same relation {@code Viewport.centreOn} uses, written for one point rather than a box:
     * {@code offset = view / 2 - content * scale}. Negative results are ordinary — content left of the
     * origin is the normal case on this canvas.
     */
    public static int offsetX(int viewWidth, float centreContentX, float scale) {
        return Math.round(viewWidth / 2F - centreContentX * scale);
    }

    /** The same for the vertical axis. */
    public static int offsetY(int viewHeight, float centreContentY, float scale) {
        return Math.round(viewHeight / 2F - centreContentY * scale);
    }

    /**
     * One axis of the glide, {@code elapsed} milliseconds in: eased out, and exact at both ends.
     *
     * <p>Exact at the end matters more than the easing: a glide that lands a pixel short leaves the
     * node visibly off-centre, and the whole point of the gesture is "there it is".
     */
    public static int glide(int from, int to, long elapsed, long duration) {
        if (duration <= 0) {
            return to;
        }
        float t = Math.max(0F, Math.min(1F, (float) elapsed / duration));
        return Math.round(Easing.QUAD_OUT.between(from, to, t));
    }

    /**
     * The flash's strength at {@code elapsed} milliseconds: two pulses that fade to nothing.
     *
     * <p>Zero before it starts and zero at the end — so the drawing can ask every frame and simply
     * draw nothing once it is over, without a second "is the flash still running" state to keep in
     * step. The double pulse is what makes it read as a flash rather than as a slow brighten, and the
     * decaying envelope is what keeps it from becoming a strobe on a node the player is reading.
     */
    public static float flash(long elapsed, long duration) {
        if (elapsed <= 0 || elapsed >= duration || duration <= 0) {
            return 0F;
        }
        float t = (float) elapsed / duration;
        float pulse = Math.abs((float) Math.sin(t * Math.PI * 2));
        return (1F - t) * (0.4F + 0.6F * pulse);
    }
}
