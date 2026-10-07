package dev.ellipog.tenet.client.dev;

import java.util.ArrayList;
import java.util.List;

/**
 * The book's own transient messages: what the screen says, said where the reader can see it.
 *
 * <h2>Why chat is not enough</h2>
 *
 * <p>Every sentence the screen produces -- an op refused, a row moved, a claim that found nothing -- went to
 * chat, and chat is not on screen while a screen is open: the HUD is not drawn behind one. So the one place
 * those messages matter most, the quest book itself, was the one place they could not be read. A toast is
 * the same sentence drawn on the book, gone after a few seconds, and chat keeps its copy for a player who
 * is not looking at the book.
 *
 * <p>Game-free bookkeeping and nothing else: the caller supplies the time and measures its own text. The
 * order is oldest first, so the drawing can anchor the newest at the bottom and let the ones already being
 * read move up rather than jump.
 */
public final class ToastStack {

    /** How long a message stays, in milliseconds. Long enough to read a sentence, short enough to go. */
    public static final long LIFETIME_MILLIS = 4500;

    /** How much of the life is spent at full strength before it starts to fade. */
    public static final float FADE_FROM = 0.7F;

    /** How many are kept at once. The oldest goes when one more arrives. */
    public static final int MAX = 4;

    /** One sentence, and when it was said. */
    public record Toast(String text, boolean error, long bornAt) {

        /**
         * How visible it is now: full until {@link #FADE_FROM} of its life, then down to nothing.
         *
         * <p>The motion-aware form is the one the book draws with: with Motion off the sentence is
         * simply there and then gone, because a fade is movement and the switch exists for people who
         * cannot comfortably use it. The one-argument form keeps the old behaviour for callers with no
         * opinion -- tests, and the book's own future ones.
         */
        public float alpha(long now, boolean motion) {
            long age = Math.max(0L, now - bornAt);
            if (age >= LIFETIME_MILLIS) {
                return 0F;
            }
            if (!motion) {
                return 1F;
            }
            float elapsed = (float) age / LIFETIME_MILLIS;
            return elapsed <= FADE_FROM ? 1F : 1F - (elapsed - FADE_FROM) / (1F - FADE_FROM);
        }

        /** {@link #alpha(long, boolean)} with motion on. */
        public float alpha(long now) {
            return alpha(now, true);
        }
    }

    private final List<Toast> toasts = new ArrayList<>();

    /**
     * Says something, now.
     *
     * <p>Bounded rather than unbounded: a stack that grew with every message would be a stack the drawing
     * has to walk for as long as the book is open, and a column of notices taller than the card is a column
     * nobody reads. The oldest goes -- it has had its time.
     */
    public void add(String text, boolean error, long now) {
        if (text == null || text.isBlank()) {
            return;
        }
        toasts.add(new Toast(text, error, now));
        while (toasts.size() > MAX) {
            toasts.remove(0);
        }
    }

    /** Drops what has faded out. Called every tick; cheap when there is nothing to drop. */
    public void expire(long now) {
        toasts.removeIf(toast -> toast.alpha(now) <= 0F);
    }

    /** What is still visible, oldest first. */
    public List<Toast> visible(long now) {
        List<Toast> out = new ArrayList<>();
        for (Toast toast : toasts) {
            if (toast.alpha(now) > 0F) {
                out.add(toast);
            }
        }
        return List.copyOf(out);
    }

    /** How many are held, faded or not. For the tests and for nothing else. */
    public int held() {
        return toasts.size();
    }
}
