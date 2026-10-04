package dev.ellipog.tasked.client;

/**
 * How much of one chapter is done, as a pair and as a percentage.
 *
 * <h2>One answer, three readers</h2>
 *
 * <p>The header's summary, the sidebar row's ring and that row's tooltip are the same fact drawn
 * three ways — "12 of 20", a ring a fifth short of full, and a sentence — and they can all be on
 * screen in one frame. If each computed its own fraction they would eventually disagree, and the
 * disagreement would read as one of them being wrong rather than as two sums of the same thing
 * having drifted apart. So the arithmetic lives here, once, and the drawings are passed a number.
 *
 * <h2>No quests is not zero percent</h2>
 *
 * <p>A chapter between being created and its first quest has nothing to be a fraction of: 0/20 is
 * "none done", 0/0 is "nothing here", and drawing the second as 0% says the player has failed to do
 * something that does not exist. {@link #percent()} answers -1 for that case, and the callers omit
 * the number rather than draw it.
 *
 * @param done  how many of the chapter's quests are finished
 * @param total how many it holds, hidden flags resolved the way the canvas resolves them
 */
public record ChapterProgress(int done, int total) {

    /** A chapter holding no quests. The state between creating a chapter and writing its first quest. */
    public static final ChapterProgress EMPTY = new ChapterProgress(0, 0);

    /** Whether the chapter holds no quests at all, so it has no completion to speak of. */
    public boolean isEmpty() {
        return total == 0;
    }

    /**
     * The completion this pair is, 0..100, or -1 when the chapter holds no quests.
     *
     * <p>Rounded rather than truncated, so 2/3 is 67 beside a ring drawn at two thirds — the number
     * and the drawing are read together, and a percent that always undershot would look like the
     * ring was ahead of the text.
     */
    public int percent() {
        return total == 0 ? -1 : Math.round(100F * done / total);
    }

    /**
     * The same share as a float, 0..1, for a drawing that fills a shape rather than prints a number.
     *
     * <p>Zero for an empty chapter, where {@link #percent()} answers -1: a ring has no way to draw
     * "no opinion", and no ink is the honest picture of there being nothing to do.
     */
    public float fraction() {
        return total == 0 ? 0F : (float) done / total;
    }
}
