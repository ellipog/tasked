package dev.ellipog.tasked.client;

import dev.ellipog.armature.client.ui.kit.Insets;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Stack;

import java.util.List;
import java.util.Objects;

/**
 * The rewards inbox's composition: a quest's header, its rewards indented under it, and where each
 * row's Claim goes.
 *
 * <h2>Why this is a layout and not a panel</h2>
 *
 * <p>The same reason every other list in this codebase is one: everything worth being wrong about is
 * arithmetic on integers — does a reward row indent under the right header, does a row's strip fit
 * its row, does a collapsed quest contribute only its header — and arithmetic is the thing the tests
 * can hold without a client. The screen takes a {@link Layout} from here and asks it where each row
 * went; the drawing and the presses are the screen's, because neither can happen without a game.
 *
 * <h2>The accordion, as rows rather than as a widget tree</h2>
 *
 * <p>A collapsed quest is a caller that built its row list without that quest's reward rows — the
 * same fold {@code QuestPanelLayout} uses for its sections, and the reason the gaps go <i>before</i>
 * each row: a fold leaves no stray gap where the folded rows used to be. Expansion state is the
 * caller's, never stored here, so a rebuild for a scroll or a resize cannot lose it.
 */
public final class RewardInboxLayout {

    /** A quest's header row: chevron, icon, title, and the strip's Claim Quest. */
    public static final int HEADER_HEIGHT = 18;

    /** A reward's row, indented under its header: icon, name, count, and the strip's Claim. */
    public static final int REWARD_HEIGHT = 18;

    /** Air between one quest's block and the next. */
    public static final int SECTION_GAP = 7;

    /** Air between two reward rows of one quest. */
    public static final int ROW_GAP = 1;

    /** How far a reward row is indented under the header it belongs to. */
    public static final int REWARD_INDENT = 14;

    /** How wide a row's action is, and its inset from the column's right edge. */
    public static final int STRIP_WIDTH = 96;
    public static final int STRIP_INSET = 2;

    /** The key a quest's header row is placed and found by. */
    public static String questKey(String questId) {
        return "quest:" + questId;
    }

    /** The key a reward row is placed and found by. */
    public static String rewardKey(String questId, int rewardIndex) {
        return "reward:" + questId + ":" + rewardIndex;
    }

    private RewardInboxLayout() {
    }

    /** Which of the three shapes a row is. */
    public enum Kind {
        /** A quest with more than one reward: the accordion's handle, with its own Claim Quest. */
        QUEST,
        /**
         * A quest whose definition holds exactly one reward: one row, the reward drawn inline, one
         * Claim.
         *
         * <p>Decided from the <b>definition</b> and never from what is left outstanding. A quest that
         * collapsed from an accordion to a single row the moment a child was claimed would move every
         * row below it — under a pointer that is usually already moving towards the next one.
         */
        SINGLE,
        /** One reward of an expanded quest, with its own Claim. */
        REWARD
    }

    /**
     * Where a row stands.
     *
     * <p>{@code PENDING} is the optimistic half of a press: the client marks the row collected the
     * moment it asks, and the server's sync either confirms it (the row becomes {@code CLAIMED}) or
     * refuses it (the row becomes {@code READY} again). The two are drawn alike on purpose — the
     * player pressed once and should see one thing — and the server stays the authority either way.
     */
    public enum Status {
        READY,
        LOCKED,
        CLAIMED,
        PENDING
    }

