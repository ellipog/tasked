package dev.ellipog.tasked.client;

import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rewards inbox's arithmetic: which rows exist, how tall they are, and where a row's button lands.
 *
 * <h2>Why this is asserted and not eyeballed</h2>
 *
 * <p>The drawing, the strip placement and the header's toggle hit test all read the same {@link Layout},
 * so an indent that is off by a pixel is not a cosmetic fault — it is a row whose button is drawn where
 * it cannot be pressed, or a press that folds a row the player was pointing beside. One derivation,
 * asserted over real rectangles, is what keeps them the same derivation; no client is needed, because
 * the rows are integers.
 */
@DisplayName("the rewards inbox's layout")
class RewardInboxLayoutTest {

    private static final Measure MEASURE = Measure.monospace(6, 9);

    /** A column wide enough for a header, an indented reward and a strip, so the arithmetic has room. */
    private static final int COLUMN = 288;

    private static RewardInboxLayout.Row header(String questId) {
        return RewardInboxLayout.Row.quest(questId, "A Quest", 2);
    }

    private static RewardInboxLayout.Row reward(String questId, int index) {
        return RewardInboxLayout.Row.reward(questId, index, "An Item", 3,
                RewardInboxLayout.Status.READY);
    }

    @Test
    @DisplayName("a collapsed quest is its header alone, and the gap closes up behind it")
    void collapsedIsTheHeaderAlone() {
        Layout one = RewardInboxLayout.build(List.of(header("a")), COLUMN, MEASURE);
        assertEquals(RewardInboxLayout.HEADER_HEIGHT, one.height(), "one header, one row's height");

        Layout two = RewardInboxLayout.build(List.of(header("a"), header("b")), COLUMN, MEASURE);
        assertEquals(RewardInboxLayout.HEADER_HEIGHT * 2 + RewardInboxLayout.SECTION_GAP, two.height(),
                "two quests are separated by the section gap, and a fold leaves no gap behind");
    }

    @Test
    @DisplayName("an expanded quest's rewards sit under it, indented, one row gap apart")
    void expandedRewardsAreIndented() {
        Layout layout = RewardInboxLayout.build(
                List.of(header("a"), reward("a", 0), reward("a", 1)), COLUMN, MEASURE);

        Slot head = layout.slot(RewardInboxLayout.questKey("a"));
        Slot first = layout.slot(RewardInboxLayout.rewardKey("a", 0));
        Slot second = layout.slot(RewardInboxLayout.rewardKey("a", 1));

        assertEquals(0, head.x(), "a header starts at the column's left edge");
        assertEquals(RewardInboxLayout.REWARD_INDENT, first.x(),
                "a reward is indented under the header it belongs to");
        assertEquals(RewardInboxLayout.HEADER_HEIGHT + RewardInboxLayout.ROW_GAP, first.y(),
                "and the first sits directly under the header");
        assertEquals(first.bottom() + RewardInboxLayout.ROW_GAP, second.y(),
                "rewards are packed at the row gap");
        assertEquals(head.right(), first.right(),
                "the indent narrows the left edge and nothing else");
    }

    @Test
    @DisplayName("every row's strip is at the column's right edge, outside the row and inside the column")
    void stripsSitAtTheColumnsEdge() {
        Layout layout = RewardInboxLayout.build(List.of(header("a"), reward("a", 0)), COLUMN, MEASURE);

        for (String key : List.of(RewardInboxLayout.questKey("a"),
                RewardInboxLayout.rewardKey("a", 0))) {
            Slot row = layout.slot(key);
            Slot strip = RewardInboxLayout.strip(row);
            assertEquals(key, strip.key(),
                    "the strip answers to its row's key -- it is the row's control, placed by the row");
            assertTrue(strip.x() >= row.right(), "the strip is outside the row's narrowed slot");
            assertEquals(COLUMN - RewardInboxLayout.STRIP_INSET, strip.right(),
                    "and its outer edge is the column's, inset by the same amount on every row");
            assertEquals(row.y(), strip.y(), "the strip shares its row's line");
            assertEquals(row.height(), strip.height(), "and its height");
        }
    }

    @Test
    @DisplayName("a row's body is the row itself, which is what a header's toggle is hit-tested in")
    void bodyIsTheRow() {
        Layout layout = RewardInboxLayout.build(List.of(header("a")), COLUMN, MEASURE);
        Slot row = layout.slot(RewardInboxLayout.questKey("a"));
        Slot body = RewardInboxLayout.body(row);

        assertEquals(row.x(), body.x());
        assertEquals(row.right(), body.right());
        assertFalse(RewardInboxLayout.strip(row).contains(body.right() - 1, body.y() + 1),
                "the body ends where the strip begins, so the row's press and its button's cannot "
                        + "overlap -- a click on Claim Quest must never also fold the row");
    }

    @Test
    @DisplayName("a row's key names its quest and reward; its status decides whether it can be pressed")
    void keysAndStatuses() {
        assertEquals("quest:a", RewardInboxLayout.questKey("a"));
        assertEquals("reward:a:2", RewardInboxLayout.rewardKey("a", 2));

        assertTrue(reward("a", 0).pressable(), "a ready row has something to take");
        assertTrue(header("a").pressable(), "and a listed header's claim is live");
        assertFalse(RewardInboxLayout.Row.reward("a", 0, "x", 1,
                RewardInboxLayout.Status.LOCKED).pressable(), "a gated row does not");
        assertFalse(RewardInboxLayout.Row.reward("a", 0, "x", 1,
                RewardInboxLayout.Status.CLAIMED).pressable(), "nor a collected one");
        assertTrue(RewardInboxLayout.Row.reward("a", 0, "x", 1,
                RewardInboxLayout.Status.PENDING).collected(),
                "and a press still in flight reads as collected, which is the optimistic half");
    }
}
