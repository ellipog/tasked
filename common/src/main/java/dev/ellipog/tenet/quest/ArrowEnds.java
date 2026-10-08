package dev.ellipog.tenet.quest;

import com.mojang.serialization.Codec;

import dev.ellipog.armature.api.data.Codecs;

/**
 * Which ends of a line carry an arrowhead.
 *
 * <p>Its own vocabulary rather than the dependency lines' {@code ArrowPlace}, and the difference is not
 * cosmetic. A dependency line's head is placed by what the line <i>means</i> — one at the dependent end,
 * both, one in the middle, a repeating run — so its four values are "the shapes a pointer between two
 * quests can take". A free line on a canvas means nothing; it is an arrow an author drew, and the only
 * question is which end it points at. Four values, no placement, no spacing.
 *
 * <p>That also keeps {@code ArrowPlace} and its three published schemas untouched, which is worth saying
 * because the tempting shortcut — one more value on the existing enum — would have been a canvas concept
 * leaking into the vocabulary of quest dependencies, and would have had to be documented in all three.
 */
public enum ArrowEnds {

    /** A blunt line end. The default, because a plain rule is the common case. */
    NONE,

    /** A head where the line starts. */
    START,

    /** A head where the line ends. */
    END,

    /** A head at each end. */
    BOTH;

    public static final Codec<ArrowEnds> CODEC = Codecs.enumByName(ArrowEnds.class);

    /** Whether this places a head at the first endpoint. */
    public boolean atStart() {
        return this == START || this == BOTH;
    }

    /** Whether this places a head at the second endpoint. */
    public boolean atEnd() {
        return this == END || this == BOTH;
    }
}
