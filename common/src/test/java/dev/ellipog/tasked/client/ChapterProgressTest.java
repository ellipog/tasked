package dev.ellipog.tasked.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fraction and the percentage, and the empty chapter's -1.
 *
 * <h2>Why this is worth a file of its own</h2>
 *
 * <p>Because two drawings and a tooltip read the same pair, and "70%" has exactly one right answer.
 * The rounding is the part that goes wrong quietly: the header and the ring sit in one frame, so a
 * number that wayside-truncated while the ring rounded would read as the ring being ahead of the
 * text. Both are asserted against the record rather than against each other.
 */
@DisplayName("ChapterProgress")
class ChapterProgressTest {

    @Test
    @DisplayName("percent is the rounded share done, from none to all")
    void percentIsTheRoundedShare() {
        assertEquals(0, new ChapterProgress(0, 20).percent());
        assertEquals(5, new ChapterProgress(1, 20).percent());
        assertEquals(70, new ChapterProgress(14, 20).percent());
        assertEquals(100, new ChapterProgress(20, 20).percent());
        assertEquals(33, new ChapterProgress(1, 3).percent(), "one third rounds down");
        assertEquals(67, new ChapterProgress(2, 3).percent(), "two thirds rounds up");
    }

    @Test
    @DisplayName("a chapter with no quests has no percentage, not a zero")
    void emptyIsNotZero() {
        ChapterProgress empty = ChapterProgress.EMPTY;
        assertTrue(empty.isEmpty());
        assertEquals(-1, empty.percent(),
                "0/0 is 'nothing here'; drawing it as 0% blames the player for absent quests");
        assertEquals(0, new ChapterProgress(0, 1).percent(), "while 0/1 really is none done");
        assertFalse(new ChapterProgress(0, 1).isEmpty());
    }

    @Test
    @DisplayName("the fraction is the same share the ring fills, and zero for an empty chapter")
    void fractionMatchesThePercent() {
        assertEquals(0.7F, new ChapterProgress(14, 20).fraction(), 1e-6F);
        assertEquals(1F, new ChapterProgress(20, 20).fraction(), 1e-6F);
        assertEquals(0F, ChapterProgress.EMPTY.fraction(), 1e-6F);
    }
}
