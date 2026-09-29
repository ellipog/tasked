package dev.ellipog.tasked.quest;

import com.mojang.serialization.Codec;

/**
 * A reference to a quest by id, from a {@code dependsOn} list.
 *
 * <p>Just a string, and the codec is just a string pass-through — the record exists so that a
 * dependency cannot be confused with any other string at a call site, which is a mistake the
 * compiler would otherwise be happy to let through.
 *
 * <p>Resolved against both ids and aliases, so renaming a quest does not orphan the quests that
 * depended on it.
 */
public record QuestRef(String id) {

    public static final Codec<QuestRef> CODEC = Codec.STRING.xmap(QuestRef::new, QuestRef::id);

    @Override
    public String toString() {
        return id;
    }
}
