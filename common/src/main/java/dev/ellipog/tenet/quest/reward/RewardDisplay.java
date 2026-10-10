package dev.ellipog.tenet.quest.reward;

import dev.ellipog.tenet.quest.ItemRef;

import java.util.Optional;

/**
 * What a reward gives, in the shape a client can draw.
 *
 * <p>Structurally the same as {@link dev.ellipog.tenet.quest.task.TaskDisplay} and deliberately
 * not the same record. They are two different questions — "what does this ask for" and "what does
 * this give" — and merging them would mean a change to how a task is displayed could quietly change
 * how a reward is, which is exactly the sort of coupling that produces a bug nobody can locate.
 *
 * <p>The label is a translation key plus an English fallback, for the reason given on
 * {@code TaskDisplay}: item rewards manage with an id alone because the client can look the item up,
 * and experience has nothing to look up.
 *
 * @param item          the item this reward gives, if it gives one. Never the author's
 *                      picture: an override travels in {@code picture} or {@code textureIcon}, so a
 *                      row's recipe lookup reads what the reward pays rather than what it wears.
 *                      An item reward's payout; empty for every other type.
 * @param label         a translation key, or literal text when {@code labelFallback} is empty
 * @param labelFallback English text, used when {@code label} has no translation
 * @param labelArg      the sentence's subject, as the key's argument — the stage, the table, the
 *                      command. Empty for a type whose sentence counts something instead, where the
 *                      count is the argument.
 * @param count         how many, for a {@code x8} after the name
 * @param textureIcon   the texture path when the author overrode the picture with a texture, and
 *                      empty otherwise. See {@link dev.ellipog.tenet.quest.task.TaskDisplay} for why
 *                      an entity override arrives resolved to its egg.
 * @param spriteIcon    the atlas region when the author overrode the picture with a sprite, and
 *                      empty otherwise. Never beside a texture: one override at a time.
 * @param picture       the author's item picture when the author overrode the picture with an item
 *                      or an entity egg, and empty otherwise. Drawn instead of {@code item}; never
 *                      read for recipes or viewer indexes, which all read {@code item}.
 */
public record RewardDisplay(Optional<ItemRef> item, String label, String labelFallback, String labelArg,
                            int count, String textureIcon, String spriteIcon, Optional<ItemRef> picture) {

    public static final RewardDisplay NONE = new RewardDisplay(Optional.empty(), "", "", "", 1, "", "", Optional.empty());

    public RewardDisplay {
        item = item == null ? Optional.empty() : item;
        label = label == null ? "" : label;
        labelFallback = labelFallback == null ? "" : labelFallback;
        labelArg = labelArg == null ? "" : labelArg;
        textureIcon = textureIcon == null ? "" : textureIcon;
        spriteIcon = spriteIcon == null ? "" : spriteIcon;
        picture = picture == null ? Optional.empty() : picture;
    }

    public static RewardDisplay ofItem(ItemRef item) {
        return new RewardDisplay(Optional.of(item), "", "", "", Math.max(1, item.count()), "", "", Optional.empty());
    }

    /** A reward whose sentence counts something: the key is formatted with {@code count}. */
    public static RewardDisplay ofTranslatableText(String key, String fallback, int count) {
        return new RewardDisplay(Optional.empty(), key, fallback, "", Math.max(1, count), "", "", Optional.empty());
    }

    /** A reward whose sentence names something: the key is formatted with {@code labelArg}. */
    public static RewardDisplay ofTranslatableText(String key, String fallback, String labelArg, int count) {
        return new RewardDisplay(Optional.empty(), key, fallback, labelArg, Math.max(1, count), "", "", Optional.empty());
    }

    /** This display wearing the author's words instead of the type's own sentence. */
    public RewardDisplay withAuthorTitle(dev.ellipog.tenet.quest.QuestText title) {
        return new RewardDisplay(item, title.value(), title.fallback().orElse(""), labelArg, count,
                textureIcon, spriteIcon, picture);
    }

    /**
     * This display wearing the author's item alongside the type's own payout.
     *
     * <p>Keeps {@code item} — an item reward still pays what it pays — and carries the picture in
     * {@code picture}, which is what the row draws. The old form replaced the payout with the
     * picture, so a reward wearing a torch opened torch recipes instead of its own.
     */
    public RewardDisplay withAuthorItem(ItemRef ref) {
        return new RewardDisplay(item, label, labelFallback, labelArg, count, "", "", Optional.of(ref));
    }

    /**
     * This display wearing the author's texture alongside the type's own payout.
     *
     * <p>Keeps {@code item} for the same reason the item arm does: a texture is a picture, and an
     * item reward wearing one still pays its item. The old form emptied the payout, so an item
     * reward wearing a texture lost its recipes entirely.
     */
    public RewardDisplay withAuthorTexture(String path) {
        return new RewardDisplay(item, label, labelFallback, labelArg, count, path, "", Optional.empty());
    }

    /**
     * This display wearing the author's atlas sprite alongside the type's own payout.
     *
     * <p>Like the texture arm: a picture, so the payout stays what it was, and the other
     * picture arms go — one override at a time, or two pictures would claim one row.
     */
    public RewardDisplay withAuthorSprite(String id) {
        return new RewardDisplay(item, label, labelFallback, labelArg, count, "", id, Optional.empty());
    }
}