    /**
     * One row of the inbox.
     *
     * <p>{@code count} is how many the row's action would hand over — a reward's own number, or for a
     * header the number of rewards waiting inside. The label is already composed by the caller (the
     * client's resolved reward text), because turning an id into a name is a question for the cache
     * and not for arithmetic.
     */
    public record Row(String key, Kind kind, String questId, int rewardIndex, String label, int count,
                      Status status) {

        public Row {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(questId, "questId");
            Objects.requireNonNull(status, "status");
            label = label == null ? "" : label;
        }

        /** A quest's header. Its own count is how many rewards wait inside. */
        public static Row quest(String questId, String label, int count) {
            return new Row(questKey(questId), Kind.QUEST, questId, -1, label, count, Status.READY);
        }

        /**
         * A quest with exactly one reward: the quest's title, and the reward drawn inline.
         *
         * <p>{@code rewardIndex} is the reward's own position in the quest definition, stored rather
         * than assumed to be zero: the row model does not get to know what the definition holds.
         */
        public static Row single(String questId, String label, int rewardIndex, int count, Status status) {
            return new Row(questKey(questId), Kind.SINGLE, questId, rewardIndex, label, count, status);
        }

        /** One reward of an expanded quest. */
        public static Row reward(String questId, int rewardIndex, String label, int count, Status status) {
            return new Row(rewardKey(questId, rewardIndex), Kind.REWARD, questId, rewardIndex, label,
                    count, status);
        }

        /** Whether this row is the accordion's handle — the only kind that folds. */
        public boolean expands() {
            return kind == Kind.QUEST;
        }

        /** Whether this row's action has anything to take. Only a ready row's button is live. */
        public boolean pressable() {
            return status == Status.READY;
        }

        /** Whether this row reads as collected: the server said so, or the client is asking. */
        public boolean collected() {
            return status == Status.CLAIMED || status == Status.PENDING;
        }

        /** Whether this row is a reward indented under a header. */
        public boolean isReward() {
            return kind == Kind.REWARD;
        }
    }

    /**
     * The rows as an unbuilt stack.
     *
     * <p>A header's gap is the section gap and a reward's is the row gap, so a collapsed quest's
     * neighbours close up exactly as far as the rows that went away. A reward row reserves its indent
     * on the left and its action's room on the right, which is what makes the indentation a property
     * of the slot rather than a correction at each of the drawing, the strip and the hit test.
     */
    public static Stack stack(List<Row> rows) {
        Objects.requireNonNull(rows, "rows");
        Stack stack = Stack.stack();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (i > 0) {
                stack.gap(row.isReward() ? ROW_GAP : SECTION_GAP);
            }
            if (row.isReward()) {
                stack.row(row.key(), REWARD_HEIGHT, stripRoom(REWARD_INDENT));
            }
            else {
                // A header and a single row are the same shape: one line, a strip, no indent.
                stack.row(row.key(), HEADER_HEIGHT, stripRoom(0));
            }
        }
        return stack;
    }

    /** The rows laid out into a column of the given width. */
    public static Layout build(List<Row> rows, int width, Measure measure) {
        Objects.requireNonNull(measure, "measure");
        return stack(rows).build(Math.max(0, width), measure);
    }

    /** The room a row reserves for its action: the strip plus its inset on each side. */
    private static Insets stripRoom(int indent) {
        return new Insets(indent, 0, STRIP_WIDTH + STRIP_INSET * 2, 0);
    }

    /**
     * Where a row's action goes: in the room its insets reserved, against the column's right edge.
     *
     * <p>The row slot is narrowed by its insets, so the strip is <i>outside</i> the slot's right edge
     * — the placement {@code ToolsLayout.strip} uses, and the one every row control in this UI reads
     * as: a button at the list's edge, with the label owning what is left of the line.
     */
    public static Slot strip(Slot row) {
        Objects.requireNonNull(row, "row");
        int width = Math.min(STRIP_WIDTH, Math.max(0, row.width()));
        return new Slot(row.key(), row.right() + STRIP_INSET, row.y(), width, row.height());
    }

    /**
     * A row's own area — everything left of its action.
     *
     * <p>Where a header's toggle is hit-tested. The strip is a widget and answers first, so a press
     * that reaches here is a press on the row and not on its button. Only {@link Kind#QUEST} rows are
     * tested: a single row has nothing to fold.
     */
    public static Slot body(Slot row) {
        Objects.requireNonNull(row, "row");
        return new Slot(row.key(), row.x(), row.y(), row.width(), row.height());
    }
}
