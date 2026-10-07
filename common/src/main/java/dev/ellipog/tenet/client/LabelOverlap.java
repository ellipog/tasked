package dev.ellipog.tenet.client;

import java.util.List;

/**
 * Which node boxes a label would be drawn over — the collision test the label pass makes, in one place.
 *
 * <h2>The shape of the cost this removes</h2>
 *
 * <p>A canvas names the nodes whose author asked for a title, and each of those labels has to be checked
 * against <b>every</b> node on the canvas — not only the named ones, because a label drawn across an
 * unnamed node's icon is just as unreadable. The test used to recompute each candidate's size and screen
 * position inside the pair loop, and each of those is a draft lookup and a viewport mapping: with a
 * hundred names among a thousand nodes that is a hundred thousand position computations per frame, for
 * boxes that do not move while the frame is being drawn.
 *
 * <p>So the boxes are computed once — by the caller, which is what knows how to map a node to the screen
 * — and this class answers the question from them. Two things follow, and only the first is obvious:
 * the per-pair work is gone, and the answer can be found without looking at every box, because a label
 * nine pixels tall can only collide with nodes whose boxes reach into those nine pixels.
 *
 * <h2>Why the window is what it is</h2>
 *
 * <p>A candidate collides when its box overlaps the label's, so it must start at or above the label's
 * bottom and end below the label's top. Writing {@code y} for the box's top edge and {@code s} for its
 * size, that is {@code y <= textY + 8} and {@code y + s > textY}. The second is not a bound on {@code y}
 * alone — a tall enough box can start well above the band and still reach into it — but it <i>is</i> a
 * bound once {@code s} is bounded, which is what {@link #tallest} is for: no box is taller than the
 * tallest on this canvas, so {@code y > textY - tallest} is implied. The search then looks at the boxes
 * sorted by top edge whose tops fall in that window, which is the nodes that could reach the label plus
 * the ones that end just above it — and nothing else.
 *
 * <p>Deliberately game-free and free of the screen: the rule is arithmetic over rectangles, so it can be
 * swept against the straightforward version in a test rather than judged by looking at a crowded canvas.
 */
public final class LabelOverlap {

    /** One node as the label pass sees it: its box in screen pixels. */
    public record Box(int x, int y, int size) {
    }

    /** How tall a label is drawn, which is also the band a collision test asks about. */
    private static final int LABEL_HEIGHT = 9;

    private final List<Box> boxes;

    /** The boxes' indices, ordered by their top edge — the index the window search walks. */
    private final int[] byTop;

    /** The tallest box on this canvas, which is what makes the window's lower bound sound. */
    private final int tallest;

    public LabelOverlap(List<Box> boxes) {
        this.boxes = List.copyOf(boxes);
        this.byTop = new int[this.boxes.size()];
        int tall = 0;
        for (int i = 0; i < this.boxes.size(); i++) {
            byTop[i] = i;
            tall = Math.max(tall, this.boxes.get(i).size());
        }
        this.tallest = tall;
        // Sorted by top edge, with `byTop` holding indices rather than boxes so the caller's own order —
        // which is what identifies an owner — is untouched.
        //
        // A packing sort rather than an insertion sort, and a chapter with nine hundred nodes in it is the
        // reason: insertion sort is O(n^2), which at that size is a few hundred thousand comparisons *per
        // frame* for a list whose only job is to be binary-searched. The key packs the row and the index
        // into one long, so the sort is over primitives — no boxing, no comparator, no object per box — and
        // the index in the low half keeps boxes on the same row in the caller's own order, which is what a
        // stable sort would have done.
        int lowest = byTop.length == 0 ? 0 : this.boxes.get(0).y();
        for (int i = 1; i < byTop.length; i++) {
            lowest = Math.min(lowest, this.boxes.get(i).y());
        }
        long[] packed = new long[byTop.length];
        for (int i = 0; i < byTop.length; i++) {
            packed[i] = ((long) (this.boxes.get(i).y() - lowest) << 32) | (i & 0xFFFFFFFFL);
        }
        java.util.Arrays.sort(packed);
        for (int i = 0; i < packed.length; i++) {
            byTop[i] = (int) packed[i];
        }
    }

    /**
     * Whether a label of this width at this position would be drawn over a node that is not its owner.
     *
     * @param owner  the index in the boxes list of the node the label belongs to, skipped rather than
     *               tested: a label sits below its own node by construction, and one that overlapped it
     *               would be a layout fault rather than a collision
     * @param textX  the label's left edge
     * @param textY  its top edge; the backdrop is drawn one pixel above this and the text nine tall
     * @param width  the width the label was drawn at
     */
    public boolean over(int owner, int textX, int textY, int width) {
        int right = textX + width;
        int bottom = textY + LABEL_HEIGHT;

        // The window, in the sorted order: every box whose top edge can reach into the label's band.
        // The upper end is the label's last row, not its bottom edge: a box starting exactly at the
        // bottom has no pixel in common with a nine-pixel-tall label, and taking the edge rather than
        // the last row would test one box too many for every label.
        int from = firstTopAbove(textY - tallest);
        int to = firstTopAbove(bottom - 1);
        for (int i = from; i < to; i++) {
            Box box = boxes.get(byTop[i]);
            if (box.y() + box.size() <= textY) {
                // Ends above the label's top, so it cannot reach the band: the lower bound is a window
                // rather than a filter, and this is the case it deliberately lets through.
                continue;
            }
            if (byTop[i] != owner && textX < box.x() + box.size() && right > box.x()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The first index in the sorted order whose box starts strictly below {@code top}.
     *
     * <p>Strictly, because a box starting exactly at the label's bottom edge has one pixel of overlap
     * with a label drawn at nine pixels tall — the same half-open arithmetic {@code Slot.contains} and
     * {@code fill} use, and the same one the version this replaces used.
     */
    private int firstTopAbove(int top) {
        int low = 0;
        int high = byTop.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (boxes.get(byTop[mid]).y() <= top) {
                low = mid + 1;
            }
            else {
                high = mid;
            }
        }
        return low;
    }
}
