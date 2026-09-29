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
 *
 * <h2>Its description is a list of paragraphs, and that was found the hard way</h2>
 *
 * <p>Not one string. {@link Chapter}'s description has always been a list, and {@link Quest}'s own
 * javadoc argues the point at length — a single string with {@code \n} in it is what FTB Quests takes,
 * and it is why its quest descriptions become one undifferentiated wall of text. That argument does
 * not stop applying one level up.
 *
 * <p>It was a single {@code Optional<QuestText>} until a second shipped questline caught it by being
 * written the obvious way: an array of lines, exactly like a chapter's, failed to decode, and the
 * failure arrived as a codec message at <b>line 1 column 1</b> saying the format had rejected the
 * file. That is the exact failure this project's whole validator exists to prevent — a message that
 * names the wrong place, about a field the validator had never been taught to look at, so it stayed
 * silent and let the codec speak from the root of the file.
 *
 * <p>A bare string is still accepted, so nothing that works today stops working. The union costs one
 * {@code xmap}: a one-line group description written as a string is not a mistake worth failing a
 * file over.
 */
public record ChapterGroup(
        String id,
        QuestText title,
        List<QuestText> description,
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
            // Accepts either shape, and the union is deliberate rather than lazy: a one-line group
            // description written as a bare string is not a mistake worth failing a file over, and
            // `Codec.either` costs one `xmap` to make both spellings work forever.
            Codec.either(QuestText.CODEC, QuestText.CODEC.listOf())
                    .xmap(either -> either.map(List::of, list -> list),
                            list -> list.size() == 1
                                    ? com.mojang.datafixers.util.Either.left(list.get(0))
                                    : com.mojang.datafixers.util.Either.right(list))
                    .optionalFieldOf("description", List.of())
                    .forGetter(ChapterGroup::description),
            Codec.STRING.listOf().optionalFieldOf("aliases", List.of()).forGetter(ChapterGroup::aliases),
            Chapter.CODEC.listOf().optionalFieldOf("chapters", List.of()).forGetter(ChapterGroup::chapters)
    ).apply(instance, ChapterGroup::new));
}
