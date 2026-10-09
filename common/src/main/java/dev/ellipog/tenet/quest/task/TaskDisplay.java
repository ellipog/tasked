package dev.ellipog.tenet.quest.task;

import dev.ellipog.tenet.quest.ItemRef;

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
 * @param item          the item this task is about, if it is about one. Never the author's
 *                      picture: an override travels in {@code picture} or {@code textureIcon}, so a
 *                      row's recipe lookup reads what the task needs rather than what it wears.
 *                      An item task's requirement; empty for every other type.
 * @param label         a translation key, or literal text when {@code labelFallback} is empty
 * @param labelFallback English text to use if {@code label} is a key with no translation
 * @param labelArg      the sentence's subject, as the key's argument -- the biome, the stage, the
 *                      entity. Empty for a type whose sentence counts something instead, where the
 *                      count is the argument.
 * @param count         how much counts as done, for a {@code 5 / 8} against live progress
 * @param textureIcon   the texture path when the author overrode the picture with a texture, and
 *                      empty otherwise. Drawn through the blit rather than the stack; an entity
 *                      override arrives here already resolved to its egg (or the type's own picture
 *                      when it has none), because the wire carries pictures, not registries.
 * @param picture       the author's item picture when the author overrode the picture with an item
 *                      or an entity egg, and empty otherwise. Drawn instead of {@code item}; never
 *                      read for recipes, progression, or viewer indexes, which all read {@code item}
 *                      and the tag the wire carries beside it.
 */
public record TaskDisplay(Optional<ItemRef> item, String label, String labelFallback, String labelArg, int count,
                          String textureIcon, Optional<ItemRef> picture) {

    public static final TaskDisplay NONE = new TaskDisplay(Optional.empty(), "", "", "", 1, "", Optional.empty());

    public TaskDisplay {
        item = item == null ? Optional.empty() : item;
        label = label == null ? "" : label;
        labelFallback = labelFallback == null ? "" : labelFallback;
        labelArg = labelArg == null ? "" : labelArg;
        textureIcon = textureIcon == null ? "" : textureIcon;
        picture = picture == null ? Optional.empty() : picture;
    }

    /** A task about an item, drawn with the item's own name. */
    public static TaskDisplay ofItem(ItemRef item, int count) {
        return new TaskDisplay(Optional.of(item), "", "", "", Math.max(1, count), "", Optional.empty());
    }

    /** A task with no item, drawn with this text. Literal — a key with no translation. */
    public static TaskDisplay ofText(String text, int count) {
        return new TaskDisplay(Optional.empty(), text, "", "", Math.max(1, count), "", Optional.empty());
    }

    /**
     * A task with no item, drawn translatably, with the count as the key's argument.
     *
     * <p>For the types whose sentence is about a number — experience, fluid — where the key is written
     * with one. A type whose sentence names something (a biome, a stage) passes that instead; see the
     * five-argument form, and the note on {@code labelArg} for why the difference is not cosmetic.
     */
    public static TaskDisplay ofTranslatableText(String key, String fallback, int count) {
        return new TaskDisplay(Optional.empty(), key, fallback, "", Math.max(1, count), "", Optional.empty());
    }

    /** A task with no item, whose sentence names something: the key is formatted with {@code labelArg}. */
    public static TaskDisplay ofTranslatableText(String key, String fallback, String labelArg, int count) {
        return new TaskDisplay(Optional.empty(), key, fallback, labelArg, Math.max(1, count), "", Optional.empty());
    }

    /** This display wearing the author's words instead of the type's own sentence. */
    public TaskDisplay withAuthorTitle(dev.ellipog.tenet.quest.QuestText title) {
        return new TaskDisplay(item, title.value(), title.fallback().orElse(""), labelArg, count,
                textureIcon, picture);
    }

    /**
     * This display wearing the author's item alongside the type's own requirement.
     *
     * <p>Keeps {@code item} — an item task still needs what it needed — and carries the picture in
     * {@code picture}, which is what the row draws. The old form replaced the requirement with the
     * picture, so a tag task wearing an oak log opened oak-log recipes instead of the tag, and a
     * checkmark wearing a torch gained recipes it never asked for.
     */
    public TaskDisplay withAuthorItem(ItemRef ref) {
        return new TaskDisplay(item, label, labelFallback, labelArg, count, "", Optional.of(ref));
    }

    /**
     * This display wearing the author's texture alongside the type's own requirement.
     *
     * <p>Keeps {@code item} for the same reason the item arm does: a texture is a picture, and an
     * item task wearing one still needs its item. The old form emptied the requirement, so an item
     * task wearing a texture lost its recipes entirely.
     */
    public TaskDisplay withAuthorTexture(String path) {
        return new TaskDisplay(item, label, labelFallback, labelArg, count, path, Optional.empty());
    }
}
