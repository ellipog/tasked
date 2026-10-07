package dev.ellipog.tasked.quest;

import com.mojang.serialization.Codec;

/**
 * A reference to a chapter by id, from a chapter's {@code dependsOn} list.
 *
 * <p>Deliberately not a {@link QuestRef}, and the reason is the one that record's own note gives:
 * {@code ChapterRef} exists so that a reference cannot be confused with any other reference at a call
 * site. That matters more here than it does one level down, because <b>a chapter and a quest may
 * legally share an id</b> — {@link QuestIndex} keys them in separate tables and says so — so a single
 * "dependency" type holding both would compile for every wrong pairing there is.
 *
 * <p>Resolved against both ids and aliases, exactly as a quest reference is, so renaming a chapter
 * does not orphan the chapters that waited on it.
 */
public record ChapterRef(String id) {

    public static final Codec<ChapterRef> CODEC = Codec.STRING.xmap(ChapterRef::new, ChapterRef::id);

    @Override
    public String toString() {
        return id;
    }
}
