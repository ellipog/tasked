package dev.ellipog.tenet.quest.condition;

import dev.ellipog.tenet.quest.ItemRef;

import java.util.Optional;

/**
 * What a condition asks for, in the shape a client can draw.
 *
 * <p>The condition half of {@link dev.ellipog.tenet.quest.task.TaskDisplay}, and the same reason for
 * existing: a client that read a condition directly would have to know every type's shape, which would
 * put the addon API on both sides of the wire. The type answers this question instead.
 *
 * <p>Two ways to draw a line, as a task has: <b>with an item</b> ({@code tenet:item}), where the
 * client resolves the id against its own registry and draws the item's real name and count; or <b>with
 * text</b>, where the label travels as a translation key plus an English fallback.
 *
 * <p>One difference from a task display is deliberate: there is no count here. A task's count is drawn
 * as a {@code 5 / 8} chip against live progress, and a condition has no progress to draw — so a
 * sentence whose meaning includes a number (a score's minimum, a party's size, a tag's count) carries
 * it in {@link #labelArg}, which is the sentence's subject. The item form gets its count from the
 * {@link ItemRef} itself.
 *
 * @param item          the item to draw, if this condition is about one
 * @param label         a translation key, or literal text when {@code labelFallback} is empty
 * @param labelFallback English text to use if {@code label} is a key with no translation
 * @param labelArg      the sentence's subject, as the key's argument
 */
public record ConditionDisplay(Optional<ItemRef> item, String label, String labelFallback, String labelArg) {

    public static final ConditionDisplay NONE = new ConditionDisplay(Optional.empty(), "", "", "");

    public ConditionDisplay {
        item = item == null ? Optional.empty() : item;
        label = label == null ? "" : label;
        labelFallback = labelFallback == null ? "" : labelFallback;
        labelArg = labelArg == null ? "" : labelArg;
    }

    /** A condition about an item, drawn with the item's own name and count. */
    public static ConditionDisplay ofItem(ItemRef item) {
        return new ConditionDisplay(Optional.of(item), "", "", "");
    }

    /** A condition with no item, drawn with this text. Literal — a key with no translation. */
    public static ConditionDisplay ofText(String text) {
        return new ConditionDisplay(Optional.empty(), text, "", "");
    }

    /** A condition with no item, whose sentence names something: the key is formatted with the arg. */
    public static ConditionDisplay ofTranslatableText(String key, String fallback, String labelArg) {
        return new ConditionDisplay(Optional.empty(), key, fallback, labelArg);
    }
}
