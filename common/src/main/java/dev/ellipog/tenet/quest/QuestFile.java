package dev.ellipog.tenet.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * One file on disk under {@code config/tenet/quests/}.
 *
 * <pre>{@code
 * {
 *   "$schema": "../tenet-quests.schema.json",
 *   "version": 1,
 *   "chapterGroups": [ ... ]
 * }
 * }</pre>
 *
 * <p>{@code version} is 1 and will stay 1 until the format has to change, at which point it becomes
 * the thing that makes the change survivable: a migration keyed off the value a file declares, rather
 * than guessing from what fields happen to be present. It is written into new files from the first
 * release so that the number is already there when it is needed — a format that adds a version field
 * later has to treat every existing file as "unknown".
 *
 * <p>{@code $schema} is ignored by the codec and is purely for editors: a JSON-Language-Server-aware
 * editor will autocomplete field names and flag typos against it. It is written into new files
 * because that is the only time an author is likely to leave it in place.
 *
 * <p>A kit is not the only unit — a file may hold any number of groups, chapters and quests, and
 * there may be any number of files. Splitting a questline across files is a matter of taste; the
 * loader treats them all as one tree and cross-file dependencies resolve normally.
 */
public record QuestFile(int version, List<ChapterGroup> chapterGroups) {

    /** The format version this build understands. */
    public static final int CURRENT_VERSION = 1;

    public static final QuestFile EMPTY = new QuestFile(CURRENT_VERSION, List.of());

    /** Every quest in the file, flattened across groups and chapters. */
    public List<Quest> allQuests() {
        return chapterGroups.stream()
                .flatMap(group -> group.chapters().stream())
                .flatMap(chapter -> chapter.quests().stream())
                .toList();
    }

    public static final Codec<QuestFile> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("version", 1).forGetter(QuestFile::version),
            ChapterGroup.CODEC.listOf().optionalFieldOf("chapterGroups", List.of()).forGetter(QuestFile::chapterGroups)
    ).apply(instance, QuestFile::new));
}
