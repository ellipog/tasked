package dev.ellipog.tasked.quest.reward;

import dev.ellipog.tasked.quest.ItemRef;

import java.util.Optional;

/**
 * What a reward gives, in the shape a client can draw.
 *
 * <p>Structurally the same as {@link dev.ellipog.tasked.quest.task.TaskDisplay} and deliberately
 * not the same record. They are two different questions — "what does this ask for" and "what does
 * this give" — and merging them would mean a change to how a task is displayed could quietly change
 * how a reward is, which is exactly the sort of coupling that produces a bug nobody can locate.
 *
 * <p>The label is a translation key plus an English fallback, for the reason given on
 * {@code TaskDisplay}: item rewards manage with an id alone because the client can look the item up,
 * and experience has nothing to look up.
 *
 * @param item          the item to draw, if this reward gives one
 * @param label         a translation key, or literal text when {@code labelFallback} is empty
 * @param labelFallback English text, used when {@code label} has no translation
 * @param labelArg      the sentence's subject, as the key's argument — the stage, the table, the
 *                      command. Empty for a type whose sentence counts something instead, where the
 *                      count is the argument.
 * @param count         how many, for a {@code x8} after the name
 */
public record RewardDisplay(Optional<ItemRef> item, String label, String labelFallback, String labelArg,
                            int count) {

    public static final RewardDisplay NONE = new RewardDisplay(Optional.empty(), "", "", "", 1);

    public RewardDisplay {
        item = item == null ? Optional.empty() : item;
        label = label == null ? "" : label;
        labelFallback = labelFallback == null ? "" : labelFallback;
        labelArg = labelArg == null ? "" : labelArg;
    }

    public static RewardDisplay ofItem(ItemRef item) {
        return new RewardDisplay(Optional.of(item), "", "", "", Math.max(1, item.count()));
    }

    /** A reward whose sentence counts something: the key is formatted with {@code count}. */
    public static RewardDisplay ofTranslatableText(String key, String fallback, int count) {
        return new RewardDisplay(Optional.empty(), key, fallback, "", Math.max(1, count));
    }

    /** A reward whose sentence names something: the key is formatted with {@code labelArg}. */
    public static RewardDisplay ofTranslatableText(String key, String fallback, String labelArg, int count) {
        return new RewardDisplay(Optional.empty(), key, fallback, labelArg, Math.max(1, count));
    }
}
