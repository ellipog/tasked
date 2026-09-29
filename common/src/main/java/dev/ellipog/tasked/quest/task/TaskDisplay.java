package dev.ellipog.tasked.quest.task;

import dev.ellipog.tasked.quest.ItemRef;

import java.util.Optional;

/**
 * What a task asks for, in the shape a client can draw.
 *
 * <h2>Why this exists rather than the client reading the task</h2>
 *
 * <p>A {@code QuestTask} is decoded by its own type's codec, and its record's fields mean whatever
 * that type decided they mean. A client that read one directly would have to know every task type's
 * shape, which would put the addon API on both sides of the wire and mean a new task type needed a
 * client change to be displayable at all. So the type answers this question instead, and the wire
 * carries only the answer.
 *
 * <h2>Two ways to draw a line</h2>
 *
 * <ul>
 *   <li><b>With an item</b> — {@code item} present, {@code label} empty. The client resolves the id
 *       against its own registry and draws the item's real name, in the player's own language.</li>
 *   <li><b>With text</b> — {@code item} empty, {@code label} set. Used by a checkmark, which has no
 *       item to count.</li>
 * </ul>
 *
 * <h2>Why the label travels as a key <i>and</i> a fallback</h2>
 *
 * <p>Because the server does not know what language the client is in, and must not guess. An item
 * task gets away with sending only an id, since the client can look the name up itself. Text has no
 * such lookup, so it goes as a translation key plus an English fallback — the same arrangement
 * {@code QuestText} uses in the files, and for the same reason: a missing translation must degrade
 * to English rather than to a raw key on screen.
 *
 * @param item          the item to draw, if this task is about one
 * @param label         a translation key, or literal text when {@code labelFallback} is empty
 * @param labelFallback English text to use if {@code label} is a key with no translation
 * @param count         how much counts as done, for a {@code 5 / 8} against live progress
 */
public record TaskDisplay(Optional<ItemRef> item, String label, String labelFallback, int count) {

    public static final TaskDisplay NONE = new TaskDisplay(Optional.empty(), "", "", 1);

    public TaskDisplay {
        item = item == null ? Optional.empty() : item;
        label = label == null ? "" : label;
        labelFallback = labelFallback == null ? "" : labelFallback;
    }

    /** A task about an item, drawn with the item's own name. */
    public static TaskDisplay ofItem(ItemRef item, int count) {
        return new TaskDisplay(Optional.of(item), "", "", Math.max(1, count));
    }

    /** A task with no item, drawn with this text. Literal — a key with no translation. */
    public static TaskDisplay ofText(String text, int count) {
        return new TaskDisplay(Optional.empty(), text, "", Math.max(1, count));
    }

    /** A task with no item, drawn translatably. The fallback is used when the key has no translation. */
    public static TaskDisplay ofTranslatableText(String key, String fallback, int count) {
        return new TaskDisplay(Optional.empty(), key, fallback, Math.max(1, count));
    }
}
