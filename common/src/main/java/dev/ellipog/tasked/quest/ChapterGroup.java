package dev.ellipog.tasked.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.Optional;

/**
 * A shelf of chapters — FTB Quests' "chapter group".
 *
 * <p>Exists so a large pack can say "Tutorial / Progression / Endgame" and put twenty chapters
 * underneath, rather than scrolling one flat list of twenty. The extra level of nesting costs one
 * record and makes the navigation panel readable at scale, which is why FTB Quests grew the same
 * level after not having it.
 */
public record ChapterGroup(
        String id,
        QuestText title,
        Optional<QuestText> description,
        List<String> aliases,
        List<Chapter> chapters
) {

    /** Finds a chapter by id or alias. */
    public Optional<Chapter> chapter(String idOrAlias) {
        return chapters.stream().filter(chapter -> chapter.matches(idOrAlias)).findFirst();
    }

    /** Whether {@code idOrAlias} refers to this group. */
    public boolean matches(String idOrAlias) {
        return id.equals(idOrAlias) || aliases.contains(idOrAlias);
    }

    public static final Codec<ChapterGroup> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(ChapterGroup::id),
            QuestText.CODEC.fieldOf("title").forGetter(ChapterGroup::title),
            QuestText.CODEC.optionalFieldOf("description").forGetter(ChapterGroup::description),
            Codec.STRING.listOf().optionalFieldOf("aliases", List.of()).forGetter(ChapterGroup::aliases),
            Chapter.CODEC.listOf().optionalFieldOf("chapters", List.of()).forGetter(ChapterGroup::chapters)
    ).apply(instance, ChapterGroup::new));
}
